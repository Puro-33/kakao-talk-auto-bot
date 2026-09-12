package com.example.kakaotalkautobot

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText

class DebugRoomActivity : AppCompatActivity() {
    private lateinit var rootScroll: NestedScrollView
    private lateinit var editRoomName: TextInputEditText
    private lateinit var roomMetaText: TextView
    private lateinit var editMemory: TextInputEditText
    private lateinit var editRoomStyle: TextInputEditText
    private lateinit var switchLearnedRoomStyle: SwitchMaterial
    private lateinit var learnedRoomStylePreview: TextView
    private lateinit var editLearnedRoomStyle: TextInputEditText
    private lateinit var resetLearnedRoomStyleButton: MaterialButton
    private lateinit var clearRoomLearningSourceButton: MaterialButton
    private lateinit var spinnerReplyMode: Spinner
    private lateinit var spinnerTriggerMode: Spinner
    private lateinit var editTriggerValue: TextInputEditText
    private lateinit var editAllowedSenders: TextInputEditText
    private lateinit var editBlockedSenders: TextInputEditText
    private lateinit var editCannedReplies: TextInputEditText
    private lateinit var saveButton: MaterialButton
    private lateinit var clearButton: MaterialButton

    private val replyModes = listOf("AI 답장", "고정 답장")
    private val triggerModes = listOf("AI가 판단", "호출어/멘션만", "질문/명령만", "특정 키워드", "모든 메시지")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug_room)

        rootScroll = findViewById(R.id.debug_room_scroll)
        editRoomName = findViewById(R.id.edit_room_name)
        roomMetaText = findViewById(R.id.text_room_meta)
        editMemory = findViewById(R.id.edit_memory)
        editRoomStyle = findViewById(R.id.edit_room_style)
        switchLearnedRoomStyle = findViewById(R.id.switch_learned_room_style)
        learnedRoomStylePreview = findViewById(R.id.text_learned_room_style_preview)
        editLearnedRoomStyle = findViewById(R.id.edit_learned_room_style)
        resetLearnedRoomStyleButton = findViewById(R.id.btn_reset_learned_room_style)
        clearRoomLearningSourceButton = findViewById(R.id.btn_clear_room_learning_source)
        spinnerReplyMode = findViewById(R.id.spinner_reply_mode)
        spinnerTriggerMode = findViewById(R.id.spinner_trigger_mode)
        editTriggerValue = findViewById(R.id.edit_trigger_value)
        editAllowedSenders = findViewById(R.id.edit_allowed_senders)
        editBlockedSenders = findViewById(R.id.edit_blocked_senders)
        editCannedReplies = findViewById(R.id.edit_canned_replies)
        saveButton = findViewById(R.id.btn_save_memory)
        clearButton = findViewById(R.id.btn_clear_memory)

        configureSpinner(spinnerReplyMode, replyModes)
        configureSpinner(spinnerTriggerMode, triggerModes)
        rootScroll.bindFocusScroll(
            editRoomName,
            editMemory,
            editRoomStyle,
            editLearnedRoomStyle,
            editTriggerValue,
            editAllowedSenders,
            editBlockedSenders,
            editCannedReplies
        )

        val initialRoomName = intent.getStringExtra("roomName")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: BotManager.getBots(this).firstOrNull { it.name != "기본 자동응답" }?.roomPattern
            ?: ""

        editRoomName.setText(initialRoomName)
        bindRoom(initialRoomName)

        clearButton.setOnClickListener {
            editMemory.setText("")
        }

        resetLearnedRoomStyleButton.setOnClickListener {
            val roomName = editRoomName.text?.toString()?.trim().orEmpty()
            if (roomName.isBlank()) {
                Toast.makeText(this, "방 이름을 먼저 입력해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            StyleProfileStore.resetRoomLearnedStyle(this, roomName)
            editLearnedRoomStyle.setText("")
            bindLearnedRoomStyle(roomName)
            Toast.makeText(this, "학습된 방 말투 수정을 초기화했습니다.", Toast.LENGTH_SHORT).show()
        }

        clearRoomLearningSourceButton.setOnClickListener {
            startActivity(Intent(this, ConversationActivity::class.java))
            Toast.makeText(this, "학습 데이터를 삭제할 방을 직접 선택하세요. 수동 설정은 유지됩니다.", Toast.LENGTH_LONG).show()
        }

        saveButton.setOnClickListener {
            val roomName = editRoomName.text?.toString()?.trim().orEmpty()
            if (roomName.isEmpty()) {
                Toast.makeText(this, "방 이름을 입력해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val existingConfig = BotManager.getConfigByRoomPattern(this, roomName)
            val baseConfig = existingConfig
                ?: BotManager.getConfig(this, "기본 자동응답")
                ?: AutoReplyJson.defaultConfig("기본 자동응답")
            val updatedConfig = baseConfig.copy(
                name = if (existingConfig == null || baseConfig.name == "기본 자동응답") roomName else baseConfig.name,
                roomPattern = roomName,
                enabled = true,
                captureEnabled = true,
                replyEnabled = true,
                replyMode = if (spinnerReplyMode.selectedItem == "고정 답장") "canned" else "provider",
                roomMemory = editMemory.text?.toString().orEmpty(),
                roomStyle = editRoomStyle.text?.toString().orEmpty().trim(),
                allowedSenders = splitLines(editAllowedSenders.text?.toString().orEmpty()),
                blockedSenders = splitLines(editBlockedSenders.text?.toString().orEmpty()),
                cannedReplies = splitLines(editCannedReplies.text?.toString().orEmpty()),
                trigger = TriggerConfig(
                    mode = mapTriggerMode(spinnerTriggerMode.selectedItem?.toString().orEmpty()),
                    value = editTriggerValue.text?.toString().orEmpty().trim()
                ),
                importHistory = ""
            )
            if (updatedConfig.replyMode == "canned" && updatedConfig.cannedReplies.isEmpty()) {
                Toast.makeText(this, "고정 답장을 한 줄 이상 입력해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            BotManager.saveConfig(this, updatedConfig)
            StyleProfileStore.setRoomLearnedStyleEnabled(this, roomName, switchLearnedRoomStyle.isChecked)
            StyleProfileStore.saveRoomLearnedStyleOverride(
                this,
                roomName,
                editLearnedRoomStyle.text?.toString().orEmpty()
            )
            Toast.makeText(this, "${roomName} 메모리를 저장했습니다.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun bindRoom(roomName: String) {
        val config = BotManager.getConfigByRoomPattern(this, roomName)
        editMemory.setText(config?.roomMemory ?: AppSettings.getRoomMemory(this, roomName))
        editRoomStyle.setText(config?.roomStyle.orEmpty())
        spinnerReplyMode.setSelection(if (config?.replyMode.equals("canned", true)) 1 else 0)
        spinnerTriggerMode.setSelection(triggerModes.indexOf(readableTriggerMode(config?.trigger?.mode)).coerceAtLeast(0))
        editTriggerValue.setText(config?.trigger?.value.orEmpty())
        editAllowedSenders.setText(config?.allowedSenders?.joinToString("\n").orEmpty())
        editBlockedSenders.setText(config?.blockedSenders?.joinToString("\n").orEmpty())
        editCannedReplies.setText(config?.cannedReplies?.joinToString("\n").orEmpty())

        roomMetaText.text = "직접 입력한 메모와 말투는 이 방 이름의 응답 설정에 적용됩니다. 자동 분석은 대화 수집·말투 분석에서 확인하세요."
        bindLearnedRoomStyle(roomName)
    }

    private fun bindLearnedRoomStyle(roomName: String) {
        val history = RoomStore.recentMessages(this, roomName, limit = 40)
        val state = StyleProfileStore.getRoomLearnedStyleState(this, roomName, history)
        switchLearnedRoomStyle.isChecked = state.enabled
        editLearnedRoomStyle.setText(state.override)
        resetLearnedRoomStyleButton.text = state.resetOverrideButtonLabel
        resetLearnedRoomStyleButton.isEnabled = state.hasManualOverride
        learnedRoomStylePreview.text = "자동 말투 미리보기와 원본 삭제는 대화 수집·말투 분석에서 실제 방을 선택하세요. " +
            "같은 이름의 방을 자동으로 연결하지 않습니다." +
            if (state.hasManualOverride) "\n수동 수정값: ${state.override}" else ""
    }

    private fun configureSpinner(spinner: Spinner, items: List<String>) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
    }

    private fun splitLines(raw: String): List<String> {
        return raw.split('\n', ',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun mapTriggerMode(label: String): String {
        return when (label) {
            "호출어/멘션만" -> "mention"
            "질문/명령만" -> "question"
            "특정 키워드" -> "keyword"
            "모든 메시지" -> "always"
            else -> "ai_judge"
        }
    }

    private fun readableTriggerMode(mode: String?): String {
        return when (mode?.lowercase()) {
            "mention" -> "호출어/멘션만"
            "question" -> "질문/명령만"
            "keyword", "contains" -> "특정 키워드"
            "always" -> "모든 메시지"
            else -> "AI가 판단"
        }
    }
}
