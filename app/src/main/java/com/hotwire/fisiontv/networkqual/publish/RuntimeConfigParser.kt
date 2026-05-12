package com.hotwire.fisiontv.networkqual.publish

import android.util.Log
import com.hotwire.fisiontv.networkqual.cert.Tier
import com.hotwire.fisiontv.networkqual.cert.TierThreshold
import com.hotwire.fisiontv.networkqual.config.HealthAssessmentConfig
import com.hotwire.fisiontv.networkqual.config.KillswitchConfig
import com.hotwire.fisiontv.networkqual.config.PlaybackPhaseConfig
import com.hotwire.fisiontv.networkqual.config.ResultsPublishingConfig
import com.hotwire.fisiontv.networkqual.config.RuntimeConfig
import com.hotwire.fisiontv.networkqual.config.RuntimeConfigDefaults
import com.hotwire.fisiontv.networkqual.config.TestsConfig
import com.hotwire.fisiontv.networkqual.config.ThroughputPhaseConfig
import com.hotwire.fisiontv.networkqual.config.WifiLinkQualityConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses the JSON body of GET /v1/cert-config into a [RuntimeConfig].
 *
 * Parser is **per-field tolerant**: a single bad field (e.g. a dashboard
 * mistake that ships `playback.durationSec=1`) does not invalidate the
 * whole config. Out-of-range or missing fields fall back to the value
 * from [RuntimeConfigDefaults.bundled] with a logged warning.
 *
 * The critical invariant: `uploadResults` is parsed first and in
 * isolation, so the kill switch (`enabled=false`) is honored even when
 * everything else fails to parse.
 *
 * Hard failures still throw: `tiers` missing entirely or containing an
 * unknown id, top-level JSON malformed. Those break certification too
 * fundamentally for fallback to be safe — the caller in
 * [OkHttpCertConfigClient] catches the throw and keeps the bundled
 * config.
 *
 * As of contract v1.4.0, the parser silently ignores deprecated fields
 * that may appear in pre-v1.4.0 configs in the DB: `servers[]`,
 * `tests.latency`, and the per-phase `durationSec` / `perRequestBytes`
 * / `warmupFraction` keys. JSONObject's read-by-key behavior makes this
 * automatic — we just don't ask for those keys.
 */
object RuntimeConfigParser {

    private const val TAG = "RuntimeConfigParser"

    fun parse(json: String): RuntimeConfig = parse(JSONObject(json))

    fun parse(o: JSONObject): RuntimeConfig {
        val defaults = RuntimeConfigDefaults.bundled

        // Parse the kill switch FIRST and in isolation. If everything
        // else fails, the operator's `enabled=false` MUST still take
        // effect — that's the entire reason the kill switch exists.
        val publishing = parsePublishingSafe(o.optJSONObject("uploadResults"))

        return RuntimeConfig(
            schemaVersion = o.optInt("schemaVersion", defaults.schemaVersion),
            configVersion = o.optString("configVersion").ifBlank { defaults.configVersion },
            tests = parseTests(o.optJSONObject("tests"), defaults.tests),
            tiers = o.getJSONArray("tiers").mapTo(::parseTier),
            dnsProbeHosts = o.optJSONArray("dnsProbeHosts")?.toStringList() ?: defaults.dnsProbeHosts,
            healthAssessment = parseHealthAssessmentSafe(o.optJSONObject("healthAssessment"), defaults.healthAssessment),
            wifiLinkQuality = parseWifiLinkQualitySafe(o.optJSONObject("wifiLinkQuality"), defaults.wifiLinkQuality),
            resultsPublishing = publishing,
            killswitch = parseKillswitchSafe(o.optJSONObject("killswitch")),
            ooklaConfigUrl = o.optString("ooklaConfigUrl").ifBlank { defaults.ooklaConfigUrl }
        )
    }

    /**
     * Fail closed: a malformed killswitch block defaults to enabled=false
     * (don't lock people out of their own STBs because of a typo).
     * Absent block also = disabled.
     */
    private fun parseKillswitchSafe(o: JSONObject?): KillswitchConfig {
        if (o == null) return KillswitchConfig(enabled = false)
        return try {
            KillswitchConfig(
                enabled = o.optBoolean("enabled", false),
                reason = if (o.isNull("reason")) null else o.optString("reason", "").ifEmpty { null }
            )
        } catch (t: Throwable) {
            Log.w(TAG, "killswitch parse failed (${t.message}); defaulting to disabled")
            KillswitchConfig(enabled = false)
        }
    }

    private fun parseTests(o: JSONObject?, defaults: TestsConfig): TestsConfig {
        if (o == null) {
            Log.w(TAG, "tests section missing; using bundled defaults")
            return defaults
        }
        return TestsConfig(
            download = parseThroughputSafe(o.optJSONObject("download"), defaults.download, "download"),
            upload = parseThroughputSafe(o.optJSONObject("upload"), defaults.upload, "upload"),
            playback = parsePlaybackSafe(o.optJSONObject("playback"), defaults.playback)
        )
    }

    private fun parseThroughputSafe(
        o: JSONObject?,
        fallback: ThroughputPhaseConfig,
        label: String
    ): ThroughputPhaseConfig {
        if (o == null) {
            Log.w(TAG, "tests.$label missing; using bundled defaults")
            return fallback
        }
        val ctx = "tests.$label"
        return try {
            ThroughputPhaseConfig(
                parallel = clampInt(o, "parallel", fallback.parallel, 1..16, ctx)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "$ctx parse failed (${t.message}); using bundled defaults")
            fallback
        }
    }

    private fun parsePlaybackSafe(o: JSONObject?, fallback: PlaybackPhaseConfig): PlaybackPhaseConfig {
        if (o == null) {
            Log.w(TAG, "tests.playback missing; using bundled defaults")
            return fallback
        }
        val ctx = "tests.playback"
        return try {
            val manifestUrl = o.optString("manifestUrl").ifBlank {
                Log.w(TAG, "$ctx.manifestUrl missing/blank; using bundled ${fallback.manifestUrl}")
                fallback.manifestUrl
            }
            PlaybackPhaseConfig(
                manifestUrl = manifestUrl,
                durationSec = clampInt(o, "durationSec", fallback.durationSec, 5..120, ctx)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "$ctx parse failed (${t.message}); using bundled defaults")
            fallback
        }
    }

    private fun parseWifiLinkQualitySafe(
        o: JSONObject?,
        fallback: WifiLinkQualityConfig
    ): WifiLinkQualityConfig {
        if (o == null) return fallback
        val ctx = "wifiLinkQuality"
        return try {
            val excellent = clampInt(o, "excellentRssiMin", fallback.excellentRssiMin, -100..0, ctx)
            val strong = clampInt(o, "strongRssiMin", fallback.strongRssiMin, -100..0, ctx)
            val good = clampInt(o, "goodRssiMin", fallback.goodRssiMin, -100..0, ctx)
            val rate = clampDouble(o, "rateAdaptationDegradedThreshold", fallback.rateAdaptationDegradedThreshold, 0.0..1.0, ctx)
            // Ordering invariant: if violated, fall back wholesale —
            // partial substitution would still produce a nonsensical
            // band layout (e.g. excellent < strong).
            if (!(excellent > strong && strong > good)) {
                Log.w(TAG, "$ctx ordering invariant violated (excellent=$excellent strong=$strong good=$good); using bundled defaults")
                return fallback
            }
            WifiLinkQualityConfig(
                excellentRssiMin = excellent,
                strongRssiMin = strong,
                goodRssiMin = good,
                rateAdaptationDegradedThreshold = rate
            )
        } catch (t: Throwable) {
            Log.w(TAG, "$ctx parse failed (${t.message}); using bundled defaults")
            fallback
        }
    }

    private fun parseHealthAssessmentSafe(
        o: JSONObject?,
        fallback: HealthAssessmentConfig
    ): HealthAssessmentConfig {
        if (o == null) return fallback
        val ctx = "healthAssessment"
        return try {
            val excellent = clampInt(o, "excellentMin", fallback.excellentMin, 1..100, ctx)
            val strong = clampInt(o, "strongMin", fallback.strongMin, 1..100, ctx)
            val good = clampInt(o, "goodMin", fallback.goodMin, 1..100, ctx)
            // topTierStretchUpFactor must be strictly > 1.0. Clamp via
            // a small open-interval check (the generic clampDouble range
            // is closed on both ends).
            val up = run {
                if (!o.has("topTierStretchUpFactor")) {
                    Log.w(TAG, "$ctx.topTierStretchUpFactor missing; using bundled ${fallback.topTierStretchUpFactor}")
                    fallback.topTierStretchUpFactor
                } else {
                    val v = o.optDouble("topTierStretchUpFactor", fallback.topTierStretchUpFactor)
                    if (v <= 1.0) {
                        Log.w(TAG, "$ctx.topTierStretchUpFactor=$v not > 1.0; using bundled ${fallback.topTierStretchUpFactor}")
                        fallback.topTierStretchUpFactor
                    } else v
                }
            }
            val down = clampDouble(o, "topTierStretchDownFactor", fallback.topTierStretchDownFactor, 0.0..1.0, ctx)
            if (!(excellent > strong && strong > good)) {
                Log.w(TAG, "$ctx ordering invariant violated (excellent=$excellent strong=$strong good=$good); using bundled defaults")
                return fallback
            }
            HealthAssessmentConfig(
                excellentMin = excellent,
                strongMin = strong,
                goodMin = good,
                topTierStretchUpFactor = up,
                topTierStretchDownFactor = down
            )
        } catch (t: Throwable) {
            Log.w(TAG, "$ctx parse failed (${t.message}); using bundled defaults")
            fallback
        }
    }

    private fun parseTier(o: JSONObject): TierThreshold {
        val id = o.getString("id")
        val tier = when (id) {
            "sd" -> Tier.SD
            "hd" -> Tier.HD
            "uhd" -> Tier.UHD_4K
            "uhd_hdr" -> Tier.UHD_4K_HDR
            else -> error("unknown tier id: $id")
        }
        return TierThreshold(
            tier = tier,
            minDownloadMbps = o.getDouble("minDownloadMbps"),
            maxLatencyMs = o.getLong("maxLatencyMs"),
            maxJitterMs = o.getLong("maxJitterMs"),
            maxRebuffers = o.optInt("maxRebuffers", 0),
            playbackMinHeight = o.getInt("minPlaybackHeight")
        )
    }

    private fun parsePublishingSafe(o: JSONObject?): ResultsPublishingConfig {
        if (o == null) return ResultsPublishingConfig(enabled = false, endpoint = null)
        return try {
            ResultsPublishingConfig(
                enabled = o.optBoolean("enabled", false),
                endpoint = if (o.isNull("endpoint")) null else o.optString("endpoint", "").ifEmpty { null }
            )
        } catch (t: Throwable) {
            // Fail closed: if uploadResults is malformed, treat as kill
            // switch on. Better to drop results than upload to a wrong
            // endpoint or DoS some random host because of a typo.
            Log.w(TAG, "uploadResults parse failed (${t.message}); defaulting to disabled")
            ResultsPublishingConfig(enabled = false, endpoint = null)
        }
    }

    private fun clampInt(o: JSONObject, key: String, fallback: Int, range: IntRange, ctx: String): Int {
        if (!o.has(key)) {
            Log.w(TAG, "$ctx.$key missing; using bundled $fallback")
            return fallback
        }
        val v = o.optInt(key, fallback)
        if (v !in range) {
            Log.w(TAG, "$ctx.$key=$v out of [${range.first},${range.last}]; using bundled $fallback")
            return fallback
        }
        return v
    }

    private fun clampLong(o: JSONObject, key: String, fallback: Long, range: LongRange, ctx: String): Long {
        if (!o.has(key)) {
            Log.w(TAG, "$ctx.$key missing; using bundled $fallback")
            return fallback
        }
        val v = o.optLong(key, fallback)
        if (v !in range) {
            Log.w(TAG, "$ctx.$key=$v out of [${range.first},${range.last}]; using bundled $fallback")
            return fallback
        }
        return v
    }

    private fun clampDouble(o: JSONObject, key: String, fallback: Double, range: ClosedFloatingPointRange<Double>, ctx: String): Double {
        if (!o.has(key)) {
            Log.w(TAG, "$ctx.$key missing; using bundled $fallback")
            return fallback
        }
        val v = o.optDouble(key, fallback)
        if (v !in range) {
            Log.w(TAG, "$ctx.$key=$v out of [${range.start},${range.endInclusive}]; using bundled $fallback")
            return fallback
        }
        return v
    }

    private fun <T> JSONArray.mapTo(map: (JSONObject) -> T): List<T> =
        (0 until length()).map { map(getJSONObject(it)) }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).map { getString(it) }
}
