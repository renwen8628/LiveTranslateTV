package com.livetranslate.tv

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Renders a [Subtitle] with [SubtitleSettings]: optional original line above the translation. */
class SubtitleOverlayView(context: Context) : LinearLayout(context) {

    private val originalView = createLine()
    private val translatedView = createLine()
    private val backgroundDrawable = GradientDrawable().apply { cornerRadius = dp(8f) }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = backgroundDrawable
        val h = dp(24f).toInt()
        val v = dp(10f).toInt()
        setPadding(h, v, h, v)
        addView(originalView)
        addView(translatedView)
        isFocusable = false
        isClickable = false
    }

    fun render(subtitle: Subtitle, settings: SubtitleSettings) {
        val showOriginal = settings.bilingual && !subtitle.originalText.isNullOrBlank()
        val hasTranslation = subtitle.translatedText.isNotBlank()

        if (subtitle.isEmpty || (!hasTranslation && !showOriginal)) {
            visibility = View.GONE
            return
        }
        visibility = View.VISIBLE

        // Max width keeps long lines wrapping instead of running edge to edge.
        val maxWidth = (resources.displayMetrics.widthPixels * 0.8f).toInt()

        originalView.apply {
            visibility = if (showOriginal) View.VISIBLE else View.GONE
            text = subtitle.originalText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.fontSizeSp * 0.75f)
            // Original line is slightly dimmed so the translation stands out.
            setTextColor(Color.argb(220, 230, 230, 230))
            this.maxWidth = maxWidth
        }
        translatedView.apply {
            visibility = if (hasTranslation) View.VISIBLE else View.GONE
            text = subtitle.translatedText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.fontSizeSp.toFloat())
            setTextColor(settings.textColor.argb)
            this.maxWidth = maxWidth
        }

        val alpha = (settings.backgroundOpacityPercent.coerceIn(0, 100) * 255) / 100
        backgroundDrawable.setColor(Color.argb(alpha, 0, 0, 0))
    }

    private fun createLine() = TextView(context).apply {
        gravity = Gravity.CENTER
        includeFontPadding = true
        // Drop shadow keeps text readable on bright scenes even with a transparent background.
        setShadowLayer(dp(3f), 0f, dp(1.5f), Color.BLACK)
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            .apply { gravity = Gravity.CENTER_HORIZONTAL }
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
}
