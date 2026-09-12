package com.example.kakaotalkautobot

import org.junit.Assert.*
import org.junit.Test

class RoomSelectionSessionTest {
    private var now = 1_000L
    private val state = RoomSelectionSessionState { now }

    @Test fun explicitSessionAcceptsOneTitleAndStopsWatching() {
        assertNull(state.active())
        val token = state.begin()
        assertTrue(state.select(token, "  Selected room  "))
        assertEquals("Selected room", state.selectedTitle(token))
        assertNull(state.active())
        assertFalse(state.select(token, "Another room"))
    }

    @Test fun timeoutClearsPendingAndSelectedResults() {
        val pending = state.begin()
        now += 120_000
        assertNull(state.active())
        assertFalse(state.select(pending, "Room"))
        val selected = state.begin()
        assertTrue(state.select(selected, "Room"))
        now += 120_000
        assertNull(state.selectedTitle(selected))
    }

    @Test fun cancelClearsSelectionAndOldTokenCannotCancelNewSession() {
        val old = state.begin()
        val next = state.begin()
        state.cancel(old)
        assertEquals(next, state.active()?.token)
        assertFalse(state.select(old, "Wrong room"))
        assertTrue(state.select(next, "Room"))
        state.cancel(next)
        assertNull(state.selectedTitle(next))
    }

    @Test fun blankOrUnboundedTitleCannotFinishSelection() {
        val token = state.begin()
        assertFalse(state.select(token, "  "))
        assertFalse(state.select(token, "a".repeat(513)))
        assertEquals(token, state.active()?.token)
    }

    @Test fun clockResetAndServiceStopInvalidateSession() {
        state.begin()
        now = 0
        assertNull(state.active())
        val token = state.begin()
        state.cancelCurrent()
        assertFalse(state.select(token, "Room"))
    }
}
