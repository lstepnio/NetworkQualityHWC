package com.hotwire.fisiontv.networkqual.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.hotwire.fisiontv.networkqual.ui.theme.FisionHealthMarginal

/**
 * Conditional warning card surfaced on the cert results screen when the
 * STB resolved against DNS servers outside the cert-config's
 * `dnsPolicy.preferredServers` set.
 *
 * Stateless on purpose: takes the two relevant String lists directly
 * rather than a `DnsAssessment`, so it can be previewed and (eventually)
 * unit-tested without constructing the full data class.
 *
 * Visual rationale: orange `FisionHealthMarginal`, NOT the red `error`
 * channel — red is reserved for cert-run failures (see `FailedScreen`).
 * A non-preferred DNS is a configuration miss the tech can fix on-site;
 * the cert itself still passed.
 */
@Composable
fun DnsResultCard(
    nonPreferred: List<String>,
    configuredPreferred: List<String>,
    modifier: Modifier = Modifier
) {
    val accent = FisionHealthMarginal
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Non-preferred DNS detected",
                    style = MaterialTheme.typography.titleMedium,
                    color = accent
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "This STB resolved against:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            ChipStack(items = nonPreferred, borderColor = accent, contentColor = Color.White)
            if (configuredPreferred.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Configured preferred:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                ChipStack(
                    items = configuredPreferred,
                    borderColor = MaterialTheme.colorScheme.outline,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ChipStack(
    items: List<String>,
    borderColor: Color,
    contentColor: Color
) {
    if (items.isEmpty()) {
        Text(
            "—",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    // Compose has no first-party FlowRow on the material3 version pinned
    // here, but the chips wrap fine in a plain Row at the widths a TV-form
    // STB will actually see (typically 2 nameservers in nonPreferred,
    // 1-2 in configuredPreferred). If a future cert-config grows the
    // preferred list past ~4 entries this should be revisited.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { item ->
            DnsChip(text = item, borderColor = borderColor, contentColor = contentColor)
        }
    }
}

@Composable
private fun DnsChip(text: String, borderColor: Color, contentColor: Color) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
            color = contentColor
        )
    }
}
