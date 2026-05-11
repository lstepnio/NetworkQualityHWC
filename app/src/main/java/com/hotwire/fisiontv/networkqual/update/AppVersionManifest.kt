package com.hotwire.fisiontv.networkqual.update

import org.json.JSONObject

/**
 * Parsed response of `GET /v1/app/version`.
 *
 * Tells the client (a) what the latest released app is, (b) the lowest
 * versionCode the client may run a certification at, and (c) where to
 * download the APK and how to verify it.
 *
 * Same shape philosophy as [com.hotwire.fisiontv.networkqual.config.RuntimeConfig]:
 *   - `schemaVersion` allows additive evolution; the client refuses
 *     manifests with `schemaVersion > SUPPORTED_SCHEMA_VERSION`
 *   - Optional fields default sensibly so the server can add metadata
 *     without breaking older clients
 *
 * Integrity invariants:
 *   - [apkSha256] must hash the downloaded bytes exactly
 *   - [signingCertSha256] must equal the pinned `BuildConfig.APP_SIGNING_CERT_SHA256`
 *     AND match the actual signing cert of the downloaded APK
 *   - [latestVersionCode] must be strictly greater than the installed
 *     versionCode for an update to be offered; downgrades are refused
 *     after download.
 */
data class AppVersionManifest(
    val schemaVersion: Int,
    val latestVersionName: String,
    val latestVersionCode: Int,
    val minRequiredVersionCode: Int,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val apkSha256: String,
    val signingCertSha256: String,
    val releaseNotes: String?,
    val publishedAt: String?
) {
    init {
        require(schemaVersion >= 1) { "schemaVersion must be >= 1" }
        require(latestVersionName.isNotBlank()) { "latestVersionName must be set" }
        require(latestVersionCode > 0) { "latestVersionCode must be > 0" }
        require(minRequiredVersionCode in 1..latestVersionCode) {
            "minRequiredVersionCode ($minRequiredVersionCode) must be in 1..latestVersionCode ($latestVersionCode)"
        }
        require(apkUrl.isNotBlank()) { "apkUrl must be set" }
        require(apkSizeBytes > 0) { "apkSizeBytes must be > 0" }
        require(apkSha256.length == 64) { "apkSha256 must be 64 hex chars (was ${apkSha256.length})" }
        require(signingCertSha256.length == 64) {
            "signingCertSha256 must be 64 hex chars (was ${signingCertSha256.length})"
        }
    }

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1
    }
}

/**
 * Parses the JSON body of `GET /v1/app/version` into an [AppVersionManifest].
 *
 * Validation runs via [AppVersionManifest]'s `init` block. Missing optional
 * fields fall back to defaults; missing required fields throw — the caller
 * (the wire client) maps that to [FetchOutcome.Error].
 */
object AppVersionManifestParser {

    fun parse(json: String): AppVersionManifest = parse(JSONObject(json))

    fun parse(o: JSONObject): AppVersionManifest = AppVersionManifest(
        schemaVersion = o.getInt("schemaVersion"),
        latestVersionName = o.getString("latestVersionName"),
        latestVersionCode = o.getInt("latestVersionCode"),
        minRequiredVersionCode = o.getInt("minRequiredVersionCode"),
        apkUrl = o.getString("apkUrl"),
        apkSizeBytes = o.getLong("apkSizeBytes"),
        apkSha256 = o.getString("apkSha256").lowercase(),
        signingCertSha256 = o.getString("signingCertSha256").lowercase(),
        releaseNotes = o.optString("releaseNotes", "").ifBlank { null },
        publishedAt = o.optString("publishedAt", "").ifBlank { null }
    )
}
