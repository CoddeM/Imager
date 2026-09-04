package com.rahul.imager.ui.screens.printers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rahul.imager.R
import com.rahul.imager.printer.domain.PaperProfile
import com.rahul.imager.printer.domain.PaperProfiles
import com.rahul.imager.printer.domain.SavedPrinter
import com.rahul.imager.ui.components.ErrorPanel
import com.rahul.imager.ui.components.EmptyState
import com.rahul.imager.ui.components.SegmentedOption
import com.rahul.imager.ui.components.SegmentedOptionRow
import com.rahul.imager.ui.components.StateDot
import com.rahul.imager.ui.components.connectionStateLabel

/**
 * The printers screen.
 *
 * Reachable at any time and completely independent of the photo flow: printers are a persistent
 * app-level resource, not a step in a wizard.
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.printers_title)) }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddPrinter,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.action_add_printer)) },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (state.rows.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.Print,
                    title = stringResource(R.string.printers_empty_title),
                    message = stringResource(R.string.printers_empty_message),
                    action = {
                        TextButton(onClick = onAddPrinter) {
                            Text(stringResource(R.string.action_add_printer))
                        }
                    },
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
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

    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StateDot(row.state)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = row.printer.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (row.printer.isDefault) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = stringResource(R.string.printers_default_badge),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                Spacer(Modifier.width(4.dp))

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
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = buildString {
                        append(connectionStateLabel(row.state))
                        append("  ·  ")
                        append(stringResource(R.string.printers_paper_label, row.paper.label))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    }
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
