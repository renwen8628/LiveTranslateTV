package com.livetranslate.tv.remoteplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.metallic.chiaki.lib.Codec
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.ConnectVideoProfile
import com.metallic.chiaki.lib.ConnectedEvent
import com.metallic.chiaki.lib.LoginPinRequestEvent
import com.metallic.chiaki.lib.QuitEvent
import com.metallic.chiaki.lib.RumbleEvent
import com.metallic.chiaki.lib.Session
import com.metallic.chiaki.lib.VideoFPSPreset
import com.metallic.chiaki.lib.VideoResolutionPreset
import java.io.File
import java.io.FileOutputStream

/** One grayscale video frame (luma only), enough for text recognition. */
class LumaFrame(val width: Int, val height: Int, val pixels: ByteArray, val timestampMs: Long)

/**
 * Runs a PS5 Remote Play session in the background without showing it.
 *
 * Video is hardware-decoded into an off-screen [ImageReader]; the TV keeps showing the HDMI
 * input. A few frames per second are handed to [frameListener] (later: OCR), and in debug
 * mode one per second is saved under files/rp_frames/ for inspection.
 *
 * Call from the main thread.
 */
object RemotePlayController {

    private const val TAG = "RemotePlay"
    private const val WIDTH = 1280
    private const val HEIGHT = 720
    private const val SAVED_FRAMES_KEPT = 5

    sealed class Status {
        data object Idle : Status()
        data class Connecting(val host: String) : Status()
        data class Streaming(val host: String, val decodedFps: Int) : Status()
        data class Failed(val message: String) : Status()
        data class Retrying(val message: String, val delaySeconds: Long) : Status()

        fun describe() = when (this) {
            Idle -> "Remote Play off"
            is Connecting -> "Remote Play: connecting to $host…"
            is Streaming -> "Remote Play: receiving video from $host ($decodedFps fps)"
            is Failed -> "Remote Play stopped: $message"
            is Retrying -> "Remote Play: $message. Retrying in ${delaySeconds}s"
        }
    }

    fun interface StatusListener {
        fun onStatus(status: Status)
    }

    @Volatile
    var frameListener: ((LumaFrame) -> Unit)? = null

    /** Minimum time between frames handed to [frameListener]. */
    @Volatile
    var frameIntervalMs = 1000L

    /** Save sampled frames to files/rp_frames (for testing). */
    @Volatile
    var saveDebugFrames = false

    var status: Status = Status.Idle
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val statusListeners = mutableSetOf<StatusListener>()
    private var session: Session? = null
    private var reader: ImageReader? = null
    private var frameThread: HandlerThread? = null
    private var framesDir: File? = null

    // Whether the user wants Remote Play on; drives automatic retries.
    private var wanted = false
    private var appContext: Context? = null
    private var retryAttempt = 0
    private val retryRunnable = Runnable { if (wanted && session == null) startSession() }

    // Frame thread state.
    private var lastDeliveredMs = 0L
    private var framesThisSecond = 0
    private var secondStartMs = 0L
    private var savedCount = 0

    fun addListener(listener: StatusListener) {
        if (statusListeners.add(listener)) listener.onStatus(status)
    }

    fun removeListener(listener: StatusListener) {
        statusListeners.remove(listener)
    }

    /** True while Remote Play is switched on (connected, connecting, or waiting to retry). */
    val isRunning get() = wanted

    fun start(context: Context) {
        if (wanted) return
        wanted = true
        appContext = context.applicationContext
        retryAttempt = 0
        startSession()
    }

    fun stop() {
        wanted = false
        mainHandler.removeCallbacks(retryRunnable)
        endSession(Status.Idle)
    }

    private fun startSession() {
        if (session != null) return
        val context = appContext ?: return
        val console = RemotePlayStore(context).load() ?: run {
            wanted = false
            setStatus(Status.Failed("not paired with a PS5"))
            return
        }
        framesDir = File(context.filesDir, "rp_frames").apply { mkdirs() }

        val thread = HandlerThread("RemotePlayFrames").also { it.start() }
        frameThread = thread
        val imageReader = ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.YUV_420_888, 3)
        imageReader.setOnImageAvailableListener({ onImageAvailable(it) }, Handler(thread.looper))
        reader = imageReader

        val profile = ConnectVideoProfile.preset(VideoResolutionPreset.RES_720P, VideoFPSPreset.FPS_30, Codec.CODEC_H264)
        val newSession = try {
            Session(ConnectInfo(true, console.host, console.registKey, console.rpKey, profile), null, false)
        } catch (e: Exception) {
            Log.e(TAG, "Session create failed", e)
            release(imageReader, thread)
            reader = null
            frameThread = null
            wanted = false
            setStatus(Status.Failed("could not create session: ${e.message}"))
            return
        }
        newSession.eventCallback = { event -> mainHandler.post { onSessionEvent(newSession, console.host, event) } }
        newSession.setSurface(imageReader.surface)
        session = newSession
        setStatus(Status.Connecting(console.host))
        val err = newSession.start()
        if (!err.isSuccess) {
            Log.e(TAG, "Session start failed: $err")
            wanted = false
            endSession(Status.Failed("could not start: $err"))
        }
    }

    private fun endSession(finalStatus: Status) {
        val s = session ?: run {
            setStatus(finalStatus)
            return
        }
        session = null
        s.eventCallback = null
        // Release this session's reader/thread only (a retry may already have new ones).
        val oldReader = reader
        val oldThread = frameThread
        reader = null
        frameThread = null
        setStatus(finalStatus)
        // stop() + dispose() (which joins the native threads) can block; keep it off the main thread.
        Thread({
            try {
                // dispose() frees the session, which also shuts down the video decoder.
                s.stop()
                s.dispose()
            } catch (e: Exception) {
                Log.w(TAG, "Session shutdown error", e)
            }
            mainHandler.post { release(oldReader, oldThread) }
        }, "RemotePlayStop").start()
    }

    private fun release(imageReader: ImageReader?, thread: HandlerThread?) {
        imageReader?.close()
        thread?.quitSafely()
    }

    /** Quit reasons worth retrying: console busy (e.g. a previous session not yet released) or connection dropped. */
    private val RETRYABLE_QUIT_REASONS = setOf(
        4,  // SESSION_REQUEST_RP_IN_USE
        5,  // SESSION_REQUEST_RP_CRASH
        9,  // CTRL_CONNECT_FAILED
        10, // CTRL_CONNECTION_REFUSED
        11, // STREAM_CONNECTION_REMOTE_DISCONNECTED
    )

    private fun scheduleRetry(reason: String) {
        retryAttempt++
        val delaySeconds = minOf(30L, 5L shl minOf(retryAttempt - 1, 3))
        endSession(Status.Retrying(reason, delaySeconds))
        mainHandler.removeCallbacks(retryRunnable)
        mainHandler.postDelayed(retryRunnable, delaySeconds * 1000)
    }

    private fun onSessionEvent(source: Session, host: String, event: Any) {
        if (source !== session) return // event from a session we already stopped
        when (event) {
            is ConnectedEvent -> {
                Log.i(TAG, "Connected to $host")
                retryAttempt = 0
                setStatus(Status.Streaming(host, 0))
            }
            is LoginPinRequestEvent -> {
                // Only happens if the PS5 has a Remote Play login PIN set; not supported yet.
                wanted = false
                endSession(Status.Failed("the PS5 asks for a login PIN (not supported yet)"))
            }
            is QuitEvent -> {
                Log.i(TAG, "Session quit: ${event.reason} (${event.reason.value}) ${event.reasonString.orEmpty()}")
                val message = event.reasonString ?: event.reason.toString()
                when {
                    wanted && event.reason.value in RETRYABLE_QUIT_REASONS -> scheduleRetry(message)
                    else -> {
                        wanted = false
                        endSession(if (event.reason.isError) Status.Failed(message) else Status.Idle)
                    }
                }
            }
            is RumbleEvent -> Unit
        }
    }

    // --- Frame thread -------------------------------------------------------------------

    private fun onImageAvailable(imageReader: ImageReader) {
        val image = try {
            imageReader.acquireLatestImage()
        } catch (e: IllegalStateException) {
            null
        } ?: return
        image.use { img ->
            val now = SystemClock.elapsedRealtime()
            countFrame(now)
            val listener = frameListener
            val save = saveDebugFrames
            if ((listener == null && !save) || now - lastDeliveredMs < frameIntervalMs) return
            lastDeliveredMs = now

            val plane = img.planes[0]
            val w = img.width
            val h = img.height
            val luma = ByteArray(w * h)
            val buf = plane.buffer
            val rowStride = plane.rowStride
            for (row in 0 until h) {
                buf.position(row * rowStride)
                buf.get(luma, row * w, w)
            }
            val frame = LumaFrame(w, h, luma, now)
            listener?.invoke(frame)
            if (save) saveFrame(frame)
        }
    }

    private fun countFrame(now: Long) {
        if (secondStartMs == 0L) secondStartMs = now
        framesThisSecond++
        if (now - secondStartMs >= 1000) {
            val fps = framesThisSecond
            framesThisSecond = 0
            secondStartMs = now
            mainHandler.post {
                (status as? Status.Streaming)?.let { if (it.decodedFps != fps) setStatus(it.copy(decodedFps = fps)) }
            }
        }
    }

    private fun saveFrame(frame: LumaFrame) {
        val dir = framesDir ?: return
        val pixels = IntArray(frame.width * frame.height) { i ->
            val v = frame.pixels[i].toInt() and 0xFF
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val bitmap = Bitmap.createBitmap(pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888)
        val file = File(dir, "frame_${savedCount % SAVED_FRAMES_KEPT}.png")
        savedCount++
        try {
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Log.d(TAG, "Saved ${file.name}")
        } catch (e: Exception) {
            Log.w(TAG, "Could not save frame", e)
        } finally {
            bitmap.recycle()
        }
    }

    private fun setStatus(newStatus: Status) {
        if (newStatus == status) return
        status = newStatus
        Log.i(TAG, newStatus.describe())
        statusListeners.toList().forEach { it.onStatus(newStatus) }
    }
}
