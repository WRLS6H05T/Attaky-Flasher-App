package com.attaky.flasher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [OtaManifest] on images built here: a partition table at `0x8000`
 * with an app entry, and an ESP image at the offset it names. The numbers the
 * manifest publishes are what the launcher's `/OTA` handler copies, so they have
 * to be the app image's own length — not the whole partition — or the deck would
 * read the rest of the flash into its new slot.
 */
class OtaManifestTest {

    @Test
    fun `the app part of a merged image is its offset and image length`() {
        val image = mergedImage(appOffset = 0x10000, hashAppended = true, tailPadding = 400)
        val part = OtaManifest.appPart(image)
        assertNotNull("the merged image has an app partition", part)
        assertEquals(0x10000L, part!!.sourceOffset)
        // 24-byte header + one 16-byte segment, a checksum byte, a 16-aligned
        // body and the appended SHA-256 — never the 3 MB the partition declares.
        assertEquals(96L, part.copySize)
    }

    @Test
    fun `an image without an appended hash ends at its checksum`() {
        val image = mergedImage(appOffset = 0x10000, hashAppended = false, tailPadding = 0)
        val part = OtaManifest.appPart(image)
        assertEquals(0x10000L, part?.sourceOffset)
        assertEquals(64L, part?.copySize)
    }

    @Test
    fun `the length never runs past the end of the file`() {
        // A table that declares more than the file holds: the deck would answer
        // "Manifest range exceeds file" if the manifest repeated the declaration.
        val image = mergedImage(appOffset = 0x10000, hashAppended = false, tailPadding = 0)
        val truncated = image.copyOf(image.size - 10)
        val part = OtaManifest.appPart(truncated)
        assertNotNull(part)
        assertEquals((truncated.size - 0x10000).toLong(), part!!.copySize)
    }

    @Test
    fun `a bare app image is taken whole`() {
        val app = espImage(hashAppended = true)
        val file = app + ByteArray(512) { 0x7F.toByte() }
        val part = OtaManifest.appPart(file)
        assertEquals(0L, part?.sourceOffset)
        assertEquals(app.size.toLong(), part?.copySize)
    }

    @Test
    fun `a file with no app image at all has no part`() {
        assertNull(OtaManifest.appPart(ByteArray(64)))
        // A partition table whose entries are all data partitions.
        val table = partitionTable(entries = listOf(Entry(0x01, 0x02, 0x9000, 0x6000, "nvs")))
        assertNull(OtaManifest.appPart(ByteArray(0x10000) { 0xFF.toByte() }.also { table.copyInto(it, 0x8000) }))
    }

    @Test
    fun `the manifest names one app part and the file it comes from`() {
        val image = mergedImage(appOffset = 0x10000, hashAppended = true, tailPadding = 0)
        val json = OtaManifest.json("Attaky_bm_launcher_v0.1.0_A001.bin", image)
        assertNotNull(json)

        val manifest = JSONObject(json!!)
        assertEquals("Attaky_bm_launcher_v0.1.0_A001.bin", manifest.getString("sourceName"))
        val parts = manifest.getJSONArray("parts")
        assertEquals(1, parts.length())
        val app = parts.getJSONObject(0)
        assertEquals("app", app.getString("kind"))
        assertEquals(0, app.getInt("subtype"))
        assertEquals(0x10000L, app.getLong("sourceOffset"))
        assertEquals(96L, app.getLong("copySize"))
        assertEquals("the deck sizes the slot from declaredSize", 96L, app.getLong("declaredSize"))
    }

    @Test
    fun `an ota subtype is the app partition too`() {
        val image = mergedImage(appOffset = 0x20000, hashAppended = true, tailPadding = 0, subtype = 0x10)
        val part = OtaManifest.appPart(image)
        assertEquals(0x20000L, part?.sourceOffset)
        assertEquals(96L, part?.copySize)
    }

    /**
     * The real thing: the merged images the store writes, as bundled with the
     * app. They end inside the ESP image's trailing padding — the store writes
     * parts, not esptool's filler — which is exactly the case a strict length
     * calculation gets wrong and turns into a manifest the deck refuses.
     */
    @Test
    fun `a real merged image resolves to its app part`() {
        val assets = java.io.File("src/main/assets/www/firmware")
        Assume.assumeTrue("the bundled merged images are available", assets.isDirectory)
        for (name in listOf("attaky-launcher-1.1.0-merged.bin", "attaky-dj-scratch-0.1.0-merged.bin")) {
            val file = java.io.File(assets, name)
            Assume.assumeTrue("$name is bundled", file.isFile)
            val image = file.readBytes()

            val part = OtaManifest.appPart(image)
            assertNotNull("$name has an app part", part)
            assertEquals("$name app offset", 0x10000L, part!!.sourceOffset)
            // The app is the last part, so it runs to the end of the merged file.
            assertEquals("$name app length", image.size.toLong() - 0x10000L, part.copySize)
            assertEquals(
                "$name app part has to start with an ESP image",
                0xE9,
                image[part.sourceOffset.toInt()].toInt() and 0xFF,
            )
        }
    }

    // ------------------------------------------------------------------ fixtures

    private data class Entry(
        val type: Int,
        val subtype: Int,
        val offset: Int,
        val size: Int,
        val label: String,
    )

    /** A merged image: partition table, then an ESP image at the app offset. */
    private fun mergedImage(
        appOffset: Int,
        hashAppended: Boolean,
        tailPadding: Int,
        subtype: Int = 0x00,
    ): ByteArray {
        val app = espImage(hashAppended)
        val size = appOffset + app.size + tailPadding
        val image = ByteArray(size) { 0xFF.toByte() }
        partitionTable(
            entries = listOf(
                Entry(0x01, 0x02, 0x9000, 0x6000, "nvs"),
                Entry(0x00, subtype, appOffset, 0x300000, "app0"),
            ),
        ).copyInto(image, 0x8000)
        app.copyInto(image, appOffset)
        return image
    }

    private fun partitionTable(entries: List<Entry>): ByteArray {
        val table = ByteArray(0x1000) { 0xFF.toByte() }
        table[0] = 0xAA.toByte()
        table[1] = 0x50
        table[2] = 0x01
        entries.forEachIndexed { index, entry ->
            val at = index * 0x20
            table[at + 2] = entry.type.toByte()
            table[at + 3] = entry.subtype.toByte()
            le32(entry.offset).copyInto(table, at + 4)
            le32(entry.size).copyInto(table, at + 8)
            entry.label.toByteArray().copyInto(table, at + 12)
        }
        // 0xEBEB end marker.
        val end = entries.size * 0x20
        table[end + 2] = 0xEB.toByte()
        table[end + 3] = 0xEB.toByte()
        return table
    }

    /**
     * One 16-byte segment, so the image measures 96 bytes with a hash and 64
     * without: 24 header + 8 + 16 data = 48, +1 checksum, +32 hash, 16-aligned.
     */
    private fun espImage(hashAppended: Boolean): ByteArray {
        val body = ByteArray(48)
        body[0] = 0xE9.toByte()
        body[1] = 1
        body[23] = if (hashAppended) 1 else 0
        le32(0x40080000).copyInto(body, 24)
        le32(16).copyInto(body, 28)
        for (i in 0 until 16) body[32 + i] = 0x5A
        val end = if (hashAppended) 96 else 64
        return body.copyOf(end)
    }

    private fun le32(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    @Test
    fun `the fixture measures what the test claims`() {
        assertTrue(espImage(true).size == 96)
        assertTrue(espImage(false).size == 64)
        assertEquals(96, OtaManifest.espImageSize(espImage(true), 0))
        assertEquals(64, OtaManifest.espImageSize(espImage(false), 0))
    }
}
