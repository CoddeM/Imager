package com.rahul.imager.ui.screens.printers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rahul.imager.data.ConnectionState
import com.rahul.imager.data.PrintOptionsStore
import com.rahul.imager.data.PrinterConnectionManager
import com.rahul.imager.data.PrinterStore
import com.rahul.imager.printer.domain.PaperProfile
import com.rahul.imager.printer.domain.PrintError
import com.rahul.imager.printer.domain.SavedPrinter
import com.rahul.imager.usecase.DeletePrinterUseCase
import com.rahul.imager.usecase.ResolvePaperProfileUseCase
import com.rahul.imager.usecase.EscPosCompatibilityFamiliesUseCase
import com.rahul.imager.usecase.SetDefaultPrinterUseCase
import com.rahul.imager.usecase.TestPrintUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A saved printer plus everything the list row needs to render it. */
data class PrinterRow(
    val printer: SavedPrinter,
    val state: ConnectionState,
    val paper: PaperProfile,
    /** True when this printer is driven over generic ESC/POS because its vendor SDK is absent. */
    val escPosCompatibility: Boolean = false,
)

/** Where a test print from this screen has got to. */
sealed interface TestPrintState {
    data object Idle : TestPrintState
    data class Running(val printerId: String) : TestPrintState
    data class Succeeded(val printerId: String) : TestPrintState
    data class Failed(val printerId: String, val error: PrintError) : TestPrintState
}

data class PrintersUiState(
    val rows: List<PrinterRow> = emptyList(),
    val testPrint: TestPrintState = TestPrintState.Idle,
)

/** The printers screen: printers are managed here, independently of any photo. */
@HiltViewModel
class PrintersViewModel @Inject constructor(
    private val printerStore: PrinterStore,
    private val printOptionsStore: PrintOptionsStore,
    private val connectionManager: PrinterConnectionManager,
    private val resolvePaperProfile: ResolvePaperProfileUseCase,
    private val setDefaultPrinter: SetDefaultPrinterUseCase,
    escPosCompatibilityFamilies: EscPosCompatibilityFamiliesUseCase,
    private val deletePrinter: DeletePrinterUseCase,
    private val testPrint: TestPrintUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PrintersUiState())
    val uiState: StateFlow<PrintersUiState> = _uiState.asStateFlow()

    // Resolved once: which families are in compatibility mode is fixed by what this build bundles.
    private val compatibilityFamilies = escPosCompatibilityFamilies()

    init {
        viewModelScope.launch {
            combine(
                printerStore.printers,
                connectionManager.states,
                printOptionsStore.paperOverrides,
            ) { printers, states, overrides ->
                printers.map { printer ->
                    PrinterRow(
                        printer = printer,
                        state = states[printer.id] ?: ConnectionState.Disconnected,
                        paper = resolvePaperProfile(printer, overrides),
                        escPosCompatibility = printer.brand in compatibilityFamilies,
                    )
                }
            }.collect { rows ->
                _uiState.value = _uiState.value.copy(rows = rows)
            }
        }
    }

    fun rename(printer: SavedPrinter, name: String) {
        viewModelScope.launch { printerStore.rename(printer.id, name) }
    }

    fun setDefault(printer: SavedPrinter) {
        viewModelScope.launch { setDefaultPrinter(printer) }
    }

    fun forget(printer: SavedPrinter) {
        viewModelScope.launch { deletePrinter(printer) }
    }

    /**
     * Changes the paper width for one printer.
     *
     * Written to BOTH the saved printer and the override map: the printer record is what a fresh
     * install of the app would read, and the override is what survives the printer being
     * re-detected with a different automatic resolution.
     */
    fun setPaperProfile(printer: SavedPrinter, profile: PaperProfile) {
        viewModelScope.launch {
            printerStore.setPaperProfile(printer.id, profile.id)
            printOptionsStore.setPaperOverride(printer.id, profile.id)
        }
    }

    fun reconnect(printer: SavedPrinter) {
        connectionManager.connect(printer)
    }

    fun disconnect(printer: SavedPrinter) {
        connectionManager.disconnect(printer)
    }

    /** Prints the built-in test slip, reporting the outcome through [PrintersUiState.testPrint]. */
    fun runTestPrint(printer: SavedPrinter) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(testPrint = TestPrintState.Running(printer.id))
            val result = testPrint(printer)
            _uiState.value = _uiState.value.copy(
                testPrint = if (result.success) {
                    TestPrintState.Succeeded(printer.id)
                } else {
                    TestPrintState.Failed(
                        printer.id,
                        result.error ?: PrintError(
                            com.rahul.imager.printer.domain.PrintCategory.UNKNOWN
                        ),
                    )
                },
            )
        }
    }

    fun dismissTestPrint() {
        _uiState.value = _uiState.value.copy(testPrint = TestPrintState.Idle)
    }
}
