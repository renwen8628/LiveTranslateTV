package com.livetranslate.tv

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Settings screen and permission handling. The overlay itself lives in
 * [SubtitleOverlayService]; this activity only starts/stops it and edits settings.
 */
class MainActivity : FragmentActivity(), SubtitleManager.Listener, SubtitleRepository.Listener {

    private lateinit var statusText: TextView
    private lateinit var rowsContainer: LinearLayout
    private lateinit var toggleButton: Button
    private lateinit var permissionButton: Button
    private lateinit var serverUrlInput: EditText
    private lateinit var connectionText: TextView
    private val settingRows = mutableListOf<ChoiceRow<*>>()
    private var sampleIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SubtitleManager.init(this)
        setContentView(R.layout.activity_settings)
        statusText = findViewById(R.id.status_text)
        rowsContainer = findViewById(R.id.rows)
        buildRows()
        toggleButton.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        SubtitleManager.addListener(this)
        SubtitleRepository.addListener(this)
    }

    override fun onResume() {
        super.onResume()
        // Re-check every time: the user may be returning from the permission screen.
        val granted = Settings.canDrawOverlays(this)
        permissionButton.visibility = if (granted) View.GONE else View.VISIBLE
        if (granted && SubtitleManager.settings.enabled) {
            // Restore subtitles that were on last time (no-op if already running).
            SubtitleOverlayService.start(this)
        }
        refresh()
    }

    override fun onStop() {
        SubtitleManager.removeListener(this)
        SubtitleRepository.removeListener(this)
        super.onStop()
    }

    override fun onSubtitleStateChanged(subtitle: Subtitle, settings: SubtitleSettings) = refresh()

    override fun onConnectionChanged(status: ConnectionStatus, stats: SubtitleRepository.Stats) =
        refreshConnection()

    private fun buildRows() {
        permissionButton = addButton("Grant overlay permission") { requestOverlayPermission() }
        toggleButton = addButton("") { toggleSubtitles() }
        addButton("Show next test subtitle") { showNextSample() }
        addButton("Clear subtitle") { SubtitleManager.clearSubtitle() }

        val s = SubtitleSettings.Companion
        settingRows += ChoiceRow(
            "Font size", s.FONT_SIZES, { "$it sp" },
            get = { it.fontSizeSp }, set = { st, v -> st.copy(fontSizeSp = v) },
        )
        settingRows += ChoiceRow(
            "Text color", SubtitleColor.entries, { it.label },
            get = { it.textColor }, set = { st, v -> st.copy(textColor = v) },
        )
        settingRows += ChoiceRow(
            "Background opacity", s.OPACITIES, { "$it%" },
            get = { it.backgroundOpacityPercent },
            set = { st, v -> st.copy(backgroundOpacityPercent = v) },
        )
        settingRows += ChoiceRow(
            "Vertical position", s.POSITIONS, { "$it% from bottom" },
            get = { it.verticalPositionPercent },
            set = { st, v -> st.copy(verticalPositionPercent = v) },
        )
        settingRows += ChoiceRow(
            "Source language", Languages.SOURCE.map { it.code },
            { code -> Languages.SOURCE.firstOrNull { it.code == code }?.label ?: code },
            get = { it.sourceLanguage }, set = { st, v -> st.copy(sourceLanguage = v) },
        )
        settingRows += ChoiceRow(
            "Target language", Languages.TARGET.map { it.code },
            { code -> Languages.TARGET.firstOrNull { it.code == code }?.label ?: code },
            get = { it.targetLanguage }, set = { st, v -> st.copy(targetLanguage = v) },
        )
        settingRows += ChoiceRow(
            "Bilingual subtitles", listOf(true, false), { if (it) "On" else "Off" },
            get = { it.bilingual }, set = { st, v -> st.copy(bilingual = v) },
        )
        settingRows += ChoiceRow(
            "Server connection", listOf(true, false), { if (it) "On" else "Off" },
            get = { it.serverEnabled }, set = { st, v -> st.copy(serverEnabled = v) },
        )
        settingRows.forEach { rowsContainer.addView(it.button) }

        serverUrlInput = EditText(this).apply {
            hint = "Server URL, e.g. ws://192.168.1.10:8765"
            setText(SubtitleManager.settings.serverUrl)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
            setBackgroundResource(R.drawable.setting_row_background)
            setTextColor(ContextCompat.getColorStateList(this@MainActivity, R.color.setting_row_text))
            setHintTextColor(0xFF90A4AE.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) { saveServerUrl(); false } else false
            }
            setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveServerUrl() }
        }
        rowsContainer.addView(
            serverUrlInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) },
        )

        // Shown at the top, under the main status line, so it is always on screen.
        connectionText = TextView(this).apply {
            setTextColor(0xFFB0BEC5.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, 0, 0, dp(16))
        }
        rowsContainer.addView(connectionText, 0)
    }

    private fun saveServerUrl() {
        val raw = serverUrlInput.text.toString().trim()
        if (raw == SubtitleManager.settings.serverUrl) return
        if (raw.isNotEmpty() && SubtitleRepository.normalizeUrl(raw) == null) {
            Toast.makeText(this, "URL must start with ws:// or wss://", Toast.LENGTH_LONG).show()
            return
        }
        SubtitleManager.updateSettings { it.copy(serverUrl = raw) }
    }

    private fun refreshConnection() {
        val settings = SubtitleManager.settings
        val stats = SubtitleRepository.stats
        connectionText.text = when {
            !settings.serverEnabled -> "Server: off"
            !settings.enabled -> "Server: connects when subtitles are on"
            else -> "Server: ${SubtitleRepository.status.describe()}\n" +
                "Messages: ${stats.received} received · ${stats.shown} shown · " +
                "${stats.droppedOutdated} outdated ignored"
        }
    }

    private fun refresh() {
        val settings = SubtitleManager.settings
        val granted = Settings.canDrawOverlays(this)
        toggleButton.text = if (settings.enabled) "Subtitles: ON  (press OK to stop)"
        else "Subtitles: OFF  (press OK to start)"
        statusText.text = when {
            !granted -> "Overlay permission is required to show subtitles over other apps."
            settings.enabled -> "Subtitles are running. They stay visible on the home screen and HDMI inputs."
            else -> "Subtitles are stopped."
        }
        settingRows.forEach { it.refresh(settings) }
        if (!serverUrlInput.hasFocus() && serverUrlInput.text.toString() != settings.serverUrl) {
            serverUrlInput.setText(settings.serverUrl) // e.g. changed over ADB
        }
        refreshConnection()
    }

    private fun toggleSubtitles() {
        if (SubtitleManager.settings.enabled) {
            SubtitleOverlayService.stop(this)
        } else if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
        } else {
            SubtitleOverlayService.start(this)
        }
    }

    private fun showNextSample() {
        val (original, translated) = SAMPLES[sampleIndex % SAMPLES.size]
        sampleIndex++
        SubtitleManager.setSubtitle(Subtitle(translated, original))
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // Some TV builds hide this settings screen; ADB can grant it instead.
            Toast.makeText(
                this,
                "Run: adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun addButton(text: String, onClick: () -> Unit): Button =
        createRowButton().apply {
            this.text = text
            setOnClickListener { onClick() }
            rowsContainer.addView(this)
        }

    private fun createRowButton() = Button(this).apply {
        isAllCaps = false
        isFocusable = true
        setBackgroundResource(R.drawable.setting_row_background)
        setTextColor(ContextCompat.getColorStateList(this@MainActivity, R.color.setting_row_text))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        val pad = dp(16)
        setPadding(pad, dp(10), pad, dp(10))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(8) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** A focusable row that cycles through [options] with D-pad left/right or OK. */
    private inner class ChoiceRow<T>(
        private val label: String,
        private val options: List<T>,
        private val format: (T) -> String,
        private val get: (SubtitleSettings) -> T,
        private val set: (SubtitleSettings, T) -> SubtitleSettings,
    ) {
        val button: Button = createRowButton().apply {
            setOnClickListener { step(+1) }
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { step(-1); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { step(+1); true }
                    else -> false
                }
            }
        }

        fun refresh(settings: SubtitleSettings) {
            button.text = "$label:   ◀  ${format(get(settings))}  ▶"
        }

        private fun step(delta: Int) {
            SubtitleManager.updateSettings { current ->
                val index = options.indexOf(get(current)).coerceAtLeast(0)
                val next = Math.floorMod(index + delta, options.size)
                set(current, options[next])
            }
        }
    }

    private companion object {
        val SAMPLES = listOf(
            "We need to move now." to "我们现在必须行动。",
            "Is anyone there?" to "有人在吗？",
            "Hold the line, reinforcements are on the way." to "守住防线，援军正在赶来。",
            "I've never seen anything like this before." to "我以前从没见过这样的东西。",
            "AI Subtitle Test" to "中文字幕测试",
        )
    }
}
