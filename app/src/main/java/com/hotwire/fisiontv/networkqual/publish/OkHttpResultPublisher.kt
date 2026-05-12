package com.hotwire.fisiontv.networkqual.publish

import android.util.Log
import com.hotwire.fisiontv.networkqual.cert.CertificationPayload
import com.hotwire.fisiontv.networkqual.cert.CertificationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Random
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Posts results to /v1/certifications with bounded retry on transient
 * failures (5xx / IO errors). Uses a stock OkHttp client with default
 * trust managers — unlike the bandwidth probes, this carries data and
 * must validate the TLS chain.
 */
class OkHttpResultPublisher(
    private val endpoint: String,
    private val authProvider: AuthProvider,
    private val deviceId: String,
    private val appVersion: String,
    private val schemaVersion: Int = 1,
    private val client: OkHttpClient = defaultClient(),
    private val maxAttempts: Int = 4,
    private val baseBackoffMs: Long = 500L,
    /** Injectable so tests get a deterministic schedule; production uses
     *  a single shared instance across attempts. */
    private val random: Random = Random()
) {

    suspend fun publish(result: CertificationResult): PublishOutcome =
        withContext(Dispatchers.IO) {
            val body = CertificationPayload.toJson(result).toString()
                .toRequestBody("application/json".toMediaType())
            val authHeader = authProvider.authorizationHeader()

            val builder = Request.Builder()
                .url(endpoint)
                .addHeader("Content-Type", "application/json")
                .addHeader("X-Device-Id", deviceId)
                .addHeader("X-App-Version", appVersion)
                .addHeader("X-Schema-Version", schemaVersion.toString())
                .post(body)
            if (authHeader != null) builder.addHeader("Authorization", authHeader)
            val req = builder.build()

            var lastError: String? = null
            repeat(maxAttempts) { attempt ->
                val outcome = attemptOnce(req, attempt)
                when (outcome) {
                    is PublishOutcome.Success, is PublishOutcome.Duplicate,
                    is PublishOutcome.PermanentFailure -> return@withContext outcome
                    is PublishOutcome.TransientFailure -> {
                        lastError = outcome.cause
                        if (attempt < maxAttempts - 1) {
                            // Equal-jitter exponential backoff. Half the
                            // nominal interval is fixed; the other half is
                            // uniform random. With N STBs hitting a 5xx in
                            // the same window, this de-synchronises their
                            // retries instead of stacking them at exactly
                            // 500 / 1000 / 2000 / 4000 ms.
                            val nominal = min(baseBackoffMs * (1L shl attempt), 8_000L)
                            val half = nominal / 2
                            val backoff = half + (random.nextLong() and Long.MAX_VALUE) % (half + 1)
                            Log.w(TAG, "transient failure (attempt ${attempt + 1}/$maxAttempts): ${outcome.cause} — retrying in ${backoff}ms (nominal ${nominal}ms ± jitter)")
                            delay(backoff)
                        }
                    }
                }
            }
            PublishOutcome.TransientFailure(lastError ?: "exhausted retries")
        }

    private fun attemptOnce(req: Request, attempt: Int): PublishOutcome {
        return try {
            client.newCall(req).execute().use { resp ->
                when (resp.code) {
                    201 -> PublishOutcome.Success.also { Log.i(TAG, "POST ok 201 (attempt ${attempt + 1})") }
                    200 -> PublishOutcome.Duplicate.also { Log.i(TAG, "POST dedupe 200 (attempt ${attempt + 1})") }
                    // 409 means cert_id already in the backend with a different
                    // payload_hash. For our model cert_id IS the natural
                    // idempotency key — same row, different submittedAt drift
                    // across retries. Result is in the DB; treat as duplicate.
                    409 -> PublishOutcome.Duplicate.also { Log.i(TAG, "POST dedupe 409 (attempt ${attempt + 1}; cert_id already submitted)") }
                    // 408 Request Timeout and 429 Too Many Requests are
                    // transient per RFC; retrying with backoff is correct.
                    408, 429 -> PublishOutcome.TransientFailure("HTTP ${resp.code}")
                    in 500..599 ->
                        PublishOutcome.TransientFailure("HTTP ${resp.code}")
                    else ->
                        PublishOutcome.PermanentFailure(resp.code, resp.message.ifBlank { "HTTP ${resp.code}" })
                }
            }
        } catch (t: IOException) {
            PublishOutcome.TransientFailure("${t::class.simpleName}: ${t.message}")
        } catch (t: Throwable) {
            // Programmer error or unexpected non-IO failure — don't retry.
            PublishOutcome.PermanentFailure(0, "${t::class.simpleName}: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "ResultPublisher"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
