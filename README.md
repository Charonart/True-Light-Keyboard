<div align="center">

# True Light Keyboard

**An ultra-lightweight, privacy-first, single-canvas Android keyboard.**

[![Android CI](https://github.com/Charonart/True-Light-Keyboard/actions/workflows/ci.yml/badge.svg)](https://github.com/Charonart/True-Light-Keyboard/actions/workflows/ci.yml)
[![Latest Release](https://img.shields.io/github/v/release/Charonart/True-Light-Keyboard?label=release&color=blue)](https://github.com/Charonart/True-Light-Keyboard/releases/latest)
[![Android API](https://img.shields.io/badge/Android-API%2021%2B-3DDC84?logo=android&logoColor=white)](https://github.com/Charonart/True-Light-Keyboard/releases)
[![IME Offline](https://img.shields.io/badge/IME%20Process-100%25%20Offline-2ea44f)](#architecture--privacy)

*Zero-allocation Canvas rendering • Isolated Material 3 Settings • Multi-language Group 1 • Custom CSS-like Themes*

</div>

---

## Why True Light?

Most modern Android keyboards ship heavy UI frameworks, embedded browsers, SQLite telemetry databases, and background services—consuming **150+ MB of RAM** even when idle.

**True Light** is built from the ground up with two strict engineering principles:

1. **Zero-Allocation Single-Canvas IME (`com.truelight.ime`)**
   The keyboard itself is rendered directly on a single native `View` using preallocated `Paint`, `RectF`, and `Path` objects—zero AndroidX, zero Material libraries, zero networking code, and **< 1 MB Dalvik heap**.
2. **Process-Isolated Material Design 3 Settings (`com.truelight.ime:settings`)**
   The settings app runs in its own isolated Android process (`:settings`) with full Material You Dynamic Color and an on-demand GitHub Releases updater. When you close Settings, Android reclaims 100% of its memory—adding **0 MB** to the keyboard process.

---

## Measured Footprint (Android API 37 Emulator, 1080×2400)

| Metric (`dumpsys meminfo`) | True Light IME *(standalone)* | Gboard *(Release)* |
| :--- | ---: | ---: |
| **App Dalvik Heap** | **~0.9 MB** | **13.8 MB** *(~14× larger)* |
| **Total PSS** *(Debug APK)* | **27.9 – 41.5 MB** | **166.9 MB** *(~4× larger)* |
| **SQLite Databases** | **0** | **5 DBs (3.1 MB)** |
| **View Hierarchy** | **24 views** | **345 views** |
| **Release APK Size** | **~1.4 MB** | **> 80 MB** |

> **Tip:** Signed **Release** builds strip debug symbols & unminified `.dex` pages via R8 and use noticeably less RAM than `-debug.apk` test builds.

---

## Features

### Fast, Ergonomic Typing
- **Multi-touch 2-thumb rollover** & Shift+letter chording (no dropped keystrokes during fast typing).
- **Zero dead zones** with nearest-key hit testing across gaps and row edges.
- **Gestures:** Spacebar horizontal glide to move cursor, Backspace left-swipe to delete whole words, long-press keys for symbols, and configurable **long-press Enter/Search → Open Emoji** (or Newline).
- **Smart Typing:** Auto-capitalization, double-space period (`. `), smart punctuation, selection-safe & emoji-grapheme-safe Backspace, and Chromium/WebView/Flutter/Unity `InputConnection` compatibility.
- **Offline Clipboard History:** Last 10 text clips stored strictly in RAM, automatically skipping sensitive/password clips on Android 13+.

### Global-First Multi-Language Support (Group 1)
Defaults to **English (US)** out of the box. Add or switch languages anytime offline in Settings:
- **English (US)** — QWERTY *(default)*
- **Vietnamese** — **Telex** & **VNI** *(supports both Old `hòa, thúy` and Modern `hoà, thuý` tone rules, plus loanword protection)*
- **French** — AZERTY
- **German** — QWERTZ
- **Spanish** — QWERTY (`ñ`)
- **Russian** — Cyrillic (ЙЦУКЕН)
- **Korean** — 2-Bulsik Hangul automaton
- *Planned (Group 2):* Optional offline packs for Chinese (Pinyin) & Japanese (Kana→Kanji).

### Lightweight Text Suggestions & Auto-Correct
- **2 independent toggles:**
  - **Text suggestions:** Turn off to unload dictionaries completely (`0 MB` RAM).
  - **Auto-correct:** 1-edit-distance typo fixing on Space with instant **Backspace Undo** and English code-switching guard.
- Supports offline user word learning and importing custom `.txt` wordlists via Android Storage Access Framework (zero storage permissions).

### 12-Category Lazy-Loaded Emoji Panel
- Standard 9 Unicode Emoji categories + **Recents** + **Kaomoji** (`¯\_(ツ)_/¯`, 54 expressions) + **Math & Tech Symbols** (`→ ≠ ≤ ≥ ∞ ∑`, 96 symbols).
- Lazy-loaded only when opened and automatically released on keyboard hide.

### Material Design 3 Settings & Custom CSS-Like Keyboard Themes
- **Android 12+ Material You Dynamic Color** (plus Dark, AMOLED Black, and Light presets).
- **Custom CSS-like Style Sheet editor** with 1-tap presets (*Precision Dark, Paper Light, AMOLED Pure, Nord Slate*) parsed once into native `Canvas` paints—**0 WebView, 0 MB extra RAM**.
- **Live Keyboard RAM Pill** showing real-time `Active` vs `Standby` keyboard memory.
- **In-App GitHub Updater** (inside `:settings` only) with download progress and **SHA-256** verification.

---

## Architecture & Privacy

```text
┌─────────────────────────────────────────┐       Binder IPC        ┌──────────────────────────────────────┐
│  com.truelight.ime  (Main IME Process)  │ ◄─────────────────────► │  com.truelight.ime:settings          │
│  • 100% Pure Android Framework          │     PrefsProvider       │  • Material Design 3 Settings UI     │
│  • ZERO AndroidX / ZERO Network code    │   (exported = false)    │  • Live RAM Monitor & CSS Editor     │
│  • Single-Canvas View (0-alloc onDraw)  │                         │  • On-demand GitHub Releases Updater │
│  • Telex / VNI / Hangul / Trie Engine   │                         │  • Reclaimed when Settings closes    │
└─────────────────────────────────────────┘                         └──────────────────────────────────────┘
```

CI enforces on every commit and release that the IME process (`TrueLightIME.kt`, `engine/`, `bridge/`, `ui/`, `emoji/`, `suggest/`, `clipboard/`, `lang/`, `config/`) contains **zero** networking or AndroidX/Material imports.

---

## Installation

1. Download the latest APK from [**GitHub Releases**](https://github.com/Charonart/True-Light-Keyboard/releases/latest).
2. Install the APK and open **True Light**.
3. Follow the 2-step setup on the Home tab: **Enable Keyboard** → **Select Default**.

---

## Building from Source

### Requirements
- **JDK 25** (or Android Studio bundled JBR 25)
- **Gradle 9.1.0** (included via wrapper)
- **Android SDK Platform 34**

### Commands
```bash
# Run unit tests
./gradlew testDebugUnitTest

# Build Debug & Release APKs
./gradlew assembleDebug assembleRelease
```

### Automated Signed Releases (GitHub Actions)
Push a tag matching `v*` (e.g. `git tag v1.2.1 && git push origin v1.2.1`) to trigger [`.github/workflows/release.yml`](.github/workflows/release.yml).

To produce signed release APKs, add these 4 repository secrets under **Settings → Secrets and variables → Actions**:
- `KEYSTORE_BASE64` — Base64-encoded `.jks` keystore
- `KEYSTORE_PASSWORD` — Keystore password
- `KEY_ALIAS` — Signing key alias
- `KEY_PASSWORD` — Signing key password

---

## License

This project is open source. See repository settings for licensing details.
