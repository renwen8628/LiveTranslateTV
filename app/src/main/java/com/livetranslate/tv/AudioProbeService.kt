package com.livetranslate.tv

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Milestone 4 feasibility probe: measures what audio a normal (non-system) app can capture
 * on this TV through public APIs only.
 *
 * For each capture path it records a segment with nothing extra playing, then a segment
 * while the app itself plays a 1 kHz tone (a positive control showing whether the path can
 * hear anything at all). Levels go to logcat (tag "AudioProbe") and to the overlay; raw
 * audio is saved as WAV files under files/audio_probe/ for later inspection.
 *
 * Start it from MainActivity (the app must be visible to start microphone or media
 * projection foreground services), then switch to the HDMI input during the start delay.
 */
class AudioProbeService : Service() {

    private var worker: Thread? = null
    private var projection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (worker != null) {
            Log.w(TAG, "Probe already running")
            return START_NOT_STICKY
        }
        val delaySeconds = intent?.getIntExtra(EXTRA_DELAY_SECONDS, 15) ?: 15
        Log.i(TAG, "onStartCommand ${intent?.action} delay=${delaySeconds}s")
        when (intent?.action) {
            ACTION_MIC -> {
                if (!startAsForeground(fgsTypeMicrophone())) return stop()
                runInBackground { runMicProbe(delaySeconds) }
            }

            ACTION_PLAYBACK -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || data == null) {
                    report("Playback capture needs Android 10+ and screen-capture consent")
                    return stop()
                }
                // Android 14: the mediaProjection FGS must be running before getMediaProjection().
                if (!startAsForeground(fgsTypeMediaProjection())) return stop()
                val mpm = getSystemService(MediaProjectionManager::class.java)
                projection = try {
                    mpm.getMediaProjection(resultCode, data)?.also {
                        it.registerCallback(object : MediaProjection.Callback() {}, Handler(Looper.getMainLooper()))
                    }
                } catch (e: Exception) {
                    report("getMediaProjection failed: ${e.message}")
                    null
                }
                val mp = projection ?: return stop()
                runInBackground { runPlaybackProbe(mp, delaySeconds) }
            }

            else -> return stop()
        }
        return START_NOT_STICKY
    }

    private fun stop(): Int {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        worker?.interrupt()
        projection?.stop()
        super.onDestroy()
    }

    // --- Probes ------------------------------------------------------------------------

    private fun runMicProbe(delaySeconds: Int) {
        val summary = mutableListOf("MIC PROBE ${Build.MODEL} / Android ${Build.VERSION.RELEASE}")
        listInputDevices(summary)
        countdown(delaySeconds, "Mic test")
        val sources = listOf(
            MediaRecorder.AudioSource.MIC to "MIC",
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION",
            MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED",
            MediaRecorder.AudioSource.CAMCORDER to "CAMCORDER",
            MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION",
        )
        for ((source, name) in sources) {
            val record = try {
                @Suppress("MissingPermission")
                AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, ENCODING, bufferSize())
            } catch (e: Exception) {
                summary += "$name: cannot create AudioRecord (${e.javaClass.simpleName}: ${e.message})"
                continue
            }
            measure(record, "mic_$name", name, summary)
        }
        finish(summary)
    }

    private fun runPlaybackProbe(mp: MediaProjection, delaySeconds: Int) {
        val summary = mutableListOf("PLAYBACK CAPTURE PROBE ${Build.MODEL} / Android ${Build.VERSION.RELEASE}")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return finish(summary)
        countdown(delaySeconds, "Playback capture test")
        val record = try {
            val config = AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            @Suppress("MissingPermission")
            AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(ENCODING)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize())
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } catch (e: Exception) {
            summary += "PLAYBACK_CAPTURE: cannot create AudioRecord (${e.javaClass.simpleName}: ${e.message})"
            return finish(summary)
        }
        measure(record, "playback", "PLAYBACK_CAPTURE", summary, quietSeconds = 8)
        finish(summary)
    }

    /** Records [quietSeconds] with no tone, then [TONE_SECONDS] with the test tone. */
    private fun measure(
        record: AudioRecord,
        fileTag: String,
        name: String,
        summary: MutableList<String>,
        quietSeconds: Int = 4,
    ) {
        try {
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                summary += "$name: AudioRecord not initialized (source unavailable)"
                return
            }
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                summary += "$name: startRecording failed"
                return
            }
            show("Audio test: $name", "recording ($quietSeconds s, no tone)")
            val quiet = read(record, quietSeconds)
            val silenced = isSilenced(record)
            val device = record.routedDevice?.let { "${it.productName} (type ${it.type})" } ?: "?"

            show("Audio test: $name", "recording with 1 kHz test tone")
            val tone = TonePlayer().use { read(record, TONE_SECONDS) }

            writeWav(File(outDir(), "${fileTag}_quiet.wav"), quiet)
            writeWav(File(outDir(), "${fileTag}_tone.wav"), tone)
            val line = "$name: device=$device silenced=$silenced " +
                "quiet=${level(quiet)} tone=${level(tone)}"
            summary += line
            show("Audio test: $name", line.substringAfter("silenced"))
        } catch (e: Exception) {
            summary += "$name: error ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            try { record.stop() } catch (_: Exception) {}
            record.release()
        }
    }

    private fun read(record: AudioRecord, seconds: Int): ShortArray {
        val out = ShortArray(SAMPLE_RATE * seconds)
        var offset = 0
        while (offset < out.size && !Thread.currentThread().isInterrupted) {
            val n = record.read(out, offset, minOf(2048, out.size - offset))
            if (n <= 0) break
            offset += n
        }
        return if (offset == out.size) out else out.copyOf(offset)
    }

    private fun isSilenced(record: AudioRecord): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "n/a"
        val am = getSystemService(AudioManager::class.java)
        val config = am.activeRecordingConfigurations
            .firstOrNull { it.clientAudioSessionId == record.audioSessionId } ?: return "unknown"
        return config.isClientSilenced.toString()
    }

    private fun listInputDevices(summary: MutableList<String>) {
        val am = getSystemService(AudioManager::class.java)
        am.getDevices(AudioManager.GET_DEVICES_INPUTS).forEach {
            summary += "input device: ${it.productName} type=${it.type} id=${it.id}"
        }
    }

    // --- Helpers -----------------------------------------------------------------------

    /** Formats RMS and peak in dBFS. Digital silence shows as -inf. */
    private fun level(samples: ShortArray): String {
        if (samples.isEmpty()) return "no data"
        var sumSq = 0.0
        var peak = 0
        for (s in samples) {
            sumSq += s.toDouble() * s
            peak = maxOf(peak, abs(s.toInt()))
        }
        val rms = sqrt(sumSq / samples.size)
        fun db(v: Double) = if (v <= 0.0) "-inf" else "%.1f".format(20 * log10(v / 32768.0))
        return "rms ${db(rms)} dBFS / peak ${db(peak.toDouble())} dBFS"
    }

    private fun countdown(seconds: Int, label: String) {
        Log.i(TAG, "$label: waiting $seconds s before recording")
        for (s in seconds downTo 1) {
            if (Thread.currentThread().isInterrupted) return
            show("$label starts in $s s", "Switch to the HDMI input now and play something with sound")
            Thread.sleep(1000)
        }
    }

    private fun finish(summary: List<String>) {
        summary.forEach { Log.i(TAG, it) }
        File(outDir(), "summary.txt").appendText(summary.joinToString("\n", postfix = "\n\n"))
        show("Audio test finished", "Results in logcat (AudioProbe)")
        Handler(Looper.getMainLooper()).post { stop() }
    }

    private fun report(message: String) {
        Log.w(TAG, message)
        show("Audio test failed", message)
    }

    private fun show(translated: String, original: String) {
        SubtitleManager.setSubtitle(Subtitle(translated, original))
    }

    private fun runInBackground(block: () -> Unit) {
        worker = Thread({
            try {
                block()
            } catch (_: InterruptedException) {
            }
        }, "AudioProbe").also { it.start() }
    }

    private fun outDir() = File(filesDir, "audio_probe").apply { mkdirs() }

    private fun bufferSize() = maxOf(
        AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, ENCODING),
        SAMPLE_RATE, // ~0.5 s of 16-bit mono
    )

    private fun fgsTypeMicrophone() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0

    private fun fgsTypeMediaProjection() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0

    private fun startAsForeground(type: Int): Boolean = try {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Audio capture test", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_subtitles)
            .setContentTitle("Audio capture test running")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && type != 0) {
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (e: Exception) {
        report("Could not start foreground service: ${e.message}")
        false
    }

    /** Plays a 1 kHz tone through the normal media path while open. */
    private class TonePlayer : AutoCloseable {
        private val track: AudioTrack

        init {
            val rate = 48_000
            val samples = ShortArray(rate) { i -> (sin(2 * PI * 1000 * i / rate) * 0.25 * Short.MAX_VALUE).toInt().toShort() }
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
            track.write(samples, 0, samples.size)
            track.setLoopPoints(0, samples.size, -1)
            track.play()
        }

        override fun close() {
            try { track.stop() } catch (_: Exception) {}
            track.release()
        }
    }

    private fun writeWav(file: File, samples: ShortArray) {
        RandomAccessFile(file, "rw").use { f ->
            f.setLength(0)
            val dataLen = samples.size * 2
            fun intLE(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
            fun shortLE(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
            f.write("RIFF".toByteArray()); f.write(intLE(36 + dataLen)); f.write("WAVE".toByteArray())
            f.write("fmt ".toByteArray()); f.write(intLE(16)); f.write(shortLE(1)); f.write(shortLE(1))
            f.write(intLE(SAMPLE_RATE)); f.write(intLE(SAMPLE_RATE * 2)); f.write(shortLE(2)); f.write(shortLE(16))
            f.write("data".toByteArray()); f.write(intLE(dataLen))
            val bytes = ByteArray(dataLen)
            samples.forEachIndexed { i, s -> bytes[2 * i] = s.toByte(); bytes[2 * i + 1] = (s.toInt() shr 8).toByte() }
            f.write(bytes)
        }
    }

    companion object {
        private const val TAG = "AudioProbe"
        private const val CHANNEL_ID = "audio_probe"
        private const val NOTIFICATION_ID = 1002
        private const val SAMPLE_RATE = 16_000
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val TONE_SECONDS = 3

        const val ACTION_MIC = "com.livetranslate.tv.action.AUDIO_PROBE_MIC"
        const val ACTION_PLAYBACK = "com.livetranslate.tv.action.AUDIO_PROBE_PLAYBACK"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_DELAY_SECONDS = "delay_seconds"

        fun startMic(context: Context, delaySeconds: Int) {
            context.startForegroundService(
                Intent(context, AudioProbeService::class.java)
                    .setAction(ACTION_MIC)
                    .putExtra(EXTRA_DELAY_SECONDS, delaySeconds)
            )
        }

        fun startPlayback(context: Context, resultCode: Int, data: Intent, delaySeconds: Int) {
            context.startForegroundService(
                Intent(context, AudioProbeService::class.java)
                    .setAction(ACTION_PLAYBACK)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_RESULT_DATA, data)
                    .putExtra(EXTRA_DELAY_SECONDS, delaySeconds)
            )
        }
    }
}
