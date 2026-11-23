package com.truelight.ime.settings

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors

/**
 * Material Design 3 "What's New" Changelog Bottom Sheet.
 * Runs strictly in the isolated `:settings` process (adds 0 bytes to `TrueLightIME`).
 */
object ChangelogSheet {

    fun show(context: Context, currentVersion: String) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val isNight = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        val colSurface = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorSurface,
            if (isNight) 0xFF111318.toInt() else 0xFFF8F9FC.toInt()
        )
        val colSurfaceLow = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorSurfaceContainerLow,
            if (isNight) 0xFF191C22.toInt() else 0xFFFFFFFF.toInt()
        )
        val colSurfaceContainer = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorSurfaceContainer,
            if (isNight) 0xFF1F2229.toInt() else 0xFFF0F3F8.toInt()
        )
        val colOnSurface = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorOnSurface,
            if (isNight) 0xFFE4E7EE.toInt() else 0xFF191C20.toInt()
        )
        val colOnSurfaceVariant = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            if (isNight) 0xFFC2C7D0.toInt() else 0xFF44474F.toInt()
        )
        val colPrimary = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorPrimary,
            if (isNight) 0xFFA8C7FA.toInt() else 0xFF1A66E8.toInt()
        )
        val colOnPrimary = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorOnPrimary,
            if (isNight) 0xFF062E6F.toInt() else 0xFFFFFFFF.toInt()
        )
        val colPrimaryContainer = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorPrimaryContainer,
            if (isNight) 0xFF0842A0.toInt() else 0xFFD8E6FF.toInt()
        )
        val colOnPrimaryContainer = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorOnPrimaryContainer,
            if (isNight) 0xFFD3E3FD.toInt() else 0xFF041E49.toInt()
        )
        val colOutlineVariant = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorOutlineVariant,
            if (isNight) 0xFF3A3F4A.toInt() else 0xFFD3D8E0.toInt()
        )

        // Harmonious semantic pill badge colors for Light & Dark modes
        val colImprovedBg = if (isNight) 0xFF1E3246.toInt() else 0xFFE3F2FD.toInt()
        val colImprovedText = if (isNight) 0xFF90CAF9.toInt() else 0xFF0D47A1.toInt()
        val colFixedBg = if (isNight) 0xFF3E2C14.toInt() else 0xFFFEF2DF.toInt()
        val colFixedText = if (isNight) 0xFFFFCC80.toInt() else 0xFFA65400.toInt()
        val colPerfBg = if (isNight) 0xFF1A3A2A.toInt() else 0xFFE3F5E9.toInt()
        val colPerfText = if (isNight) 0xFF81C784.toInt() else 0xFF137333.toInt()

        fun pillBg(bg: Int, radiusDp: Int = 100, stroke: Int = Color.TRANSPARENT): GradientDrawable {
            return GradientDrawable().apply {
                setColor(bg)
                cornerRadius = dp(radiusDp).toFloat()
                if (stroke != Color.TRANSPARENT) {
                    setStroke(dp(1), stroke)
                }
            }
        }

        val dialog = BottomSheetDialog(context)

        val sheetRoot = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(colSurface)
                val r = dp(26).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Top M3 drag handle
        val handleWrap = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(6))
        }
        handleWrap.addView(View(context).apply {
            background = pillBg(colOutlineVariant, 100)
            alpha = 0.85f
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(4))
        })
        sheetRoot.addView(handleWrap)

        // Header: "What's New" + version pill + circular close button
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(6), dp(16), dp(14))
        }
        header.addView(TextView(context).apply {
            text = "What's New"
            textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = -0.015f
            setTextColor(colOnSurface)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(10)
            }
        })
        header.addView(TextView(context).apply {
            text = "v$currentVersion"
            textSize = 11.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(colOnPrimaryContainer)
            background = pillBg(colPrimaryContainer, 100)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        })
        header.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        header.addView(TextView(context).apply {
            text = "✕"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(colOnSurfaceVariant)
            background = pillBg(colSurfaceContainer, 100)
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
            setOnClickListener { dialog.dismiss() }
        })
        sheetRoot.addView(header)

        // Subtle header divider
        sheetRoot.addView(View(context).apply {
            setBackgroundColor(colOutlineVariant)
            alpha = 0.45f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        })

        // Scrollable release cards
        val maxScrollH = (context.resources.displayMetrics.heightPixels * 0.72f).toInt()
        val scroll = NestedScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val listCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(26))
        }

        val releases = ChangelogData.RELEASES
        for (idx in releases.indices) {
            val rel = releases[idx]
            val isLatest = (idx == 0)

            val card = MaterialCardView(context).apply {
                radius = dp(20).toFloat()
                cardElevation = 0f
                strokeWidth = if (isLatest) dp(2) else dp(1)
                strokeColor = if (isLatest) colPrimary else colOutlineVariant
                setCardBackgroundColor(colSurfaceLow)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = if (idx < releases.lastIndex) dp(14) else 0
                }
            }

            val body = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(16), dp(18), dp(16))
            }

            // Version + Latest badge + Date row
            val metaRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(4)
                }
            }
            metaRow.addView(TextView(context).apply {
                text = "v${rel.version}"
                textSize = 17f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(colOnSurface)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = dp(8)
                }
            })
            if (!rel.tag.isNullOrEmpty()) {
                metaRow.addView(TextView(context).apply {
                    text = rel.tag
                    textSize = 11f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setTextColor(colOnPrimary)
                    background = pillBg(colPrimary, 100)
                    setPadding(dp(9), dp(3), dp(9), dp(3))
                })
            }
            metaRow.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })
            metaRow.addView(TextView(context).apply {
                text = rel.date
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextColor(colOnSurfaceVariant)
            })
            body.addView(metaRow)

            // 1-line release headline
            body.addView(TextView(context).apply {
                text = rel.headline
                textSize = 13.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(if (isLatest) colPrimary else colOnSurfaceVariant)
                setPadding(0, 0, 0, dp(12))
            })

            // Structured change items with crisp M3 tinted mini-badges
            for (cIdx in rel.changes.indices) {
                val item = rel.changes[cIdx]
                val itemRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.TOP
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        if (cIdx < rel.changes.lastIndex) bottomMargin = dp(9)
                    }
                }

                val (badgeBg, badgeFg) = when (item.type) {
                    ChangelogData.ChangeType.NEW -> colPrimaryContainer to colOnPrimaryContainer
                    ChangelogData.ChangeType.IMPROVED -> colImprovedBg to colImprovedText
                    ChangelogData.ChangeType.FIXED -> colFixedBg to colFixedText
                    ChangelogData.ChangeType.PERFORMANCE -> colPerfBg to colPerfText
                }

                itemRow.addView(TextView(context).apply {
                    text = item.type.badgeLabel
                    textSize = 9.5f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    letterSpacing = 0.05f
                    gravity = Gravity.CENTER
                    setTextColor(badgeFg)
                    background = pillBg(badgeBg, 6)
                    setPadding(dp(6), dp(3), dp(6), dp(3))
                    minWidth = dp(58)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dp(2)
                        marginEnd = dp(10)
                    }
                })

                itemRow.addView(TextView(context).apply {
                    text = item.text
                    textSize = 13.5f
                    setLineSpacing(0f, 1.25f)
                    setTextColor(colOnSurface)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })

                body.addView(itemRow)
            }

            card.addView(body)
            listCol.addView(card)
        }

        scroll.addView(listCol)
        sheetRoot.addView(scroll)

        dialog.setContentView(sheetRoot)
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                bottomSheet.setBackgroundColor(Color.TRANSPARENT)
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.peekHeight = maxScrollH
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        dialog.show()
    }
}
