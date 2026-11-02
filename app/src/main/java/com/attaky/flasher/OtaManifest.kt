package com.attaky.flasher

import org.json.JSONArray
import org.json.JSONObject

/**
 * The manifest the launcher's WebUI sends along with an over-the-air install.
 *
 * The deck's `/OTA` handler does not take a bare app image: it takes the *file*
 * it is about to receive plus a JSON list of the parts to pull out of it. A
 * merged store download is exactly such a file — bootloader, partition table and
 * app — so the manifest names the app partition's offset and length inside it,
 * and the deck copies just that range into its new app slot.
 *
 * This mirrors the launcher's own `analyzeFile()` in `scripts.js`:
 *
 *  - the partition table sits at `0x8000`, 32 bytes per entry, starting
 *    `AA 50 01`, ending at an `EB EB` or `FF FF` entry;
 *  - the app entry is `type 0` with subtype `0x00` (factory), `0x10` (ota_0) or
 *    `0x20` (ota_1), and its declared size is a whole partition, so the real
 *    image length is measured from the ESP image header instead;
 *  - an ESP image starts `0xE9`, carries a segment count, and ends after its
 *    segments, a checksum byte and — when the header says so — a SHA-256.
 *
 * A file with no partition table is treated as a bare app image at offset 0,
 * the same fallback the launcher's page uses.
 */
object OtaManifest {

    /** Where the ESP partition table begins in a merged image. */
    const val TABLE_OFFSET = 0x8000

    /** Where the first app partition lands when its entry declares no offset. */
    private const val DEFAULT_APP_OFFSET = 0x10000

    private const val ENTRY_SIZE = 0x20

    /** One entry either side is enough for real tables; a wrong magic stops sooner. */
    private const val MAX_ENTRIES = 0x1000 / ENTRY_SIZE

    /** The app image inside a merged file: where it starts and how long it is. */
    data class Part(val sourceOffset: Long, val copySize: Long)

    /**
     * The app image inside [image], or null when the file has nothing that looks
     * like one — in which case there is nothing an OTA slot could boot.
     */
    fun appPart(image: ByteArray): Part? {
        if (hasTable(image)) {
            // A table without an app entry is a merged image with nothing to boot.
            val table = appPartition(image) ?: return null
            val offset = table.first
            if (offset >= image.size) return null
            val available = image.size.toLong() - offset
            // The declared size is the whole partition; never read past the file.
            var size = minOf(table.second, available)
            val measured = espImageSize(image, offset.toInt())
            // The firmware trusts the measured length whenever it is shorter.
            if (measured > 0 && (size == 0L || measured < size)) size = measured.toLong()
            return if (size > 0) Part(offset, size) else null
        }

        // No table: a bare app image, which has to actually be one at offset 0.
        val measured = espImageSize(image, 0)
        return if (measured > 0) Part(0, measured.toLong()) else null
    }

    /**
     * The `manifest` field for `/OTA`: a single app part named [fileName]. Data
     * partitions are deliberately left out — an over-the-air update replaces the
     * running firmware and keeps the deck's own settings and apps.
     */
    fun json(fileName: String, image: ByteArray): String? {
        val part = appPart(image) ?: return null
        val parts = JSONArray().put(
            JSONObject()
                .put("kind", "app")
                .put("label", "app")
                .put("subtype", 0)
                .put("sourceOffset", part.sourceOffset)
                .put("copySize", part.copySize)
                .put("declaredSize", part.copySize),
        )
        return JSONObject()
            .put("sourceName", fileName)
            .put("parts", parts)
            .toString()
    }

    // --------------------------------------------------------------- partition table

    /** True when the file carries a partition table where ESP images put one. */
    private fun hasTable(image: ByteArray): Boolean {
        if (image.size < TABLE_OFFSET + ENTRY_SIZE) return false
        return u8(image, TABLE_OFFSET) == 0xAA &&
            u8(image, TABLE_OFFSET + 1) == 0x50 &&
            u8(image, TABLE_OFFSET + 2) == 0x01
    }

    /** The app entry's `(offset, declaredSize)`, or null when the table has none. */
    private fun appPartition(image: ByteArray): Pair<Long, Long>? {
        for (index in 0 until MAX_ENTRIES) {
            val at = TABLE_OFFSET + index * ENTRY_SIZE
            if (at + ENTRY_SIZE > image.size) return null
            val type = u8(image, at + 2)
            val subtype = u8(image, at + 3)
            // 0xEBEB / 0xFFFF is the end marker; anything before it is a real entry.
            if ((type == 0xEB && subtype == 0xEB) || (type == 0xFF && subtype == 0xFF)) return null

            val app = type == 0x00 && (subtype == 0x00 || subtype == 0x10 || subtype == 0x20)
            if (app) {
                val offset = le32(image, at + 4)
                val declared = le32(image, at + 8)
                return (if (offset == 0L) DEFAULT_APP_OFFSET.toLong() else offset) to declared
            }
        }
        return null
    }

    // ------------------------------------------------------------------ ESP image

    /**
     * The byte length of the ESP image at [offset], or 0 when there is no image
     * there. `0xE9`, a segment count, then `{load address, length, data}` per
     * segment, a checksum byte, and optionally a SHA-256.
     */
    fun espImageSize(image: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 24 > image.size) return 0
        val segments = u8(image, offset + 1)
        val hashAppended = u8(image, offset + 23)
        if (u8(image, offset) != 0xE9 || segments == 0 || segments > 16) return 0

        var cursor = offset + 24
        for (index in 0 until segments) {
            if (cursor + 8 > image.size) return 0
            val segmentSize = le32(image, cursor + 4)
            if (segmentSize < 0 || segmentSize > image.size) return 0
            cursor += 8
            if (cursor > image.size - segmentSize) return 0
            cursor += segmentSize.toInt()
        }

        var end = align16(cursor) + 1
        if (hashAppended != 0) end += 32
        end = align16(end)
        if (end <= offset) return 0
        if (end > image.size) {
            // A merged image can stop inside the trailing padding — the store
            // writes the parts, not esptool's filler. Everything the segments
            // declare is present, so the image runs to the end of the file.
            if (cursor > image.size) return 0
            return image.size - offset
        }
        return end - offset
    }

    // --------------------------------------------------------------------- bytes

    private fun align16(value: Int): Int = (value + 15) and 15.inv()

    private fun u8(image: ByteArray, at: Int): Int = image[at].toInt() and 0xFF

    /** Little-endian unsigned 32-bit, as a non-negative long. */
    private fun le32(image: ByteArray, at: Int): Long =
        (u8(image, at).toLong()) or
            (u8(image, at + 1).toLong() shl 8) or
            (u8(image, at + 2).toLong() shl 16) or
            (u8(image, at + 3).toLong() shl 24)
}
