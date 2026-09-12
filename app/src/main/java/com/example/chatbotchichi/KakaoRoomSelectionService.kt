package com.example.kakaotalkautobot

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Reads the selected row name during an explicit selection session; never performs UI actions. */
class KakaoRoomSelectionService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val session = RoomSelectionSession.active() ?: return
        if (event?.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED || event.packageName?.toString() != "com.kakao.talk") return
        val nowUptime = SystemClock.uptimeMillis()
        val eventElapsed = SystemClock.elapsedRealtime() - (nowUptime - event.eventTime)
        if (event.eventTime > nowUptime || eventElapsed < session.startedAtMillis) return
        val source = event.source ?: return
        if (!source.refresh()) return
        val title = selectedRowTitle(source) ?: return
        if (!RoomSelectionSession.select(session.token, title)) return
        try {
            startActivity(Intent(this, RoomSelectionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(RoomSelectionSession.EXTRA_SESSION_TOKEN, session.token))
        } catch (_: ActivityNotFoundException) {
            // Keep the result for the user to confirm when returning to the app manually.
        } catch (_: SecurityException) {
            // Some devices restrict activity launches; selection remains available in the app.
        }
    }

    private fun selectedRowTitle(source: AccessibilityNodeInfo): String? {
        var row: AccessibilityNodeInfo? = source
        for (depth in 0..6) {
            val candidate = row ?: return null
            if (candidate.packageName?.toString() != "com.kakao.talk") return null
            if (candidate.viewIdResourceName == "com.kakao.talk:id/chat_roomLayout") {
                return uniqueName(candidate)
            }
            row = candidate.parent
        }
        return null
    }

    private fun uniqueName(row: AccessibilityNodeInfo): String? {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(row)
        var visited = 0
        var name: AccessibilityNodeInfo? = null
        while (pending.isNotEmpty()) {
            if (++visited > 128) return null
            val node = pending.removeFirst()
            if (node.packageName?.toString() != "com.kakao.talk") return null
            if (node.viewIdResourceName == "com.kakao.talk:id/name") {
                if (name != null) return null
                name = node
            }
            if (node.childCount > 128 - visited - pending.size) return null
            for (index in 0 until node.childCount) {
                pending.addLast(node.getChild(index) ?: return null)
            }
        }
        // Text is read only from the one verified name node, not from messages or descriptions.
        return name?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    override fun onInterrupt() { RoomSelectionSession.cancelCurrent() }

    override fun onDestroy() {
        RoomSelectionSession.cancelCurrent()
        super.onDestroy()
    }
}
