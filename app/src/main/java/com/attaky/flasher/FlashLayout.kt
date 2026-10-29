package com.attaky.flasher

import java.util.Locale

/**
 * Works out where each .bin belongs on the deck's flash, from its name, and
 * joins a set of them into the single image the deck is written with.
 *
 * The store ships pre-merged images, but a build straight out of PlatformIO,
 * the Arduino IDE or ESP-IDF comes as separate bins whose names are the only
 * thing that says where they go (`bootloader.bin`, `partitions.bin`,
 * `boot_app0.bin`, `firmware.bin`). Those names are standard, so the flasher
 * can place them itself and write the whole set in one erase/write pass
 * instead of asking for an offset per file.
 *
 * Offsets are the ESP32-S3 defaults (the deck's chip) and match the ESP-IDF and
 * Arduino partition tables. Filesystems are deliberately absent: their offset
 * depends on the flash size, so guessing could land on top of a partition.
 */
object FlashLayout {

    /** Where one bin goes, and what it is called on the deck. */
    data class Slot(val offset: Long, val role: String)

    /** Two bins claim the same flash; the set cannot be built. */
    class OverlapException(message: String) : IllegalArgumentException(message)

    /** One bin ready to be placed. */
    class Part(val label: String, val offset: Long, val bytes: ByteArray)

    /** The image to write: [parts] merged, starting at [offset]. */
    class Image(val offset: Long, val bytes: ByteArray, val parts: List<Part>)

    /** Erased-flash fill for the gaps between parts, so unwritten areas stay blank. */
    private const val PAD = 0xFF.toByte()

    private const val MAX_IMAGE = 16L * 1024 * 1024

    private val SLOTS: Map<String, Slot> = mapOf(
        "bootloader" to Slot(0x0, "bootloader"),
        "partition-table" to Slot(0x8000, "partition table"),
        "partitions" to Slot(0x8000, "partition table"),
        "nvs" to Slot(0x9000, "NVS"),
        "boot-app0" to Slot(0xe000, "boot_app0"),
        "otadata" to Slot(0xe000, "otadata"),
        "ota-data" to Slot(0xe000, "otadata"),
        "ota-data-initial" to Slot(0xe000, "otadata"),
        "app" to Slot(0x10000, "application"),
        "application" to Slot(0x10000, "application"),
        "firmware" to Slot(0x10000, "application"),
    )

    /** Longest key first, so "partition-table" wins over "app" style prefixes. */
    private val LONGEST_KEY_FIRST = SLOTS.keys.sortedByDescending { it.length }

    /**
     * Prefixes and tags builds put in their file names that say nothing about
     * where the bin goes ("esp32s3-attaky-bootloader.bin" is a bootloader).
     */
    private val NOISE = setOf(
        "esp32", "esp32s3", "esp32s3usbotg", "s3", "attaky", "fw", "flash", "bin", "img", "image",
    )

    /** Version tags such as "1.1.0" or "v2", dropped before matching. */
    private val VERSION = Regex("""v?\d+(\.\d+)*""")

    /** Names that mean "this is already a full image at 0x0". */
    private val WHOLE_IMAGE = setOf("merged", "full", "combined", "all")

    /**
     * The flash slot [fileName] belongs to, or null when the name says nothing
     * the flasher recognises. The caller leaves the offset to the user then.
     */
    fun slotFor(fileName: String): Slot? {
        val base = fileName.substringAfterLast('/').substringAfterLast('\\')
        val stem = base.substringBeforeLast('.', base).lowercase(Locale.US)
        val tokens = stem
            .replace('_', '-')
            .replace(' ', '-')
            .split('-')
            .filter { it.isNotEmpty() }
            .filterNot { it in NOISE }
            .filterNot { VERSION.matches(it) }
        if (tokens.isEmpty()) return null

        if (tokens.any { it in WHOLE_IMAGE }) return Slot(0x0, "merged image")

        val canonical = tokens.joinToString("-")
        val key = LONGEST_KEY_FIRST.firstOrNull { canonical == it || canonical.endsWith("-$it") }
        return key?.let { SLOTS[it] }
    }

    /**
     * Join [parts] into one image padded to [PAD] between them, so a whole
     * firmware set is written in a single pass. A single part is passed through
     * untouched: no padding, no extra erase.
     *
     * @throws OverlapException when two parts share flash space.
     */
    fun join(parts: List<Part>): Image {
        val present = parts.filter { it.bytes.isNotEmpty() }
        require(present.isNotEmpty()) { "There is nothing to flash." }
        if (present.size == 1) {
            val only = present.first()
            return Image(only.offset, only.bytes, present)
        }

        val sorted = present.sortedBy { it.offset }
        var previous: Part? = null
        for (part in sorted) {
            val prev = previous
            if (prev != null && prev.offset + prev.bytes.size > part.offset) {
                throw OverlapException(
                    "${part.label} at 0x${part.offset.toString(16)} overlaps ${prev.label}, " +
                        "which runs to 0x${(prev.offset + prev.bytes.size).toString(16)}.",
                )
            }
            previous = part
        }

        val base = sorted.first().offset
        val span = sorted.last().let { it.offset + it.bytes.size } - base
        require(span <= MAX_IMAGE) {
            "The set spans $span bytes; that is larger than the deck's flash."
        }

        val image = ByteArray(span.toInt()) { PAD }
        sorted.forEach { part -> part.bytes.copyInto(image, (part.offset - base).toInt()) }
        return Image(base, image, sorted)
    }
}
