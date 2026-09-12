package com.example.kakaotalkautobot

import android.content.Context
import android.content.Intent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object UiLogger {
    private val allowedLabels = setOf("IN", "OUT", "OUT_FAIL", "OUT_SKIP")

    fun log(
        context: Context,
        label: String,
        message: String,
        roomName: String? = null,
        speaker: String? = null,
        serverMessage: String? = null,
        eventReason: String? = null,
        trackStats: Boolean = true
    ) {
        if (!allowedLabels.contains(label)) return
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        // Raw conversations live only in the consented conversation database, not in an independent log copy.
        val reason = eventReason?.let { ReplyStatsStore.normalizeReason(it) }
        val line = "[$time][$label] ${reason ?: "event"}"
        LogStore.append(context, line)
        if (trackStats) {
            ReplyStatsStore.record(context, label, eventReason ?: serverMessage)
        }
        val intent = Intent("com.example.kakaotalkautobot.LOG_UPDATE")
        intent.putExtra("log", line)
        intent.setPackage(context.packageName)
        context.sendBroadcast(intent)
    }
}
