package com.attaky.flasher

import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * The deck's HTTP dialect: the web login, the version/card info and the
 * multipart upload, written against plain `java.net` and `org.json` so the exact
 * bytes the app sends can be exercised on the JVM against a fake launcher (see
 * `DeckProtocolTest`) as well as on the phone. [DeckWifi] adds the address sweep
 * and the threading.
 *
 * The launcher firmware (`Attaky_bm_launcher_…bin`, a bmorcelli Launcher port)
 * serves this from `esp_http_server` while its **Start Web User Interface** menu
 * entry is running:
 *
 *  - `GET /systeminfo` — `{"VERSION": …, "SD": {"free": …, "total": …}, "APPS": […]}`
 *  - `POST /login` — form-encoded `username`/`password`; answers `302` with
 *    `Set-Cookie: ESP32SESSION=…`, or `302 /?failed` without one.
 *  - `POST /` — `multipart/form-data`: a `folder` field, then one file part. The
 *    deck streams the file straight onto the SD card and answers `OK`. A body
 *    that is cut short makes it delete the partial file, so an aborted transfer
 *    never leaves a file the deck would try to flash.
 *  - `POST /OTA` — form-encoded `command`, `size` and `manifest`: the deck reads
 *    the manifest ([OtaManifest]), resizes its app slot for it and answers `OK`,
 *    after which the next `/OTAFILE` body is written into flash instead of onto
 *    the card.
 *  - `POST /OTAFILE` — the multipart body itself; one part whose bytes hold the
 *    ranges the manifest names. The deck finalises the install and says it
 *    needs a restart.
 *  - `GET /reboot` — boots into the freshly written app.
 *
 * Everything except the login is behind the session cookie and answers
 * `401 Unauthorized` without it. The shipped login is `admin` / `launcher`.
 */
object DeckProtocol {

    data class Deck(
        val host: String,
        val version: String,
        /** Human-readable free space on the deck's card, e.g. "3.2 GB". */
        val sdFree: String,
        val sdTotal: String,
        val sdFreeBytes: Long,
    )

    data class Response(
        val code: Int,
        val body: String,
        val headers: Map<String, List<String>> = emptyMap(),
    )

    /** The deck answered but refused the web login. */
    class LoginFailed : IOException("The core refused the web login.")

    const val SESSION_PREFIX = "ESP32SESSION="

    /** Short, for the /24 sweep: an address that does not answer is not a deck. */
    const val PROBE_TIMEOUT_MS = 700

    const val CONNECT_TIMEOUT_MS = 4_000

    /** A merged image on a slow card takes a while; the deck writes per chunk. */
    const val UPLOAD_TIMEOUT_MS = 180_000

    /** `/OTA` resizes partitions before it answers; it is not instant. */
    const val PREPARE_TIMEOUT_MS = 30_000

    private const val BUFFER = 64 * 1024

    // ------------------------------------------------------------------ identity

    /**
     * A host that answered the launcher's web UI. [deck] is null when it answered
     * but the web login was refused — a deck whose password was changed on the
     * device — so a caller can still tell that something is there.
     */
    data class Sighting(val host: String, val deck: Deck?)

    /**
     * [host] is a deck when `/systeminfo` answers with a version. A `401` first is
     * the normal case — the endpoint is behind the web login — and only that 401
     * earns a credential, so other devices on the network never see one.
     */
    fun probe(host: String, user: String, password: String): Deck? =
        sight(host, user, password)?.deck

    /** [probe], keeping the host when the deck answered but would not log in. */
    fun sight(host: String, user: String, password: String): Sighting? {
        val plain = try {
            systemInfo(host, cookie = null)
        } catch (t: Throwable) {
            return null
        }
        if (plain.code == 200) {
            val deck = deckFrom(host, plain.body) ?: return null
            return Sighting(host, deck)
        }
        if (plain.code != 401 || !plain.body.contains("Unauthorized")) return null

        // Past the 401 this is known to be an httpd, so the login and the read
        // that follows get room to answer: the sweep's short timeout is for
        // hosts that are not there, not for a deck busy writing its screen.
        val deck = try {
            val cookie = login(host, user, password)
            val signed = systemInfo(host, cookie, timeoutMs = CONNECT_TIMEOUT_MS)
            if (signed.code == 200) deckFrom(host, signed.body) else null
        } catch (t: Throwable) {
            null
        }
        return Sighting(host, deck)
    }

    fun deckFrom(host: String, body: String): Deck? {
        val json = try {
            JSONObject(body)
        } catch (t: Throwable) {
            return null
        }
        val version = json.optString("VERSION")
        if (version.isEmpty()) return null
        val sd = json.optJSONObject("SD")
        return Deck(
            host = host,
            version = version,
            sdFree = sd?.optString("free").orEmpty(),
            sdTotal = sd?.optString("total").orEmpty(),
            sdFreeBytes = sd?.optDouble("freeBytes", 0.0)?.toLong() ?: 0L,
        )
    }

    // ---------------------------------------------------------------------- auth

    fun login(
        host: String,
        user: String,
        password: String,
        timeoutMs: Int = CONNECT_TIMEOUT_MS,
    ): String {
        val body = (
            "username=" + URLEncoder.encode(user, "UTF-8") +
                "&password=" + URLEncoder.encode(password, "UTF-8")
            ).toByteArray(Charsets.UTF_8)
        val response = request(
            host,
            "/login",
            method = "POST",
            body = body,
            contentType = "application/x-www-form-urlencoded",
            timeoutMs = timeoutMs,
        )
        return tokenFrom(response.headers) ?: throw LoginFailed()
    }

    /** The `ESP32SESSION` token a successful login sets. */
    fun tokenFrom(headers: Map<String, List<String>>): String? {
        for (value in header(headers, "Set-Cookie")) {
            val at = value.indexOf(SESSION_PREFIX)
            if (at < 0) continue
            val token = value.substring(at + SESSION_PREFIX.length).substringBefore(';').trim()
            if (token.isNotEmpty() && token != "0") return token
        }
        return null
    }

    fun systemInfo(
        host: String,
        cookie: String?,
        timeoutMs: Int = PROBE_TIMEOUT_MS,
    ): Response = request(host, "/systeminfo", cookie = cookie, timeoutMs = timeoutMs)

    // -------------------------------------------------------------------- upload

    /**
     * Send [file] to [folder] on the deck's card as [remoteName]. [onProgress] is
     * 0..1; returning true from [cancelled] aborts between chunks.
     */
    fun upload(
        host: String,
        cookie: String,
        file: File,
        remoteName: String,
        folder: String,
        onProgress: (Float) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): Response {
        val boundary = newBoundary()
        return stream(
            host = host,
            path = "/",
            cookie = cookie,
            boundary = boundary,
            head = multipartPrefix(boundary, folder, remoteName),
            file = file,
            onProgress = onProgress,
            cancelled = cancelled,
        )
    }

    // ----------------------------------------------------------------------- OTA

    /**
     * Announce an over-the-air install of a [size]-byte body whose [manifest] was
     * built by [OtaManifest]. The deck answers `OK` and switches its upload
     * handler from the card to a flash slot; anything else is its own refusal
     * text, e.g. `No OTA slot available`.
     */
    fun otaPrepare(host: String, cookie: String, size: Long, manifest: String): Response {
        val body = (
            "command=0" +
                "&size=" + size +
                "&manifest=" + URLEncoder.encode(manifest, "UTF-8")
            ).toByteArray(Charsets.UTF_8)
        return request(
            host,
            "/OTA",
            method = "POST",
            cookie = cookie,
            body = body,
            contentType = "application/x-www-form-urlencoded",
            timeoutMs = PREPARE_TIMEOUT_MS,
        )
    }

    /**
     * Send the image whose ranges the prepared manifest named. The whole file goes
     * up — the deck copies the app part out of it — so [onProgress] is 0..1 of the
     * file, not of the part.
     */
    fun otaUpload(
        host: String,
        cookie: String,
        file: File,
        fileName: String,
        onProgress: (Float) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): Response {
        val boundary = newBoundary()
        // The launcher's own page names this part `file1`; any name would do, but
        // matching it keeps the request identical to the browser's.
        return stream(
            host = host,
            path = "/OTAFILE",
            cookie = cookie,
            boundary = boundary,
            head = multipartFilePrefix(boundary, "file1", fileName),
            file = file,
            onProgress = onProgress,
            cancelled = cancelled,
        )
    }

    /** Restart the deck, which is how a finished OTA install starts running. */
    fun reboot(host: String, cookie: String): Response =
        request(host, "/reboot", cookie = cookie, timeoutMs = CONNECT_TIMEOUT_MS * 2)

    /**
     * Put the deck back into card mode.
     *
     * A `/OTA` preparation arms the deck to write its next body into flash, and
     * only a card listing — or a newer build's `POST /` — disarms it. An OTA that
     * prepared and then never delivered its body would otherwise leave every
     * later `+` trying to write flash, which the deck reports as `Unable to write
     * upload data` even though the card is fine.
     */
    fun resetUploadMode(host: String, cookie: String): Response =
        request(host, "/listfiles?folder=/", cookie = cookie, timeoutMs = CONNECT_TIMEOUT_MS)

    /** The deck's "that write went nowhere" answers, which a reset can cure. */
    fun isStuckWriteMode(response: Response): Boolean {
        val body = response.body
        return response.code !in 200..299 && (
            body.contains("Unable to write upload data") ||
                body.contains("WebUI Update Fail") ||
                body.contains("Install prep failed")
            )
    }

    fun newBoundary(): String =
        "----AttakyFlasher" + UUID.randomUUID().toString().replace("-", "")

    /** One multipart POST whose last part is [file], streamed so a big image never sits in RAM. */
    private fun stream(
        host: String,
        path: String,
        cookie: String,
        boundary: String,
        head: ByteArray,
        file: File,
        onProgress: (Float) -> Unit,
        cancelled: () -> Boolean,
    ): Response {
        val tail = multipartSuffix(boundary)
        val length = head.size.toLong() + file.length() + tail.size

        val connection = (URL("http://$host$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = UPLOAD_TIMEOUT_MS
            useCaches = false
            doOutput = true
            setFixedLengthStreamingMode(length)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Connection", "close")
            setRequestProperty("Cookie", "$SESSION_PREFIX$cookie")
        }

        try {
            val total = file.length().coerceAtLeast(1L)
            var sent = 0L
            BufferedOutputStream(connection.outputStream, BUFFER).use { output ->
                output.write(head)
                FileInputStream(file).use { input ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        if (cancelled()) throw IOException("Cancelled")
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        sent += read
                        onProgress((sent.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
                output.write(tail)
                output.flush()
            }
            return responseOf(connection)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The opening of a multipart body: the `folder` field first (the deck applies
     * it as it reads, so a folder part after the file would be too late), then
     * the file part's headers.
     */
    fun multipartPrefix(boundary: String, folder: String, remoteName: String): ByteArray {
        // The deck writes the name straight into its filesystem path.
        val safeName = remoteName.replace(Regex("""["\r\n/\\]"""), "-")
        return (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"folder\"\r\n\r\n" +
                "$folder\r\n" +
                "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)
    }

    /** A body that is nothing but one file part — what `/OTAFILE` takes. */
    fun multipartFilePrefix(boundary: String, fieldName: String, fileName: String): ByteArray {
        val safeName = fileName.replace(Regex("""["\r\n/\\]"""), "-")
        return (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$safeName\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)
    }

    fun multipartSuffix(boundary: String): ByteArray =
        "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)

    // ---------------------------------------------------------------------- HTTP

    fun request(
        host: String,
        path: String,
        method: String = "GET",
        cookie: String? = null,
        body: ByteArray? = null,
        contentType: String? = null,
        timeoutMs: Int = PROBE_TIMEOUT_MS,
    ): Response {
        val connection = (URL("http://$host$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            useCaches = false
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Connection", "close")
            if (cookie != null) setRequestProperty("Cookie", "$SESSION_PREFIX$cookie")
            if (contentType != null) setRequestProperty("Content-Type", contentType)
            if (body != null) {
                doOutput = true
                setFixedLengthStreamingMode(body.size)
            }
        }
        try {
            if (body != null) {
                BufferedOutputStream(connection.outputStream).use { it.write(body) }
            }
            return responseOf(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun responseOf(connection: HttpURLConnection): Response {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        return Response(code, text, connection.headerFields.orEmpty())
    }

    /** Case-insensitive response header lookup. */
    fun header(headers: Map<String, List<String>>, name: String): List<String> =
        headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value.orEmpty()

    /** The deck's own words when it refuses an upload, trimmed for a toast. */
    fun describe(response: Response): String {
        val body = response.body.trim()
        if (body.isEmpty()) return "HTTP ${response.code}"
        return if (body.length > 120) body.take(120) + "…" else body
    }
}
