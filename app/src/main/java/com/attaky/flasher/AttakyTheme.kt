package com.attaky.flasher

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * The colours one screen is drawn in, already resolved: a light or dark set,
 * with the user's chosen text tones folded in.
 **/
data class Palette(
    val dark: Boolean,
    val bg: Int,
    val surface: Int,
    val well: Int,
    val border: Int,
    val press: Int,
    val ink: Int,
    val muted: Int,
    val red: Int,
    val redPress: Int,
    val onRed: Int,
    /** Wordmark artwork for this theme: red on light, light on dark. */
    val logo: Int,
)

/**
 * Paints the native screens.
 *
 * The layouts are written once, against the light palette in `colors.xml`, and
 * tagged with the role each part plays (`attaky:card`, `attaky:ink`, ...). This
 * object walks a finished view tree and re-paints it from a [Palette], so a
 * theme change, a text-size change or a chosen text colour needs no layout
 * duplication and no activity restart. Drawables are built here too, because a
 * card's fill and hairline depend on the palette.
 *
 * The walk is idempotent: every TextView's designed size is remembered the first
 * time it is seen, so moving the slider multiplies that size rather than the
 * last result.
 */
object AttakyTheme {

    // Tag values layouts use to say what a view is. Public because a screen may
    // change a view's role at runtime (the selected theme button, for instance).
    const val TAG_BG = "attaky:bg"
    const val TAG_BAR = "attaky:bar"
    const val TAG_CARD = "attaky:card"
    const val TAG_WELL = "attaky:well"
    const val TAG_OUTLINE = "attaky:outline"
    const val TAG_PRIMARY = "attaky:primary"
    const val TAG_INK = "attaky:ink"
    const val TAG_MUTED = "attaky:muted"

    /** Brand-red text on no fill: a small action, like the queue's plus. */
    const val TAG_ACCENT = "attaky:accent"
    const val TAG_CHECK = "attaky:check"
    const val TAG_PROGRESS = "attaky:progress"

    /** The palette the settings currently ask for. */
    fun palette(context: Context, settings: AppSettings): Palette {
        fun color(id: Int) = ContextCompat.getColor(context, id)
        val themed = if (settings.dark) {
            Palette(
                dark = true,
                bg = color(R.color.attaky_bg_dark),
                surface = color(R.color.attaky_surface_dark),
                well = color(R.color.attaky_well_dark),
                border = color(R.color.attaky_border_dark),
                press = color(R.color.attaky_press_dark),
                ink = color(R.color.attaky_ink_dark),
                muted = color(R.color.attaky_muted_dark),
                red = color(R.color.attaky_red_dark),
                redPress = color(R.color.attaky_red_press_dark),
                onRed = Color.WHITE,
                logo = R.drawable.attaky_logo_dark,
            )
        } else {
            Palette(
                dark = false,
                bg = color(R.color.attaky_bg),
                surface = color(R.color.attaky_surface),
                well = color(R.color.attaky_bg),
                border = color(R.color.attaky_border),
                press = color(R.color.attaky_press),
                ink = color(R.color.attaky_ink),
                muted = color(R.color.attaky_muted),
                red = color(R.color.attaky_red),
                redPress = color(R.color.attaky_red_press),
                onRed = Color.WHITE,
                logo = R.drawable.attaky_logo,
            )
        }
        // The chosen text tone doubles as the action colour: CONNECT, FLASH, OTA,
        // SEND and every other primary button wear it, and so does everything else
        // that used to be brand red — the progress bar, the checkbox tick, the
        // ring on the selected swatch — so a page set to green is green all the
        // way through. The button's label flips to near-black on a pale tone, so
        // picking white does not paint white on white.
        val action = if (settings.inkColor != AppSettings.AUTO) settings.inkColor else themed.red
        return themed.copy(
            ink = if (settings.inkColor != AppSettings.AUTO) settings.inkColor else themed.ink,
            muted = if (settings.mutedColor != AppSettings.AUTO) settings.mutedColor else themed.muted,
            red = action,
            redPress = if (action == themed.red) themed.redPress else shade(action),
            onRed = if (ColorUtils.calculateLuminance(action) > 0.55) Color.BLACK else Color.WHITE,
        )
    }

    /** The same tone a shade down, for the pressed state of an action button. */
    private fun shade(color: Int): Int = ColorUtils.blendARGB(color, Color.BLACK, 0.18f)

    /** Repaint [root] and everything under it. */
    fun apply(root: View?, palette: Palette, scale: Float) {
        if (root == null) return
        paint(root, palette, scale)
    }

    /** Repaint a screen that was built from [settings]. */
    fun apply(root: View?, context: Context, settings: AppSettings) {
        apply(root, palette(context, settings), settings.textScale)
    }

    /**
     * Window chrome lives outside the view tree: the window behind the page, the
     * bar colours, their icon tint and the full-screen mode.
     */
    fun applyWindow(activity: Activity, palette: Palette, settings: AppSettings) {
        val window = activity.window
        window.setBackgroundDrawable(ColorDrawable(palette.bg))
        window.statusBarColor = palette.bg
        window.navigationBarColor = palette.bg

        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !palette.dark
        controller.isAppearanceLightNavigationBars = !palette.dark
        if (settings.fullScreen) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** A dialog that matches the theme it is opened over. */
    fun dialog(context: Context, palette: Palette): AlertDialog.Builder =
        if (palette.dark) {
            AlertDialog.Builder(
                ContextThemeWrapper(context, R.style.Theme_AttakyFlasher_Dialog_Dark),
            )
        } else {
            AlertDialog.Builder(context)
        }

    // ------------------------------------------------------------------ painting

    private fun paint(view: View, palette: Palette, scale: Float) {
        when (view.tag as? String) {
            TAG_BG -> view.setBackgroundColor(palette.bg)
            TAG_BAR -> view.setBackgroundColor(palette.surface)
            TAG_CARD -> view.background = card(palette)
            TAG_WELL -> view.background = well(palette)
            TAG_OUTLINE -> {
                view.background = outline(palette)
                (view as? TextView)?.let {
                    it.setTextColor(palette.ink)
                    it.setHintTextColor(palette.muted)
                }
            }
            TAG_PRIMARY -> {
                view.background = primary(palette)
                (view as? TextView)?.setTextColor(palette.onRed)
            }
            TAG_INK -> (view as? TextView)?.setTextColor(palette.ink)
            TAG_MUTED -> (view as? TextView)?.setTextColor(palette.muted)
            TAG_ACCENT -> (view as? TextView)?.setTextColor(palette.red)
            TAG_CHECK -> (view as? CheckBox)?.let {
                it.setTextColor(palette.ink)
                it.buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(palette.red, palette.muted),
                )
            }
            TAG_PROGRESS -> (view as? ProgressBar)?.let {
                it.progressTintList = ColorStateList.valueOf(palette.red)
                it.progressBackgroundTintList = ColorStateList.valueOf(palette.press)
                it.indeterminateTintList = ColorStateList.valueOf(palette.red)
                // A slider's handle is the part that has to stay visible.
                (it as? SeekBar)?.thumbTintList = ColorStateList.valueOf(palette.red)
            }
        }

        if (view is TextView) resize(view, scale)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) paint(view.getChildAt(index), palette, scale)
        }
    }

    /** Scale a TextView from the size its layout asked for, never from the last scale. */
    private fun resize(view: TextView, scale: Float) {
        val base = view.getTag(R.id.attaky_base_text_px) as? Float
            ?: view.textSize.also { view.setTag(R.id.attaky_base_text_px, it) }
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, base * scale)
    }

    // ------------------------------------------------------------------ drawables

    private fun card(palette: Palette): StateListDrawable = stateful(
        fill = palette.surface,
        pressed = palette.press,
        stroke = palette.border,
        radius = 12f,
    )

    private fun well(palette: Palette): GradientDrawable =
        rounded(palette.well, palette.border, 10f)

    private fun outline(palette: Palette): StateListDrawable = stateful(
        fill = palette.surface,
        pressed = palette.press,
        stroke = palette.border,
        radius = 10f,
    )

    private fun primary(palette: Palette): StateListDrawable = stateful(
        fill = palette.red,
        pressed = palette.redPress,
        stroke = null,
        radius = 10f,
    )

    private fun stateful(fill: Int, pressed: Int, stroke: Int?, radius: Float): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), rounded(pressed, stroke, radius))
            addState(intArrayOf(), rounded(fill, stroke, radius))
        }

    private fun rounded(fill: Int, stroke: Int?, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radius)
            setColor(fill)
            if (stroke != null) setStroke(dp(1f).toInt().coerceAtLeast(1), stroke)
        }

    private fun dp(value: Float): Float =
        value * android.content.res.Resources.getSystem().displayMetrics.density
}
