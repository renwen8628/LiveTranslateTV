package com.livetranslate.tv

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Keeps one WebSocket connection open to [url], reconnecting with exponential backoff.
 *
 * All callbacks ([onStatus], [onText], [onOpen]) are delivered on the main thread.
 * Must be created and controlled from the main thread.
 */
class SubtitleWebSocketClient(
    private val onStatus: (ConnectionStatus) -> Unit,
    private val onOpen: (WebSocket) -> Unit,
    private val onText: (String) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // streaming connection: no read timeout
        .pingInterval(15, TimeUnit.SECONDS)    // detect dead connections (e.g. Wi-Fi drop)
        .build()

    private var url: String? = null
    private var socket: WebSocket? = null
    private var attempt = 0

    /** Bumped on every connect/stop so callbacks from abandoned sockets are ignored. */
    private var generation = 0

    private val reconnectRunnable = Runnable { openSocket() }

    /** Connects to [newUrl], replacing any existing connection. No-op if already on that URL. */
    fun connect(newUrl: String) {
        // url is only non-null while connecting, connected, or waiting to reconnect.
        if (newUrl == url) return
        stop()
        url = newUrl
        attempt = 0
        openSocket()
    }

    fun stop() {
        generation++
        mainHandler.removeCallbacks(reconnectRunnable)
        socket?.close(NORMAL_CLOSURE, "client stopped")
        socket = null
        url = null
        attempt = 0
    }

    /** Releases the OkHttp thread pools. The client cannot be reused afterwards. */
    fun shutdown() {
        stop()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    private fun openSocket() {
        val target = url ?: return
        val request = try {
            Request.Builder().url(target).build()
        } catch (e: IllegalArgumentException) {
            // Bad URL: retrying will not help, but keep backing off so the status stays visible.
            scheduleReconnect("invalid URL")
            return
        }
        val gen = ++generation
        onStatus(ConnectionStatus.Connecting(target))
        socket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = onMain(gen) {
                attempt = 0
                onStatus(ConnectionStatus.Connected(target))
                onOpen(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) = onMain(gen) {
                onText(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(NORMAL_CLOSURE, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onMain(gen) {
                socket = null
                scheduleReconnect("closed by server")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                onMain(gen) {
                    Log.w(TAG, "WebSocket failure: ${t.message}")
                    socket = null
                    scheduleReconnect(t.message ?: t.javaClass.simpleName)
                }
        })
    }

    private fun scheduleReconnect(reason: String) {
        if (url == null) return
        attempt++
        // 1s, 2s, 4s … capped at 30s, plus up to 20% jitter so many TVs don't retry in lockstep.
        val base = (INITIAL_BACKOFF_MS shl (attempt - 1).coerceAtMost(5)).coerceAtMost(MAX_BACKOFF_MS)
        val delay = base + Random.nextLong(base / 5 + 1)
        onStatus(ConnectionStatus.WaitingToReconnect(attempt, delay, reason))
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, delay)
    }

    private fun onMain(gen: Int, block: () -> Unit) {
        mainHandler.post { if (gen == generation) block() }
    }

    private companion object {
        const val TAG = "SubtitleWebSocket"
        const val NORMAL_CLOSURE = 1000
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
    }
}
