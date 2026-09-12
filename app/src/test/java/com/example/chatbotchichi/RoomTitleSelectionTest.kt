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
        private val textFailure: Boolean = false
    ) : RoomTitleNode {
        var textReads = 0
        override val text: CharSequence? get() {
            textReads++
            check(allowText) { "Only the title node may have its text read" }
            check(!textFailure) { "Title node became unavailable" }
            return title
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
    }

    @Test fun listAndSearchToolbarsCannotSelectARoom() {
        val title = Node(title = "Search heading")
        val list = chat(title).apply { nodes.remove("com.kakao.talk:id/chat_log_recycler_list") }
        assertNull(RoomTitleSelection.read(list))
        val search = chat(title).apply { nodes.remove("com.kakao.talk:id/input_window_layout") }
        assertNull(RoomTitleSelection.read(search))
        assertEquals(0, title.textReads)
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
    }

    @Test fun titleOutsideVerifiedToolbarIsIgnored() {
        val misplaced = Node(title = "Message pretending to be a title")
        val root = chat().apply {
            put("toolbar_default_title_layout", Node())
            put("toolbar_default_title_text", misplaced)
        }
        assertNull(RoomTitleSelection.read(root))
        assertEquals(0, misplaced.textReads)
    }

    @Test fun blankAndOversizedTitlesAreRejected() {
        assertNull(RoomTitleSelection.read(chat(Node(title = "  "))))
        assertNull(RoomTitleSelection.read(chat(Node(title = "x".repeat(513)))))
    }

    private fun assertNoTextRead(root: Node) {
        assertEquals("Unexpected text access on structural node", 0, root.textReads)
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
    }

    @Test fun invalidTitleValuesShareContentFreeFailureAndLengthBoundaryIsAccepted() {
        for (value in listOf(null, "", "  ", "x".repeat(513))) {
            val title = Node(title = value, allowText = true)
            val result = RoomTitleSelection.diagnose(chat(title))
            assertEquals(RoomTitleReadFailure.TITLE_EMPTY_OR_TOO_LONG, result.failure)
            assertNull(result.title)
            assertEquals(1, title.textReads)
        }
        val valid = RoomTitleSelection.diagnose(chat(Node(title = "x".repeat(512))))
        assertEquals("x".repeat(512), valid.title)
        assertNull(valid.failure)
    }
}
