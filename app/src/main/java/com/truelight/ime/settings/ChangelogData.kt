package com.truelight.ime.settings

/**
 * Structured in-app release notes living strictly in the isolated `:settings` process
 * (adds 0 bytes to the pure-Canvas `TrueLightIME` keyboard process).
 *
 * Keep newest version at index 0 and update on every release.
 */
object ChangelogData {

    enum class ChangeType(val badgeLabel: String) {
        NEW("NEW"),
        IMPROVED("IMPROVED"),
        FIXED("FIXED"),
        PERFORMANCE("SPEED")
    }

    data class ChangeItem(
        val type: ChangeType,
        val text: String
    )

    data class ReleaseNote(
        val version: String,
        val tag: String? = null,
        val date: String,
        val headline: String,
        val changes: List<ChangeItem>
    )

    val RELEASES: List<ReleaseNote> = listOf(
        ReleaseNote(
            version = "1.4.1",
            tag = "Latest",
            date = "Nov 2026",
            headline = "Android 5–6 Compatibility Fixes, Cleaner Memory Card & GPLv3 Launch",
            changes = listOf(
                ChangeItem(
                    ChangeType.FIXED,
                    "Guarded Android 7.0+ clipboard and updater API calls so Android 5.0–6.0 (API 21–23) devices run cleanly with zero compatibility crashes."
                ),
                ChangeItem(
                    ChangeType.IMPROVED,
                    "Simplified the Home Keyboard Memory card to show only live real-time telemetry with an instant Light / Normal status badge."
                ),
                ChangeItem(
                    ChangeType.IMPROVED,
                    "Licensed under GNU GPLv3 with refreshed public documentation and zero Android Lint errors."
                )
            )
        ),
        ReleaseNote(
            version = "1.4.0",
            date = "Nov 2026",
            headline = "One-Handed Mode, Emoji Search, Clipboard Pinning & Smarter Standby Trim",
            changes = listOf(
                ChangeItem(
                    ChangeType.NEW,
                    "One-Handed Mode (Left / Right) with an 82% thumb-friendly layout, 1-tap Top Utility Bar toggle, and side gutter Expand & Flip controls."
                ),
                ChangeItem(
                    ChangeType.NEW,
                    "Inline offline English Emoji Search directly in the top strip plus long-press 6-shade skin-tone floating pill selector."
                ),
                ChangeItem(
                    ChangeType.NEW,
                    "Shift + Spacebar horizontal drag selection to highlight text smoothly left or right."
                ),
                ChangeItem(
                    ChangeType.NEW,
                    "Clipboard Pin & per-item Delete buttons, long-press to pin, Clear preserves pinned clips, and configurable auto-clear timers (Off / 15m / 30m / 60m)."
                ),
                ChangeItem(
                    ChangeType.IMPROVED,
                    "Redesigned Emoji Panel with an airy 7-column grid, Material 3 pill tab indicator, subtle scrollbar, and crisp pure-Canvas vector icons."
                ),
                ChangeItem(
                    ChangeType.PERFORMANCE,
                    "Aggressive standby memory trim releases Emoji, Search index, and transient caches shortly after hiding the keyboard."
                )
            )
        ),
        ReleaseNote(
            version = "1.3.0",
            date = "Nov 2026",
            headline = "100% Customization, Bulletproof Telex/VNI & -42% Active RAM",
            changes = listOf(
                ChangeItem(
                    ChangeType.NEW,
                    "Full keyboard element controls: toggle key preview popup, top utility bar, Emoji position (Top Bar / Bottom / Hidden), settings gear, comma, and period keys."
                ),
                ChangeItem(
                    ChangeType.NEW,
                    "Vietnamese Phonotactics engine with English auto-restore (window, word, user, software, chrome, debug...) plus European accented long-press floating pill."
                ),
                ChangeItem(
                    ChangeType.NEW,
                    "Haptic strength levels (Light / Medium / Strong), native keypress sound toggle, adjustable long-press delay (200–600 ms), and individual gesture switches."
                ),
                ChangeItem(
                    ChangeType.NEW,
                    "In-app What's New changelog viewer with full version history."
                ),
                ChangeItem(
                    ChangeType.FIXED,
                    "Fixed Telex double-w (ww → w, www → ww, nhww → nhw), compound ua+w (giuawx → giữa), and VNI mixed alphanumeric tokens (v1, mp3, h2o)."
                ),
                ChangeItem(
                    ChangeType.PERFORMANCE,
                    "Software-rendered IME window cuts active keyboard RAM by 42% (24.5 MB → 14.2 MB, 0 KB GPU/EGL) while keeping Settings hardware-accelerated."
                ),
                ChangeItem(
                    ChangeType.IMPROVED,
                    "Refreshed Material Design 3 Settings with theme color swatches, segmented pill selectors, and cleaner visual hierarchy."
                )
            )
        ),
        ReleaseNote(
            version = "1.2.2",
            date = "Nov 2026",
            headline = "5-Key Bottom Row, Image Clipboard & Keypress Animations",
            changes = listOf(
                ChangeItem(
                    ChangeType.NEW,
                    "Image clipboard support via commitContent with lazy RGB_565 micro-thumbnails recycled immediately on close."
                ),
                ChangeItem(
                    ChangeType.NEW,
                    "Long-press Enter floating 2-option picker (Emoji / Newline) plus visual Shift + Enter newline icon."
                ),
                ChangeItem(
                    ChangeType.NEW,
                    "Subtle 95 ms zero-allocation keypress scale and fade micro-animations."
                ),
                ChangeItem(
                    ChangeType.IMPROVED,
                    "Decluttered 5-key bottom row paired with an idle Top Utility Bar and hardened signed APK release builds."
                )
            )
        ),
        ReleaseNote(
            version = "1.2.1",
            date = "Nov 2026",
            headline = "Simplified M3 Dashboard & Live RAM Telemetry",
            changes = listOf(
                ChangeItem(
                    ChangeType.IMPROVED,
                    "Cleaner Material Design 3 dashboard with live Active / Standby RAM comparison bars and pinned header status pill."
                ),
                ChangeItem(
                    ChangeType.IMPROVED,
                    "Public-ready project documentation and clearer setup flow."
                )
            )
        ),
        ReleaseNote(
            version = "1.2.0",
            date = "Nov 2026",
            headline = "Dual-Process Isolation, Expanded Emoji & Auto-Updater",
            changes = listOf(
                ChangeItem(
                    ChangeType.PERFORMANCE,
                    "Isolated Material Design 3 Settings into a separate :settings process over Binder IPC (0 AndroidX/Material/Network imports in IME)."
                ),
                ChangeItem(
                    ChangeType.NEW,
                    "Expanded 12-category lazy-loaded Emoji, Kaomoji, and Math & Tech panel."
                ),
                ChangeItem(
                    ChangeType.NEW,
                    "On-demand GitHub Releases updater with SHA-256 verification strictly inside :settings."
                )
            )
        ),
        ReleaseNote(
            version = "1.1.0",
            date = "Nov 2026",
            headline = "Global-First Multi-Language Engine & Offline Suggestions",
            changes = listOf(
                ChangeItem(
                    ChangeType.NEW,
                    "Default English (US) with opt-in Vietnamese (Telex / VNI), French, German, Spanish, Russian, and Korean (Hangul)."
                ),
                ChangeItem(
                    ChangeType.NEW,
                    "Offline text suggestions, auto-correct with Backspace undo, Material You Dynamic Theme, and Custom CSS theme parser."
                ),
                ChangeItem(
                    ChangeType.PERFORMANCE,
                    "100% pure single-Canvas renderer with multi-touch 2-thumb rollover and zero per-frame allocations."
                )
            )
        )
    )
}
