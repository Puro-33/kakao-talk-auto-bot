package com.example.kakaotalkautobot

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Reads the current chat title only when the user presses the temporary selection overlay. */
class KakaoRoomSelectionService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var overlayToken: String? = null
    private var kakaoWindowShown = false
    private val expiryCheck = object : Runnable {
        override fun run() {
            val token = overlayToken ?: return
            if (!RoomSelectionSession.isActive(token)) hideOverlay()
            else handler.postDelayed(this, 1_000)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val session = RoomSelectionSession.active()
        if (session == null) {
            hideOverlay()
            return
        }
        // Only the event package is inspected here. Screen content is never polled.
        when (event.packageName?.toString()) {
            "com.kakao.talk" -> {
                kakaoWindowShown = true
                showOverlay(session.token)
            }
            packageName -> {
                // Ignore our overlay's widget event, but hide when an app activity returns.
                if (event.className?.toString()?.startsWith("$packageName.") == true) {
                    kakaoWindowShown = false
                    hideOverlay()
                }
            }
            else -> {
                kakaoWindowShown = false
                hideOverlay()
            }
        }
    }

    private fun showOverlay(token: String, messageResource: Int = R.string.room_selection_overlay_instruction) {
        if (!RoomSelectionSession.isActive(token)) return
        if (overlay != null && overlayToken == token) return
        hideOverlay()
        val status = TextView(this).apply {
            setText(messageResource)
            textSize = 14f
            setTextColor(android.graphics.Color.WHITE)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(android.graphics.Color.rgb(35, 38, 45))
            addView(status)
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply {
            setText(R.string.room_selection_overlay_select)
            minHeight = dp(48)
            setOnClickListener { selectCurrentRoom(token) }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(Button(this).apply {
            setText(R.string.room_selection_overlay_cancel)
            minHeight = dp(48)
            setOnClickListener {
                RoomSelectionSession.cancel(token)
                hideOverlay()
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(actions)
        val params = WindowManager.LayoutParams(
            minOf(dp(340), resources.displayMetrics.widthPixels - dp(24)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(96)
        }
        try {
            getSystemService(WindowManager::class.java).addView(panel, params)
            overlay = panel
            overlayToken = token
            handler.postDelayed(expiryCheck, 1_000)
        } catch (_: WindowManager.BadTokenException) {
            // Service may be disconnecting; no selection or content access occurs.
        } catch (_: SecurityException) {
            // The system controls whether this accessibility window can be displayed.
        }
    }

    private fun selectCurrentRoom(token: String) {
        if (!RoomSelectionSession.isActive(token)) {
            hideOverlay()
            return
        }
        // Remove the just-touched overlay before querying the underlying active Kakao window.
        hideOverlay()
        handler.post { readSelectedRoom(token) }
    }

    private fun readSelectedRoom(token: String) {
        if (!RoomSelectionSession.isActive(token)) return
        val root = rootInActiveWindow
        val title = if (root?.packageName?.toString() == "com.kakao.talk") {
            runCatching { KakaoChatTitleReader.read(root) }.getOrNull()
        } else null
        if (title.isNullOrBlank()) {
            // Do not re-display over another app if the user switched while the read was queued.
            if (root?.packageName?.toString() == "com.kakao.talk" || (root == null && kakaoWindowShown)) {
                showOverlay(token, R.string.room_selection_overlay_no_chat)
            }
            return
        }
        if (!RoomSelectionSession.select(token, title)) {
            hideOverlay()
            return
        }
        try {
            startActivity(Intent(this, RoomSelectionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(RoomSelectionSession.EXTRA_SESSION_TOKEN, token))
        } catch (_: ActivityNotFoundException) {
            // Keep the result for explicit confirmation after returning to the app manually.
        } catch (_: SecurityException) {
            // Some devices restrict background launches; manual return remains available.
        }
    }

    private fun hideOverlay() {
        handler.removeCallbacks(expiryCheck)
        val view = overlay
        overlay = null
        overlayToken = null
        if (view != null) {
            try {
                getSystemService(WindowManager::class.java).removeView(view)
            } catch (_: IllegalArgumentException) {
                // The system may already have removed the window during disconnection.
            }
        }
    }

    override fun onInterrupt() { hideOverlay() }

    override fun onDestroy() {
        hideOverlay()
        RoomSelectionSession.cancelCurrent()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
