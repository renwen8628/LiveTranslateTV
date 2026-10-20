package com.livetranslate.tv.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.util.Log
import com.livetranslate.tv.remoteplay.LumaFrame
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Debug tool: draws a game-style subtitle (white text, black outline) over a busy background
 * into a 1280x720 frame and measures OCR speed and accuracy at several downscale factors.
 * Results go to logcat (tag "OcrBench"). Run off the main thread.
 */
object OcrBenchmark {
    private const val TAG = "OcrBench"
    private val SAMPLES = listOf(
        "Hold the line, reinforcements are on the way.",
        "I've never seen anything like this before. Stay close.",
        "Check the map, the exit should be to the north.",
    )

    fun run(textSizesPx: List<Float> = listOf(24f, 30f, 36f), scales: List<Float> = listOf(1.0f, 0.75f, 0.5f), repeats: Int = 3) {
        for (size in textSizesPx) {
            val text = SAMPLES[(size.toInt() / 6) % SAMPLES.size]
            val frame = render(text, size)
            for (scale in scales) {
                val ocr = SubtitleOcr(scale = scale)
                val latencies = mutableListOf<Long>()
                var lastText: String? = null
                repeat(repeats) {
                    val latch = CountDownLatch(1)
                    ocr.process(frame, OcrRegion()) { reading ->
                        latencies += reading.latencyMs
                        lastText = reading.subtitle
                        latch.countDown()
                    }
                    latch.await(20, TimeUnit.SECONDS)
                }
                ocr.close()
                val sim = SubtitleTextStabilizer.similarity(
                    SubtitleTextStabilizer.normalize(text), SubtitleTextStabilizer.normalize(lastText.orEmpty()),
                )
                // First run includes model warm-up; report the rest.
                val steady = latencies.drop(1).ifEmpty { latencies }
                Log.i(TAG, "text=${size.toInt()}px scale=$scale latency=${steady.average().toInt()}ms " +
                    "(first ${latencies.firstOrNull()}ms) accuracy=${"%.2f".format(sim)} read=\"$lastText\"")
            }
        }
        Log.i(TAG, "done")
    }

    private fun render(text: String, sizePx: Float): LumaFrame {
        val w = 1280
        val h = 720
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // Busy background: gradient plus random blocks, like a game scene.
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Color.rgb(40, 70, 90), Color.rgb(200, 180, 150), Shader.TileMode.CLAMP)
        })
        val rnd = Random(42)
        val block = Paint()
        repeat(300) {
            block.color = Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
            val x = rnd.nextInt(w).toFloat(); val y = rnd.nextInt(h).toFloat()
            c.drawRect(x, y, x + rnd.nextInt(10, 80), y + rnd.nextInt(10, 80), block)
        }
        // HUD-like small text that should be ignored.
        c.drawText("HP 120/120   AMMO 30", 40f, 50f, Paint().apply { color = Color.WHITE; textSize = 16f; isAntiAlias = true })
        val stroke = Paint().apply {
            color = Color.BLACK; textSize = sizePx; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD
            style = Paint.Style.STROKE; strokeWidth = sizePx / 8; textAlign = Paint.Align.CENTER
        }
        val fill = Paint(stroke).apply { color = Color.WHITE; style = Paint.Style.FILL }
        val y = h * 0.88f
        c.drawText(text, w / 2f, y, stroke)
        c.drawText(text, w / 2f, y, fill)

        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        val luma = ByteArray(w * h) { i ->
            val p = px[i]
            ((((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8).toByte()
        }
        return LumaFrame(w, h, luma, 0)
    }
}
