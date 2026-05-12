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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
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
import com.hotwire.fisiontv.networkqual.ui.components.StatusBadge
import com.hotwire.fisiontv.networkqual.ui.components.StatusKind
import com.hotwire.fisiontv.networkqual.ui.components.Tile
import com.hotwire.fisiontv.networkqual.ui.theme.FisionV8BgGradFrom
import com.hotwire.fisiontv.networkqual.ui.theme.FisionV8BgGradTo
import com.hotwire.fisiontv.networkqual.ui.theme.FisionV8CertAccent
import com.hotwire.fisiontv.networkqual.ui.theme.FisionV8StatusFailFg
import com.hotwire.fisiontv.networkqual.ui.theme.FisionV8TextPrimary
import com.hotwire.fisiontv.networkqual.ui.theme.FisionV8TextSecondary
import com.hotwire.fisiontv.networkqual.ui.theme.FisionV8TextTertiary

/**
 * Cert results screen — port of `diagnostic-mockup-v8.html`.
 *
 * Layout, top-down:
 *
 *   ┌────────────────────────────────────────────────────────────┐
 *   │  [FisionTV logo]                                           │  topBar
 *   │  ┌──────────────────────────────────────────────────────┐  │
 *   │  │  HERO TILE                                           │  │
 *   │  │   Network certified for       [Top tier badge]       │  │
 *   │  │   4K HDR (huge green)         [headroom bar]         │  │
 *   │  │                                <caption / meta>      │  │
 *   │  └──────────────────────────────────────────────────────┘  │
 *   │  ┌──────────────────────┐  ┌────────────────────────────┐  │
 *   │  │  METRICS TILE        │  │  WIFI TILE                 │  │
 *   │  │  Download / Upload   │  │  badge · plain · meta      │  │
 *   │  │  Latency / Playback  │  └────────────────────────────┘  │
 *   │  │  DNS                 │  ┌────────────────────────────┐  │
 *   │  │                      │  │  DNS TILE                  │  │
 *   │  │                      │  │  (rendered only when       │  │
 *   │  │                      │  │   dnsAssessment fails)     │  │
 *   │  └──────────────────────┘  └────────────────────────────┘  │
 *   │  [Run again pill]                 v0.9.x · <configVersion> │  footer
 *   └────────────────────────────────────────────────────────────┘
 *
 * Background is the v8 navy gradient (`#0A1F3D → #18406E`). All tiles
 * share the [Tile] composable so radius / padding / glass treatment is
 * one place to change. Status palette comes from [StatusBadge].
 *
 * The DNS tile is conditional: hidden when `dnsAssessment == null`
 * (no policy in effect) and when `allPreferred == true`. Same gate as
 * the previous design — visual treatment changes, surfacing rule does not.
 */
@Composable
fun ResultsScreen(
    result: CertificationResult,
    onRunAgain: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(FisionV8BgGradFrom, FisionV8BgGradTo)
                )
            )
            .padding(horizontal = 36.dp, vertical = 28.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TopBar()
            HeroTile(result.achievedTier, result.health)
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                MetricsTile(result, modifier = Modifier.weight(1.4f).fillMaxHeight())
                StatusStack(result, modifier = Modifier.weight(1f).fillMaxHeight())
            }
            FooterBar(
                configVersion = result.configVersion,
                onRunAgain = onRunAgain,
                runAgainFocus = focusRequester
            )
        }
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
            modifier = Modifier.height(40.dp)
        )
    }
}

@Composable
private fun HeroTile(achieved: Tier, health: HealthAssessment) {
    Tile(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1.4f)) {
                Text(
                    "Network certified for",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = FisionV8TextSecondary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = achieved.displayName,
                    fontSize = 76.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (achieved == Tier.NONE) FisionV8StatusFailFg else FisionV8CertAccent
                )
            }
            Spacer(Modifier.width(36.dp))
            Column(modifier = Modifier.weight(1f)) {
                HeadroomSection(achieved, health)
            }
        }
    }
}

@Composable
private fun HeadroomSection(achieved: Tier, health: HealthAssessment) {
    val isTopTier = health.nextTier == null && health.rating != HealthRating.FAILED
    val isFailed = health.rating == HealthRating.FAILED || achieved == Tier.NONE
    val kind = when {
        isFailed -> StatusKind.FAIL
        health.rating == HealthRating.MARGINAL -> StatusKind.WARN
        else -> StatusKind.GOOD
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

    StatusBadge(text = pillText, kind = kind)
    Spacer(Modifier.height(12.dp))
    HeadroomBar(health.headroomPct, when (kind) {
        StatusKind.GOOD -> FisionV8CertAccent
        StatusKind.WARN -> com.hotwire.fisiontv.networkqual.ui.theme.FisionV8StatusWarnFg
        StatusKind.FAIL -> FisionV8StatusFailFg
    })
    Spacer(Modifier.height(12.dp))
    Text(
        caption,
        fontSize = 14.sp,
        color = FisionV8TextSecondary,
        lineHeight = 21.sp
    )
    Spacer(Modifier.height(8.dp))
    Text(
        meta,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        color = FisionV8TextTertiary
    )
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
private fun MetricsTile(r: CertificationResult, modifier: Modifier = Modifier) {
    Tile(modifier = modifier) {
        Column {
            TileTitle("Test results")
            Spacer(Modifier.height(10.dp))
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
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Text(
            label,
            modifier = Modifier.width(110.dp),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = FisionV8TextTertiary
        )
        Text(
            value,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = FisionV8TextPrimary
        )
        if (unit.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(
                unit,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = FisionV8TextSecondary,
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            sub,
            fontSize = 12.sp,
            color = FisionV8TextTertiary,
            modifier = Modifier.padding(bottom = 3.dp)
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
    val kind = when (link.rating) {
        HealthRating.EXCELLENT, HealthRating.STRONG, HealthRating.GOOD -> StatusKind.GOOD
        HealthRating.MARGINAL -> StatusKind.WARN
        HealthRating.FAILED -> StatusKind.FAIL
    }
    Tile(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(text = link.rating.displayName, kind = kind)
                Spacer(Modifier.width(10.dp))
                TileTitle("Wi-Fi link")
            }
            Spacer(Modifier.height(10.dp))
            Text(
                link.advice,
                fontSize = 14.sp,
                color = FisionV8TextSecondary,
                lineHeight = 21.sp
            )
        }
    }
}

@Composable
internal fun TileTitle(text: String) {
    Text(
        text,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        color = FisionV8TextPrimary
    )
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
        // Calm white pill per the v8 mockup — no more brand-pink CTA
        // stealing focus from the cert headline.
        Button(
            onClick = onRunAgain,
            modifier = Modifier
                .focusRequester(runAgainFocus)
                .height(44.dp)
                .widthIn(min = 140.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White.copy(alpha = 0.92f),
                contentColor = Color(0xFF0F172A)
            ),
            shape = RoundedCornerShape(999.dp)
        ) {
            Text("Run again", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(
            text = "${BuildConfig.VERSION_NAME} · $configVersion",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = FisionV8TextTertiary
        )
    }
}
