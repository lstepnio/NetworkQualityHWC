package com.hotwire.fisiontv.networkqual.publish

import com.hotwire.fisiontv.networkqual.cert.Tier
import com.hotwire.fisiontv.networkqual.cert.TierThreshold
import com.hotwire.fisiontv.networkqual.config.HealthAssessmentConfig
import com.hotwire.fisiontv.networkqual.config.LatencyPhaseConfig
import com.hotwire.fisiontv.networkqual.config.OoklaServer
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
 * Validation is mostly handled by [RuntimeConfig]'s `init` blocks — this
 * class just maps fields. Missing optional fields fall back to the value
 * in [RuntimeConfigDefaults.bundled] so adding a new field server-side
 * doesn't immediately break older clients.
 *
 * Health and Wi-Fi assessment configs are not (yet) part of the public
 * cert-config schema; they're inherited from bundled defaults until the
 * spec adds them.
 */
object RuntimeConfigParser {

    fun parse(json: String): RuntimeConfig = parse(JSONObject(json))

    fun parse(o: JSONObject): RuntimeConfig {
        val defaults = RuntimeConfigDefaults.bundled
        return RuntimeConfig(
            schemaVersion = o.getInt("schemaVersion"),
            configVersion = o.getString("configVersion"),
            servers = o.getJSONArray("servers").mapTo(::parseServer),
            tests = parseTests(o.getJSONObject("tests")),
            tiers = o.getJSONArray("tiers").mapTo(::parseTier),
            dnsProbeHosts = o.optJSONArray("dnsProbeHosts")?.toStringList() ?: defaults.dnsProbeHosts,
            healthAssessment = defaults.healthAssessment,
            wifiLinkQuality = defaults.wifiLinkQuality,
            resultsPublishing = parsePublishing(o.optJSONObject("uploadResults"))
        )
    }

    private fun parseServer(o: JSONObject): OoklaServer = OoklaServer(
        id = o.getString("id"),
        name = o.getString("name"),
        host = o.getString("host"),
        port = o.optInt("port", 8080),
        secure = o.optBoolean("secure", true)
    )

    private fun parseTests(o: JSONObject) = TestsConfig(
        download = parseThroughput(o.getJSONObject("download")),
        upload = parseThroughput(o.getJSONObject("upload")),
        latency = parseLatency(o.getJSONObject("latency")),
        playback = parsePlayback(o.getJSONObject("playback"))
    )

    private fun parseThroughput(o: JSONObject) = ThroughputPhaseConfig(
        durationSec = o.getInt("durationSec"),
        parallel = o.getInt("parallel"),
        perRequestBytes = o.getLong("perRequestBytes"),
        warmupFraction = o.getDouble("warmupFraction")
    )

    private fun parseLatency(o: JSONObject) = LatencyPhaseConfig(
        samples = o.getInt("samples"),
        timeoutMs = o.optInt("timeoutMs", 2000)
    )

    private fun parsePlayback(o: JSONObject) = PlaybackPhaseConfig(
        manifestUrl = o.getString("manifestUrl"),
        durationSec = o.getInt("durationSec")
    )

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

    private fun parsePublishing(o: JSONObject?): ResultsPublishingConfig {
        if (o == null) return ResultsPublishingConfig(enabled = false, endpoint = null)
        return ResultsPublishingConfig(
            enabled = o.optBoolean("enabled", false),
            endpoint = if (o.isNull("endpoint")) null else o.optString("endpoint", "").ifEmpty { null }
        )
    }

    private fun <T> JSONArray.mapTo(map: (JSONObject) -> T): List<T> =
        (0 until length()).map { map(getJSONObject(it)) }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).map { getString(it) }
}
