package com.hotwire.fisiontv.networkqual.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Color
import com.hotwire.fisiontv.networkqual.cert.CertificationResult
import com.hotwire.fisiontv.networkqual.cert.HealthAssessment
import com.hotwire.fisiontv.networkqual.cert.HealthRating
import com.hotwire.fisiontv.networkqual.cert.Tier
import com.hotwire.fisiontv.networkqual.cert.WifiLinkQuality
import com.hotwire.fisiontv.networkqual.ui.theme.FisionHealthExcellent
import com.hotwire.fisiontv.networkqual.ui.theme.FisionHealthFailed
import com.hotwire.fisiontv.networkqual.ui.theme.FisionHealthGood
import com.hotwire.fisiontv.networkqual.ui.theme.FisionHealthMarginal
import com.hotwire.fisiontv.networkqual.ui.theme.FisionHealthStrong
import com.hotwire.fisiontv.networkqual.ui.theme.FisionSuccessGreen

@Composable
fun ResultsScreen(
    result: CertificationResult,
    onRunAgain: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Row(modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "Network certified for",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            // Successful certifications read in green — the brand pink
            // looked alarming in this position, easy to misread as an
            // error state. Only Tier.NONE keeps the error treatment.
            Text(
                text = result.achievedTier.displayName,
                style = MaterialTheme.typography.displayMedium,
                color = if (result.achievedTier == Tier.NONE) MaterialTheme.colorScheme.error
                    else FisionSuccessGreen
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Tested against ${result.selectedServer.name}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // HSN intentionally not shown on screen. It IS sent raw in
            // the POST body (see CertificationPayload.identity.hsn) so
            // the backend dashboard / billing-account linkage works
            // end-to-end without exposing it to anyone reading the TV.
            Spacer(Modifier.height(20.dp))
            MetricsTable(result)
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = onRunAgain,
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .height(56.dp)
                    .widthIn(min = 220.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("Run again", style = MaterialTheme.typography.titleLarge)
            }
        }
        Spacer(Modifier.width(48.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
        ) {
            result.wifiLink?.let {
                WifiLinkCard(it)
                Spacer(Modifier.height(10.dp))
            }
            HealthBadge(result.health)
            // Configuration miss (non-preferred DNS) is surfaced below the
            // health badge so the tech sees the verdict first, then the
            // actionable detail. Hidden in the success (`allPreferred`) and
            // no-policy (`null`) cases — see DnsResultCard's KDoc.
            result.dnsAssessment?.takeIf { !it.allPreferred }?.let {
                Spacer(Modifier.height(10.dp))
                DnsResultCard(
                    nonPreferred = it.nonPreferred,
                    configuredPreferred = it.configuredPreferred
                )
            }
        }
    }
}

@Composable
private fun HealthBadge(health: HealthAssessment) {
    // Top-tier achievement is a separate visual case: the customer certified
    // at the top of the ladder, so the pill should say "Top tier" in green
    // regardless of how tight the within-tier margin is. The headroom % and
    // limiting metric still appear in the subline so the customer (and a
    // tech) can see whether the connection is comfortably or just barely
    // 4K HDR.
    val isTopTier = health.nextTier == null && health.rating != HealthRating.FAILED

    val color = when {
        isTopTier -> FisionHealthExcellent
        health.rating == HealthRating.EXCELLENT -> FisionHealthExcellent
        health.rating == HealthRating.STRONG -> FisionHealthStrong
        health.rating == HealthRating.GOOD -> FisionHealthGood
        health.rating == HealthRating.MARGINAL -> FisionHealthMarginal
        else -> FisionHealthFailed
    }
    val pillText = if (isTopTier) "Top tier" else health.rating.displayName

    val subline = when {
        health.rating == HealthRating.FAILED -> "Connection didn't reach the SD floor."
        isTopTier -> {
            val limited = health.limitingMetric ?: "—"
            "${health.headroomPct}% margin within top tier · closest to floor: $limited"
        }
        else -> "${health.headroomPct}% of the way to ${health.nextTier!!.displayName} · limited by ${health.limitingMetric ?: "—"}"
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = color,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        pillText,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    "Headroom ${health.headroomPct}%",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Spacer(Modifier.height(8.dp))
            HeadroomBar(health.headroomPct, color)
            Spacer(Modifier.height(6.dp))
            Text(
                subline,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HeadroomBar(pct: Int, color: Color) {
    val frac = (pct.coerceIn(0, 100)) / 100f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(frac)
                .height(8.dp)
                .background(color, RoundedCornerShape(4.dp))
        )
    }
}

@Composable
private fun WifiLinkCard(link: WifiLinkQuality) {
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
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = color,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        link.rating.displayName,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    "Wi-Fi link",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                link.advice,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MetricsTable(r: CertificationResult) {
    Column {
        MetricRow(
            "Download",
            "${"%.1f".format(r.download.steadyMbps)} Mbps",
            "peak ${"%.1f".format(r.download.peakMbps)} Mbps"
        )
        MetricRow(
            "Upload",
            "${"%.1f".format(r.upload.steadyMbps)} Mbps",
            "peak ${"%.1f".format(r.upload.peakMbps)} Mbps"
        )
        MetricRow(
            "Latency",
            "${r.latency.medianMs} ms",
            "jitter ± ${r.latency.jitterMs} ms"
        )
        MetricRow(
            "Playback",
            if (r.playback.peakHeight > 0) "${r.playback.peakHeight}p" else "no video",
            "${r.playback.rebufferCount} rebuffers · ${r.playback.peakBitrateKbps} kbps peak"
        )
        MetricRow(
            "DNS",
            "${r.dns.medianMs} ms",
            if (r.dns.failureCount > 0) "${r.dns.failureCount} of ${r.dns.samples.size} failed" else "max ${r.dns.maxMs} ms"
        )
    }
}

@Composable
private fun MetricRow(label: String, value: String, secondary: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.width(32.dp))
        Text(
            secondary,
            modifier = Modifier.weight(1.4f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

