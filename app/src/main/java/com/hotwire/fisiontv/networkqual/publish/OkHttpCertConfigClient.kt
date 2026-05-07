package com.hotwire.fisiontv.networkqual.publish

import android.util.Log
import com.hotwire.fisiontv.networkqual.config.RuntimeConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Fetches /v1/cert-config and parses the JSON into a [RuntimeConfig].
 *
 * Owns its ETag cache so repeated calls within a TTL hit 304 cheaply.
 * The actual JSON-to-RuntimeConfig parser is intentionally a separate
 * class ([RuntimeConfigParser]) so the wire format and the type can
 * evolve independently.
 */
class OkHttpCertConfigClient(
    private val endpoint: String,
    private val authProvider: AuthProvider,
    private val deviceId: String,
    private val appVersion: String,
    private val schemaVersion: Int = 1,
    private val client: OkHttpClient = OkHttpResultPublisher.defaultClient(),
    private val parser: RuntimeConfigParser = RuntimeConfigParser
) : CertConfigClient {

    @Volatile private var cachedEtag: String? = null

    override suspend fun fetch(): FetchOutcome = withContext(Dispatchers.IO) {
        val authHeader = authProvider.authorizationHeader()
        val builder = Request.Builder()
            .url(endpoint)
            .addHeader("X-Device-Id", deviceId)
            .addHeader("X-App-Version", appVersion)
            .addHeader("X-Schema-Version", schemaVersion.toString())
        cachedEtag?.let { builder.addHeader("If-None-Match", it) }
        if (authHeader != null) builder.addHeader("Authorization", authHeader)
        val req = builder.build()

        try {
            client.newCall(req).execute().use { resp ->
                when (resp.code) {
                    304 -> {
                        Log.i(TAG, "304 not-modified; keeping cached config")
                        FetchOutcome.NotModified
                    }
                    200 -> {
                        val body = resp.body?.string() ?: return@use FetchOutcome.Error("empty body")
                        resp.header("ETag")?.let { cachedEtag = it }
                        try {
                            val config = parser.parse(body)
                            Log.i(TAG, "fetched config ${config.configVersion} (schema=${config.schemaVersion})")
                            FetchOutcome.Updated(config)
                        } catch (t: Throwable) {
                            FetchOutcome.Error("parse: ${t::class.simpleName}: ${t.message}")
                        }
                    }
                    426 -> FetchOutcome.Error("client schema too old (HTTP 426)")
                    in 500..599 -> FetchOutcome.Error("HTTP ${resp.code}")
                    else -> FetchOutcome.Error("HTTP ${resp.code}: ${resp.message}")
                }
            }
        } catch (t: IOException) {
            FetchOutcome.Error("${t::class.simpleName}: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "CertConfigClient"
    }
}
