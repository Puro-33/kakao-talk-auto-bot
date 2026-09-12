package com.example.kakaotalkautobot

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BotManagerRoomBindingInstrumentedTest {
    private lateinit var context: Context
    private lateinit var testFiles: File
    private val preferenceNames = mutableSetOf<String>()

    @Before fun setUp() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val testId = "room-binding-${UUID.randomUUID()}"
        testFiles = File(base.cacheDir, testId).apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = testFiles
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolatedName = "$testId-$name"
                preferenceNames.add(isolatedName)
                return base.getSharedPreferences(isolatedName, mode)
            }
        }
    }

    @After fun tearDown() {
        preferenceNames.forEach { context.deleteSharedPreferences(it) }
        testFiles.deleteRecursively()
    }

    @Test fun selectingAgainPreservesSettingsAndDifferentSameTitleRoomsStayIndependent() {
        val first = BotManager.addNotificationRoom(context, room("id-a", "친구방"))
        val edited = first.copy(enabled = false, roomMemory = "보존할 메모")
        BotManager.saveConfig(context, edited)
        assertEquals(edited, BotManager.addNotificationRoom(context, room("id-a", "바뀐 방 제목")))

        val second = BotManager.addNotificationRoom(context, room("id-b", "친구방"))
        assertEquals("친구방 (2)", second.name)
        assertEquals("친구방", second.roomPattern)
        assertEquals(edited, BotManager.getConfig(context, first.name))
        assertEquals(second, BotManager.findRoomConfig(context, "새 표시 이름", "상대", "id-b"))
        assertNull(BotManager.findRoomConfig(context, "친구방", "상대", "id-c"))
        assertNull(BotManager.findRoomConfig(context, "친구방", "상대"))
    }

    @Test fun namesThatNormalizeToSameFilenameDoNotOverwriteEachOther() {
        val first = BotManager.addNotificationRoom(context, room("id-a", "친구/방"))
        val second = BotManager.addNotificationRoom(context, room("id-b", "친구?방"))
        assertNotEquals(first.name, second.name)
        assertEquals("친구?방 (2)", second.name)
        assertEquals(first, BotManager.getConfig(context, first.name))
        assertEquals(second, BotManager.getConfig(context, second.name))
    }

    @Test fun boundIdentityOutranksLegacyPatternsAndDisabledBindingDoesNotFallThrough() {
        val legacy = AutoReplyConfig("이전 설정", roomPattern = "친구*")
        BotManager.saveConfig(context, legacy)
        val bound = BotManager.addNotificationRoom(context, room("id-a", "친구방"))
        assertEquals(bound, BotManager.findRoomConfig(context, "친구방", "상대", "id-a"))
        assertEquals(legacy, BotManager.findRoomConfig(context, "친구방", "상대", "id-b"))
        assertEquals(legacy, BotManager.findRoomConfig(context, "친구방", "상대"))

        AppSettings.setAllRoomsEnabled(context, true)
        BotManager.setBotEnabled(context, bound.name, false)
        assertNull(BotManager.findRoomConfig(context, "친구방", "상대", "id-a"))
        assertFalse(BotManager.addNotificationRoom(context, room("id-a", "친구방")).enabled)
    }

    @Test fun senderRestrictionAndTriggerApplyToTheExactBinding() {
        BotManager.saveConfig(context, AutoReplyConfig("이전 설정", roomPattern = "*"))
        val bound = BotManager.addNotificationRoom(context, room("id-a", "친구방")).copy(
            allowedSenders = listOf("허용한 상대"), trigger = TriggerConfig("keyword", "도움")
        )
        BotManager.saveConfig(context, bound)
        assertNull(BotManager.findRoomConfig(context, "친구방", "다른 상대", "id-a"))
        assertNull(BotManager.findMatchingConfig(context, "친구방", "허용한 상대", "안녕", true, "id-a"))
        assertEquals(bound, BotManager.findMatchingConfig(context, "친구방", "허용한 상대", "도움 요청", true, "id-a"))
    }

    private fun room(id: String, title: String) = ConversationSummary(id, title, false, null, "notification-$id", 0)
}
