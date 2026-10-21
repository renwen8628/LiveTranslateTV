package com.livetranslate.tv

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.WebSocket
import org.json.JSONObject

/**
 * Turns backend subtitle events into on-screen subtitles.
 *
 * - Captions with an older sequence than the newest seen are dropped as outdated.
 * - Partial captions for a sequence are replaced by newer partials, then by the final.
 * - Once a sequence is final, later messages for it are ignored.
 * - Sequence tracking resets on every new connection, so a restarted server works.
 *
 * Main-thread only.
 */
object SubtitleRepository {

    data class Stats(val received: Int = 0, val shown: Int = 0, val droppedOutdated: Int = 0)

    fun interface Listener {
        fun onConnectionChanged(status: ConnectionStatus, stats: Stats)
    }

    /** Partials are cleared if no update arrives in this window (e.g. stream stalled). */
    private const val PARTIAL_TIMEOUT_MS = 10_000L
    private const val TAG = "SubtitleRepository"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<Listener>()

    var status: ConnectionStatus = ConnectionStatus.Disabled
        private set
    var stats = Stats()
        private set

    private var lastSequence = -1L
    private var lastSequenceFinal = false
    /** Lines up to this sequence already left the screen; their late translations show briefly. */
    private var clearedUpTo = -1L
    private const val LATE_TRANSLATION_MS = 3_000L
    private var socket: WebSocket? = null
    private var sentLanguages: Pair<String, String>? = null
    private var languages = "en" to "zh-CN"

    private val client by lazy {
        SubtitleWebSocketClient(
            onStatus = ::setStatus,
            onOpen = { ws ->
                socket = ws
                lastSequence = -1L
                lastSequenceFinal = false
                clearedUpTo = -1L
                sentLanguages = null
                sendHello()
            },
            onText = ::handleText,
        )
    }

    fun addListener(listener: Listener) {
        if (listeners.add(listener)) listener.onConnectionChanged(status, stats)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /**
     * Brings the connection in line with [settings]. Safe to call on every settings
     * change: it only reconnects when the URL or enabled flag actually changes.
     */
    fun sync(settings: SubtitleSettings) {
        languages = settings.sourceLanguage to settings.targetLanguage
        val url = normalizeUrl(settings.serverUrl)
        when {
            !settings.serverEnabled -> disconnect(ConnectionStatus.Disabled)
            url == null -> disconnect(ConnectionStatus.NotConfigured)
            else -> {
                client.connect(url, settings.serverToken)
                sendHello() // no-op unless connected and languages changed
            }
        }
    }

    fun disconnect(finalStatus: ConnectionStatus = ConnectionStatus.Disabled) {
        if (status == finalStatus && socket == null) return
        client.stop()
        socket = null
        setStatus(finalStatus)
    }

    val isConnected get() = socket != null && status is ConnectionStatus.Connected

    /**
     * Sends a subtitle line read on this TV (OCR) to the backend for translation.
     * The reply comes back as a normal "subtitle" message with the same [sequence].
     */
    fun sendSource(sequence: Long, text: String): Boolean {
        val ws = socket ?: return false
        val msg = JSONObject()
            .put("type", "source")
            .put("sequence", sequence)
            .put("text", text)
        return ws.send(msg.toString())
    }

    /** A line with this sequence is now on screen locally; replies for older lines are outdated. */
    fun noteLocalLine(sequence: Long) {
        if (sequence > lastSequence) {
            lastSequence = sequence
            lastSequenceFinal = false
        }
    }

    /** The current local line left the screen. */
    fun noteLocalClear() {
        clearedUpTo = lastSequence
    }

    /** Accepts ws:// or wss:// URLs; returns null if unusable. */
    fun normalizeUrl(raw: String): String? {
        val url = raw.trim()
        val httpEquivalent = when {
            url.startsWith("ws://", ignoreCase = true) -> "http://" + url.substring(5)
            url.startsWith("wss://", ignoreCase = true) -> "https://" + url.substring(6)
            else -> return null
        }
        return if (httpEquivalent.toHttpUrlOrNull() != null) url else null
    }

    /** Tells the backend which languages we want, so it can be shared across TVs later. */
    private fun sendHello() {
        val ws = socket ?: return
        if (sentLanguages == languages) return
        val hello = JSONObject()
            .put("type", "hello")
            .put("client", "livetranslate-tv")
            .put("protocolVersion", 1)
            .put("sourceLanguage", languages.first)
            .put("targetLanguage", languages.second)
        if (ws.send(hello.toString())) sentLanguages = languages
    }

    private fun handleText(text: String) {
        stats = stats.copy(received = stats.received + 1)
        when (val message = SubtitleMessage.parse(text)) {
            is SubtitleMessage.Caption -> handleCaption(message)
            is SubtitleMessage.Clear -> {
                if (message.sequence == null || message.sequence >= lastSequence) {
                    SubtitleManager.clearSubtitle()
                }
            }
            null -> Log.w(TAG, "Ignoring unrecognised message: ${text.take(200)}")
        }
        notifyListeners()
    }

    private fun handleCaption(c: SubtitleMessage.Caption) {
        val outdated = c.sequence < lastSequence ||
            (c.sequence == lastSequence && lastSequenceFinal)
        if (outdated) {
            stats = stats.copy(droppedOutdated = stats.droppedOutdated + 1)
            Log.d(TAG, "Ignored outdated #${c.sequence} (newest #$lastSequence, final=$lastSequenceFinal)")
            return
        }
        lastSequence = c.sequence
        lastSequenceFinal = c.isFinal

        val duration = when {
            !c.isFinal -> PARTIAL_TIMEOUT_MS
            // The line already disappeared from the game screen: show its translation briefly.
            c.sequence <= clearedUpTo -> LATE_TRANSLATION_MS
            else -> c.durationMs
        }
        // SubtitleManager skips the redraw when the text is unchanged.
        SubtitleManager.setSubtitle(Subtitle(c.translatedText, c.originalText, duration))
        stats = stats.copy(shown = stats.shown + 1)
    }

    private fun setStatus(newStatus: ConnectionStatus) {
        if (newStatus is ConnectionStatus.Connected) stats = Stats()
        if (newStatus !is ConnectionStatus.Connected) socket = null
        if (newStatus == status) return
        status = newStatus
        Log.i(TAG, newStatus.describe())
        notifyListeners()
    }

    private fun notifyListeners() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::notifyListeners)
            return
        }
        listeners.toList().forEach { it.onConnectionChanged(status, stats) }
    }
}
