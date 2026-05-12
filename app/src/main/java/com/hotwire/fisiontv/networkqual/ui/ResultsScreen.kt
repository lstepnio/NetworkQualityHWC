package com.hotwire.fisiontv.networkqual.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hotwire.fisiontv.networkqual.BuildConfig
import com.hotwire.fisiontv.networkqual.R
import com.hotwire.fisiontv.networkqual.cert.CertificationResult
import com.hotwire.fisiontv.networkqual.cert.HealthAssessment
import com.hotwire.fisiontv.networkqual.cert.HealthRating
import com.hotwire.fisiontv.networkqual.cert.Tier
import com.hotwire.fisiontv.networkqual.cert.WifiLinkQuality
import com.hotwire.fisiontv.networkqual.diagnostics.WifiBand
import com.hotwire.fisiontv.networkqual.ui.theme.FisionHealthExcellent
import com.hotwire.fisiontv.networkqual.ui.theme.FisionHealthFailed
import com.hotwire.fisiontv.networkqual.ui.theme.FisionHealthGood
import com.hotwire.fisiontv.networkqual.ui.theme.FisionHealthMarginal
import com.hotwire.fisiontv.networkqual.ui.theme.FisionHealthStrong
import com.hotwire.fisiontv.networkqual.ui.theme.FisionSuccessGreen

/**
 * Cert results screen.
 *
 * Layout (top-down) follows the v8 diagnostic mockup; visual treatment
 * stays in the existing FisionTV theme (navy background, FisionHealth*
 * status palette, the existing surfaceVariant card surfaces, FisionPink
 * primary CTA). The mockup contributes structure + copy patterns + the
 * "every tile speaks to both customer and tech" two-layer body — not
 * colors.
 *
 *   ┌────────────────────────────────────────────────────────────┐
 *   │  [FisionTV logo]                                           │  top bar
 *   │  ┌──────────────────────────────────────────────────────┐  │
 *   │  │  HERO TILE                                           │  │
 *   │  │   Network certified for       [Top tier badge]       │  │
 *   │  │   4K HDR (huge green)         [headroom bar]         │  │
 *   │  │                                <caption / meta>      │  │
 *   │  └──────────────────────────────────────────────────────┘  │
 *   │  ┌──────────────────────┐  ┌────────────────────────────┐  │
 *   │  │  TEST RESULTS        │  │  WI-FI LINK                │  │
 *   │  │   Download           │  │   plain sentence           │  │
 *   │  │   Upload             │  │   tech meta (mono)         │  │
 *   │  │   Latency            │  └────────────────────────────┘  │
 *   │  │   Playback           │  ┌────────────────────────────┐  │
 *   │  │   DNS                │  │  DNS CONFIGURATION         │  │
 *   │  │                      │  │   In use → Preferred table │  │
 *   │  └──────────────────────┘  └────────────────────────────┘  │
 *   │  [Run again]                       v0.9.x · <configVersion>│  footer
 *   └────────────────────────────────────────────────────────────┘
 *
 * The DNS tile is conditional on `dnsAssessment != null && !allPreferred`.
 */
@Composable
fun ResultsScreen(
    result: CertificationResult,
    onRunAgain: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 36.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TopBar()
        HeroTile(result.achievedTier, result.health)
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TestResultsTile(result, modifier = Modifier.weight(1.4f).fillMaxHeight())
            StatusStack(result, modifier = Modifier.weight(1f).fillMaxHeight())
        }
        FooterBar(
            configVersion = result.configVersion,
            onRunAgain = onRunAgain,
            runAgainFocus = focusRequester
        )
    }
}

@Composable
private fun TopBar() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(id = R.drawable.logo_fision_on_dark),
            contentDescription = "FisionTV",
            modifier = Modifier.height(36.dp)
        )
    }
}

@Composable
private fun HeroTile(achieved: Tier, health: HealthAssessment) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1.4f)) {
                Text(
                    "Network certified for",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = achieved.displayName,
                    style = MaterialTheme.typography.displayLarge,
                    color = if (achieved == Tier.NONE) FisionHealthFailed else FisionSuccessGreen
                )
            }
            Spacer(Modifier.width(28.dp))
            Column(modifier = Modifier.weight(1f)) {
                HeadroomBlock(achieved, health)
            }
        }
    }
}

@Composable
private fun HeadroomBlock(achieved: Tier, health: HealthAssessment) {
    val isTopTier = health.nextTier == null && health.rating != HealthRating.FAILED
    val isFailed = health.rating == HealthRating.FAILED || achieved == Tier.NONE
    val color = when {
        isFailed -> FisionHealthFailed
        isTopTier || health.rating == HealthRating.EXCELLENT -> FisionHealthExcellent
        health.rating == HealthRating.STRONG -> FisionHealthStrong
        health.rating == HealthRating.GOOD -> FisionHealthGood
        else -> FisionHealthMarginal
    }
    val pillText = when {
        isFailed -> "Failed"
        isTopTier -> "Top tier"
        else -> health.rating.displayName
    }
    val caption = when {
        isFailed -> "Connection didn't reach the SD floor."
        isTopTier -> "Your network has comfortable margin above ${achieved.displayName} requirements."
        else -> "${health.headroomPct}% of the way to the next tier (${health.nextTier!!.displayName})."
    }
    val meta = when {
        isFailed -> "limited by ${health.limitingMetric ?: "—"}"
        isTopTier -> "${health.headroomPct}% margin · tightest dimension: ${health.limitingMetric ?: "—"}"
        else -> "${health.headroomPct}% margin · limited by ${health.limitingMetric ?: "—"}"
    }

    StatusPill(text = pillText, color = color)
    Spacer(Modifier.height(10.dp))
    HeadroomBar(health.headroomPct, color)
    Spacer(Modifier.height(10.dp))
    Text(
        caption,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(6.dp))
    Text(
        meta,
        fontSize = 14.sp,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    )
}

@Composable
internal fun StatusPill(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.18f), shape = RoundedCornerShape(6.dp)) {
        Text(
            text,
            color = color,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun HeadroomBar(pct: Int, fillColor: Color) {
    val frac = (pct.coerceIn(0, 100)) / 100f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(999.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(frac)
                .height(10.dp)
                .background(fillColor, RoundedCornerShape(999.dp))
        )
    }
}

@Composable
private fun TestResultsTile(r: CertificationResult, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                "Test results",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(12.dp))
            MetricRow(
                "Download",
                "%.1f".format(r.download.steadyMbps),
                "Mbps",
                "peak %.1f".format(r.download.peakMbps)
            )
            MetricRow(
                "Upload",
                "%.1f".format(r.upload.steadyMbps),
                "Mbps",
                "peak %.1f".format(r.upload.peakMbps)
            )
            MetricRow(
                "Latency",
                "${r.latency.medianMs}",
                "ms",
                "jitter ± ${r.latency.jitterMs} ms"
            )
            MetricRow(
                "Playback",
                if (r.playback.peakHeight > 0) "${r.playback.peakHeight}p" else "—",
                if (r.playback.peakHeight > 0) "" else "no video",
                "${r.playback.rebufferCount} rebuffers · ${r.playback.peakBitrateKbps} kbps peak"
            )
            MetricRow(
                "DNS",
                "${r.dns.medianMs}",
                "ms",
                if (r.dns.failureCount > 0)
                    "${r.dns.failureCount} of ${r.dns.samples.size} failed"
                else
                    "max ${r.dns.maxMs} ms"
            )
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String, unit: String, sub: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Text(
            label,
            modifier = Modifier.width(120.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (unit.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(
                unit,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 3.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            sub,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(bottom = 4.dp)
        )
    }
}

@Composable
private fun StatusStack(r: CertificationResult, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        r.wifiLink?.let { WifiTile(it) }
        r.dnsAssessment?.takeIf { !it.allPreferred }?.let {
            DnsResultCard(
                actualServers = r.diagnostics.network.dnsServers,
                configuredPreferred = it.configuredPreferred
            )
        }
    }
}

@Composable
private fun WifiTile(link: WifiLinkQuality) {
    val color = when (link.rating) {
        HealthRating.EXCELLENT -> FisionHealthExcellent
        HealthRating.STRONG -> FisionHealthStrong
        HealthRating.GOOD -> FisionHealthGood
        HealthRating.MARGINAL -> FisionHealthMarginal
        HealthRating.FAILED -> FisionHealthFailed
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(text = link.rating.displayName, color = color)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Wi-Fi link",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(10.dp))
            // Two-layer body per v8: plain-language line, tech meta in mono.
            Text(
                link.advice,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 24.sp
            )
            link.techMeta()?.let { meta ->
                Spacer(Modifier.height(8.dp))
                Text(
                    meta,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

/**
 * Tech-meta line for the Wi-Fi tile per the v8 mockup pattern, e.g.
 * "−61 dBm on 5 GHz · 720/1200 Mbps". Returns null when we don't have
 * any useful signal data (a defensive belt-and-braces — rssiDbm should
 * always be populated on a real wifi link).
 */
private fun WifiLinkQuality.techMeta(): String? {
    val parts = mutableListOf<String>()
    if (rssiDbm != 0) {
        val bandLabel = when (band) {
            WifiBand.BAND_2_4_GHZ -> "2.4 GHz"
            WifiBand.BAND_5_GHZ -> "5 GHz"
            WifiBand.BAND_6_GHZ -> "6 GHz"
            WifiBand.UNKNOWN -> null
        }
        parts += if (bandLabel != null) "$rssiDbm dBm on $bandLabel" else "$rssiDbm dBm"
    }
    if (linkSpeedMbps > 0) {
        val link = if (maxSupportedMbps != null) "$linkSpeedMbps/$maxSupportedMbps Mbps"
                   else "$linkSpeedMbps Mbps"
        parts += link
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

@Composable
private fun FooterBar(
    configVersion: String,
    onRunAgain: () -> Unit,
    runAgainFocus: FocusRequester
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Button(
            onClick = onRunAgain,
            modifier = Modifier
                .focusRequester(runAgainFocus)
                .height(48.dp)
                .widthIn(min = 180.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text("Run again", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            text = "${BuildConfig.VERSION_NAME} · $configVersion",
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}
