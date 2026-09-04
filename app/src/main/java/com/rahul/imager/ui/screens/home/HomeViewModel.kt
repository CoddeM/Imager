package com.rahul.imager.ui.screens.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rahul.imager.data.ConnectionState
import com.rahul.imager.data.PrintOptionsStore
import com.rahul.imager.data.PrinterConnectionManager
import com.rahul.imager.data.PrinterStore
import com.rahul.imager.data.RecentPrint
import com.rahul.imager.data.RecentPrintsStore
import com.rahul.imager.printer.domain.SavedPrinter
import com.rahul.imager.usecase.SetDefaultPrinterUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Everything the home screen renders. */
data class HomeUiState(
    val printers: List<SavedPrinter> = emptyList(),
    val defaultPrinter: SavedPrinter? = null,
    val connectionStates: Map<String, ConnectionState> = emptyMap(),
    val recents: List<RecentPrint> = emptyList(),
    val bannerDismissed: Boolean = false,
) {
    /** The banner only appears when there is genuinely nothing to print to. */
    val showNoPrinterBanner: Boolean get() = printers.isEmpty() && !bannerDismissed

    /** The state of the printer the chip is showing. */
    val defaultPrinterState: ConnectionState
        get() = defaultPrinter?.let { connectionStates[it.id] } ?: ConnectionState.Disconnected
}

/**
 * Home.
 *
 * State comes from a `MutableStateFlow` exposed as a read-only `StateFlow`, driven by public
 * functions — no action/event scaffolding, because this screen has four interactions and a
 * dispatcher would be more code than the code it dispatches to.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val printerStore: PrinterStore,
    private val recentPrintsStore: RecentPrintsStore,
    private val printOptionsStore: PrintOptionsStore,
    private val connectionManager: PrinterConnectionManager,
    private val setDefaultPrinter: SetDefaultPrinterUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                printerStore.printers,
                printerStore.defaultPrinter,
                connectionManager.states,
                recentPrintsStore.recents,
            ) { printers, default, states, recents ->
                _uiState.value.copy(
                    printers = printers,
                    defaultPrinter = default,
                    connectionStates = states,
                    recents = recents,
                )
            }.collect { _uiState.value = it }
        }
    }

    /** Hides the "connect a printer" banner. It never blocks anything, so it is dismissible. */
    fun dismissBanner() {
        _uiState.value = _uiState.value.copy(bannerDismissed = true)
    }

    /** Switches the printer everything prints to. */
    fun selectPrinter(printer: SavedPrinter) {
        viewModelScope.launch { setDefaultPrinter(printer) }
    }

    /** Retries the background connection, for the Reconnect action on the printer sheet. */
    fun reconnect(printer: SavedPrinter) {
        connectionManager.connect(printer)
    }

    /**
     * Prepares a one-tap reprint.
     *
     * The options that produced the original print are restored FIRST, so the preview screen opens
     * with the same crop, tone and dither rather than with whatever was used most recently. Only
     * then does the caller navigate.
     */
    fun prepareReprint(recent: RecentPrint, onReady: (Uri) -> Unit) {
        viewModelScope.launch {
            printOptionsStore.saveLastUsed(recent.options)
            onReady(Uri.parse(recent.imageUri))
        }
    }

    /** Removes one entry from the recents row. */
    fun removeRecent(recent: RecentPrint) {
        viewModelScope.launch { recentPrintsStore.remove(recent.id) }
    }
}
