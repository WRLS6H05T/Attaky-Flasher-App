package com.attaky.flasher

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Finds the deck's own web server on the local network and sends firmware to its
 * SD card. The HTTP dialect itself lives in [DeckProtocol]; this class adds the
 * address sweep, the threading and the main-thread callbacks.
 *
 * Discovery sweeps the phone's own /24 plus the address the launcher uses in
 * access-point mode, because the deck does not publish a service Android's mDNS
 * browser can enumerate — its responder answers `launcher.local` for desktop
 * browsers only. A host is a deck when it answers the launcher's `/systeminfo`
 * after the web login, which nothing else on a home network does.
 *
 * Every callback is delivered on the main thread.
 */
class DeckWifi {

    private val main = Handler(Looper.getMainLooper())

    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "attaky-deck-wifi").apply { isDaemon = true }
    }

    /** Session cookies by host, so a second upload does not log in again. */
    private val cookies = HashMap<String, String>()

    /** Set when the screen closes: in-flight probes and uploads stop. */
    private val closing = AtomicBoolean(false)

    @Volatile
    private var probes: ExecutorService? = null

    /** The deck found last; probed first on the next scan. */
    @Volatile
    var knownHost: String? = null
        private set

    /** The /24 the last sweep covered, e.g. `192.168.1.x`, for the screen to say so. */
    @Volatile
    var lastSweep: String? = null
        private set

    // -------------------------------------------------------------------- lookup

    /**
     * Find the deck on the local network. [onProgress] reports how many of
     * [total] addresses have been tried; [onDone] gets the deck, or null when
     * nothing answered.
     */
    fun startScan(
        user: String,
        password: String,
        onProgress: (Int, Int) -> Unit,
        onSeen: (String) -> Unit = {},
        onDone: (DeckProtocol.Deck?) -> Unit,
    ) {
        cancel()
        closing.set(false)
        io.execute {
            val prefix = localIpv4()?.substringBeforeLast('.', "")?.takeIf { it.isNotEmpty() }
            lastSweep = prefix?.let { "$it.x" }
            val hosts = candidates(prefix)
            val total = hosts.size
            val found = CopyOnWriteArrayList<DeckProtocol.Deck>()
            val tried = AtomicInteger()
            // Set by the first host that answers the launcher's web UI, whether or
            // not its web login accepts what this phone has stored.
            val seen = AtomicBoolean(false)
            val pool = Executors.newFixedThreadPool(PROBE_THREADS) { runnable ->
                Thread(runnable, "attaky-deck-probe").apply { isDaemon = true }
            }
            probes = pool

            for (host in hosts) {
                pool.execute {
                    if (closing.get()) return@execute
                    val sighting = DeckProtocol.sight(host, user, password)
                    if (sighting != null && seen.compareAndSet(false, true)) {
                        sighting.deck?.let {
                            found.add(it)
                            knownHost = it.host
                        }
                        post { onSeen(sighting.host) }
                        post { onProgress(tried.incrementAndGet(), total) }
                        // One deck is the answer: stop sweeping, it would only
                        // keep the menu waiting on the rest of the subnet.
                        pool.shutdownNow()
                        return@execute
                    }
                    val done = tried.incrementAndGet()
                    if (done % PROGRESS_EVERY == 0 || done == total) {
                        post { onProgress(done, total) }
                    }
                }
            }

            pool.shutdown()
            // Wait for the sweep to actually finish, not for a fixed pause: the
            // deck can be the two-hundredth address tried, and a scan that
            // answered "nothing found" while the deck was still being probed
            // would report a deck that is sitting right there as missing. A hit
            // cancels the rest (shutdownNow above), so a find ends this at once.
            val deadline = System.currentTimeMillis() + SCAN_BUDGET_MS
            while (!closing.get() && System.currentTimeMillis() < deadline) {
                val finished = try {
                    pool.awaitTermination(POLL_MS, TimeUnit.MILLISECONDS)
                } catch (t: InterruptedException) {
                    Thread.currentThread().interrupt()
                    true
                }
                if (finished) break
            }
            pool.shutdownNow()
            probes = null
            if (closing.get()) return@execute
            post { onDone(found.firstOrNull()) }
        }
    }

    /**
     * Try one address the user typed — the deck prints its own on screen, which
     * beats a sweep when the phone's subnet is not where the deck landed (a
     * different router, a guest network, a hotspot with client isolation).
     */
    fun probeAddress(
        address: String,
        user: String,
        password: String,
        onDone: (DeckProtocol.Deck?) -> Unit,
    ) {
        closing.set(false)
        io.execute {
            val host = address.trim()
                .removePrefix("http://")
                .removePrefix("https://")
                .substringBefore('/')
                .trim()
            val deck = if (host.isEmpty()) null else DeckProtocol.probe(host, user, password)
            if (deck != null) knownHost = deck.host
            post { onDone(deck) }
        }
    }

    /** Re-read [deck]'s card space, e.g. after an upload changed it. */
    fun refresh(
        deck: DeckProtocol.Deck,
        user: String,
        password: String,
        onDone: (DeckProtocol.Deck?) -> Unit,
    ) {
        io.execute {
            val fresh = DeckProtocol.probe(deck.host, user, password)
            post { onDone(fresh) }
        }
    }

    /** Abandon a running sweep; an upload already writing is left to finish. */
    fun cancel() {
        probes?.shutdownNow()
        probes = null
    }

    /** The screen is gone: stop everything still talking to the network. */
    fun stop() {
        closing.set(true)
        cancel()
        io.shutdownNow()
    }

    // -------------------------------------------------------------------- upload

    /**
     * Drop [file] on the deck's SD card as [remoteName] in [folder] (`/` is the
     * card's root). [onProgress] is 0..1; [onDone] gets success plus a reason
     * when it failed.
     */
    fun upload(
        deck: DeckProtocol.Deck,
        user: String,
        password: String,
        file: File,
        remoteName: String,
        folder: String,
        onProgress: (Float) -> Unit,
        onDone: (Boolean, String) -> Unit,
    ) {
        io.execute {
            try {
                var cookie = session(deck.host, user, password)
                // Clear any install mode a previous OTA left armed: without this
                // the deck writes this body to flash and refuses it as
                // "Unable to write upload data", which is not the card's fault.
                runCatching { DeckProtocol.resetUploadMode(deck.host, cookie) }
                var response = send(deck.host, cookie, file, remoteName, folder, onProgress)
                if (response.code == 401) {
                    // The deck rebooted or the session expired: log in once more.
                    synchronized(cookies) { cookies.remove(deck.host) }
                    cookie = session(deck.host, user, password)
                    runCatching { DeckProtocol.resetUploadMode(deck.host, cookie) }
                    response = send(deck.host, cookie, file, remoteName, folder, onProgress)
                }

                // The deck's card intermittently refuses a chunk mid-body (the
                // same image succeeds on the next run), which aborts the whole
                // upload and deletes the partial file. Nothing is lost by sending
                // the body again, so it gets a few attempts before it is called a
                // failure — that is the difference between "flaky" and "broken"
                // for the person holding the phone.
                var attempt = 1
                while (DeckProtocol.isStuckWriteMode(response) && attempt < ATTEMPTS) {
                    attempt++
                    try {
                        Thread.sleep(RETRY_PAUSE_MS)
                    } catch (t: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                    if (closing.get()) break
                    runCatching { DeckProtocol.resetUploadMode(deck.host, cookie) }
                    response = send(deck.host, cookie, file, remoteName, folder, onProgress)
                }

                val result = response
                if (result.code in 200..299) {
                    post { onDone(true, result.body.trim()) }
                } else if (DeckProtocol.isStuckWriteMode(result)) {
                    post { onDone(false, CARD_REFUSED) }
                } else {
                    post { onDone(false, DeckProtocol.describe(result)) }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "upload to ${deck.host} failed", t)
                post { onDone(false, t.message ?: t.javaClass.simpleName) }
            }
        }
    }

    // ----------------------------------------------------------------------- OTA

    /**
     * Update the deck's firmware over the network: read [file], describe its app
     * image with [OtaManifest], hand that to the deck's `/OTA` handler, upload the
     * bytes to `/OTAFILE` and restart it into the new app.
     *
     * This is the same install the launcher's own web page performs; the only
     * difference is that the app already knows the address and the login, so the
     * user never types an address into a browser.
     *
     * [onDone]'s detail is [OTA_NO_APP] when the image holds nothing bootable, and
     * otherwise the deck's own words.
     */
    fun ota(
        deck: DeckProtocol.Deck,
        user: String,
        password: String,
        file: File,
        fileName: String,
        onProgress: (Float) -> Unit,
        onDone: (Boolean, String) -> Unit,
    ) {
        io.execute {
            try {
                val image = file.readBytes()
                val manifest = OtaManifest.json(fileName, image)
                if (manifest == null) {
                    post { onDone(false, OTA_NO_APP) }
                    return@execute
                }

                var cookie = session(deck.host, user, password)
                var prepare = DeckProtocol.otaPrepare(deck.host, cookie, file.length(), manifest)
                if (prepare.code == 401) {
                    synchronized(cookies) { cookies.remove(deck.host) }
                    cookie = session(deck.host, user, password)
                    prepare = DeckProtocol.otaPrepare(deck.host, cookie, file.length(), manifest)
                }
                if (prepare.code !in 200..299 || !prepare.body.trim().equals("OK", ignoreCase = true)) {
                    post { onDone(false, DeckProtocol.describe(prepare)) }
                    return@execute
                }

                val sent = DeckProtocol.otaUpload(
                    host = deck.host,
                    cookie = cookie,
                    file = file,
                    fileName = fileName,
                    onProgress = onProgress,
                    cancelled = { closing.get() },
                )
                if (sent.code !in 200..299) {
                    // The deck is still armed for an install that will not happen;
                    // disarm it so the next card upload works.
                    runCatching { DeckProtocol.resetUploadMode(deck.host, cookie) }
                    post { onDone(false, DeckProtocol.describe(sent)) }
                    return@execute
                }

                // A written slot only runs once the deck restarts, which the deck
                // itself says it needs after an install.
                runCatching { DeckProtocol.reboot(deck.host, cookie) }
                post { onDone(true, sent.body.trim()) }
            } catch (t: Throwable) {
                Log.w(TAG, "ota to ${deck.host} failed", t)
                post { onDone(false, t.message ?: t.javaClass.simpleName) }
            }
        }
    }

    private fun send(
        host: String,
        cookie: String,
        file: File,
        remoteName: String,
        folder: String,
        onProgress: (Float) -> Unit,
    ): DeckProtocol.Response = DeckProtocol.upload(
        host = host,
        cookie = cookie,
        file = file,
        remoteName = remoteName,
        folder = folder,
        onProgress = onProgress,
        cancelled = { closing.get() },
    )

    /** Log in and keep the session for [host]. */
    private fun session(host: String, user: String, password: String): String {
        synchronized(cookies) { cookies[host] }?.let { return it }
        val token = DeckProtocol.login(host, user, password)
        synchronized(cookies) { cookies[host] = token }
        return token
    }

    // ------------------------------------------------------------------ addressing

    /** Every address worth trying: last deck, the AP address, the phone's /24. */
    private fun candidates(prefix: String?): List<String> {
        val hosts = LinkedHashSet<String>()
        knownHost?.let { hosts.add(it) }
        hosts.add(AP_HOST)
        if (prefix != null) {
            for (i in 1..254) hosts.add("$prefix.$i")
        }
        return hosts.toList()
    }

    /** The phone's own IPv4 on WiFi, which is the subnet the deck is on. */
    private fun localIpv4(): String? = try {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        interfaces
            .filter { it.isUp && !it.isLoopback }
            // wlan first; with a VPN up, the tunnel must not decide the sweep.
            .sortedByDescending { it.name.startsWith("wlan") }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress.orEmpty() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("127.") && !it.startsWith("169.254.") }
    } catch (t: Throwable) {
        null
    }

    private fun post(block: () -> Unit) {
        main.post(block)
    }

    companion object {
        private const val TAG = "AttakyDeckWifi"

        /** [ota]'s detail when the file holds no app image an OTA slot could run. */
        const val OTA_NO_APP = "no-app-image"

        /** [upload]'s detail when the deck's card refused the write on every attempt. */
        const val CARD_REFUSED = "card-refused"

        /** How many times one image is offered to the deck's card. */
        const val ATTEMPTS = 3

        /** Breathe between attempts: the card, not the app, needs a moment. */
        const val RETRY_PAUSE_MS = 700L

        /** The Launcher's fixed address when the deck runs its own hotspot. */
        const val AP_HOST = "192.168.4.1"

        /** Parallel probes; the whole /24 takes a few seconds this way. */
        const val PROBE_THREADS = 24

        /** Report progress every few addresses instead of on every one. */
        const val PROGRESS_EVERY = 8

        /** A whole /24 at [PROBE_TIMEOUT_MS] each over [PROBE_THREADS] threads. */
        const val SCAN_BUDGET_MS = 45_000L

        /** How often the sweep's completion is re-checked. */
        const val POLL_MS = 200L
    }
}
