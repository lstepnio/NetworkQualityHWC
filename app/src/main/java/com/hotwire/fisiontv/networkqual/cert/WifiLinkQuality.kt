package com.hotwire.fisiontv.networkqual.cert

import com.hotwire.fisiontv.networkqual.config.WifiLinkQualityConfig
import com.hotwire.fisiontv.networkqual.diagnostics.WifiBand
import com.hotwire.fisiontv.networkqual.diagnostics.WifiInfo

data class WifiLinkQuality(
    val rating: HealthRating,
    val rssiDbm: Int,
    val band: WifiBand,
    val linkSpeedMbps: Int,
    val maxSupportedMbps: Int?,
    val rateAdaptationDegraded: Boolean,
    val advice: String
)

/**
 * Advisory rating for the connected Wi-Fi link. Independent of throughput
 * certification — a "Marginal" Wi-Fi link does not downgrade the achieved
 * tier, it just flags installation quality on screen and in the payload.
 *
 * Rating is the worse of:
 *   - RSSI bucket (excellentRssiMin / strongRssiMin / goodRssiMin)
 *   - Rate-adaptation gap (current linkSpeed / max supported < threshold
 *     drops one bucket and notes the cause)
 */
class WifiLinkQualityAssessor(private val cfg: WifiLinkQualityConfig) {

    fun assess(wifi: WifiInfo): WifiLinkQuality {
        val rssiBucket = when {
            wifi.rssiDbm >= cfg.excellentRssiMin -> HealthRating.EXCELLENT
            wifi.rssiDbm >= cfg.strongRssiMin -> HealthRating.STRONG
            wifi.rssiDbm >= cfg.goodRssiMin -> HealthRating.GOOD
            else -> HealthRating.MARGINAL
        }
        val rateAdaptationDegraded = wifi.maxSupportedTxLinkSpeedMbps?.let { max ->
            max > 0 && wifi.linkSpeedMbps.toDouble() / max < cfg.rateAdaptationDegradedThreshold
        } ?: false
        val finalRating = if (rateAdaptationDegraded) downgrade(rssiBucket) else rssiBucket

        val bandStr = when (wifi.band) {
            WifiBand.BAND_2_4_GHZ -> "2.4 GHz"
            WifiBand.BAND_5_GHZ -> "5 GHz"
            WifiBand.BAND_6_GHZ -> "6 GHz"
            WifiBand.UNKNOWN -> "unknown band"
        }
        val rateStr = wifi.maxSupportedTxLinkSpeedMbps?.takeIf { it > 0 }?.let {
            "${wifi.linkSpeedMbps}/$it Mbps"
        } ?: "${wifi.linkSpeedMbps} Mbps"

        val advice = buildAdvice(finalRating, wifi.rssiDbm, bandStr, rateStr, rateAdaptationDegraded)

        return WifiLinkQuality(
            rating = finalRating,
            rssiDbm = wifi.rssiDbm,
            band = wifi.band,
            linkSpeedMbps = wifi.linkSpeedMbps,
            maxSupportedMbps = wifi.maxSupportedTxLinkSpeedMbps,
            rateAdaptationDegraded = rateAdaptationDegraded,
            advice = advice
        )
    }

    private fun downgrade(r: HealthRating): HealthRating = when (r) {
        HealthRating.EXCELLENT -> HealthRating.STRONG
        HealthRating.STRONG -> HealthRating.GOOD
        HealthRating.GOOD -> HealthRating.MARGINAL
        else -> HealthRating.MARGINAL
    }

    private fun buildAdvice(
        rating: HealthRating,
        rssi: Int,
        band: String,
        rate: String,
        degraded: Boolean
    ): String {
        val base = "$rssi dBm on $band, $rate"
        val tail = when (rating) {
            HealthRating.EXCELLENT -> "Strong link — no action needed."
            HealthRating.STRONG -> "Healthy link with margin."
            HealthRating.GOOD -> "Acceptable but not great. Consider moving the STB or AP closer for streaming reliability."
            HealthRating.MARGINAL -> "Weak link — streaming may hiccup under load. Move the STB closer to the AP, or run an Ethernet cable."
            HealthRating.FAILED -> "Link unhealthy."
        }
        val degradedNote = if (degraded) " Rate adaptation has dropped well below the link's max — likely interference or distance." else ""
        return "$base. $tail$degradedNote"
    }
}
