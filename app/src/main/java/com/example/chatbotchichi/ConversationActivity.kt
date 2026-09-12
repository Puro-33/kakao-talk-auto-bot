package com.example.kakaotalkautobot

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** All document, parser, database and profile work runs off the UI thread. */
class ConversationActivity : AppCompatActivity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var content: LinearLayout
    private lateinit var roomList: LinearLayout
    private lateinit var status: TextView
    private var busy = false

    private data class ImportPreview(
        val raw: String, val title: String?, val participants: List<String>,
        val messageCount: Int, val warnings: List<String>, val destinations: List<ConversationSummary>
    )

    private class ExportReadException(message: String) : Exception(message)

    private val openExport = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) inspectExport(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "대화 수집·말투 분석"
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(content)
        })
        content.addView(label("대화 수집·말투 분석", 24f))
        content.addView(label("카카오톡에서 내보낸 대화(.txt)를 가져오고 본인을 선택하면 말투를 자동 분석합니다. 수동 예문 입력은 선택 사항입니다."))
        content.addView(label("알림으로는 상대방의 수신 메시지만 모을 수 있습니다. 직접 보낸 메시지의 말투를 최신화하려면 파일을 다시 가져오세요. 앱이 생성 이력을 확인한 AI 답장은 말투 표본에서 제외합니다."))
        content.addView(label("최근 90일의 대화를 기기 안에서 분석합니다. 수집과 자동답장은 별개이며, 새 방의 알림 수집은 기본 OFF입니다."))
        content.addView(button("대화 파일 가져오기") {
            openExport.launch(arrayOf("text/plain", "application/octet-stream"))
        })
        content.addView(button("방 목록 새로고침") { refresh() })
        status = label("불러오는 중…")
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        content.addView(status)
        roomList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(roomList)
        refresh()
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }

    private fun refresh() = background("대화와 말투를 불러오는 중…", {
        val summaries = ConversationStore.listRooms(applicationContext)
        summaries.map { it to ConversationStore.profilePreview(applicationContext, it.id) }
    }) { rows ->
        roomList.removeAllViews()
        status.text = if (rows.isEmpty()) "아직 수집한 방이 없습니다." else "${rows.size}개 방 · 최근 90일"
        rows.forEach { (room, preview) -> renderRoom(room, preview) }
    }

    private fun renderRoom(room: ConversationSummary, preview: String) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, dp(16))
        }
        panel.addView(label(roomLabel(room), 20f))
        panel.addView(label("저장 ${room.messageCount}개 · 본인: ${room.selfName ?: "미선택"}\n" +
            if (room.notificationKey == null) "알림 방 연결 안 됨" else "알림 방 연결됨"))
        val capture = SwitchMaterial(this).apply {
            text = "이 방의 알림 수집"
            minHeight = dp(48)
            isChecked = room.captureEnabled
            isEnabled = room.notificationKey != null
            setOnCheckedChangeListener { _, enabled ->
                background("수집 설정을 저장하는 중…", {
                    runCatching { ConversationStore.setCaptureEnabled(applicationContext, room.id, enabled) }.isSuccess
                }) { saved ->
                    if (saved) refresh() else {
                        setOnCheckedChangeListener(null)
                        isChecked = room.captureEnabled
                        isEnabled = false
                        status.text = "수집 설정을 저장하지 못했습니다. 방 목록을 새로고침한 뒤 다시 시도하세요."
                    }
                }
            }
        }
        panel.addView(capture)
        panel.addView(label(preview.ifBlank { "분석할 본인 발화가 부족합니다. 대화 파일을 가져와 주세요." }))
        if (room.notificationKey == null) {
            panel.addView(button("알림 방 연결") { chooseNotificationRoom(room) })
        }
        panel.addView(button("학습 데이터 삭제") { confirmDelete(room) })
        roomList.addView(panel)
    }

    private fun inspectExport(uri: Uri) = background("파일을 확인하는 중…", {
        val raw = readExport(uri)
        val parsed = KakaoExportParser.parse(raw)
        val participants = parsed.participants
        if (parsed.messages.isEmpty() || participants.isEmpty()) {
            throw ExportReadException("분석할 메시지를 찾지 못했습니다. Android 카카오톡의 텍스트 내보내기 파일을 선택하세요.")
        }
        ImportPreview(raw, parsed.title, participants, parsed.messages.size, parsed.warnings,
            ConversationStore.listRooms(applicationContext))
    }) { preview ->
        status.text = "메시지 ${preview.messageCount}개 확인 · 저장 전 본인과 대상 방을 선택하세요."
        AlertDialog.Builder(this)
            .setTitle("대화에서 본인을 선택하세요")
            .setItems(preview.participants.toTypedArray()) { _, index ->
                chooseDestination(preview.raw, preview.title, preview.participants[index], preview.messageCount,
                    preview.warnings, preview.destinations)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun chooseDestination(
        raw: String, exportTitle: String?, selfName: String, messageCount: Int,
        warnings: List<String>, destinations: List<ConversationSummary>
    ) {
        // Existing identity must match; a display name alone never selects a destination.
        val compatible = destinations.filter { it.selfName == null || it.selfName == selfName }
        val labels = listOf("새 방으로 가져오기") + compatible.map(::roomLabel)
        AlertDialog.Builder(this)
            .setTitle("저장할 방을 직접 선택하세요")
            .setItems(labels.toTypedArray()) { _, index ->
                val destination = if (index == 0) null else compatible[index - 1]
                confirmImport(raw, exportTitle, selfName, messageCount, warnings, destination)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun confirmImport(
        raw: String, exportTitle: String?, selfName: String, messageCount: Int,
        warnings: List<String>, destination: ConversationSummary?
    ) {
        val title = exportTitle?.takeIf { it.isNotBlank() } ?: "가져온 대화"
        val warningText = if (warnings.isEmpty()) "없음" else warnings.take(8).joinToString("\n") +
            if (warnings.size > 8) "\n외 ${warnings.size - 8}개" else ""
        AlertDialog.Builder(this)
            .setTitle("대화 가져오기 확인")
            .setMessage("대상: ${destination?.let(::roomLabel) ?: "$title (새 방)"}\n본인: $selfName\n" +
                "읽은 메시지: ${messageCount}개\n\n최근 90일만 보관하고 동일 파일의 중복 메시지는 건너뜁니다. " +
                "동명이인이 있다면 본인 발화를 구분할 수 없으므로 취소하세요.\n\n" +
                "앱이 생성 이력을 확인한 AI 답장은 제외합니다. 이전·외부 자동답장이 섞인 파일은 본인이 직접 작성한 발화만 남겨 준비하세요.\n\n해석 경고:\n$warningText\n\n" +
                "가져오기는 자동답장을 켜지 않습니다. 새 방의 알림 수집은 OFF로 시작합니다.")
            .setPositiveButton("가져오기") { _, _ ->
                background("대화를 저장하고 말투를 분석하는 중…", {
                    val roomId = destination?.id ?: ConversationStore.createRoom(applicationContext, title)
                    ConversationStore.importExport(applicationContext, roomId, raw, selfName)
                }) { result ->
                    AlertDialog.Builder(this)
                        .setTitle("가져오기 완료")
                        .setMessage("추가 ${result.inserted}개 · 중복 ${result.duplicates}개 · 보관 기간 밖 ${result.expired}개\n" +
                            "경고 ${result.warnings.size}개")
                        .setPositiveButton("확인", null).show()
                    refresh()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun chooseNotificationRoom(imported: ConversationSummary) {
        background("감지된 알림 방을 확인하는 중…", {
            ConversationStore.listRooms(applicationContext).filter {
                it.notificationKey != null && it.selfName == null && it.id != imported.id
            }
        }) { observed ->
            if (observed.isEmpty()) {
                AlertDialog.Builder(this).setTitle("감지된 알림 방 없음")
                    .setMessage("알림 접근을 허용한 뒤 연결할 방에서 새 알림을 받아 주세요.")
                    .setPositiveButton("확인", null).show()
            } else {
                AlertDialog.Builder(this).setTitle("실제 알림 방 선택")
                    .setItems(observed.map(::roomLabel).toTypedArray()) { _, index ->
                        val target = observed[index]
                        AlertDialog.Builder(this).setTitle("방 연결 확인")
                            .setMessage("${roomLabel(imported)}\n↔ ${roomLabel(target)}\n\n" +
                                "표시 이름이 같아도 다른 방일 수 있습니다. 올바른 방인지 확인하세요. " +
                                "이후 알림만 연결하며 이전에 수집한 대화는 기존 방에 별도로 남습니다. " +
                                "연결 후 알림 수집은 OFF로 시작합니다. 연결만으로 자동답장을 켜지는 않습니다.")
                            .setPositiveButton("연결") { _, _ ->
                                background("방을 연결하는 중…", {
                                    ConversationStore.linkNotificationRoom(applicationContext, imported.id, target.id)
                                }) { refresh() }
                            }.setNegativeButton("취소", null).show()
                    }.setNegativeButton("취소", null).show()
            }
        }
    }

    private fun confirmDelete(room: ConversationSummary) {
        AlertDialog.Builder(this).setTitle("학습 데이터 삭제")
            .setMessage("${roomLabel(room)}의 원문, 대표 예문과 자동 말투 분석을 삭제합니다. " +
                "직접 입력한 설정은 유지합니다. 수집이 켜져 있으면 이후 새 메시지가 다시 저장됩니다.")
            .setPositiveButton("삭제") { _, _ ->
                background("학습 데이터를 삭제하는 중…", {
                    ConversationStore.deleteLearningData(applicationContext, room.id)
                }) { refresh() }
            }.setNegativeButton("취소", null).show()
    }

    private fun readExport(uri: Uri): String {
        val maxBytes = 10 * 1024 * 1024
        val bytes = contentResolver.openInputStream(uri)?.use { input ->
            ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) throw ExportReadException("10 MiB 이하의 대화 파일을 선택하세요.")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } ?: throw ExportReadException("파일을 열 수 없습니다. 파일 접근 권한을 확인하세요.")
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    }

    private fun <T> background(message: String, work: () -> T, complete: (T) -> Unit) {
        if (busy) return
        busy = true
        setControlsEnabled(content, false)
        status.text = message
        activityScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { work() }
                busy = false
                setControlsEnabled(content, true)
                complete(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                busy = false
                setControlsEnabled(content, true)
                // Do not copy database/IO exception messages that could expose message contents.
                status.text = if (error is ExportReadException) error.message else
                    "처리하지 못했습니다. UTF-8 텍스트 파일, 파일 크기와 저장 공간을 확인한 뒤 다시 시도하세요."
            }
        }
    }

    private fun setControlsEnabled(view: View, enabled: Boolean) {
        if (view is MaterialButton || view is SwitchMaterial) {
            if (!enabled) view.tag = view.isEnabled
            view.isEnabled = enabled && view.tag != false
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            setControlsEnabled(view.getChildAt(index), enabled)
        }
    }

    private fun roomLabel(room: ConversationSummary) = "${room.title} · ${room.id.takeLast(8)}"
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float = 15f) = TextView(this).apply {
        text = value
        textSize = size
        setPadding(0, dp(8), 0, dp(8))
    }
    private fun button(value: String, action: () -> Unit) = MaterialButton(this).apply {
        text = value
        minHeight = dp(48)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { action() }
    }
}
