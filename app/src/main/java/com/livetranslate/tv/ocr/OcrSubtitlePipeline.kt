package com.livetranslate.tv.ocr

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.livetranslate.tv.Subtitle
import com.livetranslate.tv.SubtitleManager
import com.livetranslate.tv.SubtitleRepository
import com.livetranslate.tv.remoteplay.RemotePlayController

/**
 * Remote Play frames -> OCR of the subtitle area -> stabilized subtitle lines -> overlay.
 *
 * When the translation backend is connected, each line is sent to it and the English is
 * shown until the translation arrives (via SubtitleRepository). Without a backend, the
 * recognized English is shown on its own.
 *
 * Main thread only.
 */
object OcrSubtitlePipeline {

    private const val TAG = "SubtitleOcr"
    private const val FRAME_INTERVAL_MS = 500L

    data class Stats(
        val readings: Int = 0,
        val skippedBusy: Int = 0,
        val avgLatencyMs: Long = 0,
        val linesShown: Int = 0,
        val lastRaw: String? = null,
    )

    /** Called for each new or updated subtitle line (id stays the same for updates). */
    var lineListener: ((id: Long, text: String, isUpdate: Boolean) -> Unit)? = null

    var region = OcrRegion()
    var stats = Stats()
        private set
    val isRunning get() = ocr != null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var ocr: SubtitleOcr? = null
    // Show on the first reading: in fast games (tested: Call of Duty) many lines are on screen
    // for only 1-2 s, i.e. a single OCR reading, and requiring two readings dropped ~60% of them.
    // Repeated readings of the same line still merge into that line.
    private val stabilizer = SubtitleTextStabilizer(stableSamples = 1)
    private var totalLatencyMs = 0L
    /** Sequence numbers for translation requests; one per new or updated line. */
    private var requestSequence = 0L

    fun start() {
        if (ocr != null) return
        // 75% scale: measured fastest on the TCL TV (~0.7 s) with no accuracy loss for 24-36 px text.
        val engine = SubtitleOcr(scale = 0.75f)
        ocr = engine
        stabilizer.reset()
        stats = Stats()
        totalLatencyMs = 0
        RemotePlayController.frameIntervalMs = FRAME_INTERVAL_MS
        RemotePlayController.frameListener = { frame ->
            // Frame thread. The OCR result callback arrives on the main thread.
            val accepted = engine.process(frame, region) { reading ->
                if (ocr === engine) onReading(reading)
            }
            if (!accepted) mainHandler.post { stats = stats.copy(skippedBusy = stats.skippedBusy + 1) }
        }
        Log.i(TAG, "OCR started, region=$region")
    }

    fun stop() {
        val engine = ocr ?: return
        ocr = null
        RemotePlayController.frameListener = null
        engine.close()
        stabilizer.reset()
        Log.i(TAG, "OCR stopped. $stats")
    }

    private fun onReading(reading: OcrReading) {
        totalLatencyMs += reading.latencyMs
        val n = stats.readings + 1
        stats = stats.copy(readings = n, avgLatencyMs = totalLatencyMs / n, lastRaw = reading.subtitle)
        if (reading.lines.isNotEmpty()) {
            Log.d(TAG, "${reading.latencyMs}ms subtitle=${reading.subtitle} | all=" +
                reading.lines.joinToString(" / ") { "${it.text}(h=${it.height},c=${"%.2f".format(it.confidence)})" })
        }

        when (val event = stabilizer.onSample(reading.subtitle)) {
            is SubtitleTextStabilizer.Event.Show -> {
                stats = stats.copy(linesShown = stats.linesShown + 1)
                Log.i(TAG, "Line #${event.id}: ${event.text}")
                show(event.text)
                lineListener?.invoke(event.id, event.text, false)
            }
            is SubtitleTextStabilizer.Event.Update -> {
                Log.i(TAG, "Line #${event.id} updated: ${event.text}")
                show(event.text)
                lineListener?.invoke(event.id, event.text, true)
            }
            is SubtitleTextStabilizer.Event.Clear -> {
                Log.i(TAG, "Line #${event.id} cleared")
                SubtitleManager.clearSubtitle()
                SubtitleRepository.noteLocalClear()
            }
            null -> Unit
        }
    }

    private fun show(text: String) {
        if (SubtitleRepository.isConnected) {
            val sequence = ++requestSequence
            SubtitleRepository.noteLocalLine(sequence)
            SubtitleRepository.sendSource(sequence, text)
            // English (as the original line) until the translation replaces it.
            SubtitleManager.setSubtitle(Subtitle(translatedText = "", originalText = text))
        } else {
            SubtitleManager.setSubtitle(Subtitle(translatedText = text, originalText = null))
        }
    }
}
