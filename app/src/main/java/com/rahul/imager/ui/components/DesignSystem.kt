package com.rahul.imager.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rahul.imager.ui.theme.LocalStatusColors

/**
 * The app's design language, in one file.
 *
 * Screens are assembled from these rather than from raw Material components, which is what keeps
 * every surface, radius and button height in step. The rules the whole app follows:
 *
 *  - one blue for every action, neutrals for everything else;
 *  - white cards on a tinted page, separated by a hairline border rather than a heavy shadow;
 *  - a single 20 dp gutter, and a 56 dp touch height for anything primary.
 */

/** The page gutter. Every screen's content sits inside this. */
val ScreenGutter = 20.dp

/** Standard content padding for a scrolling screen body. */
val ScreenContentPadding = PaddingValues(horizontal = ScreenGutter, vertical = 8.dp)

// -------------------------------------------------------------------------------------------
// Headers
// -------------------------------------------------------------------------------------------

/**
 * The screen header.
 *
 * Deliberately not a `TopAppBar`: this one sits on the page background with no elevation or fill,
 * so the title reads as the first line of the content rather than as a bar bolted above it. Back
 * is a tappable tile on the left, matching the tile used in list rows.
 */
@Composable
fun ImagerHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenGutter, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            SquareIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                onClick = onBack,
            )
            Spacer(Modifier.width(14.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions?.let {
            Spacer(Modifier.width(12.dp))
            it()
        }
    }
}

/** A square, bordered icon button — the app's back affordance and its light-weight actions. */
@Composable
fun SquareIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(44.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * A group heading, optionally with an action on the right.
 *
 * The action is a plain blue label rather than a button: it is a shortcut, and giving it a button's
 * weight would put it in competition with whatever the screen actually wants the user to do.
 */
@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.extraSmall)
                    .clickable(onClick = onAction)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

// -------------------------------------------------------------------------------------------
// Surfaces
// -------------------------------------------------------------------------------------------

/**
 * The app's card.
 *
 * A hairline border does the separating and the shadow is barely there — on a tinted page that is
 * enough to lift a white card, and it keeps a list of them from turning into a stack of drop
 * shadows.
 */
@Composable
fun ImagerCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.medium,
    color: Color = MaterialTheme.colorScheme.surface,
    bordered: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val border = if (bordered) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    } else {
        null
    }
    val shadow = modifier.shadow(
        elevation = 1.dp,
        shape = shape,
        ambientColor = Color(0x14101B33),
        spotColor = Color(0x14101B33),
    )
    if (onClick != null) {
        Surface(onClick = onClick, modifier = shadow, shape = shape, color = color, border = border) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    } else {
        Surface(modifier = shadow, shape = shape, color = color, border = border) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}

/**
 * The full-bleed gradient block used at the top of a screen to carry its main action.
 *
 * One per screen at most. It is the loudest thing the design language has, so a second one on the
 * same screen just cancels the first one out.
 */
@Composable
fun HeroCard(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val status = LocalStatusColors.current
    val shape = MaterialTheme.shapes.large
    val base = modifier
        .fillMaxWidth()
        .shadow(10.dp, shape, ambientColor = Color(0x332F6BF6), spotColor = Color(0x332F6BF6))
        .clip(shape)
        .background(Brush.linearGradient(listOf(status.gradientStart, status.gradientEnd)))
    Box(modifier = if (onClick != null) base.clickable(onClick = onClick) else base) {
        Column(Modifier.padding(22.dp)) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(Color.White.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.height(16.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.82f),
            )
            action?.let {
                Spacer(Modifier.height(18.dp))
                it()
            }
        }
    }
}

// -------------------------------------------------------------------------------------------
// Buttons
// -------------------------------------------------------------------------------------------

private val ButtonHeight = 54.dp

/** The one button that carries a screen's main action. At most one per screen. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
) {
    val container by animateColorAsState(
        targetValue = if (enabled) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
        label = "primaryButtonContainer",
    )
    val content = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.fillMaxWidth().heightIn(min = ButtonHeight),
        shape = MaterialTheme.shapes.small,
        color = container,
        contentColor = content,
    ) {
        ButtonContent(text = text, icon = icon, loading = loading, contentColor = content)
    }
}

/** The quieter sibling: same size and shape, no fill. Used for the alternative on a screen. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
) {
    Surface(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.fillMaxWidth().heightIn(min = ButtonHeight),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        ButtonContent(
            text = text,
            icon = icon,
            loading = loading,
            contentColor = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A tonal button in the brand tint — for actions that sit inside a card. */
@Composable
fun TonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 44.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        ButtonContent(
            text = text,
            icon = icon,
            loading = false,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            horizontalPadding = 18.dp,
        )
    }
}

@Composable
private fun ButtonContent(
    text: String,
    icon: ImageVector?,
    loading: Boolean,
    contentColor: Color,
    horizontalPadding: Dp = 20.dp,
) {
    Row(
        modifier = Modifier.padding(horizontal = horizontalPadding, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = contentColor,
            )
            Spacer(Modifier.width(10.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp), tint = contentColor)
            Spacer(Modifier.width(10.dp))
        }
        Text(text = text, style = MaterialTheme.typography.labelLarge, color = contentColor)
    }
}

// -------------------------------------------------------------------------------------------
// Rows and pills
// -------------------------------------------------------------------------------------------

/** The tinted rounded-square that holds a row's icon. */
@Composable
fun IconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    background: Color = MaterialTheme.colorScheme.primaryContainer,
    size: Dp = 42.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(MaterialTheme.shapes.small)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.46f))
    }
}

/**
 * The settings-style row: icon tile, title, optional subtitle, and something on the right.
 *
 * `trailing` defaults to a chevron when the row navigates somewhere, and is replaced by a switch
 * or a value when it does not.
 */
@Composable
fun SettingRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    iconBackground: Color = MaterialTheme.colorScheme.primaryContainer,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val row: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(icon = icon, tint = iconTint, background = iconBackground)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            if (trailing != null) {
                trailing()
            } else if (onClick != null) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            color = Color.Transparent,
            shape = MaterialTheme.shapes.small,
        ) { row() }
    } else {
        Box(modifier.fillMaxWidth()) { row() }
    }
}

/** Groups [SettingRow]s into one card with hairlines between them. */
@Composable
fun SettingGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    ImagerCard(modifier = modifier, contentPadding = PaddingValues(vertical = 4.dp), content = content)
}

/** A small status capsule: coloured dot, label, tinted background. */
@Composable
fun StatusPill(
    text: String,
    color: Color,
    tint: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(tint)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        } else {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        }
        Spacer(Modifier.width(7.dp))
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/**
 * An inline notice with an optional action — the "connect a printer first" bar.
 *
 * Tone comes from the caller because the same shape carries information, a warning and an error;
 * only the wash and the icon colour change.
 */
@Composable
fun InlineBanner(
    text: String,
    icon: ImageVector,
    color: Color,
    tint: Color,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(tint)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        action?.let {
            Spacer(Modifier.width(10.dp))
            it()
        }
    }
}

/** A rounded pill that reads as a piece of metadata rather than a control. */
@Composable
fun MetaChip(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A hairline, used inside grouped cards. */
@Composable
fun RowDivider(modifier: Modifier = Modifier, inset: Dp = 70.dp) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = inset)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}
