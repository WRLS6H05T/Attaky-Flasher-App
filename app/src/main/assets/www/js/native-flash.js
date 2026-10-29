/*
 * native-flash.js — the flash engine behind the native flasher screen.
 *
 * The native screen (Kotlin, see JsFlasher.kt) draws the buttons and the console,
 * but the deck is driven by exactly what the store pages use: the vendored
 * esptool-js build (vendor/esptool-js.js) over window.AttakyUSB. That is the
 * point of this file — one implementation of the ROM/stub protocol, so a flash
 * here behaves like "Flash Online" on fwstore.attaky.com, with the same reset
 * sequences, the same stub loader, the same per-MB timeouts.
 *
 * The page is never shown. Kotlin loads it off-screen, hands over the USB port
 * and the merged image (in chunks, so no multi-megabyte strings cross the
 * bridge), and reads back the log, the progress and the result.
 *
 * Kotlin -> page
 *   window.__attakyEngine.connect({baud})
 *   window.__attakyEngine.flash({offset, compress, verify})
 *   window.__attakyEngine.disconnect()
 *
 * Page -> Kotlin (window.AttakyNative)
 *   ready()                        scripts are loaded
 *   portJson()                    {index,vid,pid,…} of the deck to open
 *   imageSize() / imageChunk() / imageReset()
 *   log(line) / progress(percent)
 *   connected({chip, mac})        connect finished
 *   failed(where, message)        connect failed
 *   done(ok, message)             flash finished
 */
(function () {
  "use strict";

  var out = window.AttakyNative;
  if (!out) return;

  var esptool = window.esptool;
  var serial = window.attakySerial;
  if (!esptool || typeof esptool.ESPLoader !== "function" || !serial) {
    out.log("The bundled esptool-js did not load, so flashing is unavailable.");
    return;
  }

  var state = {
    loader: null,
    transport: null,
    port: null,
  };

  // --------------------------------------------------------------- byte helpers

  function decodeBase64(value) {
    var binary = atob(value);
    var bytes = new Uint8Array(binary.length);
    for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return bytes;
  }

  function describe(error) {
    return error && error.message ? error.message : String(error);
  }

  // ------------------------------------------------------------------------ md5

  // esptool-js verifies a written image against the MD5 it is handed, and Web
  // Crypto has no MD5, so the engine brings its own (RFC 1321).
  var MD5_SHIFT = [
    7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
    5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
    4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
    6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
  ];

  var MD5_K = (function () {
    var table = [];
    for (var i = 0; i < 64; i++) {
      table[i] = Math.floor(Math.abs(Math.sin(i + 1)) * 4294967296) | 0;
    }
    return table;
  })();

  function md5Hex(bytes) {
    var length = bytes.length;
    var blocks = ((length + 8) >> 6) + 1;
    var padded = new Uint8Array(blocks * 64);
    padded.set(bytes);
    padded[length] = 0x80;

    // Message length in bits, little endian, as two 32-bit halves.
    var bitsLow = (length << 3) >>> 0;
    var bitsHigh = Math.floor(length / 536870912) >>> 0;
    var tail = padded.length - 8;
    for (var b = 0; b < 4; b++) {
      padded[tail + b] = (bitsLow >>> (8 * b)) & 0xff;
      padded[tail + 4 + b] = (bitsHigh >>> (8 * b)) & 0xff;
    }

    var a0 = 0x67452301;
    var b0 = 0xefcdab89;
    var c0 = 0x98badcfe;
    var d0 = 0x10325476;
    var words = [];

    for (var offset = 0; offset < padded.length; offset += 64) {
      for (var w = 0; w < 16; w++) {
        var at = offset + w * 4;
        words[w] = padded[at] | (padded[at + 1] << 8) | (padded[at + 2] << 16) | (padded[at + 3] << 24);
      }

      var a = a0;
      var bb = b0;
      var c = c0;
      var d = d0;

      for (var k = 0; k < 64; k++) {
        var f;
        var g;
        if (k < 16) {
          f = (bb & c) | (~bb & d);
          g = k;
        } else if (k < 32) {
          f = (d & bb) | (~d & c);
          g = (5 * k + 1) % 16;
        } else if (k < 48) {
          f = bb ^ c ^ d;
          g = (3 * k + 5) % 16;
        } else {
          f = c ^ (bb | ~d);
          g = (7 * k) % 16;
        }
        var sum = (a + f + MD5_K[k] + words[g]) | 0;
        var rotated = (sum << MD5_SHIFT[k]) | (sum >>> (32 - MD5_SHIFT[k]));
        a = d;
        d = c;
        c = bb;
        bb = (bb + rotated) | 0;
      }

      a0 = (a0 + a) | 0;
      b0 = (b0 + bb) | 0;
      c0 = (c0 + c) | 0;
      d0 = (d0 + d) | 0;
    }

    var hex = "";
    var values = [a0, b0, c0, d0];
    for (var v = 0; v < 4; v++) {
      for (var byte = 0; byte < 4; byte++) {
        var nibble = (values[v] >>> (8 * byte)) & 0xff;
        hex += (nibble < 16 ? "0" : "") + nibble.toString(16);
      }
    }
    return hex;
  }

  // -------------------------------------------------------------------- terminal

  var terminal = {
    clean: function () {},
    writeLine: function (line) {
      out.log(line == null ? "" : String(line));
    },
    write: function (line) {
      var text = line == null ? "" : String(line).replace(/\s+$/, "");
      if (text) out.log(text);
    },
  };

  // ---------------------------------------------------------------------- image

  /** Pull the merged image out of the app, chunk by chunk. */
  function readImage() {
    var size = out.imageSize();
    if (!size) throw new Error("No image to write.");
    out.imageReset();
    var image = new Uint8Array(size);
    var position = 0;
    while (position < size) {
      var chunk = out.imageChunk();
      if (!chunk) break;
      var bytes = decodeBase64(chunk);
      image.set(bytes, position);
      position += bytes.length;
    }
    if (position !== size) {
      throw new Error("Only " + position + " of " + size + " image bytes arrived from the app.");
    }
    return image;
  }

  // -------------------------------------------------------------------- session

  function chipIdentity() {
    try {
      var chip = state.loader && state.loader.chip;
      if (chip && typeof chip.readMac === "function") {
        return Promise.resolve(chip.readMac(state.loader)).then(
          function (mac) {
            return String(mac || "");
          },
          function () {
            return "";
          },
        );
      }
    } catch (e) {
      /* no MAC is fine */
    }
    return Promise.resolve("");
  }

  function sleep(ms) {
    return new Promise(function (resolve) {
      setTimeout(resolve, ms);
    });
  }

  /**
   * Leave the chip running the firmware that was just written.
   *
   * esptool's own `after("hard_reset")` only *releases* the reset line — it
   * assumes it is still asserted from the download-mode entry. After a write the
   * line is already idle, so on its own it pulses nothing and the deck stays in
   * the flasher stub until it is unplugged. So: let esptool do its reset (it
   * knows the USB-OTG settle times), then drive EN directly — assert, hold,
   * release — which is the same pulse a board's auto-reset circuit needs. Hold
   * the port open briefly afterwards so the reboot and USB re-enumeration finish
   * before the port closes.
   */
  function resetChip() {
    var transport = state.transport;

    function pulse() {
      if (!transport) return Promise.resolve();
      return Promise.resolve()
        .then(function () {
          return transport.setDTR(false);
        })
        .then(function () {
          return transport.setRTS(true);
        })
        .then(function () {
          return sleep(150);
        })
        .then(function () {
          return transport.setRTS(false);
        })
        .then(function () {
          return transport.setDTR(false);
        })
        .then(
          function () {},
          function () {},
        );
    }

    return Promise.resolve()
      .then(function () {
        if (!state.loader) return;
        return state.loader.after("hard_reset").then(
          function () {},
          function (error) {
            out.log("esptool's own reset did not complete: " + describe(error));
          },
        );
      })
      .then(function () {
        out.log("Resetting the deck into the new firmware…");
        return pulse();
      })
      .then(function () {
        return sleep(600);
      })
      .then(function () {
        out.log("The deck has been reset.");
      });
  }

  function closePort() {
    var port = state.port;
    state.port = null;
    state.transport = null;
    state.loader = null;
    if (!port) return Promise.resolve();
    return Promise.resolve()
      .then(function () {
        return port.close();
      })
      .then(
        function () {},
        function () {},
      );
  }

  /**
   * Open the deck and run esptool's full identification: reset and sync, read the
   * chip description and MAC, then load the flasher stub. From here the chip is
   * driven by the stub, exactly as the store page leaves it.
   */
  function connect(options) {
    var opts = options || {};
    var info = {};
    try {
      info = JSON.parse(out.portJson()) || {};
    } catch (e) {
      info = {};
    }

    state.port = new serial.AndroidSerialPort(info);
    state.transport = new esptool.Transport(state.port, false);
    state.loader = new esptool.ESPLoader({
      transport: state.transport,
      baudrate: opts.baud || 115200,
      terminal: terminal,
    });

    return state.loader.main().then(function (description) {
      return chipIdentity().then(function (mac) {
        out.connected(
          JSON.stringify({
            chip: String(description || ""),
            mac: mac,
          }),
        );
      });
    });
  }

  /**
   * Write the image from Kotlin through the stub loader, then reset the chip.
   *
   * `verify` asks esptool-js to read the flash back and compare an MD5, which is
   * the same verification the store page gets; block checksums are checked
   * either way.
   */
  function flash(options) {
    if (!state.loader) return Promise.reject(new Error("Connect the deck first."));
    var opts = options || {};
    var image = readImage();
    var offset = opts.offset || 0;

    out.log(
      "Image ready: " +
        image.length +
        " bytes at 0x" +
        offset.toString(16) +
        (opts.compress ? " (compressed on the wire)." : "."),
    );

    var request = {
      fileArray: [{ data: image, address: offset }],
      flashSize: "keep",
      flashMode: "keep",
      flashFreq: "keep",
      eraseAll: false,
      compress: !!opts.compress,
      reportProgress: function (fileIndex, written, total) {
        if (!total) return;
        var percent = Math.floor((written * 100) / total);
        out.progress(percent > 100 ? 100 : percent);
      },
    };
    if (opts.verify) request.calculateMD5Hash = md5Hex;

    return state.loader
      .writeFlash(request)
      .then(function () {
        // A reset that cannot complete still leaves a written chip.
        return resetChip();
      });
  }

  // ---------------------------------------------------------------- entry points

  function run(where, work) {
    Promise.resolve()
      .then(work)
      .then(
        function () {
          out.done(true, where, "");
        },
        function (error) {
          var message = describe(error);
          out.log(message);
          out.done(false, where, message);
        },
      );
  }

  window.__attakyEngine = {
    connect: function (json) {
      var options = {};
      try {
        options = JSON.parse(json) || {};
      } catch (e) {
        options = {};
      }
      Promise.resolve()
        .then(function () {
          return connect(options);
        })
        .then(
          function () {},
          function (error) {
            var message = describe(error);
            out.log("Connect failed: " + message);
            return closePort().then(function () {
              out.failed("connect", message);
            });
          },
        );
    },

    flash: function (json) {
      var options = {};
      try {
        options = JSON.parse(json) || {};
      } catch (e) {
        options = {};
      }
      run("flash", function () {
        return flash(options).then(function () {
          return closePort();
        });
      });
    },

    disconnect: function () {
      return closePort();
    },
  };

  out.log("Flash engine ready (esptool-js " + (esptool.VERSION || "bundled") + ").");
  out.ready();
})();
