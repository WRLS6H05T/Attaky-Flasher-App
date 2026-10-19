package com.attaky.flasher

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebResourceErrorCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewFeature
import java.net.URLDecoder

/**
 * The boot flow, then a single full-screen WebView.
 *
 * Launching the app plays a branded splash, then asks where the firmware should
 * come from this session: the live store or the offline page bundled in the
 * APK. Only then does the WebView own the screen.
 *
 * The app fronts the live firmware store (https://fwstore.attaky.com/en/) whose
 * pages already run a "Flash Online" flow over `navigator.serial`. The store
 * pages are just web pages, so this activity injects the Web Serial polyfill
 * (`assets/www/js/webserial.js`) into them; the polyfill and the [SerialBridge]
 * (`window.AttakyUSB`) are what connect the store's JS to Android's USB host
 * stack. The offline page in `assets/www` is the no-network fallback and a home
 * for developer images. Which page opens first is decided by the site URL sent
 * through `AttakyUSB.setSiteUrl()`: empty = store, "bundled:" = local page,
 * http(s) = custom hosted copy.
 */
class MainActivity : AppCompatActivity() {

    /** Where the app is in its own boot sequence. */
    private enum class Stage {
        /** Branded splash, on screen briefly. */
        SPLASH,

        /** Firmware source chooser. */
        CHOICE,

        /** The native offline flasher owns the screen. */
        FLASHER,

        /** The flasher page is live and the WebView owns the screen. */
        APP,
    }

    companion object {
        private const val TAG = "AttakyFlasher"
        private const val PREFS = "attaky_flasher"
        private const val KEY_SITE_URL = "site_url"

        /** Bundled page. WebViewAssetLoader maps /assets/ to app/src/main/assets. */
        private const val LOCAL_PAGE =
            "https://appassets.androidplatform.net/assets/www/index.html"

        private const val ASSET_HOST = "appassets.androidplatform.net"

        /** The real firmware store the app fronts. It is itself a web flasher —
         *  each firmware detail page ships a "Flash Online" flow driven by
         *  esptool-js over `navigator.serial`, which this app provides through
         *  the injected Web Serial polyfill. */
        private const val DEFAULT_STORE_URL = "https://fwstore.attaky.com/en/"
        private const val STORE_HOST = "fwstore.attaky.com"

        /** Sentinel siteUrl: always open the bundled offline flasher page. */
        private const val HOME_BUNDLED = "bundled:"

        /** How long the branded splash stays up before the next screen. */
        private const val SPLASH_MS = 1400L

        /** Escape hatch on the store pages: leave the web view for the native
         *  flasher, which runs its own ROM-bootloader engine offline. In the
         *  store's own sticky header the native flasher takes the right-hand slot
         *  and the site's menu toggle slides to the middle of the bar; both wear
         *  the same shape and hairline border as the native STORE button. A header
         *  without a menu toggle just falls back to the top-right corner. */
        private const val STORE_ESCAPE_JS = """
            ;(function () {
              if (document.getElementById('attaky-store-escape')) return;
              var b = document.createElement('button');
              b.id = 'attaky-store-escape';
              b.type = 'button';
              b.textContent = '${'\u26a1'} Native flasher';
              b.style.cssText = 'position:fixed;top:12px;right:12px;z-index:2147483647;' +
                'background:#FFFFFF;color:#141414;border:1px solid #E5E5E5;border-radius:10px;' +
                'padding:10px 16px;font:600 12px system-ui;text-transform:uppercase;' +
                'letter-spacing:.05em;white-space:nowrap;';
              b.onclick = function () {
                try {
                  if (window.AttakyUSB && window.AttakyUSB.openNativeFlasher) {
                    window.AttakyUSB.openNativeFlasher();
                    return;
                  }
                } catch (e) {}
                window.location.href = '$LOCAL_PAGE';
              };
              document.body.appendChild(b);
              try {
                var bh = b.getBoundingClientRect().height;
                var tog = document.querySelector('.navbar-toggler, [aria-label*="navigation" i]');
                var tb = tog ? tog.getBoundingClientRect() : null;
                if (tb && tb.width > 0 && tb.height > 0) {
                  // The store's own menu button wears the same shape and hairline
                  // border as the native STORE button: white, 1px #E5E5E5, 10px
                  // corners, ink bars, and the same height and side padding.
                  tog.style.boxSizing = 'border-box';
                  tog.style.background = '#FFFFFF';
                  tog.style.border = '1px solid #E5E5E5';
                  tog.style.borderRadius = '10px';
                  tog.style.color = '#141414';
                  tog.style.height = Math.round(bh) + 'px';
                  tog.style.padding = '0 16px';
                  tog.style.display = 'inline-flex';
                  tog.style.alignItems = 'center';
                  tog.style.justifyContent = 'center';
                  tb = tog.getBoundingClientRect();
                  // The native flasher takes the right-hand slot of the bar ...
                  b.style.top = Math.round(tb.top + (tb.height - bh) / 2) + 'px';
                  b.style.right = Math.round(window.innerWidth - tb.right) + 'px';
                  // ... and the store's menu button slides to the middle of the bar.
                  var brand = document.querySelector('.navbar-brand');
                  if (brand) {
                    var mid = (brand.getBoundingClientRect().right + b.getBoundingClientRect().left) / 2;
                    tog.style.transform = 'translateX(' + Math.round(mid - (tb.left + tb.width / 2)) + 'px)';
                  }
                } else {
                  var hdr = document.querySelector('header, nav, [role="banner"]');
                  var hb = hdr ? hdr.getBoundingClientRect() : null;
                  if (hb && hb.height > 20 && hb.height < window.innerHeight / 2 && hb.bottom > 0) {
                    b.style.top = Math.round(hb.bottom + 8) + 'px';
                    b.style.right = '12px';
                  }
                }
              } catch (e) {}
            })();
        """

        /** Content-Disposition `filename` / `filename*` parameter. */
        private val FILENAME_RE =
            Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)""", RegexOption.IGNORE_CASE)
    }

    private lateinit var webView: WebView
    private lateinit var bootHost: FrameLayout
    private lateinit var service: UsbSerialService
    private lateinit var bridge: SerialBridge
    private lateinit var assetLoader: WebViewAssetLoader
    private lateinit var downloads: FirmwareDownloads

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /** Where the boot sequence is; the WebView is only trusted with pages in
     *  [Stage.APP], so boot screens never get the flasher polyfill. */
    private var stage = Stage.SPLASH

    /** Cached copy of the Web Serial polyfill injected into store pages. */
    private var polyfillJs: String? = null

    /** Only fall back to the bundled page on the first store load failure. */
    private var storeFallbackDone = false

    /** True once the current page has booted (polyfill/USB events can be delivered). */
    private var pageLoaded = false

    /** A USB_DEVICE_ATTACHED launch arrived before the current page was ready. */
    private var pendingUsbAttach = false

    private var pendingFileChooser: ValueCallback<Array<Uri>>? = null

    /** The native "Downloaded firmware" flasher, while it owns the screen. */
    private var nativeFlasher: NativeFlasher? = null

    /** Where back lands when the native flasher closes. */
    private var flasherReturnStage = Stage.CHOICE

    private var pendingBinCallback: ((List<Uri>) -> Unit)? = null

    /** Local .bin picker for the native flasher (separate from the WebView one). */
    private val binLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            val callback = pendingBinCallback
            pendingBinCallback = null
            callback?.invoke(uris ?: emptyList())
        }

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = pendingFileChooser ?: return@registerForActivityResult
            pendingFileChooser = null
            if (result.resultCode != RESULT_OK) {
                callback.onReceiveValue(null)
                return@registerForActivityResult
            }
            val data = result.data
            if (data == null) {
                callback.onReceiveValue(null)
                return@registerForActivityResult
            }
            val clips = data.clipData
            val uris = if (clips != null && clips.itemCount > 0) {
                Array(clips.itemCount) { i -> clips.getItemAt(i).uri }
            } else {
                data.data?.let { arrayOf(it) }
            }
            callback.onReceiveValue(uris)
        }

    private val prefsStore = object : SerialBridge.SiteUrlStore {
        override fun get(): String = prefs.getString(KEY_SITE_URL, "").orEmpty()

        override fun set(url: String) {
            prefs.edit().putString(KEY_SITE_URL, url).apply()
        }
    }

    // ------------------------------------------------------------------- setup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bootHost = findViewById(R.id.bootHost)

        // Flashing takes a minute or two; do not let the screen sleep mid-write.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.BLACK)
        }
        findViewById<FrameLayout>(R.id.webHost).addView(webView)

        downloads = FirmwareDownloads(this)

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/firmware/", FirmwarePathHandler(downloads))
            .build()

        configureWebView()

        service = UsbSerialService(this)
        bridge = SerialBridge(
            service,
            webView,
            prefsStore,
            downloads,
            object : SerialBridge.Host {
                override fun openNativeFlasher() = this@MainActivity.openNativeFlasher(fromChooser = false)
                override fun openStore() = this@MainActivity.openStore()
            },
        )
        service.listener = bridge

        // Must be registered before the first page load.
        webView.addJavascriptInterface(bridge, "AttakyUSB")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (stage) {
                    // Back during the splash simply skips it.
                    Stage.SPLASH -> afterSplash()
                    Stage.FLASHER -> closeNativeFlasher()
                    Stage.APP ->
                        if (webView.canGoBack()) {
                            webView.goBack()
                        } else {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        }
                    Stage.CHOICE -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        service.start()

        if (savedInstanceState == null) {
            showSplash()
        } else {
            // Recreated (process death) — replaying the splash would be noise.
            afterSplash()
        }

        // Cold start by plugging the deck in: the attach intent only reaches
        // onNewIntent when the activity is already alive.
        handleUsbIntent(intent)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            // The page lives on an https:// virtual origin; local files are not needed.
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        // Without a WebViewClient the WebView refuses to render anything itself:
        // http(s) URLs are handed to the external browser and the
        // appassets.androidplatform.net URLs below would go out to the network.
        webView.webViewClient = object : WebViewClientCompat() {

            // Serve assets/www/… from the APK for the bundled offline page.
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                pageLoaded = false
                // A new load may fail again, so allow one fresh fallback per load
                // (without this the first offline hit would disable it for good).
                // Never reset it for the bundled page — that is where the fallback
                // lands, and a failing asset there must not bounce us around.
                if (url != null && isHttpUrl(url) && !isLocalUrl(url)) storeFallbackDone = false
            }

            override fun onPageFinished(view: WebView, url: String?) {
                pageLoaded = true
                when (stage) {
                    Stage.APP -> {
                        // Never re-inject into the bundled page (it ships its own
                        // polyfill).
                        if (url != null && !isLocalUrl(url)) injectSerialBridge()
                        deliverUsbAttach()
                    }
                    Stage.SPLASH, Stage.CHOICE, Stage.FLASHER -> Unit
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceErrorCompat,
            ) {
                if (!request.isForMainFrame) return
                if (storeFallbackDone) return
                val failing = request.url.toString()
                if (!isHttpUrl(failing) || isLocalUrl(failing) || !isSiteUrl(failing)) return

                // The accessors only exist when the WebView supports them
                // (same check androidx itself makes); without them, and for
                // errors Chromium cannot classify, treat the site as unreachable.
                val code = if (
                    WebViewFeature.isFeatureSupported(WebViewFeature.WEB_RESOURCE_ERROR_GET_CODE)
                ) {
                    error.errorCode
                } else {
                    WebViewClient.ERROR_UNKNOWN
                }
                val unreachable =
                    code == WebViewClient.ERROR_UNKNOWN ||
                        code == WebViewClient.ERROR_HOST_LOOKUP ||
                        code == WebViewClient.ERROR_CONNECT ||
                        code == WebViewClient.ERROR_TIMEOUT ||
                        code == WebViewClient.ERROR_IO
                if (!unreachable) return

                val reason = if (
                    WebViewFeature.isFeatureSupported(
                        WebViewFeature.WEB_RESOURCE_ERROR_GET_DESCRIPTION,
                    )
                ) {
                    "code $code: ${error.description}"
                } else {
                    "code $code"
                }
                fallbackToBundled(reason)
            }

            /** API 21/22: only the legacy callback exists. */
            @Suppress("DEPRECATION")
            override fun onReceivedError(
                view: WebView,
                errorCode: Int,
                description: String?,
                failingUrl: String?,
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) return
                if (storeFallbackDone || failingUrl == null) return
                if (!isHttpUrl(failingUrl) || isLocalUrl(failingUrl)) return
                if (!isSiteUrl(failingUrl)) return
                fallbackToBundled("$errorCode $description")
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: WebChromeClient.FileChooserParams?,
            ): Boolean {
                if (filePathCallback == null) return false
                pendingFileChooser = filePathCallback
                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                if (fileChooserParams?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                try {
                    fileChooserLauncher.launch(intent)
                } catch (t: Throwable) {
                    pendingFileChooser = null
                    filePathCallback.onReceiveValue(null)
                    return false
                }
                return true
            }
        }

        // The store serves firmware as plain file downloads. Catch them and keep
        // the bytes on the phone instead of handing the user off to a browser.
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            handleDownload(url, userAgent, contentDisposition, mimeType)
        }
    }

    // --------------------------------------------------------------- boot flow

    /** Splash: the lockup rises in, then the next screen takes over. */
    private fun showSplash() {
        stage = Stage.SPLASH
        val view = setBootScreen(R.layout.view_splash)
        setVersion(view)

        val lockup = view.findViewById<View>(R.id.splashLockup)
        lockup.alpha = 0f
        lockup.translationY = 10f * resources.displayMetrics.density
        lockup.animate().alpha(1f).translationY(0f).setDuration(500L).start()

        bootHost.postDelayed({
            if (stage == Stage.SPLASH) afterSplash()
        }, SPLASH_MS)
    }

    private fun afterSplash() {
        showChoice()
    }

    /** Every launch asks; the answer is a session choice, never stored. */
    private fun showChoice() {
        stage = Stage.CHOICE
        val view = setBootScreen(R.layout.view_choice)
        setVersion(view)

        view.findViewById<View>(R.id.tileStore).setOnClickListener {
            startApp(storeUrl())
        }
        view.findViewById<View>(R.id.tileBundled).setOnClickListener {
            openNativeFlasher(fromChooser = true)
        }
    }

    private fun startApp(url: String) {
        stage = Stage.APP
        storeFallbackDone = false
        // Emptying the overlay reveals the WebView with the flasher page.
        bootHost.removeAllViews()
        webView.loadUrl(url)
    }

    /** Inflate a boot screen into the overlay, replacing whatever was there. */
    private fun setBootScreen(layoutRes: Int): View {
        val view = layoutInflater.inflate(layoutRes, bootHost, false)
        bootHost.removeAllViews()
        bootHost.addView(view)
        return view
    }

    private fun setVersion(root: View) {
        root.findViewById<TextView>(R.id.bootVersion)?.text =
            getString(R.string.splash_version, BuildConfig.VERSION_NAME)
    }

    // ------------------------------------------------------------ native flasher

    /**
     * Open the fully native "Downloaded firmware" screen. Arriving from the boot
     * chooser with nothing downloaded shows the empty-state dialog instead, so
     * the user is not dropped into a dead end (they can still choose local .bin
     * files from there).
     */
    private fun openNativeFlasher(fromChooser: Boolean) {
        if (stage == Stage.FLASHER) return

        if (fromChooser && downloads.entries().isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.no_firmware_title)
                .setMessage(R.string.no_firmware_message)
                .setNegativeButton(R.string.no_firmware_choose) { _, _ ->
                    openNativeFlasher(fromChooser = false)
                }
                .setPositiveButton(android.R.string.ok) { _, _ -> showChoice() }
                .show()
            return
        }

        flasherReturnStage = if (stage == Stage.APP) Stage.APP else Stage.CHOICE
        stage = Stage.FLASHER

        val view = layoutInflater.inflate(R.layout.view_flasher, bootHost, false)
        bootHost.removeAllViews()
        bootHost.addView(view)

        val controller = NativeFlasher(
            activity = this,
            service = service,
            downloads = downloads,
            prefs = prefs,
            root = view,
            pickFiles = ::pickBinFiles,
            onExit = { closeNativeFlasher() },
            onOpenStore = { openStore() },
        )
        nativeFlasher = controller
        controller.start()
    }

    /** Tear the native screen down and reveal what was behind it. */
    private fun closeNativeFlasher() {
        val controller = nativeFlasher ?: return
        nativeFlasher = null
        controller.stop()
        bootHost.removeAllViews()
        if (flasherReturnStage == Stage.APP) {
            stage = Stage.APP
        } else {
            showChoice()
        }
    }

    /** Load the live store, leaving the native flasher if it was open. */
    private fun openStore() {
        nativeFlasher?.stop()
        nativeFlasher = null
        startApp(storeUrl())
    }

    /** Local .bin picker for the native screen, via the system documents UI. */
    private fun pickBinFiles(mimeTypes: Array<String>, callback: (List<Uri>) -> Unit) {
        pendingBinCallback = callback
        binLauncher.launch(mimeTypes)
    }

    // ------------------------------------------------------------------ routing

    /**
     * The store URL for an explicit "Firmware Store" choice: a custom http(s)
     * home from Settings wins, otherwise the live store. A pinned "bundled:"
     * home is ignored here — the user has just asked for the store.
     */
    private fun storeUrl(): String {
        val configured = prefsStore.get()
        return if (configured.isNotEmpty() && configured != HOME_BUNDLED && isHttpUrl(configured)) {
            configured
        } else {
            DEFAULT_STORE_URL
        }
    }

    /** The bundled page (always the APK, never the network). */
    private fun isLocalUrl(url: String): Boolean = url.startsWith("https://$ASSET_HOST/")

    /** True for the pages the app was asked to show: the firmware store, or the
     *  hosted copy of the flasher configured in Settings. Third-party links the
     *  user followed on their own must not trigger the offline fallback. */
    private fun isSiteUrl(url: String): Boolean {
        val host = Uri.parse(url).host ?: return false
        if (host == STORE_HOST) return true
        val configured = prefsStore.get()
        return configured.isNotEmpty() && Uri.parse(configured).host == host
    }

    /** The site could not be reached at all — open the offline flasher once. */
    private fun fallbackToBundled(reason: String) {
        if (storeFallbackDone) return
        storeFallbackDone = true
        Log.w(TAG, "site load failed ($reason) — falling back to the bundled flasher")
        webView.postDelayed({
            if (!isFinishing && !isDestroyed) webView.loadUrl(LOCAL_PAGE)
        }, 250)
    }

    /** Load the polyfill + escape hatch into the current (store) page. */
    private fun injectSerialBridge() {
        val polyfill = polyfillJs ?: assets.open("www/js/webserial.js")
            .bufferedReader()
            .use { it.readText() }
            .also { polyfillJs = it }
        // A hosted copy of the flasher page already ships webserial.js; running it
        // twice would drop the handlers app.js wrapped on window.__attaky.
        webView.evaluateJavascript(
            "if (!window.attakySerial) {\n$polyfill\n}\n$STORE_ESCAPE_JS",
            null,
        )
    }

    private fun isHttpUrl(url: String): Boolean =
        url.startsWith("http://") || url.startsWith("https://")

    // ------------------------------------------------------- store firmware saves

    /**
     * A download the WebView could not render. Firmware images from the store are
     * saved to app storage so the bundled page can flash them offline; anything
     * else is ignored (the app has no business downloading random files).
     */
    private fun handleDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ) {
        if (!isHttpUrl(url)) return
        val name = downloadFileName(contentDisposition, url)
        if (!isFirmwareDownload(name, mimeType, url)) return

        // Reuse the WebView session so signed-in store downloads are allowed.
        val cookie = CookieManager.getInstance().getCookie(url)
        Toast.makeText(this, getString(R.string.download_started, name), Toast.LENGTH_SHORT).show()
        downloads.download(url, name, cookie, userAgent) { ok, detail ->
            if (ok) {
                Toast.makeText(this, getString(R.string.download_saved, detail), Toast.LENGTH_SHORT).show()
                // If the flasher page is up, let it refresh the offline list.
                if (stage == Stage.APP) bridge.onFirmwareChanged()
            } else {
                Toast.makeText(this, getString(R.string.download_failed, detail), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun isFirmwareDownload(name: String, mimeType: String?, url: String): Boolean {
        if (FirmwareDownloads.looksLikeFirmware(name)) return true
        if (mimeType == "application/octet-stream") return true
        return url.contains("/firmware/", ignoreCase = true)
    }

    /** A safe file name from the Content-Disposition header, else the URL path. */
    private fun downloadFileName(contentDisposition: String?, url: String): String {
        if (!contentDisposition.isNullOrEmpty()) {
            val match = FILENAME_RE.find(contentDisposition)
            if (match != null) {
                val raw = match.groupValues[1].trim().trim('"')
                if (raw.isNotEmpty()) return sanitizeFileName(decode(raw))
            }
        }
        val segment = Uri.parse(url).lastPathSegment ?: "firmware.bin"
        return sanitizeFileName(decode(segment))
    }

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (t: Throwable) {
        value
    }

    /** Keep the name a plain file name: no separators, no traversal, sane length. */
    private fun sanitizeFileName(raw: String): String {
        var name = raw.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_").trim()
        name = name.trimStart('.')
        if (name.isEmpty()) name = "firmware.bin"
        if (name.length > 120) name = name.take(120)
        if (name.substringAfterLast('.', "").isEmpty()) name += ".bin"
        return name
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbIntent(intent)
    }

    private fun handleUsbIntent(intent: Intent?) {
        // Launched because a known USB-serial device was plugged in. The system
        // hands us permission for that exact device, so tell the page to re-scan
        // once it has finished booting.
        if (intent?.action != android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        pendingUsbAttach = true
        deliverUsbAttach()
    }

    /** Queue the attach event for the page; called again once a load finishes. */
    private fun deliverUsbAttach() {
        // The flasher page has to be the one on screen — during the boot screens
        // the attach stays queued and is delivered when the page comes up.
        if (stage != Stage.APP) return
        if (!pendingUsbAttach || !pageLoaded) return
        pendingUsbAttach = false
        webView.postDelayed({
            bridge.onUsbEvent("attached")
        }, 800)
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the system USB-permission dialog: refresh the scan.
        if (stage == Stage.APP) bridge.onUsbEvent("resume")
    }

    override fun onDestroy() {
        nativeFlasher?.stop()
        nativeFlasher = null
        service.stop()
        webView.removeJavascriptInterface("AttakyUSB")
        webView.destroy()
        super.onDestroy()
    }
}
