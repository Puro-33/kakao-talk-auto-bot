package com.example.kakaotalkautobot

/** Only notification routing metadata belongs here, never message text or reply actions. */
internal data class NotificationRoomMetadata(val identity: String, val title: String) {
    companion object {
        fun from(
            userId: Int, packageName: String, shortcutId: String?, notificationKey: String,
            conversationTitle: String?, subText: String?, summaryText: String?, title: String?
        ): NotificationRoomMetadata? {
            val roomTitle = sequenceOf(conversationTitle, subText, summaryText, title)
                .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
                .firstOrNull() ?: return null
            val routingKey = shortcutId?.let { "shortcut:$it" } ?: "notification:$notificationKey"
            return NotificationRoomMetadata("$userId:$packageName:$routingKey", roomTitle)
        }
    }
}
