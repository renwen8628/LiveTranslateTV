package com.livetranslate.tv

import android.graphics.Color

/** One subtitle event. [originalText] is only shown when bilingual mode is on. */
data class Subtitle(
    val translatedText: String,
    val originalText: String? = null,
    /** Auto-clear after this many ms; null or <= 0 keeps the subtitle until replaced. */
    val durationMs: Long? = null,
) {
    val isEmpty: Boolean get() = translatedText.isBlank() && originalText.isNullOrBlank()

    companion object {
        val EMPTY = Subtitle("")
        val TEST = Subtitle(
            translatedText = "中文字幕测试",
            originalText = "AI Subtitle Test",
        )
    }
}

enum class SubtitleColor(val argb: Int, val label: String) {
    WHITE(Color.WHITE, "White"),
    YELLOW(Color.rgb(255, 235, 59), "Yellow"),
    CYAN(Color.rgb(0, 229, 255), "Cyan"),
    GREEN(Color.rgb(118, 255, 3), "Green"),
}

data class Language(val code: String, val label: String)

object Languages {
    val SOURCE = listOf(
        Language("en", "English"),
        Language("ja", "Japanese"),
        Language("ko", "Korean"),
        Language("es", "Spanish"),
        Language("fr", "French"),
        Language("de", "German"),
    )
    val TARGET = listOf(
        Language("zh-CN", "Chinese (Simplified)"),
        Language("zh-TW", "Chinese (Traditional)"),
        Language("en", "English"),
        Language("ja", "Japanese"),
        Language("ko", "Korean"),
        Language("es", "Spanish"),
    )
}

/** All user-adjustable subtitle settings. Defaults match the original proof of concept. */
data class SubtitleSettings(
    val enabled: Boolean = false,
    val fontSizeSp: Int = 28,
    val textColor: SubtitleColor = SubtitleColor.WHITE,
    /** 0..100 */
    val backgroundOpacityPercent: Int = 80,
    /** Distance from the bottom of the screen, as a percentage of screen height (0..80). */
    val verticalPositionPercent: Int = 10,
    val sourceLanguage: String = "en",
    val targetLanguage: String = "zh-CN",
    val bilingual: Boolean = true,
    /** ws:// or wss:// address of the subtitle backend. Never holds credentials. */
    val serverUrl: String = "",
    val serverEnabled: Boolean = false,
) {
    companion object {
        val FONT_SIZES = listOf(20, 24, 28, 32, 36, 42, 48)
        val OPACITIES = (0..100 step 10).toList()
        val POSITIONS = (0..80 step 5).toList()
    }
}
