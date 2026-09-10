package com.rahul.imager.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rahul.imager.ui.theme.CodeTextStyle

/**
 * The small heading that separates groups of options.
 *
 * Quieter than [SectionTitle], which heads a whole screen section — this one labels a control
 * group inside a card, so it steps back to a muted uppercase label.
 */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/**
 * A designed empty state.
 *
 * Empty is the FIRST thing a new user sees, so it gets an icon, a headline, an explanation and,
 * where there is something useful to do, an action — never a blank screen.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconTile(
            icon = icon,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            background = MaterialTheme.colorScheme.surfaceContainer,
            size = 56.dp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        action?.let {
            Spacer(Modifier.height(4.dp))
            it()
        }
    }
}

/**
 * A slider with its label and current value on one line.
 *
 * The value is shown as text as well as position because "roughly two thirds along" is not a
 * setting anybody can reproduce or describe to somebody else.
 */
@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueLabel: String = value.toInt().toString(),
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            onValueChangeFinished = onValueChangeFinished,
        )
    }
}

/** One option in a [SegmentedOptionRow]. */
data class SegmentedOption<T>(
    val value: T,
    val label: String,
    val enabled: Boolean = true,
)

/**
 * A wrapping row of single-choice chips.
 *
 * Wrapping rather than a fixed segmented control, because these option sets have to survive a
 * 320 dp screen without truncating labels into meaninglessness.
 */
@Composable
fun <T> SegmentedOptionRow(
    options: List<SegmentedOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            val isSelected = option.value == selected
            val container = when {
                !option.enabled -> MaterialTheme.colorScheme.surfaceContainer
                isSelected -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.surface
            }
            val content = when {
                !option.enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                isSelected -> Color.White
                else -> MaterialTheme.colorScheme.onSurface
            }
            Surface(
                onClick = { onSelect(option.value) },
                enabled = option.enabled,
                shape = CircleShape,
                color = container,
                border = if (isSelected || !option.enabled) {
                    null
                } else {
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                },
            ) {
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = content,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** The `PRN-…` code, in monospace so it can be read out or copied accurately. */
@Composable
fun CodeText(code: String, modifier: Modifier = Modifier) {
    Text(
        text = code,
        style = CodeTextStyle,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * A shimmering placeholder block.
 *
 * Used while a raster or a thumbnail is being produced, so the layout does not jump when the real
 * content arrives.
 */
@Composable
fun LoadingSkeleton(
    modifier: Modifier = Modifier,
    cornerRadius: androidx.compose.ui.unit.Dp = 12.dp,
) {
    val alpha by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 280),
        label = "skeletonAlpha",
    )
    val color by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = tween(durationMillis = 280),
        label = "skeletonColor",
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .alpha(alpha)
            .background(color),
    )
}

/** A thin divider that reads as a fold in paper rather than a hard rule. */
@Composable
fun SoftDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/** A labelled key/value line, used in the identify and diagnostics screens. */
@Composable
fun DetailRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = LocalContentColor.current,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(112.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Content padding used consistently by the scrolling option panels. */
val OptionsContentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)
