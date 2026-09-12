package com.example.kakaotalkautobot

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ConversationStoreInstrumentedTest {
    private lateinit var context: IsolatedConversationContext
    private val day = 24 * 60 * 60 * 1000L

    @Before fun setUp() {
        ConversationStore.closeForTest()
        // Instrumentation runs with the target app UID; the test APK's private directory is not writable.
        context = IsolatedConversationContext(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @After fun tearDown() {
        ConversationStore.closeForTest()
        context.cleanUp()
    }

    private fun export(vararg lines: Pair<String, String>, time: Long = System.currentTimeMillis() - day): String {
        val format = SimpleDateFormat("yyyy년 M월 d일 a h:mm", Locale.KOREA).apply { timeZone = TimeZone.getTimeZone("Asia/Seoul") }
        return "테스트방 님과 카카오톡 대화\n" + lines.joinToString("\n") { (sender, body) -> "${format.format(Date(time))}, $sender : $body" }
    }

    private fun ownExport(body: String, count: Int = 12) = export(*Array(count) { "본인" to body })

    private fun count(table: String, roomId: String): Int = SQLiteDatabase.openDatabase(
        context.getDatabasePath("conversations.db").path, null, SQLiteDatabase.OPEN_READONLY
    ).use { db -> db.rawQuery("SELECT COUNT(*) FROM $table WHERE room_id=?", arrayOf(roomId)).use { it.moveToFirst(); it.getInt(0) } }

    @Test fun importIsIdempotentAndPreservesRealRepeatedOccurrences() {
        val room = ConversationStore.createRoom(context, "테스트방")
        val raw = export("본인" to "네", "본인" to "네", "상대" to "확인")
        val first = ConversationStore.importExport(context, room, raw, "본인")
        val again = ConversationStore.importExport(context, room, "\uFEFF" + raw.replace("\n", "\r\n"), "본인")
        assertEquals(3, first.inserted)
        assertEquals(0, again.inserted)
        assertEquals(3, again.duplicates)
        assertEquals(2, ConversationStore.recentMessages(context, room).count { it.message == "네" })
        assertEquals(1, count("imports", room))
        assertEquals(listOf(MessageKind.SELF, MessageKind.SELF, MessageKind.OTHER), ConversationStore.recentMessages(context, room).map { it.kind })
    }

    @Test fun identicalTitlesKeepSeparateIdentitiesUntilExplicitNotificationLink() {
        val first = ConversationStore.createRoom(context, "동일 이름")
        val second = ConversationStore.createRoom(context, "동일 이름")
        val observed = ConversationStore.observeNotification(context, "fixture-conversation-key", "동일 이름")
        assertNotEquals(first, second)
        assertNotEquals(first, observed)
        assertEquals(3, ConversationStore.listRooms(context).size)
        ConversationStore.importExport(context, first, ownExport("확인했어요."), "본인")
        assertTrue(ConversationStore.recentMessages(context, second).isEmpty())
        ConversationStore.setCaptureEnabled(context, observed, true)
        ConversationStore.record(context, observed, "상대", "기존 수신", System.currentTimeMillis() - day, MessageKind.OTHER, "notification")
        ConversationStore.linkNotificationRoom(context, first, observed)
        assertEquals(first, ConversationStore.observeNotification(context, "fixture-conversation-key", "바뀐 표시 이름"))
        assertFalse(ConversationStore.isCaptureEnabled(context, first))
        assertFalse(ConversationStore.isCaptureEnabled(context, observed))
        assertEquals("기존 수신", ConversationStore.recentMessages(context, observed).single().message)
        assertEquals(12, ConversationStore.recentMessages(context, first).size)
        assertNull(ConversationStore.listRooms(context).single { it.id == observed }.notificationKey)
    }

    @Test fun captureOptInAndProvenanceProtectStyleSamples() {
        val room = ConversationStore.createRoom(context, "수집 테스트")
        val now = System.currentTimeMillis() - day
        ConversationStore.record(context, room, "상대", "저장 금지", now, MessageKind.OTHER, "notification")
        assertTrue(ConversationStore.recentMessages(context, room).isEmpty())
        ConversationStore.setCaptureEnabled(context, room, true)
        repeat(12) {
            ConversationStore.record(context, room, "본인", "확인했어요.", now + it, MessageKind.SELF, "fixture")
            ConversationStore.record(context, room, "AI", "제외할말투ㅋㅋ", now + it, MessageKind.AI, "generated")
            ConversationStore.record(context, room, "미확인", "제외할말투ㅋㅋ", now + it, MessageKind.UNKNOWN, "legacy")
        }
        val (own, atmosphere) = ConversationStore.profiles(context, room)
        assertEquals(12, own.sampleCount)
        assertEquals(12, atmosphere.sampleCount)
        assertFalse(own.description.contains("제외할말투"))
        ConversationStore.setCaptureEnabled(context, room, false)
        ConversationStore.record(context, room, "AI", "추가 금지", now, MessageKind.AI, "generated")
        assertEquals(36, ConversationStore.recentMessages(context, room).size)
    }

    @Test fun roomOwnStyleOverridesGlobalAndSparseRoomFallsBackToGlobal() {
        val formal = ConversationStore.createRoom(context, "업무 테스트")
        val casual = ConversationStore.createRoom(context, "친구 테스트")
        val sparse = ConversationStore.createRoom(context, "신규 테스트")
        ConversationStore.importExport(context, formal, ownExport("확인했어요."), "본인")
        ConversationStore.importExport(context, casual, ownExport("응ㅋㅋ"), "본인")
        ConversationStore.importExport(context, sparse, ownExport("네", 1), "본인")
        assertTrue(ConversationStore.profiles(context, formal).first.description.contains("존댓말 중심"))
        assertTrue(ConversationStore.profiles(context, casual).first.description.contains("반말 중심"))
        assertEquals(25, ConversationStore.profiles(context, sparse).first.sampleCount)
        assertEquals(1, ConversationStore.profiles(context, sparse).second.sampleCount)
    }

    @Test fun deletionRemovesRowsExamplesAndGlobalContributionButPreservesOtherRooms() {
        val deleted = ConversationStore.createRoom(context, "삭제 테스트")
        val retained = ConversationStore.createRoom(context, "유지 테스트")
        ConversationStore.importExport(context, deleted, ownExport("삭제대상표현이요."), "본인")
        ConversationStore.importExport(context, retained, ownExport("유지표현이야"), "본인")
        ConversationStore.deleteLearningData(context, deleted)
        for (table in listOf("messages", "participants", "imports")) assertEquals(table, 0, count(table, deleted))
        assertNull(ConversationStore.listRooms(context).single { it.id == deleted }.selfName)
        assertEquals(12, ConversationStore.recentMessages(context, retained).size)
        val preview = ConversationStore.profilePreview(context, deleted)
        assertFalse(preview.contains("삭제대상표현"))
        assertEquals(12, ConversationStore.profiles(context, deleted).first.sampleCount)
        assertEquals(0, ConversationStore.profiles(context, deleted).second.sampleCount)
    }

    @Test fun maintenancePrunesOldRowsAndRebuildsProfiles() {
        val room = ConversationStore.createRoom(context, "보관 테스트")
        ConversationStore.importExport(context, room, ownExport("보관표현이요."), "본인")
        assertTrue(ConversationStore.profiles(context, room).first.usable)
        val expiredAt = System.currentTimeMillis() - ConversationStyleAnalyzer.RETENTION_MS - day
        SQLiteDatabase.openDatabase(context.getDatabasePath("conversations.db").path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE messages SET sent_at=? WHERE room_id=?", arrayOf<Any>(expiredAt, room))
            it.execSQL("UPDATE imports SET imported_at=? WHERE room_id=?", arrayOf<Any>(expiredAt, room))
        }
        ConversationStore.closeForTest()
        assertFalse(ConversationStore.profiles(context, room, refresh = false).first.usable)
        for (table in listOf("messages", "participants", "imports")) assertEquals(table, 0, count(table, room))
        assertEquals(0, ConversationStore.profiles(context, room).first.sampleCount)
        assertFalse(ConversationStore.profilePreview(context, room).contains("보관표현"))
        val expiredImport = ConversationStore.importExport(context, room, export("본인" to "너무 오래됨", time = expiredAt), "본인")
        assertEquals(0, expiredImport.inserted)
        assertEquals(1, expiredImport.expired)
    }

    private fun legacyFile(title: String, messages: List<Pair<String, Boolean>>): File {
        val items = JSONArray()
        messages.forEachIndexed { index, (body, incoming) ->
            items.put(JSONObject().put("sender", if (incoming) "이전 참여자" else "AI")
                .put("message", body).put("incoming", incoming)
                .put("timestamp", System.currentTimeMillis() - day + index))
        }
        return File(File(context.filesDir, "room_state").apply { mkdirs() }, "legacy-fixture.json").apply {
            writeText(JSONObject().put("room", title).put("messages", items).toString())
        }
    }

    @Test fun legacyMigrationPreservesProvenanceAndIsIdempotentAfterReopening() {
        val source = legacyFile("이전 대화 테스트", List(12) { "이전수신이요." to true } + List(12) { "이전생성ㅋㅋ" to false })
        val original = source.readText()
        val room = ConversationStore.listRooms(context).single()
        assertFalse("Source is removed after the migration commits", source.exists())
        assertEquals(24, room.messageCount)
        val messages = ConversationStore.recentMessages(context, room.id)
        assertEquals(12, messages.count { it.kind == MessageKind.UNKNOWN })
        assertEquals(12, messages.count { it.kind == MessageKind.AI })
        assertTrue(messages.all { it.source == "legacy-json" })
        assertEquals(0, ConversationStore.profiles(context, room.id).first.sampleCount)
        assertEquals(0, ConversationStore.profiles(context, room.id).second.sampleCount)

        // Simulate a successfully committed import whose original file could not be deleted.
        source.writeText(original)
        ConversationStore.closeForTest()
        val reopened = ConversationStore.listRooms(context).single()
        assertEquals(room.id, reopened.id)
        assertEquals(24, reopened.messageCount)
        assertFalse(source.exists())
    }

    @Test fun failedLegacyMigrationRollsBackAndKeepsOriginalForRetry() {
        assertTrue(ConversationStore.listRooms(context).isEmpty())
        val source = legacyFile("재시도 테스트", listOf("first fixture" to true, "migration failure fixture" to false))
        val original = source.readText()
        ConversationStore.closeForTest()
        SQLiteDatabase.openDatabase(context.getDatabasePath("conversations.db").path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("CREATE TRIGGER fixture_fail_migration BEFORE INSERT ON messages " +
                "WHEN NEW.body='migration failure fixture' BEGIN SELECT RAISE(ABORT, 'fixture migration failure'); END")
        }
        val failure = runCatching { ConversationStore.listRooms(context) }.exceptionOrNull()
        assertTrue("Injected insert failure must be surfaced", failure is android.database.sqlite.SQLiteException)
        assertTrue(source.exists())
        assertEquals(original, source.readText())
        ConversationStore.closeForTest()
        SQLiteDatabase.openDatabase(context.getDatabasePath("conversations.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            for (table in listOf("conversations", "messages", "participants", "migrations", "profiles")) {
                db.rawQuery("SELECT COUNT(*) FROM $table", null).use {
                    it.moveToFirst()
                    // Initialization already committed the independent legacy-log cleanup marker.
                    assertEquals("$table must roll back with the failed migration", if (table == "migrations") 1 else 0, it.getInt(0))
                }
            }
            db.execSQL("DROP TRIGGER fixture_fail_migration")
        }
        val room = ConversationStore.listRooms(context).single()
        assertEquals(2, room.messageCount)
        assertEquals(listOf("first fixture", "migration failure fixture"), ConversationStore.recentMessages(context, room.id).map { it.message })
        assertFalse(source.exists())
    }

    @Test fun exportedKnownGeneratedMessagesStayExcludedAcrossDifferentRoomIds() {
        val generatedRoom = ConversationStore.createRoom(context, "알림에서 관측한 방")
        val importedRoom = ConversationStore.createRoom(context, "별도 가져오기 방")
        val recordedAt = System.currentTimeMillis() - day
        ConversationStore.setCaptureEnabled(context, generatedRoom, true)
        ConversationStore.record(context, generatedRoom, "AI", "자동생성표현ㅋㅋ", recordedAt, MessageKind.AI, "generated")
        val raw = export(*(Array(12) { "본인" to "직접확인했어요." } + Array(12) { "본인" to "자동생성표현ㅋㅋ" }), time = recordedAt)
        assertEquals(24, ConversationStore.importExport(context, importedRoom, raw, "본인").inserted)
        assertEquals(12, ConversationStore.recentMessages(context, importedRoom).count { it.kind == MessageKind.AI })
        val (own, atmosphere) = ConversationStore.profiles(context, importedRoom)
        assertEquals(12, own.sampleCount)
        assertEquals(12, atmosphere.sampleCount)
        assertTrue(own.description.contains("존댓말 중심"))
        assertFalse(own.description.contains("자동생성표현"))
        assertFalse(atmosphere.description.contains("자동생성표현"))
    }

    @Test fun deletionInvalidatesOnlyTheDeletedRoomsRevision() {
        val deleted = ConversationStore.createRoom(context, "revision 삭제 테스트")
        val retained = ConversationStore.createRoom(context, "revision 유지 테스트")
        ConversationStore.importExport(context, deleted, ownExport("확인했어요."), "본인")
        ConversationStore.importExport(context, retained, ownExport("응ㅋㅋ"), "본인")
        val previousDeleted = ConversationStore.revision(deleted)
        val previousRetained = ConversationStore.revision(retained)
        val previousReply = ConversationStore.replyRevision(retained)
        ConversationStore.deleteLearningData(context, deleted)
        assertTrue(ConversationStore.revision(deleted) > previousDeleted)
        assertEquals(previousRetained, ConversationStore.revision(retained))
        assertNotEquals(previousReply, ConversationStore.replyRevision(retained))
        var sent = false
        ConversationStore.withReplyRevision(retained, previousReply) { sent = true }
        assertFalse("A reply using the deleted room's global style must be discarded", sent)
        assertTrue(ConversationStore.recentMessages(context, deleted).isEmpty())
    }

    @Test fun screenTitleSelectionIsExplicitIdempotentAndDoesNotEnableCapture() {
        val imported = ConversationStore.createRoom(context, "선택한 방")
        val selected = ConversationStore.ensureScreenSelectedRoom(context, "선택한 방")
        assertNotEquals(imported, selected)
        assertEquals(selected, ConversationStore.ensureScreenSelectedRoom(context, " 선택한 방 "))
        val room = ConversationStore.listRooms(context).first { it.id == selected }
        assertTrue(room.selectedByTitle)
        assertNull(room.notificationKey)
        assertFalse(room.captureEnabled)
        assertFalse(ConversationStore.listRooms(context).first { it.id == imported }.selectedByTitle)
        assertEquals(selected, BotManager.addScreenSelectedRoom(context, "선택한 방").conversationId)
        assertFalse(ConversationStore.isCaptureEnabled(context, selected))
        ConversationStore.setCaptureEnabled(context, selected, true)
        assertTrue(ConversationStore.isCaptureEnabled(context, selected))
        ConversationStore.closeForTest()
        assertEquals(selected, ConversationStore.ensureScreenSelectedRoom(context, "선택한 방"))
    }

    @Test fun selectedTitleRoutesOnlyFutureEventsAndPreservesPreviousObservedHistory() {
        val observed = ConversationStore.observeNotification(context, "real-key-a", "같은 표시 이름")
        ConversationStore.setCaptureEnabled(context, observed, true)
        ConversationStore.record(context, observed, "상대", "이전 방의 기록", System.currentTimeMillis(), MessageKind.OTHER, "notification", "before")
        val selected = ConversationStore.ensureScreenSelectedRoom(context, "같은 표시 이름")
        assertEquals(selected, ConversationStore.observeNotification(context, "real-key-a", "같은 표시 이름"))
        assertEquals(selected, ConversationStore.observeNotification(context, "real-key-b", "같은 표시 이름"))
        ConversationStore.record(context, selected, "상대", "수집 동의 전", System.currentTimeMillis(), MessageKind.OTHER, "notification", "disabled")
        assertTrue(ConversationStore.recentMessages(context, selected).isEmpty())

        ConversationStore.setCaptureEnabled(context, selected, true)
        ConversationStore.record(context, selected, "상대", "선택 후 기록", System.currentTimeMillis(), MessageKind.OTHER, "notification", "after")
        assertEquals(listOf("이전 방의 기록"), ConversationStore.recentMessages(context, observed).map { it.message })
        assertEquals(listOf("선택 후 기록"), ConversationStore.recentMessages(context, selected).map { it.message })
        val previous = ConversationStore.listRooms(context).first { it.id == observed }
        assertEquals("real-key-a", previous.notificationKey)
        assertFalse(previous.selectedByTitle)
        assertNull(ConversationStore.listRooms(context).first { it.id == selected }.notificationKey)
        assertEquals(observed, ConversationStore.observeNotification(context, "real-key-a", "다른 표시 이름"))
    }

    @Test fun versionOneDatabaseUpgradePreservesHistoryAndDoesNotInferTitleConsent() {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("conversations.db"), null).use { db ->
            db.execSQL("CREATE TABLE conversations (id TEXT PRIMARY KEY,title TEXT NOT NULL,capture INTEGER NOT NULL DEFAULT 0,self_name TEXT,notification_key TEXT UNIQUE,legacy_name TEXT UNIQUE)")
            db.execSQL("CREATE TABLE participants (room_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,name TEXT NOT NULL,PRIMARY KEY(room_id,name))")
            db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT,room_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,sender TEXT NOT NULL,body TEXT NOT NULL,sent_at INTEGER NOT NULL,kind TEXT NOT NULL,source TEXT NOT NULL,event_key TEXT NOT NULL,import_id TEXT,UNIQUE(room_id,event_key))")
            db.execSQL("CREATE INDEX messages_room_time ON messages(room_id,sent_at)")
            db.execSQL("CREATE INDEX messages_kind_time ON messages(kind,sent_at)")
            db.execSQL("CREATE TABLE imports (room_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,fingerprint TEXT NOT NULL,imported_at INTEGER NOT NULL,PRIMARY KEY(room_id,fingerprint))")
            db.execSQL("CREATE TABLE profiles (profile_key TEXT PRIMARY KEY,description TEXT NOT NULL DEFAULT '',samples INTEGER NOT NULL DEFAULT 0,first_at INTEGER NOT NULL DEFAULT 0,last_at INTEGER NOT NULL DEFAULT 0,updated_at INTEGER NOT NULL DEFAULT 0,dirty INTEGER NOT NULL DEFAULT 1)")
            db.execSQL("CREATE TABLE migrations (marker TEXT PRIMARY KEY)")
            db.execSQL("INSERT INTO conversations(id,title,capture,notification_key) VALUES('old-id','이전 방',1,'old-real-key')")
            db.execSQL("INSERT INTO messages(room_id,sender,body,sent_at,kind,source,event_key) VALUES('old-id','상대','보존된 기록',?,'OTHER','notification','old-event')", arrayOf(System.currentTimeMillis()))
            db.version = 1
        }
        val upgraded = ConversationStore.listRooms(context).single()
        assertEquals("old-id", upgraded.id)
        assertEquals("old-real-key", upgraded.notificationKey)
        assertTrue(upgraded.captureEnabled)
        assertFalse(upgraded.selectedByTitle)
        assertEquals(listOf("보존된 기록"), ConversationStore.recentMessages(context, upgraded.id).map { it.message })
        assertEquals(upgraded.id, ConversationStore.observeNotification(context, "old-real-key", "이전 방"))
        val selected = ConversationStore.ensureScreenSelectedRoom(context, "이전 방")
        assertNotEquals(upgraded.id, selected)
        assertEquals(selected, ConversationStore.observeNotification(context, "old-real-key", "이전 방"))
        SQLiteDatabase.openDatabase(context.getDatabasePath("conversations.db").path, null, SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(2, it.version)
        }
    }

    /** Every path and preference is scoped to a unique test directory/name, never the installed app DB. */
    private class IsolatedConversationContext(base: Context) : ContextWrapper(base) {
        private val prefix = "conversation-test-${UUID.randomUUID()}"
        private val root = File(base.cacheDir, prefix).apply { mkdirs() }
        private val preferenceNames = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getDatabasePath(name: String): File = File(root, name)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            baseContext.openOrCreateDatabase(getDatabasePath(name).path, mode, factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
            baseContext.openOrCreateDatabase(getDatabasePath(name).path, mode, factory, errorHandler)
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = "$prefix-$name"
            preferenceNames += isolatedName
            return baseContext.getSharedPreferences(isolatedName, mode)
        }
        fun cleanUp() {
            preferenceNames.forEach { baseContext.deleteSharedPreferences(it) }
            root.deleteRecursively()
        }
    }
}
