package com.brbrs.vinci.work

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

object FollowUpScheduler {

    private const val UNIQUE_WORK_NAME = "vinci_followup_daily"

    /**
     * Schedules the daily follow-up check to run at the user-chosen local time. Uses
     * REPLACE so a time change (via Settings) immediately re-anchors the next run. If the
     * chosen time already passed today, the first run is scheduled for the same time
     * tomorrow; subsequent runs repeat every 24h.
     */
    fun schedule(context: Context, hour: Int, minute: Int) {
        val now  = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE,      minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now.timeInMillis) add(Calendar.DAY_OF_YEAR, 1)
        }
        val initialDelayMs = next.timeInMillis - now.timeInMillis

        val request = PeriodicWorkRequestBuilder<FollowUpWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }
}
