package com.example.kakaotalkautobot

/** Only the verified title node's text or, if blank, its own description is accessed. */
internal interface RoomTitleNode {
    val packageName: String?
    val visible: Boolean
    val text: CharSequence?
    val contentDescription: CharSequence?
    fun findById(id: String): List<RoomTitleNode>
}

/** Metadata-only failure codes; no accessibility text is included in diagnostics. */
internal enum class RoomTitleReadFailure {
    ROOT_UNAVAILABLE, NOT_KAKAO, ROOT_HIDDEN,
    CHAT_LOG_MISSING, CHAT_LOG_AMBIGUOUS, CHAT_LOG_NOT_KAKAO, CHAT_LOG_HIDDEN,
    INPUT_MISSING, INPUT_AMBIGUOUS, INPUT_NOT_KAKAO, INPUT_HIDDEN,
    TOOLBAR_MISSING, TOOLBAR_AMBIGUOUS, TOOLBAR_NOT_KAKAO, TOOLBAR_HIDDEN,
    TITLE_MISSING, TITLE_AMBIGUOUS, TITLE_NOT_KAKAO, TITLE_HIDDEN,
    TITLE_EMPTY, TITLE_TOO_LONG, NODE_UNAVAILABLE
}

internal data class RoomTitleReadResult(val title: String?, val failure: RoomTitleReadFailure?) {
    init { require((title == null) != (failure == null)) }
}

internal object RoomTitleSelection {
    private const val KAKAO = "com.kakao.talk"

    private enum class Part(
        val id: String,
        val missing: RoomTitleReadFailure,
        val ambiguous: RoomTitleReadFailure,
        val foreign: RoomTitleReadFailure,
        val hidden: RoomTitleReadFailure
    ) {
        CHAT_LOG("chat_log_recycler_list", RoomTitleReadFailure.CHAT_LOG_MISSING,
            RoomTitleReadFailure.CHAT_LOG_AMBIGUOUS, RoomTitleReadFailure.CHAT_LOG_NOT_KAKAO,
            RoomTitleReadFailure.CHAT_LOG_HIDDEN),
        INPUT("input_window_layout", RoomTitleReadFailure.INPUT_MISSING,
            RoomTitleReadFailure.INPUT_AMBIGUOUS, RoomTitleReadFailure.INPUT_NOT_KAKAO,
            RoomTitleReadFailure.INPUT_HIDDEN),
        TOOLBAR("toolbar_default_title_layout", RoomTitleReadFailure.TOOLBAR_MISSING,
            RoomTitleReadFailure.TOOLBAR_AMBIGUOUS, RoomTitleReadFailure.TOOLBAR_NOT_KAKAO,
            RoomTitleReadFailure.TOOLBAR_HIDDEN),
        TITLE("toolbar_default_title_text", RoomTitleReadFailure.TITLE_MISSING,
            RoomTitleReadFailure.TITLE_AMBIGUOUS, RoomTitleReadFailure.TITLE_NOT_KAKAO,
            RoomTitleReadFailure.TITLE_HIDDEN)
    }

    fun read(root: RoomTitleNode?): String? = diagnose(root).title

    fun diagnose(root: RoomTitleNode?): RoomTitleReadResult = try {
        diagnoseAvailable(root)
    } catch (_: RuntimeException) {
        // A stale/inaccessible node is a failure, never a reason to broaden the query.
        failed(RoomTitleReadFailure.NODE_UNAVAILABLE)
    }

    private fun failed(reason: RoomTitleReadFailure) = RoomTitleReadResult(null, reason)

    private fun diagnoseAvailable(root: RoomTitleNode?): RoomTitleReadResult {
        if (root == null) return failed(RoomTitleReadFailure.ROOT_UNAVAILABLE)
        if (root.packageName != KAKAO) return failed(RoomTitleReadFailure.NOT_KAKAO)
        if (!root.visible) return failed(RoomTitleReadFailure.ROOT_HIDDEN)
        var failure: RoomTitleReadFailure? = null
        fun unique(parent: RoomTitleNode, part: Part): RoomTitleNode? {
            val matches = parent.findById("$KAKAO:id/${part.id}")
            // Count all ID matches before filtering. A hidden/foreign duplicate remains ambiguous.
            if (matches.isEmpty()) { failure = part.missing; return null }
            if (matches.size != 1) { failure = part.ambiguous; return null }
            val node = matches.single()
            if (node.packageName != KAKAO) { failure = part.foreign; return null }
            if (!node.visible) { failure = part.hidden; return null }
            return node
        }
        // A title in a list, search page, or unrelated toolbar is not a selected chat.
        unique(root, Part.CHAT_LOG) ?: return failed(checkNotNull(failure))
        unique(root, Part.INPUT) ?: return failed(checkNotNull(failure))
        val toolbar = unique(root, Part.TOOLBAR) ?: return failed(checkNotNull(failure))
        val title = unique(toolbar, Part.TITLE) ?: return failed(checkNotNull(failure))
        // All guards passed. Kakao may label this exact title node via its description.
        // Never inspect descriptions elsewhere or use them to replace oversized nonblank text.
        val text = title.text?.toString()?.trim()
        val value = if (text.isNullOrEmpty()) title.contentDescription?.toString()?.trim() else text
        if (value.isNullOrEmpty()) return failed(RoomTitleReadFailure.TITLE_EMPTY)
        if (value.length > 512) return failed(RoomTitleReadFailure.TITLE_TOO_LONG)
        return RoomTitleReadResult(value, null)
    }
}
