package com.rahul.imager.ui.screens.printers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rahul.imager.printer.discovery.DiscoveredPrinter
import com.rahul.imager.printer.domain.DEFAULT_LAN_PORT
import com.rahul.imager.printer.domain.PaperProfile
import com.rahul.imager.printer.domain.PaperProfiles
import com.rahul.imager.printer.domain.PaperWidthResolver
import com.rahul.imager.printer.domain.PrintCategory
import com.rahul.imager.printer.domain.PrintError
import com.rahul.imager.printer.domain.PrinterStatus
import com.rahul.imager.printer.domain.SavedPrinter
import com.rahul.imager.printer.domain.TransportType
import com.rahul.imager.printer.driver.registry.FamilyAvailability
import com.rahul.imager.usecase.ConnectPrinterUseCase
import com.rahul.imager.usecase.DetectBuiltInPrinterUseCase
import com.rahul.imager.usecase.DiscoverPrintersUseCase
import com.rahul.imager.usecase.ManualLanPrinterUseCase
import com.rahul.imager.usecase.QueryPrinterStatusUseCase
import com.rahul.imager.usecase.SavePrinterUseCase
import com.rahul.imager.usecase.TestPrintUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** The steps of the add-printer flow, in order. */
enum class AddStep { CONNECTION, SCAN, IDENTIFY, TEST }

/** Where the test print in the add flow has got to. */
sealed interface AddTestState {
    data object Idle : AddTestState
    data object Running : AddTestState
    data object Succeeded : AddTestState
    data class Failed(val error: PrintError) : AddTestState
}

data class AddPrinterUiState(
    val step: AddStep = AddStep.CONNECTION,
    val families: List<FamilyAvailability> = emptyList(),
    val builtInCandidates: List<DiscoveredPrinter> = emptyList(),
    val transport: TransportType? = null,
    val scanning: Boolean = false,
    val results: List<DiscoveredPrinter> = emptyList(),
    val selected: DiscoveredPrinter? = null,
    val displayName: String = "",
    val paperProfileId: String = PaperProfiles.FALLBACK.id,
    val resolvedPaperNote: String? = null,
    val manualIp: String = "",
    val manualPort: String = DEFAULT_LAN_PORT.toString(),
    val identifying: Boolean = false,
    val reachable: Boolean? = null,
    val identifyError: PrintError? = null,
    val hardwareStatus: PrinterStatus? = null,
    val testState: AddTestState = AddTestState.Idle,
    val savedPrinter: SavedPrinter? = null,
    val needsBluetoothPermission: Boolean = false,
) {
    val paper: PaperProfile get() = PaperProfiles.resolve(paperProfileId)

    val canContinueFromScan: Boolean get() = selected != null

    val manualEntryValid: Boolean
        get() = manualIp.isNotBlank() && manualPort.toIntOrNull() in 1..65535
}

/**
 * The add-printer flow.
 *
 * The order is deliberate and matches how the hardware actually behaves: choose how it is
 * connected, find it, confirm what it is, and only then — after it has actually printed something
 * — save it. A printer saved without ever printing is a printer that fails the first time it
 * matters.
 */
@HiltViewModel
class AddPrinterViewModel @Inject constructor(
    private val discoverPrinters: DiscoverPrintersUseCase,
    private val detectBuiltIn: DetectBuiltInPrinterUseCase,
    private val manualLanPrinter: ManualLanPrinterUseCase,
    private val connectPrinter: ConnectPrinterUseCase,
    private val queryPrinterStatus: QueryPrinterStatusUseCase,
    private val testPrint: TestPrintUseCase,
    private val savePrinter: SavePrinterUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddPrinterUiState())
    val uiState: StateFlow<AddPrinterUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null

    /** The printer being built. Not persisted until the test passes or is skipped. */
    private var candidate: SavedPrinter? = null

    init {
        _uiState.value = _uiState.value.copy(
            families = discoverPrinters.availability(),
            builtInCandidates = detectBuiltIn(),
        )
    }

    /** Whether the built-in option is worth offering on this device. */
    val hasBuiltInPrinter: Boolean get() = _uiState.value.builtInCandidates.isNotEmpty()

    // ---------------------------------------------------------------------------------------

    /** Chooses how the printer is connected and moves to the scan step. */
    fun chooseTransport(transport: TransportType) {
        _uiState.value = _uiState.value.copy(
            transport = transport,
            step = AddStep.SCAN,
            results = emptyList(),
            selected = null,
        )
        if (transport == TransportType.INNER) {
            // A built-in head needs no scan: it is either there or it is not.
            val builtIns = _uiState.value.builtInCandidates
            _uiState.value = _uiState.value.copy(results = builtIns)
            builtIns.singleOrNull()?.let { select(it) }
        } else {
            startScan()
        }
    }

    /** Runs (or re-runs) the scan for the chosen transport. */
    fun startScan() {
        val transport = _uiState.value.transport ?: return
        scanJob?.cancel()
        _uiState.value = _uiState.value.copy(scanning = true, results = emptyList())

        scanJob = viewModelScope.launch {
            discoverPrinters(transport)
                .onCompletion { _uiState.value = _uiState.value.copy(scanning = false) }
                .collect { discovered ->
                    val existing = _uiState.value.results
                    // De-duplicate: several families can report the same physical unit.
                    if (existing.none { it.key == discovered.key }) {
                        _uiState.value = _uiState.value.copy(results = existing + discovered)
                    }
                }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _uiState.value = _uiState.value.copy(scanning = false)
    }

    /** Picks a discovered printer and moves on to confirm it. */
    fun select(discovered: DiscoveredPrinter) {
        val resolution = PaperWidthResolver.resolve(
            model = discovered.model,
            displayName = discovered.name,
            transport = discovered.transport,
        )
        _uiState.value = _uiState.value.copy(
            selected = discovered,
            displayName = discovered.name,
            paperProfileId = resolution.profile.id,
            resolvedPaperNote = resolution.warning,
        )
    }

    fun setDisplayName(name: String) {
        _uiState.value = _uiState.value.copy(displayName = name)
    }

    fun setPaperProfile(profileId: String) {
        _uiState.value = _uiState.value.copy(paperProfileId = profileId)
    }

    fun setManualIp(value: String) {
        _uiState.value = _uiState.value.copy(manualIp = value)
    }

    fun setManualPort(value: String) {
        _uiState.value = _uiState.value.copy(manualPort = value.filter(Char::isDigit).take(5))
    }

    /** Adds a manually entered LAN printer to the results and selects it. */
    fun addManualLanPrinter() {
        val state = _uiState.value
        if (!manualLanPrinter.isValidAddress(state.manualIp)) return
        val port = state.manualPort.toIntOrNull() ?: DEFAULT_LAN_PORT
        val discovered = manualLanPrinter(state.manualIp, port)
        _uiState.value = state.copy(results = state.results + discovered)
        select(discovered)
    }

    /**
     * Moves from the scan to the confirm step, and CONNECTS on the way.
     *
     * Connecting here rather than at save time is deliberate: it turns "this address answers" into
     * something the user learns before they have committed to anything, and it lets the hardware
     * report a fault (cover open, out of paper) while there is still a Back button.
     */
    fun continueToIdentify() {
        val discovered = _uiState.value.selected ?: return
        stopScan()
        _uiState.value = _uiState.value.copy(
            step = AddStep.IDENTIFY,
            identifying = true,
            reachable = null,
            identifyError = null,
            hardwareStatus = null,
        )

        viewModelScope.launch {
            val probe = buildCandidate(discovered, _uiState.value)
            val failure = connectPrinter(probe)
            val status = if (failure == null) queryPrinterStatus(probe) else null
            _uiState.value = _uiState.value.copy(
                identifying = false,
                reachable = failure == null,
                identifyError = failure,
                hardwareStatus = status,
            )
        }
    }

    /** Moves from confirm to the test step, building the (still unsaved) printer. */
    fun continueToTest() {
        val state = _uiState.value
        val discovered = state.selected ?: return
        candidate = buildCandidate(discovered, state)
        _uiState.value = state.copy(step = AddStep.TEST, testState = AddTestState.Idle)
    }

    /**
     * Builds the printer record for a discovery result.
     *
     * The brand and model come from the DISCOVERY, never from the editable display name, and are
     * frozen from here on: renaming the printer later must never change how it is driven.
     */
    private fun buildCandidate(
        discovered: DiscoveredPrinter,
        state: AddPrinterUiState,
    ): SavedPrinter = SavedPrinter(
        id = UUID.randomUUID().toString(),
        displayName = state.displayName.trim().ifBlank { discovered.name },
        brand = discovered.brand,
        transport = discovered.transport,
        identifier = discovered.identifier,
        port = discovered.port,
        paperProfileId = state.paperProfileId,
        model = discovered.model,
        isDefault = false,
        addedAtEpochMs = System.currentTimeMillis(),
    )

    /** Prints the test slip on the candidate printer. */
    fun runTest() {
        val printer = candidate ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(testState = AddTestState.Running)
            val result = testPrint(printer)
            if (result.success) {
                _uiState.value = _uiState.value.copy(testState = AddTestState.Succeeded)
                save()
            } else {
                _uiState.value = _uiState.value.copy(
                    testState = AddTestState.Failed(
                        result.error ?: PrintError(PrintCategory.UNKNOWN)
                    ),
                )
            }
        }
    }

    /** Saves without testing. An explicit choice, never the default path. */
    fun skipTest() {
        viewModelScope.launch { save() }
    }

    /** Steps back through the flow. Returns false when there is nowhere left to go. */
    fun back(): Boolean = when (_uiState.value.step) {
        AddStep.CONNECTION -> false
        AddStep.SCAN -> {
            stopScan()
            _uiState.value = _uiState.value.copy(step = AddStep.CONNECTION, selected = null)
            true
        }

        AddStep.IDENTIFY -> {
            _uiState.value = _uiState.value.copy(step = AddStep.SCAN)
            true
        }

        AddStep.TEST -> {
            _uiState.value = _uiState.value.copy(step = AddStep.IDENTIFY)
            true
        }
    }

    /** Records that the Bluetooth permission still has to be requested. */
    fun setNeedsBluetoothPermission(needed: Boolean) {
        _uiState.value = _uiState.value.copy(needsBluetoothPermission = needed)
    }

    /** Persists the printer and starts keeping it connected in the background. */
    private suspend fun save() {
        val state = _uiState.value
        val discovered = state.selected ?: return
        val saved = savePrinter(
            discovered = discovered,
            displayName = state.displayName,
            paperProfileId = state.paperProfileId,
        )
        _uiState.value = _uiState.value.copy(savedPrinter = saved)
    }

    override fun onCleared() {
        scanJob?.cancel()
        super.onCleared()
    }
}
