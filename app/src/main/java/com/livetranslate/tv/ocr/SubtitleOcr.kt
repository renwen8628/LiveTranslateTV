package com.livetranslate.tv.ocr

import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.livetranslate.tv.remoteplay.LumaFrame
import java.util.concurrent.atomic.AtomicBoolean

/** Part of the frame to read subtitles from, as fractions of width/height. */
data class OcrRegion(
    val left: Float = 0.05f,
    val top: Float = 0.60f,
    val right: Float = 0.95f,
    val bottom: Float = 0.98f,
)

/** One recognized text line, in pixels of the cropped region. */
data class OcrLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val confidence: Float,
) {
    val height get() = bottom - top
}

/** Result for one frame. [subtitle] is the text chosen as the subtitle, or null if none. */
data class OcrReading(
    val subtitle: String?,
    val lines: List<OcrLine>,
    val latencyMs: Long,
)

/**
 * On-device text recognition (ML Kit, Latin script) for the subtitle area of a frame.
 * Processes one frame at a time; frames that arrive while busy are skipped.
 */
class SubtitleOcr(
    /** Lines shorter than this (pixels at 720p) are too small to be subtitles. Game subtitles can be ~12 px. */
    private val minLineHeightPx: Int = 9,
    private val minConfidence: Float = 0.4f,
    /** Downscale the crop before OCR (1.0 = none). Smaller is faster but misses small text. */
    private val scale: Float = 1.0f,
) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val busy = AtomicBoolean(false)

    /** Returns false if the previous frame is still being processed (frame skipped). */
    fun process(frame: LumaFrame, region: OcrRegion, onResult: (OcrReading) -> Unit): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        val started = SystemClock.elapsedRealtime()
        val image = try {
            cropToNv21(frame, region)
        } catch (e: Exception) {
            busy.set(false)
            throw e
        }
        recognizer.process(image)
            .addOnSuccessListener { text ->
                // Report boxes in unscaled pixels so the height filter means the same at any scale.
                val k = 1f / scale
                val lines = text.textBlocks.flatMap { it.lines }.map { line ->
                    val box = line.boundingBox
                    OcrLine(
                        text = line.text,
                        left = ((box?.left ?: 0) * k).toInt(),
                        top = ((box?.top ?: 0) * k).toInt(),
                        right = ((box?.right ?: 0) * k).toInt(),
                        bottom = ((box?.bottom ?: 0) * k).toInt(),
                        confidence = line.confidence,
                    )
                }
                onResult(OcrReading(pickSubtitle(lines), lines, SystemClock.elapsedRealtime() - started))
            }
            .addOnFailureListener {
                onResult(OcrReading(null, emptyList(), SystemClock.elapsedRealtime() - started))
            }
            .addOnCompleteListener { busy.set(false) }
        return true
    }

    fun close() = recognizer.close()

    /**
     * Chooses the subtitle from all recognized lines: drop small/low-confidence/non-word
     * lines, then take the lowest group of lines that sit close together (subtitles are
     * at the bottom; other UI text is usually separated from them).
     */
    internal fun pickSubtitle(lines: List<OcrLine>): String? {
        val candidates = lines
            .filter { it.height >= minLineHeightPx && it.confidence >= minConfidence }
            .filter { line -> line.text.count { it.isLetter() } >= 2 }
            .filter { isDialogueLike(it.text) }
            .sortedBy { it.top }
        if (candidates.isEmpty()) return null

        val group = ArrayDeque<OcrLine>()
        group.addFirst(candidates.last())
        for (line in candidates.dropLast(1).asReversed()) {
            val groupTop = group.first().top
            val gap = groupTop - line.bottom
            val typicalHeight = group.map { it.height }.average()
            if (gap <= typicalHeight * 1.2 && line.height >= typicalHeight * 0.6) group.addFirst(line) else break
        }
        return group.joinToString(" ") { it.text.trim() }
    }

    /**
     * Button hints and menu labels ("SELECT CLOSE", "QUIT MISSION") are short and all caps;
     * dialogue has lowercase letters or is at least a few words long.
     */
    internal fun isDialogueLike(text: String): Boolean {
        // A ratio, not "any lowercase": OCR sometimes reads "CLOSE" as "cLOSE".
        val letters = text.count { it.isLetter() }
        val mostlyLowercase = letters > 0 && text.count { it.isLowerCase() } >= letters * 0.3
        val words = text.split(Regex("\\s+")).count { w -> w.count { it.isLetter() } >= 2 }
        return mostlyLowercase || words >= 4
    }

    private fun cropToNv21(frame: LumaFrame, region: OcrRegion): InputImage {
        // NV21 needs even dimensions; chroma is filled with neutral grey.
        var x0 = (frame.width * region.left).toInt().coerceIn(0, frame.width - 2) and 1.inv()
        var y0 = (frame.height * region.top).toInt().coerceIn(0, frame.height - 2) and 1.inv()
        val x1 = (frame.width * region.right).toInt().coerceIn(x0 + 2, frame.width)
        val y1 = (frame.height * region.bottom).toInt().coerceIn(y0 + 2, frame.height)
        val srcW = x1 - x0
        val srcH = y1 - y0
        val w = ((srcW * scale).toInt()) and 1.inv()
        val h = ((srcH * scale).toInt()) and 1.inv()
        val nv21 = ByteArray(w * h * 3 / 2)
        if (scale == 1.0f) {
            for (row in 0 until h) {
                System.arraycopy(frame.pixels, (y0 + row) * frame.width + x0, nv21, row * w, w)
            }
        } else {
            // Area-average downscale (keeps thin text strokes better than skipping pixels).
            val fx = srcW.toFloat() / w
            val fy = srcH.toFloat() / h
            for (row in 0 until h) {
                val sy0 = y0 + (row * fy).toInt()
                val sy1 = (y0 + ((row + 1) * fy).toInt()).coerceAtMost(y1).coerceAtLeast(sy0 + 1)
                for (col in 0 until w) {
                    val sx0 = x0 + (col * fx).toInt()
                    val sx1 = (x0 + ((col + 1) * fx).toInt()).coerceAtMost(x1).coerceAtLeast(sx0 + 1)
                    var sum = 0
                    for (sy in sy0 until sy1) {
                        val base = sy * frame.width
                        for (sx in sx0 until sx1) sum += frame.pixels[base + sx].toInt() and 0xFF
                    }
                    nv21[row * w + col] = (sum / ((sy1 - sy0) * (sx1 - sx0))).toByte()
                }
            }
        }
        nv21.fill(128.toByte(), w * h, nv21.size)
        return InputImage.fromByteArray(nv21, w, h, 0, InputImage.IMAGE_FORMAT_NV21)
    }
}
