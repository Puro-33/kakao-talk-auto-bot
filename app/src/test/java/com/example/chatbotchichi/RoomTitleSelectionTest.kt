package com.example.kakaotalkautobot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoomTitleSelectionTest {
    private class Node(
        override val packageName: String? = "com.kakao.talk",
        override val visible: Boolean = true,
        private val title: String? = null
    ) : RoomTitleNode {
        var textReads = 0
        override val text: CharSequence? get() {
            check(title != null) { "Only the title node may have its text read" }
            textReads++
            return title
        }
        val nodes = mutableMapOf<String, List<RoomTitleNode>>()
        override fun findById(id: String) = nodes[id].orEmpty()
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
}
