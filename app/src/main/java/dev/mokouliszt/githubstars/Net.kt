package dev.mokouliszt.githubstars

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Shared HTTP plumbing.
 *
 * Some carrier resolvers fail on openai/github hosts, so lookups try the system resolver first and
 * fall back to Cloudflare DNS-over-HTTPS (same approach as overlay-ai / Coupodex). The local proxy
 * that serves the Codex binary uses the same resolver.
 */
object Net {

    private val doh: Dns by lazy {
        val bootstrap = OkHttpClient.Builder().build()
        DnsOverHttps.Builder().client(bootstrap)
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("1.0.0.1"))
            .build()
    }

    val dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            try {
                Dns.SYSTEM.lookup(hostname)
            } catch (e: Exception) {
                doh.lookup(hostname)
            }
    }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(dns)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    const val USER_AGENT = "GithubStars-Android/" + BuildConfig.VERSION_NAME
}
