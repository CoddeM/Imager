package com.rahul.imager.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rahul.imager.R
import com.rahul.imager.data.ConnectionState
import com.rahul.imager.error.PrintErrorCatalog
import com.rahul.imager.printer.domain.PrintError
import com.rahul.imager.printer.domain.SavedPrinter
import com.rahul.imager.ui.theme.LocalStatusColors

/**
 * The coloured dot that says what a printer is doing.
 *
 * Colour alone never carries the meaning — every place this appears, the state is also written out
 * in words next to it.
 */
@Composable
fun StateDot(state: ConnectionState, modifier: Modifier = Modifier) {
    val statusColors = LocalStatusColors.current
    val target = when (state) {
        is ConnectionState.Connected -> statusColors.connected
        ConnectionState.Connecting -> statusColors.warning
        is ConnectionState.Failed -> statusColors.error
        ConnectionState.Disconnected -> statusColors.idle
    }
    val color by animateColorAsState(target, tween(200), label = "stateDot")
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/** The words for a connection state. */
@Composable
fun connectionStateLabel(state: ConnectionState): String = when (state) {
    is ConnectionState.Connected -> stringResource(R.string.printer_state_connected)
    ConnectionState.Connecting -> stringResource(R.string.printer_state_connecting)
    is ConnectionState.Failed -> stringResource(R.string.printer_state_error)
    ConnectionState.Disconnected -> stringResource(R.string.printer_state_disconnected)
}

/**
 * The printer chip in the top bar.
 *
 * It appears identically on Home and on Preview and always does the same thing, which is what lets
 * the user switch printers from inside the photo flow without ever leaving it.
 */
@Composable
fun PrinterStatusChip(
    printer: SavedPrinter?,
    state: ConnectionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.printer_chip_content_description)
    AssistChip(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = description },
        leadingIcon = {
            if (printer == null) {
                Icon(
                    imageVector = Icons.Default.Print,
                    contentDescription = null,
                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                )
            } else {
                StateDot(state)
            }
        },
        label = {
            Text(
                text = printer?.displayName ?: stringResource(R.string.printer_chip_none),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp),
            )
        },
    )
}

/**
 * The full error presentation: what happened, why, what to do, and the code.
 *
 * All four parts are always shown. A message without a recovery action leaves the user stuck, and
 * a message without the code leaves support guessing.
 */
@Composable
fun ErrorPanel(
    error: PrintError,
    modifier: Modifier = Modifier,
    actions: (@Composable () -> Unit)? = null,
) {
    val presentation = PrintErrorCatalog.presentationFor(error.category)
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(presentation.titleRes),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                text = stringResource(presentation.messageRes),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(presentation.actionRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            CodeText(
                code = buildString {
                    append(presentation.code)
                    error.context.vendorErrorCode?.let { append("  ·  ").append(it) }
                },
            )
            actions?.let {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { it() }
            }
        }
    }
}

/** A compact inline warning, for non-fatal advice inside the preview screen. */
@Composable
fun InlineNotice(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(container)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = content,
        )
    }
}
