package com.hotwire.fisiontv.networkqual.publish

import com.hotwire.fisiontv.networkqual.config.RuntimeConfig

/**
 * Fetches the runtime config from /v1/cert-config.
 *
 * The implementation owns its ETag cache; callers just receive the
 * outcome and apply [FetchOutcome.Updated.config] to a
 * [com.hotwire.fisiontv.networkqual.config.RuntimeConfigProvider].
 */
interface CertConfigClient {
    suspend fun fetch(): FetchOutcome
}

sealed interface PublishOutcome {
    /** 201 Created — the server stored the result for the first time. */
    data object Success : PublishOutcome

    /** 200 OK on a re-POST with matching payload hash. Treat as success. */
    data object Duplicate : PublishOutcome

    /** 5xx, network error, or timeout. Caller may queue and retry later. */
    data class TransientFailure(val cause: String) : PublishOutcome

    /**
     * 4xx (except 200/201) — schema mismatch, auth failure, payload too
     * large, conflict on certificationId. Retrying with the same payload
     * will fail again; surface to support tooling.
     */
    data class PermanentFailure(val httpStatus: Int, val cause: String) : PublishOutcome
}

sealed interface FetchOutcome {
    /** New config arrived; caller applies it and bumps the cached version. */
    data class Updated(val config: RuntimeConfig) : FetchOutcome

    /** ETag matched; cached copy is still authoritative. */
    data object NotModified : FetchOutcome

    /** Network or server error. Caller keeps using the previous config. */
    data class Error(val cause: String) : FetchOutcome
}

/**
 * Produces the `Authorization` header value for a given v1 request.
 *
 * Called per request because the HMAC-SHA256 scheme (see contract SPEC §5
 * and `internal/auth/hmac.go` on the server) signs the (method, path,
 * deviceId, timestamp, body-hash) tuple — so every call site must hand
 * in the bytes it is about to POST / the URL it is about to hit. Bodyless
 * GETs pass an empty array.
 *
 * Returning `null` means "send no Authorization header" — used by
 * [NoAuthProvider] for legacy-passthrough mode while the field fleet
 * rolls forward.
 */
fun interface AuthProvider {
    suspend fun sign(method: String, path: String, body: ByteArray): String?
}

/** Auth provider used until the real strategy ships. Returns null. */
object NoAuthProvider : AuthProvider {
    override suspend fun sign(method: String, path: String, body: ByteArray): String? = null
}
