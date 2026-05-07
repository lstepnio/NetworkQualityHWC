package com.hotwire.fisiontv.networkqual.cert.probes.internal

import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * OkHttp helpers shared by the throughput probes.
 *
 * Bandwidth tests skip TLS chain validation so they work against public
 * speedtest endpoints whose certificates may be expired or hostname-
 * mismatched (Ookla servers commonly carry an internal-use cert). This
 * client must NOT be used for any traffic that carries data — the relaxed
 * TLS would silently accept a man-in-the-middle. The results-publisher
 * (when implemented) and any other auth-carrying call should use a stock
 * OkHttp client with default trust managers.
 */
internal object Insecure {

    private val trustAll: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val trustAllSsl: SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustAll), SecureRandom())
    }

    /**
     * Returns a builder pre-configured to skip cert validation and accept
     * any hostname. Caller adds timeouts and finishes with `.build()`.
     */
    fun unsafeClientBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .sslSocketFactory(trustAllSsl.socketFactory, trustAll)
        .hostnameVerifier { _, _ -> true }
}
