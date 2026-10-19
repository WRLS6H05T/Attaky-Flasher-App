package com.attaky.flasher

import android.content.SharedPreferences
import android.net.Uri
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
import androidx.core.widget.doAfterTextChanged
import org.json.JSONArray
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The native offline flasher screen.
 *
 * Reached from the boot chooser's "Downloaded Firmware" tile and from the
 * store's floating escape button. Everything here is local: the deck is driven
 * over USB, firmware comes from the phone's own storage (or files the user
 * picks), and nothing needs the network except the Store link.
 *
 * The port is opened in raw mode ([UsbSerialService.openRaw]), so the WebView's
 * background read loop is off and [EspFlasher] owns the byte stream. All USB
 * work runs on a private worker; the UI is only touched back on the main thread.
 */
class NativeFlasher(
    private val activity: android.app.Activity,
    private val service: UsbSerialService,
    private val downloads: FirmwareDownloads,
    private val prefs: SharedPreferences,
    private val root: View,
    private val pickFiles: (Array<String>, (List<Uri>) -> Unit) -> Unit,
    private val onExit: () -> Unit,
    private val onOpenStore: () -> Unit,
) : UsbSerialService.Listener {

    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "attaky-native-flash").apply { isDaemon = true }
    }

    private val cancelRequested = AtomicBoolean(false)

    private val btnBack: View = root.findViewById(R.id.btnBack)
    private val btnStore: View = root.findViewById(R.id.btnStore)
    private val btnConnect: Button = root.findViewById(R.id.btnConnect)
    private val btnRescan: Button = root.findViewById(R.id.btnRescan)
    private val btnPickFiles: Button = root.findViewById(R.id.btnPickFiles)
    private val btnFlashLocal: Button = root.findViewById(R.id.btnFlashLocal)
    private val connPill: TextView = root.findViewById(R.id.connPill)
    private val chipInfo: TextView = root.findViewById(R.id.chipInfo)
    private val firmwareList: ViewGroup = root.findViewById(R.id.firmwareList)
    private val firmwareEmpty: View = root.findViewById(R.id.firmwareEmpty)
    private val localFilesList: ViewGroup = root.findViewById(R.id.localFiles)
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

    private val localFiles = ArrayList<LocalFile>()

    private val console = StringBuilder()

    @Volatile
    private var connected = false

    @Volatile
    private var busy = false

    private var pendingConnectIndex = -1

    private class LocalFile(val uri: Uri, val name: String, val size: Long) {
        var offset: String = "0x0"
    }

    private class Write(val offset: Long, val label: String, val read: () -> ByteArray)

    private class Port(val index: Int, val granted: Boolean, val name: String)

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        service.nativeListener = this

        btnBack.setOnClickListener { onExit() }
        btnStore.setOnClickListener { onOpenStore() }
        advancedToggle.setOnClickListener { toggleAdvanced() }
        btnConnect.setOnClickListener { onConnectClicked() }
        btnRescan.setOnClickListener { refreshDeckStatus(logLine = true) }
        btnPickFiles.setOnClickListener { chooseFiles() }
        btnFlashLocal.setOnClickListener { flashLocalFiles() }

        baudInput.setText(prefs.getInt(KEY_BAUD, DEFAULT_BAUD).toString())
        checkVerify.isChecked = prefs.getBoolean(KEY_VERIFY, true)
        checkCompress.isChecked = prefs.getBoolean(KEY_COMPRESS, false)

        root.findViewById<TextView>(R.id.flasherVersion).text =
            activity.getString(R.string.splash_version, BuildConfig.VERSION_NAME)

        renderFirmware()
        renderLocalFiles()
        updateConnectedUi()
        log("Ready. Flash a downloaded image, or connect the deck.")
        refreshDeckStatus(logLine = false)
    }

    /** Persist the advanced options and release the port. */
    fun stop() {
        cancelRequested.set(true)
        runCatching {
            prefs.edit()
                .putInt(KEY_BAUD, baudFromPrefs())
                .putBoolean(KEY_VERIFY, checkVerify.isChecked)
                .putBoolean(KEY_COMPRESS, checkCompress.isChecked)
                .apply()
        }
        service.nativeListener = null
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
        openPort(port.index)
    }

    private fun openPort(index: Int) {
        val outcome = service.openRaw(index, baudFromPrefs())
        when (outcome.status) {
            "ok" -> beginSync()
            "permission" -> {
                pendingConnectIndex = index
                log("Waiting for USB permission…")
            }
            else -> {
                log("Could not open the deck: ${outcome.message}")
                toast(outcome.message ?: "Could not open the deck.")
            }
        }
    }

    private fun beginSync() {
        val baud = baudFromPrefs()
        setBusy(true)
        log("Connecting to the chip…")
        runIo {
            try {
                val flasher = EspFlasher(service)
                val info = flasher.connect(baud) { line -> log(line) }
                activity.runOnUiThread {
                    connected = true
                    setBusy(false)
                    updateConnectedUi()
                    chipInfo.visibility = View.VISIBLE
                    chipInfo.text = "${info.name}\nMAC ${info.mac}"
                }
            } catch (t: Throwable) {
                activity.runOnUiThread {
                    setBusy(false)
                    connected = false
                    updateConnectedUi()
                    log("Could not connect: ${t.message}")
                    toast("Could not connect to the deck.")
                    runCatching { service.close() }
                }
            }
        }
    }

    private fun disconnect() {
        runCatching { service.close() }
        connected = false
        updateConnectedUi()
        chipInfo.visibility = View.GONE
        log("Disconnected.")
    }

    override fun onPermissionResult(granted: Boolean) {
        activity.runOnUiThread {
            if (granted) {
                val index = pendingConnectIndex
                pendingConnectIndex = -1
                if (index >= 0) openPort(index)
            } else {
                pendingConnectIndex = -1
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
            row.findViewById<TextView>(R.id.fwMeta).text =
                "${humanSize(entry.size)} · 0x${entry.offset.toString(16)}"
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
            .setMessage("Write ${humanSize(entry.size)} to the deck at 0x${entry.offset.toString(16)}?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Flash") { _, _ -> flashDownloaded(entry) }
            .show()
    }

    private fun flashDownloaded(entry: FirmwareDownloads.Entry) {
        val file = downloads.fileFor(entry.name)
        if (file == null) {
            toast("That file is gone.")
            renderFirmware()
            return
        }
        startFlash(listOf(Write(entry.offset, entry.name) { file.readBytes() }))
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

    // --------------------------------------------------------------- local files

    private fun chooseFiles() {
        if (busy) return
        pickFiles(arrayOf("*/*")) { uris ->
            var added = 0
            for (uri in uris) {
                addLocalFile(uri)
                added++
            }
            if (added > 0) {
                renderLocalFiles()
                log("Added $added file(s).")
            }
        }
    }

    private fun addLocalFile(uri: Uri) {
        var name = uri.lastPathSegment ?: "firmware.bin"
        var size = -1L
        try {
            activity.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        } catch (t: Throwable) {
            log("Could not read $name: ${t.message}")
        }
        localFiles.add(LocalFile(uri, name, size))
    }

    private fun renderLocalFiles() {
        localFilesList.removeAllViews()
        val inflater = LayoutInflater.from(activity)
        for (file in localFiles) {
            val row = inflater.inflate(R.layout.item_localfile, localFilesList, false)
            row.findViewById<TextView>(R.id.lfName).text = file.name
            row.findViewById<TextView>(R.id.lfMeta).text =
                if (file.size >= 0) humanSize(file.size) else "size unknown"
            val offsetField = row.findViewById<EditText>(R.id.lfOffset)
            offsetField.setText(file.offset)
            offsetField.doAfterTextChanged { file.offset = it?.toString().orEmpty() }
            row.findViewById<View>(R.id.lfRemove).setOnClickListener {
                localFiles.remove(file)
                renderLocalFiles()
            }
            localFilesList.addView(row)
        }
        btnFlashLocal.visibility = if (localFiles.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun flashLocalFiles() {
        if (busy) return
        if (!connected) {
            toast("Connect the deck first.")
            return
        }
        if (localFiles.isEmpty()) return

        val writes = ArrayList<Write>()
        for (file in localFiles) {
            val offset = parseOffset(file.offset) ?: run {
                toast("Bad offset for ${file.name}.")
                log("Offset for ${file.name} is not a number: \"${file.offset}\"")
                return
            }
            writes.add(Write(offset, file.name) {
                activity.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
                    ?: throw java.io.IOException("Could not open ${file.name}")
            })
        }
        startFlash(writes)
    }

    // ------------------------------------------------------------------ flashing

    private fun startFlash(writes: List<Write>) {
        val compress = checkCompress.isChecked
        val verify = checkVerify.isChecked
        val baud = baudFromPrefs()

        setBusy(true)
        progressBox.visibility = View.VISIBLE
        progressBar.progress = 0
        progressText.text = "Preparing…"
        log("Flashing ${writes.size} image(s)…")

        runIo {
            try {
                val flasher = EspFlasher(service)
                flasher.connect(baud) { line -> log(line) }
                if (baud != DEFAULT_BAUD) flasher.changeBaud(baud) { line -> log(line) }
                writes.forEachIndexed { index, write ->
                    if (cancelRequested.get()) throw EspFlasher.CancelledException()
                    val bytes = write.read()
                    log("Writing ${write.label} (${humanSize(bytes.size.toLong())}) at 0x${write.offset.toString(16)}…")
                    flasher.writeFirmware(
                        offset = write.offset,
                        image = bytes,
                        compress = compress,
                        verify = verify,
                        log = { line -> log(line) },
                        progress = { percent -> onProgress(index, writes.size, percent) },
                        isCancelled = { cancelRequested.get() },
                    )
                }
                flasher.hardReset()
                activity.runOnUiThread {
                    setBusy(false)
                    progressBox.visibility = View.GONE
                    log("Done. The deck is rebooting.")
                    showFlashDone()
                }
            } catch (t: Throwable) {
                activity.runOnUiThread {
                    setBusy(false)
                    progressBox.visibility = View.GONE
                    if (t is EspFlasher.CancelledException) {
                        log("Flash stopped.")
                    } else {
                        log("Flash failed: ${t.message}")
                        toast("Flash failed: ${t.message}")
                    }
                }
            }
        }
    }

    private fun showFlashDone() {
        AlertDialog.Builder(activity)
            .setTitle("Flash complete")
            .setMessage("Firmware written successfully.")
            .setPositiveButton("OK") { _, _ -> renderFirmware() }
            .show()
    }

    private fun onProgress(index: Int, total: Int, percent: Int) {
        activity.runOnUiThread {
            progressBar.progress = percent
            progressText.text = if (total > 1) {
                "Writing ${index + 1} of $total — $percent%"
            } else {
                "Writing — $percent%"
            }
        }
    }

    // ------------------------------------------------------------------ plumbing

    private fun setBusy(value: Boolean) {
        busy = value
        btnConnect.isEnabled = !value
        btnRescan.isEnabled = !value
        btnPickFiles.isEnabled = !value
        btnFlashLocal.isEnabled = !value
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

    private fun parseOffset(text: String?): Long? {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return 0L
        return try {
            if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
                trimmed.substring(2).toLong(16)
            } else {
                trimmed.toLong()
            }
        } catch (t: Throwable) {
            null
        }
    }

    private companion object {
        const val KEY_BAUD = "flasher_baud"
        const val KEY_VERIFY = "flasher_verify"
        const val KEY_COMPRESS = "flasher_compress"
        const val DEFAULT_BAUD = 115200
        const val MAX_LOG = 20000
    }
}
