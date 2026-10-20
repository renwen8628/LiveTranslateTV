package com.livetranslate.tv.remoteplay

import android.util.Base64
import android.util.Log
import com.metallic.chiaki.lib.ChiakiLog
import com.metallic.chiaki.lib.DiscoveryHost
import com.metallic.chiaki.lib.DiscoveryService
import com.metallic.chiaki.lib.DiscoveryServiceOptions
import com.metallic.chiaki.lib.Regist
import com.metallic.chiaki.lib.RegistEventCanceled
import com.metallic.chiaki.lib.RegistEventFailed
import com.metallic.chiaki.lib.RegistEventSuccess
import com.metallic.chiaki.lib.RegistInfo
import com.metallic.chiaki.lib.Target
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Finds a PS5 on the local network and pairs with it (Remote Play "Link Device").
 * Blocking calls: run off the main thread.
 */
object RemotePlayPairing {

    private const val TAG = "RemotePlay"

    sealed class Result {
        data class Success(val console: PairedConsole) : Result()
        data class Failure(val message: String) : Result()
    }

    /** Broadcasts on the LAN for [timeoutMs] and returns the first PS5 found. */
    fun discoverPs5(timeoutMs: Long = 4000): DiscoveryHost? {
        val latch = CountDownLatch(1)
        var found: DiscoveryHost? = null
        val service = try {
            DiscoveryService(
                DiscoveryServiceOptions(
                    hostsMax = 16u,
                    hostDropPings = 3u,
                    pingMs = 500u,
                    sendAddr = InetSocketAddress("255.255.255.255", 987),
                ),
            ) { hosts ->
                hosts.firstOrNull { it.isPS5 && it.hostAddr != null }?.let {
                    found = it
                    latch.countDown()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Discovery failed to start", e)
            return null
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        service.dispose()
        return found
    }

    /**
     * Registers this TV with the console.
     * @param accountIdBase64 PSN account ID (8 bytes, base64)
     * @param pin 8-digit code from PS5 Settings > System > Remote Play > Link Device
     * @param host console IP; discovered automatically when null
     */
    fun pair(accountIdBase64: String, pin: String, host: String?, timeoutMs: Long = 30_000): Result {
        val accountId = try {
            Base64.decode(accountIdBase64.trim(), Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            return Result.Failure("Account ID is not valid base64")
        }
        if (accountId.size != RegistInfo.ACCOUNT_ID_SIZE) {
            return Result.Failure("Account ID must be ${RegistInfo.ACCOUNT_ID_SIZE} bytes, got ${accountId.size}")
        }
        val pinValue = pin.trim().takeIf { it.length == 8 }?.toIntOrNull()
            ?: return Result.Failure("PIN must be 8 digits")

        val discovered = if (host.isNullOrBlank()) discoverPs5() else null
        val target = host?.takeIf { it.isNotBlank() } ?: discovered?.hostAddr
            ?: return Result.Failure("No PS5 found on the network")

        val log = ChiakiLog(ChiakiLog.Level.INFO.value or ChiakiLog.Level.WARNING.value or ChiakiLog.Level.ERROR.value) { _, text ->
            Log.i(TAG, "regist: $text")
        }
        val latch = CountDownLatch(1)
        var result: Result = Result.Failure("Timed out waiting for the PS5")
        val regist = try {
            Regist(RegistInfo(Target.PS5_1, target, false, null, accountId, pinValue), log) { event ->
                result = when (event) {
                    is RegistEventSuccess -> Result.Success(
                        PairedConsole(
                            host = target,
                            nickname = event.host.serverNickname.ifBlank { discovered?.hostName ?: "PS5" },
                            registKey = event.host.rpRegistKey,
                            rpKey = event.host.rpKey,
                        ),
                    )
                    RegistEventFailed -> Result.Failure("PS5 rejected pairing (wrong or expired PIN?)")
                    RegistEventCanceled -> Result.Failure("Pairing canceled")
                }
                latch.countDown()
            }
        } catch (e: Exception) {
            return Result.Failure("Could not start pairing: ${e.message}")
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) regist.stop()
        regist.dispose()
        return result
    }
}
