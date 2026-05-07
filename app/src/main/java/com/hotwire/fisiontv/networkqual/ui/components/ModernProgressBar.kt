package com.hotwire.fisiontv.networkqual.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Tall, fully-rounded progress bar with a subtle gradient fill and a
 * slow shimmer overlay across the filled portion. Built on Canvas so we
 * control corner radius, shimmer mask, and color stops directly instead
 * of fighting Material's default LinearProgressIndicator styling.
 *
 * Animates smoothly to the latest [progress] via spring-tweened
 * interpolation so step-boundary jumps don't look glitchy.
 */
@Composable
fun ModernProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
    trackColor: Color,
    fillStart: Color,
    fillEnd: Color
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 400, easing = LinearEasing),
        label = "progress"
    )
    val shimmerTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerPos by shimmerTransition.animateFloat(
        initialValue = -0.3f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing)
        ),
        label = "shimmer-pos"
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        val radius = canvasHeight / 2f

        // Track
        drawRoundRect(
            color = trackColor,
            topLeft = Offset.Zero,
            size = Size(canvasWidth, canvasHeight),
            cornerRadius = CornerRadius(radius)
        )

        if (animated <= 0f) return@Canvas

        val fillWidth = canvasWidth * animated

        // Filled portion as a gradient. Drawn as a rounded rect so
        // partial-progress doesn't get a square right edge.
        drawRoundRect(
            brush = Brush.horizontalGradient(
                colors = listOf(fillStart, fillEnd),
                startX = 0f,
                endX = canvasWidth // gradient anchored to full width so colors stay stable as bar fills
            ),
            topLeft = Offset.Zero,
            size = Size(fillWidth, canvasHeight),
            cornerRadius = CornerRadius(radius)
        )

        // Shimmer: a soft white-to-transparent gradient swept across.
        // Clipped to the filled portion so it doesn't draw past the
        // visible bar even as the band travels off the right edge.
        val shimmerWidth = canvasWidth * 0.25f
        val shimmerCenterX = canvasWidth * shimmerPos
        clipPath(
            path = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = 0f, top = 0f,
                        right = fillWidth, bottom = canvasHeight,
                        cornerRadius = CornerRadius(radius)
                    )
                )
            }
        ) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0f),
                        Color.White.copy(alpha = 0.18f),
                        Color.White.copy(alpha = 0f)
                    ),
                    startX = shimmerCenterX - shimmerWidth / 2f,
                    endX = shimmerCenterX + shimmerWidth / 2f
                ),
                topLeft = Offset(shimmerCenterX - shimmerWidth / 2f, 0f),
                size = Size(shimmerWidth, canvasHeight)
            )
        }
    }
}
