package com.example.kakaotalkautobot

import android.os.SystemClock
import java.util.UUID

/** Explicit, short-lived selection only. Nothing survives process death. */
object RoomSelectionSession {
    const val EXTRA_SESSION_TOKEN = "selectionToken"
    private val state = RoomSelectionSessionState { SystemClock.elapsedRealtime() }

    fun begin(): String = state.begin()
    fun cancel(token: String) = state.cancel(token)
    fun selectedTitle(token: String): String? = state.selectedTitle(token)
    fun isActive(token: String): Boolean = state.active()?.token == token
    internal fun active(): RoomSelectionSessionState.Active? = state.active()
    internal fun select(token: String, title: String): Boolean = state.select(token, title)
    internal fun cancelCurrent() = state.cancelCurrent()
}

/** Clock-injected state machine so expiration and duplicate events have ordinary JVM tests. */
internal class RoomSelectionSessionState(private val clock: () -> Long) {
    internal data class Active(val token: String, val startedAtMillis: Long)
    private data class Session(val active: Active, var title: String? = null)
    private var session: Session? = null

    @Synchronized fun begin(): String {
        val active = Active(UUID.randomUUID().toString(), clock())
        session = Session(active)
        return active.token
    }

    @Synchronized fun cancel(token: String) {
        if (session?.active?.token == token) session = null
    }

    @Synchronized fun cancelCurrent() { session = null }

    @Synchronized fun active(): Active? = current()?.takeIf { it.title == null }?.active

    @Synchronized fun selectedTitle(token: String): String? =
        current()?.takeIf { it.active.token == token }?.title

    @Synchronized fun select(token: String, title: String): Boolean {
        val current = current() ?: return false
        val cleanTitle = title.trim()
        if (current.active.token != token || current.title != null || cleanTitle.isEmpty() || cleanTitle.length > 512) {
            return false
        }
        current.title = cleanTitle
        return true
    }

    private fun current(): Session? {
        val current = session ?: return null
        val elapsed = clock() - current.active.startedAtMillis
        if (elapsed < 0L || elapsed >= 120_000L) {
            session = null
            return null
        }
        return current
    }
}
