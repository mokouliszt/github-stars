import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---- Signing: keystore.properties (git-ignored) or environment variables. Unsigned if absent. ----
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
fun secret(key: String, env: String): String? = keystoreProps.getProperty(key) ?: System.getenv(env)
val releaseStoreFile = secret("storeFile", "GHSTARS_KEYSTORE")
val hasReleaseSigning = releaseStoreFile != null && rootProject.file(releaseStoreFile).exists()


// ---- Official Codex app-server binary (Apache-2.0), packaged as a native library ----
// Android only allows exec() from the app's native library directory, so the static
// aarch64 musl build is shipped as lib*.so and extracted at install time.
val codexVersion = "0.160.0"
val codexSha256 = "c338ddc74821a1d2cb0fff02bd39b85a752a86ef4efacc9ac8308d9632b0b657"
val codexUrl = "https://github.com/openai/codex/releases/download/rust-v$codexVersion/" +
    "codex-app-server-aarch64-unknown-linux-musl.tar.gz"
val codexTgz = layout.buildDirectory.file("codex/codex-app-server-$codexVersion-aarch64.tar.gz")
val codexJniRoot = layout.buildDirectory.dir("generated/codexJniLibs")
val codexLocal = (project.findProperty("codexBinary") as String?)?.let { file(it) }

fun sha256(f: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    f.inputStream().use { input ->
        val buf = ByteArray(1 shl 16)
        while (true) { val n = input.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

// ---- Laya multilingual (LiteRT) model, shipped inside the APK as uncompressed assets ----
// Source: huggingface.co/litert-community/Laya-Multilingual-LiteRT (Apache-2.0), pinned revision.
val layaRevision = "32f1b84d55f42a323464fad22594cfc2059e7467"
val layaFiles = linkedMapOf(
    "laya_ml_s256_embeds_wfp16.tflite" to "712f2fea6b2c39759b4c46274cd83b7653f2539fedc4d7569e4c7bf903894ac8",
    "token_embeddings_fp16.bin" to "58608f7bbd72e03adb3e2db8c4942407058eaeaac38e3abec1d6cb01c1512265",
    "tokenizer.json" to "609d8f4c067cd3950f88594c5a802616cea245823836ef5848ee4fc40aab5b6f",
    "laya_ml_act_head_fp32.tflite" to "30da532a8b752fdc82351740b0194d440a13d342ed77b9f8848978f1a96cf16d",
    "laya_ml_calibration.json" to "80e148c68154b607e7bb66446f064b1fe5da649e1074c917b25e02551a566f2c",
    "token_embeddings.json" to "8b25844d858ace3833b69354a60536111e9bbcecb2e305bee7971e8dfcbd83ef",
)
// Kept outside build/ so `clean` does not re-download ~680 MB. Git-ignored.
val layaAssetRoot = rootProject.file(".cache/laya-assets")
val layaVerified = rootProject.file(".cache/laya-verified")
val layaLocal = (project.findProperty("layaDir") as String?)?.let { file(it) }

val prepareLaya by tasks.registering {
    description = "Fetches and verifies the Laya LiteRT model files into the asset cache."
    outputs.dir(layaAssetRoot)
    doLast {
        val dir = File(layaAssetRoot, "laya").apply { mkdirs() }
        layaVerified.mkdirs()
        for ((name, sha) in layaFiles) {
            val target = File(dir, name)
            val marker = File(layaVerified, "$name.sha256")
            if (target.isFile && marker.isFile && marker.readText().trim() == sha) continue
            if (layaLocal != null && File(layaLocal, name).isFile) {
                File(layaLocal, name).copyTo(target, overwrite = true)
            } else {
                val url = "https://huggingface.co/litert-community/Laya-Multilingual-LiteRT/resolve/$layaRevision/$name"
                logger.lifecycle("Downloading $name")
                val tmp = File(dir, "$name.part")
                URI(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
                tmp.renameTo(target)
            }
            val actual = sha256(target)
            check(actual == sha) { "Laya file $name checksum mismatch: $actual" }
            marker.writeText(sha)
        }
    }
}

val downloadCodex by tasks.registering {
    description = "Downloads the official Codex app-server release archive."
    val out = codexTgz
    outputs.file(out)
    onlyIf { codexLocal == null }
    doLast {
        val f = out.get().asFile
        if (f.exists() && f.length() > 0) return@doLast
        f.parentFile.mkdirs()
        val tmp = File(f.parentFile, f.name + ".part")
        URI(codexUrl).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        tmp.renameTo(f)
    }
}

val prepareCodex by tasks.registering {
    description = "Places the Codex app-server binary into jniLibs as libcodex_app_server.so."
    dependsOn(downloadCodex)
    val outDir = codexJniRoot.map { it.dir("arm64-v8a") }
    outputs.dir(outDir)
    doLast {
        val target = File(outDir.get().asFile, "libcodex_app_server.so")
        target.parentFile.mkdirs()
        if (codexLocal != null) {
            codexLocal.copyTo(target, overwrite = true)
        } else {
            project.copy {
                from(project.tarTree(project.resources.gzip(codexTgz.get().asFile))) {
                    include("codex-app-server-aarch64-unknown-linux-musl")
                    rename { "libcodex_app_server.so" }
                }
                into(target.parentFile)
            }
        }
        val actual = sha256(target)
        check(actual == codexSha256) { "Codex binary checksum mismatch: $actual" }
    }
}

android {
    namespace = "dev.mokouliszt.githubstars"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.mokouliszt.githubstars"
        minSdk = 26
        // 28 on purpose: Android only lets apps targeting <= 28 execute binaries they download,
        // which the in-app Codex updater needs (see CodexUpdater). Same trade-off as Termux.
        targetSdk = 28
        versionCode = 2
        versionName = "1.1.0"
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("String", "CODEX_VERSION", "\"$codexVersion\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = secret("storePassword", "GHSTARS_STORE_PW")
                keyAlias = secret("keyAlias", "GHSTARS_KEY_ALIAS")
                keyPassword = secret("keyPassword", "GHSTARS_KEY_PW")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    sourceSets["main"].jniLibs.srcDir(codexJniRoot)
    sourceSets["main"].assets.srcDir(layaAssetRoot)

    // Graph and embedding table are memory-mapped straight out of the APK.
    androidResources { noCompress += listOf("tflite", "bin") }

    packaging {
        // Must be real files on disk to be executable.
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libcodex_app_server.so"
            // Laya runs on the CPU only; the GPU accelerator is never loaded.
            excludes += "**/libLiteRtClGlAccelerator.so"
        }
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }

    buildFeatures { buildConfig = true }

    lint {
        // targetSdk 28 is deliberate (see defaultConfig); this app is not distributed via Play.
        disable += "ExpiredTargetSdkVersion"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

tasks.named("preBuild") { dependsOn(prepareCodex, prepareLaya) }

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    // LiteRT runtime for the Laya multilingual decision model (hyper fuzzy search).
    implementation("com.google.ai.edge.litert:litert:2.2.0")
}
