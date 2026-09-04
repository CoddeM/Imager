package com.rahul.imager.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/**
 * The grey histogram of the photo as it will be dithered.
 *
 * It is the honest way to explain why a photo prints as a black smear: the user can see the whole
 * distribution sitting below the threshold, and the brightness and contrast sliders visibly move
 * it. Drawn from the tone-adjusted plane, not the original, so it tracks the sliders live.
 */
@Composable
fun Histogram(
    counts: IntArray?,
    threshold: Int,
    modifier: Modifier = Modifier,
) {
    val barColor = MaterialTheme.colorScheme.onSurfaceVariant
    val thresholdColor = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.surfaceContainerHigh

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(MaterialTheme.shapes.small)
            .background(background),
    ) {
        val values = counts ?: return@Canvas
        if (values.isEmpty()) return@Canvas
        val peak = values.max().coerceAtLeast(1)
        val barWidth = size.width / values.size

        values.forEachIndexed { level, count ->
            if (count == 0) return@forEachIndexed
            val barHeight = size.height * count / peak
            drawRect(
                color = barColor,
                topLeft = androidx.compose.ui.geometry.Offset(
                    x = level * barWidth,
                    y = size.height - barHeight,
                ),
                size = androidx.compose.ui.geometry.Size(barWidth.coerceAtLeast(1f), barHeight),
            )
        }

        // The threshold marker: everything left of it becomes a black dot.
        val x = size.width * threshold / 255f
        drawLine(
            color = thresholdColor,
            start = androidx.compose.ui.geometry.Offset(x, 0f),
            end = androidx.compose.ui.geometry.Offset(x, size.height),
            strokeWidth = 2f,
        )
    }
}

/**
 * A dither mode, previewed on a crop of the USER's own photo.
 *
 * A generic sample would be useless: the whole question the user is answering is "which of these
 * looks best for THIS picture", and only their own picture can answer it.
 */
@Composable
fun DitherSwatch(
    label: String,
    description: String,
    swatch: ImageBitmap?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = MaterialTheme.shapes.small,
            )
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            if (swatch != null) {
                Image(
                    bitmap = swatch,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.None,
                )
            } else {
                LoadingSkeleton(modifier = Modifier.fillMaxSize(), cornerRadius = 6.dp)
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.titleMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
