package com.attaky.flasher

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.Deflater

/**
 * A focused native port of the esptool ROM-bootloader protocol, enough to write
 * a merged firmware image to the ESP32-S3 on the Attaky deck.
 *
 * Everything here is blocking and runs on a worker thread. The transport is the
 * USB-serial bridge via [UsbSerialService] in raw mode ([UsbSerialService.openRaw]
 * + [UsbSerialService.readRaw]); the background WebView read loop is not used, so
 * this class owns the byte stream for the length of a session.
 *
 * No flasher stub is uploaded: the ROM bootloader itself erases and writes the
 * flash, which is slower than the stub but needs no extra binary and touches the
 * chip as little as possible.
 */
class EspFlasher(private val usb: UsbSerialService) {

    /** Thrown when the user stops a flash; the caller decides what to report. */
    class CancelledException : IOException("Flashing stopped")

    /** Thrown when the ROM repeats the invalid-message response. */
    private class UnsupportedCommandException : IOException("Unsupported command")

    data class ChipInfo(
        val name: String,
        val mac: String,
    )

    // ------------------------------------------------------------------ constants

    private companion object {
        const val ESP_SYNC = 0x08
        const val ESP_FLASH_BEGIN = 0x02
        const val ESP_FLASH_DATA = 0x03
        const val ESP_READ_REG = 0x0a
        const val ESP_CHANGE_BAUDRATE = 0x0f
        const val ESP_FLASH_DEFL_BEGIN = 0x10
        const val ESP_FLASH_DEFL_DATA = 0x11
        const val ESP_SPI_FLASH_MD5 = 0x13

        const val ESP_CHECKSUM_MAGIC = 0xef
        const val ROM_INVALID_RECV_MSG = 0x05

        const val SLIP_END = 0xc0
        const val SLIP_ESC = 0xdb
        const val SLIP_ESC_END = 0xdc
        const val SLIP_ESC_ESC = 0xdd

        /** ROM write block for the ESP32-S3 (matches esptool's ROM value). */
        const val FLASH_WRITE_SIZE = 0x400

        /** Bytes erased and written per FlashBegin cycle (16 flash sectors). */
        const val WRITE_CHUNK_SIZE = 0x10000

        const val ESP_FLASH_END = 0x04

        const val CHIP_DETECT_MAGIC_REG = 0x40001000L
        const val ESP32S3_MAGIC = 0x00000009L
        const val ESP32S3_CHIP_NAME = "ESP32-S3"

        /** EFUSE_BASE + 0x44, the first MAC word on the ESP32-S3. */
        const val MAC_EFUSE_REG = 0x60007044L

        /**
         * Erase budget. With 64 KB chunks this only has to cover an erase of 16
         * sectors, so 30 s/MB (esptool's ROM figure) and a 15 s floor is plenty
         * and fails fast when the deck really has stopped answering.
         */
        const val ERASE_TIMEOUT_PER_MB = 30000L

        /** Reset styles to try, most likely first (see [connect]). */
        private val UART_FIRST = listOf(ResetStyle.CLASSIC, ResetStyle.INVERTED, ResetStyle.USB_JTAG)
        private val USB_FIRST = listOf(ResetStyle.USB_JTAG, ResetStyle.CLASSIC, ResetStyle.INVERTED)
        const val WRITE_TIMEOUT_PER_MB = 40000L
        const val MD5_TIMEOUT_PER_MB = 8000L
    }

    /** Leftover bytes read from the port but not yet consumed. */
    private var buffer = ByteArray(0)

    /** The reset style that got the deck talking; reused for the final reset. */
    @Volatile
    private var workingStyle: ResetStyle? = null

    // ------------------------------------------------------------------ session

    /**
     * Get the deck into its ROM bootloader and read its chip info.
     *
     * Syncs first in case the deck is already in the loader (no reset, no
     * reboot), then resets with the sequence the attached silicon understands —
     * at most once per style, so a failed connect cannot leave the board
     * restarting over and over.
     */
    fun connect(baudRate: Int, log: (String) -> Unit): ChipInfo {
        log("Connecting to the deck at $baudRate baud…")

        // A deck already sitting in the ROM loader — left there by an earlier
        // attempt, or one flashed moments ago — answers without any reset, so
        // nothing restarts the board when there is no need to.
        identifyLoaded(1500)?.let { info ->
            log("Detected ${info.name} (already in the loader).")
            return info
        }

        // There are three ways a board responds to DTR/RTS, and the deck does
        // not announce which one it uses. Try each once, most likely first:
        //   • the S3's own USB serial/JTAG decodes DTR/RTS itself;
        //   • a CH340/CP210x auto-reset circuit drives EN from RTS and IO0 from
        //     DTR (esptool's standard wiring);
        //   • some boards wire those two the other way round.
        // Each style ends with the board out of reset, so a miss is just one
        // restart, not the seven that looked like a reboot loop.
        val order = if (usb.isUsbJtagLoader()) USB_FIRST else UART_FIRST

        var lastError: String? = null
        for (style in order) {
            try {
                log("Resetting with the ${style.label} sequence…")
                reset(style)
                flushInput()
                sync(1500)
                val info = identify()
                workingStyle = style
                log("Detected ${info.name} (${style.label} reset).")
                return info
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
                log("No answer after the ${style.label} reset: $lastError")
                sleep(80)
            }
        }

        // Never leave the board parked in reset after a failed connect.
        runCatching { hardReset() }
        throw IOException(
            "Could not sync with the deck. Tried ${order.size} reset styles; last said: " +
                "${lastError ?: "no answer"}",
        )
    }

    /** Identify without touching the reset lines; null when not in the loader. */
    private fun identifyLoaded(timeoutMs: Int): ChipInfo? = try {
        flushInput()
        sync(timeoutMs)
        identify()
    } catch (t: Throwable) {
        null
    }

    /**
     * Drop the chip back into the running application: pulse reset without
     * pulling IO0 low, using whichever line drives EN on this board.
     */
    fun hardReset() {
        val style = workingStyle ?: if (usb.isUsbJtagLoader()) ResetStyle.USB_JTAG else ResetStyle.CLASSIC
        runCatching {
            when (style) {
                ResetStyle.INVERTED -> usb.setSignals(dtr = true, rts = false, breakSignal = null)
                else -> usb.setSignals(dtr = false, rts = true, breakSignal = null)
            }
        }
        sleep(120)
        runCatching { usb.setSignals(dtr = false, rts = false, breakSignal = null) }
        sleep(50)
    }

    /**
     * Write [image] at [offset]. [progress] receives 0..100. [isCancelled] is
     * polled between blocks so a flash can be stopped without unplugging.
     */
    fun writeFirmware(
        offset: Long,
        image: ByteArray,
        compress: Boolean,
        verify: Boolean,
        log: (String) -> Unit,
        progress: (Int) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val padded = padTo(image, 4)
        val uncsize = padded.size
        if (uncsize == 0) {
            log("The image is empty; nothing to write.")
            return
        }

        val chunks = (uncsize + WRITE_CHUNK_SIZE - 1) / WRITE_CHUNK_SIZE
        if (compress && chunks > 1) {
            log(
                "The image needs $chunks chunks, so it is written uncompressed:" +
                    " one long compressed erase stalls on the ROM loader.",
            )
        }

        if (compress && chunks == 1) {
            val packed = deflate(padded)
            log("Compressed $uncsize bytes to ${packed.size} bytes.")
            flashDeflBegin(uncsize, packed.size, offset, log)
            var seq = 0
            var sent = 0
            while (sent < packed.size) {
                if (isCancelled()) throw CancelledException()
                val n = minOf(FLASH_WRITE_SIZE, packed.size - sent)
                val block = packed.copyOfRange(sent, sent + n)
                flashDeflBlock(block, seq)
                sent += n
                seq++
                progress((sent * 100) / packed.size)
            }
            flashFinish(stayInLoader = true)
        } else {
            // Erase and write a chunk at a time. One FlashBegin for a whole
            // image makes the ROM erase everything in a single long call, which
            // on this deck never comes back; short chunks answer straight away
            // and the progress bar actually moves.
            if (chunks > 1) {
                log("Writing in $chunks chunks of ${WRITE_CHUNK_SIZE / 1024} KB…")
            }
            var done = 0
            while (done < uncsize) {
                if (isCancelled()) throw CancelledException()
                val end = minOf(done + WRITE_CHUNK_SIZE, uncsize)
                val chunk = padded.copyOfRange(done, end)
                flashBegin(chunk.size, offset + done, log)
                var seq = 0
                var sent = 0
                while (sent < chunk.size) {
                    if (isCancelled()) throw CancelledException()
                    val n = minOf(FLASH_WRITE_SIZE, chunk.size - sent)
                    var block = chunk.copyOfRange(sent, sent + n)
                    if (block.size < FLASH_WRITE_SIZE) {
                        block = padTo(block, FLASH_WRITE_SIZE, 0xff)
                    }
                    flashBlock(block, seq)
                    sent += n
                    seq++
                    progress(((done + sent) * 100) / uncsize)
                }
                done = end
                flashFinish(stayInLoader = true)
            }
        }

        progress(100)
        log("Wrote $uncsize bytes at 0x${offset.toString(16)}.")

        if (verify) verify(offset, padded, log)
    }

    // ------------------------------------------------------------------ protocol

    private fun identify(): ChipInfo {
        val magic = readReg(CHIP_DETECT_MAGIC_REG)
        val name = when (magic) {
            ESP32S3_MAGIC -> ESP32S3_CHIP_NAME
            else -> "ESP32-S3 (unexpected magic 0x${magic.toString(16)})"
        }
        val mac = readMac()
        return ChipInfo(name, mac)
    }

    private fun readMac(): String {
        return try {
            val mac0 = readReg(MAC_EFUSE_REG)
            val mac1 = readReg(MAC_EFUSE_REG + 4) and 0x0000ffffL
            val bytes = intArrayOf(
                ((mac1 ushr 8) and 0xff).toInt(),
                (mac1 and 0xff).toInt(),
                ((mac0 ushr 24) and 0xff).toInt(),
                ((mac0 ushr 16) and 0xff).toInt(),
                ((mac0 ushr 8) and 0xff).toInt(),
                (mac0 and 0xff).toInt(),
            )
            bytes.joinToString(":") { "%02x".format(it) }
        } catch (t: Throwable) {
            "unknown"
        }
    }

    private fun readReg(addr: Long, timeoutMs: Int = 3000): Long {
        val response = command(ESP_READ_REG, int32(addr), 0, timeoutMs)
        return response.value
    }

    /** Optionally lift the link to [target] baud; falls back to 115200 on doubt. */
    fun changeBaud(target: Int, log: (String) -> Unit) {
        if (target <= 0) return
        if (usb.isUsbJtagLoader()) {
            // The link is USB, not a UART: there is no baud rate to raise.
            log("Native USB: the baud rate does not apply, staying as is.")
            return
        }
        try {
            command(ESP_CHANGE_BAUDRATE, int32(target.toLong()) + int32(0), 0, 3000)
            sleep(50)
            usb.setBaudRate(target)
            flushInput()
            sleep(50)
            readReg(CHIP_DETECT_MAGIC_REG, 1000)
            log("Baud rate raised to $target.")
        } catch (t: Throwable) {
            log("Could not use $target baud (${t.message}); staying at 115200.")
            runCatching { usb.setBaudRate(115200) }
            flushInput()
            runCatching { sync(1500) }
        }
    }

    private fun flashBegin(size: Int, offset: Long, log: (String) -> Unit) {
        val blocks = (size + FLASH_WRITE_SIZE - 1) / FLASH_WRITE_SIZE
        log("Erasing ${size} bytes at 0x${offset.toString(16)}…")
        val started = System.currentTimeMillis()
        val pkt = int32(size.toLong()) +
            int32(blocks.toLong()) +
            int32(FLASH_WRITE_SIZE.toLong()) +
            int32(offset) +
            int32(0)
        try {
            checkCommand(
                "enter Flash download mode",
                ESP_FLASH_BEGIN,
                pkt,
                0,
                0,
                eraseTimeout(size.toLong()),
            )
        } catch (t: IOException) {
            throw IOException("Erase did not finish: ${t.message}", t)
        }
        log("Erased in ${secondsSince(started)} s.")
    }

    /** Close the write cycle so the ROM accepts the next FlashBegin. */
    private fun flashFinish(stayInLoader: Boolean) {
        runCatching {
            checkCommand(
                "leave Flash download mode",
                ESP_FLASH_END,
                int32(if (stayInLoader) 1L else 0L),
                0,
                0,
                2000,
            )
        }
    }

    private fun flashBlock(data: ByteArray, seq: Int) {
        val pkt = int32(data.size.toLong()) +
            int32(seq.toLong()) +
            int32(0) +
            int32(0) +
            data
        val timeout = maxOf(3000L, timeoutPerMb(WRITE_TIMEOUT_PER_MB, data.size.toLong()))
        checkCommand("write to target flash after seq $seq", ESP_FLASH_DATA, pkt, checksum(data), 0, timeout)
    }

    private fun flashDeflBegin(size: Int, compsize: Int, offset: Long, log: (String) -> Unit) {
        val blocks = (compsize + FLASH_WRITE_SIZE - 1) / FLASH_WRITE_SIZE
        val eraseBlocks = (size + FLASH_WRITE_SIZE - 1) / FLASH_WRITE_SIZE
        val writeSize = eraseBlocks * FLASH_WRITE_SIZE
        log("Erasing ${writeSize} bytes at 0x${offset.toString(16)}…")
        val started = System.currentTimeMillis()
        val pkt = int32(writeSize.toLong()) +
            int32(blocks.toLong()) +
            int32(FLASH_WRITE_SIZE.toLong()) +
            int32(offset) +
            int32(0)
        try {
            checkCommand(
                "enter compressed flash mode",
                ESP_FLASH_DEFL_BEGIN,
                pkt,
                0,
                0,
                eraseTimeout(writeSize.toLong()),
            )
        } catch (t: IOException) {
            throw IOException("Erase did not finish: ${t.message}", t)
        }
        log("Erased in ${secondsSince(started)} s.")
    }

    /** Erase waits are per chunk now, so they can be tight without stalling. */
    private fun eraseTimeout(sizeBytes: Long): Long =
        maxOf(15000L, timeoutPerMb(ERASE_TIMEOUT_PER_MB, sizeBytes))

    private fun secondsSince(startedAt: Long): String =
        "%.1f".format((System.currentTimeMillis() - startedAt) / 1000.0)

    private fun flashDeflBlock(data: ByteArray, seq: Int) {
        val pkt = int32(data.size.toLong()) +
            int32(seq.toLong()) +
            int32(0) +
            int32(0) +
            data
        val timeout = maxOf(3000L, timeoutPerMb(WRITE_TIMEOUT_PER_MB, data.size.toLong()))
        checkCommand(
            "write compressed data to flash after seq $seq",
            ESP_FLASH_DEFL_DATA,
            pkt,
            checksum(data),
            0,
            timeout,
        )
    }

    private fun verify(offset: Long, image: ByteArray, log: (String) -> Unit) {
        try {
            val expected = md5Hex(image)
            val pkt = int32(offset) + int32(image.size.toLong()) + int32(0) + int32(0)
            val data = checkCommand(
                "calculate flash md5",
                ESP_SPI_FLASH_MD5,
                pkt,
                0,
                32,
                timeoutPerMb(MD5_TIMEOUT_PER_MB, image.size.toLong()),
            )
            val actual = String(data, Charsets.US_ASCII).trim()
            if (!actual.equals(expected, ignoreCase = true)) {
                throw IOException("MD5 of file does not match data in flash!")
            }
            log("Hash of data verified.")
        } catch (t: IOException) {
            if (t.message?.contains("does not match") == true) throw t
            log("Verification skipped: ${t.message}")
        }
    }

    // ------------------------------------------------------------- framing / io

    private class Response(val value: Long, val data: ByteArray)

    private fun command(op: Int, data: ByteArray, checksum: Long, timeoutMs: Int): Response {
        flushInput()
        val pkt = ByteArray(8 + data.size)
        pkt[0] = 0
        pkt[1] = op.toByte()
        putInt32(pkt, 2, data.size.toLong(), 2)
        putInt32(pkt, 4, checksum, 4)
        data.copyInto(pkt, 8)
        usb.write(slipEncode(pkt))
        return readPacket(op, timeoutMs)
    }

    private fun checkCommand(
        description: String,
        op: Int,
        data: ByteArray,
        checksum: Long,
        responseLength: Int,
        timeoutMs: Long,
    ): ByteArray {
        val response = command(op, data, checksum, timeoutMs.toInt())
        val statusBytesLength = 2
        if (response.data.size < responseLength + statusBytesLength) {
            val status = response.data.take(2)
            if (status.isNotEmpty() && status[0].toInt() != 0) {
                throw IOException("Failed to $description (status ${status.toList()})")
            }
            throw IOException("Failed to $description: only got ${response.data.size} bytes of data.")
        }
        val status = response.data[responseLength]
        if (status.toInt() != 0) {
            throw IOException("Failed to $description (status $status)")
        }
        return if (responseLength > 0) {
            response.data.copyOfRange(0, responseLength)
        } else {
            ByteArray(0)
        }
    }

    private fun readPacket(op: Int?, timeoutMs: Int): Response {
        // One budget for the whole call: a stray packet from an earlier command
        // must not restart the timer, which turned a 15 s erase wait into
        // hundreds of seconds of apparent hang.
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = (deadline - System.currentTimeMillis()).toInt()
            if (remaining <= 0) throw IOException("Invalid response from the deck.")
            val packet = readSlipPacket(remaining)
            if (packet.size < 8) continue
            if ((packet[0].toInt() and 0xff) != 1) continue
            val opRet = packet[1].toInt() and 0xff
            val value = leInt32(packet, 4)
            val data = packet.copyOfRange(8, packet.size)
            if (op == null || opRet == op) return Response(value, data)
            if (data.size >= 2 && (data[0].toInt() and 0xff) != 0 &&
                (data[1].toInt() and 0xff) == ROM_INVALID_RECV_MSG
            ) {
                throw UnsupportedCommandException()
            }
        }
        throw IOException("Invalid response from the deck.")
    }

    /** Read one SLIP-framed packet, skipping any noise before the opening 0xC0. */
    private fun readSlipPacket(timeoutMs: Int): ByteArray {
        val deadline = System.currentTimeMillis() + timeoutMs
        var packet: ByteArrayOutputStream? = null
        var escaping = false
        var index = 0
        while (true) {
            if (index < buffer.size) {
                val b = buffer[index].toInt() and 0xff
                if (packet == null) {
                    if (b == SLIP_END) {
                        packet = ByteArrayOutputStream()
                    }
                    index++
                } else if (escaping) {
                    escaping = false
                    when (b) {
                        SLIP_ESC_END -> packet.write(SLIP_END)
                        SLIP_ESC_ESC -> packet.write(SLIP_ESC)
                        else -> throw IOException("Invalid SLIP escape (0xdb, 0x%02x)".format(b))
                    }
                    index++
                } else if (b == SLIP_ESC) {
                    escaping = true
                    index++
                } else if (b == SLIP_END) {
                    val out = packet.toByteArray()
                    buffer = buffer.copyOfRange(index + 1, buffer.size)
                    return out
                } else {
                    packet.write(b)
                    index++
                }
            } else {
                buffer = ByteArray(0)
                index = 0
                val remaining = (deadline - System.currentTimeMillis()).toInt()
                if (remaining <= 0) {
                    throw IOException(
                        when {
                            packet != null -> "Timed out waiting for the deck."
                            // Open port, silent line: either the deck stopped
                            // answering or it left the bus entirely (a reset
                            // re-enumerates native USB). Those need different
                            // fixes, so they read differently here.
                            !usb.isDeviceAttached() ->
                                "The deck left USB (it reset or was unplugged). Reconnect it, then tap CONNECT."
                            else -> "No serial data received."
                        },
                    )
                }
                val tmp = ByteArray(4096)
                val n = usb.readRaw(tmp, minOf(remaining, 500))
                if (n > 0) buffer = tmp.copyOf(n)
            }
        }
    }

    private fun sync(timeoutMs: Int) {
        val cmd = ByteArray(36)
        cmd[0] = 0x07
        cmd[1] = 0x07
        cmd[2] = 0x12
        cmd[3] = 0x20
        for (i in 4 until 36) cmd[i] = 0x55

        // The ROM may still be booting when the first frame arrives, so keep
        // asking until the deadline rather than resetting the board again.
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                command(ESP_SYNC, cmd, 0, 400)
                // The ROM answers a sync with a short burst; drain what is there.
                repeat(7) { runCatching { readPacket(ESP_SYNC, 120) } }
                return
            } catch (t: Throwable) {
                lastError = t
                sleep(60)
            }
        }
        throw IOException(lastError?.message ?: "The deck did not answer the sync.")
    }

    /** Which reset dance the attached board understands. */
    private enum class ResetStyle(val label: String) {
        CLASSIC("UART (EN on RTS)"),
        INVERTED("UART (EN on DTR)"),
        USB_JTAG("USB-JTAG"),
    }

    private fun reset(style: ResetStyle) {
        when (style) {
            ResetStyle.CLASSIC -> classicReset(inverted = false)
            ResetStyle.INVERTED -> classicReset(inverted = true)
            ResetStyle.USB_JTAG -> usbJtagReset()
        }
        // The ROM needs a moment to come up before the first sync frame.
        sleep(150)
    }

    /**
     * EN/IO0 pulse for a bridge wired to an auto-reset circuit. [inverted] swaps
     * which DTR/RTS line drives EN, for boards that wire it the other way round.
     */
    private fun classicReset(inverted: Boolean) {
        val io0Low = !inverted // DTR pulls IO0 low on the standard wiring
        usb.setSignals(dtr = !io0Low, rts = io0Low, breakSignal = null) // EN low
        sleep(100)
        usb.setSignals(dtr = io0Low, rts = !io0Low, breakSignal = null) // IO0 low, EN high
        sleep(50)
        usb.setSignals(dtr = false, rts = false, breakSignal = null) // release IO0
        sleep(50)
    }

    /**
     * The ESP32-S3 USB serial/JTAG has no EN/IO0 pins to pulse: its peripheral
     * watches the DTR/RTS pattern and resets the chip from it. This is esptool's
     * sequence, including the repeated RTS edge Windows needs to propagate it.
     */
    private fun usbJtagReset() {
        usb.setSignals(dtr = false, rts = false, breakSignal = null)
        sleep(100)
        usb.setSignals(dtr = true, rts = false, breakSignal = null)
        sleep(100)
        usb.setSignals(dtr = true, rts = true, breakSignal = null)
        usb.setSignals(dtr = false, rts = true, breakSignal = null)
        usb.setSignals(dtr = false, rts = true, breakSignal = null)
        sleep(100)
        usb.setSignals(dtr = false, rts = false, breakSignal = null)
        sleep(100)
    }

    private fun flushInput() {
        buffer = ByteArray(0)
        runCatching { usb.purgeInput() }
    }

    private fun slipEncode(payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 2)
        out.write(SLIP_END)
        for (byte in payload) {
            val b = byte.toInt() and 0xff
            when (b) {
                SLIP_END -> {
                    out.write(SLIP_ESC)
                    out.write(SLIP_ESC_END)
                }
                SLIP_ESC -> {
                    out.write(SLIP_ESC)
                    out.write(SLIP_ESC_ESC)
                }
                else -> out.write(b)
            }
        }
        out.write(SLIP_END)
        return out.toByteArray()
    }

    // --------------------------------------------------------------- utilities

    private fun checksum(data: ByteArray): Long {
        var state = ESP_CHECKSUM_MAGIC
        for (byte in data) state = state xor (byte.toInt() and 0xff)
        return state.toLong()
    }

    private fun md5Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, false)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size / 2 + 64)
        val chunk = ByteArray(16384)
        while (!deflater.finished()) {
            val n = deflater.deflate(chunk)
            if (n > 0) out.write(chunk, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun padTo(data: ByteArray, alignment: Int, pad: Int = 0xff): ByteArray {
        val mod = data.size % alignment
        if (mod == 0) return data
        val out = ByteArray(data.size + alignment - mod)
        data.copyInto(out)
        for (i in data.size until out.size) out[i] = pad.toByte()
        return out
    }

    private fun timeoutPerMb(secondsPerMb: Long, sizeBytes: Long): Long {
        val result = secondsPerMb * sizeBytes / 1_000_000L
        return maxOf(3000L, result)
    }

    private fun int32(value: Long): ByteArray {
        val out = ByteArray(4)
        putInt32(out, 0, value, 4)
        return out
    }

    private fun putInt32(dst: ByteArray, offset: Int, value: Long, count: Int) {
        for (i in 0 until count) {
            dst[offset + i] = ((value ushr (8 * i)) and 0xff).toByte()
        }
    }

    private fun leInt32(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 4) {
            value = value or ((bytes[offset + i].toLong() and 0xff) shl (8 * i))
        }
        return value
    }

    private fun sleep(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private operator fun ByteArray.plus(other: ByteArray): ByteArray {
        val out = ByteArray(size + other.size)
        copyInto(out)
        other.copyInto(out, size)
        return out
    }
}
