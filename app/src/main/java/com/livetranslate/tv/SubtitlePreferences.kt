package com.livetranslate.tv

import android.content.Context

/** Persists [SubtitleSettings] in SharedPreferences. */
class SubtitlePreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("subtitle_settings", Context.MODE_PRIVATE)

    fun load(): SubtitleSettings {
        val d = SubtitleSettings()
        return SubtitleSettings(
            enabled = prefs.getBoolean(KEY_ENABLED, d.enabled),
            fontSizeSp = prefs.getInt(KEY_FONT_SIZE, d.fontSizeSp),
            textColor = prefs.getString(KEY_TEXT_COLOR, null)
                ?.let { name -> SubtitleColor.entries.firstOrNull { it.name == name } }
                ?: d.textColor,
            backgroundOpacityPercent = prefs.getInt(KEY_BG_OPACITY, d.backgroundOpacityPercent)
                .coerceIn(0, 100),
            verticalPositionPercent = prefs.getInt(KEY_POSITION, d.verticalPositionPercent)
                .coerceIn(0, 80),
            sourceLanguage = prefs.getString(KEY_SOURCE_LANG, d.sourceLanguage) ?: d.sourceLanguage,
            targetLanguage = prefs.getString(KEY_TARGET_LANG, d.targetLanguage) ?: d.targetLanguage,
            bilingual = prefs.getBoolean(KEY_BILINGUAL, d.bilingual),
        )
    }

    fun save(s: SubtitleSettings) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, s.enabled)
            .putInt(KEY_FONT_SIZE, s.fontSizeSp)
            .putString(KEY_TEXT_COLOR, s.textColor.name)
            .putInt(KEY_BG_OPACITY, s.backgroundOpacityPercent)
            .putInt(KEY_POSITION, s.verticalPositionPercent)
            .putString(KEY_SOURCE_LANG, s.sourceLanguage)
            .putString(KEY_TARGET_LANG, s.targetLanguage)
            .putBoolean(KEY_BILINGUAL, s.bilingual)
            .apply()
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_FONT_SIZE = "font_size_sp"
        const val KEY_TEXT_COLOR = "text_color"
        const val KEY_BG_OPACITY = "bg_opacity_percent"
        const val KEY_POSITION = "vertical_position_percent"
        const val KEY_SOURCE_LANG = "source_language"
        const val KEY_TARGET_LANG = "target_language"
        const val KEY_BILINGUAL = "bilingual"
    }
}
