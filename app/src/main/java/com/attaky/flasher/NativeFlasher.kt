package com.attaky.flasher

import android.content.SharedPreferences
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.json.JSONArray
import java.util.concurrent.Executors

/**
 * The native offline flasher screen.
 *
 * Reached from the boot chooser's "Downloaded Firmware" tile and from the
 * store's floating escape button. Everything here is local: the deck is driven
 * over USB, firmware comes from the phone's own storage (or files the user
 * picks), and nothing needs the network except the Store link.
 *
 * The deck is flashed by the store's own flasher: [JsFlasher] loads the vendored
 * esptool-js off-screen and drives the port through the same bridge the store
 * pages use, so both flash the chip identically. This screen only builds the
 * image, shows the log and the bar, and touches the UI back on the main thread.
 *
 * One image per row: the store writes whole merged images now, so a row is
 * already everything the deck needs and there is no local bin set to assemble.
 */
class NativeFlasher(
    private val activity: android.app.Activity,
    private val service: UsbSerialService,
    private val downloads: FirmwareDownloads,
    private val prefs: SharedPreferences,
    private val root: View,
    private val onExit: () -> Unit,
    private val onOpenStore: () -> Unit,
) : UsbSerialService.Listener {

    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "attaky-native-flash").apply { isDaemon = true }
    }

    private val btnBack: View = root.findViewById(R.id.btnBack)
    private val btnStore: View = root.findViewById(R.id.btnStore)
    private val btnConnect: Button = root.findViewById(R.id.btnConnect)
    private val btnRescan: Button = root.findViewById(R.id.btnRescan)
    private val connPill: TextView = root.findViewById(R.id.connPill)
    private val chipInfo: TextView = root.findViewById(R.id.chipInfo)
    private val firmwareList: ViewGroup = root.findViewById(R.id.firmwareList)
    private val firmwareEmpty: View = root.findViewById(R.id.firmwareEmpty)
    private val consoleView: TextView = root.findViewById(R.id.console)
    private val progressBox: View = root.findViewById(R.id.progressBox)
    private val progressBar: ProgressBar = root.findViewById(R.id.progressBar)
    private val progressText: TextView = root.findViewById(R.id.progressText)
    private val advancedToggle: View = root.findViewById(R.id.advancedToggle)
    private val advancedBody: View = root.findViewById(R.id.advancedBody)
    private val advancedChevron: TextView = root.findViewById(R.id.advancedChevron)
    private val baudInput: EditText = root.findViewById(R.id.baudInput)
    private val checkVerify: CheckBox = root.findViewById(R.id.checkVerify)
    private val checkCompress: CheckBox = root.findViewById(R.id.checkCompress)

    private val console = StringBuilder()

    @Volatile
    private var connected = false

    @Volatile
    private var busy = false

    /** The store's flasher, running off-screen (see [JsFlasher]). */
    private var engine: JsFlasher? = null

    /** Whose bytes the USB read loop delivered before the engine took over. */
    private var previousListener: UsbSerialService.Listener? = null

    private class Write(val offset: Long, val label: String, val read: () -> ByteArray)

    private class Port(val index: Int, val granted: Boolean, val name: String)

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        service.nativeListener = this

        // The store's esptool-js does the talking. It lives in a 1 px WebView on
        // this screen and receives the deck's bytes through the same serial
        // bridge the store pages use, so the read loop must point at it.
        previousListener = service.listener
        engine = (root as? ViewGroup)?.let { container ->
            JsFlasher(
                activity = activity,
                service = service,
                container = container,
                onLog = { line -> log(line) },
                onProgress = { percent -> onProgress(percent) },
                onConnected = { chip, mac -> onEngineConnected(chip, mac) },
                onConnectFailed = { message -> onEngineConnectFailed(message) },
                onFlashDone = { ok, message -> onEngineFlashDone(ok, message) },
            ).also { service.listener = it.bridge }
        }

        btnBack.setOnClickListener { onExit() }
        btnStore.setOnClickListener { onOpenStore() }
        advancedToggle.setOnClickListener { toggleAdvanced() }
        btnConnect.setOnClickListener { onConnectClicked() }
        btnRescan.setOnClickListener { refreshDeckStatus(logLine = true) }

        baudInput.setText(prefs.getInt(KEY_BAUD, DEFAULT_BAUD).toString())
        checkVerify.isChecked = prefs.getBoolean(KEY_VERIFY, true)
        checkCompress.isChecked = prefs.getBoolean(KEY_COMPRESS, false)

        root.findViewById<TextView>(R.id.flasherVersion).text =
            activity.getString(R.string.splash_version, BuildConfig.VERSION_NAME)

        renderFirmware()
        updateConnectedUi()
        log("Ready. Pick a downloaded image, or connect the deck.")
        refreshDeckStatus(logLine = false)
    }

    /** Persist the advanced options and release the port. */
    fun stop() {
        runCatching {
            prefs.edit()
                .putInt(KEY_BAUD, baudFromPrefs())
                .putBoolean(KEY_VERIFY, checkVerify.isChecked)
                .putBoolean(KEY_COMPRESS, checkCompress.isChecked)
                .apply()
        }
        service.nativeListener = null
        engine?.disconnect()
        engine?.release()
        engine = null
        service.listener = previousListener
        previousListener = null
        runCatching { service.close() }
        io.shutdownNow()
        connected = false
    }

    /** True when the store has already put at least one image on the phone. */
    fun hasDownloadedFirmware(): Boolean = downloads.entries().isNotEmpty()

    // ------------------------------------------------------------------ deck

    private fun refreshDeckStatus(logLine: Boolean) {
        val ports = probePorts()
        if (ports.isEmpty()) {
            connPill.text = if (connected) "connected" else "no deck found"
            if (logLine) log("No USB serial device attached. Plug the deck in over USB.")
        } else {
            connPill.text = if (connected) "connected" else "deck found"
            if (logLine || !connected) {
                log("Found ${ports.size} USB serial port(s): ${ports.first().name}")
            }
        }
    }

    private fun probePorts(): List<Port> {
        val out = ArrayList<Port>()
        try {
            val array = JSONArray(service.listPortsJson())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                out.add(
                    Port(
                        index = obj.optInt("index", i),
                        granted = obj.optBoolean("granted", false),
                        name = obj.optString("name", "USB serial device"),
                    ),
                )
            }
        } catch (t: Throwable) {
            log("Could not read the USB device list: ${t.message}")
        }
        return out
    }

    private fun onConnectClicked() {
        if (busy) return
        if (connected) {
            disconnect()
            return
        }
        val ports = probePorts()
        if (ports.isEmpty()) {
            toast("No deck found. Plug it in over USB.")
            log("Connect failed: no USB serial device attached.")
            return
        }
        val port = ports.firstOrNull { it.granted } ?: ports.first()
        connectWithEngine(port.index)
    }

    /**
     * Hand the deck to esptool-js: it opens the port (asking for USB permission
     * if needed), resets and syncs the chip, reads its description and MAC, and
     * loads the flasher stub — the same handshake a store page performs.
     */
    private fun connectWithEngine(index: Int) {
        val engine = engine
        if (engine == null) {
            log("The flash engine is not loaded; reopen this screen.")
            toast("The flash engine is not loaded.")
            return
        }
        setBusy(true)
        log("Connecting with the store's flasher engine…")
        engine.connect(index, baudFromPrefs())
    }

    private fun onEngineConnected(chip: String, mac: String) {
        connected = true
        setBusy(false)
        updateConnectedUi()
        chipInfo.visibility = View.VISIBLE
        chipInfo.text = if (mac.isNotEmpty()) "$chip\nMAC $mac" else chip
    }

    private fun onEngineConnectFailed(message: String) {
        connected = false
        setBusy(false)
        updateConnectedUi()
        chipInfo.visibility = View.GONE
        log("Could not connect: $message")
        toast("Could not connect to the deck.")
        runCatching { service.close() }
    }

    private fun disconnect() {
        engine?.disconnect()
        runCatching { service.close() }
        connected = false
        updateConnectedUi()
        chipInfo.visibility = View.GONE
        log("Disconnected.")
    }

    override fun onPermissionResult(granted: Boolean) {
        activity.runOnUiThread {
            if (!granted) {
                log("USB permission denied.")
                toast("USB permission denied.")
            }
        }
    }

    override fun onUsbEvent(kind: String) {
        activity.runOnUiThread {
            when (kind) {
                "attached" -> {
                    log("A USB device was attached.")
                    refreshDeckStatus(logLine = false)
                }
                "detached" -> {
                    log("A USB device was detached.")
                    if (connected) {
                        connected = false
                        updateConnectedUi()
                        chipInfo.visibility = View.GONE
                    }
                }
            }
        }
    }

    override fun onDisconnected(reason: String) {
        activity.runOnUiThread {
            if (connected) {
                connected = false
                updateConnectedUi()
                chipInfo.visibility = View.GONE
                log("The deck was disconnected: $reason")
            }
        }
    }

    override fun onData(bytes: ByteArray, length: Int) = Unit

    // ------------------------------------------------------------------ firmware

    private fun renderFirmware() {
        firmwareList.removeAllViews()
        val entries = downloads.entries()
        firmwareEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(activity)
        for (entry in entries) {
            val row = inflater.inflate(R.layout.item_firmware, firmwareList, false)
            row.findViewById<TextView>(R.id.fwName).text = entry.name
            row.findViewById<TextView>(R.id.fwMeta).text = buildString {
                append(humanSize(entry.size))
                append(" · 0x").append(offsetOf(entry).toString(16))
                FlashLayout.slotFor(entry.name)?.let { append(" · ").append(it.role) }
            }
            row.findViewById<View>(R.id.fwFlash).setOnClickListener { confirmFlashDownloaded(entry) }
            row.findViewById<View>(R.id.fwDelete).setOnClickListener { confirmDelete(entry.name) }
            firmwareList.addView(row)
        }
    }

    private fun confirmFlashDownloaded(entry: FirmwareDownloads.Entry) {
        if (busy) return
        if (!connected) {
            toast("Connect the deck first.")
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(entry.name)
            .setMessage("Write ${humanSize(entry.size)} to the deck at 0x${offsetOf(entry).toString(16)}?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Flash") { _, _ -> flashDownloaded(entry) }
            .show()
    }

    /** What the index recorded, else what the file name says. */
    private fun offsetOf(entry: FirmwareDownloads.Entry): Long =
        if (entry.offset > 0L) entry.offset else (FlashLayout.slotFor(entry.name)?.offset ?: 0L)

    private fun flashDownloaded(entry: FirmwareDownloads.Entry) {
        val file = downloads.fileFor(entry.name)
        if (file == null) {
            toast("That file is gone.")
            renderFirmware()
            return
        }
        startFlash(listOf(Write(offsetOf(entry), entry.name) { file.readBytes() }))
    }

    private fun confirmDelete(name: String) {
        if (busy) return
        AlertDialog.Builder(activity)
            .setTitle("Delete $name?")
            .setMessage("Remove this image from the phone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                if (downloads.delete(name)) log("Deleted $name.") else log("Could not delete $name.")
                renderFirmware()
            }
            .show()
    }

    // ------------------------------------------------------------------ flashing

    private fun startFlash(writes: List<Write>) {
        val compress = checkCompress.isChecked
        val verify = checkVerify.isChecked

        setBusy(true)
        progressBox.visibility = View.VISIBLE
        progressBar.progress = 0
        progressText.text = "Preparing…"
        log(if (writes.size == 1) "Flashing ${writes.first().label}…" else "Putting ${writes.size} bins together…")

        runIo {
            // Read and place everything before touching the deck: a bad set (a
            // missing file, two bins on the same offset) must not reset the chip.
            val image = try {
                FlashLayout.join(
                    writes.map { write -> FlashLayout.Part(write.label, write.offset, write.read()) },
                )
            } catch (t: Throwable) {
                activity.runOnUiThread {
                    setBusy(false)
                    progressBox.visibility = View.GONE
                    log("Could not build the image: ${t.message}")
                    toast(t.message ?: "Could not build the image.")
                }
                return@runIo
            }

            image.parts.forEach { part ->
                log("  ${part.label} → 0x${part.offset.toString(16)} (${humanSize(part.bytes.size.toLong())})")
            }
            log(
                "One image: ${humanSize(image.bytes.size.toLong())} at " +
                    "0x${image.offset.toString(16)}.",
            )

            // From here the store's engine owns the deck: it writes the image
            // through the flasher stub and resets the chip when done.
            val engine = engine
            if (engine == null) {
                activity.runOnUiThread {
                    setBusy(false)
                    progressBox.visibility = View.GONE
                    log("The flash engine is not loaded; reopen this screen.")
                }
                return@runIo
            }
            engine.flash(
                offset = image.offset,
                bytes = image.bytes,
                compress = compress,
                verify = verify,
            )
        }
    }

    private fun onEngineFlashDone(ok: Boolean, message: String) {
        activity.runOnUiThread {
            setBusy(false)
            if (ok) {
                progressBar.progress = 100
                progressText.text = "Written — 100%"
            }
            progressBox.visibility = View.GONE
            if (ok) {
                log("Done. The deck is rebooting.")
                showFlashDone()
            } else {
                log("Flash failed: $message")
                toast("Flash failed: $message")
            }
            // The engine hard-resets the chip and closes the port, so the
            // session is over either way.
            connected = false
            updateConnectedUi()
            chipInfo.visibility = View.GONE
            runCatching { service.close() }
        }
    }

    private fun showFlashDone() {
        AlertDialog.Builder(activity)
            .setTitle("Flash complete")
            .setMessage("Firmware written successfully.")
            .setPositiveButton("OK") { _, _ -> renderFirmware() }
            .show()
    }

    private fun onProgress(percent: Int) {
        activity.runOnUiThread {
            progressBar.progress = percent
            progressText.text = "Writing — $percent%"
        }
    }

    // ------------------------------------------------------------------ plumbing

    private fun setBusy(value: Boolean) {
        busy = value
        btnConnect.isEnabled = !value
        btnRescan.isEnabled = !value
    }

    private fun toggleAdvanced() {
        val showing = advancedBody.visibility == View.VISIBLE
        advancedBody.visibility = if (showing) View.GONE else View.VISIBLE
        advancedChevron.text = if (showing) "▾" else "▴"
    }

    private fun updateConnectedUi() {
        btnConnect.text = if (connected) "DISCONNECT" else "CONNECT"
        connPill.text = when {
            connected -> "connected"
            probePorts().isNotEmpty() -> "deck found"
            else -> "not connected"
        }
    }

    private fun baudFromPrefs(): Int {
        val parsed = baudInput.text?.toString()?.trim()?.toIntOrNull()
        return if (parsed != null && parsed > 0) parsed else prefs.getInt(KEY_BAUD, DEFAULT_BAUD)
    }

    private fun log(line: String) {
        activity.runOnUiThread {
            if (console.isNotEmpty()) console.append('\n')
            console.append(line)
            if (console.length > MAX_LOG) console.delete(0, console.length - MAX_LOG)
            consoleView.text = console.toString()
        }
    }

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    private fun runIo(block: () -> Unit) {
        io.execute {
            try {
                block()
            } catch (t: Throwable) {
                activity.runOnUiThread { log("Unexpected error: ${t.message}") }
            }
        }
    }

    private fun humanSize(bytes: Long): String = when {
        bytes < 0 -> "unknown"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }

    private companion object {
        const val KEY_BAUD = "flasher_baud"
        const val KEY_VERIFY = "flasher_verify"
        const val KEY_COMPRESS = "flasher_compress"
        const val DEFAULT_BAUD = 115200
        const val MAX_LOG = 20000
    }
}
