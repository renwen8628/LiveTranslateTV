package com.livetranslate.tv

import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.livetranslate.tv.remoteplay.RemotePlayPairing
import com.livetranslate.tv.remoteplay.RemotePlayStore

/**
 * Pairs this TV with a PS5 for Remote Play (PS5: Settings > System > Remote Play > Link Device).
 * The PS5 is found on the network automatically; the PSN account ID is remembered.
 */
class PairingActivity : FragmentActivity() {

    private lateinit var consoleText: TextView
    private lateinit var accountInput: EditText
    private lateinit var pinInput: EditText
    private lateinit var pairButton: Button
    private lateinit var resultText: TextView
    private var discoveredHost: String? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(32), dp(48), dp(32))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFF101418.toInt())
            addView(content, ViewLayoutParams.matchWidth())
        })

        content.addView(text("Pair with your PS5", 28f, 0xFFFFFFFF.toInt(), bold = true))
        content.addView(text(
            "1. On the PS5: Settings → System → Remote Play → Link Device.\n" +
                "2. Enter your PSN account ID and the 8-digit code shown on the PS5.\n" +
                "The code expires after a few minutes.",
            16f, 0xFFB0BEC5.toInt(),
        ).apply { setPadding(0, dp(8), 0, dp(16)) })

        consoleText = text("Looking for a PS5 on the network…", 18f, 0xFFFFFFFF.toInt())
        content.addView(consoleText, ViewLayoutParams.matchWidth(bottom = dp(16)))

        accountInput = input("PSN account ID (base64, e.g. b+UyAMBuJU8=)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        accountInput.setText(RemotePlayStore(this).accountId.orEmpty())
        content.addView(accountInput, ViewLayoutParams.matchWidth(bottom = dp(8)))

        pinInput = input("8-digit code from the PS5", InputType.TYPE_CLASS_NUMBER)
        pinInput.imeOptions = EditorInfo.IME_ACTION_DONE
        pinInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) pairButton.requestFocus()
            false
        }
        content.addView(pinInput, ViewLayoutParams.matchWidth(bottom = dp(8)))

        pairButton = Button(this).apply {
            text = "Pair"
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setBackgroundResource(R.drawable.setting_row_background)
            setTextColor(ContextCompat.getColorStateList(this@PairingActivity, R.color.setting_row_text))
            setOnClickListener { pair() }
        }
        content.addView(pairButton, ViewLayoutParams.matchWidth(bottom = dp(16)))

        resultText = text("", 18f, 0xFFFFFFFF.toInt())
        content.addView(resultText)

        (if (accountInput.text.isNullOrBlank()) accountInput else pinInput).requestFocus()
        discover()
    }

    private fun discover() {
        Thread({
            val host = RemotePlayPairing.discoverPs5()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                discoveredHost = host?.hostAddr
                consoleText.text = if (host != null) {
                    "Found ${host.hostName ?: "PS5"} at ${host.hostAddr}"
                } else {
                    "No PS5 found. Make sure it is on (not in rest mode) and on the same network. " +
                        "Pairing will search again."
                }
            }
        }, "Ps5Discovery").start()
    }

    private fun pair() {
        if (busy) return
        val account = accountInput.text.toString().trim()
        val pin = pinInput.text.toString().trim()
        if (account.isEmpty() || pin.length != 8) {
            showResult("Enter your account ID and the 8-digit code.", error = true)
            return
        }
        busy = true
        pairButton.isEnabled = false
        showResult("Pairing…", error = false)
        Thread({
            val result = RemotePlayPairing.pair(account, pin, discoveredHost)
            runOnUiThread {
                busy = false
                pairButton.isEnabled = true
                when (result) {
                    is RemotePlayPairing.Result.Success -> {
                        val store = RemotePlayStore(this)
                        store.save(result.console)
                        store.accountId = account
                        showResult("Paired with ${result.console.nickname}. PS5 translation is on.", error = false)
                        // Turn translation on (also retries Remote Play if it had failed before).
                        SubtitleManager.updateSettings { it.copy(enabled = true) }
                        SubtitleOverlayService.start(this)
                        SubtitleOverlayService.startRemotePlay(this)
                        pinInput.setText("")
                        consoleText.postDelayed({ if (!isDestroyed) finish() }, 2500)
                    }
                    is RemotePlayPairing.Result.Failure -> showResult(result.message, error = true)
                }
            }
        }, "Ps5Pairing").start()
    }

    private fun showResult(message: String, error: Boolean) {
        resultText.text = message
        resultText.setTextColor(if (error) 0xFFFF8A80.toInt() else 0xFFA5D6A7.toInt())
    }

    private fun text(value: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun input(hint: String, type: Int) = EditText(this).apply {
        this.hint = hint
        inputType = type
        isSingleLine = true
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setBackgroundResource(R.drawable.setting_row_background)
        setTextColor(ContextCompat.getColorStateList(this@PairingActivity, R.color.setting_row_text))
        setHintTextColor(0xFF90A4AE.toInt())
        setPadding(dp(16), dp(10), dp(16), dp(10))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private object ViewLayoutParams {
        fun matchWidth(bottom: Int = 0) = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = bottom }
    }
}
