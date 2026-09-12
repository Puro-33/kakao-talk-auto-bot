package com.example.kakaotalkautobot

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Returns a room title only after the user confirms the explicitly selected Kakao row. */
class RoomSelectionActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_ROOM_TITLE = "roomTitle"
        const val EXTRA_SESSION_TOKEN = "selectionToken"
        private const val STATE_TOKEN = "ownedSelectionToken"
    }

    private var sessionToken: String? = null
    private var timeoutJob: Job? = null
    private lateinit var status: TextView
    private lateinit var confirm: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        sessionToken = savedInstanceState?.getString(STATE_TOKEN)
        title = getString(R.string.room_selection_title)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        setContentView(ScrollView(this).apply {
            id = R.id.room_selection_scroll
            fitsSystemWindows = true
            addView(content)
        })
        content.addView(label(getString(R.string.room_selection_title), 24f))
        content.addView(label(getString(R.string.room_selection_disclosure)))
        content.addView(label(getString(R.string.room_selection_steps)))
        content.addView(label(getString(R.string.room_selection_name_limit)))
        content.addView(button(R.string.room_selection_accessibility_settings, R.id.room_selection_settings) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        content.addView(button(R.string.room_selection_start, R.id.room_selection_start) { startSelection() })
        status = label(getString(R.string.room_selection_ready)).apply {
            id = R.id.room_selection_status
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(status)
        confirm = button(R.string.room_selection_confirm, R.id.room_selection_confirm) {
            val token = sessionToken ?: return@button
            val title = RoomSelectionSession.selectedTitle(token)
            if (title.isNullOrBlank()) {
                updateSelection()
                return@button
            }
            RoomSelectionSession.cancel(token)
            sessionToken = null
            setResult(RESULT_OK, Intent().putExtra(EXTRA_ROOM_TITLE, title))
            finish()
        }.apply { isEnabled = false }
        content.addView(confirm)
        content.addView(button(android.R.string.cancel, R.id.room_selection_cancel) { finish() })
    }

    override fun onResume() {
        super.onResume()
        updateSelection()
        timeoutJob?.cancel()
        timeoutJob = lifecycleScope.launch {
            while (sessionToken != null) {
                delay(1_000)
                updateSelection()
            }
        }
    }

    override fun onPause() {
        timeoutJob?.cancel()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getStringExtra(EXTRA_SESSION_TOKEN) == sessionToken) updateSelection()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_TOKEN, sessionToken)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (isFinishing) sessionToken?.let(RoomSelectionSession::cancel)
        super.onDestroy()
    }

    private fun startSelection() {
        if (!selectionServiceEnabled()) {
            status.setText(R.string.room_selection_permission_needed)
            return
        }
        val kakao = packageManager.getLaunchIntentForPackage("com.kakao.talk")
        if (kakao == null) {
            status.setText(R.string.room_selection_kakao_missing)
            return
        }
        sessionToken?.let(RoomSelectionSession::cancel)
        sessionToken = RoomSelectionSession.begin()
        confirm.isEnabled = false
        status.setText(R.string.room_selection_waiting)
        try {
            startActivity(kakao)
        } catch (_: android.content.ActivityNotFoundException) {
            sessionToken?.let(RoomSelectionSession::cancel)
            sessionToken = null
            status.setText(R.string.room_selection_kakao_missing)
        }
    }

    private fun updateSelection() {
        val token = sessionToken ?: return
        val selectedTitle = RoomSelectionSession.selectedTitle(token)
        if (!selectedTitle.isNullOrBlank()) {
            status.text = getString(R.string.room_selection_selected_title, selectedTitle)
            confirm.isEnabled = true
        } else if (!RoomSelectionSession.isActive(token)) {
            RoomSelectionSession.cancel(token)
            sessionToken = null
            confirm.isEnabled = false
            status.setText(R.string.room_selection_expired)
        } else {
            confirm.isEnabled = false
            status.setText(R.string.room_selection_waiting)
        }
    }

    private fun selectionServiceEnabled(): Boolean {
        val manager = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val expected = ComponentName(this, KakaoRoomSelectionService::class.java)
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val service = info.resolveInfo.serviceInfo
                ComponentName(service.packageName, service.name) == expected
            }
    }

    private fun label(value: String, size: Float = 16f) = TextView(this).apply {
        text = value
        textSize = size
        setPadding(0, 0, 0, dp(16))
    }

    private fun button(textResource: Int, viewId: Int, action: () -> Unit) = MaterialButton(this).apply {
        id = viewId
        setText(textResource)
        minHeight = dp(48)
        setOnClickListener { action() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
