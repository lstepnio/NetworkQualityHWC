package com.hotwire.fisiontv.networkqual.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hotwire.fisiontv.networkqual.R

/**
 * The "pre-cert" screen — checking for updates, downloading them,
 * installing them. Single composable that handles all three phases by
 * just changing the title / subtitle / progress fraction passed in.
 *
 * Stays on screen while the OS installs and replaces the process. The
 * tech only sees this for a few hundred ms in the no-update case
 * (transitions straight through to RunningScreen).
 */
@Composable
fun PreparingScreen(
    title: String,
    subtitle: String?,
    fraction: Float?
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 80.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(id = R.drawable.logo_fision_on_dark),
            contentDescription = "FisionTV+",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth(0.55f)
                .height(140.dp)
        )
        Spacer(Modifier.height(40.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        if (subtitle != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(28.dp))
        // Determinate when we have a fraction (download in progress),
        // indeterminate otherwise (manifest fetch, install commit wait).
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(0.55f).height(8.dp),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${(fraction * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(0.55f).height(8.dp),
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
