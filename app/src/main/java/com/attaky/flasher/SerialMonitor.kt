package com.attaky.flasher

import android.app.Activity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray

/**
 * The ESP serial console on the main page.
 *
 * A plain serial terminal for whatever ESP board is plugged in: open the port and
 * the deck's boot log streams into the box, and a typed line is sent back. It is
 * deliberately not the flasher — it never resets the chip, never speaks the ROM
 * protocol, and holds the port only while the main page is on screen, so opening
 * the flasher or the store hands the deck straight back.
 *
 * The card hides itself whenever no USB serial device is attached, so the main
 * page looks the same as before on a phone with nothing plugged in.
 *
 * Reads bypass [UsbSerialService]'s WebView read loop (`openRaw`) and use
 * [UsbSerialService.readRaw], so the page's serial bridge is untouched by this.
 */
class SerialMonitor(
    private val activity: Activity,
    private val service: UsbSerialService,
    private val root: View,
) : UsbSerialService.Listener {

    private val card: View = root.findViewById(R.id.serialCard)
    private val pill: TextView = root.findViewById(R.id.serialPill)
    private val status: TextView = root.findViewById(R.id.serialStatus)
    private val console: TextView = root.findViewById(R.id.serialOutput)
    private val scroller: ScrollView = root.findViewById(R.id.serialScroll)
    private val input: EditText = root.findViewById(R.id.serialInput)
    private val sendButton: Button = root.findViewById(R.id.serialSend)
    private val toggleButton: Button = root.findViewById(R.id.serialToggle)
    private val clearButton: Button = root.findViewById(R.id.serialClear)

    /** Everything received, and the lines this side sent. */
    private val log = StringBuilder()

    private var index = -1
    private var portName = ""

    @Volatile
    private var reading = false

    @Volatile
    private var opened = false

    private var reader: Thread? = null

    /** Baud the deck's console runs at; the same one the flasher saves. */
    private val baudRate: Int
        get() = activity.getSharedPreferences(PREFS, 0)
            .getInt(KEY_BAUD, DEFAULT_BAUD)
            .takeIf { it > 0 } ?: DEFAULT_BAUD

    fun start() {
        sendButton.setOnClickListener { sendTypedLine() }
        toggleButton.setOnClickListener {
            if (opened) close("Port closed.") else openPort()
        }
        clearButton.setOnClickListener {
            synchronized(log) { log.setLength(0) }
            render()
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                sendTypedLine()
                true
            } else {
                false
            }
        }
        refresh()
    }

    /** Leaving the main page: give the port back and remove the card. */
    fun stop() {
        close(null)
        card.visibility = View.GONE
    }

    // -------------------------------------------------------------- device / port

    /** Re-scan the bus. Called on attach/detach and whenever the page resumes. */
    fun refresh() {
        val ports = ports()
        if (ports.isEmpty()) {
            if (opened) close("The device was unplugged.")
            card.visibility = View.GONE
            return
        }
        card.visibility = View.VISIBLE
        val first = ports.first()
        index = first.first
        portName = first.second
        if (opened && !service.isOpen()) {
            // The port went away behind our back (unplug, or the flasher took it).
            opened = false
            reading = false
        }
        updateUi()
    }

    private fun ports(): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        try {
            val array = JSONArray(service.listPortsJson())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                out.add(obj.optInt("index", i) to obj.optString("name", "USB serial device"))
            }
        } catch (ignored: Throwable) {
            // A probe failure just means "nothing attached".
        }
        return out
    }

    private fun openPort() {
        val target = if (index >= 0) index else ports().firstOrNull()?.first ?: return
        val outcome = service.openRaw(target, baudRate)
        when (outcome.status) {
            "ok" -> {
                opened = true
                append("\n— listening on $portName at $baudRate —\n")
                startReader()
            }
            "permission" -> status.text = "Waiting for USB permission…"
            else -> append("\n— could not open: ${outcome.message ?: outcome.status} —\n")
        }
        updateUi()
    }

    private fun startReader() {
        if (reading) return
        reading = true
        val thread = Thread({
            val buffer = ByteArray(1024)
            while (reading) {
                try {
                    val read = service.readRaw(buffer, READ_TIMEOUT_MS)
                    if (read > 0) append(String(buffer, 0, read, Charsets.UTF_8))
                } catch (t: Throwable) {
                    if (reading) {
                        reading = false
                        activity.runOnUiThread { close("Disconnected: ${t.message ?: t}") }
                    }
                    break
                }
            }
        }, "attaky-serial-monitor")
        thread.isDaemon = true
        thread.start()
        reader = thread
    }

    private fun close(reason: String?) {
        val wasOpen = opened || service.isOpen()
        reading = false
        reader = null
        opened = false
        if (wasOpen) runCatching { service.close() }
        if (reason != null && wasOpen) append("\n— $reason —\n")
        updateUi()
    }

    // ---------------------------------------------------------------- send / show

    private fun sendTypedLine() {
        if (!opened) {
            append("\n— open the port first —\n")
            return
        }
        val line = input.text?.toString().orEmpty()
        try {
            service.write((line + "\r\n").toByteArray(Charsets.UTF_8))
            append("> $line\n")
            input.setText("")
        } catch (t: Throwable) {
            append("\n— send failed: ${t.message} —\n")
        }
    }

    private fun append(chunk: String) {
        if (chunk.isEmpty()) return
        synchronized(log) {
            log.append(chunk)
            if (log.length > MAX_LOG) log.delete(0, log.length - MAX_LOG)
        }
        activity.runOnUiThread { render() }
    }

    private fun render() {
        console.text = synchronized(log) { log.toString() }
        scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
    }

    private fun updateUi() {
        val attached = ports().isNotEmpty()
        pill.text = if (opened) "open · $baudRate" else "closed"
        status.text = when {
            !attached -> "No USB serial device attached."
            opened -> "$portName — listening."
            else -> "$portName is attached. Open the port to watch its output."
        }
        toggleButton.text = activity.getString(
            if (opened) R.string.serial_close else R.string.serial_open,
        )
        sendButton.isEnabled = opened
        input.isEnabled = opened
        card.visibility = if (attached) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------- USB lifecycle events

    override fun onData(bytes: ByteArray, length: Int) = Unit

    override fun onDisconnected(reason: String) {
        activity.runOnUiThread { if (opened) close("Disconnected: $reason") }
    }

    override fun onUsbEvent(kind: String) {
        activity.runOnUiThread { refresh() }
    }

    override fun onPermissionResult(granted: Boolean) {
        activity.runOnUiThread {
            if (granted && !opened) openPort() else if (!granted) status.text = "USB permission denied."
        }
    }

    private companion object {
        const val PREFS = "attaky_flasher"
        const val KEY_BAUD = "flasher_baud"
        const val DEFAULT_BAUD = 115200
        const val READ_TIMEOUT_MS = 200
        const val MAX_LOG = 16000
    }
}
