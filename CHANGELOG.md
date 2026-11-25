# Changelog

All notable changes to **True Light Keyboard** are documented in this file.

---

## [1.4.0] — 2026-11-25

### New
- **One-Handed Mode (Left / Right)**: Shrinks keyboard width to ~82% with quick side-gutter vector controls to flip sides or expand back to full width (0 KB RAM).
- **Pinned Clipboard & Privacy Auto-Clear**: Pin favorite clips to keep them at the top across clears, delete individual items, and optionally auto-clear unpinned clips after 15, 30, or 60 minutes.
- **Shift + Spacebar Text Selection**: Hold Shift and glide horizontally on the Spacebar to select text left or right (0 KB RAM).
- **Offline English Emoji Search**: Tap the vector search icon inside the Emoji panel to filter emojis in real time directly above the keyboard with zero extra views.
- **Emoji Skin-Tone Picker**: Long-press supported hand and people emojis to pick a skin tone cleanly.

### Improved
- **Polished Emoji Panel UI**: Airier 7-column grid, Material 3 pill category tabs, crisp vector icons (Search & Recent), and cleaner category headers.

### Performance
- **Aggressive Standby RAM Trim**: Automatically releases transient Emoji/search caches and thumbnails shortly after the keyboard hides to keep standby memory minimal.

---

## [1.3.0] — 2026-11-23

### New
- **100% Granular Keyboard Customization**: Toggle key preview popup, top utility bar, comma/period keys, settings button, emoji button placement (`Top Bar` / `Bottom` / `Hidden`), keypress sound, haptic strength (`Light` / `Medium` / `Strong`), long-press delay (`200–600ms`), spacebar cursor glide, spacebar long-press language switch, swipe backspace word delete, and smart punctuation.
- **Vietnamese Phonotactics & Auto-Restore**: Automatic English/foreign word restoration while typing in Vietnamese mode (`window`, `word`, `work`, `user`, `software`, `chrome`, `debug`, `camera`, etc.) with a dedicated toggle.
- **European Accented Character Popups**: Long-press base letters in French, German, and Spanish layouts to pick accented variants cleanly.
- **In-App Changelog ("What's New")**: Dedicated Material 3 changelog sheet inside Settings with categorized badges (`New`, `Improved`, `Fixed`, `RAM`) and clean release cards.

### Fixed
- **Telex Double-`w` & Compound `ua+w` Bugs**: Typing `ww` now produces `w` (`www` → `ww`, `nhww` → `nhw`) instead of `uw` while keeping `uww` → `uw` and `luuww` → `luuw` accurate; compound `ua+w` (`giuawx` → `giữa`, `muaw` → `mưa`, `suawx` → `sữa`) now transforms properly while preserving `quawn` → `quăn`.
- **VNI Mixed Alphanumeric Guard**: Words like `v1`, `mp3`, `h2o`, `chrome1`, and `covid19` no longer get corrupted by VNI tone digits.

### Performance
- **-42% Active Keyboard RAM**: Software-rendered IME window (`android:hardwareAccelerated="false"` on IME, `"true"` on `:settings`) cuts active Alpha keyboard RAM from **24.5 MB → 14.2 MB** (**0 KB** GPU/EGL overhead) with razor-sharp Canvas & Color Emoji rendering.

---

## [1.2.2] — 2026-11-23

### New
- **Image Clipboard Support**: Paste copied images directly via `commitContent` with lazy `RGB_565` micro-thumbnails that recycle immediately when the panel closes.
- **Zero-Allocation Keypress Animation**: Subtle 95ms spring-like key press animation with zero heap allocations inside `onDraw`.
- **Enter Long-Press Floating Mini-Popup**: Choose between Emoji and Newline without losing newline access, plus visual `↵` icon when holding Shift + Enter.

### Improved
- **Decluttered 5-Key Bottom Row**: Clean `?123`, `,`, `Space`, `.`, `Enter` bottom bar paired with an idle Top Utility Bar (`Clipboard`, `Emoji`, `Language`, `Settings`).
- **Hardened Signed Release Pipeline**: Verified keystore signing in GitHub Actions so every release ships a properly signed `app-release.apk`.

---

## [1.2.1] — 2026-11-23

### Improved
- **Simplified Material 3 Settings UI**: Removed confusing memory jargon and pinned a clean live `Active` / `Standby` RAM pill in the Settings header.
- **Public-Ready README**: Polished project documentation with clear architecture overview and privacy guarantees.

---

## [1.2.0] — 2026-11-23

### New
- **Dual-Process Isolation (`:settings`)**: Material Design 3 Settings and In-App GitHub Auto-Updater run in an isolated `:settings` process communicating over Binder IPC, keeping zero AndroidX/Material/Network imports inside the main IME process.
- **Expanded 12-Category Emoji Panel**: Lazy-loaded Emoji, Kaomoji, and Math & Tech symbols.
- **In-App GitHub Auto-Updater**: On-demand update check with SHA-256 verification inside `:settings`.

---

## [1.1.0] — 2026-11-22

### New
- **Global-First Multi-Language Support**: Default English (US) with opt-in Vietnamese (Telex / VNI), French, German, Spanish, Russian, and Korean (Hangul).
- **2-Toggle Text Suggestions**: Independent `Suggestions` and `Auto-correct` toggles with Backspace Undo and English code-switching protection.
- **Material You Dynamic Theme & Custom Theme Parser**: Android 12+ dynamic colors plus custom CSS-like theme support.
- **Multi-Touch 2-Thumb Rollover**: Zero dead zones with crisp vector Canvas icons.
