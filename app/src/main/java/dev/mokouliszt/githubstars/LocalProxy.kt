package dev.mokouliszt.githubstars

import android.util.Base64
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Loopback HTTPS (CONNECT) proxy for the bundled Codex binary.
 *
 * The official Codex build is a static musl executable. musl resolves names through
 * /etc/resolv.conf, which does not exist on Android, so every lookup inside Codex fails.
 * Codex honours HTTPS_PROXY/HTTP_PROXY/ALL_PROXY, so it is pointed at this proxy: Codex connects
 * to 127.0.0.1 (no lookup needed) and the proxy resolves the host with Android's resolver.
 * TLS stays end to end between Codex and the remote host; the proxy only relays bytes.
 *
 * Loopback is shared by every app on the device, so the proxy requires a random per-launch
 * credential (sent by Codex as Proxy-Authorization) and only tunnels to port 443.
 */
class LocalProxy {

    private val token: String = ByteArray(18).also { SecureRandom().nextBytes(it) }
        .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }
    private val expected = "Basic " + Base64.encodeToString("gs:$token".toByteArray(), Base64.NO_WRAP)
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "gs-proxy").apply { isDaemon = true } }
    private var server: ServerSocket? = null

    @Synchronized
    fun start(): Int {
        server?.let { if (!it.isClosed) return it.localPort }
        val s = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        server = s
        pool.execute {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                pool.execute { handle(c) }
            }
        }
        return s.localPort
    }

    /** Proxy URL including the credential, for HTTPS_PROXY. */
    fun url(): String = "http://gs:$token@127.0.0.1:${start()}"

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        try {
            client.soTimeout = 15_000
            client.tcpNoDelay = true
            val input = client.getInputStream()
            val out = client.getOutputStream()
            val head = readHead(input) ?: return client.close()
            val lines = head.text.split("\r\n")
            val parts = lines.first().split(" ")
            if (parts.size < 3) return reply(out, "400 Bad Request", client)
            val auth = lines.drop(1).firstOrNull { it.startsWith("proxy-authorization:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()
            if (auth != expected) {
                out.write("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"gs\"\r\nContent-Length: 0\r\n\r\n".toByteArray())
                out.flush(); client.close(); return
            }
            if (!parts[0].equals("CONNECT", ignoreCase = true)) return reply(out, "405 Method Not Allowed", client)
            val target = parts[1]
            val host = target.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
            val port = target.substringAfterLast(':').toIntOrNull() ?: return reply(out, "400 Bad Request", client)
            if (port != 443 || host.isBlank()) return reply(out, "403 Forbidden", client)

            upstream = connect(host, port) ?: return reply(out, "502 Bad Gateway", client)
            out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            out.flush()
            val up = upstream
            if (head.rest.isNotEmpty()) up.getOutputStream().write(head.rest)
            client.soTimeout = 0
            up.soTimeout = 0
            pool.execute { pump(up.getInputStream(), out, up, client) }
            pump(input, up.getOutputStream(), client, up)
        } catch (_: Exception) {
            runCatching { client.close() }
            runCatching { upstream?.close() }
        }
    }

    private fun connect(host: String, port: Int): Socket? {
        val addrs = runCatching { Net.dns.lookup(host) }.getOrNull() ?: return null
        for (a in addrs) {
            val s = Socket()
            try {
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(a, port), 10_000)
                return s
            } catch (_: Exception) {
                runCatching { s.close() }
            }
        }
        return null
    }

    private fun pump(from: InputStream, to: OutputStream, a: Socket, b: Socket) {
        val buf = ByteArray(32 * 1024)
        try {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n)
                to.flush()
            }
        } catch (_: Exception) {
        } finally {
            runCatching { a.close() }
            runCatching { b.close() }
        }
    }

    private class Head(val text: String, val rest: ByteArray)

    /** Reads up to the blank line ending the request head (max 16 KiB). */
    private fun readHead(input: InputStream): Head? {
        val buf = ByteArray(16 * 1024)
        var len = 0
        while (len < buf.size) {
            val n = input.read(buf, len, buf.size - len)
            if (n <= 0) return null
            len += n
            for (i in 3 until len) {
                if (buf[i - 3] == '\r'.code.toByte() && buf[i - 2] == '\n'.code.toByte() &&
                    buf[i - 1] == '\r'.code.toByte() && buf[i] == '\n'.code.toByte()
                ) {
                    return Head(String(buf, 0, i - 3, Charsets.ISO_8859_1), buf.copyOfRange(i + 1, len))
                }
            }
        }
        return null
    }

    private fun reply(out: OutputStream, status: String, c: Socket) {
        runCatching {
            out.write("HTTP/1.1 $status\r\nContent-Length: 0\r\n\r\n".toByteArray())
            out.flush()
        }
        runCatching { c.close() }
    }
}
