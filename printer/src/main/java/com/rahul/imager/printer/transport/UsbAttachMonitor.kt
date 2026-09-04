package com.rahul.imager.printer.transport

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Watches USB printers being plugged in and unplugged.
 *
 * `USB_DEVICE_ATTACHED` and `USB_DEVICE_DETACHED` are among the few implicit broadcasts still
 * delivered to a manifest- or context-registered receiver on modern Android, so a single
 * long-lived registration is enough to keep the whole app up to date.
 *
 * The two events mean different things to the app and are exposed differently:
 *
 *  * ATTACHED is *state* — the UI offers "Printer detected, add it?" until the user acts on it,
 *    so it lives in a [StateFlow] that can be consumed.
 *  * DETACHED is an *event* — exactly one cached connection has to be evicted, once — so it is a
 *    [SharedFlow] and never replays.
 */
class UsbAttachMonitor(context: Context) {

    private val appContext: Context = context.applicationContext

    private val _attached = MutableStateFlow<UsbDevice?>(null)

    /** The most recently attached device the user has not dealt with yet. */
    val attached: StateFlow<UsbDevice?> = _attached.asStateFlow()

    private val _detached = MutableSharedFlow<UsbDevice>(extraBufferCapacity = 8)

    /** Devices that have just been unplugged. */
    val detached: SharedFlow<UsbDevice> = _detached.asSharedFlow()

    private var receiver: BroadcastReceiver? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val device = extraDevice(intent) ?: return
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.i(TAG, "USB attached: ${device.deviceName} vid=${device.vendorId}")
                    _attached.value = device
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.i(TAG, "USB detached: ${device.deviceName}")
                    if (_attached.value?.deviceName == device.deviceName) _attached.value = null
                    _detached.tryEmit(device)
                }
            }
        }
    }

    /** Starts listening. Safe to call more than once. */
    fun start() {
        if (receiver != null) return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            appContext,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
        receiver = usbReceiver
    }

    /** Stops listening. Safe to call when never started. */
    fun stop() {
        receiver?.let { runCatching { appContext.unregisterReceiver(it) } }
        receiver = null
    }

    /** Clears the pending attach once the user has accepted or dismissed the offer. */
    fun consumeAttached() {
        _attached.value = null
    }

    @Suppress("DEPRECATION")
    private fun extraDevice(intent: Intent?): UsbDevice? {
        intent ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    private companion object {
        const val TAG = "UsbAttachMonitor"
    }
}
