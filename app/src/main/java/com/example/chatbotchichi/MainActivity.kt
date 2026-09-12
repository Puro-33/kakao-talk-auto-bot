package com.example.kakaotalkautobot

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var deviceNameText: TextView
    private lateinit var statusIndicator: View
    private lateinit var sessionSummaryText: TextView
    private lateinit var replyStatsSummaryText: TextView
    private lateinit var identitySummaryText: TextView
    private lateinit var providerSummaryText: TextView
    private lateinit var behaviorSummaryText: TextView
    private lateinit var roomSummaryText: TextView
    private lateinit var roomHistorySummaryText: TextView
    private lateinit var roomEmptyText: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: NestedScrollView
    private lateinit var clearLogsButton: MaterialButton
    private lateinit var copyLogsButton: MaterialButton
    private lateinit var recyclerView: RecyclerView
    private lateinit var roomAdapter: RoomTargetAdapter
    private lateinit var replySwitch: SwitchMaterial
    private lateinit var redactLogsSwitch: SwitchMaterial
    private lateinit var themeToggleGroup: MaterialButtonToggleGroup
    private lateinit var permissionButton: MaterialButton
    private lateinit var editConfigButton: MaterialButton
    private lateinit var manageRoomsButton: MaterialButton

    private val roomTargets = mutableListOf<AppSettings.RoomTarget>()
    private val logLines = mutableListOf<String>()
    private val maxLogLines = 100
    private var receiverRegistered = false
    private var bindingReplySwitch = false
    private var bindingThemeToggle = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.example.kakaotalkautobot.STATUS_UPDATE" -> refreshStatusUi()
                "com.example.kakaotalkautobot.LOG_UPDATE" -> appendLogLine(intent.getStringExtra("log") ?: return)
                "com.example.kakaotalkautobot.LOG_CLEARED" -> loadLogHistory()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        deviceNameText = findViewById(R.id.device_name_text)
        statusIndicator = findViewById(R.id.status_indicator)
        sessionSummaryText = findViewById(R.id.text_session_summary)
        replyStatsSummaryText = findViewById(R.id.text_reply_stats_summary)
        identitySummaryText = findViewById(R.id.text_identity_summary)
        providerSummaryText = findViewById(R.id.text_provider_summary)
        behaviorSummaryText = findViewById(R.id.text_behavior_summary)
        roomSummaryText = findViewById(R.id.text_room_summary)
        roomHistorySummaryText = findViewById(R.id.text_room_history_summary)
        roomEmptyText = findViewById(R.id.text_room_empty)
        logText = findViewById(R.id.log_text)
        logScroll = findViewById(R.id.log_scroll)
        clearLogsButton = findViewById(R.id.btn_clear_logs)
        copyLogsButton = findViewById(R.id.btn_copy_logs)
        recyclerView = findViewById(R.id.recycler_rooms)
        replySwitch = findViewById(R.id.switch_ai_replies)
        redactLogsSwitch = findViewById(R.id.switch_redact_logs)
        themeToggleGroup = findViewById(R.id.theme_toggle_group)
        permissionButton = findViewById(R.id.permission_button)
        editConfigButton = findViewById(R.id.btn_edit_config)
        findViewById<MaterialButton>(R.id.btn_conversations).setOnClickListener {
            startActivity(Intent(this, ConversationActivity::class.java))
        }
        manageRoomsButton = findViewById(R.id.btn_manage_rooms)

        clearLogsButton.setOnClickListener { confirmClearLogs() }
        copyLogsButton.setOnClickListener { copyAllLogsToClipboard() }
        redactLogsSwitch.isChecked = AppSettings.shouldRedactLogCopies(this)
        redactLogsSwitch.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setRedactLogCopies(this, isChecked)
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        roomAdapter = RoomTargetAdapter(
            roomTargets,
            context = this,
            secondaryActionLabel = getString(R.string.action_memo),
            onRoomClick = { room -> openRoomMemory(room.name) },
            onSecondaryActionClick = { room -> openRoomMemory(room.name) },
            onDeleteClick = { room ->
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("대상 방 제거")
                    .setMessage("'${room.name}' 방을 대상 목록에서 제거할까요?")
                    .setPositiveButton("제거") { _, _ ->
                        BotManager.deleteBot(this, room.name)
                        AppSettings.removeRoomTarget(this, room.name)
                        loadRoomTargets()
                    }
                    .setNegativeButton("취소", null)
                    .show()
            },
            onToggleChanged = { room, isChecked ->
                BotManager.setBotEnabled(this, room.name, isChecked)
                loadRoomTargets()
            }
        )
        recyclerView.adapter = roomAdapter

        permissionButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        editConfigButton.setOnClickListener {
            startActivity(Intent(this, EditBotActivity::class.java))
        }

        manageRoomsButton.setOnClickListener {
            startActivity(Intent(this, CreatePollingBotActivity::class.java))
        }

        replySwitch.setOnCheckedChangeListener { _, isChecked ->
            if (bindingReplySwitch) return@setOnCheckedChangeListener
            AppSettings.setAiReplyEnabled(this, isChecked)
            refreshStatusUi()
        }

        themeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (bindingThemeToggle || !isChecked) return@addOnButtonCheckedListener
            val themeMode = when (checkedId) {
                R.id.theme_button_light -> AppSettings.ThemeMode.LIGHT
                R.id.theme_button_dark -> AppSettings.ThemeMode.DARK
                else -> AppSettings.ThemeMode.SYSTEM
            }
            AppSettings.setThemeMode(this, themeMode)
        }

        syncThemeToggle(AppSettings.getThemeMode(this))
    }

    override fun onResume() {
        super.onResume()

        loadRoomTargets()
        updateConfigSummary()
        updateDeviceNameUi()
        loadLogHistory()
        refreshStatusUi()
        syncThemeToggle(AppSettings.getThemeMode(this))
        requestRebindIfNeeded()

        val filter = IntentFilter().apply {
            addAction("com.example.kakaotalkautobot.STATUS_UPDATE")
            addAction("com.example.kakaotalkautobot.LOG_UPDATE")
            addAction("com.example.kakaotalkautobot.LOG_CLEARED")
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    override fun onPause() {
        super.onPause()
        if (receiverRegistered) {
            unregisterReceiver(receiver)
            receiverRegistered = false
        }
    }

    private enum class StatusState {
        ACTIVE,
        CAPTURE_ONLY,
        DISCONNECTED
    }

    private fun refreshStatusUi() {
        val permissionGranted = NotificationManagerCompat.getEnabledListenerPackages(this)
            .contains(packageName)
        val aiReplyEnabled = AppSettings.isAiReplyEnabled(this)
        syncReplySwitch(aiReplyEnabled)

        if (!permissionGranted) {
            applyStatusUi("권한 필요 (알림 접근 허용)", StatusState.DISCONNECTED)
            updateRoomSummary()
            updateSessionSummary()
            updateReplyStatsSummary()
            return
        }

        if (!aiReplyEnabled) {
            applyStatusUi("AI 답장 OFF · 메시지 수집 중", StatusState.CAPTURE_ONLY)
            updateRoomSummary()
            updateSessionSummary()
            updateReplyStatsSummary()
            return
        }

        val stored = StatusStore.get(this)
        if (stored != null && stored.isConnected) {
            applyStatusUi("AI 답장 ON · 카카오톡 수신 대기 중", StatusState.ACTIVE)
        } else {
            applyStatusUi("권한 허용됨 · 연결 대기 중", StatusState.CAPTURE_ONLY)
        }
        updateRoomSummary()
        updateSessionSummary()
        updateReplyStatsSummary()
    }

    private fun requestRebindIfNeeded() {
        val enabled = NotificationManagerCompat.getEnabledListenerPackages(this)
            .contains(packageName)
        if (!enabled) return
        NotificationListenerService.requestRebind(ComponentName(this, NotificationListener::class.java))
    }

    private fun updateDeviceNameUi() {
        val aiConfig = AppSettings.getAiConfig(this)
        val displayName = aiConfig.displayName.trim().ifBlank { "미설정" }
        deviceNameText.text = getString(R.string.device_name_summary, displayName)
    }

    private fun applyStatusUi(status: String, state: StatusState) {
        statusText.text = status
        val color = when (state) {
            StatusState.ACTIVE -> ContextCompat.getColor(this, R.color.colorSuccess)
            StatusState.CAPTURE_ONLY -> ContextCompat.getColor(this, R.color.colorInfo)
            StatusState.DISCONNECTED -> ContextCompat.getColor(this, R.color.colorDanger)
        }
        statusIndicator.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
    }

    private fun loadLogHistory() {
        logLines.clear()
        val history = LogStore.getAll(this)
        if (history.isBlank()) {
            logText.setText(R.string.logs_empty)
            return
        }
        history.split("\n")
            .filter { it.isNotBlank() }
            .forEach { logLines.add(it) }
        trimLogLines()
        renderLogPreview()
    }

    private fun appendLogLine(line: String) {
        logLines.add(line)
        trimLogLines()
        renderLogPreview()
        updateReplyStatsSummary()
    }

    private fun trimLogLines() {
        if (logLines.size <= maxLogLines) return
        val overflow = logLines.size - maxLogLines
        if (overflow > 0) {
            logLines.subList(0, overflow).clear()
        }
    }

    private fun renderLogPreview() {
        if (logLines.isEmpty()) {
            logText.setText(R.string.logs_empty)
            return
        }
        logText.text = logLines.takeLast(12).joinToString("\n")
        scrollLogToBottom()
    }

    private fun scrollLogToBottom() {
        logScroll.post {
            // Updating the log must not move keyboard/accessibility focus or the outer page.
            logScroll.scrollTo(0, logText.height)
        }
    }

    private fun copyAllLogsToClipboard() {
        val allLogs = LogStore.getAll(this)
        if (allLogs.isBlank()) {
            Toast.makeText(this, "복사할 로그가 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        val redacted = AppSettings.shouldRedactLogCopies(this)
        val copyText = if (redacted) LogPrivacy.redact(allLogs) else allLogs
        val dialogText = LogCopyPolicy.dialogText(redacted)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(dialogText.title)
            .setMessage(dialogText.message)
            .setPositiveButton(dialogText.positiveButton) { _, _ ->
                val clip = ClipData.newPlainText("자동답장 로그", copyText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "로그가 복사되었습니다.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun confirmClearLogs() {
        val allLogs = LogStore.getAll(this)
        if (allLogs.isBlank()) {
            Toast.makeText(this, "삭제할 로그가 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("로그 삭제")
            .setMessage("로컬에 저장된 최근 로그를 삭제합니다. 답장 통계와 방 메모리는 유지됩니다.")
            .setPositiveButton("삭제") { _, _ ->
                LogStore.clear(this)
                logLines.clear()
                renderLogPreview()
                Toast.makeText(this, "로그를 삭제했습니다.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun updateConfigSummary() {
        val config = AppSettings.getAiConfig(this)
        identitySummaryText.text = getString(R.string.identity_summary, config.displayName, config.persona.take(40))
        providerSummaryText.text = getString(R.string.response_engine_summary, config.provider)
        behaviorSummaryText.text = getString(R.string.behavior_summary, config.replyMode, config.triggerMode)
    }

    private fun loadRoomTargets() {
        val rooms = BotManager.getBots(this)
            .filterNot { it.name == "기본 자동응답" }
            .map { bot ->
                val metadata = AppSettings.getRoomTarget(this, bot.roomPattern)
                AppSettings.RoomTarget(
                    name = bot.roomPattern,
                    isEnabled = bot.isEnabled,
                    lastImportedAt = metadata?.lastImportedAt ?: 0L,
                    lastImportSource = metadata?.lastImportSource
                )
            }
        roomAdapter.replaceItems(rooms)
        roomEmptyText.visibility = if (rooms.isEmpty()) View.VISIBLE else View.GONE
        updateRoomSummary()
        updateSessionSummary()
        updateReplyStatsSummary()
    }

    private fun updateRoomSummary() {
        val allRooms = BotManager.getBots(this).filterNot { it.name == "기본 자동응답" }
        val enabledCount = allRooms.count { it.isEnabled }
        roomSummaryText.text = getString(R.string.room_count_summary, enabledCount, allRooms.size)

        roomHistorySummaryText.text = if (AppSettings.isAllRoomsEnabled(this)) {
            getString(R.string.all_rooms_reply_active)
        } else {
            getString(R.string.selected_rooms_reply_active)
        }
    }

    private fun updateSessionSummary() {
        val knownRooms = SessionManager.getRegisteredRooms(this)
        val configuredRooms = BotManager.getBots(this).count { it.name != "기본 자동응답" }
        sessionSummaryText.text = getString(R.string.session_count_summary, configuredRooms, knownRooms.size)
    }

    private fun updateReplyStatsSummary() {
        val snapshot = ReplyStatsStore.snapshot(this)
        replyStatsSummaryText.text = getString(R.string.reply_statistics_summary, snapshot.summary(), snapshot.detailSummary())
    }

    private fun syncReplySwitch(enabled: Boolean) {
        bindingReplySwitch = true
        replySwitch.isChecked = enabled
        bindingReplySwitch = false
    }

    private fun syncThemeToggle(mode: AppSettings.ThemeMode) {
        bindingThemeToggle = true
        themeToggleGroup.check(
            when (mode) {
                AppSettings.ThemeMode.LIGHT -> R.id.theme_button_light
                AppSettings.ThemeMode.DARK -> R.id.theme_button_dark
                AppSettings.ThemeMode.SYSTEM -> R.id.theme_button_system
            }
        )
        bindingThemeToggle = false
    }

    private fun openRoomMemory(roomName: String) {
        startActivity(
            Intent(this, DebugRoomActivity::class.java)
                .putExtra("roomName", roomName)
        )
    }
}
