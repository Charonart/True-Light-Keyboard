package com.truelight.ime.ui

import android.graphics.RectF

/**
 * Key representation with preallocated bounds for zero-allocation rendering.
 */
class KeyModel(
    var code: Int = 0,
    var label: String = "",
    var subLabel: String? = null,
    var type: Int = TYPE_NORMAL
) {
    companion object {
        const val TYPE_NORMAL = 0
        const val TYPE_SHIFT = 1
        const val TYPE_DELETE = 2
        const val TYPE_ENTER = 3
        const val TYPE_SPACE = 4
        const val TYPE_SWITCH_SYMBOLS = 5
        const val TYPE_SWITCH_ALPHA = 6
        const val TYPE_SWITCH_NUMPAD = 7
        const val TYPE_EMOJI = 8
        const val TYPE_SWITCH_LANG = 9

        const val CODE_SHIFT = -1
        const val CODE_SWITCH_SYMBOLS = -2
        const val CODE_SWITCH_ALPHA = -3
        const val CODE_ENTER = -4
        const val CODE_DELETE = -5
        const val CODE_SWITCH_NUMPAD = -6
        const val CODE_EMOJI = -7
        const val CODE_SWITCH_LANG = -8
        const val CODE_SPACE = 32
    }

    val bounds = RectF()
    var isPressed = false

    fun contains(x: Float, y: Float): Boolean {
        return bounds.contains(x, y)
    }
}
