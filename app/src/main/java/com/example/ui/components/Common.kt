package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Small, quiet label used above groups of content. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Int = 8) {
    Box(
        modifier = modifier
            .size(size.dp)
            .background(color, CircleShape)
    )
}

/** Neutral metadata pill. Used for themes and keywords, never as decoration. */
@Composable
fun TagPill(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun KeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Static waveform. No infinite shimmer, no fake interpolation: if a recording has no amplitude
 * data (for example an older clip) the caller gets an empty row instead of a decorative pattern.
 */
@Composable
fun Waveform(
    points: List<Float>,
    modifier: Modifier = Modifier,
    playedFraction: Float = 0f,
    playedColor: Color = MaterialTheme.colorScheme.primary,
    restColor: Color = MaterialTheme.colorScheme.outline
) {
    val bars = points.ifEmpty {
        List(32) { 0.18f }
    }
    val hasData = points.isNotEmpty()

    Canvas(modifier = modifier) {
        val count = bars.size
        if (count == 0) return@Canvas
        val gap = size.width / (count * 2.2f)
        val barWidth = ((size.width - gap * (count - 1)) / count).coerceAtLeast(1.5f)
        val centerY = size.height / 2f
        val playedUntil = if (hasData) (size.width * playedFraction.coerceIn(0f, 1f)) else -1f

        bars.forEachIndexed { index, raw ->
            val amplitude = raw.coerceIn(0.05f, 1f)
            val barHeight = (size.height * amplitude).coerceAtLeast(2f)
            val x = index * (barWidth + gap)
            drawRoundRect(
                color = if (x <= playedUntil) playedColor else restColor,
                topLeft = Offset(x, centerY - barHeight / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

/** Pause/play glyph drawn locally so the app does not depend on the huge icon-extended artifact. */
@Composable
fun PlayPauseGlyph(
    isPlaying: Boolean,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        drawPlayPause(isPlaying, tint)
    }
}

private fun DrawScope.drawPlayPause(isPlaying: Boolean, tint: Color) {
    if (isPlaying) {
        val barWidth = size.width * 0.26f
        val gap = size.width * 0.18f
        val height = size.height * 0.72f
        val top = (size.height - height) / 2f
        val left = (size.width - (barWidth * 2 + gap)) / 2f
        drawRoundRect(
            color = tint,
            topLeft = Offset(left, top),
            size = Size(barWidth, height),
            cornerRadius = CornerRadius(barWidth / 3f, barWidth / 3f)
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(left + barWidth + gap, top),
            size = Size(barWidth, height),
            cornerRadius = CornerRadius(barWidth / 3f, barWidth / 3f)
        )
    } else {
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(size.width * 0.30f, size.height * 0.20f)
            lineTo(size.width * 0.80f, size.height * 0.50f)
            lineTo(size.width * 0.30f, size.height * 0.80f)
            close()
        }
        drawPath(path, tint)
    }
}

/** A single horizontal rule, 1dp, using the outline colour. */
@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

/** A compact, honest state line: dot + text. */
@Composable
fun StatusLine(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusDot(color)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
