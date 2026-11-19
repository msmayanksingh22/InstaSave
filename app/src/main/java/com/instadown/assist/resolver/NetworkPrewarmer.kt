package com.instadown.assist.resolver

import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object SharedHttpEngine {
    val client: OkHttpClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .connectionPool(ConnectionPool(20, 5, TimeUnit.MINUTES))
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(false) // Detect 302 login walls immediately instead of wasting RTTs
        .retryOnConnectionFailure(true)
        .build()
}

/**
 * Call once from InstaSaveApp.onCreate() on a background thread.
 * Warms OS DNS cache + opens reusable HTTP/2 TLS 1.3 sockets before first user tap.
 */
object NetworkPrewarmer {
    private val dnsExecutor = Executors.newSingleThreadExecutor()
    private val targetHosts = listOf(
        "www.instagram.com",
        "scontent.cdninstagram.com",
        "fastdl.app"
    )

    fun warmUpAsync() {
        targetHosts.forEach { host ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    DnsResolver.getInstance().query(
                        null,
                        host,
                        DnsResolver.FLAG_EMPTY,
                        dnsExecutor,
                        CancellationSignal(),
                        object : DnsResolver.Callback<List<InetAddress>> {
                            override fun onAnswer(answer: List<InetAddress>, rcode: Int) = preconnect(host)
                            override fun onError(error: DnsResolver.DnsException) = Unit
                        }
                    )
                } catch (e: Exception) {
                    dnsExecutor.execute {
                        runCatching { InetAddress.getAllByName(host) }
                        preconnect(host)
                    }
                }
            } else {
                dnsExecutor.execute {
                    runCatching { InetAddress.getAllByName(host) }
                    preconnect(host)
                }
            }
        }
    }

    private fun preconnect(host: String) {
        val req = Request.Builder()
            .url("https://$host/favicon.ico")
            .head()
            .header("User-Agent", DESKTOP_UA)
            .build()
        runCatching { SharedHttpEngine.client.newCall(req).execute().close() }
    }

    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
}
