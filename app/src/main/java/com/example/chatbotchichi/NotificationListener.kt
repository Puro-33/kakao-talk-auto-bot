package com.example.kakaotalkautobot

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import java.lang.ref.WeakReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class NotificationListener : NotificationListenerService() {
    companion object {
        @Volatile private var connectedListener = WeakReference<NotificationListener>(null)

        /** Returns whether Android has granted this app notification-listener access. */
        fun isAccessEnabled(context: Context): Boolean =
            packageName(context) in NotificationManagerCompat.getEnabledListenerPackages(context)

        private fun packageName(context: Context): String = context.applicationContext.packageName

        /** Refresh room metadata only; existing messages are never replayed or answered. */
        suspend fun refreshObservedRooms(): Boolean {
            val listener = connectedListener.get() ?: return false
            return listener.refreshRoomMetadata()
        }
    }

    private val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processingMutex = Mutex()
    private val processedNotifications = mutableMapOf<String, Long>()
    @Volatile private var listenerConnected = false
    private var debugReceiverRegistered = false

    internal data class IncomingHandlingPlan(
        val shouldCapture: Boolean,
        val shouldAttemptReply: Boolean,
        val skippedReason: String? = null
    )

    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != "com.example.kakaotalkautobot.DEBUG_MSG") return
            val room = intent.getStringExtra("room") ?: "디버그방"
            val msg = intent.getStringExtra("msg") ?: return
            val sender = intent.getStringExtra("sender") ?: "테스터"
            processIncoming(room, msg, sender, room != sender, SessionReplier(this@NotificationListener, room, true))
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        listenerConnected = true
        connectedListener = WeakReference(this)
        updateStatus("알림 리스너 연결됨", true)
        if (!debugReceiverRegistered) {
            val filter = IntentFilter("com.example.kakaotalkautobot.DEBUG_MSG")
            ContextCompat.registerReceiver(this, debugReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            debugReceiverRegistered = true
        }
        processingScope.launch { refreshRoomMetadata() }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        clearConnection()
        updateStatus("연결 끊김", false)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        processingScope.launch { processingMutex.withLock { handleNotification(sbn) } }
    }

    override fun onDestroy() {
        clearConnection()
        processingScope.cancel()
        super.onDestroy()
    }

    private fun clearConnection() {
        listenerConnected = false
        if (connectedListener.get() === this) connectedListener = WeakReference(null)
        if (debugReceiverRegistered) {
            unregisterReceiver(debugReceiver)
            debugReceiverRegistered = false
        }
    }

    private suspend fun refreshRoomMetadata(): Boolean = withContext(Dispatchers.IO) {
        processingMutex.withLock {
            if (!listenerConnected || connectedListener.get() !== this@NotificationListener ||
                packageName !in NotificationManagerCompat.getEnabledListenerPackages(this@NotificationListener)) {
                return@withLock false
            }
            try {
                val notifications = activeNotifications ?: return@withLock false
                notifications.forEach { sbn ->
                    if (!listenerConnected) return@withLock false
                    if (sbn.packageName == "com.kakao.talk" &&
                        sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0) {
                        val metadata = roomMetadata(sbn) ?: return@forEach
                        ConversationStore.observeNotification(this@NotificationListener, metadata.identity, metadata.title)
                    }
                }
                listenerConnected
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // No notification bodies or exception details are copied into logs.
                false
            }
        }
    }

    private fun roomMetadata(sbn: StatusBarNotification): NotificationRoomMetadata? {
        val extras = sbn.notification.extras ?: return null
        val shortcut = if (android.os.Build.VERSION.SDK_INT >= 26) sbn.notification.shortcutId else null
        return NotificationRoomMetadata.from(
            userId = sbn.user.hashCode(), packageName = sbn.packageName, shortcutId = shortcut,
            notificationKey = sbn.key,
            conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString(),
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            summaryText = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString(),
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        )
    }

    private fun handleNotification(sbn: StatusBarNotification) {
        if (sbn.packageName != "com.kakao.talk" && sbn.packageName != packageName && sbn.packageName != "com.android.shell") return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val lastTime = processedNotifications[sbn.key] ?: 0L
        if (sbn.postTime <= lastTime) return
        processedNotifications[sbn.key] = sbn.postTime
        if (processedNotifications.size > 200) {
            val iterator = processedNotifications.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }

        try {
            val extras = sbn.notification.extras ?: return
            var msg = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            if (msg.isNullOrBlank()) {
                msg = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            }
            val metadata = roomMetadata(sbn) ?: return
            val room = metadata.title
            var sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "알수없음"

            val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            if (!messages.isNullOrEmpty()) {
                val last = messages.last()
                if (last is Bundle) {
                    val text = last.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                    val senderName = last.getCharSequence("sender")?.toString()
                    if (!text.isNullOrBlank()) msg = text
                    if (!senderName.isNullOrBlank()) sender = senderName
                }
            }
            if (!msg.isNullOrBlank() && room == sender) {
                val idx = msg.indexOf(": ")
                if (idx in 1..40) {
                    val possibleSender = msg.substring(0, idx).trim()
                    val possibleMsg = msg.substring(idx + 2).trim()
                    if (possibleSender.isNotBlank() && possibleMsg.isNotBlank()) {
                        sender = possibleSender
                        msg = possibleMsg
                    }
                }
            }

            if (msg.isNullOrBlank()) return
            val conversationId = ConversationStore.observeNotification(this, metadata.identity, room)
            SessionManager.bindSession(this, conversationId, sbn.notification, sbn.packageName)
            processIncoming(room, msg, sender, room != sender, SessionReplier(this, conversationId, false), conversationId,
                ConversationStore.digest("${sbn.key}:${sbn.postTime}:$sender:$msg"), sbn.postTime)
        } catch (error: Throwable) {
            Log.e("AutoReply-Listener", "알림 처리 실패", error)
        }
    }

    private fun processIncoming(room: String, msg: String, sender: String, isGroupChat: Boolean, replier: SessionReplier,
        conversationId: String = room, eventKey: String? = null, timestamp: Long = System.currentTimeMillis()) {
        val roomConfig = BotManager.findRoomConfig(this, room, sender, conversationId)
        val handlingPlan = incomingHandlingPlan(roomConfig, AppSettings.isAiReplyEnabled(this))
        val capture = handlingPlan.shouldCapture && ConversationStore.isCaptureEnabled(this, conversationId)
        if (capture) {
            AppSettings.ensureRoomTargetExists(this, room, enabled = false)
            ConversationStore.record(this, conversationId, sender, msg, timestamp, MessageKind.OTHER, "notification", eventKey)
        }
        if (capture) UiLogger.log(
            this,
            "IN",
            "[$room] $sender: $msg",
            roomName = room,
            speaker = sender,
            serverMessage = msg
        )
        if (!handlingPlan.shouldAttemptReply || roomConfig == null) {
            handlingPlan.skippedReason?.let { reason ->
                UiLogger.log(
                    this,
                    "OUT_SKIP",
                    "[$room] $reason",
                    roomName = room,
                    speaker = "시스템",
                    serverMessage = reason,
                    eventReason = reason
                )
            }
            return
        }
        AutoReplyEngine.onIncoming(this, room, msg, sender, isGroupChat, replier, roomConfig, conversationId)
    }

    internal fun incomingHandlingPlan(
        roomConfig: AutoReplyConfig?,
        aiReplyEnabled: Boolean
    ): IncomingHandlingPlan {
        val shouldCapture = roomConfig?.captureEnabled ?: true
        val shouldAttemptReply = aiReplyEnabled && roomConfig?.replyEnabled == true
        val skippedReason = when {
            shouldAttemptReply -> null
            !aiReplyEnabled -> "AI 답장 OFF 상태입니다."
            roomConfig == null -> "응답 설정이 없는 방입니다."
            !roomConfig.replyEnabled -> "방별 답장이 OFF 상태입니다."
            else -> "답장 조건을 충족하지 못했습니다."
        }
        return IncomingHandlingPlan(
            shouldCapture = shouldCapture,
            shouldAttemptReply = shouldAttemptReply,
            skippedReason = skippedReason
        )
    }

    private fun updateStatus(msg: String, isConnected: Boolean) {
        StatusStore.save(this, msg, isConnected)
        val intent = Intent("com.example.kakaotalkautobot.STATUS_UPDATE")
        intent.putExtra("status", msg)
        intent.putExtra("connected", isConnected)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }
}
