package com.example.kakaotalkautobot

import java.util.Locale

enum class MessageKind { SELF, OTHER, AI, UNKNOWN }

/** Descriptive coverage, not a probability that a generated answer is correct. */
data class ConversationStyleProfile(
    val description: String = "",
    val sampleCount: Int = 0,
    val firstTimestamp: Long = 0,
    val lastTimestamp: Long = 0,
    val updatedAt: Long = 0
) {
    val usable: Boolean get() = sampleCount >= ConversationStyleAnalyzer.MIN_SAMPLES
}

object ConversationStyleAnalyzer {
    const val MIN_SAMPLES = 12
    const val MAX_SAMPLES = 500
    const val RETENTION_MS = 90L * 24 * 60 * 60 * 1000

    fun analyze(messages: List<RoomHistoryMessage>, ownOnly: Boolean, now: Long): ConversationStyleProfile {
        val samples = messages.asSequence()
            .filter { it.kind == MessageKind.SELF || (!ownOnly && it.kind == MessageKind.OTHER) }
            .filter { (it.source == "export" || it.source == "legacy-import" || it.timestamp in (now - RETENTION_MS)..now) && it.timestamp <= now && it.message.isNotBlank() }
            .sortedBy { it.timestamp }.toList().takeLast(MAX_SAMPLES)
        if (samples.isEmpty()) return ConversationStyleProfile(updatedAt = now)
        val first = samples.first().timestamp
        val last = samples.last().timestamp
        if (samples.size < MIN_SAMPLES) return ConversationStyleProfile(
            sampleCount = samples.size, firstTimestamp = first, lastTimestamp = last, updatedAt = now
        )
        val texts = samples.map { it.message.trim() }
        fun percent(predicate: (String) -> Boolean): Int = texts.count(predicate) * 100 / texts.size
        val formal = percent { Regex("(요|습니다|합니다|입니다)[.!?~\\s]*$").containsMatchIn(it) }
        val tone = when { formal >= 70 -> "존댓말 중심"; formal <= 30 -> "반말 중심"; else -> "존댓말과 반말 혼용; 현재 상대와 맥락에 맞춤" }
        val endings = texts.map { it.substringAfterLast('\n').trimEnd('.', '!', '?', '~', ' ') }
            .map { text -> listOf("습니다", "할게", "어요", "아요", "네요", "거야", "해", "응", "요")
                .firstOrNull { text.endsWith(it) } ?: "기타" }
            .groupingBy { it }.eachCount().filterKeys { it != "기타" }
            .entries.sortedByDescending { it.value }.take(3).joinToString(", ") { it.key }
        val description = buildString {
            append("$tone. 평균 ${String.format(Locale.ROOT, "%.1f", texts.map { it.length }.average())}자. ")
            append("줄바꿈 ${percent { '\n' in it }}%, 질문 ${percent { '?' in it || '？' in it }}%, ")
            append("웃음 ${percent { 'ㅋ' in it || 'ㅎ' in it }}%, 감탄부호 ${percent { '!' in it }}%, ")
            append("이모지 ${percent { text -> text.codePoints().anyMatch { it in 0x1F000..0x1FAFF || it in 0x2600..0x27BF } }}%. ")
            if (endings.isNotBlank()) append("주요 종결 표현: $endings. ")
            // Redact contact/numeric tokens; remaining example text is style-only, not factual evidence.
            val examples = texts.map(::styleExample).filter { it.length in 2..48 }.distinct().takeLast(3)
            if (examples.isNotEmpty()) append("형식 참고 예문(사실로 사용 금지): ${examples.joinToString(" / ")}")
        }
        return ConversationStyleProfile(description, samples.size, first, last, now)
    }

    internal fun styleExample(text: String): String = text
        .replace(Regex("https?://\\S+|[\\w.+-]+@[\\w.-]+\\.[A-Za-z]+"), "[정보]")
        .replace(Regex("[0-9]+(?:[.:/~-][0-9]+)*"), "[수치]")
        .replace(Regex("[\\r\\n]+"), " / ")
        .take(80)
}
