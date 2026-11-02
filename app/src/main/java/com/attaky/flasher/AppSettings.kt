package com.attaky.flasher

import android.content.Context

/**
 * Appearance settings for the native screens: theme, text size, the two text
 * tones and the full-screen mode.
 *
 * Everything is written straight to the shared preferences (the same file the
 * flasher keeps its baud rate in) and read back by [AttakyTheme] whenever a
 * screen is drawn, so a change takes effect on the next screen build and
 * survives a restart. The store pages are a website and are not touched.
 */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Dark theme. */
    var dark: Boolean
        get() = prefs.getBoolean(KEY_DARK, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DARK, value).apply()
        }

    /** Hide the system bars. */
    var fullScreen: Boolean
        get() = prefs.getBoolean(KEY_FULL_SCREEN, false)
        set(value) {
            prefs.edit().putBoolean(KEY_FULL_SCREEN, value).apply()
        }

    /** Text multiplier applied to every native screen; 1 is the designed size. */
    var textScale: Float
        get() = prefs.getFloat(KEY_TEXT_SCALE, 1f).coerceIn(MIN_SCALE, MAX_SCALE)
        set(value) {
            prefs.edit().putFloat(KEY_TEXT_SCALE, value.coerceIn(MIN_SCALE, MAX_SCALE)).apply()
        }

    /** Heading/value tone, or [AUTO] to follow the theme. */
    var inkColor: Int
        get() = prefs.getInt(KEY_INK, AUTO)
        set(value) {
            prefs.edit().putInt(KEY_INK, value).apply()
        }

    /** Label/small-print tone, or [AUTO] to follow the theme. */
    var mutedColor: Int
        get() = prefs.getInt(KEY_MUTED, AUTO)
        set(value) {
            prefs.edit().putInt(KEY_MUTED, value).apply()
        }

    /** Back to the designed look: light theme, default size, automatic colours. */
    fun reset() {
        prefs.edit()
            .remove(KEY_DARK)
            .remove(KEY_FULL_SCREEN)
            .remove(KEY_TEXT_SCALE)
            .remove(KEY_INK)
            .remove(KEY_MUTED)
            .apply()
    }

    companion object {
        /** Shared preference file; the flasher's own options live here too. */
        const val PREFS = "attaky_flasher"

        /** "Follow the theme" for the two text tones. */
        const val AUTO = 0

        const val MIN_SCALE = 0.85f
        const val MAX_SCALE = 1.45f

        private const val KEY_DARK = "appearance_dark"
        private const val KEY_FULL_SCREEN = "appearance_full_screen"
        private const val KEY_TEXT_SCALE = "appearance_text_scale"
        private const val KEY_INK = "appearance_ink"
        private const val KEY_MUTED = "appearance_muted"
    }
}
