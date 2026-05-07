package com.hotwire.fisiontv.networkqual.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

/**
 * Field-tech-oriented results screen, top-down:
 *
 *   1. Hero verdict — big tier name in green, server tested against
 *   2. Status cards row — Headroom (left) + Wi-Fi link (right) side-by-side,
 *      each with a colored rating pill, the headline number, and a one-line
 *      tech-readable explanation
 *   3. Performance details — full-width single-column metric rows with
 *      P50/P95/loss for latency and DNS, no horizontal squeeze
 *   4. Run again — centered action button
 *
 * The previous layout put the metrics table in a narrow left column with
 * the status cards stacked on the right. That caused metric facets to
 * wrap character-by-character on TV screens. Going single-column gives
 * each metric row the full screen width and eliminates the squeeze.
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
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 56.dp, vertical = 32.dp)
    ) {
        Hero(result)
        Spacer(Modifier.height(20.dp))
        StatusCardsRow(result)
        Spacer(Modifier.height(24.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        Spacer(Modifier.height(20.dp))
        PerformanceDetails(result)
        Spacer(Modifier.height(28.dp))
        RunAgainBar(onRunAgain, focusRequester)
    }
}

// ---------------------------------------------------------------------------
// Hero
// ---------------------------------------------------------------------------

@Composable
private fun Hero(result: CertificationResult) {
    Text(
        "Network Certification",
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = if (result.achievedTier == Tier.NONE) "Not certified"
        else "Certified for ${result.achievedTier.displayName}",
        style = MaterialTheme.typography.displayMedium,
        fontWeight = FontWeight.Bold,
        color = if (result.achievedTier == Tier.NONE) MaterialTheme.colorScheme.error
        else FisionSuccessGreen
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "Tested against ${result.selectedServer.name}",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

// ---------------------------------------------------------------------------
// Status cards (Headroom + Wi-Fi)
// ---------------------------------------------------------------------------

@Composable
private fun StatusCardsRow(result: CertificationResult) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(modifier = Modifier.weight(1f)) {
            HeadroomCard(result.health)
        }
        Box(modifier = Modifier.weight(1f)) {
            if (result.wifiLink != null) WifiLinkCard(result.wifiLink)
            else EthernetCard()
        }
    }
}

@Composable
private fun HeadroomCard(health: HealthAssessment) {
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
        isTopTier -> "${health.headroomPct}% margin · closest to floor: ${health.limitingMetric ?: "—"}"
        else -> "${health.headroomPct}% to ${health.nextTier!!.displayName} · limited by ${health.limitingMetric ?: "—"}"
    }

    StatusCard(pillText = pillText, pillColor = color, title = "Headroom") {
        Spacer(Modifier.height(8.dp))
        ProgressBar(health.headroomPct, color)
        Spacer(Modifier.height(8.dp))
        Text(
            subline,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2
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
    StatusCard(pillText = link.rating.displayName, pillColor = color, title = "Wi-Fi link") {
        Spacer(Modifier.height(8.dp))
        Text(
            link.advice,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3
        )
    }
}

@Composable
private fun EthernetCard() {
    StatusCard(
        pillText = "Wired",
        pillColor = FisionHealthExcellent,
        title = "Connection"
    ) {
        Spacer(Modifier.height(8.dp))
        Text(
            "STB on Ethernet — no Wi-Fi link to evaluate.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatusCard(
    pillText: String,
    pillColor: Color,
    title: String,
    body: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = pillColor, shape = RoundedCornerShape(6.dp)) {
                    Text(
                        pillText,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            body()
        }
    }
}

@Composable
private fun ProgressBar(pct: Int, color: Color) {
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

// ---------------------------------------------------------------------------
// Performance details — single full-width column, one row per metric
// ---------------------------------------------------------------------------

@Composable
private fun PerformanceDetails(r: CertificationResult) {
    Text(
        "Performance details",
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))

    val dnsTotal = r.dns.samples.size.coerceAtLeast(1)
    val dnsFailPct = (r.dns.failureCount * 100) / dnsTotal

    DetailRow(
        label = "Download",
        primary = "${"%.1f".format(r.download.steadyMbps)} Mbps",
        facets = listOf("peak" to "${"%.1f".format(r.download.peakMbps)} Mbps")
    )
    DetailRow(
        label = "Upload",
        primary = "${"%.1f".format(r.upload.steadyMbps)} Mbps",
        facets = listOf("peak" to "${"%.1f".format(r.upload.peakMbps)} Mbps")
    )
    DetailRow(
        label = "Latency",
        primary = "${r.latency.medianMs} ms",
        facets = listOf(
            "P95" to "${r.latency.p95Ms} ms",
            "loss" to "${r.latency.lossPct}%"
        )
    )
    DetailRow(
        label = "DNS",
        primary = "${r.dns.medianMs} ms",
        facets = listOf(
            "P95" to "${r.dns.p95Ms} ms",
            "fail" to "$dnsFailPct%"
        )
    )
    DetailRow(
        label = "Playback",
        primary = if (r.playback.peakHeight > 0) "${r.playback.peakHeight}p" else "no video",
        facets = listOf(
            "bitrate" to "${r.playback.peakBitrateKbps} kbps",
            "rebuffers" to "${r.playback.rebufferCount}"
        )
    )
}

/**
 * Full-width metric row. Three regions:
 *
 *   [label]                  [primary value]                  [facets · joined]
 *
 * On a 1080p TV at 16:9 with 56dp horizontal page padding, the outer
 * row is ~1808dp wide. label takes 200dp, primary 280dp, facets get
 * ~1300dp — plenty of room for "P95 50 ms · loss 0%" without wrapping.
 */
@Composable
private fun DetailRow(
    label: String,
    primary: String,
    facets: List<Pair<String, String>>
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            modifier = Modifier.width(200.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            primary,
            modifier = Modifier.width(220.dp),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            softWrap = false,
            maxLines = 1,
            overflow = TextOverflow.Visible
        )
        Spacer(Modifier.width(32.dp))
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            facets.forEachIndexed { i, (k, v) ->
                if (i > 0) {
                    Text(
                        "  ·  ",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Text(
                    "$k ",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    v,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    softWrap = false,
                    maxLines = 1
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Run again
// ---------------------------------------------------------------------------

@Composable
private fun RunAgainBar(onRunAgain: () -> Unit, focusRequester: FocusRequester) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        Button(
            onClick = onRunAgain,
            modifier = Modifier
                .focusRequester(focusRequester)
                .height(60.dp)
                .widthIn(min = 240.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text("Run again", style = MaterialTheme.typography.titleLarge)
        }
    }
}
