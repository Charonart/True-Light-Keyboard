package com.truelight.ime.ui.theme

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Preallocated Paint and geometry objects for 100% zero-allocation onDraw.
 */
class KeyboardTheme(density: Float) {

    val colorBgKeyboard = 0xFF1E1F22.toInt()
    val colorBgKey = 0xFF2B2D30.toInt()
    val colorBgKeyPressed = 0xFF4E5157.toInt()
    val colorBgKeySpecial = 0xFF393B40.toInt()
    val colorTextPrimary = 0xFFFFFFFF.toInt()
    val colorTextSecondary = 0xFF9DA0A8.toInt()
    val colorAccent = 0xFF3574F0.toInt()

    val keyCornerRadius = 6f * density
    val keyMarginH = 3f * density
    val keyMarginV = 4f * density

    // Preallocated Paint objects
    val bgPaint = Paint().apply {
        color = colorBgKeyboard
        style = Paint.Style.FILL
    }

    val keyBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorBgKey
        style = Paint.Style.FILL
    }

    val keyPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorBgKeyPressed
        style = Paint.Style.FILL
    }

    val keySpecialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorBgKeySpecial
        style = Paint.Style.FILL
    }

    val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorAccent
        style = Paint.Style.FILL
    }

    val textPrimaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorTextPrimary
        textAlign = Paint.Align.CENTER
        textSize = 20f * density
        typeface = Typeface.DEFAULT
    }

    val textSecondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorTextSecondary
        textAlign = Paint.Align.CENTER
        textSize = 11f * density
        typeface = Typeface.DEFAULT
    }

    val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorTextPrimary
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // Reusable geometry instances
    val reusableRectF = RectF()
    val reusablePath = Path()
}
