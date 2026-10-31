package com.brbrs.vinci.work

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object CalendarNudgeScheduler {

    private const val UNIQUE_WORK_NAME = "vinci_calendar_nudges"

    /**
     * Scheduled every 15 minutes (WorkManager's minimum period). Each run looks at the last
     * 30 minutes of ended events, so even if we miss a tick the next one still catches up.
     */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<CalendarNudgeWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }
}
