package com.rahul.imager.ui.screens.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rahul.imager.R
import com.rahul.imager.ui.components.ImagerCard
import com.rahul.imager.ui.components.ImagerHeader
import com.rahul.imager.ui.components.SquareIconButton
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
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val traces by PrintTraceRecorder.traces.collectAsStateWithLifecycle()
    val copyToClipboard = rememberClipboardCopier(label = "Imager diagnostics")

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            ImagerHeader(
                title = stringResource(R.string.diagnostics_title),
                onBack = onBack,
                actions = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SquareIconButton(
                            icon = Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.diagnostics_copy_all),
                            onClick = { copyToClipboard(PrintTraceRecorder.dump()) },
                        )
                        SquareIconButton(
                            icon = Icons.Default.DeleteSweep,
                            contentDescription = stringResource(R.string.diagnostics_clear),
                            onClick = PrintTraceRecorder::clear,
                        )
                    }
                },
            )
        Box(modifier = Modifier.fillMaxSize()) {
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
                        ImagerCard(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(14.dp),
                        ) {
                            Text(
                                text = "${trace.printerName} · ${trace.outcome}",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
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
