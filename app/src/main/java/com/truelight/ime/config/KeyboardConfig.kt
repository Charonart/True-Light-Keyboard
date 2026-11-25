package com.truelight.ime.config

import com.truelight.ime.lang.LanguageSpec

/**
 * Immutable configuration snapshot passed from [com.truelight.ime.TrueLightIME]
 * to [com.truelight.ime.ui.KeyboardCanvasView.applyConfig].
 */
data class KeyboardConfig(
    val themeMode: Int,
    val customCssEnabled: Boolean,
    val customCss: String,
    val heightDp: Int,
    val showNumberRow: Boolean,
    val showSubLabels: Boolean,
    val longPressSymbols: Boolean,
    val keyPopupEnabled: Boolean,
    val keypressAnimation: Boolean,
    val showTopUtilityBar: Boolean,
    val emojiButtonMode: Int,
    val showSettingsKey: Boolean,
    val showCommaKey: Boolean,
    val showPeriodKey: Boolean,
    val longPressEnterAction: Int,
    val longPressDelayMs: Int,
    val spaceCursorGlide: Boolean,
    val spaceLongPressLang: Boolean,
    val swipeDeleteWord: Boolean,
    val hapticEnabled: Boolean,
    val hapticStrength: Int,
    val soundEnabled: Boolean,
    val languageSpec: LanguageSpec,
    val multipleLanguages: Boolean,
    val oneHandedMode: Int = KeyboardPrefs.ONE_HANDED_OFF
)
