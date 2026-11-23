package com.truelight.ime.ui.theme

import android.content.Context
import android.content.res.Configuration
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import com.truelight.ime.config.CssThemeParser
import com.truelight.ime.config.KeyboardPrefs

/**
 * Preallocated Paint and geometry objects for 100% zero-allocation onDraw.
 * Supports:
 *  - Android 12+ Material You Dynamic Color (zero external libraries!)
 *  - Automatic System Dark / Light adaptation on all Android versions
 *  - Explicit Dark, AMOLED Black, and Light themes
 *  - Optional CSS-like custom style sheet overrides via CssThemeParser
 */
class KeyboardTheme(val density: Float) {

    var colorBgKeyboard = 0xFF1E1F22.toInt()
    var colorBgKey = 0xFF2B2D30.toInt()
    var colorBgKeyPressed = 0xFF4E5157.toInt()
    var colorBgKeySpecial = 0xFF393B40.toInt()
    var colorKeyBorder = 0x22FFFFFF
    var colorKeyShadow = 0x55000000
    var colorTextPrimary = 0xFFFFFFFF.toInt()
    var colorTextSecondary = 0xFF9DA0A8.toInt()
    var colorAccent = 0xFF3574F0.toInt()
    var colorTextOnAccent = 0xFFFFFFFF.toInt()
    var colorStripBg = 0xFF17181B.toInt()
    var colorStripDivider = 0xFF32353B.toInt()
    var colorPopupBg = 0xFF45484F.toInt()

    // Customizable precision keycap geometry (mutated only on theme/CSS change)
    var keyCornerRadius = 6f * density
    var keyMarginH = 2.8f * density
    var keyMarginV = 4f * density
    var keyBorderWidth = maxOf(1f, 0.75f * density)
    var keyShadowHeight = 1.5f * density
    var baseFontSizePx = 20f * density

    // Preallocated Paint objects (mutated in-place on theme change -> 0 allocation in onDraw)
    val bgPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    val keyBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    val keyPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    val keySpecialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    val keyBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = keyBorderWidth
    }

    val keyShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    val textPrimaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 20f * density
        typeface = Typeface.DEFAULT
    }

    val textOnAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 20f * density
        typeface = Typeface.DEFAULT
    }

    val textSpecialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 14.5f * density
        typeface = Typeface.DEFAULT
    }

    val textSpacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 13.5f * density
        typeface = Typeface.DEFAULT
    }

    val textSecondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 10.5f * density
        typeface = Typeface.DEFAULT
    }

    val stripBgPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    val stripDividerPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    val suggestionTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 15.5f * density
        typeface = Typeface.DEFAULT
    }

    val suggestionBoldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 15.5f * density
        typeface = Typeface.DEFAULT_BOLD
    }

    val clipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        textSize = 13.5f * density
        typeface = Typeface.DEFAULT
    }

    val popupBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    val popupTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 26f * density
        typeface = Typeface.DEFAULT_BOLD
    }

    val popupSubTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 12f * density
        typeface = Typeface.DEFAULT_BOLD
    }

    val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.0f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    val iconSecondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.75f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    val iconOnAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.1f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    val iconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    val iconOnAccentFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // Reusable geometry instances (0 allocations in onDraw)
    val reusableRectF = RectF()
    val reusablePath = Path()

    init {
        syncPaints()
    }

    fun applyTheme(
        context: Context,
        themeMode: Int,
        customCssEnabled: Boolean = false,
        customCss: String = ""
    ) {
        // Reset precision geometry defaults before applying base palette & optional CSS
        keyCornerRadius = 6f * density
        keyMarginH = 2.8f * density
        keyMarginV = 4f * density
        keyBorderWidth = maxOf(1f, 0.75f * density)
        keyShadowHeight = 1.5f * density
        baseFontSizePx = 20f * density

        val isSystemNight = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        when (themeMode) {
            KeyboardPrefs.THEME_AMOLED -> applyAmoledPalette()
            KeyboardPrefs.THEME_DARK -> applyDarkPalette()
            KeyboardPrefs.THEME_LIGHT -> applyLightPalette()
            else -> {
                // THEME_SYSTEM: Use Android 12+ Material You Dynamic Color if available
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    applyDynamicSystemPalette(context, isSystemNight)
                } else if (isSystemNight) {
                    applyDarkPalette()
                } else {
                    applyLightPalette()
                }
            }
        }

        if (customCssEnabled && customCss.isNotBlank()) {
            val css = CssThemeParser.parse(customCss)
            css.bg?.let { colorBgKeyboard = it }
            css.stripBg?.let { colorStripBg = it }
            css.keyBg?.let { colorBgKey = it }
            css.keySpecialBg?.let { colorBgKeySpecial = it }
            css.keyPressedBg?.let {
                colorBgKeyPressed = it
                colorPopupBg = it
            }
            css.keyBorder?.let { colorKeyBorder = it }
            css.keyBorderWidthDp?.let { keyBorderWidth = (it * density).coerceIn(0f, 6f * density) }
            css.keyRadiusDp?.let { keyCornerRadius = (it * density).coerceIn(0f, 24f * density) }
            css.keyGapHDp?.let { keyMarginH = ((it * 0.5f) * density).coerceIn(0f, 10f * density) }
            css.keyGapVDp?.let { keyMarginV = ((it * 0.5f) * density).coerceIn(0f, 12f * density) }
            css.keyShadowDp?.let { keyShadowHeight = (it * density).coerceIn(0f, 6f * density) }
            css.textColor?.let { colorTextPrimary = it }
            css.subtextColor?.let { colorTextSecondary = it }
            css.accent?.let { colorAccent = it }
            css.textOnAccent?.let { colorTextOnAccent = it }
            css.fontSizeSp?.let { baseFontSizePx = (it * density).coerceIn(12f * density, 32f * density) }
        }

        syncPaints()
    }

    private fun applyDynamicSystemPalette(context: Context, isNight: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (isNight) {
            colorBgKeyboard = context.getColor(android.R.color.system_neutral1_900)
            colorStripBg = context.getColor(android.R.color.system_neutral1_1000)
            colorBgKey = context.getColor(android.R.color.system_neutral1_800)
            colorBgKeySpecial = context.getColor(android.R.color.system_neutral2_700)
            colorBgKeyPressed = context.getColor(android.R.color.system_accent1_700)
            colorPopupBg = context.getColor(android.R.color.system_accent2_700)
            colorKeyBorder = 0x20FFFFFF
            colorKeyShadow = 0x48000000
            colorAccent = context.getColor(android.R.color.system_accent1_200)
            colorTextOnAccent = context.getColor(android.R.color.system_accent1_900)
            colorTextPrimary = context.getColor(android.R.color.system_neutral1_50)
            colorTextSecondary = context.getColor(android.R.color.system_neutral2_300)
            colorStripDivider = context.getColor(android.R.color.system_neutral2_700)
        } else {
            colorBgKeyboard = context.getColor(android.R.color.system_neutral1_100)
            colorStripBg = context.getColor(android.R.color.system_neutral1_50)
            colorBgKey = context.getColor(android.R.color.system_neutral1_0)
            colorBgKeySpecial = context.getColor(android.R.color.system_neutral2_200)
            colorBgKeyPressed = context.getColor(android.R.color.system_accent1_200)
            colorPopupBg = context.getColor(android.R.color.system_accent1_100)
            colorKeyBorder = 0x1A000000
            colorKeyShadow = 0x26000000
            colorAccent = context.getColor(android.R.color.system_accent1_600)
            colorTextOnAccent = context.getColor(android.R.color.system_neutral1_0)
            colorTextPrimary = context.getColor(android.R.color.system_neutral1_900)
            colorTextSecondary = context.getColor(android.R.color.system_neutral2_600)
            colorStripDivider = context.getColor(android.R.color.system_neutral2_200)
        }
    }

    private fun applyDarkPalette() {
        colorBgKeyboard = 0xFF1E1F22.toInt()
        colorStripBg = 0xFF17181B.toInt()
        colorBgKey = 0xFF2B2D30.toInt()
        colorBgKeyPressed = 0xFF4E5157.toInt()
        colorBgKeySpecial = 0xFF393B40.toInt()
        colorPopupBg = 0xFF45484F.toInt()
        colorKeyBorder = 0x1FFFFFFF
        colorKeyShadow = 0x55000000
        colorTextPrimary = 0xFFFFFFFF.toInt()
        colorTextSecondary = 0xFF9DA0A8.toInt()
        colorAccent = 0xFF3574F0.toInt()
        colorTextOnAccent = 0xFFFFFFFF.toInt()
        colorStripDivider = 0xFF32353B.toInt()
    }

    private fun applyAmoledPalette() {
        colorBgKeyboard = 0xFF000000.toInt()
        colorStripBg = 0xFF000000.toInt()
        colorBgKey = 0xFF141518.toInt()
        colorBgKeyPressed = 0xFF32353B.toInt()
        colorBgKeySpecial = 0xFF1E2025.toInt()
        colorPopupBg = 0xFF2B2E35.toInt()
        colorKeyBorder = 0x2AFFFFFF
        colorKeyShadow = 0x00000000
        colorTextPrimary = 0xFFF5F6F8.toInt()
        colorTextSecondary = 0xFF8A8E96.toInt()
        colorAccent = 0xFF3574F0.toInt()
        colorTextOnAccent = 0xFFFFFFFF.toInt()
        colorStripDivider = 0xFF222429.toInt()
    }

    private fun applyLightPalette() {
        colorBgKeyboard = 0xFFE8ECF1.toInt()
        colorStripBg = 0xFFF4F6F9.toInt()
        colorBgKey = 0xFFFFFFFF.toInt()
        colorBgKeyPressed = 0xFFCFD8E6.toInt()
        colorBgKeySpecial = 0xFFD5DCE4.toInt()
        colorPopupBg = 0xFFFFFFFF.toInt()
        colorKeyBorder = 0x1C000000
        colorKeyShadow = 0x2A000000
        colorTextPrimary = 0xFF1A1C1E.toInt()
        colorTextSecondary = 0xFF5F6368.toInt()
        colorAccent = 0xFF1A73E8.toInt()
        colorTextOnAccent = 0xFFFFFFFF.toInt()
        colorStripDivider = 0xFFCFD6DF.toInt()
    }

    private fun syncPaints() {
        bgPaint.color = colorBgKeyboard
        keyBgPaint.color = colorBgKey
        keyPressedPaint.color = colorBgKeyPressed
        keySpecialPaint.color = colorBgKeySpecial
        accentPaint.color = colorAccent

        keyBorderPaint.color = colorKeyBorder
        keyBorderPaint.strokeWidth = keyBorderWidth
        keyShadowPaint.color = colorKeyShadow

        textPrimaryPaint.color = colorTextPrimary
        textPrimaryPaint.textSize = baseFontSizePx
        textOnAccentPaint.color = colorTextOnAccent
        textOnAccentPaint.textSize = baseFontSizePx
        textSpecialPaint.color = colorTextPrimary
        textSpecialPaint.textSize = (baseFontSizePx * 0.725f).coerceIn(11f * density, 18f * density)
        textSpacePaint.color = colorTextSecondary
        textSpacePaint.textSize = 13.5f * density
        textSecondaryPaint.color = colorTextSecondary

        stripBgPaint.color = colorStripBg
        stripDividerPaint.color = colorStripDivider
        suggestionTextPaint.color = colorTextPrimary
        suggestionBoldPaint.color = colorAccent
        clipTextPaint.color = colorTextPrimary

        popupBgPaint.color = colorPopupBg
        popupTextPaint.color = colorTextPrimary
        popupTextPaint.textSize = (baseFontSizePx * 1.3f).coerceAtLeast(22f * density)
        popupSubTextPaint.color = colorAccent

        iconPaint.color = colorTextPrimary
        iconSecondaryPaint.color = colorTextSecondary
        iconOnAccentPaint.color = colorTextOnAccent
        iconFillPaint.color = colorTextPrimary
        iconOnAccentFillPaint.color = colorTextOnAccent
    }
}
