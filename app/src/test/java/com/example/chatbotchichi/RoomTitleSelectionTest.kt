package com.example.kakaotalkautobot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoomTitleSelectionTest {
    private class Node(
        override val packageName: String? = "com.kakao.talk",
        override val visible: Boolean = true,
        private val title: String? = null,
        private val allowText: Boolean = title != null,
        private val queryFailure: Boolean = false,
        private val textFailure: Boolean = false,
        private val description: String? = null,
        private val allowDescription: Boolean = allowText,
        private val descriptionFailure: Boolean = false
    ) : RoomTitleNode {
        var textReads = 0
        var descriptionReads = 0
        override val text: CharSequence? get() {
            textReads++
            check(allowText) { "Only the title node may have its text read" }
            check(!textFailure) { "Title node became unavailable" }
            return title
        }
        override val contentDescription: CharSequence? get() {
            descriptionReads++
            check(allowDescription) { "Only the verified title node may have its description read" }
            check(!descriptionFailure) { "Title description became unavailable" }
            return description
        }
        val nodes = mutableMapOf<String, List<RoomTitleNode>>()
        override fun findById(id: String): List<RoomTitleNode> {
            check(!queryFailure) { "Window became unavailable" }
            return nodes[id].orEmpty()
        }
        fun put(id: String, vararg children: RoomTitleNode) { nodes["com.kakao.talk:id/$id"] = children.toList() }
    }

    private fun chat(title: Node = Node(title = "  Selected room  ")): Node = Node().apply {
        put("chat_log_recycler_list", Node())
        put("input_window_layout", Node())
        put("toolbar_default_title_layout", Node().apply { put("toolbar_default_title_text", title) })
    }

    @Test fun confirmedChatReadsOnlyItsTitle() {
        val title = Node(title = "  Selected room  ")
        assertEquals("Selected room", RoomTitleSelection.read(chat(title)))
        assertEquals(1, title.textReads)
        assertEquals(0, title.descriptionReads)
    }

    @Test fun listAndSearchToolbarsCannotSelectARoom() {
        val title = Node(title = "Search heading")
        val list = chat(title).apply { nodes.remove("com.kakao.talk:id/chat_log_recycler_list") }
        assertNull(RoomTitleSelection.read(list))
        val search = chat(title).apply { nodes.remove("com.kakao.talk:id/input_window_layout") }
        assertNull(RoomTitleSelection.read(search))
        assertEquals(0, title.textReads)
        assertEquals(0, title.descriptionReads)
    }

    @Test fun foreignHiddenAndMissingWindowsAreRejected() {
        assertNull(RoomTitleSelection.read(null))
        assertNull(RoomTitleSelection.read(Node(packageName = "other.app")))
        assertNull(RoomTitleSelection.read(Node(visible = false)))
        assertNull(RoomTitleSelection.read(chat(Node(packageName = "other.app", title = "Wrong"))))
        assertNull(RoomTitleSelection.read(chat(Node(visible = false, title = "Hidden"))))
    }

    @Test fun duplicateTitleOrToolbarIsNotGuessed() {
        val a = Node(title = "A")
        val b = Node(title = "B")
        val toolbar = Node().apply { put("toolbar_default_title_text", a, b) }
        assertNull(RoomTitleSelection.read(chat().apply { put("toolbar_default_title_layout", toolbar) }))
        assertNull(RoomTitleSelection.read(chat().apply { put("toolbar_default_title_layout", toolbar, Node()) }))
        assertEquals(0, a.textReads + b.textReads)
        assertEquals(0, a.descriptionReads + b.descriptionReads)
    }

    @Test fun titleOutsideVerifiedToolbarIsIgnored() {
        val misplaced = Node(title = "Message pretending to be a title")
        val root = chat().apply {
            put("toolbar_default_title_layout", Node())
            put("toolbar_default_title_text", misplaced)
        }
        assertNull(RoomTitleSelection.read(root))
        assertEquals(0, misplaced.textReads)
        assertEquals(0, misplaced.descriptionReads)
    }

    @Test fun blankAndOversizedTitlesAreRejected() {
        assertNull(RoomTitleSelection.read(chat(Node(title = "  "))))
        assertNull(RoomTitleSelection.read(chat(Node(title = "x".repeat(513)))))
    }

    private fun assertNoTextRead(root: Node) {
        assertEquals("Unexpected text access on structural node", 0, root.textReads)
        assertEquals("Unexpected description access on structural node", 0, root.descriptionReads)
        root.nodes.values.flatten().forEach { assertNoTextRead(it as Node) }
    }

    @Test fun rootFailuresHaveDistinctReasonsWithoutReadingText() {
        assertEquals(RoomTitleReadFailure.ROOT_UNAVAILABLE, RoomTitleSelection.diagnose(null).failure)
        val foreign = Node(packageName = "other.app")
        val hidden = Node(visible = false)
        assertEquals(RoomTitleReadFailure.NOT_KAKAO, RoomTitleSelection.diagnose(foreign).failure)
        assertEquals(RoomTitleReadFailure.ROOT_HIDDEN, RoomTitleSelection.diagnose(hidden).failure)
        assertNoTextRead(foreign)
        assertNoTextRead(hidden)
    }

    @Test fun everyStructuralStageSeparatesMissingDuplicateForeignAndHidden() {
        val stages = listOf(
            "chat_log_recycler_list" to "CHAT_LOG",
            "input_window_layout" to "INPUT",
            "toolbar_default_title_layout" to "TOOLBAR",
            "toolbar_default_title_text" to "TITLE"
        )
        for ((id, prefix) in stages) {
            val faults = listOf(
                "MISSING" to emptyList(),
                "AMBIGUOUS" to listOf(Node(), Node(visible = false)),
                "NOT_KAKAO" to listOf(Node(packageName = "other.app")),
                "HIDDEN" to listOf(Node(visible = false))
            )
            for ((suffix, replacement) in faults) {
                val title = Node(title = "Must stay unread")
                val root = chat(title)
                val parent = if (prefix == "TITLE") {
                    root.nodes.getValue("com.kakao.talk:id/toolbar_default_title_layout").single() as Node
                } else root
                parent.nodes["com.kakao.talk:id/$id"] = replacement
                val result = RoomTitleSelection.diagnose(root)
                assertEquals("$prefix $suffix", RoomTitleReadFailure.valueOf("${prefix}_$suffix"), result.failure)
                assertNull(result.title)
                assertNoTextRead(root)
                assertEquals(0, title.textReads)
                assertEquals(0, title.descriptionReads)
            }
        }
    }

    @Test fun unavailableNodesFailClosedWithoutFallbackTextSearch() {
        val root = Node(queryFailure = true)
        assertEquals(RoomTitleReadFailure.NODE_UNAVAILABLE, RoomTitleSelection.diagnose(root).failure)
        assertNoTextRead(root)
        val title = Node(title = "Hidden by failure", textFailure = true)
        val result = RoomTitleSelection.diagnose(chat(title))
        assertEquals(RoomTitleReadFailure.NODE_UNAVAILABLE, result.failure)
        assertNull(result.title)
        assertEquals(1, title.textReads)
        assertEquals(0, title.descriptionReads)
        val missingDescription = Node(title = "", descriptionFailure = true)
        val unavailable = RoomTitleSelection.diagnose(chat(missingDescription))
        assertEquals(RoomTitleReadFailure.NODE_UNAVAILABLE, unavailable.failure)
        assertNull(unavailable.title)
        assertEquals(1, missingDescription.descriptionReads)
    }

    @Test fun nullOrBlankTextUsesOnlyTheVerifiedTitleDescription() {
        for (value in listOf(null, "", "  ")) {
            val title = Node(title = value, allowText = true, description = "  Selected room  ")
            val result = RoomTitleSelection.diagnose(chat(title))
            assertEquals("Selected room", result.title)
            assertNull(result.failure)
            assertEquals(1, title.textReads)
            assertEquals(1, title.descriptionReads)
        }
    }

    @Test fun validTextTakesPriorityWithoutAccessingDescription() {
        val title = Node(title = "  Visible title  ", description = "Other title", descriptionFailure = true)
        assertEquals("Visible title", RoomTitleSelection.read(chat(title)))
        assertEquals(1, title.textReads)
        assertEquals(0, title.descriptionReads)
    }

    @Test fun oversizedNonblankTextRejectsWithoutDescriptionFallbackOrTruncation() {
        val title = Node(title = "x".repeat(513), description = "Short title", descriptionFailure = true)
        val result = RoomTitleSelection.diagnose(chat(title))
        assertNull(result.title)
        assertEquals(RoomTitleReadFailure.TITLE_TOO_LONG, result.failure)
        assertEquals(0, title.descriptionReads)
    }

    @Test fun bothEmptyTitleSourcesProduceEmptyFailure() {
        for (text in listOf(null, "", "  ")) {
            for (description in listOf(null, "", "  ")) {
                val title = Node(title = text, allowText = true, description = description)
                val result = RoomTitleSelection.diagnose(chat(title))
                assertEquals(RoomTitleReadFailure.TITLE_EMPTY, result.failure)
                assertNull(result.title)
                assertEquals(1, title.textReads)
                assertEquals(1, title.descriptionReads)
            }
        }
    }

    @Test fun descriptionUsesTheSameLengthLimitAsText() {
        val valid = RoomTitleSelection.diagnose(chat(Node(title = "", description = "x".repeat(512))))
        assertEquals("x".repeat(512), valid.title)
        assertNull(valid.failure)
        val tooLong = RoomTitleSelection.diagnose(chat(Node(title = "", description = "x".repeat(513))))
        assertEquals(RoomTitleReadFailure.TITLE_TOO_LONG, tooLong.failure)
        assertNull(tooLong.title)
    }

    @Test fun textLengthBoundaryIsAccepted() {
        val valid = RoomTitleSelection.diagnose(chat(Node(title = "x".repeat(512))))
        assertEquals("x".repeat(512), valid.title)
        assertNull(valid.failure)
    }
}
