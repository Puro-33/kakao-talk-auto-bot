package com.example.kakaotalkautobot

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

data class ExportMessage(val sender: String, val message: String, val timestamp: Long, val ordinal: Int)

data class ExportParseResult(
    val title: String?,
    val messages: List<ExportMessage>,
    val warnings: List<String>
) {
    val participants: List<String> get() = messages.map { it.sender }.distinct()
}

/** Parses exported text only. It never evaluates or executes message content. */
object KakaoExportParser {
    private data class DateParts(val year: Int, val month: Int, val day: Int, val end: Int)

    private val koreanDate = Regex("^(\\d{4})년\\s*(\\d{1,2})월\\s*(\\d{1,2})일")
    private val dotDate = Regex("^(\\d{4})\\s*\\.\\s*(\\d{1,2})\\s*\\.\\s*(\\d{1,2})\\s*\\.")
    private val numericDate = Regex("^(\\d{4})\\s*([-/])\\s*(\\d{1,2})\\s*\\2\\s*(\\d{1,2})")
    private val twelveHourMessage = Regex(
        "^\\s*(오전|오후)\\s+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\s*,\\s*(.+?)\\s*:\\s?(.*)$"
    )
    private val twentyFourHourMessage = Regex(
        "^\\s*(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\s*,\\s*(.+?)\\s*:\\s?(.*)$"
    )
    private val bracketMessage = Regex(
        "^\\[(.+?)]\\s+\\[((?:오전|오후)\\s+)?(\\d{1,2}):(\\d{2})(?::(\\d{2}))?]\\s?(.*)$"
    )
    private val weekday = Regex(
        "(?:[월화수목금토일]요일|Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)",
        RegexOption.IGNORE_CASE
    )
    private val dateLike = Regex("^[-=\\[\\s]*\\d{4}(?:년|\\s*[./-])")
    private val separator = Regex("^[-=]{3,}.*")
    private val systemLine = Regex("^.+님이 (?:들어왔습니다|나갔습니다|퇴장했습니다|.+님을 초대했습니다)\\.?$")
    private val titleWithPartner = Regex("^(.*?)\\s*님과 카카오톡 대화$")
    private val titleWithoutPartner = Regex("^(.*?)\\s+카카오톡 대화$")
    private val exportFilename = Regex("^(?:Talk[_-].*|KakaoTalkChats?)\\.txt$", RegexOption.IGNORE_CASE)

    fun parse(raw: String): ExportParseResult {
        val messages = mutableListOf<ExportMessage>()
        val warnings = mutableListOf<String>()
        var title: String? = null
        var day: DateParts? = null
        var pending: ExportMessage? = null
        fun flush() {
            pending?.let { messages += it.copy(message = it.message.trimEnd('\n')) }
            pending = null
        }
        fun warn(lineNumber: Int) {
            // Never echo private conversation text in diagnostics.
            warnings += "${lineNumber}행: 지원하지 않는 항목 또는 잘못된 날짜를 건너뛰었습니다."
        }

        normalizeLines(raw).split('\n').forEachIndexed { index, line ->
            val lineNumber = index + 1
            val full = parseFullMessage(line, messages.size + if (pending == null) 0 else 1)
            val header = parseDayHeader(line)
            val bracket = bracketMessage.matchEntire(line)
            val titleMatch = titleWithPartner.matchEntire(line.trim())
                ?: titleWithoutPartner.matchEntire(line.trim())
            when {
                full != null -> {
                    flush()
                    pending = full
                }
                header != null -> {
                    flush()
                    day = header.takeIf { timestamp24(it.year, it.month, it.day, 0, 0, 0) != null }
                    if (day == null) warn(lineNumber)
                }
                bracket != null -> {
                    flush()
                    val g = bracket.groupValues
                    val currentDay = day
                    val second = g[5].toIntOrNull() ?: 0
                    val timestamp = currentDay?.let {
                        if (g[2].isBlank()) {
                            timestamp24(it.year, it.month, it.day, g[3].toInt(), g[4].toInt(), second)
                        } else {
                            timestamp12(
                                it.year, it.month, it.day, g[2].trim(), g[3].toInt(), g[4].toInt(), second
                            )
                        }
                    }
                    if (timestamp == null || g[1].isBlank()) warn(lineNumber)
                    else pending = ExportMessage(g[1].trim(), g[6], timestamp, messages.size)
                }
                titleMatch != null -> {
                    flush()
                    if (title == null) title = titleMatch.groupValues[1].trim().takeIf { it.isNotEmpty() }
                    day = null
                }
                exportFilename.matches(line.trim()) -> {
                    flush()
                    day = null
                }
                line.startsWith("저장한 날짜") || line.startsWith("Saved Date", ignoreCase = true) -> {
                    flush()
                    day = null
                }
                dateLike.containsMatchIn(line) || separator.matches(line) ||
                    line.startsWith("[") || systemLine.matches(line) -> {
                    flush()
                    if (dateLike.containsMatchIn(line) || separator.matches(line)) day = null
                    warn(lineNumber)
                }
                pending != null -> pending = pending!!.copy(message = pending!!.message + "\n" + line)
                line.isNotBlank() -> warn(lineNumber)
            }
        }
        flush()
        if (messages.isEmpty()) {
            warnings += "지원하는 대화를 찾지 못했습니다. Android 카카오톡 텍스트 내보내기 파일을 선택하세요."
        }
        return ExportParseResult(title, messages, warnings)
    }

    private fun normalizeLines(raw: String): String = raw.removePrefix("\uFEFF")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace('\u0085', '\n')
        .replace('\u2028', '\n')
        .replace('\u2029', '\n')

    private fun parseDatePrefix(line: String): DateParts? {
        koreanDate.find(line)?.let { match ->
            val g = match.groupValues
            return DateParts(g[1].toInt(), g[2].toInt(), g[3].toInt(), match.range.last + 1)
        }
        dotDate.find(line)?.let { match ->
            val g = match.groupValues
            return DateParts(g[1].toInt(), g[2].toInt(), g[3].toInt(), match.range.last + 1)
        }
        numericDate.find(line)?.let { match ->
            val g = match.groupValues
            return DateParts(g[1].toInt(), g[3].toInt(), g[4].toInt(), match.range.last + 1)
        }
        return null
    }

    private fun parseFullMessage(line: String, ordinal: Int): ExportMessage? {
        val date = parseDatePrefix(line) ?: return null
        val remainder = line.substring(date.end)
        twelveHourMessage.matchEntire(remainder)?.let { match ->
            val g = match.groupValues
            val timestamp = timestamp12(
                date.year, date.month, date.day, g[1], g[2].toInt(), g[3].toInt(), g[4].toIntOrNull() ?: 0
            ) ?: return null
            val sender = g[5].trim()
            return sender.takeIf { it.isNotEmpty() }?.let { ExportMessage(it, g[6], timestamp, ordinal) }
        }
        twentyFourHourMessage.matchEntire(remainder)?.let { match ->
            val g = match.groupValues
            val timestamp = timestamp24(
                date.year, date.month, date.day, g[1].toInt(), g[2].toInt(), g[3].toIntOrNull() ?: 0
            ) ?: return null
            val sender = g[4].trim()
            return sender.takeIf { it.isNotEmpty() }?.let { ExportMessage(it, g[5], timestamp, ordinal) }
        }
        return null
    }

    private fun parseDayHeader(line: String): DateParts? {
        val candidate = line.trim().trim('-', '=').trim().removeSurrounding("[", "]").trim()
        val date = parseDatePrefix(candidate) ?: return null
        val suffix = candidate.substring(date.end).trim()
        return date.takeIf { suffix.isEmpty() || weekday.matches(suffix) }
    }

    private fun timestamp12(
        year: Int, month: Int, day: Int, period: String, hour: Int, minute: Int, second: Int
    ): Long? {
        if (period !in setOf("오전", "오후") || hour !in 1..12) return null
        return timestamp24(year, month, day, hour % 12 + if (period == "오후") 12 else 0, minute, second)
    }

    /** Strict Korean export wall-clock time; independent of the phone's current timezone. */
    private fun timestamp24(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long? {
        if (year !in 1900..9999 || hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
        return try {
            GregorianCalendar(TimeZone.getTimeZone("Asia/Seoul")).apply {
                isLenient = false
                clear()
                set(year, month - 1, day, hour, minute, second)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
