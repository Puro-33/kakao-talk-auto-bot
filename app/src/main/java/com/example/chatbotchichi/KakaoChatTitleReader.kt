package com.example.kakaotalkautobot

import android.view.accessibility.AccessibilityNodeInfo

internal object KakaoChatTitleReader {
    fun read(root: AccessibilityNodeInfo?): String? = diagnose(root).title

    fun diagnose(root: AccessibilityNodeInfo?): RoomTitleReadResult =
        RoomTitleSelection.diagnose(root?.let(::Node))

    private class Node(private val node: AccessibilityNodeInfo) : RoomTitleNode {
        override val packageName: String? get() = node.packageName?.toString()
        override val visible: Boolean get() = node.isVisibleToUser
        override val text: CharSequence? get() = node.text
        override fun findById(id: String): List<RoomTitleNode> =
            node.findAccessibilityNodeInfosByViewId(id).orEmpty().map(::Node)
    }
}
