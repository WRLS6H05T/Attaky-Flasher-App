# Attaky Flasher — Android USB flasher for the Attaky Cyber Deck (ESP32-S3)

An Android app that flashes the Attaky deck over its USB-C port. It has two
front ends:

- **Firmware store (online):** the app fronts the live store
  (`https://fwstore.attaky.com/en/`) in a WebView and injects a Web Serial
  polyfill, so the store's own **Flash Online** flow — esptool-js over
  `navigator.serial` — downloads and flashes the firmware on the phone.
- **Native flasher (offline):** a fully native Kotlin screen that speaks the
  ESP32-S3 ROM bootloader protocol directly, so images already on the device —
  or local `.bin` files — can be flashed with no network at all.

```
app/src/main/assets/www/
  index.html                 bundled offline page (fallback / home for dev images)
  css/app.css                Attaky terminal-instrument-panel theme
  js/webserial.js            navigator.serial polyfill -> Android USB bridge
  js/app.js                  catalogue, connect + flash flow
  vendor/esptool-js.js       esptool-js (classic script, window.esptool)
  firmware/manifest.json     bundled firmware catalogue
  firmware/*.bin             bundled merged images (developer builds)
```

## Build

Requires Android Studio (Jellyfish or newer) with Android SDK 34. No other
tooling — no baud-rate config, no Windows drivers; the app is plain USB Host.

1. Open this directory (`AndroidStudioProjects/AttakyFlasher/`) in Android Studio.
2. Let Gradle sync. The wrapper pins Gradle 9.6, the plugins are AGP 9.4.1 /
   Kotlin 2.2.10 (`build.gradle.kts`), the daemon JVM is JetBrains Runtime 21
   (`gradle/gradle-daemon-jvm.properties`) and the app targets Java 17 bytecode.
3. Run on a device with USB debugging enabled (OTG cable not required — your
   phone IS the host; use a USB-C host cable if your phone's port is not full
   size). From the command line:

   ```bash
   ./gradlew :app:assembleDebug     # debug build (applicationId *.debug)
   ./gradlew :app:assembleRelease   # unsigned release build
   ```

## Use

1. Open **Attaky Flasher**. After the splash you get a two-tile chooser:
   **FIRMWARE STORE** or **DOWNLOADED FIRMWARE**.
2. **Firmware Store** — pick a firmware and tap **Flash Online**. The app shows
   the Android USB permission dialog and the store drives the whole
   download + flash.
3. On any store page the header also carries an **⚡ Native Flasher** button
   that opens the offline flasher directly.
4. **Downloaded Firmware** — the fully native screen:
   - **DECK → CONNECT** the deck (allow USB access the first time) to read chip info.
   - **DOWNLOADED FIRMWARE** lists images the store has already saved on this
     device; each row has **Flash** and **Delete**. With none saved, a
     *No downloaded firmware* dialog appears and **OK** returns to the chooser.
   - **LOCAL .BIN FILES → CHOOSE FILES** picks `.bin` files from this device and
     lets you set a flash offset per file.
   - **ADVANCED** exposes baud rate, verify and compress.
   - Progress, chip info and a console log are shown on the same screen; when a
     flash finishes it returns to the list with a confirmation.
   - The header button reads **FIRMWARE STORE** and takes you back to the store.

There is no sign-in and no settings screen.

## Hosting the firmware catalogue

The bundled page resolves its manifest like this:

- `firmware/manifest.json` inside the app (developer builds).

The store keeps its own catalogue: `/en/firmware/{id}/launchpad.json` per
firmware, with image parts served from `/firmware/{id}/parts/{pid}/{name}.bin`.
The bundled images ("Attaky Controller 1.1.0", "DJ Scratch 0.1.0") are developer
builds not published to the store and are intentionally only available through
the bundled page.

Manifest shape:

```json
{
  "firmwares": [
    {
      "id": "attaky-launcher",
      "name": "Attaky Launcher",
      "version": "1.1.0",
      "chip": "ESP32-S3",
      "baudrate": 460800,
      "flashSize": "keep",
      "parts": [
        { "label": "Merged full image", "offset": 0,
          "url": "attaky-launcher-1.1.0-merged.bin",
          "sha256": "fd0b874c…" }
      ]
    }
  ]
}
```

- `parts[].offset` is the flash address (a merged image goes at `0`).
- `parts[].sha256` is verified before writing (SHA-256 of the downloaded bytes).

## Re-vendoring esptool-js

The page uses a classic-script build of esptool-js (WebViews do not reliably
serve ES modules over the asset loader):

```bash
curl -L https://unpkg.com/esptool-js@0.7.0/bundle.js -o tools/esptool-js.bundle.esm.js
node tools/vendor-esptool.js tools/esptool-js.bundle.esm.js \
     app/src/main/assets/www/vendor/esptool-js.js
```

The license is kept at `app/src/main/assets/www/vendor/esptool-js.LICENSE.txt`.

## Project layout

| Path | Purpose |
|---|---|
| `app/src/main/java/com/attaky/flasher/MainActivity.kt` | Boot chooser, WebView, store-first routing, polyfill injection, native-flasher host, store header injection |
| `app/src/main/java/com/attaky/flasher/NativeFlasher.kt` | The fully native offline flasher screen |
| `app/src/main/java/com/attaky/flasher/EspFlasher.kt` | ESP32-S3 ROM bootloader protocol in Kotlin |
| `app/src/main/java/com/attaky/flasher/UsbSerialService.kt` | USB enumeration, permission, open/write/signals, read loop |
| `app/src/main/java/com/attaky/flasher/SerialBridge.kt` | `window.AttakyUSB` JS↔Java contract |
| `app/src/main/java/com/attaky/flasher/FirmwareDownloads.kt` | App-local store of downloaded firmware images |
| `res/xml/usb_device_filter.xml` | USB-serial VID list for auto-attach |
| `tools/vendor-esptool.js` | ESM→classic-script bundler for esptool-js |
| `tools/gen-icons.ps1` | Launcher PNGs for API < 26 |
| `tools/gen-fastlane-graphics.ps1` | F-Droid listing icon and feature graphic |
| `fdroid/metadata/com.attaky.flasher.yml` | F-Droid (`fdroiddata`) build recipe |
| `fastlane/metadata/android/en-US/` | F-Droid listing texts, graphics and changelogs |

## Notes / limitations

- USB Host requires a phone with USB-OTG support and a data cable.
- The store pages are the live Django site; the polyfill is injected with
  `evaluateJavascript`, which bypasses the page's Content-Security-Policy
  (nonces and ES modules are left untouched). Styles injected into the store
  header are applied inline for the same reason.
- Some ESP32-S3 boards are USB-JTAG-only; those still work (the page uses the
  USB-JTAG reset sequence when it sees Espressif VID/PID `0x303A:0x1001`).
- The read loop delivers data in ≤4 KB chunks to keep `evaluateJavascript`
  traffic low — irrelevant for flashing, which is dominated by writes.
- Errors and progress are surfaced in the on-page console, not in logcat
  (turn on **Advanced → Verbose esptool trace** for the SLIP-level dump).
- Physical flashing has only been exercised against real decks, not the
  emulator (an emulator has no USB host).

## License

Attaky Flasher is free and open-source software, licensed under the
**Apache License, Version 2.0**. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

Third-party components: AndroidX and Kotlin (Apache-2.0),
[usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) (MIT),
and [esptool-js](https://github.com/espressif/esptool-js) (Apache-2.0).

## F-Droid

Everything F-Droid needs is in this repository:

- [`fdroid/metadata/com.attaky.flasher.yml`](fdroid/metadata/com.attaky.flasher.yml)
  is the `fdroiddata` build recipe (single-module Gradle build: `subdir: app`,
  `gradle: [yes]`).
- [`fastlane/metadata/android/en-US/`](fastlane/metadata/android/en-US/) holds the
  title, short/full description, changelog (`1.txt` = versionCode 1), icon,
  feature graphic and phone screenshots.

To submit:

1. Push this repository to a public git host and add a tag for the release
   (e.g. `v0.1.0-A001`).
2. Copy `fdroid/metadata/com.attaky.flasher.yml` to
   `metadata/com.attaky.flasher.yml` in a fork of
   [fdroiddata](https://gitlab.com/fdroid/fdroiddata), set `Repo`/`SourceCode`
   to your host and `commit` to the full tag commit hash, then open a merge
   request. (Alternatively open a *Request For Packaging* issue and point at
   this repository.)
