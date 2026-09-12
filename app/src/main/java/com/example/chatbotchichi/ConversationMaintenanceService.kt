package com.example.kakaotalkautobot

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import java.util.concurrent.Executors

/** Best-effort daily retention, also enforced on database access and during active use. */
class ConversationMaintenanceService : JobService() {
    private val worker = Executors.newSingleThreadExecutor()
    override fun onStartJob(params: JobParameters): Boolean {
        worker.execute {
            val failed = runCatching { ConversationStore.maintain(applicationContext) }.isFailure
            jobFinished(params, failed)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean = true
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }

    companion object {
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val id = 0x434F4E
            if (scheduler.getPendingJob(id) == null) scheduler.schedule(
                JobInfo.Builder(id, ComponentName(context, ConversationMaintenanceService::class.java))
                    .setPeriodic(24L * 60 * 60 * 1000).build()
            )
        }
    }
}
