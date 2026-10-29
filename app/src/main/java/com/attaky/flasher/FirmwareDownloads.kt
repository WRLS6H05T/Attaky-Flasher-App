package com.attaky.flasher

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Firmware the user pulled from the store, kept on the phone.
 *
 * Downloads land in the app's own external files directory
 * (`Android/data/com.attaky.flasher/files/firmware`), which needs no storage
 * permission and is wiped with the app. An `index.json` next to the images
 * records where each one came from, so the bundled flasher page can list them
 * and flash them fully offline.
 *
 * The page never touches the filesystem directly: [FirmwarePathHandler] serves
 * the stored bytes back over the appassets origin, and the `AttakyUSB` bridge
 * (see [SerialBridge]) exposes [listJson] so the page knows what is available.
 */
class FirmwareDownloads(context: Context) : SerialBridge.FirmwareStore {

    data class Entry(
        val name: String,
        val sourceUrl: String,
        val size: Long,
        val offset: Long,
        val added: Long,
    )

    private val appContext: Context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private val lock = Any()
    private var entries = LinkedHashMap<String, Entry>()

    /** Where the images live; created lazily. */
    val dir: File = File(
        appContext.getExternalFilesDir(null) ?: appContext.filesDir,
        DIR_NAME,
    )

    private val indexFile = File(dir, INDEX_FILE)

    /** Told on the main thread when a merge-on-download finishes:
     *  `(true, fileName, publishedLocation)` or `(false, reason, null)`.
     *  The location is where the image was copied for the user to see, or null
     *  when that copy could not be made. */
    var onMerged: ((Boolean, String, String?) -> Unit)? = null

    // ------------------------------------------------------------ bridge surface

    /** JSON array of the images on the phone, newest first. */
    override fun listJson(): String = synchronized(lock) {
        refreshLocked()
        val array = JSONArray()
        entries.values.sortedByDescending { it.added }.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("name", entry.name)
                    put("size", entry.size)
                    put("offset", entry.offset)
                    put("added", entry.added)
                    put("sourceUrl", entry.sourceUrl)
                },
            )
        }
        array.toString()
    }

    override fun delete(name: String): Boolean = synchronized(lock) {
        val file = fileFor(name) ?: return@synchronized false
        val removed = file.delete()
        entries.remove(name)
        writeIndex(entries)
        removed
    }

    /** The images on the phone, newest first, for the native flasher screen. */
    fun entries(): List<Entry> = synchronized(lock) {
        refreshLocked()
        entries.values.sortedByDescending { it.added }
    }

    // ------------------------------------------------------------------ downloads

    /**
     * Pull [url] into the firmware folder in the background. [cookie] and
     * [userAgent] come from the WebView so authenticated store downloads work.
     * [onDone] is delivered on the main thread: `(true, name)` on success,
     * `(false, reason)` on failure.
     */
    fun download(
        url: String,
        name: String,
        cookie: String?,
        userAgent: String?,
        onDone: (Boolean, String) -> Unit,
    ) {
        io.execute {
            var tmp: File? = null
            try {
                dir.mkdirs()
                val target = File(dir, name)
                tmp = File(dir, name + PART_SUFFIX)

                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    requestMethod = "GET"
                    setRequestProperty("Accept", "*/*")
                    if (!cookie.isNullOrEmpty()) setRequestProperty("Cookie", cookie)
                    if (!userAgent.isNullOrEmpty()) setRequestProperty("User-Agent", userAgent)
                }
                try {
                    val code = connection.responseCode
                    if (code !in 200..299) throw IOException("HTTP $code")
                    connection.inputStream.use { input ->
                        FileOutputStream(tmp).use { output -> input.copyTo(output) }
                    }
                } finally {
                    connection.disconnect()
                }

                if (target.exists() && !target.delete()) throw IOException("could not replace $name")
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }

                synchronized(lock) {
                    refreshLocked()
                    // A store file whose name is a standard bin (bootloader.bin,
                    // partitions.bin, …) is already placed; merged images sit at 0.
                    entries[name] = Entry(
                        name = name,
                        sourceUrl = url,
                        size = target.length(),
                        offset = FlashLayout.slotFor(name)?.offset ?: 0L,
                        added = System.currentTimeMillis(),
                    )
                    writeIndex(entries)
                }
                post { onDone(true, name) }
            } catch (t: Throwable) {
                Log.w(TAG, "firmware download failed: $url", t)
                tmp?.delete()
                post { onDone(false, t.message ?: t.javaClass.simpleName) }
            }
        }
    }

    // ------------------------------------------------------------ merged download

    /**
     * Store pages send a whole firmware set here (see assets/www/js/store-merge.js)
     * instead of one download per part: every part is fetched, placed at the
     * address the store gave it and joined into one image, so the downloaded
     * list holds a single thing to flash.
     *
     * @return "ok" once the work is queued, else "error: …".
     */
    override fun downloadMerged(requestJson: String): String {
        val request = try {
            parseMerged(requestJson)
        } catch (t: Throwable) {
            return "error: ${t.message ?: "bad request"}"
        }
        io.execute {
            try {
                val saved = saveMerged(request)
                post { onMerged?.invoke(true, saved.name, saved.published) }
            } catch (t: Throwable) {
                Log.w(TAG, "merged download failed", t)
                post { onMerged?.invoke(false, t.message ?: t.javaClass.simpleName, null) }
            }
        }
        return "ok"
    }

    private class MergedPart(val name: String, val url: String, val offset: Long?)

    private class MergedRequest(val title: String, val parts: List<MergedPart>)

    private fun parseMerged(json: String): MergedRequest {
        val root = JSONObject(json)
        val array = root.optJSONArray("parts") ?: throw IllegalArgumentException("no parts")
        val parts = ArrayList<MergedPart>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val url = obj.optString("url")
            if (url.isEmpty()) continue
            val name = obj.optString("name").ifEmpty { url.substringAfterLast('/') }
            val offset = if (obj.has("offset") && !obj.isNull("offset")) {
                obj.optLong("offset", 0L)
            } else {
                null
            }
            parts.add(MergedPart(name, url, offset))
        }
        if (parts.isEmpty()) throw IllegalArgumentException("no parts")
        return MergedRequest(root.optString("title"), parts)
    }

    private class SavedImage(val name: String, val published: String?)

    /** Fetch, place and join every part; keeps the image and copies it out. */
    private fun saveMerged(request: MergedRequest): SavedImage {
        dir.mkdirs()
        val parts = ArrayList<FlashLayout.Part>()
        for (part in request.parts) {
            // The store's Flash Address column is authoritative; a part without
            // one (or an older page) falls back to what its name says.
            val offset = part.offset
                ?: FlashLayout.slotFor(part.name)?.offset
                ?: throw IOException("The store did not say where ${part.name} goes.")
            parts.add(FlashLayout.Part(part.name, offset, fetch(part.url)))
        }

        val image = FlashLayout.join(parts)
        val name = mergedName(request.title)
        val tmp = File(dir, name + PART_SUFFIX)
        FileOutputStream(tmp).use { it.write(image.bytes) }

        val target = File(dir, name)
        if (target.exists() && !target.delete()) throw IOException("could not replace $name")
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }

        synchronized(lock) {
            refreshLocked()
            entries[name] = Entry(
                name = name,
                sourceUrl = request.parts.first().url,
                size = target.length(),
                offset = image.offset,
                added = System.currentTimeMillis(),
            )
            writeIndex(entries)
        }

        // The flasher reads its own copy; this one is for the user to see in the
        // phone's Downloads folder (and to move onto an SD card, share, etc.).
        return SavedImage(name, publishToDownloads(name, image.bytes))
    }

    /**
     * Copy [bytes] to `Download/AttakyFlasher/[name]` so the image shows up in
     * the Files app, not only in the app's private folder. Returns the visible
     * location, or null when the copy could not be made.
     */
    private fun publishToDownloads(name: String, bytes: ByteArray): String? {
        val relative = "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_DIR"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = appContext.contentResolver
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                // Replace an earlier copy instead of piling up "name (1).bin".
                resolver.delete(
                    collection,
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                        "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                    arrayOf(name, "$relative/"),
                )
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                }
                val uri = resolver.insert(collection, values) ?: return null
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null
                "$relative/$name"
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    PUBLIC_DIR,
                )
                dir.mkdirs()
                val file = File(dir, name)
                FileOutputStream(file).use { it.write(bytes) }
                file.absolutePath
            }
        } catch (t: Throwable) {
            Log.w(TAG, "could not copy $name to Downloads", t)
            null
        }
    }

    /** GET [url] with the WebView's cookies, so signed-in store parts download. */
    private fun fetch(url: String): ByteArray {
        val cookie = try {
            CookieManager.getInstance().getCookie(url)
        } catch (t: Throwable) {
            null
        }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 120_000
            requestMethod = "GET"
            setRequestProperty("Accept", "*/*")
            if (!cookie.isNullOrEmpty()) setRequestProperty("Cookie", cookie)
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code for ${url.substringAfterLast('/')}")
            }
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    /** "Voice Memo 1.1.0" → "Voice-Memo-1.1.0-merged.bin". */
    private fun mergedName(title: String): String {
        var base = title.trim()
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "-")
            .replace(Regex("\\s+"), "-")
            .trim('-', '.')
        if (base.isEmpty()) base = "firmware"
        if (base.length > 80) base = base.take(80)
        return "$base-merged.bin"
    }

    // ---------------------------------------------------------------- filesystem

    /** The stored file for [name], or null if it is absent or unsafe. */
    fun fileFor(name: String): File? {
        if (name.isEmpty()) return null
        if (name.contains('/') || name.contains('\\') || name.contains("..")) return null
        val file = File(dir, name)
        return if (file.isFile) file else null
    }

    /** Re-read the folder, dropping vanished files and adopting stray images. */
    private fun refreshLocked() {
        dir.mkdirs()
        val index = readIndex()
        val merged = LinkedHashMap<String, Entry>()
        index.forEach { (name, entry) ->
            val file = File(dir, name)
            if (file.isFile) entries[name]?.let { /* keep */ }
            if (file.isFile) merged[name] = entry.copy(size = file.length())
        }

        val files = dir.listFiles()
        if (files != null) {
            for (file in files) {
                if (!file.isFile) continue
                val n = file.name
                if (n == INDEX_FILE || n.endsWith(PART_SUFFIX)) continue
                if (merged.containsKey(n)) continue
                if (!looksLikeFirmware(n)) continue
                merged[n] = Entry(
                    name = n,
                    sourceUrl = "",
                    size = file.length(),
                    offset = FlashLayout.slotFor(n)?.offset ?: 0L,
                    added = file.lastModified(),
                )
            }
        }

        entries = merged
        writeIndex(merged)
    }

    private fun readIndex(): LinkedHashMap<String, Entry> {
        val map = LinkedHashMap<String, Entry>()
        if (!indexFile.isFile) return map
        try {
            val array = JSONArray(indexFile.readText())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val name = obj.optString("name")
                if (name.isEmpty()) continue
                map[name] = Entry(
                    name = name,
                    sourceUrl = obj.optString("sourceUrl"),
                    size = obj.optLong("size"),
                    offset = obj.optLong("offset"),
                    added = obj.optLong("added"),
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "could not read the firmware index", t)
        }
        return map
    }

    private fun writeIndex(map: Map<String, Entry>) {
        try {
            dir.mkdirs()
            val array = JSONArray()
            map.values.forEach { entry ->
                array.put(
                    JSONObject().apply {
                        put("name", entry.name)
                        put("sourceUrl", entry.sourceUrl)
                        put("size", entry.size)
                        put("offset", entry.offset)
                        put("added", entry.added)
                    },
                )
            }
            indexFile.writeText(array.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "could not write the firmware index", t)
        }
    }

    private fun post(block: () -> Unit) {
        main.post(block)
    }

    companion object {
        private const val TAG = "AttakyFirmware"
        private const val DIR_NAME = "firmware"

        /** User-visible folder under Downloads for merged images. */
        private const val PUBLIC_DIR = "AttakyFlasher"
        private const val INDEX_FILE = "index.json"
        private const val PART_SUFFIX = ".part"

        /** Only these extensions are adopted when scanning the folder. */
        private val FIRMWARE_EXT = setOf("bin", "img", "zip")

        fun looksLikeFirmware(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext.isNotEmpty() && ext in FIRMWARE_EXT
        }
    }
}

/**
 * Serves the downloaded firmware back to the flasher page at
 * `https://appassets.androidplatform.net/firmware/<name>`, so `fetch()` in
 * app.js can read the bytes with no network and no file-system permission.
 */
class FirmwarePathHandler(
    private val downloads: FirmwareDownloads,
) : WebViewAssetLoader.PathHandler {

    override fun handle(path: String): WebResourceResponse? {
        val name = path.substringAfterLast('/')
        val file = downloads.fileFor(name) ?: return null
        return try {
            val headers = HashMap<String, String>()
            headers["Content-Length"] = file.length().toString()
            headers["Cache-Control"] = "no-store"
            WebResourceResponse(MIME, null, 200, "OK", headers, FileInputStream(file))
        } catch (t: Throwable) {
            Log.w("AttakyFirmware", "could not serve $name", t)
            null
        }
    }

    private companion object {
        const val MIME = "application/octet-stream"
    }
}
