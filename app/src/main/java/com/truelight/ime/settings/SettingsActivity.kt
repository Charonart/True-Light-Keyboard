package com.truelight.ime.settings

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.truelight.ime.config.CssThemeParser
import com.truelight.ime.config.KeyboardPrefs
import com.truelight.ime.emoji.EmojiHistoryManager
import com.truelight.ime.lang.LanguageRegistry
import com.truelight.ime.suggest.SuggestionManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale

/**
 * 100% Pure Android Framework Settings & Telemetry Activity (Zero AndroidX, Zero Internet).
 * Distinctive True Light precision aesthetic: hairline card framing, monospace technical telemetry,
 * live PSS RAM monitor & comparison bars, inline CSS-like style sheet editor, and real-time sync.
 */
class SettingsActivity : Activity() {

    companion object {
        private const val REQ_IMPORT_DICT = 1001
        private const val RAM_REFRESH_INTERVAL_MS = 2500L
        private const val REF_AOSP_MB = 55f
        private const val REF_GBOARD_MB = 167f
        private const val RAM_SCALE_MAX_MB = 180f
    }

    private lateinit var prefs: KeyboardPrefs
    private var pendingImportDictKey: String = "en"
    private var isSyncingUi = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ramRefreshRunnable = object : Runnable {
        override fun run() {
            refreshRamMonitor()
            mainHandler.postDelayed(this, RAM_REFRESH_INTERVAL_MS)
        }
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        mainHandler.post {
            syncUiFromPrefs()
            refreshRamMonitor()
        }
    }

    // Live-updated UI references
    private lateinit var heroRamBadge: TextView
    private lateinit var ramLiveValueView: TextView
    private lateinit var ramBreakdownView: TextView
    private lateinit var ramTrueLightBar: View
    private lateinit var ramDeltaSummaryView: TextView

    private lateinit var statusEnabledPill: TextView
    private lateinit var statusDefaultPill: TextView
    private lateinit var languagesContainer: LinearLayout
    private lateinit var viToneRow: LinearLayout
    private lateinit var viToneValueView: TextView

    private lateinit var switchSuggestions: Switch
    private lateinit var switchAutoCorrect: Switch
    private lateinit var switchAutoCaps: Switch
    private lateinit var switchDoubleSpace: Switch
    private lateinit var switchClipboard: Switch
    private lateinit var switchCustomCss: Switch
    private lateinit var switchNumberRow: Switch
    private lateinit var switchSubLabels: Switch
    private lateinit var switchLongPress: Switch
    private lateinit var switchHaptic: Switch

    private lateinit var themePills: Array<TextView>
    private lateinit var cssStatusBadge: TextView
    private lateinit var heightValueView: TextView
    private lateinit var heightSeekBar: SeekBar

    // Palette colors (resolved dynamically from Android system theme)
    private var isNightMode = false
    private var colBg = 0
    private var colCard = 0
    private var colBorder = 0
    private var colText = 0
    private var colSubtext = 0
    private var colAccent = 0
    private var colOnAccent = 0
    private var colDivider = 0
    private var colChipBg = 0
    private var colBarTrack = 0
    private var colBarRef = 0
    private var colBarHeavy = 0
    private var colOkBg = 0
    private var colOkText = 0
    private var colWarnBg = 0
    private var colWarnText = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = KeyboardPrefs(this)
        resolveSystemColors()
        setContentView(buildContentView())
        applySystemBarsAppearance()
    }

    private fun applySystemBarsAppearance() {
        window.statusBarColor = colBg
        window.navigationBarColor = colBg
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val statusMask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            val navMask = WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            val combined = statusMask or navMask
            window.insetsController?.setSystemBarsAppearance(if (isNightMode) 0 else combined, combined)
        } else {
            @Suppress("DEPRECATION")
            var flags = 0
            if (!isNightMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
            if (!isNightMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = flags
        }
    }

    override fun onResume() {
        super.onResume()
        prefs.prefs.registerOnSharedPreferenceChangeListener(prefListener)
        refreshSetupStatus()
        syncUiFromPrefs()
        refreshRamMonitor()
        mainHandler.removeCallbacks(ramRefreshRunnable)
        mainHandler.postDelayed(ramRefreshRunnable, RAM_REFRESH_INTERVAL_MS)
    }

    override fun onPause() {
        super.onPause()
        prefs.prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        mainHandler.removeCallbacks(ramRefreshRunnable)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMPORT_DICT && resultCode == RESULT_OK) {
            val uri: Uri? = data?.data
            if (uri != null) {
                importDictionaryFromUri(uri, pendingImportDictKey)
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun getAppVersionName(): String {
        return try {
            @Suppress("DEPRECATION")
            val info = packageManager.getPackageInfo(packageName, 0)
            info.versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }

    private fun resolveSystemColors() {
        isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (isNightMode) {
                colBg = getColor(android.R.color.system_neutral1_900)
                colCard = getColor(android.R.color.system_neutral1_800)
                colBorder = getColor(android.R.color.system_neutral2_700)
                colText = getColor(android.R.color.system_neutral1_50)
                colSubtext = getColor(android.R.color.system_neutral2_300)
                colAccent = getColor(android.R.color.system_accent1_200)
                colOnAccent = getColor(android.R.color.system_accent1_900)
                colDivider = getColor(android.R.color.system_neutral2_700)
                colChipBg = getColor(android.R.color.system_neutral1_900)
                colBarTrack = getColor(android.R.color.system_neutral1_900)
                colBarRef = getColor(android.R.color.system_neutral2_500)
                colBarHeavy = 0xFFE57373.toInt()
                colOkBg = 0xFF1B3B2B.toInt()
                colOkText = 0xFF81C784.toInt()
                colWarnBg = 0xFF3E2D16.toInt()
                colWarnText = 0xFFFFB74D.toInt()
            } else {
                colBg = getColor(android.R.color.system_neutral1_50)
                colCard = getColor(android.R.color.system_neutral1_0)
                colBorder = getColor(android.R.color.system_neutral2_200)
                colText = getColor(android.R.color.system_neutral1_900)
                colSubtext = getColor(android.R.color.system_neutral2_600)
                colAccent = getColor(android.R.color.system_accent1_600)
                colOnAccent = getColor(android.R.color.system_neutral1_0)
                colDivider = getColor(android.R.color.system_neutral2_200)
                colChipBg = getColor(android.R.color.system_neutral1_100)
                colBarTrack = getColor(android.R.color.system_neutral1_100)
                colBarRef = getColor(android.R.color.system_neutral2_400)
                colBarHeavy = 0xFFD32F2F.toInt()
                colOkBg = 0xFFE6F4EA.toInt()
                colOkText = 0xFF137333.toInt()
                colWarnBg = 0xFFFEF3E1.toInt()
                colWarnText = 0xFFB06000.toInt()
            }
        } else if (isNightMode) {
            colBg = 0xFF0E1013.toInt()
            colCard = 0xFF171A1F.toInt()
            colBorder = 0xFF282D36.toInt()
            colText = 0xFFF3F5F8.toInt()
            colSubtext = 0xFF98A0AD.toInt()
            colAccent = 0xFF5B93FF.toInt()
            colOnAccent = 0xFF0A1426.toInt()
            colDivider = 0xFF242830.toInt()
            colChipBg = 0xFF1F232B.toInt()
            colBarTrack = 0xFF111317.toInt()
            colBarRef = 0xFF6B7280.toInt()
            colBarHeavy = 0xFFE57373.toInt()
            colOkBg = 0xFF1B3B2B.toInt()
            colOkText = 0xFF81C784.toInt()
            colWarnBg = 0xFF3E2D16.toInt()
            colWarnText = 0xFFFFB74D.toInt()
        } else {
            colBg = 0xFFF4F6F9.toInt()
            colCard = 0xFFFFFFFF.toInt()
            colBorder = 0xFFDCE2EA.toInt()
            colText = 0xFF121519.toInt()
            colSubtext = 0xFF5A6370.toInt()
            colAccent = 0xFF1A66E8.toInt()
            colOnAccent = 0xFFFFFFFF.toInt()
            colDivider = 0xFFE6EAF0.toInt()
            colChipBg = 0xFFF0F3F8.toInt()
            colBarTrack = 0xFFE9EEF5.toInt()
            colBarRef = 0xFF8A94A6.toInt()
            colBarHeavy = 0xFFD32F2F.toInt()
            colOkBg = 0xFFE6F4EA.toInt()
            colOkText = 0xFF137333.toInt()
            colWarnBg = 0xFFFEF3E1.toInt()
            colWarnText = 0xFFB06000.toInt()
        }
    }

    private fun roundedBg(color: Int, radiusDp: Int, strokeColor: Int = Color.TRANSPARENT): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != Color.TRANSPARENT) {
                setStroke(dp(1), strokeColor)
            }
        }
    }

    private fun buildContentView(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(colBg)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, dp(24), pad, dp(36))
        }

        // Distinctive True Light Precision Hero Header
        root.addView(buildHeroHeader())

        // Card 1: Live RAM Footprint & Comparison Monitor
        root.addView(buildRamMonitorCard())

        // Card 2: Setup & Live Sandbox
        root.addView(buildSetupCard())

        // Card 3: Languages (Group 1)
        root.addView(buildLanguagesCard())

        // Card 4: Typing & Intelligence
        root.addView(buildTypingAndSuggestionsCard())

        // Card 5: Appearance & Custom CSS Style Sheet
        root.addView(buildAppearanceAndCssCard())

        // Card 6: Updates & Privacy (Zero Internet)
        root.addView(buildUpdatesAndPrivacyCard())

        scroll.addView(root)
        return scroll
    }

    private fun buildHeroHeader(): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(8), dp(2), dp(18))
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Precision accent bar mark
        titleRow.addView(View(this).apply {
            background = roundedBg(colAccent, 3)
            layoutParams = LinearLayout.LayoutParams(dp(4), dp(26)).apply {
                marginEnd = dp(10)
            }
        })

        titleRow.addView(TextView(this).apply {
            text = "True Light"
            textSize = 27f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = -0.02f
            setTextColor(colText)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        // Version monospace badge
        titleRow.addView(createMonoPill("v${getAppVersionName()}", colChipBg, colSubtext, colBorder))
        header.addView(titleRow)

        val badgeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }

        badgeRow.addView(createMonoPill("0 INTERNET • OFFLINE", colOkBg, colOkText).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(8)
            }
        })

        heroRamBadge = createMonoPill("LIVE RAM: -- MB", colChipBg, colAccent, colBorder)
        badgeRow.addView(heroRamBadge)
        header.addView(badgeRow)

        return header
    }

    private fun createMonoPill(
        label: String,
        bgColor: Int,
        textColor: Int,
        strokeColor: Int = Color.TRANSPARENT
    ): TextView {
        return TextView(this).apply {
            text = label
            textSize = 11f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.04f
            setTextColor(textColor)
            background = roundedBg(bgColor, 8, strokeColor)
            setPadding(dp(9), dp(4), dp(9), dp(4))
        }
    }

    private fun createCard(eyebrow: String, title: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(colCard, 14, colBorder)
            val p = dp(16)
            setPadding(p, dp(14), p, p)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
        }
        card.addView(TextView(this).apply {
            text = eyebrow
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.12f
            setTextColor(colAccent)
        })
        card.addView(TextView(this).apply {
            text = title
            textSize = 16.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colText)
            setPadding(0, dp(2), 0, dp(10))
        })
        return card
    }

    private fun createButton(label: String, primary: Boolean, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (primary) colOnAccent else colText)
            background = roundedBg(
                if (primary) colAccent else colChipBg,
                10,
                if (primary) Color.TRANSPARENT else colBorder
            )
            minHeight = dp(42)
            minimumHeight = dp(42)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { onClick() }
        }
    }

    private fun createDivider(): View {
        return View(this).apply {
            setBackgroundColor(colDivider)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                topMargin = dp(10)
                bottomMargin = dp(10)
            }
        }
    }

    // --- CARD 1: LIVE RAM FOOTPRINT & COMPARISON MONITOR ---

    private fun buildRamMonitorCard(): View {
        val card = createCard("01 / TELEMETRY", "Live RAM Footprint")

        // Live headline row
        val headlineRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(0, 0, 0, dp(12))
        }
        ramLiveValueView = TextView(this).apply {
            text = "--.- MB"
            textSize = 26f
            typeface = Typeface.MONOSPACE
            setTextColor(colAccent)
        }
        headlineRow.addView(ramLiveValueView)

        ramBreakdownView = TextView(this).apply {
            text = "  PSS • measuring..."
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setTextColor(colSubtext)
            setPadding(dp(8), 0, 0, dp(4))
        }
        headlineRow.addView(ramBreakdownView)
        card.addView(headlineRow)

        // Bar 1: True Light (Live)
        val tlBarPair = buildComparisonBarRow("True Light (Live PSS)", "-- MB", colAccent, 0.22f)
        ramTrueLightBar = tlBarPair.second
        card.addView(tlBarPair.first)

        // Bar 2: AOSP Native (Ref)
        card.addView(
            buildComparisonBarRow(
                "AOSP Native (Ref)",
                "~55 MB",
                colBarRef,
                (REF_AOSP_MB / RAM_SCALE_MAX_MB).coerceIn(0.05f, 1f)
            ).first
        )

        // Bar 3: Gboard (Ref)
        card.addView(
            buildComparisonBarRow(
                "Gboard (Ref)",
                "~167 MB",
                colBarHeavy,
                (REF_GBOARD_MB / RAM_SCALE_MAX_MB).coerceIn(0.05f, 1f)
            ).first
        )

        // Live toggle delta readout
        ramDeltaSummaryView = TextView(this).apply {
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setTextColor(colText)
            background = roundedBg(colChipBg, 10, colBorder)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setLineSpacing(dp(3).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        }
        card.addView(ramDeltaSummaryView)

        // Transparent footnote
        card.addView(TextView(this).apply {
            text = "True Light RAM is measured live (PSS). Android restricts apps from reading other apps' live RAM, so AOSP/Gboard bars use reference benchmarks."
            textSize = 11f
            setTextColor(colSubtext)
            setPadding(0, dp(8), 0, 0)
        })

        return card
    }

    private fun buildComparisonBarRow(
        label: String,
        valueText: String,
        barColor: Int,
        initialFraction: Float
    ): Pair<LinearLayout, View> {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(9)
            }
        }

        val labelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        labelRow.addView(TextView(this).apply {
            text = label
            textSize = 12.5f
            setTextColor(colText)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val valueView = TextView(this).apply {
            tag = "bar_value"
            text = valueText
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(colSubtext)
        }
        labelRow.addView(valueView)
        wrapper.addView(labelRow)

        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedBg(colBarTrack, 6, colBorder)
            weightSum = 1f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(10)
            )
        }
        val fill = View(this).apply {
            tag = valueView
            background = roundedBg(barColor, 6)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, initialFraction)
        }
        track.addView(fill)
        wrapper.addView(track)

        return wrapper to fill
    }

    private fun refreshRamMonitor() {
        try {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val infos = am.getProcessMemoryInfo(intArrayOf(Process.myPid()))
            if (infos.isNotEmpty()) {
                val info = infos[0]
                val totalMb = info.totalPss / 1024f
                val dalvikMb = info.dalvikPss / 1024f
                val nativeMb = info.nativePss / 1024f

                val totalStr = String.format(Locale.US, "%.1f MB", totalMb)
                heroRamBadge.text = "LIVE RAM: $totalStr"
                ramLiveValueView.text = totalStr
                ramBreakdownView.text = String.format(
                    Locale.US,
                    "PSS • Dalvik %.1f • Native %.1f MB",
                    dalvikMb,
                    nativeMb
                )

                val barVal = ramTrueLightBar.tag as? TextView
                barVal?.text = totalStr
                val frac = (totalMb / RAM_SCALE_MAX_MB).coerceIn(0.06f, 1f)
                val lp = ramTrueLightBar.layoutParams as LinearLayout.LayoutParams
                lp.weight = frac
                ramTrueLightBar.layoutParams = lp
            }
        } catch (_: Exception) {
            // Safe fallback if system restricts telemetry
        }

        val sugStatus = if (prefs.suggestionsEnabled) "ON (+1.5 MB)" else "OFF (0 MB)"
        val clipStatus = if (prefs.clipboardEnabled) "ON (+0.1 MB)" else "OFF (0 MB)"
        val langCount = prefs.enabledLanguages.size
        ramDeltaSummaryView.text =
            "Suggestions: $sugStatus  •  Clipboard: $clipStatus\n" +
            "Languages: $langCount active  •  Emoji: Lazy (0 MB until opened)"
    }

    // --- CARD 2: SETUP & LIVE SANDBOX ---

    private fun buildSetupCard(): View {
        val card = createCard("02 / ACTIVATION", "Setup & Live Sandbox")

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }
        statusEnabledPill = createMonoPill("1. CHECKING...", colChipBg, colSubtext).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
            gravity = Gravity.CENTER
        }
        statusDefaultPill = createMonoPill("2. CHECKING...", colChipBg, colSubtext).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER
        }
        statusRow.addView(statusEnabledPill)
        statusRow.addView(statusDefaultPill)
        card.addView(statusRow)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val btnEnable = createButton("1. Enable Keyboard", primary = true) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        val btnSelect = createButton("2. Select Default", primary = false) {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnRow.addView(btnEnable)
        btnRow.addView(btnSelect)
        card.addView(btnRow)

        val testInput = EditText(this).apply {
            hint = "Sandbox: tap to test typing, multi-touch, space glide..."
            setHintTextColor(colSubtext)
            setTextColor(colText)
            textSize = 14.5f
            background = roundedBg(colChipBg, 10, colBorder)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        }
        card.addView(testInput)

        return card
    }

    // --- CARD 3: LANGUAGES (GROUP 1) ---

    private fun buildLanguagesCard(): View {
        val card = createCard("03 / LANGUAGES", "Group 1 Offline Layouts")

        card.addView(TextView(this).apply {
            text = "Defaults to English (US). Add Vietnamese (Telex) or other Group 1 languages offline. Tap a language to activate."
            textSize = 12.5f
            setTextColor(colSubtext)
            setPadding(0, 0, 0, dp(10))
        })

        languagesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        card.addView(languagesContainer)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        }
        val btnAdd = createButton("+ Add Language", primary = true) {
            showAddLanguageDialog()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        val btnImportDict = createButton("Import Dict (.txt)", primary = false) {
            showImportDictionaryLanguagePicker()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        actionRow.addView(btnAdd)
        actionRow.addView(btnImportDict)
        card.addView(actionRow)

        // Vietnamese tone placement row (visible only when Vietnamese is added)
        viToneRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(colChipBg, 10, colBorder)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
            }
            setOnClickListener { showVietnameseToneDialog() }
        }
        val viLabelCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        viLabelCol.addView(TextView(this).apply {
            text = "Vietnamese tone style"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colText)
        })
        viToneValueView = TextView(this).apply {
            textSize = 12f
            setTextColor(colSubtext)
        }
        viLabelCol.addView(viToneValueView)
        viToneRow.addView(viLabelCol)
        viToneRow.addView(createMonoPill("CHANGE", colCard, colAccent, colBorder))
        card.addView(viToneRow)

        return card
    }

    // --- CARD 4: TYPING & SUGGESTIONS ---

    private fun buildTypingAndSuggestionsCard(): View {
        val card = createCard("04 / TYPING & INTELLIGENCE", "Suggestions & Smart Input")

        val sugRow = buildSwitchRow(
            title = "Text suggestions (Gợi ý sửa)",
            subtitle = "~1–2 MB RAM when ON / 0 MB when OFF (unloads dictionary).",
            checked = prefs.suggestionsEnabled
        ) { isChecked ->
            prefs.suggestionsEnabled = isChecked
            switchAutoCorrect.isEnabled = isChecked
            if (!isChecked) {
                switchAutoCorrect.isChecked = false
            }
            refreshRamMonitor()
        }
        switchSuggestions = sugRow.second
        card.addView(sugRow.first)

        card.addView(createDivider())

        val acRow = buildSwitchRow(
            title = "Auto-correct (Tự động sửa)",
            subtitle = "Auto-replace typos on Space. Press Backspace immediately to undo.",
            checked = prefs.autoCorrectEnabled
        ) { isChecked ->
            prefs.autoCorrectEnabled = isChecked
            refreshRamMonitor()
        }
        switchAutoCorrect = acRow.second
        switchAutoCorrect.isEnabled = prefs.suggestionsEnabled
        card.addView(acRow.first)

        card.addView(createDivider())

        val capsRow = buildSwitchRow(
            title = "Auto-capitalization",
            subtitle = "Automatically capitalize the first word of each sentence.",
            checked = prefs.autoCapsEnabled
        ) { isChecked ->
            prefs.autoCapsEnabled = isChecked
        }
        switchAutoCaps = capsRow.second
        card.addView(capsRow.first)

        card.addView(createDivider())

        val dspRow = buildSwitchRow(
            title = "Double-space period (. )",
            subtitle = "Double-tap Space after a word to insert a period and space.",
            checked = prefs.doubleSpacePeriodEnabled
        ) { isChecked ->
            prefs.doubleSpacePeriodEnabled = isChecked
        }
        switchDoubleSpace = dspRow.second
        card.addView(dspRow.first)

        card.addView(createDivider())

        val clipRow = buildSwitchRow(
            title = "Clipboard history (10 items, RAM-only)",
            subtitle = "Quick-paste recent clips from strip icon. Never written to disk.",
            checked = prefs.clipboardEnabled
        ) { isChecked ->
            prefs.clipboardEnabled = isChecked
            refreshRamMonitor()
        }
        switchClipboard = clipRow.second
        card.addView(clipRow.first)

        card.addView(createDivider())

        val btnClear = createButton("Clear learned words, clipboard & emoji history", primary = false) {
            SuggestionManager(this).clearAllUserDictionaries()
            EmojiHistoryManager(this).clearHistory()
            prefs.bumpHistoryRevision()
            refreshRamMonitor()
            Toast.makeText(this, "Cleared learned words, clipboard & emoji history", Toast.LENGTH_SHORT).show()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        card.addView(btnClear)

        return card
    }

    // --- CARD 5: APPEARANCE & CUSTOM CSS STYLE SHEET ---

    private fun buildAppearanceAndCssCard(): View {
        val card = createCard("05 / APPEARANCE & CSS", "Theme & Custom Style Sheet")

        // Inline 4-pill Theme Preset Selector
        card.addView(TextView(this).apply {
            text = "Theme preset"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colText)
            setPadding(0, 0, 0, dp(8))
        })

        val pillRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedBg(colChipBg, 10, colBorder)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val labels = arrayOf("Dynamic", "Dark", "AMOLED", "Light")
        themePills = Array(4) { idx ->
            TextView(this).apply {
                text = labels[idx]
                textSize = 12.5f
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(9), dp(4), dp(9))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    prefs.themeMode = idx
                    updateThemePillsVisual(idx)
                }
            }.also { pillRow.addView(it) }
        }
        card.addView(pillRow)

        card.addView(createDivider())

        // Custom CSS-like Style Sheet toggle + Editor button
        val cssRow = buildSwitchRow(
            title = "Custom CSS-like Style Sheet",
            subtitle = "Override key colors, borders, radius, gaps & font size (0 WebView, 0 MB).",
            checked = prefs.customCssEnabled
        ) { isChecked ->
            prefs.customCssEnabled = isChecked
            updateCssStatusBadge()
        }
        switchCustomCss = cssRow.second
        card.addView(cssRow.first)

        val cssBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        }
        val btnEditCss = createButton("Edit CSS Style Sheet", primary = false) {
            showCssEditorDialog()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        cssStatusBadge = createMonoPill("DEFAULT", colChipBg, colSubtext, colBorder)
        cssBtnRow.addView(btnEditCss)
        cssBtnRow.addView(cssStatusBadge)
        card.addView(cssBtnRow)

        card.addView(createDivider())

        // Keyboard height slider
        val heightHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heightHeader.addView(TextView(this).apply {
            text = "Keyboard height"
            textSize = 14.5f
            setTextColor(colText)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        heightValueView = createMonoPill("${prefs.keyboardHeightDp} dp", colChipBg, colAccent, colBorder)
        heightHeader.addView(heightValueView)
        card.addView(heightHeader)

        heightSeekBar = SeekBar(this).apply {
            max = KeyboardPrefs.MAX_HEIGHT_DP - KeyboardPrefs.MIN_HEIGHT_DP
            progress = prefs.keyboardHeightDp - KeyboardPrefs.MIN_HEIGHT_DP
            setPadding(0, dp(10), 0, dp(4))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val h = KeyboardPrefs.MIN_HEIGHT_DP + progress
                    heightValueView.text = "$h dp"
                    if (fromUser && !isSyncingUi) {
                        prefs.keyboardHeightDp = h
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        card.addView(heightSeekBar)

        card.addView(createDivider())

        val numRow = buildSwitchRow(
            title = "Dedicated number row",
            subtitle = "Show a fixed 1–0 row above letter keys (stable height across symbols).",
            checked = prefs.showNumberRow
        ) { prefs.showNumberRow = it }
        switchNumberRow = numRow.second
        card.addView(numRow.first)

        card.addView(createDivider())

        val subRow = buildSwitchRow(
            title = "Show key sub-labels",
            subtitle = "Display secondary symbols on keycaps.",
            checked = prefs.showSubLabels
        ) { prefs.showSubLabels = it }
        switchSubLabels = subRow.second
        card.addView(subRow.first)

        card.addView(createDivider())

        val lpRow = buildSwitchRow(
            title = "Long-press for symbols",
            subtitle = "Hold a key to type its secondary symbol.",
            checked = prefs.longPressSymbols
        ) { prefs.longPressSymbols = it }
        switchLongPress = lpRow.second
        card.addView(lpRow.first)

        card.addView(createDivider())

        val hapRow = buildSwitchRow(
            title = "Haptic vibration",
            subtitle = "Crisp tactile feedback on keypress & gestures.",
            checked = prefs.hapticEnabled
        ) { prefs.hapticEnabled = it }
        switchHaptic = hapRow.second
        card.addView(hapRow.first)

        return card
    }

    // --- CARD 6: UPDATES & PRIVACY (OPTION B — 0 INTERNET) ---

    private fun buildUpdatesAndPrivacyCard(): View {
        val card = createCard("06 / UPDATES & PRIVACY", "Zero-Network Guarantee")

        val infoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        infoRow.addView(createMonoPill("VERSION v${getAppVersionName()}", colChipBg, colText, colBorder).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(8)
            }
        })
        infoRow.addView(createMonoPill("0 NETWORK PERMISSIONS VERIFIED", colOkBg, colOkText))
        card.addView(infoRow)

        card.addView(TextView(this).apply {
            text = "True Light declares zero INTERNET or ACCESS_NETWORK_STATE permissions. Checking for releases opens GitHub safely in your external browser."
            textSize = 12.5f
            setTextColor(colSubtext)
            setPadding(0, 0, 0, dp(12))
        })

        val btnReleases = createButton("Check GitHub Releases", primary = false) {
            openGitHubReleases()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        card.addView(btnReleases)

        return card
    }

    private fun openGitHubReleases() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(KeyboardPrefs.GITHUB_RELEASES_URL))
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "No browser found to open GitHub Releases", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildSwitchRow(
        title: String,
        subtitle: String,
        checked: Boolean,
        onToggle: (Boolean) -> Unit
    ): Pair<LinearLayout, Switch> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(12)
            }
        }
        textCol.addView(TextView(this).apply {
            text = title
            textSize = 14.5f
            setTextColor(colText)
        })
        textCol.addView(TextView(this).apply {
            text = subtitle
            textSize = 12f
            setTextColor(colSubtext)
            setPadding(0, dp(2), 0, 0)
        })
        val sw = Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, isChecked ->
                if (!isSyncingUi) {
                    onToggle(isChecked)
                }
            }
        }
        row.setOnClickListener {
            if (sw.isEnabled) sw.toggle()
        }
        row.addView(textCol)
        row.addView(sw)
        return row to sw
    }

    // --- SYNC & REFRESH ---

    private fun syncUiFromPrefs() {
        isSyncingUi = true
        try {
            refreshLanguagesList()
            updateThemePillsVisual(prefs.themeMode)
            updateCssStatusBadge()

            heightValueView.text = "${prefs.keyboardHeightDp} dp"
            val expectedProgress = prefs.keyboardHeightDp - KeyboardPrefs.MIN_HEIGHT_DP
            if (heightSeekBar.progress != expectedProgress) {
                heightSeekBar.progress = expectedProgress
            }

            viToneValueView.text = if (prefs.vietnameseModernTone) {
                "New style (hoà, hoè, thuý)"
            } else {
                "Old style (hòa, hòe, thúy)"
            }

            switchSuggestions.isChecked = prefs.suggestionsEnabled
            switchAutoCorrect.isEnabled = prefs.suggestionsEnabled
            switchAutoCorrect.isChecked = prefs.autoCorrectEnabled
            switchAutoCaps.isChecked = prefs.autoCapsEnabled
            switchDoubleSpace.isChecked = prefs.doubleSpacePeriodEnabled
            switchClipboard.isChecked = prefs.clipboardEnabled
            switchCustomCss.isChecked = prefs.customCssEnabled
            switchNumberRow.isChecked = prefs.showNumberRow
            switchSubLabels.isChecked = prefs.showSubLabels
            switchLongPress.isChecked = prefs.longPressSymbols
            switchHaptic.isChecked = prefs.hapticEnabled
        } finally {
            isSyncingUi = false
        }
    }

    private fun updateThemePillsVisual(selectedMode: Int) {
        val activeIdx = selectedMode.coerceIn(0, themePills.lastIndex)
        for (i in themePills.indices) {
            val pill = themePills[i]
            if (i == activeIdx) {
                pill.background = roundedBg(colAccent, 8)
                pill.setTextColor(colOnAccent)
                pill.typeface = Typeface.DEFAULT_BOLD
            } else {
                pill.background = null
                pill.setTextColor(colSubtext)
                pill.typeface = Typeface.DEFAULT
            }
        }
    }

    private fun updateCssStatusBadge() {
        if (prefs.customCssEnabled) {
            cssStatusBadge.text = "CSS ACTIVE"
            cssStatusBadge.background = roundedBg(colOkBg, 8)
            cssStatusBadge.setTextColor(colOkText)
        } else {
            cssStatusBadge.text = "CSS OFF"
            cssStatusBadge.background = roundedBg(colChipBg, 8, colBorder)
            cssStatusBadge.setTextColor(colSubtext)
        }
    }

    private fun refreshSetupStatus() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val isEnabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val defaultIme = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        val isDefault = defaultIme.startsWith("$packageName/")

        if (isEnabled) {
            statusEnabledPill.text = "1. ENABLED"
            statusEnabledPill.background = roundedBg(colOkBg, 8)
            statusEnabledPill.setTextColor(colOkText)
        } else {
            statusEnabledPill.text = "1. NOT ENABLED"
            statusEnabledPill.background = roundedBg(colWarnBg, 8)
            statusEnabledPill.setTextColor(colWarnText)
        }

        if (isDefault) {
            statusDefaultPill.text = "2. DEFAULT IME"
            statusDefaultPill.background = roundedBg(colOkBg, 8)
            statusDefaultPill.setTextColor(colOkText)
        } else {
            statusDefaultPill.text = "2. NOT DEFAULT"
            statusDefaultPill.background = roundedBg(colWarnBg, 8)
            statusDefaultPill.setTextColor(colWarnText)
        }
    }

    private fun refreshLanguagesList() {
        languagesContainer.removeAllViews()
        val enabledIds = prefs.enabledLanguages
        val activeId = prefs.activeLanguageId
        var hasVietnamese = false

        for (id in enabledIds) {
            val spec = LanguageRegistry.getById(id)
            if (spec.isVietnamese()) hasVietnamese = true
            val isActive = (spec.id == activeId)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedBg(colChipBg, 10, if (isActive) colAccent else colBorder)
                setPadding(dp(12), dp(10), dp(10), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(6)
                }
                setOnClickListener {
                    prefs.activeLanguageId = spec.id
                    refreshLanguagesList()
                    Toast.makeText(this@SettingsActivity, "Active: ${spec.displayName}", Toast.LENGTH_SHORT).show()
                }
            }

            val infoCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            infoCol.addView(TextView(this).apply {
                text = spec.displayName
                textSize = 14f
                typeface = if (isActive) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(if (isActive) colAccent else colText)
            })
            val dictBadge = if (SuggestionManager.hasCustomDictionary(this, spec.dictKey)) {
                "${spec.nativeName} • Custom offline dict"
            } else {
                spec.nativeName
            }
            infoCol.addView(TextView(this).apply {
                text = dictBadge
                textSize = 12f
                setTextColor(colSubtext)
            })
            row.addView(infoCol)

            if (isActive) {
                row.addView(createMonoPill("ACTIVE", colAccent, colOnAccent).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginEnd = if (enabledIds.size > 1) dp(6) else 0
                    }
                })
            }

            if (enabledIds.size > 1) {
                val removeBtn = TextView(this).apply {
                    text = "✕"
                    textSize = 15f
                    setTextColor(colSubtext)
                    setPadding(dp(10), dp(4), dp(6), dp(4))
                    setOnClickListener {
                        prefs.removeLanguage(spec.id)
                        refreshLanguagesList()
                        refreshRamMonitor()
                    }
                }
                row.addView(removeBtn)
            }

            languagesContainer.addView(row)
        }

        viToneRow.visibility = if (hasVietnamese) View.VISIBLE else View.GONE
    }

    // --- DIALOGS ---

    private fun showCssEditorDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(16)
            setPadding(p, dp(12), p, dp(8))
        }

        val initialCss = prefs.customCss.ifBlank { CssThemeParser.DEFAULT_CSS_TEMPLATE }
        val editor = EditText(this).apply {
            setText(initialCss)
            typeface = Typeface.MONOSPACE
            textSize = 12.5f
            setTextColor(colText)
            background = roundedBg(colChipBg, 10, colBorder)
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 10
            maxLines = 15
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Utility toolbar: Reset Default & Copy
        val toolRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        }
        val btnReset = createButton("Reset Default", primary = false) {
            editor.setText(CssThemeParser.DEFAULT_CSS_TEMPLATE)
            Toast.makeText(this, "Loaded default CSS template", Toast.LENGTH_SHORT).show()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        val btnCopy = createButton("Copy", primary = false) {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("True Light CSS", editor.text.toString()))
            Toast.makeText(this, "CSS copied to clipboard", Toast.LENGTH_SHORT).show()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        toolRow.addView(btnReset)
        toolRow.addView(btnCopy)

        container.addView(toolRow)
        container.addView(editor)

        AlertDialog.Builder(this)
            .setTitle("Custom CSS Style Sheet")
            .setView(container)
            .setPositiveButton("Apply") { _, _ ->
                val text = editor.text.toString()
                prefs.customCss = text
                prefs.customCssEnabled = true
                syncUiFromPrefs()
                Toast.makeText(this, "Custom CSS style applied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddLanguageDialog() {
        val enabled = prefs.enabledLanguages.toSet()
        val available = LanguageRegistry.ALL_LANGUAGES.filter { it.id !in enabled }
        if (available.isEmpty()) {
            Toast.makeText(this, "All Group 1 languages are already added", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = available.map { "${it.displayName} (${it.nativeName})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Add Language")
            .setItems(labels) { _, which ->
                val chosen = available[which]
                prefs.addLanguage(chosen.id)
                prefs.activeLanguageId = chosen.id
                syncUiFromPrefs()
                refreshRamMonitor()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showVietnameseToneDialog() {
        val options = arrayOf(
            "Old style (hòa, hòe, thúy)",
            "New style (hoà, hoè, thuý)"
        )
        val current = if (prefs.vietnameseModernTone) 1 else 0
        AlertDialog.Builder(this)
            .setTitle("Vietnamese Tone Placement")
            .setSingleChoiceItems(options, current) { dialog, which ->
                prefs.vietnameseModernTone = (which == 1)
                syncUiFromPrefs()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showImportDictionaryLanguagePicker() {
        val enabledSpecs = prefs.enabledLanguages.map { LanguageRegistry.getById(it) }
        val labels = enabledSpecs.map { "${it.displayName} [${it.dictKey}]" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Import Offline Wordlist (.txt) for:")
            .setItems(labels) { _, which ->
                pendingImportDictKey = enabledSpecs[which].dictKey
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/plain"
                }
                startActivityForResult(intent, REQ_IMPORT_DICT)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importDictionaryFromUri(uri: Uri, dictKey: String) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
                val count = SuggestionManager.importCustomDictionary(this, dictKey, text)
                prefs.bumpDictRevision()
                refreshLanguagesList()
                refreshRamMonitor()
                Toast.makeText(this, "Imported $count words for [$dictKey]", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
