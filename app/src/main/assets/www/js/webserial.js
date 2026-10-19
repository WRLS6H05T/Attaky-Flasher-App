/*
 * webserial.js — a Web Serial API implementation backed by the Android USB host
 * bridge (`window.AttakyUSB`).
 *
 * esptool-js's Transport class only ever touches a SerialPort object:
 *
 *   port.getInfo()                       -> { usbVendorId, usbProductId }
 *   port.open({ baudRate, dataBits, stopBits, parity, flowControl, bufferSize })
 *   port.close()
 *   port.setSignals({ dataTerminalReady, requestToSend, break })
 *   port.readable  -> { locked, getReader() -> { read(), cancel(), releaseLock() } }
 *   port.writable  -> { locked, getWriter() -> { write(), close(), releaseLock() } }
 *   port.setBaudRate(baud)               -> optional in-place speed change
 *
 * The streams are hand-rolled rather than real ReadableStream/WritableStream
 * instances so that flushOutput()'s writer.close() cannot permanently close the
 * port, and so that a reader cancel always unblocks Transport.waitForUnlock().
 *
 * Exposes `window.attakySerial` and, when the runtime allows it, also patches
 * `navigator.serial` so other Web Serial code would work too.
 */
(function () {
  "use strict";

  var bridge = window.AttakyUSB;
  if (!bridge) {
    console.error("[attaky] window.AttakyUSB bridge is missing — USB flashing disabled.");
  }

  // ------------------------------------------------------------- byte helpers

  function b64ToBytes(b64) {
    var bin = atob(b64);
    var out = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }

  function bytesToB64(bytes) {
    var view = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
    var parts = [];
    var STEP = 0x8000;
    for (var i = 0; i < view.length; i += STEP) {
      parts.push(String.fromCharCode.apply(null, view.subarray(i, i + STEP)));
    }
    return btoa(parts.join(""));
  }

  // ------------------------------------------------------------- inbound state

  var handlers = {
    data: null,        // (Uint8Array) -> void
    permission: null,  // (boolean) -> void
    disconnect: null,  // (reason) -> void
    usb: null,         // (kind) -> void
    serial: null,      // (kind) -> void
  };

  window.__attaky = window.__attaky || {};

  window.__attaky.onData = function (b64) {
    if (handlers.data) handlers.data(b64ToBytes(b64));
  };

  window.__attaky.onPermission = function (granted) {
    var resolve = handlers.permission;
    handlers.permission = null;
    if (resolve) resolve(!!granted);
  };

  window.__attaky.onDisconnect = function (reason) {
    if (handlers.disconnect) handlers.disconnect(reason || "device disconnected");
  };

  window.__attaky.onUsbEvent = function (kind) {
    if (handlers.usb) handlers.usb(kind);
    dispatch(serialEvents, kind === "detached" ? "disconnect" : "connect");
  };

  // ------------------------------------------------------------- stream shims

  function ReadQueue() {
    this.chunks = [];
    this.pending = null;
    this.closed = false;
    this.error = null;
  }

  ReadQueue.prototype.push = function (bytes) {
    if (this.closed || this.error) return;
    if (this.pending) {
      var p = this.pending;
      this.pending = null;
      p.resolve({ value: bytes, done: false });
    } else {
      this.chunks.push(bytes);
    }
  };

  ReadQueue.prototype.finish = function () {
    this.closed = true;
    if (this.pending) {
      var p = this.pending;
      this.pending = null;
      p.resolve({ value: undefined, done: true });
    }
  };

  ReadQueue.prototype.fail = function (err) {
    this.error = err || new Error("stream error");
    this.closed = true;
    if (this.pending) {
      var p = this.pending;
      this.pending = null;
      p.reject(this.error);
    }
  };

  ReadQueue.prototype.read = function () {
    if (this.chunks.length) return Promise.resolve({ value: this.chunks.shift(), done: false });
    if (this.error) return Promise.reject(this.error);
    if (this.closed) return Promise.resolve({ value: undefined, done: true });
    var self = this;
    return new Promise(function (resolve, reject) {
      self.pending = { resolve: resolve, reject: reject };
    });
  };

  function makeReadable(queue) {
    var stream = {
      locked: false,
      getReader: function () {
        if (stream.locked) throw new TypeError("ReadableStream is locked");
        stream.locked = true;
        var reader = {
          read: function () {
            return queue.read();
          },
          cancel: function () {
            queue.finish();
            // Release eagerly so Transport.waitForUnlock() never spins forever
            // even when no read() is in flight (e.g. connected but idle).
            stream.locked = false;
            return Promise.resolve();
          },
          releaseLock: function () {
            stream.locked = false;
          },
          closed: Promise.resolve(),
        };
        return reader;
      },
      cancel: function () {
        queue.finish();
        return Promise.resolve();
      },
    };
    return stream;
  }

  function makeWritable() {
    var stream = {
      locked: false,
      getWriter: function () {
        if (stream.locked) throw new TypeError("WritableStream is locked");
        stream.locked = true;
        return {
          write: function (chunk) {
            var result = bridge.writeB64(bytesToB64(chunk));
            if (result !== "ok") return Promise.reject(new Error(result));
            return Promise.resolve();
          },
          // Deliberately a no-op: Transport.flushOutput() closes the writer, but
          // the underlying USB port must stay usable.
          close: function () {
            stream.locked = false;
            return Promise.resolve();
          },
          abort: function () {
            stream.locked = false;
            return Promise.resolve();
          },
          releaseLock: function () {
            stream.locked = false;
          },
          ready: Promise.resolve(),
          closed: new Promise(function () {}),
        };
      },
      abort: function () {
        return Promise.resolve();
      },
    };
    return stream;
  }

  // ------------------------------------------------------------- SerialPort

  var PARITY_MASK = { none: 0, odd: 1, even: 2, mark: 3, space: 4 };

  function AndroidSerialPort(info) {
    this._info = info || {};
    this._queue = null;
    this._readable = null;
    this._writable = null;
    this._open = false;
    this.baudRate = 115200;
    this._listeners = {};
  }

  AndroidSerialPort.prototype.getInfo = function () {
    return {
      usbVendorId: this._info.vid,
      usbProductId: this._info.pid,
    };
  };

  AndroidSerialPort.prototype.addEventListener = function (type, fn) {
    (this._listeners[type] = this._listeners[type] || []).push(fn);
  };

  AndroidSerialPort.prototype.removeEventListener = function (type, fn) {
    var list = this._listeners[type];
    if (!list) return;
    this._listeners[type] = list.filter(function (f) {
      return f !== fn;
    });
  };

  AndroidSerialPort.prototype._emit = function (type, detail) {
    var list = this._listeners[type] || [];
    var event = { type: type, target: this, detail: detail };
    for (var i = 0; i < list.length; i++) {
      try {
        list[i].call(this, event);
      } catch (e) {
        console.error("[attaky] port listener error", e);
      }
    }
  };

  AndroidSerialPort.prototype.open = function (options) {
    var self = this;
    var opts = options || {};
    var baud = opts.baudRate || 115200;
    var dataBits = opts.dataBits || 8;
    var stopBits = opts.stopBits || 1;
    var parity = opts.parity || "none";
    var flow = opts.flowControl || "none";
    if (PARITY_MASK[parity] === undefined) parity = "none";

    function attempt() {
      var raw = bridge.open(self._info.index, baud, dataBits, stopBits, parity, flow);
      var res;
      try {
        res = JSON.parse(raw);
      } catch (e) {
        throw new Error("Malformed response from the USB bridge: " + raw);
      }
      if (res.status === "permission") {
        return waitForPermission().then(function (granted) {
          if (!granted) throw new DOMException("USB permission denied", "SecurityError");
          return attempt();
        });
      }
      if (res.status !== "ok") {
        throw new DOMException(res.message || "Failed to open serial port", "NetworkError");
      }
      return null;
    }

    return Promise.resolve()
      .then(attempt)
      .then(function () {
        self.baudRate = baud;
        self._queue = new ReadQueue();
        self._readable = makeReadable(self._queue);
        self._writable = makeWritable();
        self._open = true;

        handlers.data = function (bytes) {
          if (self._queue) self._queue.push(bytes);
        };
        handlers.disconnect = function (reason) {
          self._onLost(reason);
        };
      });
  };

  AndroidSerialPort.prototype.close = function () {
    handlers.data = null;
    handlers.disconnect = null;
    if (this._queue) this._queue.finish();
    this._queue = null;
    this._readable = null;
    this._writable = null;
    this._open = false;
    if (bridge) bridge.close();
    return Promise.resolve();
  };

  AndroidSerialPort.prototype.setSignals = function (signals) {
    var s = signals || {};
    var mask = 0;
    if (s.dataTerminalReady !== undefined) mask |= 1;
    if (s.requestToSend !== undefined) mask |= 2;
    if (s.break !== undefined) mask |= 4;
    var result = bridge.setSignals(mask, !!s.dataTerminalReady, !!s.requestToSend, !!s.break);
    if (result !== "ok") return Promise.reject(new Error(result));
    return Promise.resolve();
  };

  AndroidSerialPort.prototype.getSignals = function () {
    try {
      return Promise.resolve(JSON.parse(bridge.getSignals()));
    } catch (e) {
      return Promise.resolve({});
    }
  };

  AndroidSerialPort.prototype.setBaudRate = function (baud) {
    var result = bridge.setBaudRate(baud);
    if (result !== "ok") return Promise.reject(new Error(result));
    this.baudRate = baud;
    return Promise.resolve();
  };

  AndroidSerialPort.prototype.forget = function () {
    return this.close();
  };

  AndroidSerialPort.prototype._onLost = function (reason) {
    var err = new DOMException("The device has been lost", "NetworkError");
    if (this._queue) this._queue.fail(err);
    this._readable = null;
    this._writable = null;
    this._open = false;
    handlers.data = null;
    handlers.disconnect = null;
    this._emit("disconnect", { reason: reason });
  };

  Object.defineProperty(AndroidSerialPort.prototype, "readable", {
    get: function () {
      return this._readable;
    },
  });

  Object.defineProperty(AndroidSerialPort.prototype, "writable", {
    get: function () {
      return this._writable;
    },
  });

  Object.defineProperty(AndroidSerialPort.prototype, "connected", {
    get: function () {
      return !!this._open;
    },
  });

  // ------------------------------------------------------------- permission

  function waitForPermission() {
    return new Promise(function (resolve) {
      handlers.permission = resolve;
    });
  }

  // ------------------------------------------------------------- choose a port

  var pendingPicker = null;

  /** Built by app.js: shows the device-chooser sheet and resolves with an index. */
  function setPortPicker(fn) {
    pendingPicker = fn;
  }

  function portsFromBridge() {
    var raw = bridge.listPorts();
    try {
      return JSON.parse(raw) || [];
    } catch (e) {
      throw new Error("Malformed device list from the USB bridge: " + raw);
    }
  }

  function requestPort() {
    var ports;
    try {
      ports = portsFromBridge();
    } catch (e) {
      return Promise.reject(e);
    }
    if (!ports.length) {
      return Promise.reject(
        new DOMException(
          "No USB serial device found. Plug the deck in with a data cable and allow USB access.",
          "NotFoundError",
        ),
      );
    }

    var choose = ports.length === 1 || !pendingPicker
      ? Promise.resolve(ports[0])
      : pendingPicker(ports);

    return choose.then(function (chosen) {
      if (!chosen) throw new DOMException("No port selected", "NotFoundError");
      var status = bridge.ensurePermission(chosen.index);
      if (status === "granted") return chosen;
      if (status === "permission") {
        return waitForPermission().then(function (granted) {
          if (!granted) throw new DOMException("USB permission denied", "SecurityError");
          return chosen;
        });
      }
      throw new DOMException(status, "SecurityError");
    }).then(function (chosen) {
      return new AndroidSerialPort(chosen);
    });
  }

  function getPorts() {
    return Promise.resolve(portsFromBridge().map(function (info) {
      return new AndroidSerialPort(info);
    }));
  }

  // ------------------------------------------------------------- navigator.serial

  var serialEvents = {
    _listeners: {},
    addEventListener: function (type, fn) {
      (this._listeners[type] = this._listeners[type] || []).push(fn);
    },
    removeEventListener: function (type, fn) {
      var list = this._listeners[type];
      if (list) {
        this._listeners[type] = list.filter(function (f) {
          return f !== fn;
        });
      }
    },
  };

  function dispatch(target, type) {
    var list = target._listeners[type] || [];
    for (var i = 0; i < list.length; i++) {
      try {
        list[i].call(target, { type: type, target: target });
      } catch (e) {
        console.error("[attaky] serial event listener error", e);
      }
    }
  }

  var serialApi = {
    requestPort: requestPort,
    getPorts: getPorts,
    addEventListener: function (type, fn) {
      serialEvents.addEventListener(type, fn);
    },
    removeEventListener: function (type, fn) {
      serialEvents.removeEventListener(type, fn);
    },
    onconnect: null,
    ondisconnect: null,
  };

  window.attakySerial = serialApi;
  window.attakySerial.setPortPicker = setPortPicker;
  window.attakySerial.AndroidSerialPort = AndroidSerialPort;

  // Best-effort: also present it as the real Web Serial API.
  try {
    Object.defineProperty(navigator, "serial", {
      value: serialApi,
      configurable: true,
      writable: true,
    });
  } catch (e) {
    try {
      navigator.serial = serialApi;
    } catch (e2) {
      console.warn("[attaky] could not install navigator.serial; use window.attakySerial");
    }
  }

  console.log("[attaky] Web Serial bridge ready" + (bridge ? "" : " (no USB bridge!)"));
})();
