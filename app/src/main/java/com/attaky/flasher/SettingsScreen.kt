package com.attaky.flasher

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/**
 * The settings screen's behaviour.
 *
 * Four appearance choices: light or dark, full screen, text size and the two
 * text tones, plus the neon category of tones aimed at the dark page. Each one is
 * written to [AppSettings] the moment it changes and the host repaints the
 * screen, so the result is visible without leaving Settings.
 */
class SettingsScreen(
    private val activity: Activity,
    private val settings: AppSettings,
    private val root: View,
    private val onMenu: () -> Unit,
    private val onChanged: () -> Unit,
) {

    private class Swatch(val color: Int, val label: Int)

    private val themeLight: Button = root.findViewById(R.id.themeLight)
    private val themeDark: Button = root.findViewById(R.id.themeDark)
    private val fullScreenToggle: CheckBox = root.findViewById(R.id.fullScreenToggle)
    private val scaleBar: SeekBar = root.findViewById(R.id.textScaleBar)
    private val scaleValue: TextView = root.findViewById(R.id.textScaleValue)
    private val inkSwatches: LinearLayout = root.findViewById(R.id.inkSwatches)
    private val mutedSwatches: LinearLayout = root.findViewById(R.id.mutedSwatches)
    private val neonSwatches: LinearLayout = root.findViewById(R.id.neonSwatches)

    private val options = listOf(
        Swatch(AppSettings.AUTO, R.string.settings_color_auto),
        Swatch(color(R.color.swatch_white), R.string.settings_color_white),
        Swatch(color(R.color.swatch_ink), R.string.settings_color_ink),
        Swatch(color(R.color.swatch_red), R.string.settings_color_red),
        Swatch(color(R.color.swatch_amber), R.string.settings_color_amber),
        Swatch(color(R.color.swatch_green), R.string.settings_color_green),
        Swatch(color(R.color.swatch_blue), R.string.settings_color_blue),
        Swatch(color(R.color.swatch_violet), R.string.settings_color_violet),
    )

    /** The neon category: the same pick, in the tones made for the dark page. */
    private val neonOptions = listOf(
        Swatch(color(R.color.swatch_neon_green), R.string.settings_color_neon_green),
        Swatch(color(R.color.swatch_neon_cyan), R.string.settings_color_neon_cyan),
        Swatch(color(R.color.swatch_neon_blue), R.string.settings_color_neon_blue),
        Swatch(color(R.color.swatch_neon_violet), R.string.settings_color_neon_violet),
        Swatch(color(R.color.swatch_neon_magenta), R.string.settings_color_neon_magenta),
        Swatch(color(R.color.swatch_neon_red), R.string.settings_color_neon_red),
        Swatch(color(R.color.swatch_neon_orange), R.string.settings_color_neon_orange),
        Swatch(color(R.color.swatch_neon_yellow), R.string.settings_color_neon_yellow),
    )

    fun start() {
        root.findViewById<View>(R.id.btnMenu).setOnClickListener { onMenu() }

        themeLight.setOnClickListener { setDark(false) }
        themeDark.setOnClickListener { setDark(true) }

        fullScreenToggle.setOnCheckedChangeListener { _, checked ->
            if (settings.fullScreen == checked) return@setOnCheckedChangeListener
            settings.fullScreen = checked
            onChanged()
        }

        scaleBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                settings.textScale = scaleFor(progress)
                scaleValue.text = percent()
                onChanged()
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit

            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })

        root.findViewById<View>(R.id.settingsReset).setOnClickListener {
            settings.reset()
            render()
            onChanged()
        }

        render()
    }

    /** The theme is a repaint, not a restart: the host re-colours the screen. */
    private fun setDark(dark: Boolean) {
        if (settings.dark == dark) return
        settings.dark = dark
        render()
        onChanged()
    }

    private fun render() {
        // The chosen theme wears the primary treatment; the other one the outline.
        themeLight.tag = if (settings.dark) AttakyTheme.TAG_OUTLINE else AttakyTheme.TAG_PRIMARY
        themeDark.tag = if (settings.dark) AttakyTheme.TAG_PRIMARY else AttakyTheme.TAG_OUTLINE

        fullScreenToggle.isChecked = settings.fullScreen

        val progress = progressFor(settings.textScale)
        if (scaleBar.progress != progress) scaleBar.progress = progress
        scaleValue.text = percent()

        fill(inkSwatches, options, settings.inkColor) { picked ->
            settings.inkColor = picked
            render()
            onChanged()
        }
        fill(mutedSwatches, options, settings.mutedColor) { picked ->
            settings.mutedColor = picked
            render()
            onChanged()
        }
        // The neon pick is the primary tone too: same slot, brighter palette.
        fill(neonSwatches, neonOptions, settings.inkColor) { picked ->
            settings.inkColor = picked
            render()
            onChanged()
        }
    }

    /** Four swatches to a line, so the row fits a phone without scrolling. */
    private fun fill(row: LinearLayout, options: List<Swatch>, current: Int, onPick: (Int) -> Unit) {
        row.removeAllViews()
        var line: LinearLayout? = null
        options.forEachIndexed { index, option ->
            if (index % 4 == 0) {
                line = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                }.also { row.addView(it) }
            }
            line?.addView(swatch(option, option.color == current) { onPick(option.color) })
        }
    }

    private fun swatch(option: Swatch, selected: Boolean, onClick: () -> Unit): View {
        val palette = AttakyTheme.palette(activity, settings)
        val frame = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(6) }
            isClickable = true
            isFocusable = true
            contentDescription = buildString {
                append(activity.getString(option.label))
                if (selected) append(", selected")
            }
            setOnClickListener { onClick() }
            if (selected) {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(android.graphics.Color.TRANSPARENT)
                    setStroke(dp(2), palette.red)
                }
            }
        }

        // "Automatic" is drawn as the theme's own text tone, so the swatch reads
        // as "whatever this theme uses" rather than a colour of its own.
        val fill = if (option.color == AppSettings.AUTO) {
            withAlpha(palette.ink, 0x59)
        } else {
            option.color
        }
        val dot = View(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(fill)
                setStroke(dp(1), palette.border)
            }
        }
        frame.addView(dot, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
        return frame
    }

    private fun scaleFor(progress: Int): Float =
        AppSettings.MIN_SCALE +
            (AppSettings.MAX_SCALE - AppSettings.MIN_SCALE) * progress.coerceIn(0, 100) / 100f

    private fun progressFor(scale: Float): Int =
        (((scale - AppSettings.MIN_SCALE) / (AppSettings.MAX_SCALE - AppSettings.MIN_SCALE)) * 100)
            .roundToInt()
            .coerceIn(0, 100)

    private fun percent(): String = "${(settings.textScale * 100).roundToInt()}%"

    private fun color(id: Int): Int = ContextCompat.getColor(activity, id)

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha shl 24)

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).roundToInt()
}
