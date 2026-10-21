package com.livetranslate.tv.remoteplay

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Asks the PS5 which game is running, so the translator can use that game's names and terms.
 *
 * The PS5 answers a Remote Play discovery request ("SRCH") with its status, and includes
 * "running-app-name:" while a game is open. We poll the paired console directly (unicast)
 * and report each new name on the main thread. Going back to the home screen reports
 * nothing, so the last game stays in use.
 */
object GameTitleWatcher {

    private const val TAG = "GameTitle"
    private const val PS5_DISCOVERY_PORT = 9302
    private const val POLL_MS = 20_000L
    private const val REPLY_TIMEOUT_MS = 2_000
    private val SEARCH = "SRCH * HTTP/1.1\ndevice-discovery-protocol-version:00030010\n".toByteArray()

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var thread: Thread? = null

    /** Starts polling [host]; [onGame] runs on the main thread when the running game changes. */
    fun start(host: String, onGame: (String) -> Unit) {
        stop()
        val t = Thread({ pollLoop(host, onGame) }, "GameTitleWatcher")
        thread = t
        t.start()
    }

    fun stop() {
        thread?.interrupt()
        thread = null
    }

    private fun pollLoop(host: String, onGame: (String) -> Unit) {
        val me = Thread.currentThread()
        var lastName: String? = null
        while (thread === me) {
            val name = query(host)
            if (name != null && name != lastName) {
                lastName = name
                Log.i(TAG, "Running game: $name")
                mainHandler.post { if (thread === me) onGame(name) }
            }
            try {
                Thread.sleep(POLL_MS)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    private fun query(host: String): String? = try {
        DatagramSocket().use { socket ->
            socket.soTimeout = REPLY_TIMEOUT_MS
            val address = InetAddress.getByName(host)
            socket.send(DatagramPacket(SEARCH, SEARCH.size, address, PS5_DISCOVERY_PORT))
            val buffer = ByteArray(2048)
            val reply = DatagramPacket(buffer, buffer.size)
            socket.receive(reply)
            parseRunningAppName(String(reply.data, 0, reply.length, Charsets.UTF_8))
        }
    } catch (e: SocketTimeoutException) {
        null // console asleep or busy; try again next poll
    } catch (e: Exception) {
        Log.w(TAG, "Status query failed: ${e.message}")
        null
    }

    /** Extracts "running-app-name:" from a discovery reply, or null when no game is open. */
    fun parseRunningAppName(reply: String): String? =
        reply.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("running-app-name:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
}
