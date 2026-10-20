package com.livetranslate.tv

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Process-wide holder for the current subtitle and style settings.
 *
 * All state changes and listener callbacks happen on the main thread, so callers on
 * other threads (e.g. a future WebSocket client) can call the setters safely.
 */
object SubtitleManager {

    fun interface Listener {
        fun onSubtitleStateChanged(subtitle: Subtitle, settings: SubtitleSettings)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<Listener>()
    private var preferences: SubtitlePreferences? = null
    private val clearRunnable = Runnable { setSubtitle(Subtitle.EMPTY) }

    var subtitle: Subtitle = Subtitle.TEST
        private set
    var settings: SubtitleSettings = SubtitleSettings()
        private set

    /** Loads persisted settings once. Safe to call repeatedly. */
    fun init(context: Context) {
        if (preferences != null) return
        preferences = SubtitlePreferences(context).also { settings = it.load() }
    }

    fun addListener(listener: Listener) {
        runOnMain {
            if (listeners.add(listener)) listener.onSubtitleStateChanged(subtitle, settings)
        }
    }

    fun removeListener(listener: Listener) {
        runOnMain { listeners.remove(listener) }
    }

    fun setSubtitle(newSubtitle: Subtitle) {
        runOnMain {
            mainHandler.removeCallbacks(clearRunnable)
            val duration = newSubtitle.durationMs
            if (duration != null && duration > 0 && !newSubtitle.isEmpty) {
                mainHandler.postDelayed(clearRunnable, duration)
            }
            if (newSubtitle != subtitle) {
                subtitle = newSubtitle
                notifyListeners()
            }
        }
    }

    fun clearSubtitle() {
        setSubtitle(Subtitle.EMPTY)
    }

    /** Applies [transform] to the current settings, persists, and notifies if changed. */
    fun updateSettings(transform: (SubtitleSettings) -> SubtitleSettings) {
        runOnMain {
            val updated = transform(settings)
            if (updated != settings) {
                settings = updated
                preferences?.save(updated)
                notifyListeners()
            }
        }
    }

    private fun notifyListeners() {
        listeners.toList().forEach { it.onSubtitleStateChanged(subtitle, settings) }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }
}
