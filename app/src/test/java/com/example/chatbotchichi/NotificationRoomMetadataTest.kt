package com.example.kakaotalkautobot

import org.junit.Assert.*
import org.junit.Test

class NotificationRoomMetadataTest {
    @Test fun shortcutIdentitySurvivesRepostedNotificationAndTitleChange() {
        val first = metadata(shortcut = "stable-room", key = "old", conversation = "Earlier title")!!
        val next = metadata(shortcut = "stable-room", key = "new", conversation = "New title")!!
        assertEquals(first.identity, next.identity)
        assertEquals("New title", next.title)
    }

    @Test fun identicalTitlesDoNotMergeDifferentNotificationKeysOrUsers() {
        assertNotEquals(metadata(key = "one")!!.identity, metadata(key = "two")!!.identity)
        assertNotEquals(metadata(user = 0)!!.identity, metadata(user = 10)!!.identity)
    }

    @Test fun titlesUseSameTrimmedPriorityForLiveAndRefreshPaths() {
        assertEquals("Group", metadata(conversation = " Group ", sub = "Sub")!!.title)
        assertEquals("Sub", metadata(conversation = "  ", sub = " Sub ")!!.title)
        assertEquals("Summary", metadata(conversation = null, sub = null, summary = " Summary ")!!.title)
        assertEquals("Sender", metadata(conversation = null, sub = null, summary = null, title = " Sender ")!!.title)
        assertNull(metadata(conversation = null, sub = " ", summary = null, title = ""))
    }

    private fun metadata(user: Int = 0, shortcut: String? = null, key: String = "key",
        conversation: String? = "Room", sub: String? = null, summary: String? = null, title: String? = null
    ) = NotificationRoomMetadata.from(user, "com.kakao.talk", shortcut, key, conversation, sub, summary, title)
}
