package com.example.kakaotalkautobot

import java.text.Normalizer

object ConversationImportRouting {
    fun suggestedTitle(exportTitle: String?, participants: List<String>, selfName: String): String? {
        exportTitle?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return participants.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != selfName }
            .distinct()
            .joinToString(", ")
            .takeIf { it.isNotEmpty() }
    }

    fun matchingRooms(
        title: String?, selfName: String, destinations: List<ConversationSummary>
    ): List<ConversationSummary> {
        val normalizedTitle = title?.let(::normalizeTitle)?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return destinations.filter { room ->
            (room.selectedByTitle || room.selfName == selfName) &&
                (room.selfName == null || room.selfName == selfName) &&
                normalizeTitle(room.title) == normalizedTitle
        }
    }

    fun compatibleRooms(selfName: String, destinations: List<ConversationSummary>): List<ConversationSummary> =
        destinations.filter { it.selfName == null || it.selfName == selfName }
            .sortedWith(
                compareByDescending<ConversationSummary> { it.selectedByTitle }
                    .thenBy { normalizeTitle(it.title) }
                    .thenBy { it.id }
            )

    private fun normalizeTitle(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim()
        .replace(Regex("\\s+"), " ")
}
