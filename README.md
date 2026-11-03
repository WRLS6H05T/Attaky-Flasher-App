# Attaky Flasher — Android flasher for the Attaky core (ESP32-S3)

An Android app that puts firmware on the Attaky core (ESP32-S3) from a phone:
over the core's USB-C port, over WiFi, or straight from the online store. It
has three front ends and two tools:

- **Firmware store (online):** the app fronts the live store
  (`https://fwstore.attaky.com/en/`) in a WebView and injects a Web Serial
  polyfill, so the store's own **Flash Online** flow — esptool-js over
  `navigator.serial` — downloads and flashes the firmware on the phone.
- **Native flasher (offline):** a native Kotlin screen that drives the core
  with the same esptool-js build the store uses, loaded off-screen in a 1 px
  WebView, so images already saved on the device can be flashed with no network
  at all.
- **Launcher Web UI (WiFi):** finds the core on the network, logs into its
  Launcher, and posts merged images to its SD card — or installs them into its
  app slot over OTA — with no cable and no card reader.
- **ESP serial:** a console for any USB serial board, printing the raw bytes of
  everything in and out.
- **Settings:** light/dark, full screen, text size and the text colours —
  including a neon category for the dark page — applied to every native screen.

Labels say **core**, not deck. The shared-preference keys kept their original
names (`deck_host`, `deck_user`, `deck_pass`) so a saved core login survives.

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

Requires a current Android Studio with **Android SDK 37**. No other tooling — no
baud-rate config, no Windows drivers; the app is plain USB Host.

1. Open this directory (`AndroidStudioProjects/AttakyFlasher/`) in Android Studio.
2. Let Gradle sync. The wrapper pins **Gradle 9.8.2**; **AGP 9.5.0** and its
   built-in Kotlin support compile the app (`build.gradle.kts`), the daemon JVM
   is JetBrains Runtime 21, and the app targets Java 17 bytecode against API 37
   (min API 24, for WebKit).
3. Run on a device with USB debugging enabled (OTG cable not required — your
   phone IS the host; use a USB-C host cable if your phone's port is not full
   size). From the command line:

   ```bash
   ./gradlew :app:assembleDebug     # debug build (applicationId *.debug)
   ./gradlew :app:assembleRelease   # unsigned release build
   ./gradlew :app:testDebugUnitTest # launcher protocol + OTA manifest tests
   ```

Apps targeting 35+ are drawn edge to edge, so `MainActivity` pads the root view
with the system-bar and display-cutout insets itself.

## Use

1. Open **Attaky Flasher**. The splash shows the wordmark and the version, then
   the chooser, with **SETTINGS** in its top-right corner. Every native screen
   puts its corner button in that same box at the same 20dp/10dp corner —
   **SETTINGS** on the chooser, **MENU** on the flasher, on Settings and on the
   Launcher Web UI — so moving between screens does not move the button. There
   are no back arrows: **MENU**, and system back, both return to the chooser.
2. **FIRMWARE STORE** — pick a firmware and tap **Flash Online**. The app shows
   the Android USB permission dialog and the store drives the whole
   download + flash.
3. On any store page a **MENU** button sits in the header — the same box, size
   and palette colours as the chooser's **SETTINGS** button, dropped into the
   store's own sticky bar when there is one and in the top-right corner when
   there is not. It leaves the page for the native menu. The store's own
   three-bar toggle stays in the header at every width, so rotating to landscape
   does not swap it for a row of inline links.
   Once a firmware is on the phone the store says so: the page's hero button
   reads **Downloaded ✓ (merged for flasher)** with the saved file name under it
   (tapping still downloads it again), and every card in the listing that is
   already saved wears a small **Downloaded** chip. Downloads are matched by the
   store's firmware id — `/firmware_details/50`, `/firmware/50/parts/…` — which
   the app records in its index as `firmwareId`.
4. **DOWNLOADED FIRMWARE** — the native screen, with **MENU** in the top-right
   corner in the same place as the chooser's **SETTINGS**:
   - **CORE → CONNECT** the core (allow USB access the first time) to read chip
     info.
   - The list holds images the store has saved on this device. A store download
     fetches every part and merges them by flash address into one image, so a
     firmware with separate bootloader / partition / app bins flashes in a single
     pass. Each row carries the firmware's own app icon — the store page's hero
     icon, fetched once with the download and kept beside the image as
     `<image>.icon.webp`, so the list looks right with no network — with the
     Attaky mark standing in for images that have none. Each row has **Flash**
     and **Delete**; with none saved a *No downloaded firmware* dialog appears
     and **OK** returns to the chooser.
   - **ADVANCED** exposes baud rate, verify and compress.
   - Progress, chip info and a console log are shown on the same screen. A
     finished flash hard-resets the core so it reboots into the new firmware,
     then closes the port.
   - Flashing is guarded at both ends: **Flash** asks first and spells out that
     the core is erased and must not be unplugged, and while a write is running
     **MENU** and system back offer *Keep flashing* or *Stop and leave*. Stopping
     closes the port under esptool, which fails the write in flight — the core
     can be left half-flashed, which the warning says.
5. **LAUNCHER WEB UI** — a third tile on the chooser that appears once a core
   answers on the network; there is no tile to tap when there is nothing to talk
   to. While the menu is up it looks every seven seconds — the remembered
   address on each tick, the full /24 sweep paced by the same tick — so a core
   that boots late, moves address or comes back from an OTA restart brings the
   tile in without the app being restarted. The tile then shows the address it
   was found at. On the core, open **MENU → Start Web User Interface** and leave
   it on that screen.

   The screen itself:
   - It sweeps the phone's own subnet — and the launcher's `192.168.4.1` hotspot
     address — for a host that answers the launcher's `/systeminfo` behind its
     web login, and shows the core's address, Launcher version and the card's
     free space. When nothing answers it says which /24 it covered, and **Core
     address** takes the address the core prints on its own screen with
     **CONNECT**, which is what to use when the phone and the core are not on the
     same subnet.
   - **Core web login** is the launcher's own WebUI login, shipped as
     `admin` / `launcher` and changeable on the core. It is remembered for the
     next visit, and is only ever offered to a host that already answered the
     launcher's `401 Unauthorized`, so other devices on the network never see it.
   - Each row in **Firmware on this phone** has a **+** that posts the image to
     the card's root as a multipart upload — the same call the launcher's file
     manager makes when a browser drops a file on it — with a progress bar, an
     `ON THE CORE'S SD CARD` mark when it lands, and the core re-read for its new
     free space. Merged images are the point: the core's SD flasher reads the
     file exactly as if it had been copied on by hand.
   - **OTA** installs the image into the core's own app slot instead of the card.
     The phone reads the merged image's partition table (`0x8000`, `AA 50 01`),
     finds the app entry and measures the ESP image's real length, then sends
     that as the launcher's `/OTA` manifest — the same description its own WebUI
     builds — and streams the file to `/OTAFILE`. The core writes a spare slot
     and is restarted with `/reboot` so the new firmware actually runs. Data
     partitions are left out of the update, so the core keeps its settings and
     installed apps; an image with no app part is refused before anything is
     written.
   - Leaving mid-transfer asks first — **MENU** and system back both offer *Keep
     sending* or *Leave anyway*. An aborted upload is discarded by the core, which
     deletes a body it did not receive in full, so no half file is left on the
     card.

   The wire format — login cookie, `/systeminfo`, multipart framing, `/OTA` — is
   in `DeckProtocol` and `DeckWifi`, which have no Android in them;
   `DeckProtocolTest` drives them against a fake launcher on a socket, and
   `OtaManifestTest` against real partition tables.
6. **ESP serial** appears on the chooser when a USB serial device is attached:
   a scrolling console, a send box, and open/close port, for watching a running
   core. Everything is printed twice — the text, then the bytes as hex sixteen
   to a line — so a button press that sends only control codes still shows
   exactly what arrived (`·RX …` for the core's bytes, `·TX …` for the phone's).
   The log is capped at 16,000 characters, so a fast boot log pushes the oldest
   hex out. The port is never taken on its own — a port open toggles DTR/RTS and
   would reset the board.

## Settings

**SETTINGS**, top right of the chooser, changes the app's appearance only (the
store pages keep their own colours). Its own screen carries **MENU** in that same
corner, which returns to the chooser:

- **Theme** — light or dark. Dark repaints the page, the cards, the dialogs
  (each one an outlined card in the dark palette, not a flat black panel), the
  boot splash and the wordmark, for which it swaps in the dark artwork
  (`attaky_logo_dark.png`) from the first app-drawn frame.
- **Full screen** — hides the status and navigation bars; swiping from the edge
  brings them back.
- **Text size** — 85% to 145%, applied to every native screen.
- **Text colour** — a heading/value tone and a label/small-print tone, or
  *Automatic* to follow the theme. The heading tone also paints the actions:
  every primary button (CONNECT, FLASH, OTA, SEND), the progress bars, the
  checkbox tick and the ring around the selected swatch, with the label flipping
  to near-black when the tone is pale.
- **Neon** — eight sign-light tones (green, cyan, blue, violet, magenta, red,
  orange, yellow). These are the same primary slot, not a separate setting:
  picking one repaints the headings, the values and the action buttons in it.
  They are made for the dark page, and are offered in both themes rather than
  hidden on the light one, so a neon pick made in the dark stays visible and
  undoable after switching back.
- **Reset** puts everything back to the designed light look.

The palettes live in `colors.xml` (plus `values-night/`) and are applied by
`AttakyTheme`, which walks the finished view tree, so each layout is written once
and tagged with the role of each part (`attaky:card`, `attaky:ink`,
`attaky:muted`, `attaky:primary`, …). `AppSettings` stores the choices in the
same shared preferences as the flasher's baud rate. There is no sign-in.

## The two splashes

Android draws a launch window before any app code runs, and on Android 12+ a
system splash after it; the app then draws its own branded splash, whose logo
shrinks into the menu. The launch window follows the **phone's** night mode, not
the app's LIGHT/DARK toggle, because it is theme resources read before the
process starts:

- `values/themes.xml` — white page, `windowDisablePreview`, splash colour only,
  the icon left to the system.
- `values-night/themes.xml` — near-black page, splash colour only with
  `@drawable/splash_none` as the icon, because the 3.4:1 wordmark cannot go into
  the splash icon slot without being squashed. The wordmark appears once, on the
  app's own splash, at its proper size.

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
| `app/src/main/java/com/attaky/flasher/MainActivity.kt` | Boot splash, chooser, stage routing, WebView + polyfill injection, core discovery, store header injection |
| `app/src/main/java/com/attaky/flasher/NativeFlasher.kt` | The native offline flasher screen (UI, bin naming, merge, console) |
| `app/src/main/java/com/attaky/flasher/JsFlasher.kt` | Off-screen WebView host that runs esptool-js for the native flasher |
| `app/src/main/java/com/attaky/flasher/FlashLayout.kt` | Bin name → flash offset resolution and image merging |
| `app/src/main/java/com/attaky/flasher/SerialMonitor.kt` | ESP serial console (text plus hex, both directions) |
| `app/src/main/java/com/attaky/flasher/WifiDeckScreen.kt` | Launcher Web UI screen: discovery, login, card upload, OTA |
| `app/src/main/java/com/attaky/flasher/DeckWifi.kt` | Launcher HTTP: subnet sweep, `/systeminfo`, multipart upload, OTA |
| `app/src/main/java/com/attaky/flasher/DeckProtocol.kt` | Pure-Kotlin launcher wire format: login, system info, upload framing |
| `app/src/main/java/com/attaky/flasher/OtaManifest.kt` | Reads a merged image's partition table and builds the `/OTA` manifest |
| `app/src/main/java/com/attaky/flasher/SettingsScreen.kt` | Settings behaviour: theme, full screen, text size, text colours and the neon swatches |
| `app/src/main/java/com/attaky/flasher/AttakyTheme.kt` | Palette resolution and the view-tree repaint every native screen uses |
| `app/src/main/java/com/attaky/flasher/AppSettings.kt` | The stored appearance choices |
| `app/src/main/java/com/attaky/flasher/EspFlasher.kt` | Earlier hand-rolled ROM protocol, no longer used (superseded by `JsFlasher`) |
| `app/src/main/java/com/attaky/flasher/UsbSerialService.kt` | USB enumeration, permission, open/write/signals, read loop |
| `app/src/main/java/com/attaky/flasher/SerialBridge.kt` | `window.AttakyUSB` JS↔Java contract, including the menu/store navigation calls |
| `app/src/main/java/com/attaky/flasher/FirmwareDownloads.kt` | App-local store of downloaded firmware images and their saved app icons |
| `app/src/test/java/com/attaky/flasher/` | `DeckProtocolTest`, `OtaManifestTest` — JVM, no device needed |
| `res/values/`, `res/values-night/` | Palettes and themes for both app themes |
| `res/xml/usb_device_filter.xml` | USB-serial VID list for auto-attach |
| `tools/vendor-esptool.js` | ESM→classic-script bundler for esptool-js |
| `tools/gen-icons.ps1` | Launcher PNGs for API < 26 |
| `tools/gen-fastlane-graphics.ps1` | F-Droid listing icon and feature graphic |
| `fdroid/metadata/com.attaky.flasher.yml` | F-Droid (`fdroiddata`) build recipe |
| `fastlane/metadata/android/en-US/` | F-Droid listing texts, graphics and changelogs |

## Notes / limitations

- USB Host requires a phone with USB-OTG support and a data cable.
- The launch window (the white or near-black screen before the app paints
  anything) follows the phone's night mode, not the app's theme toggle.
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
- Physical flashing and the WiFi screen have only been exercised against real
  cores, not the emulator (which has no USB host and no core on its network).

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
  `gradle: [yes]`). It pins **0.1.5-A007** / `versionCode: 18` to commit
  `3669c46`, with the first release kept below it; when a tag is cut, append a
  `Builds` entry for it, then move `CurrentVersion` and `CurrentVersionCode`
  forward. `UpdateCheckMode: Tags` reads the version from every tag, so the tag
  name itself does not have to carry it.
- [`fastlane/metadata/android/en-US/`](fastlane/metadata/android/en-US/) holds the
  title, short/full description, changelogs (one file per `versionCode`), icon,
  feature graphic and phone screenshots.

To submit:

1. Push this repository to a public git host and add a tag for the release
   (e.g. `v0.1.5-A007`).
2. Copy `fdroid/metadata/com.attaky.flasher.yml` to
   `metadata/com.attaky.flasher.yml` in a fork of
   [fdroiddata](https://gitlab.com/fdroid/fdroiddata), set `Repo`/`SourceCode`
   to your host and `commit` to the full tag commit hash, then open a merge
   request. (Alternatively open a *Request For Packaging* issue and point at
   this repository.)
