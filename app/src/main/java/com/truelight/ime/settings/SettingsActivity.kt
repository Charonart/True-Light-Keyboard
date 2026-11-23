package com.truelight.ime.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.truelight.ime.R
import com.truelight.ime.config.CssThemeParser
import com.truelight.ime.config.KeyboardPrefs
import com.truelight.ime.config.PrefsProvider
import com.truelight.ime.config.RemotePrefsClient
import com.truelight.ime.lang.LanguageRegistry
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Material Design 3 Settings & Live IME Telemetry App running in isolated `android:process=":settings"`.
 *
 * Architecture guarantees:
 *  - Material 3 / AndroidX loads ONLY in `:settings` (adds 0 MB to the pure-Canvas `TrueLightIME` process).
 *  - All reads, writes, and live IME process RAM stats bridge atomically through [RemotePrefsClient]
 *    and [PrefsProvider] in the main IME process.
 *  - Zero INTERNET permission — 100% offline and private.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val REQ_IMPORT_DICT = 1001
        private const val RAM_REFRESH_INTERVAL_MS = 2500L
        private const val RAM_DEBOUNCE_MS = 250L
        private const val REF_AOSP_MB = 55
        private const val REF_GBOARD_MB = 167
        private const val RAM_SCALE_MAX_MB = 180

        private const val CSS_PRESET_PAPER_LIGHT = """keyboard {
  bg: #E9EDF2;
  strip-bg: #F5F7FA;
  key-bg: #FFFFFF;
  key-special-bg: #D7DEE8;
  key-pressed-bg: #C5D1E3;
  key-border: #CBD3DE;
  key-border-width: 1dp;
  key-radius: 6dp;
  key-gap-h: 3dp;
  key-gap-v: 4dp;
  key-shadow: 1.5dp;
  text-color: #14171C;
  subtext-color: #5C6573;
  accent: #1A66E8;
  text-on-accent: #FFFFFF;
  font-size: 20sp;
}"""

        private const val CSS_PRESET_AMOLED_PURE = """keyboard {
  bg: #000000;
  strip-bg: #000000;
  key-bg: #121316;
  key-special-bg: #1B1D22;
  key-pressed-bg: #2E323B;
  key-border: #262A33;
  key-border-width: 1dp;
  key-radius: 6dp;
  key-gap-h: 3dp;
  key-gap-v: 4dp;
  key-shadow: 0dp;
  text-color: #F5F6F8;
  subtext-color: #828794;
  accent: #4F8CFF;
  text-on-accent: #FFFFFF;
  font-size: 20sp;
}"""

        private const val CSS_PRESET_NORD_SLATE = """keyboard {
  bg: #2E3440;
  strip-bg: #242933;
  key-bg: #3B4252;
  key-special-bg: #434C5E;
  key-pressed-bg: #4C566A;
  key-border: #4C566A;
  key-border-width: 1dp;
  key-radius: 6dp;
  key-gap-h: 3dp;
  key-gap-v: 4dp;
  key-shadow: 1.5dp;
  text-color: #ECEFF4;
  subtext-color: #D8DEE9;
  accent: #88C0D0;
  text-on-accent: #2E3440;
  font-size: 20sp;
}"""
    }

    private lateinit var remotePrefs: RemotePrefsClient
    private var pendingImportDictKey: String = "en"
    private var isSyncingUi = false
    private var activeTabIndex = 0

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ramRefreshRunnable = object : Runnable {
        override fun run() {
            refreshRamMonitor()
            mainHandler.postDelayed(this, RAM_REFRESH_INTERVAL_MS)
        }
    }
    private val debouncedRamRefreshRunnable = Runnable {
        refreshRamMonitor()
    }

    private val prefsObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            remotePrefs.invalidateCache()
            syncUiFromPrefs()
            refreshRamMonitor()
            mainHandler.removeCallbacks(debouncedRamRefreshRunnable)
            mainHandler.postDelayed(debouncedRamRefreshRunnable, RAM_DEBOUNCE_MS)
        }
    }

    // Tab views & top bar
    private lateinit var tabPages: Array<View>
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var topRamPill: TextView

    // Home Tab live memory & setup views
    private lateinit var ramHeadlineView: TextView
    private lateinit var ramStatusChip: TextView
    private lateinit var ramLiveBarLabel: TextView
    private lateinit var ramLiveIndicator: LinearProgressIndicator
    private lateinit var ramFeatureSummaryView: TextView
    private lateinit var ramDetailsToggleView: TextView
    private lateinit var ramDetailsTextView: TextView
    private var isRamDetailsExpanded = false

    private lateinit var statusEnabledChip: TextView
    private lateinit var statusDefaultChip: TextView

    // Typing Tab views
    private lateinit var languagesContainer: LinearLayout
    private lateinit var viToneRow: LinearLayout
    private lateinit var viToneValueView: TextView
    private lateinit var switchSuggestions: MaterialSwitch
    private lateinit var switchAutoCorrect: MaterialSwitch
    private lateinit var enterLongPressSegments: Array<TextView>
    private lateinit var switchShowEmojiKey: MaterialSwitch
    private lateinit var switchAutoCaps: MaterialSwitch
    private lateinit var switchDoubleSpace: MaterialSwitch
    private lateinit var switchClipboard: MaterialSwitch

    // Style Tab views
    private lateinit var themePresetCards: Array<MaterialCardView>
    private lateinit var themePresetTitles: Array<TextView>
    private lateinit var themePresetSubtitles: Array<TextView>
    private lateinit var switchCustomCss: MaterialSwitch
    private lateinit var cssStatusChip: TextView
    private lateinit var heightValueChip: TextView
    private lateinit var heightSlider: Slider
    private lateinit var switchNumberRow: MaterialSwitch
    private lateinit var switchSubLabels: MaterialSwitch
    private lateinit var switchLongPress: MaterialSwitch
    private lateinit var switchHaptic: MaterialSwitch

    // Resolved Material 3 dynamic color tokens
    private var isNightMode = false
    private var colSurface = 0
    private var colSurfaceLow = 0
    private var colSurfaceContainer = 0
    private var colSurfaceHigh = 0
    private var colOnSurface = 0
    private var colOnSurfaceVariant = 0
    private var colPrimary = 0
    private var colOnPrimary = 0
    private var colPrimaryContainer = 0
    private var colOnPrimaryContainer = 0
    private var colSecondaryContainer = 0
    private var colOnSecondaryContainer = 0
    private var colOutlineVariant = 0
    private var colError = 0
    private var colOkBg = 0
    private var colOkText = 0
    private var colWarnBg = 0
    private var colWarnText = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)

        remotePrefs = RemotePrefsClient(this)
        resolveMaterialColors()
        setContentView(buildRootLayout())
        applySystemBarsAppearance()
        selectTab(0)
    }

    override fun onResume() {
        super.onResume()
        try {
            contentResolver.registerContentObserver(PrefsProvider.CONTENT_URI, true, prefsObserver)
        } catch (_: Exception) {
            // Safe fallback if observer registration fails
        }
        remotePrefs.invalidateCache()
        refreshSetupStatus()
        syncUiFromPrefs()
        refreshRamMonitor()
        mainHandler.removeCallbacks(ramRefreshRunnable)
        mainHandler.postDelayed(ramRefreshRunnable, RAM_REFRESH_INTERVAL_MS)
    }

    override fun onPause() {
        super.onPause()
        try {
            contentResolver.unregisterContentObserver(prefsObserver)
        } catch (_: Exception) {
            // Ignore
        }
        mainHandler.removeCallbacks(ramRefreshRunnable)
        mainHandler.removeCallbacks(debouncedRamRefreshRunnable)
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
            info.versionName ?: "1.2.0"
        } catch (_: Exception) {
            "1.2.0"
        }
    }

    private fun resolveMaterialColors() {
        isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        val fallbackSurface = if (isNightMode) 0xFF111318.toInt() else 0xFFF8F9FC.toInt()
        val fallbackLow = if (isNightMode) 0xFF191C22.toInt() else 0xFFFFFFFF.toInt()
        val fallbackContainer = if (isNightMode) 0xFF1F2229.toInt() else 0xFFF0F3F8.toInt()
        val fallbackHigh = if (isNightMode) 0xFF2A2E37.toInt() else 0xFFE5EAF2.toInt()
        val fallbackOnSurface = if (isNightMode) 0xFFE4E7EE.toInt() else 0xFF191C20.toInt()
        val fallbackOnVariant = if (isNightMode) 0xFFC2C7D0.toInt() else 0xFF44474F.toInt()
        val fallbackPrimary = if (isNightMode) 0xFFA8C7FA.toInt() else 0xFF1A66E8.toInt()
        val fallbackOnPrimary = if (isNightMode) 0xFF062E6F.toInt() else 0xFFFFFFFF.toInt()
        val fallbackPrimaryCont = if (isNightMode) 0xFF0842A0.toInt() else 0xFFD8E6FF.toInt()
        val fallbackOnPrimaryCont = if (isNightMode) 0xFFD3E3FD.toInt() else 0xFF041E49.toInt()
        val fallbackSecCont = if (isNightMode) 0xFF334055.toInt() else 0xFFE0E8F6.toInt()
        val fallbackOnSecCont = if (isNightMode) 0xFFD8E4F8.toInt() else 0xFF15253B.toInt()
        val fallbackOutline = if (isNightMode) 0xFF3A3F4A.toInt() else 0xFFD3D8E0.toInt()
        val fallbackError = if (isNightMode) 0xFFF2B8B5.toInt() else 0xFFB3261E.toInt()

        colSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, fallbackSurface)
        colSurfaceLow = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerLow, fallbackLow)
        colSurfaceContainer = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainer, fallbackContainer)
        colSurfaceHigh = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerHigh, fallbackHigh)
        colOnSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, fallbackOnSurface)
        colOnSurfaceVariant = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, fallbackOnVariant)
        colPrimary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, fallbackPrimary)
        colOnPrimary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimary, fallbackOnPrimary)
        colPrimaryContainer = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryContainer, fallbackPrimaryCont)
        colOnPrimaryContainer = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimaryContainer, fallbackOnPrimaryCont)
        colSecondaryContainer = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSecondaryContainer, fallbackSecCont)
        colOnSecondaryContainer = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSecondaryContainer, fallbackOnSecCont)
        colOutlineVariant = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant, fallbackOutline)
        colError = MaterialColors.getColor(this, com.google.android.material.R.attr.colorError, fallbackError)

        if (isNightMode) {
            colOkBg = 0xFF1A3A2A.toInt()
            colOkText = 0xFF81C784.toInt()
            colWarnBg = 0xFF3E2C14.toInt()
            colWarnText = 0xFFFFB74D.toInt()
        } else {
            colOkBg = 0xFFE3F5E9.toInt()
            colOkText = 0xFF137333.toInt()
            colWarnBg = 0xFFFEF2DF.toInt()
            colWarnText = 0xFFB06000.toInt()
        }
    }

    private fun applySystemBarsAppearance() {
        window.statusBarColor = colSurface
        window.navigationBarColor = colSurfaceContainer
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (isNightMode) 0 else mask, mask)
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

    private fun pillBg(bgColor: Int, radiusDp: Int = 100, strokeColor: Int = Color.TRANSPARENT): GradientDrawable {
        return GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != Color.TRANSPARENT) {
                setStroke(dp(1), strokeColor)
            }
        }
    }

    // --- ROOT SHELL: M3 TOP BAR + 4 TAB PAGES + M3 BOTTOM NAVIGATION ---

    private fun buildRootLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colSurface)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        root.addView(buildTopHeader())

        // Hairline separator below header
        root.addView(View(this).apply {
            setBackgroundColor(colOutlineVariant)
            alpha = 0.45f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        })

        val contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val homePage = wrapInScroll(buildHomeTab())
        val typingPage = wrapInScroll(buildTypingTab())
        val stylePage = wrapInScroll(buildStyleTab())
        val aboutPage = wrapInScroll(buildAboutTab())
        tabPages = arrayOf(homePage, typingPage, stylePage, aboutPage)
        for (page in tabPages) {
            contentFrame.addView(page)
        }
        root.addView(contentFrame)

        bottomNav = BottomNavigationView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            inflateMenu(R.menu.settings_bottom_nav)
            labelVisibilityMode = NavigationBarView.LABEL_VISIBILITY_LABELED
            setOnItemSelectedListener { item ->
                when (item.itemId) {
                    R.id.nav_home -> selectTab(0)
                    R.id.nav_typing -> selectTab(1)
                    R.id.nav_style -> selectTab(2)
                    R.id.nav_about -> selectTab(3)
                    else -> false
                }
            }
        }
        root.addView(bottomNav)

        return root
    }

    private fun selectTab(index: Int): Boolean {
        activeTabIndex = index.coerceIn(0, tabPages.lastIndex)
        for (i in tabPages.indices) {
            tabPages[i].visibility = if (i == activeTabIndex) View.VISIBLE else View.GONE
        }
        if (activeTabIndex == 0) {
            refreshSetupStatus()
            refreshRamMonitor()
        }
        return true
    }

    private fun buildTopHeader(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(colSurface)
            setPadding(dp(16), dp(12), dp(14), dp(12))
        }

        bar.addView(TextView(this).apply {
            text = "True Light"
            textSize = 20f
            maxLines = 1
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = -0.015f
            setTextColor(colOnSurface)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        })

        topRamPill = createChip("○ -- MB • Standby", colSurfaceContainer, colOnSurfaceVariant, colOutlineVariant).apply {
            maxLines = 1
            setPadding(dp(9), dp(4), dp(9), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(6)
            }
        }
        bar.addView(topRamPill)

        bar.addView(createChip("v${getAppVersionName()}", colSurfaceContainer, colOnSurfaceVariant, colOutlineVariant).apply {
            maxLines = 1
            setPadding(dp(8), dp(4), dp(8), dp(4))
        })

        return bar
    }

    private fun wrapInScroll(content: View): NestedScrollView {
        return NestedScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(content)
        }
    }

    private fun createTabColumn(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(28))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    // --- MATERIAL 3 UI HELPERS ---

    private fun createChip(
        label: String,
        bgColor: Int,
        textColor: Int,
        strokeColor: Int = Color.TRANSPARENT
    ): TextView {
        return TextView(this).apply {
            text = label
            textSize = 11.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textColor)
            background = pillBg(bgColor, 100, strokeColor)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
    }

    private fun createCard(title: String, subtitle: String? = null): Pair<MaterialCardView, LinearLayout> {
        val card = MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = colOutlineVariant
            setCardBackgroundColor(colSurfaceLow)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(18))
        }
        body.addView(TextView(this).apply {
            text = title
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(colOnSurface)
        })
        if (subtitle != null) {
            body.addView(TextView(this).apply {
                text = subtitle
                textSize = 12.5f
                setTextColor(colOnSurfaceVariant)
                setPadding(0, dp(3), 0, dp(12))
            })
        } else {
            body.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(1, dp(10))
            })
        }
        card.addView(body)
        return card to body
    }

    private fun createFilledButton(label: String, onClick: () -> Unit): MaterialButton {
        return MaterialButton(this).apply {
            text = label
            isAllCaps = false
            cornerRadius = dp(14)
            setOnClickListener { onClick() }
        }
    }

    private fun createTonalButton(label: String, onClick: () -> Unit): MaterialButton {
        return MaterialButton(this).apply {
            text = label
            isAllCaps = false
            cornerRadius = dp(14)
            backgroundTintList = ColorStateList.valueOf(colSecondaryContainer)
            setTextColor(colOnSecondaryContainer)
            elevation = 0f
            stateListAnimator = null
            setOnClickListener { onClick() }
        }
    }

    private fun createOutlinedButton(label: String, onClick: () -> Unit): MaterialButton {
        return MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            isAllCaps = false
            cornerRadius = dp(14)
            setOnClickListener { onClick() }
        }
    }

    private fun createDivider(): View {
        return View(this).apply {
            setBackgroundColor(colOutlineVariant)
            alpha = 0.55f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                topMargin = dp(10)
                bottomMargin = dp(10)
            }
        }
    }

    private fun createSwitchRow(
        title: String,
        subtitle: String? = null,
        checked: Boolean,
        onToggle: (Boolean) -> Unit
    ): Pair<LinearLayout, MaterialSwitch> {
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
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(colOnSurface)
        })
        if (!subtitle.isNullOrEmpty()) {
            textCol.addView(TextView(this).apply {
                text = subtitle
                textSize = 12.5f
                setTextColor(colOnSurfaceVariant)
                setPadding(0, dp(2), 0, 0)
            })
        }
        val sw = MaterialSwitch(this).apply {
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

    // =========================================================================
    // TAB 1 — HOME (KEYBOARD MEMORY + SETUP & TEST)
    // =========================================================================

    private fun buildHomeTab(): View {
        val col = createTabColumn()
        col.addView(buildRamTelemetryCard())
        col.addView(buildSetupAndSandboxCard())
        return col
    }

    private fun buildRamTelemetryCard(): View {
        val (card, body) = createCard(title = "Keyboard Memory")

        // Headline RAM + Active/Standby chip
        val headlineRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        ramHeadlineView = TextView(this).apply {
            text = "--.- MB"
            textSize = 30f
            typeface = Typeface.MONOSPACE
            setTextColor(colPrimary)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(10)
            }
        }
        ramStatusChip = createChip("Standby", colSurfaceContainer, colOnSurfaceVariant, colOutlineVariant)
        headlineRow.addView(ramHeadlineView)
        headlineRow.addView(ramStatusChip)
        body.addView(headlineRow)

        // 3 clean comparison bars
        val liveBar = buildProgressBarRow("True Light", "--.- MB", colPrimary, 10)
        ramLiveBarLabel = liveBar.second
        ramLiveIndicator = liveBar.third
        body.addView(liveBar.first)

        body.addView(
            buildProgressBarRow("Android Default", "~55 MB", colOnSurfaceVariant, REF_AOSP_MB).first
        )
        body.addView(
            buildProgressBarRow("Gboard", "~167 MB", colError, REF_GBOARD_MB).first
        )

        // Compact 1-line feature summary + collapsed-by-default "Details ▾" toggle
        val footerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(2)
            }
        }
        ramFeatureSummaryView = TextView(this).apply {
            text = "Suggestions: On  •  Clipboard: On"
            textSize = 12.5f
            setTextColor(colOnSurfaceVariant)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        ramDetailsToggleView = TextView(this).apply {
            text = "Details ▾"
            textSize = 12.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(colPrimary)
            setPadding(dp(6), dp(4), dp(2), dp(4))
            setOnClickListener {
                isRamDetailsExpanded = !isRamDetailsExpanded
                ramDetailsTextView.visibility = if (isRamDetailsExpanded) View.VISIBLE else View.GONE
                text = if (isRamDetailsExpanded) "Details ▴" else "Details ▾"
            }
        }
        footerRow.addView(ramFeatureSummaryView)
        footerRow.addView(ramDetailsToggleView)
        body.addView(footerRow)

        // Collapsible single monospace technical line (collapsed by default)
        ramDetailsTextView = TextView(this).apply {
            text = "PSS -- MB • Heap -- MB • Native -- MB • PID --"
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(colOnSurfaceVariant)
            visibility = View.GONE
            setPadding(0, dp(6), 0, 0)
        }
        body.addView(ramDetailsTextView)

        // Debug-only 1-line amber badge
        val isDebugBuild = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebugBuild) {
            body.addView(TextView(this).apply {
                text = "Debug build — signed Release APK uses less RAM."
                textSize = 11.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(colWarnText)
                background = pillBg(colWarnBg, 10)
                setPadding(dp(10), dp(7), dp(10), dp(7))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(10)
                }
            })
        }

        return card
    }

    private fun buildProgressBarRow(
        label: String,
        valueText: String,
        indicatorColor: Int,
        initialProgress: Int
    ): Triple<LinearLayout, TextView, LinearProgressIndicator> {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(5))
        }
        header.addView(TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(colOnSurface)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val valueTv = TextView(this).apply {
            text = valueText
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(colOnSurfaceVariant)
        }
        header.addView(valueTv)
        wrapper.addView(header)

        val indicator = LinearProgressIndicator(this).apply {
            isIndeterminate = false
            max = RAM_SCALE_MAX_MB
            progress = initialProgress.coerceIn(0, RAM_SCALE_MAX_MB)
            trackThickness = dp(8)
            trackCornerRadius = dp(4)
            setIndicatorColor(indicatorColor)
            trackColor = colSurfaceHigh
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        wrapper.addView(indicator)
        return Triple(wrapper, valueTv, indicator)
    }

    private fun buildSetupAndSandboxCard(): View {
        val (card, body) = createCard(title = "Setup & Test")

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }
        statusEnabledChip = createChip("1. Checking...", colSurfaceContainer, colOnSurfaceVariant).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        statusDefaultChip = createChip("2. Checking...", colSurfaceContainer, colOnSurfaceVariant).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusRow.addView(statusEnabledChip)
        statusRow.addView(statusDefaultChip)
        body.addView(statusRow)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
        }
        val btnEnable = createFilledButton("Enable") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        val btnDefault = createTonalButton("Select Default") {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnRow.addView(btnEnable)
        btnRow.addView(btnDefault)
        body.addView(btnRow)

        // Material 3 OutlinedBox TextInputLayout + TextInputEditText
        val inputLayout = TextInputLayout(this).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            val corner = dp(14).toFloat()
            setBoxCornerRadii(corner, corner, corner, corner)
            hint = "Tap to open keyboard & watch RAM ↑"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val editText = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            maxLines = 4
            gravity = Gravity.TOP or Gravity.START
        }
        inputLayout.addView(editText)
        body.addView(inputLayout)

        return card
    }

    // =========================================================================
    // TAB 2 — TYPING (LANGUAGES, SUGGESTIONS, GESTURES & SHORTCUTS)
    // =========================================================================

    private fun buildTypingTab(): View {
        val col = createTabColumn()
        col.addView(buildLanguagesCard())
        col.addView(buildSuggestionsCard())
        col.addView(buildGesturesCard())
        return col
    }

    private fun buildLanguagesCard(): View {
        val (card, body) = createCard(title = "Languages")

        languagesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        body.addView(languagesContainer)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        }
        val btnAdd = createFilledButton("+ Add Language") {
            showAddLanguageDialog()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        val btnImport = createOutlinedButton("Import Dict") {
            showImportDictionaryLanguagePicker()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnRow.addView(btnAdd)
        btnRow.addView(btnImport)
        body.addView(btnRow)

        // Vietnamese tone placement selector (shown only when Vietnamese is added)
        viToneRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillBg(colSurfaceContainer, 14)
            setPadding(dp(14), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
            setOnClickListener { showVietnameseToneDialog() }
        }
        val viCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        viCol.addView(TextView(this).apply {
            text = "Vietnamese tone"
            textSize = 14.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(colOnSurface)
        })
        viToneValueView = TextView(this).apply {
            textSize = 12.5f
            setTextColor(colOnSurfaceVariant)
        }
        viCol.addView(viToneValueView)
        viToneRow.addView(viCol)
        viToneRow.addView(createChip("Change", colPrimaryContainer, colOnPrimaryContainer))
        body.addView(viToneRow)

        return card
    }

    private fun buildSuggestionsCard(): View {
        val (card, body) = createCard(title = "Suggestions")

        val sugRow = createSwitchRow(
            title = "Text suggestions",
            subtitle = "Off = unload dictionary (0 MB)",
            checked = remotePrefs.suggestionsEnabled
        ) { isChecked ->
            remotePrefs.suggestionsEnabled = isChecked
            switchAutoCorrect.isEnabled = isChecked
            if (!isChecked) {
                isSyncingUi = true
                switchAutoCorrect.isChecked = false
                isSyncingUi = false
            }
            refreshRamMonitor()
        }
        switchSuggestions = sugRow.second
        body.addView(sugRow.first)

        body.addView(createDivider())

        val acRow = createSwitchRow(
            title = "Auto-correct",
            subtitle = "Fix typos on Space, Backspace undoes",
            checked = remotePrefs.autoCorrectEnabled
        ) { isChecked ->
            remotePrefs.autoCorrectEnabled = isChecked
            refreshRamMonitor()
        }
        switchAutoCorrect = acRow.second
        switchAutoCorrect.isEnabled = remotePrefs.suggestionsEnabled
        body.addView(acRow.first)

        return card
    }

    private fun buildGesturesCard(): View {
        val (card, body) = createCard(title = "Typing & Shortcuts")

        // Long-press Enter segmented selector
        body.addView(TextView(this).apply {
            text = "Long-press Enter"
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(colOnSurface)
            setPadding(0, 0, 0, dp(8))
        })

        val segContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = pillBg(colSurfaceContainer, 14, colOutlineVariant)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val segmentDefs = arrayOf(
            "Emoji" to KeyboardPrefs.ENTER_LONG_PRESS_EMOJI,
            "Newline" to KeyboardPrefs.ENTER_LONG_PRESS_NEWLINE,
            "Off" to KeyboardPrefs.ENTER_LONG_PRESS_NONE
        )
        enterLongPressSegments = Array(segmentDefs.size) { idx ->
            val (label, actionVal) = segmentDefs[idx]
            TextView(this).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(9), dp(6), dp(9))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    remotePrefs.longPressEnterAction = actionVal
                    updateEnterLongPressSegments(actionVal)
                }
            }.also { segContainer.addView(it) }
        }
        body.addView(segContainer)

        body.addView(createDivider())

        val emojiKeyRow = createSwitchRow(
            title = "Emoji key on bottom row",
            subtitle = "Off = wider spacebar",
            checked = remotePrefs.showEmojiKey
        ) { isChecked ->
            remotePrefs.showEmojiKey = isChecked
        }
        switchShowEmojiKey = emojiKeyRow.second
        body.addView(emojiKeyRow.first)

        body.addView(createDivider())

        val capsRow = createSwitchRow(
            title = "Auto-capitalization",
            subtitle = "Capitalize first word",
            checked = remotePrefs.autoCapsEnabled
        ) { remotePrefs.autoCapsEnabled = it }
        switchAutoCaps = capsRow.second
        body.addView(capsRow.first)

        body.addView(createDivider())

        val dspRow = createSwitchRow(
            title = "Double-space period",
            subtitle = "Tap space twice for . ",
            checked = remotePrefs.doubleSpacePeriodEnabled
        ) { remotePrefs.doubleSpacePeriodEnabled = it }
        switchDoubleSpace = dspRow.second
        body.addView(dspRow.first)

        body.addView(createDivider())

        val clipRow = createSwitchRow(
            title = "Clipboard history",
            subtitle = "Last 10 items, RAM-only",
            checked = remotePrefs.clipboardEnabled
        ) {
            remotePrefs.clipboardEnabled = it
            refreshRamMonitor()
        }
        switchClipboard = clipRow.second
        body.addView(clipRow.first)

        return card
    }

    // =========================================================================
    // TAB 3 — STYLE (THEME PRESETS, CUSTOM CSS, LAYOUT & FEEL)
    // =========================================================================

    private fun buildStyleTab(): View {
        val col = createTabColumn()
        col.addView(buildThemePresetsCard())
        col.addView(buildCustomCssCard())
        col.addView(buildLayoutAndFeelCard())
        return col
    }

    private fun buildThemePresetsCard(): View {
        val (card, body) = createCard(title = "Theme")

        val presets = arrayOf(
            "Dynamic" to "Material You",
            "Dark" to "Slate",
            "AMOLED" to "Pure black",
            "Light" to "Paper white"
        )
        val cardsList = ArrayList<MaterialCardView>(4)
        val titlesList = ArrayList<TextView>(4)
        val subsList = ArrayList<TextView>(4)

        for (rowIdx in 0 until 2) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (rowIdx == 0) bottomMargin = dp(8)
                }
            }
            for (colIdx in 0 until 2) {
                val modeIdx = rowIdx * 2 + colIdx
                val (title, sub) = presets[modeIdx]
                val itemCard = MaterialCardView(this).apply {
                    radius = dp(14).toFloat()
                    cardElevation = 0f
                    strokeWidth = dp(1)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (colIdx == 0) marginEnd = dp(8)
                    }
                    setOnClickListener {
                        remotePrefs.themeMode = modeIdx
                        updateThemePresetSelection(modeIdx)
                    }
                }
                val inner = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                }
                val tvTitle = TextView(this).apply {
                    text = title
                    textSize = 14f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                }
                val tvSub = TextView(this).apply {
                    text = sub
                    textSize = 11.5f
                    setPadding(0, dp(2), 0, 0)
                }
                inner.addView(tvTitle)
                inner.addView(tvSub)
                itemCard.addView(inner)

                cardsList.add(itemCard)
                titlesList.add(tvTitle)
                subsList.add(tvSub)
                row.addView(itemCard)
            }
            body.addView(row)
        }
        themePresetCards = cardsList.toTypedArray()
        themePresetTitles = titlesList.toTypedArray()
        themePresetSubtitles = subsList.toTypedArray()

        return card
    }

    private fun buildCustomCssCard(): View {
        val (card, body) = createCard(title = "Custom CSS")

        val cssToggleRow = createSwitchRow(
            title = "Enable custom CSS",
            subtitle = "Override theme colors & key style",
            checked = remotePrefs.customCssEnabled
        ) { isChecked ->
            remotePrefs.customCssEnabled = isChecked
            updateCssStatusChip()
        }
        switchCustomCss = cssToggleRow.second
        body.addView(cssToggleRow.first)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        }
        val btnEdit = createTonalButton("Edit CSS") {
            showCssEditorDialog()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(10)
            }
        }
        cssStatusChip = createChip("Off", colSurfaceContainer, colOnSurfaceVariant, colOutlineVariant)
        actionRow.addView(btnEdit)
        actionRow.addView(cssStatusChip)
        body.addView(actionRow)

        return card
    }

    private fun buildLayoutAndFeelCard(): View {
        val (card, body) = createCard(title = "Layout & Feel")

        // Height header + Material 3 Slider (180..300 step 5)
        val heightHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heightHeader.addView(TextView(this).apply {
            text = "Keyboard height"
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(colOnSurface)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        heightValueChip = createChip("${snapHeightToStep(remotePrefs.keyboardHeightDp)} dp", colPrimaryContainer, colOnPrimaryContainer)
        heightHeader.addView(heightValueChip)
        body.addView(heightHeader)

        heightSlider = Slider(this).apply {
            valueFrom = KeyboardPrefs.MIN_HEIGHT_DP.toFloat()
            valueTo = KeyboardPrefs.MAX_HEIGHT_DP.toFloat()
            stepSize = 5f
            value = snapHeightToStep(remotePrefs.keyboardHeightDp).toFloat()
            addOnChangeListener { _, newValue, fromUser ->
                val h = newValue.roundToInt()
                heightValueChip.text = "$h dp"
                if (fromUser && !isSyncingUi) {
                    remotePrefs.keyboardHeightDp = h
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
            }
        }
        body.addView(heightSlider)

        body.addView(createDivider())

        val numRow = createSwitchRow(
            title = "Number row",
            subtitle = "Fixed 1–0 row above letters",
            checked = remotePrefs.showNumberRow
        ) { remotePrefs.showNumberRow = it }
        switchNumberRow = numRow.second
        body.addView(numRow.first)

        body.addView(createDivider())

        val subRow = createSwitchRow(
            title = "Key sub-labels",
            subtitle = "Show secondary symbols",
            checked = remotePrefs.showSubLabels
        ) { remotePrefs.showSubLabels = it }
        switchSubLabels = subRow.second
        body.addView(subRow.first)

        body.addView(createDivider())

        val lpRow = createSwitchRow(
            title = "Long-press symbols",
            subtitle = "Hold letter for symbol",
            checked = remotePrefs.longPressSymbols
        ) { remotePrefs.longPressSymbols = it }
        switchLongPress = lpRow.second
        body.addView(lpRow.first)

        body.addView(createDivider())

        val hapRow = createSwitchRow(
            title = "Haptic vibration",
            checked = remotePrefs.hapticEnabled
        ) { remotePrefs.hapticEnabled = it }
        switchHaptic = hapRow.second
        body.addView(hapRow.first)

        return card
    }

    // =========================================================================
    // TAB 4 — ABOUT (PRIVACY, UPDATES, CLEAR USER DATA)
    // =========================================================================

    private fun buildAboutTab(): View {
        val col = createTabColumn()

        // Privacy & Updates Card
        val (privacyCard, privacyBody) = createCard(title = "Privacy & Updates")

        // Compact 2-line max offline privacy banner
        val verifiedBanner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pillBg(colOkBg, 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }
        verifiedBanner.addView(TextView(this).apply {
            text = "Keyboard 100% Offline"
            textSize = 14.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(colOkText)
        })
        verifiedBanner.addView(TextView(this).apply {
            text = "Zero network code in the keyboard. Only manual update checks use internet."
            textSize = 12.5f
            maxLines = 2
            setTextColor(colOkText)
            alpha = 0.9f
            setPadding(0, dp(3), 0, 0)
        })
        privacyBody.addView(verifiedBanner)

        lateinit var btnCheckUpdate: MaterialButton
        btnCheckUpdate = createFilledButton("Check for Updates") {
            checkAndInstallUpdate(btnCheckUpdate)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }
        privacyBody.addView(btnCheckUpdate)

        privacyBody.addView(createOutlinedButton("GitHub Releases") {
            openGitHubReleases()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })
        col.addView(privacyCard)

        // Local Data Management Card
        val (dataCard, dataBody) = createCard(title = "Local Data")

        dataBody.addView(createOutlinedButton("Clear history & learned words") {
            MaterialAlertDialogBuilder(this)
                .setTitle("Clear local history?")
                .setMessage("Erases learned words, imported dictionaries, clipboard, and recent emojis.")
                .setPositiveButton("Clear") { _, _ ->
                    remotePrefs.clearAllUserData()
                    refreshLanguagesList()
                    refreshRamMonitor()
                    Toast.makeText(this, "Cleared local history", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })
        col.addView(dataCard)

        return col
    }

    // =========================================================================
    // LIVE SYNC & TELEMETRY REFRESH
    // =========================================================================

    private fun snapHeightToStep(heightDp: Int): Int {
        val clamped = heightDp.coerceIn(KeyboardPrefs.MIN_HEIGHT_DP, KeyboardPrefs.MAX_HEIGHT_DP)
        val offset = clamped - KeyboardPrefs.MIN_HEIGHT_DP
        val snapped = ((offset + 2) / 5) * 5 + KeyboardPrefs.MIN_HEIGHT_DP
        return snapped.coerceIn(KeyboardPrefs.MIN_HEIGHT_DP, KeyboardPrefs.MAX_HEIGHT_DP)
    }

    private fun refreshRamMonitor() {
        val stats = remotePrefs.getImeMemoryStats()
        val hasReading = stats.totalPssKb > 0
        val totalMb = stats.totalPssKb / 1024f
        val dalvikMb = stats.dalvikPssKb / 1024f
        val nativeMb = stats.nativePssKb / 1024f

        val totalStr = if (hasReading) String.format(Locale.US, "%.1f MB", totalMb) else "--.- MB"
        val pillMbStr = if (hasReading) "${totalMb.roundToInt()} MB" else "-- MB"

        // Top App Bar live RAM pill (always visible even when keyboard is open)
        if (stats.isKeyboardActive) {
            topRamPill.text = "● $pillMbStr • Active"
            topRamPill.background = pillBg(colOkBg, 100)
            topRamPill.setTextColor(colOkText)
        } else {
            topRamPill.text = "○ $pillMbStr • Standby"
            topRamPill.background = pillBg(colSurfaceContainer, 100, colOutlineVariant)
            topRamPill.setTextColor(colOnSurfaceVariant)
        }

        // Home card headline + inline status chip
        ramHeadlineView.text = totalStr
        if (stats.isKeyboardActive) {
            ramStatusChip.text = "Active"
            ramStatusChip.background = pillBg(colOkBg, 100)
            ramStatusChip.setTextColor(colOkText)
        } else {
            ramStatusChip.text = "Standby"
            ramStatusChip.background = pillBg(colSurfaceContainer, 100, colOutlineVariant)
            ramStatusChip.setTextColor(colOnSurfaceVariant)
        }

        ramLiveBarLabel.text = totalStr
        ramLiveIndicator.progress = if (hasReading) totalMb.roundToInt().coerceIn(2, RAM_SCALE_MAX_MB) else 0

        val sugStatus = if (remotePrefs.suggestionsEnabled) "On" else "Off"
        val clipStatus = if (remotePrefs.clipboardEnabled) "On" else "Off"
        ramFeatureSummaryView.text = "Suggestions: $sugStatus  •  Clipboard: $clipStatus"

        val pidText = if (stats.imePid > 0) stats.imePid.toString() else "--"
        ramDetailsTextView.text = if (hasReading) {
            String.format(
                Locale.US,
                "PSS %.1f MB • Heap %.1f MB • Native %.1f MB • PID %s",
                totalMb,
                dalvikMb,
                nativeMb,
                pidText
            )
        } else {
            "PSS -- MB • Heap -- MB • Native -- MB • PID $pidText"
        }
    }

    private fun refreshSetupStatus() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val isEnabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val defaultIme = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        val isDefault = defaultIme.startsWith("$packageName/")

        if (isEnabled) {
            statusEnabledChip.text = "1. Enabled ✓"
            statusEnabledChip.background = pillBg(colOkBg, 100)
            statusEnabledChip.setTextColor(colOkText)
        } else {
            statusEnabledChip.text = "1. Not Enabled"
            statusEnabledChip.background = pillBg(colWarnBg, 100)
            statusEnabledChip.setTextColor(colWarnText)
        }

        if (isDefault) {
            statusDefaultChip.text = "2. Default IME ✓"
            statusDefaultChip.background = pillBg(colOkBg, 100)
            statusDefaultChip.setTextColor(colOkText)
        } else {
            statusDefaultChip.text = "2. Not Default"
            statusDefaultChip.background = pillBg(colWarnBg, 100)
            statusDefaultChip.setTextColor(colWarnText)
        }
    }

    private fun syncUiFromPrefs() {
        isSyncingUi = true
        try {
            refreshLanguagesList()
            updateThemePresetSelection(remotePrefs.themeMode)
            updateCssStatusChip()
            updateEnterLongPressSegments(remotePrefs.longPressEnterAction)

            val snappedH = snapHeightToStep(remotePrefs.keyboardHeightDp)
            heightValueChip.text = "$snappedH dp"
            if (heightSlider.value.roundToInt() != snappedH) {
                heightSlider.value = snappedH.toFloat()
            }

            viToneValueView.text = if (remotePrefs.vietnameseModernTone) {
                "New style (hoà, hoè, thuý)"
            } else {
                "Old style (hòa, hòe, thúy)"
            }

            switchSuggestions.isChecked = remotePrefs.suggestionsEnabled
            switchAutoCorrect.isEnabled = remotePrefs.suggestionsEnabled
            switchAutoCorrect.isChecked = remotePrefs.autoCorrectEnabled
            switchShowEmojiKey.isChecked = remotePrefs.showEmojiKey
            switchAutoCaps.isChecked = remotePrefs.autoCapsEnabled
            switchDoubleSpace.isChecked = remotePrefs.doubleSpacePeriodEnabled
            switchClipboard.isChecked = remotePrefs.clipboardEnabled
            switchCustomCss.isChecked = remotePrefs.customCssEnabled
            switchNumberRow.isChecked = remotePrefs.showNumberRow
            switchSubLabels.isChecked = remotePrefs.showSubLabels
            switchLongPress.isChecked = remotePrefs.longPressSymbols
            switchHaptic.isChecked = remotePrefs.hapticEnabled
        } finally {
            isSyncingUi = false
        }
    }

    private fun updateEnterLongPressSegments(action: Int) {
        val actions = intArrayOf(
            KeyboardPrefs.ENTER_LONG_PRESS_EMOJI,
            KeyboardPrefs.ENTER_LONG_PRESS_NEWLINE,
            KeyboardPrefs.ENTER_LONG_PRESS_NONE
        )
        for (i in enterLongPressSegments.indices) {
            val tv = enterLongPressSegments[i]
            val selected = (actions[i] == action)
            if (selected) {
                tv.background = pillBg(colPrimaryContainer, 10)
                tv.setTextColor(colOnPrimaryContainer)
                tv.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            } else {
                tv.background = null
                tv.setTextColor(colOnSurfaceVariant)
                tv.typeface = Typeface.DEFAULT
            }
        }
    }

    private fun updateThemePresetSelection(selectedMode: Int) {
        val active = selectedMode.coerceIn(0, themePresetCards.lastIndex)
        for (i in themePresetCards.indices) {
            val isSelected = (i == active)
            val card = themePresetCards[i]
            if (isSelected) {
                card.setCardBackgroundColor(colPrimaryContainer)
                card.strokeColor = colPrimary
                card.strokeWidth = dp(2)
                themePresetTitles[i].setTextColor(colOnPrimaryContainer)
                themePresetSubtitles[i].setTextColor(colOnPrimaryContainer)
            } else {
                card.setCardBackgroundColor(colSurfaceContainer)
                card.strokeColor = colOutlineVariant
                card.strokeWidth = dp(1)
                themePresetTitles[i].setTextColor(colOnSurface)
                themePresetSubtitles[i].setTextColor(colOnSurfaceVariant)
            }
        }
    }

    private fun updateCssStatusChip() {
        if (remotePrefs.customCssEnabled) {
            cssStatusChip.text = "Active"
            cssStatusChip.background = pillBg(colOkBg, 100)
            cssStatusChip.setTextColor(colOkText)
        } else {
            cssStatusChip.text = "Off"
            cssStatusChip.background = pillBg(colSurfaceContainer, 100, colOutlineVariant)
            cssStatusChip.setTextColor(colOnSurfaceVariant)
        }
    }

    private fun refreshLanguagesList() {
        languagesContainer.removeAllViews()
        val enabledIds = remotePrefs.enabledLanguages
        val activeId = remotePrefs.activeLanguageId
        var hasVietnamese = false

        for (id in enabledIds) {
            val spec = LanguageRegistry.getById(id)
            if (spec.isVietnamese()) hasVietnamese = true
            val isActive = (spec.id == activeId)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = pillBg(
                    if (isActive) colPrimaryContainer else colSurfaceContainer,
                    14,
                    if (isActive) colPrimary else Color.TRANSPARENT
                )
                setPadding(dp(14), dp(11), dp(10), dp(11))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(8)
                }
                setOnClickListener {
                    remotePrefs.activeLanguageId = spec.id
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
                textSize = 14.5f
                typeface = Typeface.create("sans-serif-medium", if (isActive) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (isActive) colOnPrimaryContainer else colOnSurface)
            })
            val dictSubtitle = if (remotePrefs.hasCustomDictionary(spec.dictKey)) {
                "${spec.nativeName} • Custom dict"
            } else {
                spec.nativeName
            }
            infoCol.addView(TextView(this).apply {
                text = dictSubtitle
                textSize = 12f
                setTextColor(if (isActive) colOnPrimaryContainer else colOnSurfaceVariant)
            })
            row.addView(infoCol)

            if (isActive) {
                row.addView(createChip("Active", colPrimary, colOnPrimary).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginEnd = if (enabledIds.size > 1) dp(6) else 0
                    }
                })
            }

            if (enabledIds.size > 1) {
                row.addView(TextView(this).apply {
                    text = "✕"
                    textSize = 16f
                    setTextColor(if (isActive) colOnPrimaryContainer else colOnSurfaceVariant)
                    setPadding(dp(10), dp(4), dp(8), dp(4))
                    setOnClickListener {
                        remotePrefs.removeLanguage(spec.id)
                        refreshLanguagesList()
                        refreshRamMonitor()
                    }
                })
            }

            languagesContainer.addView(row)
        }

        viToneRow.visibility = if (hasVietnamese) View.VISIBLE else View.GONE
    }

    // =========================================================================
    // MATERIAL 3 DIALOGS & ACTIONS
    // =========================================================================

    private fun showCssEditorDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(6))
        }

        val initialCss = remotePrefs.customCss.ifBlank { CssThemeParser.DEFAULT_CSS_TEMPLATE }
        val editor = EditText(this).apply {
            setText(initialCss)
            typeface = Typeface.MONOSPACE
            textSize = 12.5f
            setTextColor(colOnSurface)
            background = pillBg(colSurfaceContainer, 14, colOutlineVariant)
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 9
            maxLines = 14
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // 1-tap CSS Presets horizontal bar
        container.addView(TextView(this).apply {
            text = "1-tap CSS Presets"
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(colOnSurfaceVariant)
            setPadding(0, 0, 0, dp(6))
        })

        val presetScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        }
        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val cssPresets = arrayOf(
            "Precision Dark" to CssThemeParser.DEFAULT_CSS_TEMPLATE,
            "Paper Light" to CSS_PRESET_PAPER_LIGHT,
            "AMOLED Pure" to CSS_PRESET_AMOLED_PURE,
            "Nord Slate" to CSS_PRESET_NORD_SLATE
        )
        for ((name, cssBody) in cssPresets) {
            presetRow.addView(createChip(name, colSecondaryContainer, colOnSecondaryContainer).apply {
                setPadding(dp(12), dp(7), dp(12), dp(7))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = dp(8)
                }
                setOnClickListener {
                    editor.setText(cssBody)
                    Toast.makeText(this@SettingsActivity, "Loaded $name preset", Toast.LENGTH_SHORT).show()
                }
            })
        }
        presetScroll.addView(presetRow)
        container.addView(presetScroll)

        // Reset & Copy row
        val toolRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        }
        val btnReset = createOutlinedButton("Reset") {
            editor.setText(CssThemeParser.DEFAULT_CSS_TEMPLATE)
            Toast.makeText(this, "Reset to default CSS template", Toast.LENGTH_SHORT).show()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        val btnCopy = createOutlinedButton("Copy") {
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

        MaterialAlertDialogBuilder(this)
            .setTitle("Custom CSS Style Sheet")
            .setView(container)
            .setPositiveButton("Apply") { _, _ ->
                remotePrefs.customCss = editor.text.toString()
                remotePrefs.customCssEnabled = true
                syncUiFromPrefs()
                Toast.makeText(this, "Custom CSS style applied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddLanguageDialog() {
        val enabled = remotePrefs.enabledLanguages.toSet()
        val available = LanguageRegistry.ALL_LANGUAGES.filter { it.id !in enabled }
        if (available.isEmpty()) {
            Toast.makeText(this, "All Group 1 languages are already added", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = available.map { "${it.displayName} (${it.nativeName})" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Add Language")
            .setItems(labels) { _, which ->
                val chosen = available[which]
                remotePrefs.addLanguage(chosen.id)
                remotePrefs.activeLanguageId = chosen.id
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
        val current = if (remotePrefs.vietnameseModernTone) 1 else 0
        MaterialAlertDialogBuilder(this)
            .setTitle("Vietnamese Tone Placement")
            .setSingleChoiceItems(options, current) { dialog, which ->
                remotePrefs.vietnameseModernTone = (which == 1)
                syncUiFromPrefs()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showImportDictionaryLanguagePicker() {
        val enabledSpecs = remotePrefs.enabledLanguages.map { LanguageRegistry.getById(it) }
        val labels = enabledSpecs.map { "${it.displayName} [${it.dictKey}]" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
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
                val count = remotePrefs.importDictionary(dictKey, text)
                refreshLanguagesList()
                refreshRamMonitor()
                Toast.makeText(this, "Imported $count words for [$dictKey]", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkAndInstallUpdate(triggerButton: MaterialButton) {
        val currentVer = getAppVersionName()
        triggerButton.isEnabled = false
        triggerButton.text = "Checking..."

        Thread {
            val result = GitHubUpdater.checkLatestRelease(currentVer)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                triggerButton.isEnabled = true
                triggerButton.text = "Check for Updates"

                when (result) {
                    is GitHubUpdater.CheckResult.UpdateAvailable -> {
                        val rel = result.release
                        val asset = rel.apkAsset
                        val sizeText = if (asset != null && asset.sizeBytes > 0) {
                            String.format(Locale.US, "%.2f MB", asset.sizeBytes / (1024f * 1024f))
                        } else {
                            "Unknown size"
                        }
                        val notesPreview = rel.notes.ifBlank { "Bug fixes and improvements." }.take(600)
                        val msg = "New version ${rel.tagName} available (current: v$currentVer)\n" +
                            "Package: ${asset?.name ?: "No APK"} ($sizeText)\n\n$notesPreview"

                        val builder = MaterialAlertDialogBuilder(this)
                            .setTitle("Update Available: ${rel.title}")
                            .setMessage(msg)
                            .setNegativeButton("Later", null)
                            .setNeutralButton("Browser") { _, _ -> openGitHubReleases() }

                        if (asset != null) {
                            builder.setPositiveButton("Download & Install") { _, _ ->
                                startApkDownloadAndInstall(rel)
                            }
                        }
                        builder.show()
                    }
                    is GitHubUpdater.CheckResult.UpToDate -> {
                        MaterialAlertDialogBuilder(this)
                            .setTitle("You're up to date")
                            .setMessage("True Light v${result.currentVersion} is the latest release (GitHub latest: ${result.latestTag}).")
                            .setPositiveButton("OK", null)
                            .setNeutralButton("View Releases") { _, _ -> openGitHubReleases() }
                            .show()
                    }
                    is GitHubUpdater.CheckResult.Error -> {
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Update Check Failed")
                            .setMessage("${result.message}\n\nYou can still check GitHub Releases manually in your browser.")
                            .setPositiveButton("Open Browser") { _, _ -> openGitHubReleases() }
                            .setNegativeButton("Close", null)
                            .show()
                    }
                }
            }
        }.start()
    }

    private fun startApkDownloadAndInstall(release: GitHubUpdater.ReleaseInfo) {
        val progressBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(16), dp(22), dp(12))
        }
        val statusText = TextView(this).apply {
            text = "Downloading ${release.apkAsset?.name ?: "update"}..."
            textSize = 13.5f
            setTextColor(colOnSurface)
            setPadding(0, 0, 0, dp(10))
        }
        val progressBar = LinearProgressIndicator(this).apply {
            max = 100
            progress = 0
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        progressBox.addView(statusText)
        progressBox.addView(progressBar)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Downloading ${release.tagName}")
            .setView(progressBox)
            .setCancelable(false)
            .show()

        Thread {
            val dl = GitHubUpdater.downloadAndVerify(this, release) { read, total ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (total > 0) {
                        val pct = ((read * 100) / total).toInt().coerceIn(0, 100)
                        if (progressBar.isIndeterminate) progressBar.isIndeterminate = false
                        progressBar.progress = pct
                        statusText.text = String.format(
                            Locale.US,
                            "Downloading... %d%% (%.2f / %.2f MB)",
                            pct,
                            read / (1024f * 1024f),
                            total / (1024f * 1024f)
                        )
                    } else {
                        statusText.text = String.format(Locale.US, "Downloading... %.2f MB", read / (1024f * 1024f))
                    }
                }
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                dialog.dismiss()
                when (dl) {
                    is GitHubUpdater.DownloadResult.Ready -> {
                        val shaNote = if (dl.shaVerified) " (SHA-256 verified ✓)" else ""
                        val launched = GitHubUpdater.launchApkInstaller(this, dl.apkFile)
                        if (launched) {
                            Toast.makeText(this, "Installing ${release.tagName}$shaNote", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(
                                this,
                                "Allow 'Install unknown apps' for True Light, then tap Check for Updates again",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    is GitHubUpdater.DownloadResult.Error -> {
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Download Failed")
                            .setMessage(dl.message)
                            .setPositiveButton("Open Browser") { _, _ -> openGitHubReleases() }
                            .setNegativeButton("Close", null)
                            .show()
                    }
                }
            }
        }.start()
    }

    private fun openGitHubReleases() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(KeyboardPrefs.GITHUB_RELEASES_URL))
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "No browser found to open GitHub Releases", Toast.LENGTH_SHORT).show()
        }
    }
}
