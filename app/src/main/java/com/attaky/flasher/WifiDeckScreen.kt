package com.attaky.flasher

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/**
 * "Deck over WiFi": find the deck's web server on the network and drop firmware
 * straight on its SD card, with no cable and no card reader.
 *
 * The transfer itself is [DeckWifi]: it sweeps the phone's subnet for a host
 * that answers the launcher's `/systeminfo`, logs in with the deck's own web
 * credentials, then POSTs the image as a multipart upload — the same call the
 * launcher's file manager makes when a browser drops a file on it.
 *
 * The list is the phone's downloaded firmware, so a merged image downloaded from
 * the store is the thing being sent; the deck's SD flasher then sees it exactly
 * as if it had been copied onto the card by hand.
 *
 * Each row also offers **OTA**: instead of the card, the image goes through the
 * launcher's over-the-air install — the deck resizes a spare app slot, takes the
 * image over the same session and restarts into it.
 */
class WifiDeckScreen(
    private val activity: Activity,
    private val settings: AppSettings,
    private val downloads: FirmwareDownloads,
    private val root: View,
    private val onMenu: () -> Unit,
    /**
     * An OTA install rebooted the deck, so the session is gone. The menu is the
     * only sensible place to be: there is nothing left to send and the deck will
     * not answer until it has booted again.
     */
    private val onOtaDone: () -> Unit = {},
) {

    private val client = DeckWifi()

    private val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val status: TextView = root.findViewById(R.id.wifiStatus)
    private val scanBar: View = root.findViewById(R.id.wifiScanBar)
    private val scanButton: Button = root.findViewById(R.id.wifiScan)
    private val deckCard: View = root.findViewById(R.id.wifiDeckCard)
    private val deckHost: TextView = root.findViewById(R.id.wifiDeckHost)
    private val deckInfo: TextView = root.findViewById(R.id.wifiDeckInfo)
    private val userInput: EditText = root.findViewById(R.id.wifiUser)
    private val passInput: EditText = root.findViewById(R.id.wifiPass)
    private val addressInput: EditText = root.findViewById(R.id.wifiAddress)
    private val connectButton: Button = root.findViewById(R.id.wifiConnect)
    private val list: ViewGroup = root.findViewById(R.id.wifiList)
    private val empty: View = root.findViewById(R.id.wifiEmpty)

    private var deck: DeckProtocol.Deck? = null

    private var scanning = false

    /** Name of the image being uploaded right now, or null. */
    private var sending: String? = null

    /** A host that answered this visit but would not log in, if any. */
    private var answered: String? = null

    /** Images that landed on the card during this visit. */
    private val sent = HashSet<String>()

    /** Images installed into the deck's own app slot during this visit. */
    private val updated = HashSet<String>()

    /** The row for each image, so a progress update finds its bar. */
    private val rows = HashMap<String, View>()

    /** Where the screen is: the deck's login and the list, ready to use. */
    fun start() {
        root.findViewById<View>(R.id.btnMenu).setOnClickListener { leave(onMenu) }
        scanButton.setOnClickListener { if (!scanning && sending == null) scan() }

        userInput.setText(prefs.getString(KEY_USER, DEFAULT_USER))
        passInput.setText(prefs.getString(KEY_PASS, DEFAULT_PASS))
        addressInput.setText(prefs.getString(KEY_HOST, ""))
        connectButton.setOnClickListener { connectTyped() }

        renderFirmware()
        scan()
    }

    /**
     * Leave the screen — asking first while a write is in flight, because a
     * half-sent image would sit on the card as a file that cannot load.
     */
    fun leave(then: () -> Unit) {
        if (sending == null) {
            then()
            return
        }
        AttakyTheme.dialog(activity, AttakyTheme.palette(activity, settings))
            .setTitle(R.string.wifi_leave_title)
            .setMessage(R.string.wifi_leave_message)
            .setNegativeButton(R.string.wifi_leave_stay, null)
            .setPositiveButton(R.string.wifi_leave_go) { _, _ -> then() }
            .show()
    }

    fun stop() {
        client.stop()
    }

    // -------------------------------------------------------------------- lookup

    private fun scan() {
        if (scanning) return
        scanning = true
        deck = null
        answered = null
        deckCard.visibility = View.GONE
        scanBar.visibility = View.VISIBLE
        scanButton.isEnabled = false
        connectButton.isEnabled = false
        status.text = activity.getString(R.string.wifi_scanning)
        renderFirmware()

        rememberLogin()
        client.startScan(
            user = user(),
            password = password(),
            onProgress = { done, total ->
                if (scanning) {
                    status.text = activity.getString(R.string.wifi_scan_progress, done, total)
                }
            },
            // A deck was seen even if its web login is not what this phone kept.
            onSeen = { host ->
                answered = host
                if (deck == null) {
                    status.text = activity.getString(R.string.wifi_deck_answered, host)
                }
            },
            onDone = { found ->
                scanning = false
                scanBar.visibility = View.GONE
                scanButton.isEnabled = true
                connectButton.isEnabled = true
                val seen = answered
                if (found != null) {
                    showDeck(found)
                    status.text = activity.getString(R.string.wifi_found, found.host)
                } else if (seen != null) {
                    // The deck is there; the credential is the thing to fix.
                    status.text = activity.getString(R.string.wifi_login_failed, seen)
                } else {
                    // Say where it looked, so a wrong network is obvious at a glance.
                    val swept = client.lastSweep
                    status.text = if (swept == null) {
                        activity.getString(R.string.wifi_scan_none)
                    } else {
                        activity.getString(R.string.wifi_scan_none_at, swept)
                    }
                }
                renderFirmware()
            },
        )
    }

    /**
     * Connect to one address the user typed. The deck prints its own address on
     * the WebUI screen, so this works even when the sweep's subnet is not the
     * deck's — a guest network, a second router, or a hotspot with isolation.
     */
    private fun connectTyped() {
        if (scanning || sending != null) return
        val typed = addressInput.text.toString().trim()
        if (typed.isEmpty()) {
            toast(activity.getString(R.string.wifi_address_needed))
            return
        }
        rememberLogin()
        status.text = activity.getString(R.string.wifi_connecting, typed)
        connectButton.isEnabled = false
        deckCard.visibility = View.GONE

        client.probeAddress(typed, user(), password()) { found ->
            connectButton.isEnabled = true
            if (found != null) {
                showDeck(found)
                status.text = activity.getString(R.string.wifi_found, found.host)
            } else {
                status.text = activity.getString(R.string.wifi_address_none, typed)
            }
            renderFirmware()
        }
    }

    private fun showDeck(found: DeckProtocol.Deck) {
        deck = found
        deckCard.visibility = View.VISIBLE
        deckHost.text = found.host
        // Keep the address in the field: it is also what CONNECT retries.
        if (addressInput.text.toString().trim() != found.host) addressInput.setText(found.host)
        prefs.edit().putString(KEY_HOST, found.host).apply()
        deckInfo.text = if (found.sdTotal.isEmpty()) {
            activity.getString(R.string.wifi_deck_no_sd)
        } else {
            activity.getString(
                R.string.wifi_deck_sd,
                activity.getString(R.string.wifi_deck_version, found.version),
                found.sdFree,
                found.sdTotal,
            )
        }
    }

    // ------------------------------------------------------------------ transfer

    private fun send(entry: FirmwareDownloads.Entry) {
        val deck = deck
        if (deck == null) {
            toast(activity.getString(R.string.wifi_need_deck))
            return
        }
        if (sending != null) return
        val file = downloads.fileFor(entry.name)
        if (file == null) {
            toast(activity.getString(R.string.wifi_file_gone))
            renderFirmware()
            return
        }

        sending = entry.name
        renderFirmware()

        client.upload(
            deck = deck,
            user = user(),
            password = password(),
            file = file,
            remoteName = entry.name,
            folder = "/",
            onProgress = { fraction -> rowProgress(entry.name, fraction) },
            onDone = { ok, detail ->
                sending = null
                if (ok) {
                    sent.add(entry.name)
                    toast(activity.getString(R.string.wifi_sent_toast, entry.name))
                    // The card has less room now; ask the deck rather than guess.
                    client.refresh(deck, user(), password()) { fresh ->
                        if (fresh != null) showDeck(fresh)
                    }
                } else if (detail == DeckWifi.CARD_REFUSED) {
                    toast(activity.getString(R.string.wifi_card_refused, DeckWifi.ATTEMPTS))
                } else {
                    toast(activity.getString(R.string.wifi_failed, detail))
                }
                renderFirmware()
            },
        )
    }

    // ----------------------------------------------------------------------- OTA

    /**
     * The update is a firmware install, so it is spelled out before it starts —
     * the deck writes a slot and reboots into it, and a cable pulled mid-write
     * leaves it without a running app.
     */
    private fun confirmOta(entry: FirmwareDownloads.Entry) {
        if (sending != null || deck == null) {
            toast(activity.getString(R.string.wifi_need_deck))
            return
        }
        AttakyTheme.dialog(activity, AttakyTheme.palette(activity, settings))
            .setTitle(R.string.wifi_ota_title)
            .setMessage(activity.getString(R.string.wifi_ota_message, entry.name))
            .setNegativeButton(R.string.wifi_ota_cancel, null)
            .setPositiveButton(R.string.wifi_ota_start) { _, _ -> update(entry) }
            .show()
    }

    private fun update(entry: FirmwareDownloads.Entry) {
        val deck = deck ?: return
        if (sending != null) return
        val file = downloads.fileFor(entry.name)
        if (file == null) {
            toast(activity.getString(R.string.wifi_file_gone))
            renderFirmware()
            return
        }

        sending = entry.name
        renderFirmware()

        client.ota(
            deck = deck,
            user = user(),
            password = password(),
            file = file,
            fileName = entry.name,
            onProgress = { fraction -> rowProgress(entry.name, fraction) },
            onDone = { ok, detail ->
                sending = null
                if (ok) {
                    updated.add(entry.name)
                    val message = activity.getString(R.string.wifi_ota_ok, entry.name)
                    toast(message)
                    status.text = message
                    // Let the message be read, then hand the user back to the
                    // menu with the Launcher tile hidden — the deck is restarting
                    // into the new slot, so it is about to drop off the network.
                    root.postDelayed({ onOtaDone() }, OTA_RETURN_MS)
                } else if (detail == DeckWifi.OTA_NO_APP) {
                    toast(activity.getString(R.string.wifi_ota_no_app, entry.name))
                } else {
                    toast(activity.getString(R.string.wifi_failed, detail))
                }
                renderFirmware()
            },
        )
    }

    private fun rowProgress(name: String, fraction: Float) {
        val row = rows[name] ?: return
        row.findViewById<ProgressBar>(R.id.wifiBinProgress).progress =
            (fraction * 100f).toInt().coerceIn(0, 100)
    }

    // ---------------------------------------------------------------------- list

    private fun renderFirmware() {
        list.removeAllViews()
        rows.clear()

        val entries = downloads.entries()
        empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(activity)

        for (entry in entries) {
            val row = inflater.inflate(R.layout.item_wifi_bin, list, false)

            // The app icon saved with the image, or the Attaky mark without one.
            val icon = row.findViewById<ImageView>(R.id.wifiBinIcon)
            val artwork = downloads.iconFile(entry)
                ?.let { file -> BitmapFactory.decodeFile(file.absolutePath) }
            if (artwork != null) icon.setImageBitmap(artwork) else icon.setImageResource(R.drawable.ic_launcher_foreground)

            row.findViewById<TextView>(R.id.wifiBinName).text = entry.name
            row.findViewById<TextView>(R.id.wifiBinMeta).text = buildString {
                append(humanSize(entry.size))
                append(" · ")
                append(activity.getString(R.string.wifi_bin_merged))
            }

            val busy = sending == entry.name
            val enabled = deck != null && sending == null

            row.findViewById<TextView>(R.id.wifiBinSend).apply {
                visibility = if (busy) View.GONE else View.VISIBLE
                isEnabled = enabled
                alpha = if (enabled) 1f else 0.35f
                setOnClickListener { send(entry) }
            }
            row.findViewById<TextView>(R.id.wifiBinOta).apply {
                visibility = if (busy) View.GONE else View.VISIBLE
                isEnabled = enabled
                alpha = if (enabled) 1f else 0.35f
                setOnClickListener { confirmOta(entry) }
            }
            row.findViewById<ProgressBar>(R.id.wifiBinProgress).visibility =
                if (busy) View.VISIBLE else View.GONE
            row.findViewById<TextView>(R.id.wifiBinState).apply {
                val onCard = sent.contains(entry.name)
                val installed = updated.contains(entry.name)
                visibility = if ((onCard || installed) && !busy) View.VISIBLE else View.GONE
                // A row can have gone both ways: the card copy is the lasting one.
                setText(if (onCard) R.string.wifi_sent else R.string.wifi_ota_done)
            }

            rows[entry.name] = row
            list.addView(row)
        }

        // Rows are new views every time, so they are painted after they are built.
        applyTheme()
    }

    // ------------------------------------------------------------------- helpers

    private fun user(): String = userInput.text.toString().trim().ifEmpty { DEFAULT_USER }

    private fun password(): String = passInput.text.toString().ifEmpty { DEFAULT_PASS }

    /** Keep the deck's login, so a second visit does not retype it. */
    private fun rememberLogin() {
        prefs.edit()
            .putString(KEY_USER, user())
            .putString(KEY_PASS, password())
            .apply()
    }

    private fun applyTheme() {
        AttakyTheme.apply(root, AttakyTheme.palette(activity, settings), settings.textScale)
    }

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }

    /** "3.2 MB" — the same shape the flasher's rows use. */
    private fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB")
        var value = bytes / 1024.0
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return String.format("%.1f %s", value, units[unit])
    }

    companion object {
        const val PREFS = "attaky_flasher"
        const val KEY_USER = "deck_user"
        const val KEY_PASS = "deck_pass"
        const val KEY_HOST = "deck_host"

        /**
         * The deck web login last used, so the menu can probe for the deck
         * without the user opening this screen first.
         */
        fun savedLogin(context: Context): Pair<String, String> {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return (prefs.getString(KEY_USER, DEFAULT_USER) ?: DEFAULT_USER) to
                (prefs.getString(KEY_PASS, DEFAULT_PASS) ?: DEFAULT_PASS)
        }

        /** The Launcher's shipped web login; changeable on the deck itself. */
        const val DEFAULT_USER = "admin"
        const val DEFAULT_PASS = "launcher"

        /** How long the "is on the deck" message stays up before the menu returns. */
        private const val OTA_RETURN_MS = 1_800L
    }
}
