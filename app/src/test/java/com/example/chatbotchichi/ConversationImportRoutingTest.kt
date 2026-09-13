package com.example.kakaotalkautobot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationImportRoutingTest {
    @Test fun usesExportTitleWhenPresent() {
        assertEquals(
            "지정된 방",
            ConversationImportRouting.suggestedTitle(" 지정된 방 ", listOf("나", "상대"), "나")
        )
    }

    @Test fun derivesTitleFromOtherParticipantsWhenCurrentExportHasNoRoomTitle() {
        assertEquals(
            "상대A, 상대B",
            ConversationImportRouting.suggestedTitle(null, listOf("나", "상대A", "상대B", "상대A"), "나")
        )
    }

    @Test fun matchesOnlySameTitleAndCompatibleSelfIdentity() {
        val matching = room("matching", " 연습  방 ", selfName = null, selectedByTitle = true)
        val unconfirmed = room("unconfirmed", "연습 방", selfName = null)
        val sameTitleWrongSelf = room("wrong-self", "연습 방", selfName = "다른 본인")
        val unrelated = room("unrelated", "다른 방", selfName = null)

        assertEquals(
            listOf("matching"),
            ConversationImportRouting.matchingRooms(
                "연습 방", "나", listOf(matching, unconfirmed, sameTitleWrongSelf, unrelated)
            ).map { it.id }
        )
    }

    @Test fun duplicateTitlesStayAmbiguousAndMissingTitleDoesNotMatch() {
        val rooms = listOf(
            room("one", "동명 방", selectedByTitle = true),
            room("two", "동명 방", selfName = "나")
        )
        assertEquals(2, ConversationImportRouting.matchingRooms("동명 방", "나", rooms).size)
        assertTrue(ConversationImportRouting.matchingRooms(null, "나", rooms).isEmpty())
    }

    @Test fun previouslyImportedSameIdentityCanBeMatchedAgain() {
        val imported = room("imported", "기존 방", selfName = "나")
        assertEquals(
            listOf("imported"),
            ConversationImportRouting.matchingRooms("기존 방", "나", listOf(imported)).map { it.id }
        )
    }

    @Test fun manualPickerListsScreenSelectedRoomsFirst() {
        val rooms = listOf(
            room("ordinary", "가 방"),
            room("selected", "나 방", selectedByTitle = true)
        )
        assertEquals(
            listOf("selected", "ordinary"),
            ConversationImportRouting.compatibleRooms("나", rooms).map { it.id }
        )
    }

    private fun room(
        id: String,
        title: String,
        selfName: String? = null,
        selectedByTitle: Boolean = false
    ) = ConversationSummary(id, title, false, selfName, null, 0, selectedByTitle)
}
