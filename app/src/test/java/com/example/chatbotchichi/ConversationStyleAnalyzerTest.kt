package com.example.kakaotalkautobot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationStyleAnalyzerTest {
    private val now = 1_800_000_000_000L
    private fun message(text: String, kind: MessageKind, time: Long = now - 1_000, source: String = "fixture") =
        RoomHistoryMessage("테스트 참여자", text, kind == MessageKind.OTHER, time, kind, source)
    private fun samples(text: String, kind: MessageKind, count: Int = 12) =
        (0 until count).map { message(text, kind, now - 1_000 - it) }

    @Test fun ownStyleExcludesOtherAiAndUnknownMessages() {
        val own = samples("확인했어요.", MessageKind.SELF)
        val excluded = samples("아니ㅋㅋ", MessageKind.OTHER) + samples("아니ㅋㅋ", MessageKind.AI) + samples("아니ㅋㅋ", MessageKind.UNKNOWN)
        val profile = ConversationStyleAnalyzer.analyze(own + excluded, true, now)
        assertEquals(12, profile.sampleCount)
        assertTrue(profile.usable)
        assertTrue(profile.description.contains("존댓말 중심"))
        assertTrue(profile.description.contains("웃음 0%"))
        assertFalse(profile.description.contains("아니"))
    }

    @Test fun roomAtmosphereIncludesHumansButExcludesAiAndUnknown() {
        val profile = ConversationStyleAnalyzer.analyze(
            samples("확인했어요.", MessageKind.SELF, 6) + samples("알겠어", MessageKind.OTHER, 6) +
                samples("숨겨진예문", MessageKind.AI) + samples("숨겨진예문", MessageKind.UNKNOWN), false, now)
        assertEquals(12, profile.sampleCount)
        assertTrue(profile.description.contains("존댓말과 반말 혼용"))
        assertFalse(profile.description.contains("숨겨진예문"))
    }

    @Test fun insufficientSamplesExposeCoverageWithoutApplyingStyle() {
        val profile = ConversationStyleAnalyzer.analyze(samples("네", MessageKind.SELF, 11), true, now)
        assertEquals(11, profile.sampleCount)
        assertFalse(profile.usable)
        assertEquals("", profile.description)
        assertEquals(now - 1_010, profile.firstTimestamp)
        assertEquals(now - 1_000, profile.lastTimestamp)
        assertEquals(now, profile.updatedAt)
    }

    @Test fun excludesExpiredFutureAndBlankMessagesWithInclusiveWindowEdges() {
        val cutoff = now - ConversationStyleAnalyzer.RETENTION_MS
        val profile = ConversationStyleAnalyzer.analyze(listOf(
            message("오래됨", MessageKind.SELF, cutoff - 1),
            message("경계", MessageKind.SELF, cutoff),
            message("현재", MessageKind.SELF, now),
            message("미래", MessageKind.SELF, now + 1),
            message(" \n ", MessageKind.SELF)), true, now)
        assertEquals(2, profile.sampleCount)
        assertEquals(cutoff, profile.firstTimestamp)
        assertEquals(now, profile.lastTimestamp)
    }

    @Test fun importedHistoryRemainsEligibleBeyondLiveNotificationRetention() {
        val old = now - ConversationStyleAnalyzer.RETENTION_MS - 1
        val profile = ConversationStyleAnalyzer.analyze(
            (0 until 12).map { message("가져온 말투예요.", MessageKind.SELF, old + it, source = "export") },
            true, now
        )
        assertEquals(12, profile.sampleCount)
        assertTrue(profile.usable)
        assertEquals(old, profile.firstTimestamp)
    }

    @Test fun onlyNewestFiveHundredEligibleMessagesContribute() {
        val profile = ConversationStyleAnalyzer.analyze(
            (0 until 520).map { message(if (it < 20) "낡은표현" else "알겠어", MessageKind.SELF, now - 520 + it) }.reversed(), true, now)
        assertEquals(500, profile.sampleCount)
        assertEquals(now - 500, profile.firstTimestamp)
        assertEquals(now - 1, profile.lastTimestamp)
        assertFalse(profile.description.contains("낡은표현"))
    }

    @Test fun describesLineBreaksLaughterEmojiAndPunctuation() {
        val profile = ConversationStyleAnalyzer.analyze(samples("응ㅋㅋ!\n괜찮아? 🙂", MessageKind.SELF), true, now)
        for (feature in listOf("줄바꿈 100%", "질문 100%", "웃음 100%", "감탄부호 100%", "이모지 100%")) {
            assertTrue(feature, profile.description.contains(feature))
        }
    }

    @Test fun exampleRemovesUrlsEmailAndNumbers() {
        val result = ConversationStyleAnalyzer.styleExample("https://example.invalid/a sample@example.invalid 12:30\n확인")
        assertEquals("[정보] [정보] [수치] / 확인", result)
    }

    @Test fun emptyAnalysisHasNoStaleExamplesOrCoverage() {
        val profile = ConversationStyleAnalyzer.analyze(samples("제외", MessageKind.AI), true, now)
        assertEquals(0, profile.sampleCount)
        assertFalse(profile.usable)
        assertEquals("", profile.description)
        assertEquals(0L, profile.firstTimestamp)
        assertEquals(now, profile.updatedAt)
    }
}
