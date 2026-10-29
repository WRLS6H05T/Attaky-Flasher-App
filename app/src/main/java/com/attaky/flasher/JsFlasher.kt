package com.attaky.flasher

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * The native flasher's engine: the store's flasher, run off-screen.
 *
 * The native screen used to speak the ROM bootloader itself. This class instead
 * loads `assets/www/native-flasher.html` in a 1 px WebView — the same vendored
 * esptool-js build and the same `AttakyUSB` bridge that store pages flash
 * through — so a flash from the native screen is the store's flash: same reset
 * sequences, same stub loader, same timeouts. Kotlin keeps the UI and feeds the
 * merged image; every byte on the wire is esptool-js.
 *
 * The page reports back through the `AttakyNative` interface ([Callbacks]); the
 * image is pulled chunk by chunk so no multi-megabyte string is ever handed to
 * `evaluateJavascript`.
 *
 * The port stays owned by [UsbSerialService]; [connect] opens it through the
 * bridge exactly as the store page does, which is also how USB permission is
 * requested.
 */
class JsFlasher(
    private val activity: Activity,
    private val service: UsbSerialService,
    container: ViewGroup,
    private val onLog: (String) -> Unit,
    private val onProgress: (Int) -> Unit,
    private val onConnected: (chip: String, mac: String) -> Unit,
    private val onConnectFailed: (String) -> Unit,
    private val onFlashDone: (ok: Boolean, message: String) -> Unit,
) {

    companion object {
        private const val TAG = "AttakyEngine"
        private const val PAGE = "file:///android_asset/www/native-flasher.html"

        /** Bytes of image handed over per bridge call (~256 KB of Base64). */
        private const val CHUNK = 192 * 1024
    }

    /** `window.AttakyUSB` for the engine page — the store's serial bridge. */
    val bridge: SerialBridge

    @Volatile
    private var ready = false

    @Volatile
    private var image: ByteArray? = null

    @Volatile
    private var imagePosition = 0

    @Volatile
    private var portJson = "{}"

    private var pending: (() -> Unit)? = null

    @SuppressLint("SetJavaScriptEnabled")
    private val webView: WebView = WebView(activity).apply {
        setBackgroundColor(Color.TRANSPARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        // A single pixel in the flasher screen: attached and shown, so the page
        // is not treated as hidden (hidden pages get their timers throttled, and
        // the reset and sync sequences depend on millisecond timing), but there
        // is nothing to see or touch.
        layoutParams = ViewGroup.LayoutParams(1, 1)
        isClickable = false
        isFocusable = false
    }

    init {
        bridge = SerialBridge(
            service = service,
            webView = webView,
            siteUrlStore = object : SerialBridge.SiteUrlStore {
                override fun get(): String = ""
                override fun set(url: String) = Unit
            },
            firmwareStore = object : SerialBridge.FirmwareStore {
                override fun listJson(): String = "[]"
                override fun delete(name: String): Boolean = false
                override fun downloadMerged(requestJson: String): String =
                    "error: the engine page cannot download firmware"
            },
            host = object : SerialBridge.Host {
                override fun openNativeFlasher() = Unit
                override fun openStore() = Unit
            },
        )

        // Without this the engine can fail silently and CONNECT would sit on a
        // spinner with nothing to read in the console.
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                val line = message ?: return true
                if (line.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    report("Engine page error: ${line.message()}")
                }
                return true
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                report("Engine page failed to load: ${error?.description ?: "unknown error"}")
            }
        }

        webView.addJavascriptInterface(bridge, "AttakyUSB")
        webView.addJavascriptInterface(Callbacks(), "AttakyNative")
        container.addView(webView)
        webView.loadUrl(PAGE)
    }

    // --------------------------------------------------------------- the session

    /** Open the deck and identify it, leaving the chip in esptool's stub loader. */
    fun connect(index: Int, baud: Int) {
        portJson = portJsonFor(index)
        val request = JSONObject().put("baud", baud).toString()
        whenReady { evaluate("window.__attakyEngine&&window.__attakyEngine.connect(${quote(request)})") }
    }

    /**
     * Write one merged image at [offset] through the connected session.
     *
     * [verify] makes esptool-js read the flash back and compare an MD5; block
     * checksums are verified either way.
     */
    fun flash(offset: Long, bytes: ByteArray, compress: Boolean, verify: Boolean) {
        image = bytes
        imagePosition = 0
        val request = JSONObject()
            .put("offset", offset)
            .put("compress", compress)
            .put("verify", verify)
            .toString()
        whenReady { evaluate("window.__attakyEngine&&window.__attakyEngine.flash(${quote(request)})") }
    }

    /** Drop the session without writing anything (used by DISCONNECT and teardown). */
    fun disconnect() {
        image = null
        imagePosition = 0
        evaluate("window.__attakyEngine&&window.__attakyEngine.disconnect()")
    }

    /** Tear the engine page down; the flasher screen is going away. */
    fun release() {
        runCatching { webView.loadUrl("about:blank") }
        runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
        runCatching { webView.removeJavascriptInterface("AttakyUSB") }
        runCatching { webView.removeJavascriptInterface("AttakyNative") }
        runCatching { webView.destroy() }
        pending = null
    }

    // ------------------------------------------------------------- to the page

    private fun whenReady(action: () -> Unit) {
        activity.runOnUiThread {
            if (ready) action() else pending = action
        }
    }

    /** An engine-side problem, straight into the flasher's console. */
    private fun report(line: String) {
        activity.runOnUiThread { onLog(line) }
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

    /** The bridge's own description of the port, as the page expects it. */
    private fun portJsonFor(index: Int): String = try {
        val array = JSONArray(service.listPortsJson())
        var found: JSONObject? = null
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            if (obj.optInt("index", -1) == index) {
                found = obj
                break
            }
        }
        (found ?: JSONObject().put("index", index)).toString()
    } catch (t: Throwable) {
        Log.w(TAG, "Could not describe the port", t)
        JSONObject().put("index", index).toString()
    }

    private fun quote(json: String): String = "'" + json.replace("\\", "\\\\").replace("'", "\\'") + "'"

    // ------------------------------------------------------------ from the page

    /**
     * `window.AttakyNative`: what the engine page calls back into.
     *
     * Everything here runs on the WebView's JavaScript thread, so anything that
     * touches the screen is posted to the UI thread, and anything that reads the
     * image or the port list just answers synchronously.
     */
    inner class Callbacks {

        @JavascriptInterface
        fun ready() {
            ready = true
            activity.runOnUiThread {
                val action = pending
                pending = null
                action?.invoke()
            }
        }

        @JavascriptInterface
        fun log(line: String) {
            activity.runOnUiThread { onLog(line) }
        }

        @JavascriptInterface
        fun progress(percent: Int) {
            activity.runOnUiThread { onProgress(percent.coerceIn(0, 100)) }
        }

        @JavascriptInterface
        fun connected(json: String) {
            val parsed = try {
                JSONObject(json)
            } catch (t: Throwable) {
                JSONObject()
            }
            val chip = parsed.optString("chip", "").ifBlank { "ESP32" }
            val mac = parsed.optString("mac", "")
            activity.runOnUiThread { onConnected(chip, mac) }
        }

        @JavascriptInterface
        fun failed(where: String, message: String) {
            if (where == "connect") activity.runOnUiThread { onConnectFailed(message) }
        }

        @JavascriptInterface
        fun done(ok: Boolean, where: String, message: String) {
            if (where == "flash") {
                image = null
                activity.runOnUiThread { onFlashDone(ok, message) }
            }
        }

        @JavascriptInterface
        fun portJson(): String = portJson

        @JavascriptInterface
        fun imageSize(): Int = image?.size ?: 0

        @JavascriptInterface
        fun imageReset() {
            imagePosition = 0
        }

        @JavascriptInterface
        fun imageChunk(): String {
            val bytes = image ?: return ""
            if (imagePosition >= bytes.size) return ""
            val end = minOf(bytes.size, imagePosition + CHUNK)
            val chunk = Base64.encodeToString(bytes, imagePosition, end - imagePosition, Base64.NO_WRAP)
            imagePosition = end
            return chunk
        }
    }
}
