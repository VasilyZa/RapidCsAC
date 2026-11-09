package com.xiaobai.rapidcsac.platform

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.xiaobai.rapidcsac.RapidApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/**
 * Opportunistic background sync. Android schedules execution according to battery and network
 * state.
 */
class BackgroundSync : JobService() {
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val graph = (application as RapidApplication).graph
        work =
            graph.scope.launch {
                try {
                    withTimeout(25_000) {
                        graph.loading.first { !it }
                        val repo = graph.repository.value ?: return@withTimeout
                        repo.start()
                        repo.awaitReady()
                        repo.refresh()
                        repo.conversations.value
                            .filter { it.unread > 0 && !it.muted }
                            .forEach {
                                Notifications.message(this@BackgroundSync, it, repo.account.key)
                            }
                    }
                } catch (_: Exception) {
                    // The next scheduled window or foreground reconnect handles a transient
                    // failure.
                } finally {
                    if (!graph.foreground) graph.repository.value?.pause()
                    jobFinished(params, false)
                }
            }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true
    }

    companion object {
        private const val ID = 5179

        fun schedule(context: Context, enabled: Boolean) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (!enabled) {
                scheduler.cancel(ID)
                return
            }
            scheduler.schedule(
                JobInfo.Builder(ID, ComponentName(context, BackgroundSync::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(15 * 60_000L)
                    .build()
            )
        }
    }
}
