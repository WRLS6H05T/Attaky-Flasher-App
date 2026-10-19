package com.attaky.flasher

import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject

/**
 * The `window.AttakyUSB` object the flasher page talks to.
 *
 * Every method here is called synchronously from the WebView's JavaScript
 * bridge thread, so blocking USB work is fine, but anything that needs to reach
 * back into the page (received bytes, permission results, unplug events) is
 * posted to the UI thread and delivered through `window.__attaky`.
 *
 * See assets/www/js/webserial.js for the JavaScript contract.
 */
class SerialBridge(
    private val service: UsbSerialService,
    private val webView: WebView,
    private val siteUrlStore: SiteUrlStore,
    private val firmwareStore: FirmwareStore,
    private val host: Host,
) : UsbSerialService.Listener {

    /** Native navigation the bundled menu (and the store's escape button) can
     *  trigger: leave the WebView for the native screens. */
    interface Host {
        fun openNativeFlasher()
        fun openStore()
    }

    interface SiteUrlStore {
        fun get(): String
        fun set(url: String)
    }

    /** Firmware pulled from the store and kept on the phone (see
     *  [FirmwareDownloads]). Exposed to the bundled page so its catalogue can
     *  list what is available offline. */
    interface FirmwareStore {
        fun listJson(): String
        fun delete(name: String): Boolean
    }

    companion object {
        private const val TAG = "AttakyBridge"
        private const val VERSION = "attaky-bridge/1.0"
    }

    // ------------------------------------------------------- events pushed to JS

    override fun onData(bytes: ByteArray, length: Int) {
        val encoded = Base64.encodeToString(bytes, 0, length, Base64.NO_WRAP)
        evaluate("window.__attaky&&window.__attaky.onData('$encoded')")
    }

    override fun onDisconnected(reason: String) {
        evaluate("window.__attaky&&window.__attaky.onDisconnect(${jsString(reason)})")
    }

    override fun onUsbEvent(kind: String) {
        evaluate("window.__attaky&&window.__attaky.onUsbEvent('$kind')")
    }

    override fun onPermissionResult(granted: Boolean) {
        evaluate("window.__attaky&&window.__attaky.onPermission($granted)")
    }

    private fun evaluate(script: String) {
        webView.post {
            try {
                webView.evaluateJavascript(script, null)
            } catch (t: Throwable) {
                Log.w(TAG, "evaluateJavascript failed", t)
            }
        }
    }

    private fun jsString(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        return "'$escaped'"
    }

    // ------------------------------------------------------- methods called by JS

    @JavascriptInterface
    fun version(): String = VERSION

    @JavascriptInterface
    fun listPorts(): String = service.listPortsJson()

    /** @return "granted", "permission" (dialog pending) or "error: ...". */
    @JavascriptInterface
    fun ensurePermission(index: Int): String = service.ensurePermission(index)

    /**
     * @return JSON: {"status":"ok","info":{...}} |
     *               {"status":"permission"} |
     *               {"status":"error","message":"..."}
     */
    @JavascriptInterface
    fun open(
        index: Int,
        baudRate: Int,
        dataBits: Int,
        stopBits: Int,
        parity: String,
        flowControl: String,
    ): String {
        val outcome = service.open(index, baudRate, dataBits, stopBits, parity, flowControl)
        return JSONObject().apply {
            put("status", outcome.status)
            outcome.message?.let { put("message", it) }
            outcome.info?.let { put("info", it) }
        }.toString()
    }

    @JavascriptInterface
    fun writeB64(data: String): String {
        if (!service.isOpen()) return "Serial port is not open"
        return try {
            val bytes = Base64.decode(data, Base64.DEFAULT)
            service.write(bytes)
            "ok"
        } catch (t: Throwable) {
            "Write failed: ${t.message}"
        }
    }

    /**
     * @param mask bit 0 = DTR present, bit 1 = RTS present, bit 2 = BREAK present.
     */
    @JavascriptInterface
    fun setSignals(mask: Int, dtr: Boolean, rts: Boolean, breakSignal: Boolean): String {
        if (!service.isOpen()) return "Serial port is not open"
        return try {
            service.setSignals(
                dtr = if ((mask and 1) != 0) dtr else null,
                rts = if ((mask and 2) != 0) rts else null,
                breakSignal = if ((mask and 4) != 0) breakSignal else null,
            )
            "ok"
        } catch (t: Throwable) {
            "setSignals failed: ${t.message}"
        }
    }

    @JavascriptInterface
    fun setBaudRate(baudRate: Int): String {
        if (!service.isOpen()) return "Serial port is not open"
        return try {
            service.setBaudRate(baudRate)
            "ok"
        } catch (t: Throwable) {
            "setBaudRate failed: ${t.message}"
        }
    }

    @JavascriptInterface
    fun getSignals(): String = service.getSignalsJson()

    @JavascriptInterface
    fun purgeInput(): String {
        service.purgeInput()
        return "ok"
    }

    @JavascriptInterface
    fun close(): String {
        service.close()
        return "ok"
    }

    // ------------------------------------------------------- site configuration

    @JavascriptInterface
    fun getSiteUrl(): String = siteUrlStore.get()

    @JavascriptInterface
    fun setSiteUrl(url: String): String {
        siteUrlStore.set(url)
        return "ok"
    }

    @JavascriptInterface
    fun clearSiteUrl(): String {
        siteUrlStore.set("")
        return "ok"
    }

    // ------------------------------------------------------- native navigation

    /** Leave the WebView and open the native offline flasher screen. */
    @JavascriptInterface
    fun openNativeFlasher(): String {
        webView.post { host.openNativeFlasher() }
        return "ok"
    }

    /** Leave the WebView and open the live firmware store. */
    @JavascriptInterface
    fun openStore(): String {
        webView.post { host.openStore() }
        return "ok"
    }

    // ------------------------------------------------- downloaded firmware

    /** A firmware download finished; tell the page (if it is the bundled one)
     *  to refresh its catalogue. */
    fun onFirmwareChanged() {
        evaluate(
            "window.__attakyFirmware&&window.__attakyFirmware.onChanged&&" +
                "window.__attakyFirmware.onChanged()",
        )
    }

    /** @return JSON array: [{name,size,offset,added,sourceUrl}, …]. */
    @JavascriptInterface
    fun listFirmware(): String = firmwareStore.listJson()

    /** @return "ok" or "not found". */
    @JavascriptInterface
    fun deleteFirmware(name: String): String =
        if (firmwareStore.delete(name)) "ok" else "not found"
}
