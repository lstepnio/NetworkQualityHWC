package com.hotwire.fisiontv.networkqual.update

import android.util.Log
import com.hotwire.fisiontv.networkqual.publish.AuthProvider
import com.hotwire.fisiontv.networkqual.publish.OkHttpResultPublisher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Fetches `/v1/app/version` and parses the JSON into an [AppVersionManifest].
 *
 * Mirrors [com.hotwire.fisiontv.networkqual.publish.OkHttpCertConfigClient] —
 * same ETag cache, same X-Device-Id / X-App-Version / X-App-Version-Code
 * headers, same `AuthProvider` plug, same 304 / 200 / 5xx / parse-error
 * mapping. The actual JSON-to-type parser lives in
 * [AppVersionManifestParser] so the wire format and the type can evolve
 * independently.
 *
 * `schemaVersion > SUPPORTED_SCHEMA_VERSION` is treated as an Error so the
 * client falls back to whatever manifest was previously cached (or none —
 * which means the "Run cert" button just stays in its default state).
 */
class OkHttpAppUpdateClient(
    private val endpoint: String,
    private val authProvider: AuthProvider,
    private val deviceId: String,
    private val appVersion: String,
    private val appVersionCode: Int,
    private val client: OkHttpClient = OkHttpResultPublisher.defaultClient(),
    private val parser: AppVersionManifestParser = AppVersionManifestParser
) : AppUpdateClient {

    @Volatile private var cachedEtag: String? = null

    override suspend fun fetch(): AppUpdateFetchOutcome = withContext(Dispatchers.IO) {
        val path = endpoint.toHttpUrl().encodedPath
        val authHeader = authProvider.sign("GET", path, EMPTY_BODY)
        val builder = Request.Builder()
            .url(endpoint)
            .addHeader("X-Device-Id", deviceId)
            .addHeader("X-App-Version", appVersion)
            .addHeader("X-App-Version-Code", appVersionCode.toString())
        cachedEtag?.let { builder.addHeader("If-None-Match", it) }
        if (authHeader != null) builder.addHeader("Authorization", authHeader)
        val req = builder.build()

        try {
            client.newCall(req).execute().use { resp ->
                when (resp.code) {
                    304 -> {
                        Log.i(TAG, "304 not-modified; keeping cached manifest")
                        AppUpdateFetchOutcome.NotModified
                    }
                    200 -> {
                        val body = resp.body?.string()
                            ?: return@use AppUpdateFetchOutcome.Error("empty body")
                        resp.header("ETag")?.let { cachedEtag = it }
                        try {
                            val manifest = parser.parse(body)
                            if (manifest.schemaVersion > AppVersionManifest.SUPPORTED_SCHEMA_VERSION) {
                                Log.w(TAG, "rejecting manifest: schemaVersion ${manifest.schemaVersion} > supported ${AppVersionManifest.SUPPORTED_SCHEMA_VERSION}")
                                AppUpdateFetchOutcome.Error("schemaVersion ${manifest.schemaVersion} > supported ${AppVersionManifest.SUPPORTED_SCHEMA_VERSION}")
                            } else {
                                Log.i(TAG, "fetched manifest ${manifest.latestVersionName} (code=${manifest.latestVersionCode}, minRequired=${manifest.minRequiredVersionCode})")
                                AppUpdateFetchOutcome.Updated(manifest)
                            }
                        } catch (t: Throwable) {
                            AppUpdateFetchOutcome.Error("parse: ${t::class.simpleName}: ${t.message}")
                        }
                    }
                    426 -> AppUpdateFetchOutcome.Error("client too old (HTTP 426)")
                    in 500..599 -> AppUpdateFetchOutcome.Error("HTTP ${resp.code}")
                    else -> AppUpdateFetchOutcome.Error("HTTP ${resp.code}: ${resp.message}")
                }
            }
        } catch (t: IOException) {
            AppUpdateFetchOutcome.Error("${t::class.simpleName}: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "AppUpdateClient"
        private val EMPTY_BODY = ByteArray(0)
    }
}
