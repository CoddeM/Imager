package com.rahul.imager.printer.transport

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Obtains and holds USB host permission, independently of any vendor SDK.
 *
 * Vendor SDKs generally request USB permission themselves, using an unflagged `BroadcastReceiver`
 * and a mutable implicit `PendingIntent`. Android 14+ rejects that combination outright, so the
 * SDK silently never receives its grant and the connect call fails with a useless error. This
 * object therefore acquires permission BEFORE any SDK is asked to connect.
 */
object UsbHostPermission {

    private const val TAG = "UsbHostPermission"

    /** Explicit, app-private action for the permission broadcast. */
    private const val ACTION_USB_PERMISSION = "com.rahul.imager.printer.USB_PERMISSION"

    /** How long to wait for the user to answer the system permission dialog. */
    const val PERMISSION_TIMEOUT_MS = 15_000L

    /** Polling interval while waiting for the grant. */
    private const val POLL_INTERVAL_MS = 250L

    /**
     * Asks the system for permission to talk to [device].
     *
     * `FLAG_MUTABLE` is REQUIRED here — the system fills `EXTRA_PERMISSION_GRANTED` into the
     * intent it sends back, which it cannot do for an immutable one. Android 14+ additionally
     * demands that a mutable `PendingIntent` be explicit, which is what `setPackage` provides.
     */
    fun requestPermission(context: Context, device: UsbDevice) {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        if (manager.hasPermission(device)) return

        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        val pending = PendingIntent.getBroadcast(context.applicationContext, 0, intent, flags)
        runCatching { manager.requestPermission(device, pending) }
            .onFailure { Log.w(TAG, "requestPermission failed for ${device.deviceName}", it) }
    }

    /**
     * Waits until permission for [device] has been granted, or the timeout expires.
     *
     * Deliberately implemented by POLLING `hasPermission` rather than by registering a result
     * receiver: the grant is observable directly, so a receiver would only add a lifecycle to leak
     * and an exported-receiver flag to get wrong.
     *
     * @return true if permission is held when this returns.
     */
    suspend fun awaitPermission(
        context: Context,
        device: UsbDevice,
        timeoutMs: Long = PERMISSION_TIMEOUT_MS,
    ): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        if (manager.hasPermission(device)) return true
        return withTimeoutOrNull(timeoutMs) {
            while (!manager.hasPermission(device)) {
                delay(POLL_INTERVAL_MS)
            }
            true
        } ?: false
    }

    /** Requests permission if needed and waits for the answer. */
    suspend fun ensurePermission(
        context: Context,
        device: UsbDevice,
        timeoutMs: Long = PERMISSION_TIMEOUT_MS,
    ): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        if (manager.hasPermission(device)) return true
        requestPermission(context, device)
        return awaitPermission(context, device, timeoutMs)
    }

    /**
     * Finds the attached device a saved identifier refers to.
     *
     * A USB device path (`/dev/bus/usb/001/004`) is NOT stable across a replug, so a saved
     * identifier can easily point at nothing. The ladder below degrades from exact to plausible:
     *
     *  1. exact device name (the path) — correct while the device stays plugged in;
     *  2. exact serial number — survives replugging, when the device exposes one;
     *  3. the ONLY attached device with a VID this app claims;
     *  4. the ONLY attached device of any kind.
     *
     * Steps 3 and 4 only fire when there is exactly one candidate, so they can never pick the
     * wrong printer out of two.
     */
    fun findDevice(context: Context, identifier: String?): UsbDevice? {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return null
        val devices = manager.deviceList.values.toList()
        if (devices.isEmpty()) return null

        if (!identifier.isNullOrBlank()) {
            devices.firstOrNull { it.deviceName == identifier }?.let { return it }
            devices.firstOrNull { device ->
                runCatching { device.serialNumber }.getOrNull() == identifier
            }?.let { return it }
        }

        val knownVendor = devices.filter { it.vendorId in UsbVendorId.KNOWN_PRINTER_VENDORS }
        if (knownVendor.size == 1) return knownVendor.single()

        return devices.singleOrNull()
    }

    /** Every currently attached USB device. */
    fun attachedDevices(context: Context): List<UsbDevice> {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return emptyList()
        return manager.deviceList.values.toList()
    }

    /** True when permission for [device] is already held. */
    fun hasPermission(context: Context, device: UsbDevice): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        return manager.hasPermission(device)
    }
}
