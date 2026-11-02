# Attaky Flasher — Android USB flasher for the Attaky Cyber Deck (ESP32-S3)

An Android app that flashes the Attaky deck over its USB-C port. It has two
front ends:

- **Firmware store (online):** the app fronts the live store
  (`https://fwstore.attaky.com/en/`) in a WebView and injects a Web Serial
  polyfill, so the store's own **Flash Online** flow — esptool-js over
  `navigator.serial` — downloads and flashes the firmware on the phone.
- **Native flasher (offline):** a native Kotlin screen that drives the deck
  with the same esptool-js build the store uses, loaded off-screen in a 1 px
  WebView, so images already saved on the device can be flashed with no network
  at all.

```
app/src/main/assets/www/
  index.html                 bundled offline page (fallback / home for dev images)
  css/app.css                Attaky terminal-instrument-panel theme
  js/webserial.js            navigator.serial polyfill -> Android USB bridge
  js/app.js                  catalogue, connect + flash flow
  native-flasher.html        never shown; hosts the native flasher's esptool-js
  js/native-flash.js         connect / flash / reset glue for that engine
  js/store-merge.js          store pages -> download-merged hook
  vendor/esptool-js.js       esptool-js (classic script, window.esptool)
  firmware/manifest.json     bundled firmware catalogue
  firmware/*.bin             bundled merged images (developer builds)
```

## Build

Requires a current Android Studio with Android SDK 37 (Android 17). No other
tooling — no baud-rate config, no Windows drivers; the app is plain USB Host.

1. Open this directory (`AndroidStudioProjects/AttakyFlasher/`) in Android Studio.
2. Let Gradle sync. The wrapper pins Gradle 9.8.2; AGP 9.5.0 and its built-in Kotlin
   support compile the app (`build.gradle.kts`), the daemon JVM is JetBrains
   Runtime 21 and the app targets Java 17 bytecode against API 37 (min API 24).
3. Run on a device with USB debugging enabled (OTG cable not required — your
   phone IS the host; use a USB-C host cable if your phone's port is not full
   size). From the command line:

   ```bash
   ./gradlew :app:assembleDebug     # debug build (applicationId *.debug)
   ./gradlew :app:assembleRelease   # unsigned release build
   ```

## Use

1. Open **Attaky Flasher**. After the splash you get the chooser:
   **FIRMWARE STORE** and **DOWNLOADED FIRMWARE**. A third tile, **LAUNCHER WEB
   UI**, appears only once a deck answers on the network — while the menu is up it
   looks for one every ten seconds (the remembered address each time, a full /24
   sweep at most once a minute), so a deck that boots late or comes back from an
   OTA restart brings the tile in without the app being restarted. The tile shows
   the address it was found at, so the menu never offers a screen that has no deck
   to talk to.
2. **Firmware Store** — pick a firmware and tap **Flash Online**. The app shows
   the Android USB permission dialog and the store drives the whole
   download + flash.
3. On any store page the header also carries an **⚡ Native Flasher** button
   that opens the offline flasher directly. The store's own three-bar menu
   stays in the header at every width, so rotating to landscape does not swap
   it for a row of inline links.
   Once a firmware is on the phone the store says so: the page's hero button
   reads   **Downloaded ✓ (merged for flasher)** with the saved file name under it
   (tapping still downloads it again), and every card in the listing that is
   already saved wears a small **Downloaded** chip. Downloads are matched by the
   store's firmware id — `/firmware_details/50`, `/firmware/50/parts/…` — which
   the app records in its index as `firmwareId`.
4. **Downloaded Firmware** — the native screen:
   - **DECK → CONNECT** the deck (allow USB access the first time) to read chip info.
   - **DOWNLOADED FIRMWARE** lists images the store has saved on this device.
     A store download fetches every part and merges them by flash address into
     one image, so a firmware with separate bootloader / partition / app bins
     flashes in a single pass. Each row carries the firmware's own app icon —
     the store page's hero icon, fetched once with the download and kept beside
     the image as `<image>.icon.webp`, so the list looks right with no network —
     with the Attaky mark standing in for images that have none. Each row has
     **Flash** and **Delete**; with none saved a *No downloaded firmware* dialog
     appears and **OK** returns to the chooser.
   - **ADVANCED** exposes baud rate, verify and compress.
   - Progress, chip info and a console log are shown on the same screen. A
     finished flash hard-resets the deck so it reboots into the new firmware,
     then closes the port.
   - Flashing is guarded at both ends: **Flash** asks first and spells out that
     the deck is erased and must not be unplugged, and while a write is running
     the back arrow, system back and **FIRMWARE STORE** offer *Keep flashing* or
     *Stop and leave*. Stopping closes the port under esptool, which fails the
     write in flight — the deck can be left half-flashed, which the warning says.
   - The header button reads **FIRMWARE STORE** and takes you back to the store.
5. **ESP serial** appears on the chooser when a USB serial device is attached:
   a scrolling console, a send box, and open/close port, for watching a running
   deck. The port is never taken on its own — a port open toggles DTR/RTS and
   would reset the board.

6. **LAUNCHER WEB UI** — find your deck on the network and send bins to its SD
   card, with no cable and no card reader. The tile is hidden until a deck is
   heard from (a deck whose web password was changed on the device still counts:
   it answers, so the screen says the login is what needs fixing). On the deck, open **MENU → Start Web User
   Interface** and leave it on that screen; the phone finds it on the same WiFi
   network.
   - The screen sweeps the phone's own subnet — and the launcher's
     `192.168.4.1` hotspot address — for a host that answers the launcher's
     `/systeminfo` behind its web login. The sweep is waited out, not cut short:
     an address found late is still reported. The deck then shows at its address
     with its Launcher version and the card's free space. When the sweep finds
     nothing it says which /24 it covered, and **Deck address** takes the address
     the deck prints on its own screen with **CONNECT**, which is what to use
     when the phone and the deck are not on the same subnet.
   - **Deck web login** is the launcher's own WebUI login, shipped as
     `admin` / `launcher` and changeable on the deck. It is remembered for the
     next visit. The credential is only ever offered to a host that already
     answered the launcher's `401 Unauthorized`, so other devices on the network
     never see it.
   - Each row in **Firmware on this phone** has a **+** that posts the image to
     the card's root as a multipart upload — the same call the launcher's file
     manager makes when a browser drops a file on it — with a progress bar, an
     `ON THE DECK'S SD CARD` mark when it lands, and the deck re-read for its new
     free space. Merged images are the point: the deck's SD flasher reads the
     file exactly as if it had been copied on by hand.
   - Leaving mid-transfer asks first. An aborted upload is discarded by the
     deck, which deletes a body it did not receive in full, so no half file is
     left on the card.
   - **OTA** installs the image into the deck's own app slot instead of the
     card: no card, no cable. The phone reads the merged image's partition table
     (`0x8000`, `AA 50 01`), finds the app entry and measures the ESP image's
     real length, then sends that as the launcher's `/OTA` manifest — the same
     description its own WebUI builds — and streams the file to `/OTAFILE`. The
     deck writes a spare slot, and is restarted with `/reboot` so the new
     firmware actually runs. The app's data partitions are left out of the
     update, so the deck keeps its settings and installed apps; an image with no
     app part is refused before anything is written.

   The wire format — login cookie, `/systeminfo`, multipart framing — lives in
   `DeckProtocol`, which has no Android in it; `DeckProtocolTest` drives it
   against a fake launcher on a socket.

7. **SETTINGS**, top right of the chooser, changes the app's appearance only
   (the store pages keep their own colours):
   - **Theme** — light or dark. Dark repaints the page, the cards, the dialogs
     (each one an outlined card in the dark palette, not a flat black panel),
     the boot splash and swaps the wordmark for the dark artwork
     (`attaky_logo_dark.png`) from the very first frame — the launch preview
     window is switched off so a dark start never flashes white.
   - **Full screen** — hides the status and navigation bars; swiping from the
     edge brings them back.
   - **Text size** — 85% to 145%, applied to every native screen.
   - **Text colour** — a heading/value tone and a label/small-print tone for the
     chooser and the native flasher, or *Automatic* to follow the theme.
   - **Reset** puts everything back to the designed light look.

   The palettes live in `colors.xml` and are applied by `AttakyTheme`, which walks
   the finished view tree, so each layout is written once and tagged with the
   role of each part (`attaky:card`, `attaky:ink`, `attaky:muted`, …).
   `AppSettings` stores the choices in the same shared preferences as the
   flasher's baud rate. There is no sign-in.

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
| `app/src/main/java/com/attaky/flasher/NativeFlasher.kt` | The native offline flasher screen (UI, bin naming, merge, console) |
| `app/src/main/java/com/attaky/flasher/JsFlasher.kt` | Off-screen WebView host that runs esptool-js for the native flasher |
| `app/src/main/java/com/attaky/flasher/FlashLayout.kt` | Bin name → flash offset resolution and image merging |
| `app/src/main/java/com/attaky/flasher/SerialMonitor.kt` | ESP serial console on the chooser screen |
| `app/src/main/java/com/attaky/flasher/EspFlasher.kt` | Earlier hand-rolled ROM protocol, no longer used (superseded by `JsFlasher`) |
| `app/src/main/java/com/attaky/flasher/UsbSerialService.kt` | USB enumeration, permission, open/write/signals, read loop |
| `app/src/main/java/com/attaky/flasher/SerialBridge.kt` | `window.AttakyUSB` JS↔Java contract |
| `app/src/main/java/com/attaky/flasher/FirmwareDownloads.kt` | App-local store of downloaded firmware images and their saved app icons |
| `res/xml/usb_device_filter.xml` | USB-serial VID list for auto-attach |
| `tools/vendor-esptool.js` | ESM→classic-script bundler for esptool-js |
| `tools/gen-icons.ps1` | Launcher PNGs for API < 26 |
| `tools/gen-fastlane-graphics.ps1` | F-Droid listing icon and feature graphic |
| `fdroid/metadata/com.attaky.flasher.yml` | F-Droid (`fdroiddata`) build recipe |
| `fastlane/metadata/android/en-US/` | F-Droid listing texts, graphics and changelogs |

## Notes / limitations

- USB Host requires a phone with USB-OTG support and a data cable.
- Edge-to-edge is enforced from targetSdk 35 on, so `activity_main.xml` no longer
  relies on `fitsSystemWindows`: the root view pads itself from the system-bar
  and display-cutout insets in `MainActivity.onCreate`.
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
