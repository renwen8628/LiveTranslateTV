package com.livetranslate.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.livetranslate.tv.ocr.OcrRegion
import com.livetranslate.tv.ocr.OcrSubtitlePipeline
import com.livetranslate.tv.remoteplay.RemotePlayController
import com.livetranslate.tv.remoteplay.RemotePlayPairing
import com.livetranslate.tv.remoteplay.RemotePlayStore

/**
 * Owns the subtitle overlay window so it survives MainActivity leaving the foreground
 * (Google TV home, HDMI inputs, other apps).
 *
 * Runs as a `specialUse` foreground service: Android 14+ requires a declared type, and
 * none of the standard types describe "draw subtitles over other apps". Being in the
 * foreground keeps the process from being killed as a cached background app, but the
 * system can still stop it under memory pressure, so the service is START_STICKY and
 * restores itself from persisted settings when restarted.
 *
 * On the TCL test TV the firmware ("TclAppBoot") silently refuses startForeground() for this
 * app, so the service runs as a normal background service. The process is still kept alive
 * because a visible overlay window ranks it as perceptible. See docs/audio-capture-feasibility.md.
 */
class SubtitleOverlayService : Service(), SubtitleManager.Listener {

    private lateinit var windowManager: WindowManager
    private var overlayView: SubtitleOverlayView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isForeground = false
    /** Whether this service started Remote Play + OCR (follows settings.remotePlayEnabled). */
    private var remotePlayActive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        SubtitleManager.init(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            // Null intent = system restart of a sticky service.
            null -> {
                if (SubtitleManager.settings.enabled) {
                    ensureRunning(calledViaStartForegroundService = false)
                } else {
                    stopSelf()
                }
            }

            ACTION_START -> {
                SubtitleManager.updateSettings { it.copy(enabled = true) }
                ensureRunning(calledViaStartForegroundService = true)
            }

            ACTION_STOP -> {
                SubtitleManager.updateSettings { it.copy(enabled = false) }
                stopService()
            }

            ACTION_SHOW_TEXT -> {
                val translated = intent.getStringExtra(EXTRA_TRANSLATED).orEmpty()
                val original = intent.getStringExtra(EXTRA_ORIGINAL)
                val duration = intent.getLongExtra(EXTRA_DURATION_MS, 0L).takeIf { it > 0 }
                SubtitleManager.setSubtitle(Subtitle(translated, original, duration))
                // Updating text never starts the overlay on its own.
                if (overlayView == null) stopSelf(startId)
            }

            ACTION_CONFIGURE_SERVER -> {
                // Typing a URL with a TV remote is slow; this lets it be set over ADB.
                val url = intent.getStringExtra(EXTRA_SERVER_URL)
                val enabled = intent.getBooleanExtra(EXTRA_SERVER_ENABLED, true)
                SubtitleManager.updateSettings { s ->
                    s.copy(serverUrl = url?.trim() ?: s.serverUrl, serverEnabled = enabled)
                }
                if (overlayView == null) stopSelf(startId)
            }

            ACTION_RP_PAIR -> {
                val accountId = intent.getStringExtra(EXTRA_PSN_ACCOUNT_ID).orEmpty()
                val pin = intent.getStringExtra(EXTRA_PIN).orEmpty()
                val host = intent.getStringExtra(EXTRA_HOST)
                Thread({
                    val result = RemotePlayPairing.pair(accountId, pin, host)
                    android.os.Handler(mainLooper).post {
                        when (result) {
                            is RemotePlayPairing.Result.Success -> {
                                RemotePlayStore(this).save(result.console)
                                RemotePlayStore(this).accountId = accountId
                                Log.i(TAG, "Paired with ${result.console.nickname} at ${result.console.host}")
                                SubtitleManager.setSubtitle(Subtitle("已配对 ${result.console.nickname}", "Paired with PS5", 5000))
                            }
                            is RemotePlayPairing.Result.Failure -> {
                                Log.w(TAG, "Pairing failed: ${result.message}")
                                SubtitleManager.setSubtitle(Subtitle("配对失败", "Pairing failed: ${result.message}", 8000))
                            }
                        }
                        if (overlayView == null) stopSelf(startId)
                    }
                }, "RemotePlayPair").start()
            }

            ACTION_RP_START -> {
                RemotePlayController.saveDebugFrames = intent.getBooleanExtra(EXTRA_SAVE_FRAMES, false)
                // Optional subtitle area override (fractions of the frame), for tuning per game.
                val d = OcrRegion()
                OcrSubtitlePipeline.region = OcrRegion(
                    left = intent.getFloatExtra(EXTRA_OCR_LEFT, d.left),
                    top = intent.getFloatExtra(EXTRA_OCR_TOP, d.top),
                    right = intent.getFloatExtra(EXTRA_OCR_RIGHT, d.right),
                    bottom = intent.getFloatExtra(EXTRA_OCR_BOTTOM, d.bottom),
                )
                // An explicit start also retries after a failure (e.g. after pairing again).
                if (remotePlayActive && !RemotePlayController.isRunning) stopRemotePlayNow()
                SubtitleManager.updateSettings { it.copy(remotePlayEnabled = true) }
                syncRemotePlay(SubtitleManager.settings)
                // Runs once the overlay is up; the setting is saved either way.
                if (overlayView == null) stopSelf(startId)
            }

            ACTION_OCR_BENCHMARK -> {
                Thread({ com.livetranslate.tv.ocr.OcrBenchmark.run() }, "OcrBench").start()
                if (overlayView == null) stopSelf(startId)
            }

            ACTION_RP_STOP -> {
                SubtitleManager.updateSettings { it.copy(remotePlayEnabled = false) }
                stopRemotePlayNow()
                if (overlayView == null) stopSelf(startId)
            }

            ACTION_CLEAR -> {
                SubtitleManager.clearSubtitle()
                if (overlayView == null) stopSelf(startId)
            }

            else -> Log.w(TAG, "Unknown action: ${intent.action}")
        }
        return START_STICKY
    }

    private fun ensureRunning(calledViaStartForegroundService: Boolean) {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission not granted; stopping")
            // startForegroundService() requires a matching startForeground() call even if
            // we stop immediately, otherwise the system crashes the app.
            if (calledViaStartForegroundService) startAsForeground()
            stopService()
            return
        }
        // Overlay first: on Android 15+ a visible overlay window is what allows a
        // foreground service started from the background (e.g. a sticky restart).
        showOverlay()
        if (!startAsForeground()) {
            stopService()
        }
    }

    /**
     * Called on every start request, even if already in the foreground: each
     * startForegroundService() call must be matched by startForeground(), and repeating
     * it simply re-posts the same notification.
     */
    private fun startAsForeground(): Boolean {
        return try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForeground = true
            true
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException when started from the background.
            Log.e(TAG, "Could not start foreground service", e)
            false
        }
    }

    private fun showOverlay() {
        if (overlayView != null) return // repeated start requests reuse the existing window

        val view = SubtitleOverlayView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Never take key focus or touches: remote navigation must reach the app below.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = verticalOffsetPx(SubtitleManager.settings)
        }

        try {
            windowManager.addView(view, params)
            overlayView = view
            layoutParams = params
            SubtitleManager.addListener(this) // renders current state immediately
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay", e)
        }
    }

    override fun onSubtitleStateChanged(subtitle: Subtitle, settings: SubtitleSettings) {
        val view = overlayView ?: return
        val params = layoutParams ?: return
        view.render(subtitle, settings)
        // The connection lives only while the overlay is up; sync() is cheap when nothing changed.
        SubtitleRepository.sync(settings)
        syncRemotePlay(settings)

        val y = verticalOffsetPx(settings)
        if (params.y != y) {
            params.y = y
            try {
                windowManager.updateViewLayout(view, params)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to move overlay", e)
            }
        }
    }

    /**
     * Starts or stops Remote Play + OCR to match the setting. Acts only on changes, so a
     * failed start (e.g. PS5 off) is not retried on every subtitle update; the controller
     * retries transient failures itself, and ACTION_RP_START forces a fresh start.
     */
    private fun syncRemotePlay(settings: SubtitleSettings) {
        val want = settings.remotePlayEnabled && overlayView != null
        if (want && !remotePlayActive) {
            if (RemotePlayStore(this).load() == null) return // not paired yet; the settings screen says so
            remotePlayActive = true
            RemotePlayController.start(this)
            OcrSubtitlePipeline.start()
        } else if (!want && remotePlayActive) {
            stopRemotePlayNow()
        }
    }

    private fun stopRemotePlayNow() {
        remotePlayActive = false
        OcrSubtitlePipeline.stop()
        RemotePlayController.stop()
    }

    private fun verticalOffsetPx(settings: SubtitleSettings): Int {
        val screenHeight = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.maximumWindowMetrics.bounds.height()
        } else {
            @Suppress("DEPRECATION")
            resources.displayMetrics.heightPixels
        }
        return screenHeight * settings.verticalPositionPercent / 100
    }

    private fun removeOverlay() {
        SubtitleManager.removeListener(this)
        SubtitleRepository.disconnect()
        stopRemotePlayNow()
        overlayView?.let { view ->
            try {
                windowManager.removeViewImmediate(view)
            } catch (e: Exception) {
                Log.w(TAG, "Overlay already removed", e)
            }
        }
        overlayView = null
        layoutParams = null
    }

    private fun stopService() {
        removeOverlay()
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        stopSelf()
    }

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Subtitle overlay", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, SubtitleOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_subtitles)
            .setContentTitle("Live subtitles on")
            .setContentText("Subtitles are shown over other apps and HDMI inputs.")
            .setContentIntent(openApp)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "SubtitleOverlay"
        private const val CHANNEL_ID = "subtitle_overlay"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.livetranslate.tv.action.START"
        const val ACTION_STOP = "com.livetranslate.tv.action.STOP"
        const val ACTION_SHOW_TEXT = "com.livetranslate.tv.action.SHOW_TEXT"
        const val ACTION_CLEAR = "com.livetranslate.tv.action.CLEAR"
        const val ACTION_CONFIGURE_SERVER = "com.livetranslate.tv.action.CONFIGURE_SERVER"
        const val ACTION_RP_PAIR = "com.livetranslate.tv.action.RP_PAIR"
        const val ACTION_RP_START = "com.livetranslate.tv.action.RP_START"
        const val ACTION_RP_STOP = "com.livetranslate.tv.action.RP_STOP"
        const val ACTION_OCR_BENCHMARK = "com.livetranslate.tv.action.OCR_BENCHMARK"

        const val EXTRA_TRANSLATED = "translated"
        const val EXTRA_ORIGINAL = "original"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_SERVER_ENABLED = "server_enabled"
        const val EXTRA_PSN_ACCOUNT_ID = "psn_account_id"
        const val EXTRA_PIN = "pin"
        const val EXTRA_HOST = "host"
        const val EXTRA_SAVE_FRAMES = "save_frames"
        const val EXTRA_OCR_LEFT = "ocr_left"
        const val EXTRA_OCR_TOP = "ocr_top"
        const val EXTRA_OCR_RIGHT = "ocr_right"
        const val EXTRA_OCR_BOTTOM = "ocr_bottom"

        fun startRemotePlay(context: Context) {
            context.startService(Intent(context, SubtitleOverlayService::class.java).setAction(ACTION_RP_START))
        }

        fun stopRemotePlay(context: Context) {
            context.startService(Intent(context, SubtitleOverlayService::class.java).setAction(ACTION_RP_STOP))
        }

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SubtitleOverlayService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            // Plain startService is fine here: we are either in the foreground or the service
            // is already running, so no new foreground service is being created.
            try {
                context.startService(
                    Intent(context, SubtitleOverlayService::class.java).setAction(ACTION_STOP)
                )
            } catch (e: IllegalStateException) {
                context.stopService(Intent(context, SubtitleOverlayService::class.java))
            }
        }
    }
}
