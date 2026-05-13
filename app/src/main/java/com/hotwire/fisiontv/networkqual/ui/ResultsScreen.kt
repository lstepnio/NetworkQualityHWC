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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hotwire.fisiontv.networkqual.cert.CertificationResult
import com.hotwire.fisiontv.networkqual.cert.DnsAssessment
import com.hotwire.fisiontv.networkqual.cert.HealthAssessment
import com.hotwire.fisiontv.networkqual.cert.HealthRating
import com.hotwire.fisiontv.networkqual.cert.Tier
import com.hotwire.fisiontv.networkqual.cert.WifiLinkQuality
import com.hotwire.fisiontv.networkqual.ui.theme.FisionHealthFailed
import com.hotwire.fisiontv.networkqual.ui.theme.FisionHealthMarginal
import com.hotwire.fisiontv.networkqual.ui.theme.FisionSuccessGreen

/**
 * Cert results screen — calmer redesign.
 *
 *   ┌────────────────────────────────────────────────┐
 *   │                                                │
 *   │       Network certified for                    │  ← eyebrow
 *   │       4K HDR                                   │  ← green displayLarge (kept)
 *   │       Top tier · 32% headroom                  │  ← one subtitle, no bar, no pill
 *   │                                                │
 *   │       ─────────────────────────────            │
 *   │                                                │
 *   │       112.5 Mbps    217.1 Mbps    46 ms        │  ← values, large
 *   │       Download      Upload        Latency      │  ← labels, muted
 *   │                                                │
 *   │       ─────────────────────────────            │
 *   │                                                │
 *   │       • Wi-Fi: <advice>                        │  ← inline advisories,
 *   │       • Update router DNS to 9.9.9.9 / 1.1.1.1 │    only when actionable
 *   │                                                │
 *   │       [ Run again ]                            │  ← brand pink, kept
 *   │                                                │
 *   └────────────────────────────────────────────────┘
 *
 * Design principles in this iteration:
 * - Single centered column — the prior two-column / scrolling-right
 *   pattern was forcing horizontal pressure that didn't pay off.
 * - Health "pill + bar + subline" collapsed to one subtitle. The hero
 *   already says "you're at top tier" via the giant green headline; the
 *   bar was reading like an alarm-style progress indicator.
 * - Wi-Fi card, DNS card, "Tested against" subtitle, peak / jitter /
 *   rebuffer / playback-height / DNS-median secondary stats all removed
 *   from the on-TV view. The full payload still lands in postgres; the
 *   dashboard's `/certs/{id}` page has the complete record for operators.
 * - Advisories surface as one-line bullets, muted-color text, no icons,
 *   no chips. When nothing is wrong, the whole advisory block disappears.
 * - Debug version / configVersion footer removed — useful for support
 *   calls but visually noisy on the tech-on-site flow.
 */
@Composable
fun ResultsScreen(
    result: CertificationResult,
    onRunAgain: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(modifier = Modifier.fillMaxSize().padding(48.dp)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp)
                .align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Hero(result.achievedTier, result.health)
            Spacer(Modifier.height(28.dp))
            ThinDivider()
            Spacer(Modifier.height(28.dp))
            MetricsRow(result)
            val advisories = buildAdvisories(result.wifiLink, result.dnsAssessment)
            if (advisories.isNotEmpty()) {
                Spacer(Modifier.height(28.dp))
                ThinDivider()
                Spacer(Modifier.height(20.dp))
                advisories.forEach { adv ->
                    Advisory(adv)
                    Spacer(Modifier.height(6.dp))
                }
            }
            Spacer(Modifier.height(36.dp))
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
    }
}

@Composable
private fun Hero(achieved: Tier, health: HealthAssessment) {
    Text(
        "Network certified for",
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = achieved.displayName,
        style = MaterialTheme.typography.displayLarge,
        color = if (achieved == Tier.NONE) FisionHealthFailed else FisionSuccessGreen,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(10.dp))
    Text(
        heroSubtitle(health),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}

/**
 * Single-line subtitle replacing the prior pill+bar+subline trio. Reads
 * the verdict, the headroom number, and the limiting metric when it's
 * informative.
 */
private fun heroSubtitle(health: HealthAssessment): String {
    if (health.rating == HealthRating.FAILED) {
        return "Connection didn't reach the SD floor."
    }
    val isTopTier = health.nextTier == null
    val tierLabel = if (isTopTier) "Top tier" else health.rating.displayName
    return "$tierLabel · ${health.headroomPct}% headroom"
}

@Composable
private fun MetricsRow(r: CertificationResult) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        Metric(
            value = "%.1f".format(r.download.steadyMbps),
            unit = "Mbps",
            label = "Download"
        )
        Metric(
            value = "%.1f".format(r.upload.steadyMbps),
            unit = "Mbps",
            label = "Upload"
        )
        Metric(
            value = "${r.latency.medianMs}",
            unit = "ms",
            label = "Latency"
        )
    }
}

@Composable
private fun Metric(value: String, unit: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                fontSize = 36.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.widthIn(min = 4.dp))
            Text(
                unit,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private data class AdvisoryItem(val text: String, val tone: Color)

private fun buildAdvisories(
    wifi: WifiLinkQuality?,
    dns: DnsAssessment?
): List<AdvisoryItem> {
    val out = mutableListOf<AdvisoryItem>()
    if (wifi != null) {
        val tone = when (wifi.rating) {
            HealthRating.FAILED -> FisionHealthFailed
            HealthRating.MARGINAL -> FisionHealthMarginal
            else -> null
        }
        if (tone != null) {
            out += AdvisoryItem(
                text = "Wi-Fi: ${wifi.advice}",
                tone = tone
            )
        }
    }
    if (dns != null && !dns.allPreferred && dns.configuredPreferred.isNotEmpty()) {
        val first = dns.configuredPreferred.first()
        val rest = dns.configuredPreferred.drop(1)
        val advice = when {
            rest.isEmpty() -> "Update router DNS to $first"
            rest.size == 1 -> "Update router DNS to $first / ${rest[0]}"
            else -> "Update router DNS to $first (+ ${rest.size} more)"
        }
        out += AdvisoryItem(text = advice, tone = FisionHealthMarginal)
    }
    return out
}

@Composable
private fun Advisory(item: AdvisoryItem) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            "• ",
            color = item.tone,
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            item.text,
            color = item.tone,
            style = MaterialTheme.typography.bodyLarge,
            lineHeight = 24.sp
        )
    }
}

@Composable
private fun ThinDivider() {
    Box(
        modifier = Modifier
            .widthIn(min = 320.dp)
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.10f))
    )
}
