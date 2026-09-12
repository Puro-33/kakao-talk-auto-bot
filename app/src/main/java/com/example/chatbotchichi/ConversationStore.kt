package com.example.kakaotalkautobot

import android.content.ContentValues
import android.content.Context
import androidx.core.content.edit
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class ConversationSummary(
    val id: String,
    val title: String,
    val captureEnabled: Boolean,
    val selfName: String?,
    val notificationKey: String?,
    val messageCount: Int
)

data class ConversationImportResult(
    val inserted: Int, val duplicates: Int, val expired: Int, val warnings: List<String>
)

/** All writes are transactional. IDs are opaque: display titles never identify an imported room. */
object ConversationStore {
    private const val GLOBAL = "global-own"
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val scheduled = mutableSetOf<String>()
    private var helper: ConversationDatabase? = null
    private var ready = false
    private var lastPrunedAt = 0L
    private var maintenanceStarted = false
    private var generation = 0L
    private var maintenance: java.util.concurrent.ScheduledFuture<*>? = null
    private val refreshTasks = mutableMapOf<String, java.util.concurrent.ScheduledFuture<*>>()
    private val revisions = mutableMapOf<String, Long>()
    private var privacyRevision = 0L

    @Synchronized fun revision(roomId: String): Long = revisions.getOrDefault(roomId, 0L)
    @Synchronized fun replyRevision(roomId: String): Pair<Long, Long> = privacyRevision to revision(roomId)
    @Synchronized fun <T> withReplyRevision(roomId: String, expected: Pair<Long, Long>, action: () -> T): T? {
        if (replyRevision(roomId) != expected) return null
        return action()
    }
    private fun invalidate(roomId: String) { revisions[roomId] = revision(roomId) + 1 }

    @Synchronized private fun database(context: Context): SQLiteDatabase {
        val app = context.applicationContext
        val db = (helper ?: ConversationDatabase(app).also { helper = it }).writableDatabase
        if (!ready) {
            migrateLegacy(app, db)
            ready = true
        }
        prune(db, System.currentTimeMillis())
        return db
    }

    fun initialize(context: Context) {
        val app = context.applicationContext
        worker.execute { runCatching { database(app) } }
        synchronized(this) {
            if (!maintenanceStarted) {
                maintenanceStarted = true
                maintenance = worker.scheduleWithFixedDelay({ runCatching { maintain(app) } }, 1, 1, TimeUnit.HOURS)
            }
        }
    }

    @Synchronized fun maintain(context: Context) {
        val db = database(context)
        prune(db, System.currentTimeMillis(), force = true)
        refreshDirty(db)
    }

    @Synchronized fun listRooms(context: Context): List<ConversationSummary> = listRooms(database(context))

    private fun listRooms(db: SQLiteDatabase): List<ConversationSummary> = db.rawQuery(
        "SELECT c.id,c.title,c.capture,c.self_name,c.notification_key,COUNT(m.id) FROM conversations c " +
            "LEFT JOIN messages m ON m.room_id=c.id GROUP BY c.id ORDER BY c.title,c.id", null
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add(ConversationSummary(cursor.getString(0), cursor.getString(1),
            cursor.getInt(2) != 0, cursor.getString(3), cursor.getString(4), cursor.getInt(5)))
    } }

    @Synchronized fun createRoom(context: Context, title: String): String {
        require(title.isNotBlank()) { "방 이름을 입력해 주세요." }
        return create(database(context), title.trim())
    }

    private fun create(db: SQLiteDatabase, title: String, notificationKey: String? = null, legacyName: String? = null): String {
        val id = UUID.randomUUID().toString()
        db.insertOrThrow("conversations", null, ContentValues().apply {
            put("id", id); put("title", title); put("capture", 0)
            put("notification_key", notificationKey); put("legacy_name", legacyName)
        })
        return id
    }

    /** Called for an observed notification, not a request to start collecting its body. */
    @Synchronized fun observeNotification(context: Context, key: String, title: String): String {
        val db = database(context)
        require(key.isNotBlank())
        val found = scalar(db, "SELECT id FROM conversations WHERE notification_key=?", key)
        if (found != null) return found
        return create(db, title, notificationKey = key)
    }

    @Synchronized fun setCaptureEnabled(context: Context, roomId: String, enabled: Boolean) {
        val db = database(context)
        requireRoom(db, roomId)
        db.update("conversations", ContentValues().apply { put("capture", if (enabled) 1 else 0) }, "id=?", arrayOf(roomId))
        invalidate(roomId)
    }

    @Synchronized fun isCaptureEnabled(context: Context, roomId: String): Boolean =
        scalar(database(context), "SELECT capture FROM conversations WHERE id=?", roomId) == "1"

    /** User explicitly selected both rooms. Never infer a merge from their titles. */
    @Synchronized fun linkNotificationRoom(context: Context, importedRoomId: String, observedRoomId: String) {
        require(importedRoomId != observedRoomId) { "서로 다른 방을 선택해 주세요." }
        val db = database(context)
        requireRoom(db, importedRoomId)
        requireRoom(db, observedRoomId)
        val key = scalar(db, "SELECT notification_key FROM conversations WHERE id=?", observedRoomId)
            ?: error("알림에서 관측된 방을 선택해 주세요.")
        require(scalar(db, "SELECT notification_key FROM conversations WHERE id=?", importedRoomId) == null) {
            "이미 알림 방과 연결되어 있습니다."
        }
        require(scalar(db, "SELECT self_name FROM conversations WHERE id=?", observedRoomId) == null) {
            "이미 가져온 내 대화가 있는 방은 연결 대상으로 사용할 수 없습니다."
        }
        transaction(db) {
            // Keep observed messages under their existing identity; linking changes only future capture.
            db.update("conversations", ContentValues().apply { putNull("notification_key"); put("capture", 0) }, "id=?", arrayOf(observedRoomId))
            db.update("conversations", ContentValues().apply { put("notification_key", key); put("capture", 0) }, "id=?", arrayOf(importedRoomId))
        }
        invalidate(importedRoomId); invalidate(observedRoomId)
    }

    @Synchronized fun importExport(context: Context, roomId: String, raw: String, selfName: String): ConversationImportResult {
        val parsed = KakaoExportParser.parse(raw)
        require(parsed.messages.isNotEmpty()) { parsed.warnings.firstOrNull() ?: "가져올 대화가 없습니다." }
        require(selfName in parsed.participants) { "내 이름을 참여자 목록에서 선택해 주세요." }
        val db = database(context)
        requireRoom(db, roomId)
        val previousSelf = scalar(db, "SELECT self_name FROM conversations WHERE id=?", roomId)
        require(previousSelf == null || previousSelf == selfName) { "기존 본인 선택과 다릅니다. 별도 방으로 가져오거나 학습 데이터를 삭제해 주세요." }
        val now = System.currentTimeMillis()
        val fingerprint = digest(raw.removePrefix("\uFEFF").replace("\r\n", "\n"))
        var inserted = 0
        var duplicates = 0
        var expired = 0
        val occurrences = mutableMapOf<String, Int>()
        transaction(db) {
            db.update("conversations", ContentValues().apply { put("self_name", selfName) }, "id=?", arrayOf(roomId))
            for (message in parsed.messages) {
                if (message.timestamp < now - ConversationStyleAnalyzer.RETENTION_MS || message.timestamp > now) { expired++; continue }
                val base = digest("${message.timestamp}\u0000${message.sender}\u0000${message.message}")
                val occurrence = occurrences.getOrDefault(base, 0)
                occurrences[base] = occurrence + 1
                val key = "export:$base:$occurrence"
                val knownGenerated = db.rawQuery("SELECT 1 FROM messages WHERE kind='AI' AND body=? AND ABS(sent_at-?)<60000 LIMIT 1",
                    arrayOf(message.message, message.timestamp.toString())).use { it.moveToFirst() }
                val role = if (knownGenerated) MessageKind.AI else if (message.sender == selfName) MessageKind.SELF else MessageKind.OTHER
                if (insertMessage(db, roomId, message.sender, message.message, message.timestamp, role, "export", key, fingerprint)) inserted++ else duplicates++
            }
            db.insertWithOnConflict("imports", null, ContentValues().apply {
                put("room_id", roomId); put("fingerprint", fingerprint); put("imported_at", now)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            dirty(db, roomId)
        }
        refreshDirty(db)
        return ConversationImportResult(inserted, duplicates, expired, parsed.warnings)
    }

    @Synchronized fun record(context: Context, roomId: String, sender: String, body: String,
        timestamp: Long, kind: MessageKind, source: String, eventKey: String? = null) {
        val db = database(context)
        // Disabled collection includes generated replies: nothing is persisted through this path.
        if (scalar(db, "SELECT capture FROM conversations WHERE id=?", roomId) != "1") return
        val now = System.currentTimeMillis()
        if (body.isBlank() || timestamp !in (now - ConversationStyleAnalyzer.RETENTION_MS)..now) return
        transaction(db) {
            val repeatedGenerated = kind == MessageKind.OTHER && db.rawQuery(
                "SELECT 1 FROM messages WHERE room_id=? AND kind='AI' AND body=? AND ABS(sent_at-?)<60000 LIMIT 1",
                arrayOf(roomId, body, timestamp.toString())).use { it.moveToFirst() }
            insertMessage(db, roomId, sender, body, timestamp, if (repeatedGenerated) MessageKind.AI else kind,
                source, eventKey ?: UUID.randomUUID().toString())
            dirty(db, roomId)
        }
        scheduleRefresh(context.applicationContext, roomId)
    }

    private fun insertMessage(db: SQLiteDatabase, roomId: String, sender: String, body: String, timestamp: Long,
        kind: MessageKind, source: String, eventKey: String, importId: String? = null): Boolean {
        db.insertWithOnConflict("participants", null, ContentValues().apply {
            put("room_id", roomId); put("name", sender)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        return db.insertWithOnConflict("messages", null, ContentValues().apply {
            put("room_id", roomId); put("sender", sender); put("body", body); put("sent_at", timestamp)
            put("kind", kind.name); put("source", source); put("event_key", eventKey); put("import_id", importId)
        }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    @Synchronized fun recentMessages(context: Context, roomId: String, limit: Int = 40): List<RoomHistoryMessage> =
        messages(database(context), resolveLegacy(database(context), roomId), limit.coerceIn(0, 500))

    private fun messages(db: SQLiteDatabase, roomId: String?, limit: Int, ownOnly: Boolean = false, styleOnly: Boolean = false): List<RoomHistoryMessage> {
        val where = if (roomId == null) "kind='SELF'" else "room_id=?" +
            if (ownOnly) " AND kind='SELF'" else if (styleOnly) " AND kind IN ('SELF','OTHER')" else ""
        return db.rawQuery("SELECT sender,body,kind,sent_at,source FROM messages WHERE $where ORDER BY sent_at DESC,id DESC LIMIT $limit",
            roomId?.let { arrayOf(it) }).use { cursor -> buildList {
            while (cursor.moveToNext()) {
                val kind = runCatching { MessageKind.valueOf(cursor.getString(2)) }.getOrDefault(MessageKind.UNKNOWN)
                add(RoomHistoryMessage(cursor.getString(0), cursor.getString(1), kind != MessageKind.AI && kind != MessageKind.SELF,
                    cursor.getLong(3), kind, cursor.getString(4)))
            }
        }.reversed() }
    }

    @Synchronized fun profiles(context: Context, roomId: String, refresh: Boolean = true): Pair<ConversationStyleProfile, ConversationStyleProfile> {
        val db = database(context)
        val id = resolveLegacy(db, roomId)
        if (refresh) refreshDirty(db)
        val own = readProfile(db, "$id:own").takeIf { it.usable } ?: readProfile(db, GLOBAL)
        return own to readProfile(db, "$id:room")
    }

    @Synchronized fun profilePreview(context: Context, roomId: String): String {
        val db = database(context)
        requireRoom(db, roomId)
        refreshDirty(db)
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA)
        fun preview(label: String, profile: ConversationStyleProfile): String {
            val period = if (profile.sampleCount == 0) "분석할 대화 없음" else "${format.format(Date(profile.firstTimestamp))} ~ ${format.format(Date(profile.lastTimestamp))}"
            return "$label · 표본 ${profile.sampleCount}개\n$period\n" +
                (if (profile.usable) profile.description else "표본 ${ConversationStyleAnalyzer.MIN_SAMPLES}개부터 말투 반영") +
                "\n갱신: ${if (profile.updatedAt > 0) format.format(Date(profile.updatedAt)) else "대기 중"}"
        }
        return listOf(preview("이 방의 내 말투", readProfile(db, "$roomId:own")),
            preview("전체 내 말투 (방별 표본 부족 시 참고)", readProfile(db, GLOBAL)),
            preview("방의 대화 분위기", readProfile(db, "$roomId:room"))).joinToString("\n\n")
    }

    @Synchronized fun deleteLearningData(context: Context, roomId: String) {
        val db = database(context)
        val id = resolveLegacy(db, roomId)
        requireRoom(db, id)
        transaction(db) {
            db.delete("messages", "room_id=?", arrayOf(id))
            db.delete("participants", "room_id=?", arrayOf(id))
            db.delete("imports", "room_id=?", arrayOf(id))
            db.update("conversations", ContentValues().apply { putNull("self_name") }, "id=?", arrayOf(id))
            dirty(db, id)
        }
        AutoMemoryStore.clear(context, id)
        invalidate(id)
        privacyRevision++ // Other rooms may have used this room's samples through the global own profile.
        LogStore.clear(context)
        refreshDirty(db)
    }

    private fun requireRoom(db: SQLiteDatabase, id: String) {
        require(scalar(db, "SELECT id FROM conversations WHERE id=?", id) != null) { "방을 다시 선택해 주세요." }
    }

    private fun resolveLegacy(db: SQLiteDatabase, value: String): String =
        scalar(db, "SELECT id FROM conversations WHERE id=?", value)
            ?: scalar(db, "SELECT id FROM conversations WHERE legacy_name=?", value) ?: value

    /** Compatibility import: unknown provenance is retained for context, never labeled as the user. */
    @Synchronized fun importLegacy(context: Context, room: String, raw: String) {
        val db = database(context)
        val id = scalar(db, "SELECT id FROM conversations WHERE legacy_name=?", room) ?: create(db, room, legacyName = room)
        val fingerprint = digest(raw)
        transaction(db) {
            RoomStore.parseImportedLines(raw).forEachIndexed { index, message ->
                insertMessage(db, id, message.sender, message.message, message.timestamp, MessageKind.UNKNOWN, "legacy-import", "$fingerprint:$index")
            }
            dirty(db, id)
        }
    }

    private fun migrateLegacy(context: Context, db: SQLiteDatabase) {
        val dir = File(context.filesDir, "room_state")
        dir.listFiles()?.filter { it.isFile && it.extension == "json" }?.forEach { file ->
            val raw = file.readText()
            val marker = "${file.name}:${digest(raw)}"
            if (scalar(db, "SELECT marker FROM migrations WHERE marker=?", marker) != null) {
                file.delete(); return@forEach
            }
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return@forEach
            val title = json.optString("room").ifBlank { file.nameWithoutExtension }
            transaction(db) {
                val id = scalar(db, "SELECT id FROM conversations WHERE legacy_name=?", title) ?: create(db, title, legacyName = title)
                RoomStore.parseHistoryJsonText(raw, Int.MAX_VALUE).forEachIndexed { index, message ->
                    if (message.timestamp >= System.currentTimeMillis() - ConversationStyleAnalyzer.RETENTION_MS) {
                        insertMessage(db, id, message.sender, message.message, message.timestamp,
                            if (message.incoming) MessageKind.UNKNOWN else MessageKind.AI, "legacy-json", "$marker:$index")
                    }
                }
                db.insertOrThrow("migrations", null, ContentValues().apply { put("marker", marker) })
                dirty(db, id)
            }
            // Commit precedes removal. If deletion fails the marker makes the next migration idempotent.
            file.delete()
        }
        // Clear independent legacy copies once; preserve manual settings and conversation files that failed migration.
        if (scalar(db, "SELECT marker FROM migrations WHERE marker=?", "derived-privacy-v1") == null) {
            // Persist removal before recording the migration marker; an asynchronous write
            // could leave the legacy privacy copy behind after a process interruption.
            context.getSharedPreferences("AutoMemoryPrefs", Context.MODE_PRIVATE).edit(commit = true) {
                clear()
            }
            LogStore.clear(context)
            db.insertOrThrow("migrations", null, ContentValues().apply { put("marker", "derived-privacy-v1") })
        }
    }

    private fun scheduleRefresh(context: Context, id: String) {
        if (!scheduled.add(id)) return
        val epoch = generation
        refreshTasks[id] = worker.schedule({ synchronized(this) {
            if (epoch != generation) return@synchronized
            scheduled.remove(id)
            refreshTasks.remove(id)
            runCatching { refreshDirty(database(context)) }
        } }, 2, TimeUnit.SECONDS)
    }

    private fun dirty(db: SQLiteDatabase, roomId: String) {
        for (key in listOf("$roomId:own", "$roomId:room", GLOBAL)) {
            db.insertWithOnConflict("profiles", null, ContentValues().apply { put("profile_key", key) }, SQLiteDatabase.CONFLICT_IGNORE)
            db.update("profiles", ContentValues().apply { put("dirty", 1) }, "profile_key=?", arrayOf(key))
        }
    }

    private fun prune(db: SQLiteDatabase, now: Long, force: Boolean = false) {
        if (!force && now - lastPrunedAt < 60_000L) return
        transaction(db) {
            val rooms = db.rawQuery("SELECT DISTINCT room_id FROM messages WHERE sent_at<?", arrayOf((now - ConversationStyleAnalyzer.RETENTION_MS).toString()))
                .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            db.delete("messages", "sent_at<?", arrayOf((now - ConversationStyleAnalyzer.RETENTION_MS).toString()))
            db.delete("imports", "imported_at<?", arrayOf((now - ConversationStyleAnalyzer.RETENTION_MS).toString()))
            db.execSQL("DELETE FROM participants WHERE NOT EXISTS (SELECT 1 FROM messages WHERE messages.room_id=participants.room_id AND messages.sender=participants.name)")
            rooms.forEach { dirty(db, it) }
            if (rooms.isNotEmpty()) {
                privacyRevision++
                refreshDirty(db) // Cached reply paths must not retain expired samples.
            }
        }
        lastPrunedAt = now
    }

    private fun refreshDirty(db: SQLiteDatabase) {
        val keys = db.rawQuery("SELECT profile_key FROM profiles WHERE dirty=1", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        for (key in keys) {
            val own = key == GLOBAL || key.endsWith(":own")
            val roomId = if (key == GLOBAL) null else key.substringBeforeLast(':')
            val profile = ConversationStyleAnalyzer.analyze(messages(db, roomId, 500, own, styleOnly = true), own, System.currentTimeMillis())
            db.update("profiles", ContentValues().apply {
                put("description", profile.description); put("samples", profile.sampleCount)
                put("first_at", profile.firstTimestamp); put("last_at", profile.lastTimestamp)
                put("updated_at", profile.updatedAt); put("dirty", 0)
            }, "profile_key=?", arrayOf(key))
        }
    }

    private fun readProfile(db: SQLiteDatabase, key: String): ConversationStyleProfile = db.rawQuery(
        "SELECT description,samples,first_at,last_at,updated_at FROM profiles WHERE profile_key=?", arrayOf(key)
    ).use { if (it.moveToFirst()) ConversationStyleProfile(it.getString(0).orEmpty(), it.getInt(1), it.getLong(2), it.getLong(3), it.getLong(4)) else ConversationStyleProfile() }

    private fun scalar(db: SQLiteDatabase, sql: String, value: String): String? = db.rawQuery(sql, arrayOf(value)).use {
        if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
    }

    private inline fun <T> transaction(db: SQLiteDatabase, block: () -> T): T {
        db.beginTransaction()
        try { val result = block(); db.setTransactionSuccessful(); return result } finally { db.endTransaction() }
    }

    internal fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    /** Instrumentation only: close singleton before a test swaps the database file. */
    @Synchronized internal fun closeForTest() {
        generation++
        refreshTasks.values.forEach { it.cancel(false) }; refreshTasks.clear(); scheduled.clear()
        maintenance?.cancel(false); maintenance = null; maintenanceStarted = false
        helper?.close(); helper = null; ready = false; lastPrunedAt = 0
    }
}

private class ConversationDatabase(context: Context) : SQLiteOpenHelper(context, "conversations.db", null, 1) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.rawQuery("PRAGMA secure_delete=ON", null).use { it.moveToFirst() }
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE conversations (id TEXT PRIMARY KEY,title TEXT NOT NULL,capture INTEGER NOT NULL DEFAULT 0,self_name TEXT,notification_key TEXT UNIQUE,legacy_name TEXT UNIQUE)")
        db.execSQL("CREATE TABLE participants (room_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,name TEXT NOT NULL,PRIMARY KEY(room_id,name))")
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT,room_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,sender TEXT NOT NULL,body TEXT NOT NULL,sent_at INTEGER NOT NULL,kind TEXT NOT NULL,source TEXT NOT NULL,event_key TEXT NOT NULL,import_id TEXT,UNIQUE(room_id,event_key))")
        db.execSQL("CREATE INDEX messages_room_time ON messages(room_id,sent_at)")
        db.execSQL("CREATE INDEX messages_kind_time ON messages(kind,sent_at)")
        db.execSQL("CREATE TABLE imports (room_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,fingerprint TEXT NOT NULL,imported_at INTEGER NOT NULL,PRIMARY KEY(room_id,fingerprint))")
        db.execSQL("CREATE TABLE profiles (profile_key TEXT PRIMARY KEY,description TEXT NOT NULL DEFAULT '',samples INTEGER NOT NULL DEFAULT 0,first_at INTEGER NOT NULL DEFAULT 0,last_at INTEGER NOT NULL DEFAULT 0,updated_at INTEGER NOT NULL DEFAULT 0,dirty INTEGER NOT NULL DEFAULT 1)")
        db.execSQL("CREATE TABLE migrations (marker TEXT PRIMARY KEY)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("대화 DB 버전 변경에는 명시적인 마이그레이션이 필요합니다.")
    }
}
