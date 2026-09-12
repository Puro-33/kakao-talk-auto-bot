package com.example.kakaotalkautobot

/** Minimal window interface: only the verified title node's text is accessed. */
internal interface RoomTitleNode {
    val packageName: String?
    val visible: Boolean
    val text: CharSequence?
    fun findById(id: String): List<RoomTitleNode>
}

internal object RoomTitleSelection {
    private const val KAKAO = "com.kakao.talk"
    private fun RoomTitleNode.trusted() = packageName == KAKAO && visible
    private fun RoomTitleNode.unique(id: String): RoomTitleNode? =
        findById("$KAKAO:id/$id").singleOrNull()?.takeIf { it.trusted() }

    fun read(root: RoomTitleNode?): String? {
        if (root == null || !root.trusted()) return null
        // A title in a list, search page, or unrelated toolbar is not a selected chat.
        root.unique("chat_log_recycler_list") ?: return null
        root.unique("input_window_layout") ?: return null
        val toolbar = root.unique("toolbar_default_title_layout") ?: return null
        val title = toolbar.unique("toolbar_default_title_text") ?: return null
        return title.text?.toString()?.trim()?.takeIf { it.isNotEmpty() && it.length <= 512 }
    }
}
