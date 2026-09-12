package com.example.kakaotalkautobot

import android.content.Context

import org.json.JSONArray
import org.json.JSONObject


data class RoomHistoryMessage(
    val sender: String,
    val message: String,
    val incoming: Boolean,
    val timestamp: Long,
    val kind: MessageKind = MessageKind.UNKNOWN,
    val source: String = "unknown"
)

object RoomStore {


    private const val MAX_MESSAGES = 80

    @Synchronized
    fun recordIncoming(context: Context, room: String, sender: String, message: String) {
        ConversationStore.record(context, room, sender, message, System.currentTimeMillis(), MessageKind.OTHER, "notification")
    }

    @Synchronized
    fun recordOutgoing(context: Context, room: String, message: String, sender: String = "AI") {
        ConversationStore.record(context, room, sender, message, System.currentTimeMillis(), MessageKind.AI, "generated")
    }

    @Synchronized
    fun importHistory(context: Context, room: String, raw: String) {
        ConversationStore.importLegacy(context, room, raw)
    }

    @Synchronized
    fun recentMessages(context: Context, room: String, limit: Int = 20): List<RoomHistoryMessage> {
        return ConversationStore.recentMessages(context, room, limit)
    }

    @Synchronized
    fun clearRoomHistory(context: Context, room: String): Boolean {
        val normalizedRoom = room.trim()
        if (normalizedRoom.isBlank()) return false
        ConversationStore.deleteLearningData(context, normalizedRoom)
        AutoMemoryStore.clear(context, normalizedRoom)
        return true
    }

    internal fun parseImportedLines(raw: String, timestamp: Long = System.currentTimeMillis()): List<RoomHistoryMessage> {
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val pipeParts = line.split("|").map { it.trim() }
                if (pipeParts.size >= 3) {
                    val sender = pipeParts[pipeParts.size - 2]
                    val message = pipeParts.last()
                    return@mapNotNull RoomHistoryMessage(sender, message, true, timestamp)
                }
                val colonIndex = line.indexOf(":")
                if (colonIndex in 1..50) {
                    val sender = line.substring(0, colonIndex).trim()
                    val message = line.substring(colonIndex + 1).trim()
                    if (sender.isNotBlank() && message.isNotBlank()) {
                        return@mapNotNull RoomHistoryMessage(sender, message, true, timestamp)
                    }
                }
                null
            }
            .toList()
    }

    internal fun parseHistoryJsonText(raw: String, limit: Int = 20): List<RoomHistoryMessage> {
        return runCatching { JSONObject(raw).toHistoryMessages(limit) }
            .getOrDefault(emptyList())
    }

    private fun JSONObject.toHistoryMessages(limit: Int = MAX_MESSAGES): List<RoomHistoryMessage> {
        val messages = optJSONArray("messages") ?: return emptyList()
        val start = maxOf(0, messages.length() - limit)
        return buildList {
            for (index in start until messages.length()) {
                val item = messages.optJSONObject(index) ?: continue
                add(
                    RoomHistoryMessage(
                        sender = item.optString("sender", "알수없음"),
                        message = item.optString("message", ""),
                        incoming = item.optBoolean("incoming", true),
                        timestamp = item.optLong("timestamp", 0L)
                    )
                )
            }
        }
    }
}
