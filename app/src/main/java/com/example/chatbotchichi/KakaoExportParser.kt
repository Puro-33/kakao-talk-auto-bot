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
    private const val DATE = "(\\d{4})년\\s*(\\d{1,2})월\\s*(\\d{1,2})일"
    private val fullMessage = Regex("^$DATE\\s+(오전|오후)\\s+(\\d{1,2}):(\\d{2}),\\s*(.+?) : (.*)$")
    private val dayHeader = Regex("^[-=\\s]*\\[?$DATE(?:\\s+[월화수목금토일]요일)?\\]?[-=\\s]*$")
    private val bracketMessage = Regex("^\\[(.+?)] \\[(오전|오후) (\\d{1,2}):(\\d{2})] ?(.*)$")
    private val dateLike = Regex("^\\d{4}(?:년|[-./])")
    private val separator = Regex("^[-=]{3,}.*")
    private val systemLine = Regex("^.+님이 (?:들어왔습니다|나갔습니다|퇴장했습니다|.+님을 초대했습니다)\\.?$")

    fun parse(raw: String): ExportParseResult {
        val messages = mutableListOf<ExportMessage>()
        val warnings = mutableListOf<String>()
        var title: String? = null
        var day: List<Int>? = null
        var pending: ExportMessage? = null
        fun flush() {
            pending?.let { messages += it.copy(message = it.message.trimEnd('\n')) }
            pending = null
        }
        fun warn(lineNumber: Int) {
            // Never echo private conversation text in diagnostics.
            warnings += "${lineNumber}행: 지원하지 않는 항목 또는 잘못된 날짜를 건너뛰었습니다."
        }
        raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
            .split('\n').forEachIndexed { index, line ->
                val lineNumber = index + 1
                val full = fullMessage.matchEntire(line)
                val header = dayHeader.matchEntire(line.trim())
                val bracket = bracketMessage.matchEntire(line)
                when {
                    full != null -> {
                        flush()
                        val g = full.groupValues
                        val timestamp = timestamp(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4], g[5].toInt(), g[6].toInt())
                        if (timestamp == null || g[7].isBlank()) warn(lineNumber)
                        else pending = ExportMessage(g[7].trim(), g[8], timestamp, messages.size)
                    }
                    header != null -> {
                        flush()
                        val g = header.groupValues
                        val parts = listOf(g[1].toInt(), g[2].toInt(), g[3].toInt())
                        day = parts.takeIf { timestamp(it[0], it[1], it[2], "오전", 12, 0) != null }
                        if (day == null) warn(lineNumber)
                    }
                    bracket != null -> {
                        flush()
                        val g = bracket.groupValues
                        val timestamp = day?.let { timestamp(it[0], it[1], it[2], g[2], g[3].toInt(), g[4].toInt()) }
                        if (timestamp == null || g[1].isBlank()) warn(lineNumber)
                        else pending = ExportMessage(g[1].trim(), g[5], timestamp, messages.size)
                    }
                    line.endsWith(" 님과 카카오톡 대화") && messages.isEmpty() && pending == null -> {
                        title = line.removeSuffix(" 님과 카카오톡 대화").trim()
                    }
                    line.endsWith(" 카카오톡 대화") && messages.isEmpty() && pending == null -> {
                        title = line.removeSuffix(" 카카오톡 대화").trim()
                    }
                    line.startsWith("저장한 날짜 :") && messages.isEmpty() && pending == null -> Unit
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
        if (messages.isEmpty()) warnings += "지원하는 대화를 찾지 못했습니다. 한국어 Android 카카오톡 텍스트 내보내기 파일을 선택하세요."
        return ExportParseResult(title, messages, warnings)
    }

    /** Strict Korean export wall-clock time; independent of the phone's current timezone. */
    private fun timestamp(year: Int, month: Int, day: Int, period: String, hour: Int, minute: Int): Long? {
        if (year !in 1900..9999 || hour !in 1..12 || minute !in 0..59) return null
        return try {
            GregorianCalendar(TimeZone.getTimeZone("Asia/Seoul")).apply {
                isLenient = false
                clear()
                set(year, month - 1, day, hour % 12 + if (period == "오후") 12 else 0, minute, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
