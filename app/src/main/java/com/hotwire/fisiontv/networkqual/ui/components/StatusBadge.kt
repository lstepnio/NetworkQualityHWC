package com.hotwire.fisiontv.networkqual.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hotwire.fisiontv.networkqual.ui.theme.FisionV8StatusFailBg
import com.hotwire.fisiontv.networkqual.ui.theme.FisionV8StatusFailFg
import com.hotwire.fisiontv.networkqual.ui.theme.FisionV8StatusGoodBg
import com.hotwire.fisiontv.networkqual.ui.theme.FisionV8StatusGoodFg
import com.hotwire.fisiontv.networkqual.ui.theme.FisionV8StatusWarnBg
import com.hotwire.fisiontv.networkqual.ui.theme.FisionV8StatusWarnFg

/**
 * Small filled pill — Good (green) / Warn (amber) / Fail (red) per the
 * v8 mockup's status palette. Used as the leading element on the WiFi
 * and DNS tiles, and as the "Top tier" pill on the hero.
 *
 * Per the mockup's "Badge color always matches the word" rule: pick the
 * variant by what the badge says, not by visual preference.
 */
enum class StatusKind { GOOD, WARN, FAIL }

@Composable
fun StatusBadge(text: String, kind: StatusKind, modifier: Modifier = Modifier) {
    val (bg, fg) = when (kind) {
        StatusKind.GOOD -> FisionV8StatusGoodBg to FisionV8StatusGoodFg
        StatusKind.WARN -> FisionV8StatusWarnBg to FisionV8StatusWarnFg
        StatusKind.FAIL -> FisionV8StatusFailBg to FisionV8StatusFailFg
    }
    Surface(
        modifier = modifier,
        color = bg,
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = text,
            color = fg,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}
