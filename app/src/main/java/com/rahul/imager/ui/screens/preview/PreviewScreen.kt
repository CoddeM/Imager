package com.rahul.imager.ui.screens.preview

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rahul.imager.R
import com.rahul.imager.printer.engine.PrintProgress
import com.rahul.imager.printer.engine.PrintTraceRecorder
import com.rahul.imager.printer.raster.RasterWarning
import com.rahul.imager.ui.components.ErrorPanel
import com.rahul.imager.ui.components.InlineNotice
import com.rahul.imager.ui.components.LoadingSkeleton
import com.rahul.imager.ui.components.PaperCanvas
import com.rahul.imager.ui.components.PrinterPickerSheet
import com.rahul.imager.ui.components.PrinterStatusChip
import com.rahul.imager.ui.components.rememberClipboardCopier

/**
 * The preview and options screen: the core of the app.
 *
 * The canvas is a true WYSIWYG of the printed dots, and the options beside it re-render it live.
 * The layout adapts rather than scaling: one column with a bottom sheet on a phone in portrait,
 * two panes everywhere there is room, because comparing an option against its effect is the whole
 * point and a sheet covering the picture defeats it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    windowSizeClass: WindowSizeClass,
    onBack: () -> Unit,
    onAddPrinter: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PreviewViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableStateOf(OptionsTab.SIZE) }
    var showOptionsSheet by rememberSaveable { mutableStateOf(false) }
    var showPrinterSheet by rememberSaveable { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val twoPane = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact || isLandscape

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
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.preview_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        if (state.outputWidthDots > 0) {
                            Text(
                                text = stringResource(
                                    R.string.preview_output_size,
                                    state.outputWidthDots,
                                    state.outputHeightDots,
                                    state.outputLengthMm,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    PrinterStatusChip(
                        printer = state.selectedPrinter,
                        state = state.printerState,
                        onClick = { showPrinterSheet = true },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                },
            )
        },
        bottomBar = {
            PrintBar(
                state = state,
                showOptionsButton = !twoPane,
                onOptions = { showOptionsSheet = true },
                onPrint = viewModel::print,
                onChoosePrinter = { showPrinterSheet = true },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            if (twoPane) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f)) {
                        PreviewPane(state = state)
                    }
                    Column(
                        modifier = Modifier
                            .width(OPTIONS_RAIL_WIDTH)
                            .fillMaxHeight(),
                    ) {
                        OptionsTabs(selectedTab) { selectedTab = it }
                        OptionsPanel(
                            tab = selectedTab,
                            state = state,
                            viewModel = viewModel,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            } else {
                PreviewPane(state = state)
            }
        }
    }

    if (showOptionsSheet && !twoPane) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        ModalBottomSheet(
            onDismissRequest = { showOptionsSheet = false },
            sheetState = sheetState,
        ) {
            Column(modifier = Modifier.navigationBarsPadding()) {
                OptionsTabs(selectedTab) { selectedTab = it }
                OptionsPanel(
                    tab = selectedTab,
                    state = state,
                    viewModel = viewModel,
                    modifier = Modifier.height(OPTIONS_SHEET_HEIGHT),
                )
            }
        }
    }

    if (showPrinterSheet) {
        PrinterPickerSheet(
            printers = state.printers,
            states = state.connectionStates,
            selectedId = state.selectedPrinter?.id,
            onSelect = {
                viewModel.selectPrinter(it)
                showPrinterSheet = false
            },
            onReconnect = viewModel::reconnect,
            onAddPrinter = {
                showPrinterSheet = false
                onAddPrinter()
            },
            onDismiss = { showPrinterSheet = false },
        )
    }

    if (state.printState != PrintUiState.Idle) {
        PrintingSheet(
            printState = state.printState,
            onCancel = viewModel::cancelPrint,
            onDismiss = viewModel::dismissPrintResult,
            onRetry = {
                viewModel.dismissPrintResult()
                viewModel.print()
            },
            onChangePrinter = {
                viewModel.dismissPrintResult()
                showPrinterSheet = true
            },
        )
    }
}

/** The canvas plus any non-fatal notices about this raster. */
@Composable
private fun PreviewPane(state: PreviewUiState, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        state.rasterError?.let { error ->
            ErrorPanel(error = error, modifier = Modifier.padding(16.dp))
        }

        state.warnings.forEach { warning ->
            InlineNotice(
                text = when (warning) {
                    RasterWarning.SOURCE_NARROWER_THAN_PAPER ->
                        stringResource(R.string.preview_warning_low_resolution)

                    RasterWarning.ROTATION_SUGGESTED ->
                        stringResource(R.string.preview_warning_rotation)

                    RasterWarning.VERY_LONG_PRINT ->
                        stringResource(R.string.preview_warning_long, state.outputLengthMm)

                    RasterWarning.DECODED_AT_LOWER_QUALITY ->
                        stringResource(R.string.preview_warning_low_quality)

                    RasterWarning.SHRUNK_TO_FIT_PAPER ->
                        stringResource(R.string.preview_warning_shrunk)
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            if (state.rasterBitmap == null && state.rasterError == null) {
                LoadingSkeleton(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                )
            } else {
                PaperCanvas(
                    raster = state.rasterBitmap,
                    original = state.originalBitmap,
                    paperWidthDots = state.paper.widthDots.coerceAtLeast(1),
                    horizontalBias = when (state.options.alignment) {
                        com.rahul.imager.printer.raster.Alignment.LEFT -> -1f
                        com.rahul.imager.printer.raster.Alignment.CENTER -> 0f
                        com.rahul.imager.printer.raster.Alignment.RIGHT -> 1f
                    },
                )
            }

            // A quiet, non-blocking indicator: the previous raster stays on screen while the next
            // one renders, so the preview never flashes empty during a slider drag.
            if (state.isRendering) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .size(20.dp),
                    strokeWidth = 2.dp,
                )
            }
        }

        Text(
            text = stringResource(R.string.preview_compare_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
        )
    }
}

@Composable
private fun OptionsTabs(selected: OptionsTab, onSelect: (OptionsTab) -> Unit) {
    PrimaryTabRow(selectedTabIndex = selected.ordinal) {
        OptionsTab.entries.forEach { tab ->
            Tab(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                text = { Text(stringResource(tab.labelRes)) },
            )
        }
    }
}

/** The bottom bar: one prominent Print button, with an inline reason when it cannot run. */
@Composable
private fun PrintBar(
    state: PreviewUiState,
    showOptionsButton: Boolean,
    onOptions: () -> Unit,
    onPrint: () -> Unit,
    onChoosePrinter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val blocked = state.printBlockedReason

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        blocked?.let { reason ->
            val message = when (reason) {
                PrintBlockedReason.NO_PRINTER -> stringResource(R.string.preview_no_printer)
                PrintBlockedReason.PAPER_CANNOT_PRINT_IMAGES ->
                    stringResource(R.string.preview_cannot_print_images)

                PrintBlockedReason.RASTER_FAILED -> stringResource(R.string.err_unknown_title)
                PrintBlockedReason.NOT_READY -> stringResource(R.string.preview_rendering)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                InlineNotice(text = message, modifier = Modifier.weight(1f))
                if (reason == PrintBlockedReason.NO_PRINTER) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onChoosePrinter) {
                        Text(stringResource(R.string.action_choose_printer))
                    }
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showOptionsButton) {
                OutlinedButton(onClick = onOptions) {
                    Icon(Icons.Default.Tune, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.tab_size))
                }
            }
            Button(
                onClick = onPrint,
                enabled = blocked == null,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Print, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_print))
            }
        }
    }
}

/**
 * The printing sheet.
 *
 * The screen is kept awake for exactly as long as this sheet is on screen: a photo takes tens of
 * seconds on a 203 dpi head, and a device that sleeps mid-print leaves the user staring at a
 * half-printed strip wondering what happened.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrintingSheet(
    printState: PrintUiState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onChangePrinter: () -> Unit,
) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val copyToClipboard = rememberClipboardCopier(label = "Imager diagnostics")
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = {
            if (printState is PrintUiState.Running) onCancel() else onDismiss()
        },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedContent(targetState = printState::class, label = "printState") { _ ->
                when (printState) {
                    is PrintUiState.Running -> RunningContent(printState, onCancel)

                    PrintUiState.Succeeded -> SuccessContent(
                        onPrintAgain = onRetry,
                        onDismiss = onDismiss,
                    )

                    is PrintUiState.Failed -> FailureContent(
                        printState = printState,
                        onRetry = onRetry,
                        onChangePrinter = onChangePrinter,
                        onCopyDiagnostics = {
                            copyToClipboard(PrintTraceRecorder.dump())
                        },
                        onDismiss = onDismiss,
                    )

                    PrintUiState.Idle -> Unit
                }
            }
        }
    }
}

@Composable
private fun RunningContent(state: PrintUiState.Running, onCancel: () -> Unit) {
    val label = when (val progress = state.progress) {
        PrintProgress.Resolving -> stringResource(R.string.printing_resolving)
        PrintProgress.Connecting -> stringResource(R.string.printing_connecting)
        is PrintProgress.Sending -> stringResource(
            R.string.printing_sending,
            progress.bandsSent,
            progress.totalBands,
        )

        PrintProgress.Finishing -> stringResource(R.string.printing_finishing)
    }

    // Indeterminate until the first band lands, then determinate: the transition is the moment the
    // user learns the printer actually accepted the job.
    val fraction = (state.progress as? PrintProgress.Sending)?.fraction
    val animated by animateFloatAsState(
        targetValue = fraction ?: 0f,
        animationSpec = tween(200),
        label = "printProgress",
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.printing_title), style = MaterialTheme.typography.titleLarge)
        if (state.copies > 1) {
            Text(
                text = stringResource(R.string.printing_copy_of, state.copy, state.copies),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (fraction == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { animated },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
    }
}

@Composable
private fun SuccessContent(onPrintAgain: () -> Unit, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(stringResource(R.string.printing_success), style = MaterialTheme.typography.titleLarge)
        Text(
            text = stringResource(R.string.printing_success_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onPrintAgain, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.action_print_again))
            }
            Button(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.action_done))
            }
        }
    }
}

@Composable
private fun FailureContent(
    printState: PrintUiState.Failed,
    onRetry: () -> Unit,
    onChangePrinter: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ErrorPanel(error = printState.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRetry, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.action_retry))
            }
            OutlinedButton(onClick = onChangePrinter, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.action_change_printer))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onCopyDiagnostics, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.action_copy_diagnostics))
            }
            TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.action_close))
            }
        }
    }
}

private val OPTIONS_RAIL_WIDTH = 360.dp
private val OPTIONS_SHEET_HEIGHT = 420.dp
