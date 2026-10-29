package com.attaky.flasher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.hoho.android.usbserial.driver.SerialTimeoutException
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the USB host side of the flasher.
 *
 * The web page drives esptool-js through `navigator.serial` (see
 * assets/www/js/webserial.js). That polyfill funnels every call here, and this
 * class translates it onto usb-serial-for-android, which understands the CH34x
 * bridge on the Attaky deck as well as the ESP32-S3 native USB serial/JTAG and
 * the usual CP210x / FTDI silicon.
 */
class UsbSerialService(private val context: Context) {

    companion object {
        private const val TAG = "AttakyUsb"
        const val ACTION_USB_PERMISSION = "com.attaky.flasher.USB_PERMISSION"

        /** Larger than any single SLIP frame esptool-js sends for a flash block. */
        private const val READ_SIZE = 4096
        private const val READ_TIMEOUT_MS = 250
        private const val WRITE_TIMEOUT_MS = 20000

        /** Espressif's own VID, and the ESP32-S3 USB serial/JTAG product id. */
        private const val ESPRESSIF_VID = 0x303A
        private const val USB_JTAG_PID = 0x1001

        // Web Serial parity strings -> UsbSerialPort.PARITY_*
        private fun parityValue(name: String?): Int = when (name?.lowercase()) {
            "odd" -> UsbSerialPort.PARITY_ODD
            "even" -> UsbSerialPort.PARITY_EVEN
            "mark" -> UsbSerialPort.PARITY_MARK
            "space" -> UsbSerialPort.PARITY_SPACE
            else -> UsbSerialPort.PARITY_NONE
        }

        // Web Serial stop bits -> UsbSerialPort.STOPBITS_*
        private fun stopBitsValue(stopBits: Int): Int = when (stopBits) {
            2 -> UsbSerialPort.STOPBITS_2
            // 1.5 stop bits is not expressible in the Web Serial API; treat as 1.
            else -> UsbSerialPort.STOPBITS_1
        }
    }

    /** Events pushed back to the WebView. Always called off the UI thread. */
    interface Listener {
        fun onData(bytes: ByteArray, length: Int)
        fun onDisconnected(reason: String)
        fun onUsbEvent(kind: String)
        fun onPermissionResult(granted: Boolean)
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    @Volatile
    var listener: Listener? = null

    /** The native flasher screen's own listener. It shares the port with the
     *  WebView but is not driven by the read loop, so only lifecycle events
     *  (permission results, detach) are delivered to it. */
    @Volatile
    var nativeListener: Listener? = null

    /** The main page's serial console. Same deal: lifecycle events only, since
     *  it reads the port itself through [readRaw]. */
    @Volatile
    var monitorListener: Listener? = null

    @Volatile
    private var port: UsbSerialPort? = null

    @Volatile
    private var connection: UsbDeviceConnection? = null

    @Volatile
    private var currentDevice: UsbDevice? = null

    @Volatile
    private var openBaudRate: Int = 115200

    @Volatile
    private var openParams: IntArray = intArrayOf(8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

    private var readThread: Thread? = null
    private val reading = AtomicBoolean(false)
    private var receiversRegistered = false

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        if (receiversRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiversRegistered = true
    }

    fun stop() {
        close()
        if (receiversRegistered) {
            try {
                context.unregisterReceiver(receiver)
            } catch (ignored: IllegalArgumentException) {
                // Already unregistered.
            }
            receiversRegistered = false
        }
        listener = null
        monitorListener = null
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    listener?.onPermissionResult(granted)
                    nativeListener?.onPermissionResult(granted)
                    monitorListener?.onPermissionResult(granted)
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    listener?.onUsbEvent("attached")
                    nativeListener?.onUsbEvent("attached")
                    monitorListener?.onUsbEvent("attached")
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val detached = IntentCompat.getParcelableExtra(
                        intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java,
                    )
                    listener?.onUsbEvent("detached")
                    nativeListener?.onUsbEvent("detached")
                    monitorListener?.onUsbEvent("detached")
                    val open = currentDevice
                    if (open != null && detached != null && open.deviceName == detached.deviceName) {
                        // The kernel will fail the next read/write; the read loop
                        // reports it as a disconnect.
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ device list

    private fun drivers(): List<UsbSerialDriver> =
        try {
            UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        } catch (t: Throwable) {
            Log.w(TAG, "USB probe failed", t)
            emptyList()
        }

    /** Stable (device index, port) pairs in probe order. */
    private fun refs(): List<Pair<Int, UsbSerialPort>> {
        val out = ArrayList<Pair<Int, UsbSerialPort>>()
        var index = 0
        for (driver in drivers()) {
            for (p in driver.ports) {
                out.add(index to p)
                index++
            }
        }
        return out
    }

    private fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    fun listPortsJson(): String {
        val array = JSONArray()
        refs().forEach { (index, p) ->
            val device = p.device
            val granted = hasPermission(device)
            val obj = JSONObject()
            obj.put("index", index)
            obj.put("vid", device.vendorId)
            obj.put("pid", device.productId)
            obj.put("driver", p.driver.javaClass.simpleName)
            obj.put("portNumber", p.portNumber)
            obj.put("deviceName", device.deviceName)
            obj.put("granted", granted)
            obj.put("name", describe(device, p))
            if (granted) {
                obj.put("manufacturer", device.manufacturerName ?: JSONObject.NULL)
                obj.put("product", device.productName ?: JSONObject.NULL)
                obj.put("serial", safeSerial(device))
            }
            array.put(obj)
        }
        return array.toString()
    }

    private fun safeSerial(device: UsbDevice): Any = try {
        device.serialNumber ?: JSONObject.NULL
    } catch (t: Throwable) {
        JSONObject.NULL
    }

    private fun describe(device: UsbDevice, p: UsbSerialPort): String {
        val known = when (device.vendorId) {
            0x1A86 -> "WCH CH34x"
            0x303A -> "Espressif USB serial/JTAG"
            0x10C4 -> "CP210x"
            0x0403 -> "FTDI"
            0x067B -> "PL2303"
            else -> p.driver.javaClass.simpleName
        }
        val vidPid = "0x%04X:0x%04X".format(device.vendorId, device.productId)
        return "$known ($vidPid)"
    }

    private fun findPort(index: Int): UsbSerialPort? = refs().firstOrNull { it.first == index }?.second

    // ------------------------------------------------------------------ permission

    /** @return "granted" when already allowed, "permission" when a dialog is pending. */
    fun ensurePermission(index: Int): String {
        val p = findPort(index) ?: return "error: no such device (index $index)"
        return if (hasPermission(p.device)) "granted" else requestPermission(p.device)
    }

    private fun requestPermission(device: UsbDevice): String {
        return try {
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // The system writes EXTRA_DEVICE / EXTRA_PERMISSION_GRANTED into the intent.
                flags = flags or PendingIntent.FLAG_MUTABLE
            }
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
            val pending = PendingIntent.getBroadcast(context, 0, intent, flags)
            usbManager.requestPermission(device, pending)
            "permission"
        } catch (t: Throwable) {
            "error: ${t.message}"
        }
    }

    /** True when the app already holds permission for the given attached device. */
    fun hasPermissionForDevice(device: UsbDevice?): Boolean =
        device != null && hasPermission(device)

    // ------------------------------------------------------------------ open / close

    data class OpenOutcome(val status: String, val message: String? = null, val info: JSONObject? = null)

    fun open(
        index: Int,
        baudRate: Int,
        dataBits: Int,
        stopBits: Int,
        parity: String,
        flowControl: String,
    ): OpenOutcome = openInternal(index, baudRate, dataBits, stopBits, parity, flowControl, true)

    /**
     * Open the port for the native flasher. The background read loop stays off so
     * [readRaw] owns the stream; the caller drives the request/response cycle.
     */
    fun openRaw(index: Int, baudRate: Int): OpenOutcome =
        openInternal(index, baudRate, 8, UsbSerialPort.STOPBITS_1, "none", "none", false)

    private fun openInternal(
        index: Int,
        baudRate: Int,
        dataBits: Int,
        stopBits: Int,
        parity: String,
        flowControl: String,
        startReads: Boolean,
    ): OpenOutcome {
        val p = findPort(index) ?: return OpenOutcome("error", "No USB serial device at index $index")

        if (!hasPermission(p.device)) {
            return if (requestPermission(p.device) == "permission") {
                OpenOutcome("permission")
            } else {
                OpenOutcome("error", "Could not request USB permission")
            }
        }

        close()

        val device = p.device
        val conn = try {
            usbManager.openDevice(device)
        } catch (t: Throwable) {
            null
        } ?: return OpenOutcome("error", "UsbManager.openDevice() returned null")

        try {
            p.open(conn)
        } catch (e: IOException) {
            runCatching { conn.close() }
            return OpenOutcome("error", "Port open failed: ${e.message}")
        }

        val stop = stopBitsValue(stopBits)
        val par = parityValue(parity)
        try {
            p.setParameters(baudRate, dataBits, stop, par)
        } catch (t: Throwable) {
            // Some bridges reject rare combinations; retry with 8N1 before failing.
            try {
                p.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            } catch (t2: Throwable) {
                runCatching { p.close() }
                return OpenOutcome("error", "setParameters failed: ${t.message}")
            }
        }

        try {
            if (flowControl.equals("hardware", ignoreCase = true)) {
                p.setFlowControl(UsbSerialPort.FlowControl.RTS_CTS)
            } else {
                p.setFlowControl(UsbSerialPort.FlowControl.NONE)
            }
        } catch (ignored: Throwable) {
            // Flow control is optional for flashing.
        }

        // esptool-js drives EN/IO0 through DTR/RTS; start from a known idle
        // state. Not for the S3's own USB-JTAG, though: it reads those edges as
        // reset requests, so forcing them here restarted the deck every time the
        // port was opened.
        val nativeUsb = device.vendorId == ESPRESSIF_VID && device.productId == USB_JTAG_PID
        if (!nativeUsb) {
            runCatching { p.setDTR(false) }
            runCatching { p.setRTS(false) }
        }

        port = p
        connection = conn
        currentDevice = device
        openBaudRate = baudRate
        openParams = intArrayOf(dataBits, stop, par)

        if (startReads) startReadLoop(p)

        val info = JSONObject().apply {
            put("vid", device.vendorId)
            put("pid", device.productId)
            put("name", describe(device, p))
            put("baudRate", baudRate)
        }
        return OpenOutcome("ok", null, info)
    }

    fun close() {
        reading.set(false)
        readThread?.let { t ->
            t.interrupt()
            runCatching { t.join(400) }
        }
        readThread = null
        val p = port
        port = null
        connection = null
        currentDevice = null
        if (p != null) {
            // UsbSerialPort.close() also closes the UsbDeviceConnection.
            runCatching { p.close() }
        }
    }

    fun isOpen(): Boolean = port != null

    /**
     * True while the open device is still present on the USB bus.
     *
     * A reset on the ESP32-S3's own USB re-enumerates the device, so the open
     * connection goes stale and reads stay silent forever. Checking the bus
     * separates that from a deck that is attached but simply not answering.
     */
    fun isDeviceAttached(): Boolean {
        val device = currentDevice ?: return false
        return runCatching {
            usbManager.deviceList.values.any { it.deviceName == device.deviceName }
        }.getOrDefault(true)
    }

    /**
     * True when the open device is the ESP32-S3's own USB serial/JTAG.
     *
     * That peripheral has no EN/IO0 auto-reset circuit on the wire: it watches
     * the DTR/RTS pattern itself and resets the chip from it. Pulsing it the
     * way a CH340 board is pulsed makes the deck restart on every edge.
     */
    fun isUsbJtagLoader(): Boolean {
        val device = currentDevice ?: return false
        return device.vendorId == ESPRESSIF_VID && device.productId == USB_JTAG_PID
    }

    // ------------------------------------------------------------------ io

    @Synchronized
    fun write(bytes: ByteArray) {
        val p = port ?: throw IOException("Serial port is not open")
        p.write(bytes, WRITE_TIMEOUT_MS)
    }

    /**
     * Blocking read for the native flasher (opened via [openRaw]). Returns the
     * number of bytes read, or 0 when the timeout expired with no new data.
     */
    @Synchronized
    fun readRaw(buffer: ByteArray, timeoutMs: Int): Int {
        val p = port ?: throw IOException("Serial port is not open")
        return readOrNothing(p, buffer, timeoutMs)
    }

    /**
     * Read with "nothing arrived yet" expressed as 0 bytes per [SerialTimeoutException].
     *
     * usb-serial-for-android's `UsbSerialPort.read(dest, timeout)` does not return 0
     * on a quiet line: it throws [SerialTimeoutException]. Swallowing only that one
     * exception keeps silence meaning "no data yet" everywhere in this app —
     * otherwise the WebView read loop called a disconnect on the first idle tick
     * (esptool-js then reports "No serial data received."), and the native flasher
     * aborted an erase the deck was simply still working on. A real fault (device
     * gone, I/O error) is still a different exception and still propagates.
     */
    private fun readOrNothing(p: UsbSerialPort, dest: ByteArray, timeoutMs: Int): Int = try {
        p.read(dest, timeoutMs)
    } catch (e: SerialTimeoutException) {
        0
    }

    /** In-place baud change so esptool-js can skip a full close/reopen. */
    @Synchronized
    fun setBaudRate(baudRate: Int) {
        val p = port ?: throw IOException("Serial port is not open")
        p.setParameters(baudRate, openParams[0], openParams[1], openParams[2])
        openBaudRate = baudRate
    }

    fun currentBaudRate(): Int = openBaudRate

    @Synchronized
    fun setSignals(dtr: Boolean?, rts: Boolean?, breakSignal: Boolean?) {
        val p = port ?: throw IOException("Serial port is not open")
        dtr?.let { runCatching { p.setDTR(it) } }
        rts?.let { runCatching { p.setRTS(it) } }
        breakSignal?.let { runCatching { p.setBreak(it) } }
    }

    fun getSignalsJson(): String {
        val p = port ?: return "{}"
        val obj = JSONObject()
        obj.put("dataTerminalReady", runCatching { p.getDTR() }.getOrDefault(false))
        obj.put("requestToSend", runCatching { p.getRTS() }.getOrDefault(false))
        obj.put("clearToSend", runCatching { p.getCTS() }.getOrDefault(false))
        obj.put("dataSetReady", runCatching { p.getDSR() }.getOrDefault(false))
        return obj.toString()
    }

    /** Drop bytes the bridge already buffered, mirroring esptool's flushInput. */
    fun purgeInput() {
        val p = port ?: return
        runCatching { p.purgeHwBuffers(true, true) }
    }

    private fun startReadLoop(p: UsbSerialPort) {
        reading.set(true)
        val thread = Thread({
            val buffer = ByteArray(READ_SIZE)
            while (reading.get()) {
                try {
                    val n = readOrNothing(p, buffer, READ_TIMEOUT_MS)
                    if (n > 0) {
                        // The listener base64-encodes synchronously, so reuse is safe.
                        listener?.onData(buffer, n)
                    }
                } catch (e: Throwable) {
                    if (reading.get()) {
                        reading.set(false)
                        val reason = e.message ?: e.toString()
                        Log.w(TAG, "read loop ended: $reason")
                        listener?.onDisconnected(reason)
                    }
                    break
                }
            }
        }, "attaky-usb-read")
        thread.isDaemon = true
        thread.start()
        readThread = thread
    }
}
