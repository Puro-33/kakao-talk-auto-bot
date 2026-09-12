package com.example.kakaotalkautobot

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
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
    private var pendingReadToken: String? = null
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
                    pendingReadToken = null
                    hideOverlay()
                }
            }
            else -> {
                kakaoWindowShown = false
                pendingReadToken = null
                hideOverlay()
            }
        }
    }

    private fun showOverlay(token: String, messageResource: Int = R.string.room_selection_overlay_instruction) {
        if (!RoomSelectionSession.isActive(token)) return
        if (pendingReadToken != null) return
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
        trace("SELECTION_REQUESTED")
        if (!RoomSelectionSession.isActive(token)) {
            hideOverlay()
            return
        }
        // Remove the just-touched overlay before querying the underlying active Kakao window.
        hideOverlay()
        pendingReadToken = token
        handler.postDelayed({
            if (pendingReadToken == token) {
                pendingReadToken = null
                readSelectedRoom(token)
            } else {
                trace("SELECTION_READ_CANCELLED")
            }
        }, 150)
    }

    private fun readSelectedRoom(token: String) {
        if (!RoomSelectionSession.isActive(token)) return
        if (!kakaoWindowShown) return
        val visibleWindows = try { windows } catch (_: RuntimeException) {
            reportFailure(token, "WINDOWS_UNAVAILABLE", R.string.room_selection_overlay_window_unavailable)
            return
        }
        // Select the current application window first, then inspect only that root's package.
        // Never search background windows for Kakao after finding another foreground app.
        val applications = visibleWindows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val active = applications.filter { it.isActive }
        val candidates = active.ifEmpty { applications.filter { it.isFocused } }
        if (candidates.size != 1) {
            reportFailure(token, if (candidates.isEmpty()) "NO_CURRENT_APPLICATION" else "AMBIGUOUS_CURRENT_APPLICATION",
                R.string.room_selection_overlay_window_unavailable)
            return
        }
        val root = try { candidates.single().root } catch (_: RuntimeException) {
            reportFailure(token, "WINDOW_ROOT_EXCEPTION", R.string.room_selection_overlay_root_unavailable)
            return
        }
        if (root == null) {
            reportFailure(token, "WINDOW_ROOT_UNAVAILABLE", R.string.room_selection_overlay_root_unavailable)
            return
        }
        if (root.packageName?.toString() != "com.kakao.talk") {
            kakaoWindowShown = false
            reportFailure(token, "CURRENT_APPLICATION_NOT_KAKAO", R.string.room_selection_overlay_wrong_app)
            return
        }
        val result = KakaoChatTitleReader.diagnose(root)
        val title = result.title
        if (title.isNullOrBlank()) {
            val code = result.failure?.name ?: "UNKNOWN_TITLE_FAILURE"
            val message = when {
                code == "ROOT_UNAVAILABLE" || code == "NODE_UNAVAILABLE" -> R.string.room_selection_overlay_root_unavailable
                code.endsWith("NOT_KAKAO") -> R.string.room_selection_overlay_wrong_app
                code.endsWith("HIDDEN") -> R.string.room_selection_overlay_hidden
                code.endsWith("AMBIGUOUS") -> R.string.room_selection_overlay_ambiguous
                code == "CHAT_LOG_MISSING" -> R.string.room_selection_overlay_chat_missing
                code == "INPUT_MISSING" -> R.string.room_selection_overlay_input_missing
                code == "TOOLBAR_MISSING" -> R.string.room_selection_overlay_toolbar_missing
                code == "TITLE_MISSING" -> R.string.room_selection_overlay_title_missing
                code == "TITLE_EMPTY_OR_TOO_LONG" -> R.string.room_selection_overlay_title_invalid
                else -> R.string.room_selection_overlay_no_chat
            }
            reportFailure(token, code, message)
            return
        }
        if (!RoomSelectionSession.select(token, title)) {
            hideOverlay()
            return
        }
        trace("SELECTION_READY")
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

    private fun reportFailure(token: String, code: String, messageResource: Int) {
        trace(code)
        if (kakaoWindowShown) showOverlay(token, messageResource)
    }

    private fun trace(code: String) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // Fixed diagnostic codes only: never titles, node text, packages or exception messages.
            Log.d("RoomSelection", code)
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

    override fun onInterrupt() {
        pendingReadToken = null
        hideOverlay()
    }

    override fun onDestroy() {
        pendingReadToken = null
        hideOverlay()
        RoomSelectionSession.cancelCurrent()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
