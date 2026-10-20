package com.livetranslate.tv.remoteplay

import android.content.Context
import android.util.Base64

/** A PS5 this TV has been paired with (keys returned by Remote Play registration). */
data class PairedConsole(
    val host: String,
    val nickname: String,
    val registKey: ByteArray,
    val rpKey: ByteArray,
) {
    override fun equals(other: Any?) = other is PairedConsole && host == other.host &&
        nickname == other.nickname && registKey.contentEquals(other.registKey) && rpKey.contentEquals(other.rpKey)

    override fun hashCode() = host.hashCode() * 31 + registKey.contentHashCode()
}

/**
 * Stores the pairing keys in private app storage. They let this app start Remote Play
 * sessions with the console, so they are excluded from backups (see res/xml/backup_rules.xml).
 */
class RemotePlayStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): PairedConsole? {
        val host = prefs.getString(KEY_HOST, null) ?: return null
        val registKey = prefs.getString(KEY_REGIST_KEY, null)?.decode() ?: return null
        val rpKey = prefs.getString(KEY_RP_KEY, null)?.decode() ?: return null
        return PairedConsole(host, prefs.getString(KEY_NICKNAME, "PS5") ?: "PS5", registKey, rpKey)
    }

    fun save(console: PairedConsole) {
        prefs.edit()
            .putString(KEY_HOST, console.host)
            .putString(KEY_NICKNAME, console.nickname)
            .putString(KEY_REGIST_KEY, console.registKey.encode())
            .putString(KEY_RP_KEY, console.rpKey.encode())
            .apply()
    }

    /** Updates just the address, e.g. after the console got a new IP from the router. */
    fun updateHost(host: String) {
        prefs.edit().putString(KEY_HOST, host).apply()
    }

    fun clear() = prefs.edit().remove(KEY_HOST).remove(KEY_NICKNAME).remove(KEY_REGIST_KEY).remove(KEY_RP_KEY).apply()

    /** Last PSN account ID used for pairing, to prefill the pairing screen. */
    var accountId: String?
        get() = prefs.getString(KEY_ACCOUNT_ID, null)
        set(value) = prefs.edit().putString(KEY_ACCOUNT_ID, value).apply()

    private fun ByteArray.encode() = Base64.encodeToString(this, Base64.NO_WRAP)
    private fun String.decode() = try { Base64.decode(this, Base64.NO_WRAP) } catch (e: IllegalArgumentException) { null }

    companion object {
        const val PREFS_NAME = "remote_play"
        private const val KEY_HOST = "host"
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_REGIST_KEY = "regist_key"
        private const val KEY_RP_KEY = "rp_key"
        private const val KEY_ACCOUNT_ID = "psn_account_id"
    }
}
