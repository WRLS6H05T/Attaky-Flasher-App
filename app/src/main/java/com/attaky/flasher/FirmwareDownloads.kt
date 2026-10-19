package com.attaky.flasher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
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
                    entries[name] = Entry(name, url, target.length(), 0L, System.currentTimeMillis())
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
                merged[n] = Entry(n, "", file.length(), 0L, file.lastModified())
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
