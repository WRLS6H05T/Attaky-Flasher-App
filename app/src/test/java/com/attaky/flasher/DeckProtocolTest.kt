package com.attaky.flasher

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Exercises [DeckProtocol] against a server shaped like the deck's launcher
 * firmware: `/login` hands out an `ESP32SESSION` cookie, `/systeminfo` is behind
 * it, and `POST /` reads a multipart upload and writes the file part out.
 *
 * The app cannot be pointed at a real deck from a unit test, so [FakeDeck]
 * enforces the same rules the firmware does and the tests read back what it
 * received: the session cookie for everything but login, the `folder` field
 * before the file part, a `Content-Length` that covers the whole body, and no
 * stored file when the body is cut short.
 */
class DeckProtocolTest {

    private lateinit var deck: FakeDeck

    @Before
    fun start() {
        deck = FakeDeck()
    }

    @After
    fun stop() {
        deck.close()
    }

    // ------------------------------------------------------------------- identity

    @Test
    fun `a deck behind its login is recognised`() {
        val found = DeckProtocol.probe(deck.host, "admin", "launcher")
        assertNotNull("the fake launcher should be recognised", found)
        assertEquals(deck.version, found!!.version)
        assertEquals("3.2 GB", found.sdFree)
        assertEquals("29.0 GB", found.sdTotal)
        assertEquals(3435973836L, found.sdFreeBytes)
    }

    @Test
    fun `the wrong deck login is not a deck`() {
        assertNull(DeckProtocol.probe(deck.host, "admin", "wrong"))
    }

    @Test
    fun `a host that is not a deck is ignored`() {
        assertNull(DeckProtocol.probe(deck.host, "", ""))
        assertNull(DeckProtocol.deckFrom(deck.host, "<html>some other device</html>"))
        assertNull(DeckProtocol.deckFrom(deck.host, """{"APPS":[]}"""))
    }

    @Test
    fun `a deck that answers but refuses the login is still seen`() {
        // The menu offers the Launcher Web UI tile on a sighting, so a deck whose
        // web password was changed on the device must not disappear from it:
        // the screen is where the login gets fixed.
        val sighting = DeckProtocol.sight(deck.host, "admin", "wrong")
        assertNotNull("the host answered the launcher's own endpoint", sighting)
        assertEquals(deck.host, sighting!!.host)
        assertNull("a deck record still needs the login", sighting.deck)
        assertNull(DeckProtocol.probe(deck.host, "admin", "wrong"))
    }

    @Test
    fun `the session token is read from set-cookie`() {
        val headers = mapOf("Set-Cookie" to listOf("ESP32SESSION=${deck.token}; Path=/; HttpOnly"))
        assertEquals(deck.token, DeckProtocol.tokenFrom(headers))
        // A logged-out cookie is not a session.
        assertNull(DeckProtocol.tokenFrom(mapOf("Set-Cookie" to listOf("ESP32SESSION=0; Path=/"))))
        assertNull(DeckProtocol.tokenFrom(emptyMap()))
    }

    @Test
    fun `login refuses a deck password that does not match`() {
        try {
            DeckProtocol.login(deck.host, "admin", "nope")
            throw AssertionError("expected LoginFailed")
        } catch (expected: DeckProtocol.LoginFailed) {
            // The launcher redirects to /?failed without a cookie: that is a refusal.
        }
    }

    @Test
    fun `a listing disarms a pending install so a card upload works again`() {
        val image = File.createTempFile("attaky-test", ".bin").apply {
            writeBytes(ByteArray(4096))
            deleteOnExit()
        }
        val token = DeckProtocol.login(deck.host, "admin", "launcher")

        // An OTA that prepared but never delivered its body leaves the deck
        // writing its next upload to flash: the card is refused for it.
        deck.armed = true
        val refused = DeckProtocol.upload(deck.host, token, image, "x.bin", "/")
        assertEquals(500, refused.code)
        assertEquals("Unable to write upload data", refused.body)
        assertTrue(DeckProtocol.isStuckWriteMode(refused))
        assertNull("nothing may be stored while the deck is armed", deck.storedBytes)

        // The app's cure: a listing puts it back in card mode.
        assertEquals(200, DeckProtocol.resetUploadMode(deck.host, token).code)
        assertEquals(1, deck.listings)

        val ok = DeckProtocol.upload(deck.host, token, image, "x.bin", "/")
        assertEquals(200, ok.code)
        assertTrue("the retry has to land the bytes", image.readBytes().contentEquals(deck.storedBytes))
    }

    @Test
    fun `only a write that went nowhere counts as a stuck write`() {
        assertTrue(DeckProtocol.isStuckWriteMode(DeckProtocol.Response(500, "Unable to write upload data")))
        assertTrue(DeckProtocol.isStuckWriteMode(DeckProtocol.Response(500, "WebUI Update Fail: 0x1")))
        assertFalse(DeckProtocol.isStuckWriteMode(DeckProtocol.Response(500, "Upload truncated")))
        assertFalse(DeckProtocol.isStuckWriteMode(DeckProtocol.Response(200, "OK")))
    }

    // ------------------------------------------------------------------------ OTA

    @Test
    fun `an over-the-air install prepares, uploads and restarts the deck`() {
        val image = File.createTempFile("attaky-ota", ".bin").apply {
            writeBytes(ByteArray(300_000) { index -> (index % 241).toByte() })
            deleteOnExit()
        }
        val manifest = """{"sourceName":"x.bin","parts":[]}"""
        val token = DeckProtocol.login(deck.host, "admin", "launcher")

        val prepared = DeckProtocol.otaPrepare(deck.host, token, image.length(), manifest)
        assertEquals(200, prepared.code)
        assertEquals("OK", prepared.body)
        assertEquals(manifest, deck.otaManifest)
        assertEquals(image.length(), deck.otaSize)

        var lastProgress = 0f
        val sent = DeckProtocol.otaUpload(
            host = deck.host,
            cookie = token,
            file = image,
            fileName = "Attaky_bm_launcher_v0.1.0_A001.bin",
            onProgress = { lastProgress = it },
        )
        assertEquals(200, sent.code)
        assertEquals("OK", sent.body)
        assertEquals("Attaky_bm_launcher_v0.1.0_A001.bin", deck.otaFileName)
        assertTrue("the image must arrive unchanged", image.readBytes().contentEquals(deck.otaBytes))
        assertEquals(1f, lastProgress, 0.0001f)

        val reboot = DeckProtocol.reboot(deck.host, token)
        assertEquals(200, reboot.code)
        assertEquals(1, deck.reboots)
    }

    @Test
    fun `an over-the-air install without the session is refused`() {
        val response = DeckProtocol.otaPrepare(deck.host, "stale-token", 1024, """{"parts":[]}""")
        assertEquals(401, response.code)
        assertNull("nothing may be prepared without the session", deck.otaManifest)
    }

    @Test
    fun `the deck's own refusal is passed back to the caller`() {
        deck.otaSlotFree = false
        val token = DeckProtocol.login(deck.host, "admin", "launcher")
        val response = DeckProtocol.otaPrepare(deck.host, token, 1024, """{"parts":[]}""")
        assertEquals(400, response.code)
        assertEquals("No OTA slot available", DeckProtocol.describe(response))
    }

    @Test
    fun `the ota body is one file part with the image's own name`() {
        val prefix = DeckProtocol.multipartFilePrefix("B", "file1", "Attaky_launcher.bin")
            .toString(Charsets.UTF_8)
        assertTrue(prefix.contains("name=\"file1\"; filename=\"Attaky_launcher.bin\""))
        assertTrue(
            "no folder field: an OTA body must not be routed to the card",
            !prefix.contains("name=\"folder\""),
        )
    }

    // --------------------------------------------------------------------- upload

    @Test
    fun `an image reaches the deck's card with its name`() {
        val image = File.createTempFile("attaky-test", ".bin").apply {
            writeBytes(ByteArray(200_000) { index -> (index % 251).toByte() })
            deleteOnExit()
        }
        val token = DeckProtocol.login(deck.host, "admin", "launcher")

        var lastProgress = 0f
        val response = DeckProtocol.upload(
            host = deck.host,
            cookie = token,
            file = image,
            remoteName = "Scratch-1.1.0-merged.bin",
            folder = "/",
            onProgress = { lastProgress = it },
        )

        assertEquals(200, response.code)
        assertEquals("OK", response.body)
        assertEquals("/", deck.storedFolder)
        assertEquals("Scratch-1.1.0-merged.bin", deck.storedName)
        assertTrue(
            "the bytes must arrive unchanged",
            image.readBytes().contentEquals(deck.storedBytes),
        )
        assertEquals("progress should finish at 100%", 1f, lastProgress, 0.0001f)
    }

    @Test
    fun `an upload without the session is refused rather than stored`() {
        val image = File.createTempFile("attaky-test", ".bin").apply {
            writeBytes(ByteArray(1024))
            deleteOnExit()
        }
        val response = DeckProtocol.upload(deck.host, "stale-token", image, "x.bin", "/")
        assertEquals(401, response.code)
        assertNull("nothing may be written without the session", deck.storedBytes)
    }

    @Test
    fun `the file name cannot escape the card's folder`() {
        val prefix = DeckProtocol.multipartPrefix("B", "/", "../../evil/name.bin")
            .toString(Charsets.UTF_8)
        assertTrue(prefix.contains("filename=\"..-..-evil-name.bin\""))
        assertTrue(prefix.contains("name=\"folder\"\r\n\r\n/\r\n"))
        assertTrue(
            "the folder field has to come before the file part",
            prefix.indexOf("name=\"folder\"") < prefix.indexOf("name=\"file\""),
        )
    }

    @Test
    fun `cancelling stops the upload midway`() {
        val image = File.createTempFile("attaky-test", ".bin").apply {
            writeBytes(ByteArray(4 * 1024 * 1024))
            deleteOnExit()
        }
        val token = DeckProtocol.login(deck.host, "admin", "launcher")
        var cancel = false
        try {
            DeckProtocol.upload(
                host = deck.host,
                cookie = token,
                file = image,
                remoteName = "big.bin",
                folder = "/",
                onProgress = { if (it > 0f) cancel = true },
                cancelled = { cancel },
            )
            throw AssertionError("expected the upload to abort")
        } catch (expected: java.io.IOException) {
            assertNull("an aborted upload stores nothing", deck.storedBytes)
        }
    }
}

/**
 * The smallest faithful stand-in for the launcher's web server: one request per
 * connection, the session cookie, and the multipart rules the firmware applies.
 */
private class FakeDeck(
    val version: String = "v0.1.0-A001",
    private val user: String = "admin",
    private val password: String = "launcher",
) {

    val token = "c0ffee5e5510n"

    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))

    val host: String get() = "127.0.0.1:${server.localPort}"

    @Volatile
    var storedName: String? = null

    @Volatile
    var storedFolder: String? = null

    @Volatile
    var storedBytes: ByteArray? = null

    /** What `/OTA` was told to install. */
    @Volatile
    var otaManifest: String? = null

    @Volatile
    var otaSize: Long = 0

    /** The name on the `/OTAFILE` part, and the bytes it carried. */
    @Volatile
    var otaFileName: String? = null

    @Volatile
    var otaBytes: ByteArray? = null

    @Volatile
    var reboots = 0

    /** Set false to make the deck answer `No OTA slot available`. */
    @Volatile
    var otaSlotFree = true

    /** True while a prepared install has the deck writing bodies into flash. */
    @Volatile
    var armed = false

    /** How many card listings the deck served — the call that disarms it. */
    @Volatile
    var listings = 0

    private val worker = Thread({ acceptLoop() }, "fake-deck").apply {
        isDaemon = true
        start()
    }

    fun close() {
        server.close()
    }

    private fun acceptLoop() {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (t: Throwable) {
                return
            }
            try {
                serve(socket)
            } catch (t: Throwable) {
                // A client that hangs up mid-body is expected (the cancel test).
            } finally {
                socket.close()
            }
        }
    }

    private fun serve(socket: Socket) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        val requestLine = readLine(input) ?: return
        val request = requestLine.split(' ')
        val method = request.getOrElse(0) { "GET" }
        val path = request.getOrElse(1) { "/" }.substringBefore('?')

        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val at = line.indexOf(':')
            if (at > 0) headers[line.substring(0, at).trim().lowercase()] = line.substring(at + 1).trim()
        }

        // The firmware reads req->content_len; a short body means a broken upload.
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(body, read, length - read)
            if (count < 0) break
            read += count
        }
        val payload = body.copyOf(read)

        when {
            path == "/login" && method == "POST" -> {
                val form = payload.toString(Charsets.UTF_8)
                if (form == "username=$user&password=$password") {
                    output.write(
                        (
                            "HTTP/1.1 302 Found\r\n" +
                                "Set-Cookie: ESP32SESSION=$token; Path=/; HttpOnly\r\n" +
                                "Content-Length: 0\r\nConnection: close\r\n\r\n"
                            ).toByteArray(),
                    )
                } else {
                    output.write(
                        (
                            "HTTP/1.1 302 Found\r\nLocation: /?failed\r\n" +
                                "Content-Length: 0\r\nConnection: close\r\n\r\n"
                            ).toByteArray(),
                    )
                }
            }

            path == "/systeminfo" -> {
                if (headers["cookie"] != "ESP32SESSION=$token") {
                    respond(output, 401, "Unauthorized")
                } else {
                    respond(output, 200, systemInfo())
                }
            }

            path == "/" && method == "POST" -> {
                when {
                    headers["cookie"] != "ESP32SESSION=$token" -> respond(output, 401, "Unauthorized")
                    // A prepared install is still armed: this body would go to
                    // flash, which is what the real firmware refuses.
                    armed -> respond(output, 500, "Unable to write upload data")
                    read < length -> respond(output, 500, "Upload truncated")
                    else -> {
                        storeMultipart(headers["content-type"].orEmpty(), payload)
                        respond(output, 200, "OK")
                    }
                }
            }

            // The firmware clears any pending install on a listing.
            path == "/listfiles" -> {
                if (headers["cookie"] != "ESP32SESSION=$token") {
                    respond(output, 401, "Unauthorized")
                } else {
                    listings++
                    armed = false
                    respond(output, 200, "pa:/:0\n")
                }
            }

            // The launcher's over-the-air install: a form-encoded preparation,
            // then the image itself, which lands in flash instead of the card.
            path == "/OTA" && method == "POST" -> {
                val form = parseForm(payload)
                when {
                    headers["cookie"] != "ESP32SESSION=$token" -> respond(output, 401, "Unauthorized")
                    form["command"] != "0" || form["manifest"] == null || form["size"] == null ->
                        respond(output, 400, "Invalid OTA request")
                    !otaSlotFree -> respond(output, 400, "No OTA slot available")
                    else -> {
                        otaManifest = form["manifest"]
                        otaSize = form["size"]!!.toLongOrNull() ?: 0
                        respond(output, 200, "OK")
                    }
                }
            }

            path == "/OTAFILE" && method == "POST" -> {
                when {
                    headers["cookie"] != "ESP32SESSION=$token" -> respond(output, 401, "Unauthorized")
                    otaManifest == null -> respond(output, 500, "Install prep failed")
                    read < length -> respond(output, 500, "Upload truncated")
                    else -> {
                        val part = filePart(headers["content-type"].orEmpty(), payload)
                        otaFileName = part?.first
                        otaBytes = part?.second
                        respond(output, 200, "OK")
                    }
                }
            }

            path == "/reboot" -> {
                if (headers["cookie"] != "ESP32SESSION=$token") {
                    respond(output, 401, "Unauthorized")
                } else {
                    reboots++
                    respond(output, 200, "Rebooting")
                }
            }

            else -> respond(output, 200, "<html><body>Launcher</body></html>")
        }
        output.flush()
    }

    private fun systemInfo(): String =
        """{"VERSION":"$version","SD":{"free":"3.2 GB","used":"25.8 GB","total":"29.0 GB",""" +
            """"freeBytes":3435973836,"usedBytes":27700000000,"totalBytes":31138512896},""" +
            """"APPS":[{"label":"Scratch","name":"scratch"}]}"""

    /** Split the body the way the firmware does: boundary, headers, content. */
    private fun storeMultipart(contentType: String, body: ByteArray) {
        val boundary = contentType.substringAfter("boundary=", "")
        if (boundary.isEmpty()) return
        val text = body.toString(Charsets.ISO_8859_1)
        for (chunk in text.split("--$boundary")) {
            if (chunk.isEmpty() || chunk.startsWith("--")) continue
            val part = chunk.removePrefix("\r\n")
            val split = part.indexOf("\r\n\r\n")
            if (split < 0) continue
            val head = part.substring(0, split)
            // One byte per char in ISO-8859-1, so the frames drop off exactly.
            val content = part.substring(split + 4).removeSuffix("\r\n")
            val fileAt = head.indexOf("filename=\"")
            if (fileAt >= 0) {
                storedName = head.substring(fileAt + 10, head.indexOf('"', fileAt + 10))
                storedBytes = content.toByteArray(Charsets.ISO_8859_1)
            } else if (head.contains("name=\"folder\"")) {
                storedFolder = content
            }
        }
    }

    /** `a=b&c=d`, url-decoded, as the deck's `readParams` sees it. */
    private fun parseForm(payload: ByteArray): Map<String, String> =
        payload.toString(Charsets.UTF_8)
            .split('&')
            .filter { it.contains('=') }
            .associate { pair ->
                val at = pair.indexOf('=')
                java.net.URLDecoder.decode(pair.substring(0, at), "UTF-8") to
                    java.net.URLDecoder.decode(pair.substring(at + 1), "UTF-8")
            }

    /** The one file part of a multipart body: its name and its bytes. */
    private fun filePart(contentType: String, body: ByteArray): Pair<String, ByteArray>? {
        val boundary = contentType.substringAfter("boundary=", "")
        if (boundary.isEmpty()) return null
        val text = body.toString(Charsets.ISO_8859_1)
        for (chunk in text.split("--$boundary")) {
            if (chunk.isEmpty() || chunk.startsWith("--")) continue
            val part = chunk.removePrefix("\r\n")
            val split = part.indexOf("\r\n\r\n")
            if (split < 0) continue
            val head = part.substring(0, split)
            val fileAt = head.indexOf("filename=\"")
            if (fileAt < 0) continue
            val name = head.substring(fileAt + 10, head.indexOf('"', fileAt + 10))
            val content = part.substring(split + 4).removeSuffix("\r\n")
            return name to content.toByteArray(Charsets.ISO_8859_1)
        }
        return null
    }

    private fun respond(output: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        output.write(
            (
                "HTTP/1.1 $code Status\r\nContent-Type: text/plain\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                ).toByteArray(),
        )
        output.write(bytes)
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val value = input.read()
            if (value < 0) return if (line.isEmpty()) null else line.toString()
            if (value == '\n'.code) return line.toString().removeSuffix("\r")
            line.append(value.toChar())
        }
    }
}
