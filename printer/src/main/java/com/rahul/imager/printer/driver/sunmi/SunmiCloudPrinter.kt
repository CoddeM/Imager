package com.rahul.imager.printer.driver.sunmi

import android.content.Context
import android.util.Log
import com.rahul.imager.printer.domain.PrintCategory
import com.rahul.imager.printer.domain.PrintError
import com.rahul.imager.printer.domain.PrinterBrand
import com.rahul.imager.printer.domain.PrinterContext
import com.rahul.imager.printer.domain.PrinterStatus
import com.rahul.imager.printer.domain.ThermalPrinter
import com.rahul.imager.printer.domain.TransportType
import com.rahul.imager.printer.raster.Alignment
import com.rahul.imager.printer.raster.RasterJob
import com.rahul.imager.printer.transport.UsbHostPermission
import com.rahul.imager.printer.transport.UsbVendorId
import com.sunmi.externalprinterlibrary2.ConnectCallback
import com.sunmi.externalprinterlibrary2.ResultCallback
import com.sunmi.externalprinterlibrary2.SearchCallback
import com.sunmi.externalprinterlibrary2.SearchMethod
import com.sunmi.externalprinterlibrary2.StatusCallback
import com.sunmi.externalprinterlibrary2.SunmiPrinterManager
import com.sunmi.externalprinterlibrary2.printer.CloudPrinter
import com.sunmi.externalprinterlibrary2.style.AlignStyle
import com.sunmi.externalprinterlibrary2.style.CloudPrinterStatus
import com.sunmi.externalprinterlibrary2.style.ImageAlgorithm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Holds the `CloudPrinter` handles for Sunmi's external LAN and USB printers.
 *
 * LAN handles can be constructed directly from an address. USB handles CANNOT: the SDK's USB
 * constructors are package-private, so the only supported way to obtain one is to run the SDK's
 * own USB search and keep what it hands back.
 */
object SunmiCloudManager {

    private const val TAG = "SunmiCloud"

    const val CONNECT_TIMEOUT_MS = 15_000L
    const val PRINT_TIMEOUT_MS = 30_000L
    const val SEARCH_TIMEOUT_MS = 8_000L

    private val printers = ConcurrentHashMap<String, CloudPrinter>()

    /** The cached handle for [key], if one exists. */
    fun cached(key: String): CloudPrinter? = printers[key]

    /** Creates (or reuses) a LAN handle for `host:port`. */
    fun lanPrinter(host: String, port: Int): CloudPrinter? {
        val key = "lan:$host:$port"
        printers[key]?.let { return it }
        return runCatching { SunmiPrinterManager.getInstance().createCloudPrinter(host, port) }
            .onFailure { Log.w(TAG, "createCloudPrinter($host:$port) threw", it) }
            .getOrNull()
            ?.also { printers[key] = it }
    }

    /**
     * Finds a USB handle by running the SDK's own USB search.
     *
     * @param identifier the saved address; matched against the device path, name and MAC the SDK
     *   reports, so a printer keeps working when only one of them survives a replug.
     */
    suspend fun usbPrinter(context: Context, identifier: String): CloudPrinter? {
        val key = "usb:$identifier"
        printers[key]?.let { return it }
        val found = search(context, SearchMethod.USB).firstOrNull { it.matches(identifier) }
            ?: search(context, SearchMethod.USB).singleOrNull()
        return found?.also { printers[key] = it }
    }

    /** Runs one SDK search pass and collects everything it reports inside the window. */
    suspend fun search(context: Context, method: Int): List<CloudPrinter> {
        val results = LinkedHashMap<String, CloudPrinter>()
        withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val callback = SearchCallback { printer ->
                    printer?.let { results[it.identity()] = it }
                }
                runCatching {
                    SunmiPrinterManager.getInstance()
                        .searchCloudPrinter(context.applicationContext, method, callback)
                }.onFailure {
                    Log.w(TAG, "searchCloudPrinter($method) threw", it)
                    if (continuation.isActive) continuation.resume(Unit)
                }
                // The SDK has no "search finished" callback, so the caller closes the window.
                continuation.invokeOnCancellation {
                    runCatching {
                        SunmiPrinterManager.getInstance()
                            .stopSearch(context.applicationContext, method)
                    }
                }
            }
        }
        runCatching {
            SunmiPrinterManager.getInstance().stopSearch(context.applicationContext, method)
        }
        return results.values.toList()
    }

    /** Drops and releases a cached handle. Safe from any thread. */
    fun forceClose(key: String, context: Context?) {
        printers.remove(key)?.let { printer ->
            runCatching { printer.release(context) }
                .onFailure { Log.d(TAG, "release($key) threw: ${it.message}") }
        }
    }

    /** A stable identity for a discovered handle. */
    fun CloudPrinter.identity(): String {
        val info = cloudPrinterInfo
        return info?.address?.takeIf { it.isNotBlank() }
            ?: info?.mac?.takeIf { it.isNotBlank() }
            ?: info?.name.orEmpty()
    }

    /** True when this handle is the one the saved [identifier] refers to. */
    fun CloudPrinter.matches(identifier: String): Boolean {
        if (identifier.isBlank()) return false
        val info = cloudPrinterInfo ?: return false
        return identifier.equals(info.address, ignoreCase = true) ||
            identifier.equals(info.mac, ignoreCase = true) ||
            identifier.equals(info.name, ignoreCase = true)
    }
}

/**
 * Sunmi's external LAN and USB printers, driven through `external-printerlibrary2`.
 *
 * Success on this API means "the result callback fired", not "the call returned": every print
 * command only appends to a transaction buffer, and the buffer is what gets committed.
 */
class SunmiCloudThermalPrinter(
    override val displayName: String,
    override val transport: TransportType,
    private val identifier: String,
    private val port: Int,
) : ThermalPrinter {

    override val brand: PrinterBrand = PrinterBrand.SUNMI

    private val cacheKey: String = when (transport) {
        TransportType.USB -> "usb:$identifier"
        else -> "lan:$identifier:$port"
    }

    private val printerContext: PrinterContext
        get() = PrinterContext(brand, transport, identifier)

    override suspend fun isAvailable(context: Context?): Boolean = when (transport) {
        TransportType.LAN -> identifier.isNotBlank()
        TransportType.USB -> context != null &&
            UsbHostPermission.attachedDevices(context).any { it.vendorId == UsbVendorId.SUNMI }

        else -> false
    }

    override suspend fun connect(context: Context?): Boolean = withContext(Dispatchers.IO) {
        val printer = handle(context) ?: return@withContext false
        open(context, printer)
    }

    override suspend fun printImage(
        context: Context?,
        job: RasterJob,
        onProgress: (Float) -> Unit,
    ): PrintError? = withContext(Dispatchers.IO) {
        val printer = handle(context) ?: return@withContext PrintError(
            category = if (transport == TransportType.USB) {
                PrintCategory.USB_NOT_ENUMERATED
            } else {
                PrintCategory.CONNECT_FAILED
            },
            context = printerContext,
            detail = "No Sunmi CloudPrinter could be obtained for \"$identifier\".",
        )

        if (!open(context, printer)) {
            return@withContext PrintError(
                PrintCategory.CONNECT_TIMEOUT,
                printerContext,
                detail = "The Sunmi CloudPrinter did not connect within " +
                    "${SunmiCloudManager.CONNECT_TIMEOUT_MS / 1000}s.",
            )
        }

        try {
            printer.initStyle()
            printer.setAlignment(job.options.alignment.toSunmi())

            val bands = job.bands
            bands.forEachIndexed { index, band ->
                currentCoroutineContext().ensureActive()
                // The raster is already reduced to true 1-bit dots, so BINARIZATION passes them
                // through untouched. DITHERING would dither an already-dithered image and turn
                // the carefully chosen pattern into mush.
                printer.printImage(job.bandBitmap(band), ImageAlgorithm.BINARIZATION)
                onProgress((index + 1).toFloat() / bands.size)
            }

            printer.lineFeed(job.options.feedLinesAfter)
            if (job.options.cutAfter && job.paper.supportsCutter) {
                runCatching { printer.cutPaper(false) }
            }

            when (val result = commit(printer)) {
                null -> null
                else -> PrintError(
                    category = SunmiErrorMapper.fromCloudStatus(result),
                    context = printerContext.copy(vendorErrorCode = result.name),
                    detail = "The printer rejected the job (${result.name}).",
                )
            }
        } catch (e: CancellationException) {
            runCatching { printer.clearTransBuffer() }
            SunmiCloudManager.forceClose(cacheKey, context)
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sunmi CloudPrinter print failed for $identifier", e)
            runCatching { printer.clearTransBuffer() }
            SunmiCloudManager.forceClose(cacheKey, context)
            PrintError(PrintCategory.SEND_FAILED, printerContext, cause = e, detail = e.message)
        }
    }

    override suspend fun disconnect(context: Context?): Boolean = withContext(Dispatchers.IO) {
        SunmiCloudManager.forceClose(cacheKey, context)
        true
    }

    override fun forceClose() {
        SunmiCloudManager.forceClose(cacheKey, null)
    }

    override suspend fun resolvedLockIdentity(context: Context?): String? {
        if (transport != TransportType.USB) return null
        val ctx = context ?: return null
        return SunmiCloudManager.usbPrinter(ctx, identifier)
            ?.let { with(SunmiCloudManager) { it.identity() } }
            ?.takeIf { it.isNotBlank() }
    }

    override suspend fun queryStatus(context: Context?): PrinterStatus? =
        withContext(Dispatchers.IO) {
            val printer = handle(context) ?: return@withContext null
            if (!open(context, printer)) return@withContext null
            val status = withTimeoutOrNull(SunmiCloudManager.CONNECT_TIMEOUT_MS) {
                suspendCancellableCoroutine<CloudPrinterStatus?> { continuation ->
                    val resumed = AtomicBoolean(false)
                    val callback = StatusCallback { state ->
                        if (resumed.compareAndSet(false, true) && continuation.isActive) {
                            continuation.resume(state)
                        }
                    }
                    runCatching { printer.getDeviceState(callback) }.onFailure {
                        if (resumed.compareAndSet(false, true) && continuation.isActive) {
                            continuation.resume(null)
                        }
                    }
                }
            } ?: return@withContext null

            PrinterStatus(
                online = status != CloudPrinterStatus.OFFLINE,
                paperOut = status == CloudPrinterStatus.OUT_PAPER,
                paperLow = status == CloudPrinterStatus.NEAR_OUT_PAPER,
                coverOpen = status == CloudPrinterStatus.COVER,
                overheat = status == CloudPrinterStatus.OVER_HOT ||
                    status == CloudPrinterStatus.MOTOR_HOT,
                busy = status == CloudPrinterStatus.RUNNING,
                rawVendorCode = status?.name,
            )
        }

    /** Resolves the handle for this printer, creating or searching for it as needed. */
    private suspend fun handle(context: Context?): CloudPrinter? = when (transport) {
        TransportType.LAN -> SunmiCloudManager.lanPrinter(identifier, port)
        TransportType.USB -> context?.let { SunmiCloudManager.usbPrinter(it, identifier) }
        else -> null
    }

    /** Connects, bounded by [SunmiCloudManager.CONNECT_TIMEOUT_MS]. */
    private suspend fun open(context: Context?, printer: CloudPrinter): Boolean {
        if (runCatching { printer.isConnected }.getOrDefault(false)) return true
        val ctx = context ?: return false
        return withTimeoutOrNull(SunmiCloudManager.CONNECT_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val resumed = AtomicBoolean(false)
                fun finish(success: Boolean) {
                    if (resumed.compareAndSet(false, true) && continuation.isActive) {
                        continuation.resume(success)
                    }
                }

                val callback = object : ConnectCallback {
                    override fun onConnect() = finish(true)
                    override fun onFailed(message: String?) {
                        Log.w(TAG, "CloudPrinter connect failed: $message")
                        finish(false)
                    }

                    override fun onDisConnect() = finish(false)
                }

                runCatching { printer.connect(ctx.applicationContext, callback) }
                    .onFailure {
                        Log.w(TAG, "CloudPrinter.connect threw", it)
                        finish(false)
                    }
            }
        } ?: false
    }

    /**
     * Commits the transaction buffer.
     *
     * @return `null` on success, or the [CloudPrinterStatus] the printer failed with. A timeout is
     *   reported as [CloudPrinterStatus.UNKNOWN], because the job may or may not have landed and
     *   the engine must treat it as a dirty failure.
     */
    private suspend fun commit(printer: CloudPrinter): CloudPrinterStatus? {
        // Wrapped rather than returned bare: `null` legitimately means SUCCESS here, and
        // withTimeoutOrNull also yields null, so the two have to stay distinguishable.
        val outcome = withTimeoutOrNull(SunmiCloudManager.PRINT_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val resumed = AtomicBoolean(false)
                fun finish(status: CloudPrinterStatus?) {
                    if (resumed.compareAndSet(false, true) && continuation.isActive) {
                        continuation.resume(CommitOutcome(status))
                    }
                }

                val callback = object : ResultCallback {
                    override fun onComplete() = finish(null)
                    override fun onFailed(status: CloudPrinterStatus?) =
                        finish(status ?: CloudPrinterStatus.UNKNOWN)
                }

                runCatching { printer.commitTransBuffer(callback) }
                    .onFailure {
                        Log.w(TAG, "commitTransBuffer threw", it)
                        finish(CloudPrinterStatus.UNKNOWN)
                    }
            }
        } ?: CommitOutcome(CloudPrinterStatus.UNKNOWN)
        return outcome.status
    }

    /** Success (`status == null`) versus a specific failure, distinguishable through a timeout. */
    private class CommitOutcome(val status: CloudPrinterStatus?)

    private fun Alignment.toSunmi(): AlignStyle = when (this) {
        Alignment.LEFT -> AlignStyle.LEFT
        Alignment.CENTER -> AlignStyle.CENTER
        Alignment.RIGHT -> AlignStyle.RIGHT
    }

    private companion object {
        const val TAG = "SunmiCloudPrinter"
    }
}
