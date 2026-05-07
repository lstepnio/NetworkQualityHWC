package com.hotwire.fisiontv.networkqual.publish

import com.hotwire.fisiontv.networkqual.cert.CertificationResult
import com.hotwire.fisiontv.networkqual.config.RuntimeConfig

/**
 * Posts a finished certification to the backend at /v1/certifications.
 *
 * Idempotent on [CertificationResult.certificationId]; the server treats a
 * duplicate POST with matching payload hash as success. Implementations
 * must retry [PublishOutcome.TransientFailure] cases internally with
 * bounded backoff before surfacing them to the caller.
 */
interface ResultPublisher {
    suspend fun publish(result: CertificationResult): PublishOutcome
}

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
 * Provides the bearer token for authenticated requests. The actual token
 * source is decided by the auth strategy (see contract/SPEC.md §5):
 *  - Option A: per-install token issued by /v1/devices/register
 *  - Option B: HMAC-signed timestamp with a build-time shared secret
 *
 * Both strategies plug in here without changing call sites.
 */
fun interface AuthProvider {
    suspend fun authorizationHeader(): String?
}

/** Auth provider used until the real strategy ships. Returns null. */
object NoAuthProvider : AuthProvider {
    override suspend fun authorizationHeader(): String? = null
}
