package com.hotwire.fisiontv.networkqual.update

/**
 * Fetches the app-update manifest from `GET /v1/app/version`.
 *
 * The implementation owns its ETag cache; callers just receive the
 * outcome and stash the manifest somewhere observable (typically a
 * `MutableStateFlow` on [com.hotwire.fisiontv.networkqual.AppContainer]).
 *
 * Symmetric with [com.hotwire.fisiontv.networkqual.publish.CertConfigClient] —
 * same interaction shape, separate endpoint and payload.
 */
interface AppUpdateClient {
    suspend fun fetch(): AppUpdateFetchOutcome
}

sealed interface AppUpdateFetchOutcome {
    /** New manifest arrived; caller stashes it. */
    data class Updated(val manifest: AppVersionManifest) : AppUpdateFetchOutcome

    /** ETag matched; the cached manifest is still authoritative. */
    data object NotModified : AppUpdateFetchOutcome

    /** Network, server, or parse error. Caller keeps using the previous cached manifest (if any). */
    data class Error(val cause: String) : AppUpdateFetchOutcome
}
