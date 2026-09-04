package com.rahul.imager.printer.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID

/**
 * A byte pipe to a printer.
 *
 * All three raw transports (LAN, Bluetooth SPP, USB bulk) reduce to "write these bytes, flush,
 * optionally read a status byte back, close", so the generic ESC/POS driver is written once
 * against this interface.
 *
 * Every method BLOCKS and must therefore be called from `Dispatchers.IO` — except [close], which
 * is the one method that is explicitly safe to call from any thread at any time. Closing the
 * underlying socket from outside is the ONLY way to release a thread that is wedged inside a
 * non-interruptible native write, which is exactly what the print engine needs on a timeout.
 */
interface RawLink {

    /** True while this link is believed usable. */
    val isOpen: Boolean

    /** Writes [bytes] in full. */
    fun write(bytes: ByteArray)

    /** Pushes anything buffered towards the device. */
    fun flush()

    /**
     * Sends [request] and waits up to [timeoutMs] for a single status byte.
     *
     * @return the reply byte, or `null` when the device did not answer. A silent device is NOT an
     *   error: plenty of thermal heads simply do not implement real-time status.
     */
    fun readStatus(request: ByteArray, timeoutMs: Long): Byte?

    /** Closes the link. Safe from any thread, idempotent, never throws. */
    fun close()
}

/**
 * TCP/IP link, the standard RAW / JetDirect port 9100 protocol.
 */
class LanRawLink private constructor(
    private val socket: Socket,
    private val output: OutputStream,
    private val input: InputStream,
) : RawLink {

    @Volatile
    private var closed = false

    override val isOpen: Boolean get() = !closed && socket.isConnected && !socket.isClosed

    override fun write(bytes: ByteArray) {
        output.write(bytes)
    }

    override fun flush() {
        output.flush()
    }

    override fun readStatus(request: ByteArray, timeoutMs: Long): Byte? = try {
        val previousTimeout = socket.soTimeout
        socket.soTimeout = timeoutMs.toInt().coerceAtLeast(1)
        try {
            output.write(request)
            output.flush()
            val value = input.read()
            if (value < 0) null else value.toByte()
        } finally {
            runCatching { socket.soTimeout = previousTimeout }
        }
    } catch (_: SocketTimeoutException) {
        null
    }

    override fun close() {
        closed = true
        // Order matters: closing the socket is what unblocks a wedged write, so it must happen
        // even if flushing the stream throws.
        runCatching { socket.close() }
        runCatching { output.close() }
        runCatching { input.close() }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val SO_TIMEOUT_MS = 15_000

        /** Opens a socket to `host:port`. Blocking. */
        fun open(host: String, port: Int): LanRawLink {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = SO_TIMEOUT_MS
            return LanRawLink(socket, socket.getOutputStream(), socket.getInputStream())
        }
    }
}

/**
 * Classic Bluetooth (RFCOMM / SPP) link.
 *
 * Callers must have passed [BluetoothLink.ensureReady] first: an unbonded device produces a socket
 * that connects and then silently discards everything written to it.
 */
class BluetoothRawLink private constructor(
    private val socket: BluetoothSocket,
    private val output: OutputStream,
    private val input: InputStream,
) : RawLink {

    @Volatile
    private var closed = false

    override val isOpen: Boolean get() = !closed && socket.isConnected

    override fun write(bytes: ByteArray) {
        output.write(bytes)
    }

    override fun flush() {
        output.flush()
    }

    /**
     * `BluetoothSocket` has no read timeout at all, so the reply is polled through `available()`
     * rather than by blocking on `read()`, which would hang forever against a silent printer.
     */
    override fun readStatus(request: ByteArray, timeoutMs: Long): Byte? {
        output.write(request)
        output.flush()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                val value = input.read()
                return if (value < 0) null else value.toByte()
            }
            Thread.sleep(STATUS_POLL_INTERVAL_MS)
        }
        return null
    }

    override fun close() {
        closed = true
        runCatching { socket.close() }
        runCatching { output.close() }
        runCatching { input.close() }
    }

    companion object {
        private const val TAG = "BluetoothRawLink"
        private const val STATUS_POLL_INTERVAL_MS = 50L

        /** The standard Serial Port Profile UUID every ESC/POS printer advertises. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** Opens an RFCOMM socket to [macAddress]. Blocking. */
        @SuppressLint("MissingPermission")
        fun open(context: Context, macAddress: String): BluetoothRawLink {
            val adapter = BluetoothLink.adapter(context)
                ?: error("This device has no Bluetooth adapter")
            val mac = BluetoothLink.normalizeAddress(macAddress)
            val device = adapter.getRemoteDevice(mac)
            val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            // Discovery starves RFCOMM: connecting while a scan is running fails intermittently.
            runCatching { adapter.cancelDiscovery() }
                .onFailure { Log.d(TAG, "cancelDiscovery failed: ${it.message}") }
            socket.connect()
            return BluetoothRawLink(socket, socket.outputStream, socket.inputStream)
        }
    }
}

/**
 * USB bulk-transfer link.
 *
 * Permission must already be held — see [UsbHostPermission]. The interface is claimed exclusively
 * (`force = true`) so a kernel printer driver cannot hold it hostage.
 */
class UsbRawLink private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val bulkOut: UsbEndpoint,
    private val bulkIn: UsbEndpoint?,
) : RawLink {

    @Volatile
    private var closed = false

    override val isOpen: Boolean get() = !closed

    override fun write(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val length = minOf(CHUNK_SIZE, bytes.size - offset)
            val chunk = if (offset == 0 && length == bytes.size) {
                bytes
            } else {
                bytes.copyOfRange(offset, offset + length)
            }
            val sent = connection.bulkTransfer(bulkOut, chunk, length, WRITE_TIMEOUT_MS)
            if (sent < 0) error("USB bulk write failed at offset $offset of ${bytes.size}")
            offset += if (sent == 0) length else sent
        }
    }

    /** USB bulk transfers are not buffered on our side; there is nothing to flush. */
    override fun flush() = Unit

    override fun readStatus(request: ByteArray, timeoutMs: Long): Byte? {
        write(request)
        val endpoint = bulkIn ?: return null
        val buffer = ByteArray(endpoint.maxPacketSize.coerceAtLeast(1))
        val read = connection.bulkTransfer(endpoint, buffer, buffer.size, timeoutMs.toInt())
        return if (read > 0) buffer[0] else null
    }

    override fun close() {
        closed = true
        runCatching { connection.releaseInterface(usbInterface) }
        runCatching { connection.close() }
    }

    companion object {
        private const val CHUNK_SIZE = 16 * 1024
        private const val WRITE_TIMEOUT_MS = 5_000

        /**
         * Opens [device], claiming the first interface that offers a bulk OUT endpoint.
         *
         * @throws IllegalStateException when the device exposes no usable printer interface.
         */
        fun open(context: Context, device: UsbDevice): UsbRawLink {
            val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
                ?: error("No USB service on this device")

            var chosen: UsbInterface? = null
            var out: UsbEndpoint? = null
            var input: UsbEndpoint? = null
            for (i in 0 until device.interfaceCount) {
                val candidate = device.getInterface(i)
                var candidateOut: UsbEndpoint? = null
                var candidateIn: UsbEndpoint? = null
                for (e in 0 until candidate.endpointCount) {
                    val endpoint = candidate.getEndpoint(e)
                    if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (endpoint.direction == UsbConstants.USB_DIR_OUT) candidateOut = endpoint
                    if (endpoint.direction == UsbConstants.USB_DIR_IN) candidateIn = endpoint
                }
                if (candidateOut != null) {
                    chosen = candidate
                    out = candidateOut
                    input = candidateIn
                    break
                }
            }
            val usbInterface = chosen ?: error("No bulk OUT interface on ${device.deviceName}")
            val bulkOut = out ?: error("No bulk OUT endpoint on ${device.deviceName}")

            val connection = manager.openDevice(device)
                ?: error("Could not open ${device.deviceName}")
            if (!connection.claimInterface(usbInterface, true)) {
                connection.close()
                error("Could not claim the interface on ${device.deviceName}")
            }
            return UsbRawLink(connection, usbInterface, bulkOut, input)
        }
    }
}
