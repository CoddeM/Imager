package com.rahul.imager.data

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.rahul.imager.printer.domain.PrintCategory
import com.rahul.imager.printer.domain.PrintError
import com.rahul.imager.printer.domain.SavedPrinter
import com.rahul.imager.printer.driver.registry.PrinterDriverRegistry
import com.rahul.imager.printer.driver.registry.PrinterRouting
import com.rahul.imager.printer.transport.UsbAttachMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** What the app currently knows about one printer's connection. */
sealed interface ConnectionState {

    data object Disconnected : ConnectionState

    data object Connecting : ConnectionState

    data class Connected(val sinceEpochMs: Long) : ConnectionState

    data class Failed(val error: PrintError) : ConnectionState
}

/**
 * Keeps the saved printers connected in the background.
 *
 * This is the piece that makes "just print" one tap. Without it, every print would begin with a
 * cold connect — several seconds on Bluetooth — and the user would experience the app as slow even
 * though the printer was sitting there ready.
 *
 * It reconnects on the events that actually matter: app start, Bluetooth being switched back on,
 * and a USB printer being plugged in. It never blocks the UI, and it gives up after a bounded
 * number of attempts rather than retrying for ever in the background.
 */
@Singleton
class PrinterConnectionManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val printerStore: PrinterStore,
    private val usbAttachMonitor: UsbAttachMonitor,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _states = MutableStateFlow<Map<String, ConnectionState>>(emptyMap())

    /** Connection state per saved printer id. */
    val states: StateFlow<Map<String, ConnectionState>> = _states.asStateFlow()

    /** One reconnect job per printer, so a new attempt always supersedes the previous one. */
    private val jobs = ConcurrentHashMap<String, Job>()

    private var started = false

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            if (state == BluetoothAdapter.STATE_ON) {
                Log.i(TAG, "Bluetooth came back on; reconnecting the default printer")
                scope.launch { connectDefault() }
            }
        }
    }

    /** Starts watching. Called once from the Application. Safe to call twice. */
    fun start() {
        if (started) return
        started = true

        usbAttachMonitor.start()

        ContextCompat.registerReceiver(
            context,
            bluetoothReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )

        // The default printer: connect it now, and again whenever the user changes it.
        scope.launch {
            printerStore.defaultPrinter
                .distinctUntilChanged { old, new -> old?.id == new?.id }
                .filterNotNull()
                .collect { printer -> connect(printer) }
        }

        // A USB printer being plugged in is worth an immediate reconnect attempt: the saved device
        // path has just changed, and the driver re-resolves it on connect.
        scope.launch {
            usbAttachMonitor.attached.filterNotNull().collect { connectDefault() }
        }

        // A USB printer being unplugged invalidates exactly that unit's state, nothing else.
        scope.launch {
            usbAttachMonitor.detached.collect { device ->
                printerStore.snapshot()
                    .filter { it.identifier == device.deviceName }
                    .forEach { markDisconnected(it.id) }
            }
        }
    }

    /** Stops watching and cancels every pending reconnect. */
    fun stop() {
        if (!started) return
        started = false
        runCatching { context.unregisterReceiver(bluetoothReceiver) }
        usbAttachMonitor.stop()
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    /** The state of one printer. */
    fun stateOf(printerId: String): ConnectionState =
        _states.value[printerId] ?: ConnectionState.Disconnected

    /** Connects the default printer, if there is one. */
    suspend fun connectDefault() {
        val default = printerStore.snapshot().firstOrNull { it.isDefault }
            ?: printerStore.snapshot().firstOrNull()
            ?: return
        connect(default)
    }

    /**
     * Connects a printer in the background, with exponential backoff.
     *
     * Backoff is 1 s, 2 s, 4 s, 8 s, capped at 30 s, and gives up after five attempts. A printer
     * that is genuinely off is not going to answer the sixth try either, and the user gets a clear
     * state to act on instead of a battery-draining retry loop.
     */
    fun connect(printer: SavedPrinter) {
        jobs.remove(printer.id)?.cancel()
        jobs[printer.id] = scope.launch {
            var delayMs = INITIAL_BACKOFF_MS
            repeat(MAX_ATTEMPTS) { attempt ->
                setState(printer.id, ConnectionState.Connecting)

                val outcome = attemptConnect(printer)
                if (outcome == null) {
                    setState(printer.id, ConnectionState.Connected(System.currentTimeMillis()))
                    return@launch
                }

                setState(printer.id, ConnectionState.Failed(outcome))
                if (attempt < MAX_ATTEMPTS - 1) {
                    delay(delayMs)
                    delayMs = (delayMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                }
            }
            Log.i(TAG, "Giving up on ${printer.displayName} after $MAX_ATTEMPTS attempts")
        }
    }

    /** Disconnects a printer and forgets any pending reconnect. */
    fun disconnect(printer: SavedPrinter) {
        jobs.remove(printer.id)?.cancel()
        scope.launch {
            when (val routing = PrinterDriverRegistry.route(context, printer)) {
                is PrinterRouting.Resolved ->
                    runCatching { routing.printer.disconnect(context) }

                is PrinterRouting.Unroutable -> Unit
            }
            markDisconnected(printer.id)
        }
    }

    /** Clears remembered state for a printer that has been deleted. */
    fun forget(printerId: String) {
        jobs.remove(printerId)?.cancel()
        _states.value = _states.value - printerId
    }

    /**
     * One connection attempt.
     *
     * @return `null` on success, or the reason it failed.
     */
    private suspend fun attemptConnect(printer: SavedPrinter): PrintError? =
        when (val routing = PrinterDriverRegistry.route(context, printer)) {
            is PrinterRouting.Unroutable ->
                PrintError(routing.category, detail = routing.detail)

            is PrinterRouting.Resolved -> {
                val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                    runCatching { routing.printer.connect(context) }.getOrDefault(false)
                }
                when (connected) {
                    true -> null
                    false -> PrintError(
                        PrintCategory.CONNECT_FAILED,
                        detail = "${printer.displayName} did not accept a connection.",
                    )

                    null -> PrintError(
                        PrintCategory.CONNECT_TIMEOUT,
                        detail = "${printer.displayName} did not answer in time.",
                    )
                }
            }
        }

    private fun markDisconnected(printerId: String) {
        setState(printerId, ConnectionState.Disconnected)
    }

    private fun setState(printerId: String, state: ConnectionState) {
        _states.value = _states.value + (printerId to state)
    }

    private companion object {
        const val TAG = "PrinterConnectionManager"
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val MAX_ATTEMPTS = 5
        const val CONNECT_TIMEOUT_MS = 20_000L
    }
}
