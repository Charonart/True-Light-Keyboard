<div align="center">

# True Light Keyboard

**An ultra-lightweight, privacy-first, single-canvas Android keyboard.**

[![Android CI](https://github.com/Charonart/True-Light-Keyboard/actions/workflows/ci.yml/badge.svg)](https://github.com/Charonart/True-Light-Keyboard/actions/workflows/ci.yml)
[![Latest Release](https://img.shields.io/github/v/release/Charonart/True-Light-Keyboard?label=release&color=blue)](https://github.com/Charonart/True-Light-Keyboard/releases/latest)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-5.0%2B%20(API%2021%E2%80%9336%2B)-3DDC84?logo=android&logoColor=white)](#android-compatibility)
[![Offline IME](https://img.shields.io/badge/Keyboard%20Process-100%25%20Offline-2ea44f)](#architecture--privacy)

[Download Latest APK](https://github.com/Charonart/True-Light-Keyboard/releases/latest) · [Changelog](CHANGELOG.md) · [Android Compatibility](#android-compatibility) · [Build from Source](#build-from-source)

</div>

---

## Overview

Most modern Android keyboards bundle heavy UI frameworks, embedded browsers, telemetry databases, and background services—often consuming **150+ MB of RAM** even when idle.

**True Light Keyboard** is engineered from the ground up to stay fast, predictable, and tiny:

- **Single-Canvas Pure-Framework IME (`com.truelight.ime`)** — The keyboard renders onto one native Android `View` using preallocated `Paint`, `RectF`, and `Path` objects (zero heap allocations inside `onDraw`). No AndroidX, no Material libraries, no native `.so` binaries, and **zero networking code**.
- **Isolated Material 3 Settings (`com.truelight.ime:settings`)** — Settings, Material You theming, and the on-demand GitHub updater run in a separate `:settings` process over Binder IPC. When you close Settings, Android reclaims 100% of its memory so the keyboard stays lightweight.

---

## Measured Footprint (Reproducible)

Measured using `adb shell dumpsys meminfo com.truelight.ime` with the signed **Release APK** (`v1.4.1`, ~1.56 MB, 0 native `.so` libraries, 0 SQLite databases) on an AOSP Android Emulator (1080×2400, 420 dpi):

| Process State | Total PSS | Notes |
| :--- | ---: | :--- |
| **Cold Standby** (`com.truelight.ime`) | **~9.1 MB** | Before opening the Emoji panel |
| **Active Typing** (Alpha / One-Handed) | **~14.2 – 14.5 MB** | `0 KB` GPU/EGL (software Canvas IME), `~1.2 MB` Java heap |
| **Active Emoji Panel** | **~17.2 MB** | Android memory-maps system `NotoColorEmoji.ttf` when emojis render |
| **Settings UI** (`com.truelight.ime:settings`) | *Separate process* | Loads Material 3 only while Settings is open; reclaimed by Android on close |

> **Full transparency on real devices:** Total PSS varies by Android version, screen DPI, and OEM framework/font overlays—expect roughly **15–30 MB** on physical devices depending on ROM and whether the Emoji panel or offline suggestions are enabled. Most of the ~1.56 MB APK size comes from the Material 3 library used exclusively in `:settings`; the keyboard process itself has zero AndroidX/Material dependencies. You can verify on your own device anytime with:
> ```bash
> adb shell dumpsys meminfo com.truelight.ime
> ```

---

## Features

### Fast, Ergonomic Typing
- **Multi-touch 2-thumb rollover** and Shift+letter chording with nearest-key hit testing (zero dead zones).
- **Cursor & Selection Gestures:**
  - Glide horizontally on the **Spacebar** to move the cursor.
  - Hold **Shift + Spacebar glide** to select text left or right.
  - Swipe left on **Backspace** to delete whole words.
  - Long-press **Enter** for a quick floating picker (**Emoji** or **Newline**), or hold **Shift + Enter** for a direct newline.
- **One-Handed Mode (Left / Right):** Shrinks the keyboard to ~82% width with pure-vector side-gutter controls to flip sides or expand back to full width.
- **Smart Typing:** Auto-capitalization, double-space period (`. `), smart punctuation, selection-safe & emoji-grapheme-safe Backspace, subtle 95ms zero-allocation tap animation, and wide editor compatibility (Chrome/WebView, Flutter, Unity, chat apps).

### Multi-Language Support (100% Offline)
Defaults to **English (US)**. Enable or switch languages anytime in Settings:

| Language | Input Method / Layout | Highlights |
| :--- | :--- | :--- |
| **English (US)** *(default)* | QWERTY | Offline suggestions & auto-correct with Backspace Undo |
| **Vietnamese** | **Telex** & **VNI** | Phonotactics validation, Old (`hòa, thúy`) & Modern (`hoà, thuý`) tone styles, English auto-restore (`window`, `work`, `chrome`), compound `ua+w` & `ww → w` accuracy, VNI alphanumeric guard (`v1`, `mp3`, `h2o`) |
| **French** | AZERTY | Long-press accented character popups |
| **German** | QWERTZ | Long-press umlaut/accented character popups |
| **Spanish** | QWERTY (`ñ`) | Long-press accented character popups |
| **Russian** | Cyrillic (ЙЦУКЕН) | Full Cyrillic layout |
| **Korean** | 2-Bulsik Hangul | Built-in Hangul Jamo composition automaton |

### Lightweight Suggestions & Auto-Correct
- **2 independent toggles:**
  - **Text suggestions** — Turn off to unload dictionaries completely (`0 MB` RAM).
  - **Auto-correct** — Fixes 1-edit-distance typos on Space with instant **Backspace Undo** and English code-switching protection.
- Supports offline personal word learning and importing custom `.txt` wordlists via Android Storage Access Framework (no storage permissions required).

### Privacy-First RAM Clipboard
- Keeps up to 10 recent clips strictly in **RAM** (never written to disk) and automatically skips password/sensitive clips on Android 13+.
- **Pin favorite clips** to keep them at the top across clears, delete individual items, and optionally **auto-clear unpinned clips** after 15, 30, or 60 minutes.
- **Image Clipboard** (Android 7.1+): Paste copied images directly into supported chat apps via `InputConnection.commitContent()` with lazy `RGB_565` micro-thumbnails that recycle immediately when closed.

### Emoji, Kaomoji & Math Panel
- 12 lazy-loaded categories: **Recents**, 9 standard Unicode Emoji tabs, **Kaomoji** (54 expressions), and **Math & Tech Symbols** (96 symbols).
- **Offline English Emoji Search:** Filter 450+ emojis in real time directly above the keyboard.
- **Skin-Tone Picker:** Long-press supported people/hand emojis to pick from 6 skin tones.

### 100% Granular Customization & Themes
- Toggle key preview popup, top utility bar, number row, sub-labels, comma/period keys, settings gear, Emoji button placement (`Top Bar` / `Bottom` / `Hidden`), keypress animation, keypress sound, haptic strength (`Light` / `Medium` / `Strong`), long-press delay (`200–600ms`), and every gesture individually.
- **Themes:** Material You Dynamic Color (Android 12+), Dark, AMOLED Black, Light, plus a **Custom CSS-like Theme Editor** with 1-tap presets parsed into native `Canvas` paints (no WebView).
- **Live RAM Pill & In-App Changelog:** Monitor real-time `Active` / `Standby` IME memory and read release notes directly inside Settings.

---

## Android Compatibility

True Light supports **Android 5.0 (API 21)** through **Android 16+ (API 36+)** with automatic graceful degradation on older versions and zero native `.so` libraries (100% compatible with Android 15+ 16 KB memory page sizes).

| Android Version | API Level | Support Status |
| :--- | :---: | :--- |
| **Android 13 – 16+** | 33 – 36+ | Full support, including sensitive/password clipboard auto-skip (`EXTRA_IS_SENSITIVE`) and Material You Dynamic Color |
| **Android 12** | 31 – 32 | Full support, including Material You Dynamic Color (`system_accent1_*`) and `VibratorManager` |
| **Android 8.0 – 11** | 26 – 30 | Full support, including amplitude-controlled `VibrationEffect` haptics and adaptive navigation bar icons |
| **Android 7.1** | 25 | Core keyboard + Image Clipboard (`commitContent`) + standard vibration fallback |
| **Android 5.0 – 7.0** | 21 – 24 | Core keyboard, multi-language typing, themes, text clipboard, emoji search *(Image Clipboard requires Android 7.1+)* |

---

## Architecture & Privacy

```text
┌─────────────────────────────────────────┐       Binder IPC        ┌──────────────────────────────────────┐
│  com.truelight.ime  (Main IME Process)  │ ◄─────────────────────► │  com.truelight.ime:settings          │
│  • 100% Pure Android Framework          │     PrefsProvider       │  • Material Design 3 Settings UI     │
│  • ZERO AndroidX / ZERO Network code    │   (exported = false)    │  • Live RAM Monitor & CSS Editor     │
│  • Single-Canvas View (0-alloc onDraw)  │                         │  • In-App Changelog ("What's New")   │
│  • Telex / VNI / Hangul / Trie Engine   │                         │  • On-demand GitHub Releases Updater │
└─────────────────────────────────────────┘                         └──────────────────────────────────────┘
```

- **Zero Telemetry, Zero Ads, Zero Tracking:** What you type never leaves your device.
- **CI-Enforced Isolation:** Every commit and release runs an automated static audit verifying that the main IME process (`TrueLightIME.kt`, `engine/`, `bridge/`, `ui/`, `emoji/`, `suggest/`, `clipboard/`, `lang/`, `config/`) has **zero** networking (`java.net`, `javax.net`, `okhttp`) and **zero** AndroidX/Material imports.
- **Permissions Explained:**
  - `VIBRATE` — Optional keypress haptic feedback.
  - `INTERNET` & `REQUEST_INSTALL_PACKAGES` — Used **only** inside the isolated `:settings` process when you manually tap **Check for Updates** (verified with `SHA-256` before installation).

---

## Installation

1. Download the latest signed APK (`TrueLight-vX.Y.Z.apk`) from [**GitHub Releases**](https://github.com/Charonart/True-Light-Keyboard/releases/latest).
2. Install the APK on your Android device and open **True Light**.
3. On the **Home** tab, complete the 2-step setup: **Enable Keyboard** → **Select Default**.

> **Optional Integrity Check:** Each release includes a `SHA256SUMS.txt` file so you can verify the APK checksum before installing.

---

## Build from Source

### Prerequisites
- **JDK 25** (or Android Studio bundled JBR 25)
- **Android SDK Platform 34**
- **Gradle 9.1.0** (included via `./gradlew`)

### Commands

```bash
# Run all unit tests
./gradlew testDebugUnitTest

# Run Android Lint checks
./gradlew lintDebug

# Build Debug and Release APKs
./gradlew assembleDebug assembleRelease
```

### Signed Release Builds
Provide your keystore via environment variables when running `assembleRelease`:

```bash
export RELEASE_STORE_FILE=/path/to/keystore.jks
export RELEASE_STORE_PASSWORD=your_store_password
export RELEASE_KEY_ALIAS=your_alias
export RELEASE_KEY_PASSWORD=your_key_password
./gradlew assembleRelease
```

On GitHub, pushing a semver tag (`git tag vX.Y.Z && git push origin vX.Y.Z`) automatically runs [`.github/workflows/release.yml`](.github/workflows/release.yml), builds & signs the APK using the `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` repository secrets, generates `SHA256SUMS.txt`, and publishes the GitHub Release.

---

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for the complete release history, or open **Settings → About → What's New** inside the app.

---

## License

True Light Keyboard is free and open-source software licensed under the **GNU General Public License v3.0 (GPL-3.0)**. See [LICENSE](LICENSE) for full details.
