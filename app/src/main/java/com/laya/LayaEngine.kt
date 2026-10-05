// SPDX-License-Identifier: Apache-2.0
// Adapted from litert-community/Laya-Multilingual-LiteRT android/app/.../LayaEngine.kt.
// Changes for GitHub Stars: model files ship inside the APK as uncompressed assets (graphs are
// compiled from the AssetManager, the embedding table is memory-mapped straight out of the APK),
// only the S256/WFP16 graph is used, and the app runs it on the CPU only (no GPU/NPU paths,
// no gate timings) because it targets devices without a usable GPU.
package com.laya

import android.content.Context
import android.content.res.AssetManager
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import java.io.Closeable
import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

class LayaEngine(context: Context, private val assetDir: String = "laya") : Closeable {

    private class Graph(
        val model: CompiledModel,
        val inputs: Map<String, TensorBuffer>,
        val outputs: Map<String, TensorBuffer>,
    ) : Closeable {
        override fun close() {
            try {
                (inputs.values + outputs.values).forEach { it.close() }
            } finally {
                model.close()
            }
        }
    }

    private val appContext = context.applicationContext
    private val assets: AssetManager = appContext.assets
    private val embeddings: LayaEmbeddings
    private val tokenizer: LayaTokenizer
    private val calibration: LayaCalibration
    private var main: Graph? = null
    private var act: Graph? = null
    private val embeds = FloatArray(WINDOW * LayaEmbeddings.WIDTH)
    private var closed = false

    /** Threads used by the CPU (XNNPACK) kernels. */
    val threads: Int = Runtime.cpuThreads()

    init {
        tokenizer = assets.open(path("tokenizer.json")).bufferedReader(Charsets.UTF_8).use { LayaTokenizer(it) }
        calibration = assets.open(path("laya_ml_calibration.json")).bufferedReader(Charsets.UTF_8).use {
            LayaCalibration.fromMap(LayaJson.asObject(LayaJson.parse(it)))
        }
        val meta = assets.open(path("token_embeddings.json")).bufferedReader(Charsets.UTF_8).use {
            LayaJson.asObject(LayaJson.parse(it))
        }
        // The table is stored uncompressed (noCompress "bin"), so it can be mapped in place.
        val table = assets.openFd(path("token_embeddings_fp16.bin")).use { afd ->
            FileInputStream(afd.fileDescriptor).channel.use { ch ->
                ch.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.length)
            }
        }
        embeddings = LayaEmbeddings(table, meta)
    }

    /** Compiles both graphs for the CPU. */
    fun initialize() = Runtime.call {
        check(!closed) { "LayaEngine is closed" }
        if (main != null) return@call
        try {
            main = createGraph(MAIN_GRAPH, listOf("attention_mask", "inputs_embeds", "qtype_onehot"),
                listOf("pooled_cls", "token_logits"))
            act = createGraph(ACT_GRAPH, listOf("feats", "pooled_cls"), listOf("act_logits"))
        } catch (t: Throwable) {
            main?.close(); main = null
            act?.close(); act = null
            throw t
        }
    }

    /** Builds, runs and decodes one question. Returns the official answer dictionary. */
    fun answer(state: Any?, question: Map<String, Any?>): Map<String, Any?> {
        val sequence = LayaPromptBuilder(tokenizer, maxLen = WINDOW, headMaxLen = WINDOW).build(state, question)
        return Runtime.call {
            check(!closed) { "LayaEngine is closed" }
            val m = checkNotNull(main) { "initialize() first" }
            val a = checkNotNull(act) { "initialize() first" }
            val attention = FloatArray(WINDOW) { if (it < sequence.ids.size) 1f else 0f }
            val qtype = FloatArray(3).also { it[sequence.question.qtype] = 1f }
            embeddings.gather(sequence.ids, WINDOW, embeds)
            m.inputs.getValue("inputs_embeds").writeFloat(embeds)
            m.inputs.getValue("attention_mask").writeFloat(attention)
            m.inputs.getValue("qtype_onehot").writeFloat(qtype)
            m.model.run(m.inputs, m.outputs, SIGNATURE)
            val tokens = m.outputs.getValue("token_logits").readFloat()
            val pooled = m.outputs.getValue("pooled_cls").readFloat()
            check(tokens.all { it.isFinite() } && pooled.all { it.isFinite() }) { "Nonfinite model output" }
            val markers = LayaDecoder.gather(tokens, sequence.markers)
            a.inputs.getValue("pooled_cls").writeFloat(pooled)
            a.inputs.getValue("feats").writeFloat(LayaDecoder.actFeatures(markers))
            a.model.run(a.inputs, a.outputs, SIGNATURE)
            val action = a.outputs.getValue("act_logits").readFloat()
            LayaDecoder.decode(markers, action, sequence.question, calibration)
        }
    }

    private fun createGraph(name: String, ins: List<String>, outs: List<String>): Graph {
        val options = CompiledModel.Options(Accelerator.CPU).apply {
            cpuOptions = CompiledModel.CpuOptions(numThreads = threads)
        }
        val model = CompiledModel.create(assets, path(name), options, Runtime.environment(appContext))
        val inputs = linkedMapOf<String, TensorBuffer>()
        val outputs = linkedMapOf<String, TensorBuffer>()
        try {
            ins.forEach { inputs[it] = model.createInputBuffer(it, SIGNATURE) }
            outs.forEach { outputs[it] = model.createOutputBuffer(it, SIGNATURE) }
            return Graph(model, inputs, outputs)
        } catch (t: Throwable) {
            (inputs.values + outputs.values).forEach { it.close() }
            model.close()
            throw t
        }
    }

    private fun path(name: String) = "$assetDir/$name"

    override fun close() = Runtime.call {
        if (!closed) {
            closed = true
            try {
                main?.close(); act?.close()
            } finally {
                main = null; act = null
                embeddings.close()
            }
        }
    }

    companion object {
        const val WINDOW = 256
        const val MAIN_GRAPH = "laya_ml_s256_embeds_wfp16.tflite"
        const val ACT_GRAPH = "laya_ml_act_head_fp32.tflite"
        private const val SIGNATURE = "serving_default"
        /** Asset names under assets/laya (verified at build time). */
        val FILES = listOf(
            MAIN_GRAPH, ACT_GRAPH, "laya_ml_calibration.json",
            "tokenizer.json", "token_embeddings_fp16.bin", "token_embeddings.json",
        )
    }

    /** One native thread and one Environment for the process lifetime. */
    private object Runtime {
        private val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "Laya-LiteRT").apply { isDaemon = true }
        }
        private var env: Environment? = null

        fun environment(context: Context): Environment =
            env ?: Environment.create(context).also { env = it }

        /** Up to four threads; the reference measurements used four. */
        fun cpuThreads(): Int = java.lang.Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

        fun <T> call(block: () -> T): T =
            try {
                executor.submit(Callable { block() }).get()
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
    }
}
