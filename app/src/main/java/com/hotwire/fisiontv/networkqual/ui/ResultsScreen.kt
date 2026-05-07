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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import com.hotwire.fisiontv.networkqual.cert.TierEvaluation
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
                "Certification result",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = result.achievedTier.displayName,
                style = MaterialTheme.typography.displayMedium,
                color = if (result.achievedTier == Tier.NONE) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Tested against ${result.selectedServer.name}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
                .verticalScroll(rememberScrollState())
        ) {
            HealthBadge(result.health)
            result.wifiLink?.let {
                Spacer(Modifier.height(12.dp))
                WifiLinkCard(it)
            }
            Spacer(Modifier.height(20.dp))
            TierBreakdown(evals = result.tierBreakdown.filter { it.passed })
        }
    }
}

@Composable
private fun HealthBadge(health: HealthAssessment) {
    val color = when (health.rating) {
        HealthRating.EXCELLENT -> FisionHealthExcellent
        HealthRating.STRONG -> FisionHealthStrong
        HealthRating.GOOD -> FisionHealthGood
        HealthRating.MARGINAL -> FisionHealthMarginal
        HealthRating.FAILED -> FisionHealthFailed
    }
    val subline = when {
        health.rating == HealthRating.FAILED -> "Connection didn't reach the SD floor."
        health.nextTier == null -> "Above the top tier — no headroom limit."
        else -> "${health.headroomPct}% of the way to ${health.nextTier.displayName} · limited by ${health.limitingMetric ?: "—"}"
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = color,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text(
                        health.rating.displayName,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    "Headroom ${health.headroomPct}%",
                    style = MaterialTheme.typography.titleLarge
                )
            }
            Spacer(Modifier.height(10.dp))
            HeadroomBar(health.headroomPct, color)
            Spacer(Modifier.height(8.dp))
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
            .height(10.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(frac)
                .height(10.dp)
                .background(color, RoundedCornerShape(6.dp))
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
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = color,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text(
                        link.rating.displayName,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    "Wi-Fi link",
                    style = MaterialTheme.typography.titleLarge
                )
            }
            Spacer(Modifier.height(8.dp))
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
        Text(
            secondary,
            modifier = Modifier.weight(1.4f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TierBreakdown(evals: List<TierEvaluation>, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text("Supported tiers", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        if (evals.isEmpty()) {
            Text(
                "This connection didn't meet any FisionTV+ streaming tier.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            evals.forEach { eval ->
                TierEvalCard(eval)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun TierEvalCard(eval: TierEvaluation) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = FisionSuccessGreen
            )
            Spacer(Modifier.width(12.dp))
            Text(eval.tier.displayName, style = MaterialTheme.typography.titleLarge)
        }
    }
}
