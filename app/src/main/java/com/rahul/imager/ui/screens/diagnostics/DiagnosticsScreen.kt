package com.rahul.imager.ui.screens.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rahul.imager.R
import com.rahul.imager.printer.engine.PrintTraceRecorder
import com.rahul.imager.ui.components.EmptyState
import com.rahul.imager.ui.components.rememberClipboardCopier
import com.rahul.imager.ui.theme.CodeTextStyle

/**
 * The hidden diagnostics screen.
 *
 * One structured line per print attempt, exactly as it was written to logcat, plus a copy button.
 * When a printer misbehaves in the field this is the difference between a useful bug report and
 * "it did not work".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val traces by PrintTraceRecorder.traces.collectAsStateWithLifecycle()
    val copyToClipboard = rememberClipboardCopier(label = "Imager diagnostics")

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                title = { Text(stringResource(R.string.diagnostics_title)) },
                actions = {
                    TextButton(
                        onClick = {
                            copyToClipboard(PrintTraceRecorder.dump())
                        },
                        enabled = traces.isNotEmpty(),
                    ) { Text(stringResource(R.string.diagnostics_copy_all)) }
                    TextButton(
                        onClick = PrintTraceRecorder::clear,
                        enabled = traces.isNotEmpty(),
                    ) { Text(stringResource(R.string.diagnostics_clear)) }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (traces.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.BugReport,
                    title = stringResource(R.string.diagnostics_title),
                    message = stringResource(R.string.diagnostics_empty),
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(traces) { trace ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "${trace.printerName} · ${trace.outcome}",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = trace.toLogLine(),
                                    style = CodeTextStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
