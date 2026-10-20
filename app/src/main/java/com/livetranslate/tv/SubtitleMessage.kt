package com.livetranslate.tv

import org.json.JSONException
import org.json.JSONObject

/** Events received from the subtitle backend. */
sealed class SubtitleMessage {

    /**
     * A subtitle for one utterance. The backend may send several partial (`isFinal = false`)
     * updates with the same [sequence] before the final one.
     */
    data class Caption(
        val sequence: Long,
        val sourceLanguage: String?,
        val targetLanguage: String?,
        val originalText: String?,
        val translatedText: String,
        val isFinal: Boolean,
        val durationMs: Long?,
    ) : SubtitleMessage()

    /** Remove whatever is on screen (e.g. a long silence). */
    data class Clear(val sequence: Long?) : SubtitleMessage()

    companion object {
        /** Returns null for malformed or unknown messages; the caller just ignores them. */
        fun parse(json: String): SubtitleMessage? = try {
            val obj = JSONObject(json)
            when (obj.optString("type")) {
                "subtitle" -> parseCaption(obj)
                "clear" -> Clear(obj.optLongOrNull("sequence"))
                else -> null
            }
        } catch (e: JSONException) {
            null
        }

        private fun parseCaption(obj: JSONObject): Caption? {
            val sequence = obj.optLongOrNull("sequence") ?: return null
            val translated = obj.optStringOrNull("translatedText").orEmpty()
            val original = obj.optStringOrNull("originalText")
            if (translated.isBlank() && original.isNullOrBlank()) return null
            return Caption(
                sequence = sequence,
                sourceLanguage = obj.optStringOrNull("sourceLanguage"),
                targetLanguage = obj.optStringOrNull("targetLanguage"),
                originalText = original,
                translatedText = translated,
                isFinal = obj.optBoolean("isFinal", true),
                durationMs = obj.optLongOrNull("durationMs")?.takeIf { it > 0 },
            )
        }

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) optString(key) else null

        private fun JSONObject.optLongOrNull(key: String): Long? =
            if (has(key) && !isNull(key)) {
                val value = opt(key)
                (value as? Number)?.toLong() ?: value?.toString()?.toLongOrNull()
            } else {
                null
            }
    }
}

/** Connection state shown in the settings screen. */
sealed class ConnectionStatus {
    data object Disabled : ConnectionStatus()
    data object NotConfigured : ConnectionStatus()
    data class Connecting(val url: String) : ConnectionStatus()
    data class Connected(val url: String) : ConnectionStatus()
    data class WaitingToReconnect(val attempt: Int, val delayMs: Long, val reason: String) :
        ConnectionStatus()

    fun describe(): String = when (this) {
        Disabled -> "Server connection off"
        NotConfigured -> "No server URL set"
        is Connecting -> "Connecting to $url…"
        is Connected -> "Connected to $url"
        is WaitingToReconnect ->
            "Disconnected ($reason). Retry #$attempt in ${(delayMs + 999) / 1000}s"
    }
}
