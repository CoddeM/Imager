package com.rahul.imager.printer.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.rahul.imager.printer.domain.PrintCategory
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Whether classic Bluetooth is ready to carry a print job to a specific device.
 *
 * This is deliberately NOT a boolean: every reason a Bluetooth printer is unreachable has a
 * different fix (turn Bluetooth on, grant the permission, pair the device, move closer), and the
 * user deserves to be told which one applies.
 */
sealed interface BtReadiness {

    /** The device is bonded and the adapter is usable. */
    data object Ready : BtReadiness

    /** Bluetooth cannot be used, with the specific reason. */
    data class NotReady(val category: PrintCategory, val detail: String) : BtReadiness
}

/**
 * The brand-agnostic Bluetooth gate that every classic-Bluetooth driver calls FIRST.
 *
 * Classic Bluetooth (RFCOMM/SPP) can only reach devices the OPERATING SYSTEM has bonded. A printer
 * that was never paired looks perfectly connected inside an app — the SDK reports success, the
 * writes appear to work — and then silently never prints. Checking the bond state up front turns
 * that whole class of ghost failure into one clear message.
 */
object BluetoothLink {

    private const val TAG = "BluetoothLink"

    /** How long to wait for a bond to reach a terminal state. */
    const val PAIRING_TIMEOUT_MS = 30_000L

    /** Some SDKs hand back identifiers with this prefix; it is not part of the MAC. */
    private const val BT_PREFIX = "BT:"

    /** Strips the `BT:` prefix some vendor SDKs attach to a MAC address. */
    fun normalizeAddress(identifier: String): String =
        identifier.removePrefix(BT_PREFIX).trim().uppercase()

    /**
     * Checks — and, if necessary, establishes — everything a classic-Bluetooth print needs.
     *
     * The steps are ordered cheapest-and-most-common-first so the usual failure (Bluetooth is off)
     * is reported without touching the adapter's device list.
     */
    suspend fun ensureReady(context: Context, identifier: String): BtReadiness {
        val mac = normalizeAddress(identifier)

        // 1. Permission. On API 31+ this is BLUETOOTH_CONNECT; below it, the legacy BLUETOOTH
        //    permission is install-time and always granted.
        if (!hasConnectPermission(context)) {
            return BtReadiness.NotReady(
                PrintCategory.BT_PERMISSION_DENIED,
                "BLUETOOTH_CONNECT has not been granted.",
            )
        }

        // 2. Adapter present and switched on.
        val adapter = adapter(context)
            ?: return BtReadiness.NotReady(
                PrintCategory.CONNECT_FAILED,
                "This device has no Bluetooth adapter.",
            )
        if (!adapter.isEnabled) {
            return BtReadiness.NotReady(PrintCategory.BT_OFF, "Bluetooth is switched off.")
        }

        // 3. The address has to be a real MAC before it is worth asking the adapter about it.
        if (!BluetoothAdapter.checkBluetoothAddress(mac)) {
            return BtReadiness.NotReady(
                PrintCategory.CONNECT_FAILED,
                "\"$identifier\" is not a valid Bluetooth address.",
            )
        }

        logBondedDevices(adapter, mac)

        val device = runCatching { adapter.getRemoteDevice(mac) }.getOrNull()
            ?: return BtReadiness.NotReady(
                PrintCategory.CONNECT_FAILED,
                "The adapter could not resolve $mac.",
            )

        // 5. Already bonded: nothing to do.
        if (bondState(device) == BluetoothDevice.BOND_BONDED) return BtReadiness.Ready

        // 6. Not bonded — start pairing and wait for a terminal state.
        return createBondAndAwait(context, device, mac)
    }

    /** True when the app may talk to bonded Bluetooth devices. */
    fun hasConnectPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** True when the app may scan for nearby Bluetooth devices. */
    fun hasScanPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** The system Bluetooth adapter, or null on a device without Bluetooth. */
    fun adapter(context: Context): BluetoothAdapter? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        @Suppress("DEPRECATION")
        return manager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
    }

    /** True when the adapter exists and is switched on. */
    fun isEnabled(context: Context): Boolean = adapter(context)?.isEnabled == true

    /** The OS-bonded devices, or an empty list when they cannot be read. */
    @SuppressLint("MissingPermission")
    fun bondedDevices(context: Context): List<BluetoothDevice> {
        if (!hasConnectPermission(context)) return emptyList()
        val adapter = adapter(context) ?: return emptyList()
        return runCatching { adapter.bondedDevices?.toList().orEmpty() }
            .onFailure { Log.w(TAG, "Cannot read bonded devices", it) }
            .getOrDefault(emptyList())
    }

    /** Cancels any in-progress discovery. Required before opening an RFCOMM socket. */
    @SuppressLint("MissingPermission")
    fun cancelDiscovery(context: Context) {
        runCatching { adapter(context)?.cancelDiscovery() }
            .onFailure { Log.d(TAG, "cancelDiscovery failed: ${it.message}") }
    }

    @SuppressLint("MissingPermission")
    private fun bondState(device: BluetoothDevice): Int =
        runCatching { device.bondState }.getOrDefault(BluetoothDevice.BOND_NONE)

    /**
     * Logs the bonded-device list around a pairing decision.
     *
     * This single log line is what lets a support report distinguish "not paired" from
     * "Bluetooth off" from "permission denied" after the fact, so it is worth the noise.
     */
    @SuppressLint("MissingPermission")
    private fun logBondedDevices(adapter: BluetoothAdapter, target: String) {
        runCatching {
            val bonded = adapter.bondedDevices.orEmpty()
            Log.i(
                TAG,
                "Bonded devices: ${bonded.size}, target=$target, " +
                    bonded.joinToString { "${it.address}(${it.name ?: "?"})" },
            )
        }.onFailure { Log.w(TAG, "Cannot enumerate bonded devices: ${it.message}") }
    }

    /**
     * Starts bonding and suspends until the bond reaches a terminal state.
     *
     * A high-priority receiver watches both the pairing request and the bond-state change. It is
     * unregistered on EVERY exit path — cancellation, timeout, and both normal resumes. Relying on
     * `invokeOnCancellation` alone would leak one SYSTEM_HIGH_PRIORITY receiver per pairing
     * attempt, because the common paths resume rather than cancel.
     */
    @SuppressLint("MissingPermission")
    private suspend fun createBondAndAwait(
        context: Context,
        device: BluetoothDevice,
        mac: String,
    ): BtReadiness {
        val appContext = context.applicationContext
        val result = withTimeoutOrNull(PAIRING_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val resumed = AtomicBoolean(false)
                val unregistered = AtomicBoolean(false)
                var receiver: BroadcastReceiver? = null

                fun cleanup() {
                    if (!unregistered.compareAndSet(false, true)) return
                    receiver?.let { runCatching { appContext.unregisterReceiver(it) } }
                    receiver = null
                }

                fun finish(readiness: BtReadiness) {
                    if (!resumed.compareAndSet(false, true)) return
                    cleanup()
                    if (continuation.isActive) continuation.resume(readiness)
                }

                val bondReceiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        val intentDevice: BluetoothDevice? = extraDevice(intent)
                        if (intentDevice?.address?.equals(mac, ignoreCase = true) != true) return

                        when (intent?.action) {
                            BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                                autoAcceptPairing(this, intent, intentDevice)
                            }

                            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                                val state = intent.getIntExtra(
                                    BluetoothDevice.EXTRA_BOND_STATE,
                                    BluetoothDevice.BOND_NONE,
                                )
                                val previous = intent.getIntExtra(
                                    BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                                    BluetoothDevice.BOND_NONE,
                                )
                                when (state) {
                                    BluetoothDevice.BOND_BONDED -> finish(BtReadiness.Ready)

                                    // BOND_NONE only means failure when we were actually bonding.
                                    // The same broadcast fires while a bond is being set up, and
                                    // treating that as a failure aborts a pairing that is working.
                                    BluetoothDevice.BOND_NONE ->
                                        if (previous == BluetoothDevice.BOND_BONDING) {
                                            finish(
                                                BtReadiness.NotReady(
                                                    PrintCategory.BT_PAIRING_FAILED,
                                                    "Pairing with $mac was rejected or failed.",
                                                )
                                            )
                                        }
                                }
                            }
                        }
                    }
                }
                receiver = bondReceiver

                val filter = IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                    addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
                    priority = IntentFilter.SYSTEM_HIGH_PRIORITY
                }
                ContextCompat.registerReceiver(
                    appContext,
                    bondReceiver,
                    filter,
                    ContextCompat.RECEIVER_EXPORTED,
                )

                // Cancellation (including the enclosing withTimeoutOrNull firing) must still take
                // the receiver down; it must NOT resume the continuation.
                continuation.invokeOnCancellation { cleanup() }

                val started = runCatching { device.createBond() }.getOrDefault(false)
                if (!started) {
                    finish(
                        BtReadiness.NotReady(
                            PrintCategory.BT_NOT_PAIRED,
                            "$mac is not paired and pairing could not be started.",
                        )
                    )
                }
            }
        }

        return result ?: BtReadiness.NotReady(
            PrintCategory.BT_PAIRING_TIMEOUT,
            "Pairing with $mac did not complete within ${PAIRING_TIMEOUT_MS / 1000}s.",
        )
    }

    /**
     * Best-effort automatic acceptance of a pairing request.
     *
     * Both calls need `BLUETOOTH_PRIVILEGED`, which a normal app cannot hold, so they will usually
     * throw — and that is fine: the system pairing dialog is the intended fallback and the user
     * simply confirms it by hand. Attempting it first is what makes fixed-PIN printers pair with
     * no interaction at all on the devices where it does work.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun autoAcceptPairing(
        receiver: BroadcastReceiver,
        intent: Intent,
        device: BluetoothDevice,
    ) {
        runCatching {
            val variant = intent.getIntExtra(
                BluetoothDevice.EXTRA_PAIRING_VARIANT,
                BluetoothDevice.ERROR,
            )
            if (variant == BluetoothDevice.PAIRING_VARIANT_PIN) {
                device.setPin(DEFAULT_PAIRING_PIN.toByteArray())
            } else {
                device.setPairingConfirmation(true)
            }
            if (receiver.isOrderedBroadcast) receiver.abortBroadcast()
        }.onFailure {
            Log.d(TAG, "Auto-pairing not permitted, falling back to the system dialog: ${it.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun extraDevice(intent: Intent?): BluetoothDevice? {
        intent ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    /** The PIN the overwhelming majority of thermal printers ship with. */
    private const val DEFAULT_PAIRING_PIN = "1234"
}
