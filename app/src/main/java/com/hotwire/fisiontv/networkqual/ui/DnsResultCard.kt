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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hotwire.fisiontv.networkqual.ui.components.StatusBadge
import com.hotwire.fisiontv.networkqual.ui.components.StatusKind
import com.hotwire.fisiontv.networkqual.ui.components.Tile
import com.hotwire.fisiontv.networkqual.ui.theme.FisionV8TextPrimary
import com.hotwire.fisiontv.networkqual.ui.theme.FisionV8TextSecondary
import com.hotwire.fisiontv.networkqual.ui.theme.FisionV8TextTertiary

/**
 * DNS-policy verdict tile, shown when the cert's `dnsAssessment` reports
 * `allPreferred = false`. Layout per `diagnostic-mockup-v8.html`:
 *
 *   [⚠ Warning] DNS configuration
 *   Your network is using non-preferred DNS servers...
 *
 *   IN USE     1.1.1.1
 *              8.8.8.8
 *   ─────────────────────────────
 *   PREFERRED  9.9.9.9
 *              149.112.112.112
 *
 * IPs are mono, white, slightly tracked. Group labels are uppercased
 * and align next to the first entry in their group. Provider-name
 * labels from the v8 mockup are deliberately not rendered here — they'd
 * need either a hardcoded provider map or a contract addition; either
 * is a separate PR.
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
    Tile(modifier = modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(text = "Warning", kind = StatusKind.WARN)
                Spacer(Modifier.width(10.dp))
                TileTitle("DNS configuration")
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Your network is using non-preferred DNS servers, which can slow streaming. " +
                    "Update your router's DNS settings to use the preferred servers below.",
                fontSize = 14.sp,
                color = FisionV8TextSecondary,
                lineHeight = 21.sp
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
            modifier = Modifier.width(96.dp).padding(top = 3.dp),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = FisionV8TextTertiary,
            letterSpacing = 1.sp
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (servers.isEmpty()) {
                Text(
                    "—",
                    fontSize = 14.sp,
                    color = FisionV8TextTertiary
                )
            } else {
                servers.forEach { ip ->
                    Text(
                        ip,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                        color = FisionV8TextPrimary,
                        letterSpacing = 0.5.sp
                    )
                }
            }
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
