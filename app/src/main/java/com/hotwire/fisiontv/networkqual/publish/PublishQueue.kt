package com.hotwire.fisiontv.networkqual.publish

import android.util.Log
import com.hotwire.fisiontv.networkqual.cert.CertificationPayload
import com.hotwire.fisiontv.networkqual.cert.CertificationResult
import com.hotwire.fisiontv.networkqual.data.PendingPublishDao
import com.hotwire.fisiontv.networkqual.data.PendingPublishEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Persistent queue for results that need to be POSTed to the backend.
 *
 * Why this exists: STBs are on flaky networks and the app process can be
 * killed at any time. The in-memory retry in [OkHttpResultPublisher]
 * handles short blips inside a single run; this queue handles "the run
 * finished, we tried to publish, the network was down, the app got
 * killed, we came back later." Rows are persisted to the Room DB and
 * drained on every app launch + after every successful run.
 *
 * The queue stores the **fully-built JSON payload** (not the typed
 * [CertificationResult]) so it survives schema evolution between when
 * a row is enqueued and when it's drained. If we add a new optional
 * field to [CertificationPayload] tomorrow, queued rows from yesterday
 * still POST fine.
 *
 * [maxAttempts] caps how many times a row is retried before the queue
 * gives up and deletes it. Eight attempts at growing backoff covers
 * roughly a day of network flakiness; beyond that the row is unlikely
 * ever to succeed and is just consuming disk.
 */
class PublishQueue(
    private val dao: PendingPublishDao,
    private val send: suspend (PendingPublishEntity, String, String?) -> PublishOutcome = okHttpSender(),
    private val authProvider: AuthProvider = NoAuthProvider,
    private val maxAttempts: Int = 8
) {
    private val drainMutex = Mutex()

    suspend fun enqueue(result: CertificationResult, endpoint: String) {
        val payload = CertificationPayload.toJson(result).toString()
        dao.upsert(
            PendingPublishEntity(
                certificationId = result.certificationId,
                payloadJson = payload,
                deviceId = result.diagnostics.identity.deviceId,
                appVersion = result.diagnostics.device.appVersion,
                schemaVersion = 1,
                createdAtMs = System.currentTimeMillis()
            )
        )
        Log.i(TAG, "enqueued ${result.certificationId} for $endpoint")
    }

    /**
     * Try to flush every pending row to [endpoint]. Returns the number
     * of successfully drained rows. Idempotent — safe to call from
     * multiple places (app launch, run completion, future network
     * callback).
     */
    suspend fun drain(endpoint: String): Int = drainMutex.withLock {
        withContext(Dispatchers.IO) {
            dao.deleteExhausted(maxAttempts)
            val pending = dao.all()
            if (pending.isEmpty()) return@withContext 0

            Log.i(TAG, "draining ${pending.size} pending result(s) to $endpoint")
            var drained = 0
            val authHeader = authProvider.authorizationHeader()

            for (row in pending) {
                val outcome = send(row, endpoint, authHeader)
                when (outcome) {
                    is PublishOutcome.Success, is PublishOutcome.Duplicate -> {
                        dao.deleteById(row.certificationId)
                        drained++
                    }
                    is PublishOutcome.PermanentFailure -> {
                        // Won't ever succeed. Log and drop so the queue
                        // doesn't grow forever; preserve the failure on
                        // the row so support can grep.
                        Log.e(TAG, "dropping ${row.certificationId} on permanent failure: ${outcome.cause}")
                        dao.deleteById(row.certificationId)
                    }
                    is PublishOutcome.TransientFailure -> {
                        dao.recordAttempt(row.certificationId, System.currentTimeMillis(), outcome.cause)
                        // Stop draining on the first transient failure;
                        // the network is flapping and continuing just
                        // burns retries. Next drain() picks up where we
                        // left off.
                        Log.w(TAG, "transient on ${row.certificationId}: ${outcome.cause}; pausing drain")
                        break
                    }
                }
            }
            drained
        }
    }

    companion object {
        private const val TAG = "PublishQueue"

        /**
         * Default HTTP sender. Lifted into the companion so tests can
         * substitute a fake without dragging OkHttp into JVM unit tests.
         */
        fun okHttpSender(
            httpClient: OkHttpClient = OkHttpResultPublisher.defaultClient(),
            now: () -> Long = System::currentTimeMillis
        ): suspend (PendingPublishEntity, String, String?) -> PublishOutcome {
            return { row, endpoint, authHeader ->
                // Stamp submittedAt/enqueuedAt onto the frozen payload at
                // POST time. The body the backend actually receives carries
                // four timestamps: startedAt + completedAt (from the cert
                // run, frozen at enqueue) and enqueuedAt + submittedAt
                // (added here so the backend can tell when the row sat in
                // the queue vs. when the cert itself happened).
                val body = CertificationPayload
                    .stampSubmission(row.payloadJson, submittedAtMs = now(), enqueuedAtMs = row.createdAtMs)
                    .toRequestBody("application/json".toMediaType())
                val req = Request.Builder()
                    .url(endpoint)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("X-Device-Id", row.deviceId)
                    .addHeader("X-App-Version", row.appVersion)
                    .addHeader("X-Schema-Version", row.schemaVersion.toString())
                    .apply { if (authHeader != null) addHeader("Authorization", authHeader) }
                    .post(body)
                    .build()
                try {
                    httpClient.newCall(req).execute().use { resp ->
                        when (resp.code) {
                            201 -> PublishOutcome.Success
                            200 -> PublishOutcome.Duplicate
                            in 500..599 -> PublishOutcome.TransientFailure("HTTP ${resp.code}")
                            else -> PublishOutcome.PermanentFailure(resp.code, resp.message.ifBlank { "HTTP ${resp.code}" })
                        }
                    }
                } catch (t: IOException) {
                    PublishOutcome.TransientFailure("${t::class.simpleName}: ${t.message}")
                } catch (t: Throwable) {
                    PublishOutcome.PermanentFailure(0, "${t::class.simpleName}: ${t.message}")
                }
            }
        }
    }
}
