package com.hotwire.fisiontv.networkqual.cert

import com.google.common.truth.Truth.assertThat
import com.hotwire.fisiontv.networkqual.diagnostics.WifiBand
import com.hotwire.fisiontv.networkqual.diagnostics.WifiInfo
import com.hotwire.fisiontv.networkqual.diagnostics.WifiSecurity
import com.hotwire.fisiontv.networkqual.diagnostics.WifiStandard
import com.hotwire.fisiontv.networkqual.fixtures.Fixtures
import org.junit.Test

class WifiLinkQualityAssessorTest {

    private val assessor = WifiLinkQualityAssessor(Fixtures.wifiCfg)

    private fun wifi(rssi: Int, link: Int = 433, max: Int? = 866) = WifiInfo(
        rssiDbm = rssi,
        signalLevel = 3,
        linkSpeedMbps = link,
        txLinkSpeedMbps = link,
        rxLinkSpeedMbps = null,
        maxSupportedTxLinkSpeedMbps = max,
        maxSupportedRxLinkSpeedMbps = max,
        frequencyMhz = 5180,
        band = WifiBand.BAND_5_GHZ,
        channelWidthMhz = null,
        standard = WifiStandard.AC_11AC,
        security = WifiSecurity.UNKNOWN,
        ssid = null,
        bssid = null,
        supplicantState = "COMPLETED",
        hiddenSsid = false
    )

    @Test fun `strong signal yields EXCELLENT`() {
        val q = assessor.assess(wifi(rssi = -50))
        assertThat(q.rating).isEqualTo(HealthRating.EXCELLENT)
        assertThat(q.rateAdaptationDegraded).isFalse()
    }

    @Test fun `RSSI in -55 to -65 yields STRONG`() {
        val q = assessor.assess(wifi(rssi = -60))
        assertThat(q.rating).isEqualTo(HealthRating.STRONG)
    }

    @Test fun `RSSI below -75 yields MARGINAL`() {
        val q = assessor.assess(wifi(rssi = -80))
        assertThat(q.rating).isEqualTo(HealthRating.MARGINAL)
    }

    @Test fun `rate adaptation gap below threshold downgrades by one bucket`() {
        // -50 dBm is normally EXCELLENT; with linkSpeed 100 of 866 max
        // (ratio 0.115 < 0.5) the rating drops to STRONG.
        val q = assessor.assess(wifi(rssi = -50, link = 100, max = 866))
        assertThat(q.rating).isEqualTo(HealthRating.STRONG)
        assertThat(q.rateAdaptationDegraded).isTrue()
        assertThat(q.advice).contains("Rate adaptation")
    }

    @Test fun `null max link speed treats rate adaptation as not degraded`() {
        val q = assessor.assess(wifi(rssi = -50, link = 100, max = null))
        assertThat(q.rateAdaptationDegraded).isFalse()
    }

    @Test fun `advice string includes RSSI and rate values`() {
        val q = assessor.assess(wifi(rssi = -63, link = 433, max = 866))
        assertThat(q.advice).contains("-63 dBm")
        assertThat(q.advice).contains("433/866 Mbps")
    }
}
