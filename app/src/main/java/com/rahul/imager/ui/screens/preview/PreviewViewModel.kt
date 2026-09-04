package com.rahul.imager.ui.screens.preview

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.rahul.imager.data.ConnectionState
import com.rahul.imager.data.PrintOptionsStore
import com.rahul.imager.data.PrinterConnectionManager
import com.rahul.imager.data.PrinterStore
import com.rahul.imager.data.RecentPrintsStore
import com.rahul.imager.printer.domain.PaperProfile
import com.rahul.imager.printer.domain.PaperProfiles
import com.rahul.imager.printer.domain.PrintCategory
import com.rahul.imager.printer.domain.PrintError
import com.rahul.imager.printer.domain.SavedPrinter
import com.rahul.imager.printer.engine.PrintProgress
import com.rahul.imager.printer.raster.CropRect
import com.rahul.imager.printer.raster.DitherMode
import com.rahul.imager.printer.raster.PrintOptions
import com.rahul.imager.printer.raster.PrintPreset
import com.rahul.imager.printer.raster.RasterCore
import com.rahul.imager.printer.raster.RasterJob
import com.rahul.imager.printer.raster.RasterOutcome
import com.rahul.imager.printer.raster.RasterWarning
import com.rahul.imager.ui.components.loadThumbnail
import com.rahul.imager.ui.navigation.PreviewRoute
import com.rahul.imager.usecase.BuildRasterUseCase
import com.rahul.imager.usecase.PickPhotoUseCase
import com.rahul.imager.usecase.PrintPhotoUseCase
import com.rahul.imager.usecase.PrintStep
import com.rahul.imager.usecase.ResolvePaperProfileUseCase
import com.rahul.imager.usecase.SetDefaultPrinterUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Where a print attempt has got to, as the printing sheet renders it. */
sealed interface PrintUiState {

    data object Idle : PrintUiState

    data class Running(
        val progress: PrintProgress,
        val copy: Int,
        val copies: Int,
    ) : PrintUiState

    data object Succeeded : PrintUiState

    data class Failed(val error: PrintError) : PrintUiState
}

/** Everything the preview screen renders. */
data class PreviewUiState(
    val imageUri: Uri? = null,
    val options: PrintOptions = PrintOptions(),
    val preset: PrintPreset = PrintPreset.PHOTO,
    val paper: PaperProfile = PaperProfiles.FALLBACK,
    val printers: List<SavedPrinter> = emptyList(),
    val selectedPrinter: SavedPrinter? = null,
    val connectionStates: Map<String, ConnectionState> = emptyMap(),
    val rasterBitmap: ImageBitmap? = null,
    val originalBitmap: ImageBitmap? = null,
    val histogram: IntArray? = null,
    val swatches: Map<DitherMode, ImageBitmap> = emptyMap(),
    val warnings: List<RasterWarning> = emptyList(),
    val outputWidthDots: Int = 0,
    val outputHeightDots: Int = 0,
    val isRendering: Boolean = false,
    val rasterError: PrintError? = null,
    val printState: PrintUiState = PrintUiState.Idle,
) {
    /** The state of the printer the chip is showing. */
    val printerState: ConnectionState
        get() = selectedPrinter?.let { connectionStates[it.id] } ?: ConnectionState.Disconnected

    /** Length of paper this print will consume, in millimetres at 203 dpi. */
    val outputLengthMm: Int get() = outputHeightDots / 8

    /** Why the Print button is disabled, or `null` when it can be tapped. */
    val printBlockedReason: PrintBlockedReason?
        get() = when {
            selectedPrinter == null -> PrintBlockedReason.NO_PRINTER
            !paper.supportsGraphics -> PrintBlockedReason.PAPER_CANNOT_PRINT_IMAGES
            rasterError != null -> PrintBlockedReason.RASTER_FAILED
            rasterBitmap == null -> PrintBlockedReason.NOT_READY
            else -> null
        }
}

/** Why printing is currently unavailable, so the UI can say so inline instead of just greying. */
enum class PrintBlockedReason { NO_PRINTER, PAPER_CANNOT_PRINT_IMAGES, RASTER_FAILED, NOT_READY }

/**
 * The preview and options screen.
 *
 * Two things here are worth reading before changing anything.
 *
 *  * Re-rastering is DEBOUNCED and cancellable. Every slider movement cancels the render in flight
 *    and schedules another 150 ms later, so dragging a slider across its whole range costs one
 *    render rather than three hundred.
 *  * While the user is dragging, the render runs at reduced resolution and switches to full
 *    resolution on release. The preview stays honest either way — it is the same pipeline, just a
 *    narrower paper — but it stays responsive on a mid-range phone.
 */
@HiltViewModel
class PreviewViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    savedStateHandle: SavedStateHandle,
    private val buildRaster: BuildRasterUseCase,
    private val printPhoto: PrintPhotoUseCase,
    private val printerStore: PrinterStore,
    private val printOptionsStore: PrintOptionsStore,
    private val recentPrintsStore: RecentPrintsStore,
    private val connectionManager: PrinterConnectionManager,
    private val setDefaultPrinter: SetDefaultPrinterUseCase,
    private val resolvePaperProfile: ResolvePaperProfileUseCase,
    private val pickPhoto: PickPhotoUseCase,
) : ViewModel() {

    private val route: PreviewRoute = savedStateHandle.toRoute()

    private val _uiState = MutableStateFlow(
        PreviewUiState(imageUri = Uri.parse(route.imageUri))
    )
    val uiState: StateFlow<PreviewUiState> = _uiState.asStateFlow()

    /** The decoded source photo, kept for the compare overlay, the crop and the swatches. */
    private var sourceBitmap: Bitmap? = null

    /** The last full raster, reused by Print so the bytes on paper are exactly what was previewed. */
    private var currentJob: RasterJob? = null

    private var renderJob: Job? = null
    private var swatchJob: Job? = null
    private var printJob: Job? = null

    /** Set while a slider is being dragged; drives the reduced preview resolution. */
    private var dragging = false

    init {
        viewModelScope.launch {
            printOptionsStore.lastUsedOptions.collect { stored ->
                // Only the first emission seeds the screen; later ones are our own writes.
                if (currentJob == null && _uiState.value.rasterBitmap == null) {
                    _uiState.value = _uiState.value.copy(
                        options = stored,
                        preset = PrintPreset.of(stored),
                    )
                    scheduleRender(immediate = true)
                }
            }
        }

        viewModelScope.launch {
            combine(
                printerStore.printers,
                printerStore.defaultPrinter,
                connectionManager.states,
                printOptionsStore.paperOverrides,
            ) { printers, default, states, overrides ->
                val selected = _uiState.value.selectedPrinter
                    ?.let { current -> printers.firstOrNull { it.id == current.id } }
                    ?: default
                val paper = selected?.let { resolvePaperProfile(it, overrides) }
                    ?: PaperProfiles.FALLBACK
                Triple(printers, selected, paper) to states
            }.collect { (triple, states) ->
                val (printers, selected, paper) = triple
                val paperChanged = paper.id != _uiState.value.paper.id
                _uiState.value = _uiState.value.copy(
                    printers = printers,
                    selectedPrinter = selected,
                    connectionStates = states,
                    paper = paper,
                )
                if (paperChanged) scheduleRender(immediate = true)
            }
        }

        loadSource()
    }

    // ---------------------------------------------------------------------------------------
    // Options
    // ---------------------------------------------------------------------------------------

    /** Applies an options change and schedules a re-render. */
    fun updateOptions(transform: (PrintOptions) -> PrintOptions) {
        val next = transform(_uiState.value.options).sanitized()
        if (next == _uiState.value.options) return
        _uiState.value = _uiState.value.copy(options = next, preset = PrintPreset.of(next))
        scheduleRender()
        scheduleSwatches()
        // Persisted on release rather than on every tick: a slider drag would otherwise be
        // hundreds of DataStore writes for one setting the user has not finished choosing.
        if (!dragging) persistOptions()
    }

    /** Writes the current options as the ones the next photo should open with. */
    private fun persistOptions() {
        val options = _uiState.value.options
        viewModelScope.launch { printOptionsStore.saveLastUsed(options) }
    }

    /** Applies a preset bundle. */
    fun applyPreset(preset: PrintPreset) {
        if (preset == PrintPreset.CUSTOM) return
        updateOptions { preset.applyTo(it) }
    }

    /** Called when a slider drag begins, so the preview drops to a faster resolution. */
    fun onDragStart() {
        dragging = true
    }

    /** Called on release: renders once more at full resolution. */
    fun onDragEnd() {
        dragging = false
        scheduleRender(immediate = true)
        persistOptions()
    }

    fun rotate(degrees: Int) = updateOptions { it.copy(rotationDegrees = it.rotationDegrees + degrees) }

    fun setCrop(crop: CropRect?) = updateOptions { it.copy(crop = crop) }

    /** Overrides the paper width for this printer, and remembers the choice. */
    fun setPaperProfile(profile: PaperProfile) {
        val printer = _uiState.value.selectedPrinter
        _uiState.value = _uiState.value.copy(paper = profile)
        scheduleRender(immediate = true)
        if (printer != null) {
            viewModelScope.launch {
                printOptionsStore.setPaperOverride(printer.id, profile.id)
            }
        }
    }

    /** Switches the target printer from inside the preview, without leaving the photo. */
    fun selectPrinter(printer: SavedPrinter) {
        _uiState.value = _uiState.value.copy(selectedPrinter = printer)
        viewModelScope.launch { setDefaultPrinter(printer) }
    }

    fun reconnect(printer: SavedPrinter) = connectionManager.connect(printer)

    // ---------------------------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------------------------

    private fun loadSource() {
        val uri = _uiState.value.imageUri ?: return
        pickPhoto.persistAccess(uri)
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                loadThumbnail(
                    context = appContext,
                    uri = uri,
                    targetPx = SOURCE_PREVIEW_PX,
                )
            }
            sourceBitmap = bitmap
            _uiState.value = _uiState.value.copy(originalBitmap = bitmap?.asImageBitmap())
            scheduleRender(immediate = true)
            scheduleSwatches()
        }
    }

    /**
     * Schedules a render.
     *
     * The previous render is CANCELLED rather than allowed to finish: a stale raster arriving
     * after a newer one would make the preview flicker backwards.
     */
    private fun scheduleRender(immediate: Boolean = false) {
        renderJob?.cancel()
        val uri = _uiState.value.imageUri ?: return
        renderJob = viewModelScope.launch {
            if (!immediate) delay(RENDER_DEBOUNCE_MS)
            _uiState.value = _uiState.value.copy(isRendering = true)

            val state = _uiState.value
            val scale = if (dragging) DRAG_RESOLUTION_SCALE else 1f
            when (val outcome = buildRaster(uri, state.paper, state.options, scale)) {
                is RasterOutcome.Failure -> {
                    currentJob = null
                    _uiState.value = _uiState.value.copy(
                        isRendering = false,
                        rasterError = outcome.error,
                        rasterBitmap = null,
                    )
                }

                is RasterOutcome.Success -> {
                    currentJob = outcome.job
                    _uiState.value = _uiState.value.copy(
                        isRendering = false,
                        rasterError = null,
                        rasterBitmap = outcome.job.monoBitmap.asImageBitmap(),
                        warnings = outcome.warnings,
                        outputWidthDots = outcome.job.widthDots,
                        outputHeightDots = outcome.job.heightDots,
                        histogram = histogramFor(state.options),
                    )
                }
            }
        }
    }

    /** Builds the per-mode swatches from the user's own photo. */
    private fun scheduleSwatches() {
        swatchJob?.cancel()
        val source = sourceBitmap ?: return
        swatchJob = viewModelScope.launch(Dispatchers.Default) {
            delay(SWATCH_DEBOUNCE_MS)
            val options = _uiState.value.options
            val cropped = centerSquare(source, SWATCH_PX)
            val pixels = IntArray(cropped.width * cropped.height)
            cropped.getPixels(pixels, 0, cropped.width, 0, 0, cropped.width, cropped.height)
            val gray = RasterCore.toGrayscale(pixels, options.grayscaleMode)
            val toned = RasterCore.applyTone(
                gray,
                options.brightness,
                options.contrast,
                options.gamma,
                options.invert,
            )

            val swatches = DitherMode.entries.associateWith { mode ->
                val plane = if (mode == DitherMode.NONE_GRAYSCALE) {
                    RasterCore.grayToArgb(toned)
                } else {
                    RasterCore.monoToArgb(
                        RasterCore.dither(
                            toned,
                            cropped.width,
                            cropped.height,
                            mode,
                            options.threshold,
                        ),
                        cropped.width,
                        cropped.height,
                    )
                }
                Bitmap.createBitmap(
                    plane,
                    cropped.width,
                    cropped.height,
                    Bitmap.Config.ARGB_8888,
                ).asImageBitmap()
            }

            _uiState.value = _uiState.value.copy(swatches = swatches)
        }
    }

    /** The tone histogram, computed from a downsampled copy of the source. */
    private fun histogramFor(options: PrintOptions): IntArray? {
        val source = sourceBitmap ?: return null
        val small = centerSquare(source, HISTOGRAM_PX)
        val pixels = IntArray(small.width * small.height)
        small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
        val gray = RasterCore.toGrayscale(pixels, options.grayscaleMode)
        val toned = RasterCore.applyTone(
            gray,
            options.brightness,
            options.contrast,
            options.gamma,
            options.invert,
        )
        return RasterCore.histogram(toned)
    }

    // ---------------------------------------------------------------------------------------
    // Printing
    // ---------------------------------------------------------------------------------------

    /** Sends the previewed raster to the selected printer. */
    fun print() {
        val printer = _uiState.value.selectedPrinter ?: run {
            _uiState.value = _uiState.value.copy(
                printState = PrintUiState.Failed(PrintError(PrintCategory.NO_PRINTER_SELECTED)),
            )
            return
        }
        val job = currentJob ?: run {
            _uiState.value = _uiState.value.copy(
                printState = PrintUiState.Failed(
                    _uiState.value.rasterError ?: PrintError(PrintCategory.IMAGE_EMPTY)
                ),
            )
            return
        }

        printJob?.cancel()
        printJob = viewModelScope.launch {
            printPhoto(job, printer).collect { step ->
                when (step) {
                    is PrintStep.InProgress -> _uiState.value = _uiState.value.copy(
                        printState = PrintUiState.Running(step.progress, step.copy, step.copies),
                    )

                    is PrintStep.Finished -> {
                        val result = step.result
                        _uiState.value = _uiState.value.copy(
                            printState = if (result.success) {
                                PrintUiState.Succeeded
                            } else {
                                PrintUiState.Failed(
                                    result.error ?: PrintError(PrintCategory.UNKNOWN)
                                )
                            },
                        )
                        recordRecent(printer, result.success, result.error?.code, job)
                    }
                }
            }
        }
    }

    /** Cancels the print in flight, which also force-closes the socket. */
    fun cancelPrint() {
        printJob?.cancel()
        printJob = null
        _uiState.value = _uiState.value.copy(printState = PrintUiState.Idle)
    }

    /** Dismisses the printing sheet once the user has read the outcome. */
    fun dismissPrintResult() {
        _uiState.value = _uiState.value.copy(printState = PrintUiState.Idle)
    }

    private suspend fun recordRecent(
        printer: SavedPrinter,
        success: Boolean,
        errorCode: String?,
        job: RasterJob,
    ) {
        val uri = _uiState.value.imageUri ?: return
        recentPrintsStore.record(
            imageUri = uri.toString(),
            printerName = printer.displayName,
            printerId = printer.id,
            success = success,
            errorCode = errorCode,
            options = _uiState.value.options,
            thumbnail = job.monoBitmap,
        )
    }

    // ---------------------------------------------------------------------------------------

    /** Centre-crops to a square of at most [size] pixels, for swatches and the histogram. */
    private fun centerSquare(source: Bitmap, size: Int): Bitmap {
        val edge = minOf(source.width, source.height)
        val square = Bitmap.createBitmap(
            source,
            (source.width - edge) / 2,
            (source.height - edge) / 2,
            edge,
            edge,
        )
        if (edge <= size) return square
        return Bitmap.createScaledBitmap(square, size, size, true)
    }

    private companion object {
        /** Long enough to swallow a slider drag, short enough to feel immediate. */
        const val RENDER_DEBOUNCE_MS = 150L

        /** Swatches are secondary; they can wait a little longer than the main preview. */
        const val SWATCH_DEBOUNCE_MS = 260L

        /** While dragging, render at this fraction of the paper width. */
        const val DRAG_RESOLUTION_SCALE = 0.45f

        const val SOURCE_PREVIEW_PX = 1024
        const val SWATCH_PX = 96
        const val HISTOGRAM_PX = 192
    }
}
