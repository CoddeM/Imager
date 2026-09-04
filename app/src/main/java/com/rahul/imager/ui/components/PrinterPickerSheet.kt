package com.rahul.imager.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rahul.imager.R
import com.rahul.imager.data.ConnectionState
import com.rahul.imager.printer.domain.PaperProfiles
import com.rahul.imager.printer.domain.SavedPrinter

/**
 * The printer sheet.
 *
 * The same sheet is reachable from the home top bar and from inside the preview screen, which is
 * what lets a user switch printers mid-flow and land straight back on their photo. It never
 * navigates away by itself: choosing a printer closes the sheet and leaves the user exactly where
 * they were.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterPickerSheet(
    printers: List<SavedPrinter>,
    states: Map<String, ConnectionState>,
    selectedId: String?,
    onSelect: (SavedPrinter) -> Unit,
    onReconnect: (SavedPrinter) -> Unit,
    onAddPrinter: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.printers_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onAddPrinter) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_add_printer))
                }
            }

            if (printers.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.Print,
                    title = stringResource(R.string.printers_empty_title),
                    message = stringResource(R.string.printers_empty_message),
                    action = {
                        TextButton(onClick = onAddPrinter) {
                            Text(stringResource(R.string.action_add_printer))
                        }
                    },
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(printers, key = { it.id }) { printer ->
                        val state = states[printer.id] ?: ConnectionState.Disconnected
                        PrinterSheetRow(
                            printer = printer,
                            state = state,
                            selected = printer.id == selectedId,
                            onSelect = { onSelect(printer) },
                            onReconnect = { onReconnect(printer) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PrinterSheetRow(
    printer: SavedPrinter,
    state: ConnectionState,
    selected: Boolean,
    onSelect: () -> Unit,
    onReconnect: () -> Unit,
) {
    val paper = PaperProfiles.resolve(printer.paperProfileId)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StateDot(state)

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = printer.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (selected) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = stringResource(R.string.printers_default_badge),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = buildString {
                    append(connectionStateLabel(state))
                    append("  ·  ")
                    append(printer.transport.name.lowercase())
                    if (printer.identifier.isNotBlank()) {
                        append("  ·  ")
                        append(printer.identifier)
                    }
                    append("  ·  ")
                    append(paper.label)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        IconButton(onClick = onReconnect) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = stringResource(R.string.action_reconnect),
            )
        }
    }
    Spacer(Modifier.height(0.dp))
}
