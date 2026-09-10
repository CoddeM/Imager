package com.rahul.imager.ui.screens.printers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rahul.imager.R
import com.rahul.imager.data.ConnectionState
import com.rahul.imager.printer.domain.PaperProfile
import com.rahul.imager.printer.domain.PaperProfiles
import com.rahul.imager.printer.domain.SavedPrinter
import com.rahul.imager.ui.components.ErrorPanel
import com.rahul.imager.ui.components.EmptyState
import com.rahul.imager.ui.components.IconTile
import com.rahul.imager.ui.components.ImagerCard
import com.rahul.imager.ui.components.ImagerHeader
import com.rahul.imager.ui.components.MetaChip
import com.rahul.imager.ui.components.ScreenGutter
import com.rahul.imager.ui.components.SegmentedOption
import com.rahul.imager.ui.components.SegmentedOptionRow
import com.rahul.imager.ui.components.StatusPill
import com.rahul.imager.ui.components.TonalButton
import com.rahul.imager.ui.components.connectionStateTint
import com.rahul.imager.ui.theme.LocalStatusColors
import com.rahul.imager.ui.components.connectionStateLabel

/**
 * The printers screen.
 *
 * Reachable at any time and completely independent of the photo flow: printers are a persistent
 * app-level resource, not a step in a wizard.
 */
@Composable
fun PrintersScreen(
    onAddPrinter: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PrintersViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<SavedPrinter?>(null) }
    var forgetting by remember { mutableStateOf<SavedPrinter?>(null) }
    var changingPaper by remember { mutableStateOf<PrinterRow?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ImagerHeader(title = stringResource(R.string.printers_title))

            if (state.rows.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        icon = Icons.Default.Print,
                        title = stringResource(R.string.printers_empty_title),
                        message = stringResource(R.string.printers_empty_message),
                        action = {
                            TonalButton(
                                text = stringResource(R.string.action_add_printer),
                                icon = Icons.Default.Add,
                                onClick = onAddPrinter,
                            )
                        },
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(ScreenGutter, 4.dp, ScreenGutter, 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.rows, key = { it.printer.id }) { row ->
                        PrinterCard(
                            row = row,
                            testPrint = state.testPrint,
                            onSetDefault = { viewModel.setDefault(row.printer) },
                            onRename = { renaming = row.printer },
                            onForget = { forgetting = row.printer },
                            onPaper = { changingPaper = row },
                            onTestPrint = { viewModel.runTestPrint(row.printer) },
                            onReconnect = { viewModel.reconnect(row.printer) },
                            onDisconnect = { viewModel.disconnect(row.printer) },
                        )
                    }
                }
            }
        }

        // Floating rather than a header action: adding a printer is the one thing this screen
        // exists to make easy, and it stays in thumb reach however far the list is scrolled.
        if (state.rows.isNotEmpty()) {
            ExtendedFloatingActionButton(
                onClick = onAddPrinter,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(ScreenGutter),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.action_add_printer)) },
            )
        }
    }

    renaming?.let { printer ->
        RenameDialog(
            printer = printer,
            onConfirm = { name ->
                viewModel.rename(printer, name)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }

    forgetting?.let { printer ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text(stringResource(R.string.printers_forget_title)) },
            text = {
                Text(stringResource(R.string.printers_forget_message, printer.displayName))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.forget(printer)
                        forgetting = null
                    },
                ) { Text(stringResource(R.string.action_forget)) }
            },
            dismissButton = {
                TextButton(onClick = { forgetting = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    changingPaper?.let { row ->
        PaperDialog(
            current = row.paper,
            onConfirm = { profile ->
                viewModel.setPaperProfile(row.printer, profile)
                changingPaper = null
            },
            onDismiss = { changingPaper = null },
        )
    }

    (state.testPrint as? TestPrintState.Failed)?.let { failed ->
        AlertDialog(
            onDismissRequest = viewModel::dismissTestPrint,
            title = { Text(stringResource(R.string.action_test_print)) },
            text = { ErrorPanel(error = failed.error) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissTestPrint) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }
}

@Composable
private fun PrinterCard(
    row: PrinterRow,
    testPrint: TestPrintState,
    onSetDefault: () -> Unit,
    onRename: () -> Unit,
    onForget: () -> Unit,
    onPaper: () -> Unit,
    onTestPrint: () -> Unit,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val isTesting = (testPrint as? TestPrintState.Running)?.printerId == row.printer.id

    val statusColors = LocalStatusColors.current
    val stateColor = when (row.state) {
        is ConnectionState.Connected -> statusColors.connected
        ConnectionState.Connecting -> statusColors.warning
        is ConnectionState.Failed -> statusColors.error
        ConnectionState.Disconnected -> statusColors.idle
    }

    ImagerCard(modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(icon = Icons.Default.Print, size = 46.dp)
            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.printer.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (row.printer.isDefault) {
                        Spacer(Modifier.width(8.dp))
                        DefaultBadge()
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = buildString {
                        append(row.printer.brand.name.lowercase().replaceFirstChar(Char::uppercase))
                        append("  ·  ")
                        append(row.printer.transport.name.lowercase())
                        if (row.printer.identifier.isNotBlank()) {
                            append("  ·  ")
                            append(row.printer.identifier)
                            if (row.printer.transport ==
                                com.rahul.imager.printer.domain.TransportType.LAN
                            ) {
                                append(':').append(row.printer.port)
                            }
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (isTesting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }

            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.action_more),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_set_default)) },
                        onClick = { menuOpen = false; onSetDefault() },
                        enabled = !row.printer.isDefault,
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_test_print)) },
                        onClick = { menuOpen = false; onTestPrint() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.printers_paper_title)) },
                        onClick = { menuOpen = false; onPaper() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_rename)) },
                        onClick = { menuOpen = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_reconnect)) },
                        onClick = { menuOpen = false; onReconnect() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_disconnect)) },
                        onClick = { menuOpen = false; onDisconnect() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_forget)) },
                        onClick = { menuOpen = false; onForget() },
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // State and paper as pills rather than another line of grey text: these are the two things
        // a user scans this list for, and they have to survive a glance.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusPill(
                text = connectionStateLabel(row.state),
                color = stateColor,
                tint = connectionStateTint(row.state),
            )
            MetaChip(text = stringResource(R.string.printers_paper_label, row.paper.label))
        }
    }
}

/** The "Default" marker on the printer that photos are sent to unless told otherwise. */
@Composable
private fun DefaultBadge(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.printers_default_badge),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

@Composable
private fun RenameDialog(
    printer: SavedPrinter,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(printer.displayName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.printers_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.printers_rename_label)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun PaperDialog(
    current: PaperProfile,
    onConfirm: (PaperProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(current.id) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.printers_paper_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.printers_paper_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
                SegmentedOptionRow(
                    options = PaperProfiles.GRAPHICS_CAPABLE.map {
                        SegmentedOption(it.id, it.label)
                    },
                    selected = selected,
                    onSelect = { selected = it },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(PaperProfiles.resolve(selected)) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
