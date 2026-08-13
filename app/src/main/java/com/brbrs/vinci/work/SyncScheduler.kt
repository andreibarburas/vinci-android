package com.brbrs.vinci.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

object SyncScheduler {

    private const val UNIQUE_WORK_NAME = "vinci_sync_retry"

    /**
     * Queues a background sync to run as soon as a network connection is available, and does
     * nothing if one is already queued (ExistingWorkPolicy.KEEP) -- one pending job is enough
     * to flush everything unsynced whenever it eventually runs, so there's no need to stack a
     * new one per failed save.
     */
    fun scheduleRetry(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }
}
