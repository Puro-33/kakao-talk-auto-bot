package com.example.kakaotalkautobot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KakaoExportParserTest {
    @Test fun parsesAndroidExportWithBomCrLfAndMultiline() {
        val result = KakaoExportParser.parse("\uFEFF연습방 님과 카카오톡 대화\r\n저장한 날짜 : 2026년 9월 12일\r\n\r\n2026년 9월 11일 오후 2:30, 참여자A : 첫 줄\r\n둘째 줄 : 내용\r\n2026년 9월 11일 오후 2:31, 참여자B : 응답\r\n")
        assertEquals("연습방", result.title)
        assertEquals(listOf("참여자A", "참여자B"), result.participants)
        assertEquals("첫 줄\n둘째 줄 : 내용", result.messages[0].message)
        assertEquals(1789104600000L, result.messages[0].timestamp)
        assertTrue(result.warnings.isEmpty())
    }

    @Test fun preservesRepeatedMessagesAsDistinctOccurrences() {
        val line = "2026년 9월 11일 오후 2:30, 참여자A : 네"
        val messages = KakaoExportParser.parse("$line\n$line").messages
        assertEquals(2, messages.size)
        assertEquals(listOf(0, 1), messages.map { it.ordinal })
        assertEquals(messages[0].message, messages[1].message)
    }

    @Test fun handlesBracketFormatAndMidnightNoon() {
        val result = KakaoExportParser.parse("--------------- 2026년 9월 11일 금요일 ---------------\n[참여자A] [오전 12:00] 밤\n[참여자B] [오후 12:00] 낮")
        assertEquals(2, result.messages.size)
        assertEquals(12 * 60 * 60 * 1000L, result.messages[1].timestamp - result.messages[0].timestamp)
        assertTrue(result.warnings.isEmpty())
    }

    @Test fun rejectsInvalidDatesAndDoesNotAttachTheirContinuation() {
        val result = KakaoExportParser.parse("2026년 9월 11일 오후 2:30, 참여자A : 정상\n2026년 2월 30일 오후 2:30, 참여자B : 잘못됨\n후속 줄\n2026년 9월 11일 오후 13:30, 참여자B : 잘못됨")
        assertEquals(1, result.messages.size)
        assertEquals("정상", result.messages.single().message)
        assertEquals(3, result.warnings.size)
        assertFalse(result.warnings.joinToString().contains("참여자"))
    }

    @Test fun systemAndUnknownSectionsEndThePreviousMessage() {
        val result = KakaoExportParser.parse("2026년 9월 11일 오후 2:30, 참여자A : 안녕\n참여자B님이 나갔습니다.\n알 수 없는 구간\n2026년 9월 11일 오후 2:31, 참여자A : 다음\n2026년 9월 11일 오후 2:32, 참여자B님이 들어왔습니다.\n첨부하지 않음")
        assertEquals(listOf("안녕", "다음"), result.messages.map { it.message })
        assertEquals(4, result.warnings.size)
    }

    @Test fun invalidHeaderClearsPreviousDay() {
        val result = KakaoExportParser.parse("2026년 9월 11일 금요일\n[참여자A] [오전 1:00] 정상\n2026년 2월 30일 월요일\n[참여자A] [오전 1:00] 제외")
        assertEquals(1, result.messages.size)
        assertEquals(2, result.warnings.size)
    }

    @Test fun unsupportedDayBoundaryCannotReuseThePreviousDay() {
        val result = KakaoExportParser.parse("2026년 9월 11일 금요일\n[참여자A] [오전 1:00] 정상\n2026-09-12 Saturday\n[참여자A] [오전 1:00] 제외")
        assertEquals(listOf("정상"), result.messages.map { it.message })
        assertEquals(2, result.warnings.size)
    }

    @Test fun unsupportedAndEmptyFilesHaveVisibleFailure() {
        for (raw in listOf("", "unrecognized export", "[참여자A] [오후 2:30] 날짜 없음")) {
            val result = KakaoExportParser.parse(raw)
            assertTrue(result.messages.isEmpty())
            assertTrue(result.warnings.last().contains("지원하는 대화를 찾지 못했습니다"))
        }
    }
}
