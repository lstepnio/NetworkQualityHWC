package com.hotwire.fisiontv.networkqual.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hotwire.fisiontv.networkqual.ui.theme.FisionV8TileBg
import com.hotwire.fisiontv.networkqual.ui.theme.FisionV8TileBorder

/**
 * Shared "glassmorphism" tile used across the diagnostic results screen.
 * Backed by `--tile-bg` / `--tile-border` / `--tile-radius` from the v8
 * mockup. The CSS `backdrop-filter: blur(8px)` is intentionally not
 * approximated — Compose's `RenderEffect.blur` is API 31+ only, the
 * white-translucent surface reads close enough on the dark gradient,
 * and skipping it saves the GPU work on tight-budget STBs.
 */
@Composable
fun Tile(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier
            .border(1.dp, FisionV8TileBorder, RoundedCornerShape(18.dp)),
        color = FisionV8TileBg,
        shape = RoundedCornerShape(18.dp)
    ) {
        Box(Modifier.padding(24.dp)) {
            content()
        }
    }
}
