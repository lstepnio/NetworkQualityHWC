package com.hotwire.fisiontv.networkqual.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hotwire.fisiontv.networkqual.cert.DnsProviders
import com.hotwire.fisiontv.networkqual.ui.theme.FisionHealthMarginal

/**
 * DNS-policy verdict tile, shown when the cert's dnsAssessment reports
 * `allPreferred = false`. Layout from `diagnostic-mockup-v8.html` —
 * copy/structure only; visual treatment uses the existing FisionTV
 * theme (no v8 design tokens):
 *
 *   [Warning] DNS configuration
 *   Your network is using non-preferred DNS servers...
 *
 *   IN USE     1.1.1.1            Cloudflare
 *              8.8.8.8            Google
 *   ─────────────────────────────────────────
 *   PREFERRED  9.9.9.9            Hotwire Primary
 *              149.112.112.112    Hotwire Secondary
 *
 * Provider names come from `DnsProviders.name()` — a small static map
 * of well-known DNS IPs plus the operator's preferred-server branding.
 * Unknown IPs render with no label (just the IP in mono).
 *
 * Hidden via the call-site check (`!allPreferred`) — passing rows have
 * nothing to surface; the absence of the tile is itself the success
 * state.
 */
@Composable
fun DnsResultCard(
    actualServers: List<String>,
    configuredPreferred: List<String>,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(text = "Warning", color = FisionHealthMarginal)
                Spacer(Modifier.width(10.dp))
                Text(
                    "DNS configuration",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Your network is using non-preferred DNS servers, which can slow streaming. " +
                    "Update your router's DNS settings to use the preferred servers below.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 24.sp
            )
            Spacer(Modifier.height(16.dp))
            DnsGroup(label = "In use", servers = actualServers)
            DnsTableDivider()
            DnsGroup(label = "Preferred", servers = configuredPreferred)
        }
    }
}

@Composable
private fun DnsGroup(label: String, servers: List<String>) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            label.uppercase(),
            modifier = Modifier
                .width(104.dp)
                .padding(top = 5.dp),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            letterSpacing = 1.sp
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (servers.isEmpty()) {
                Text(
                    "—",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            } else {
                servers.forEach { ip -> DnsRow(ip) }
            }
        }
    }
}

@Composable
private fun DnsRow(ip: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            ip,
            modifier = Modifier.width(180.dp),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.5.sp
        )
        DnsProviders.name(ip)?.let { provider ->
            Text(
                provider,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DnsTableDivider() {
    Spacer(Modifier.height(10.dp))
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.07f))
    )
    Spacer(Modifier.height(10.dp))
}
