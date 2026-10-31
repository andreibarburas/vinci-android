package com.brbrs.vinci.sync.contract

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

private const val UNIQUE_WORK_NAME = "vinci-contact-contract-sync"
private const val PERIODIC_WORK_NAME = "vinci-contact-contract-sync-periodic"

/**
 * Feature #ContactContract -- periodic/manual background sync, independent of the OS's own
 * sync-adapter triggering (DIRTY rows, pull-to-refresh in the system Contacts app): this is what
 * keeps the mirror fresh even when nothing local has changed, same role Zaman's CalDavSyncWorker
 * plays for calendars. Contacts change far less often than calendar events, so a longer default
 * interval than Zaman's calendar sync is fine here.
 */
@HiltWorker
class ContactContractSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val contractRepository: ContactContractRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = runCatching {
        contractRepository.sync()
    }.fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })

    companion object {
        /** Enqueues an immediate one-shot sync -- used right after the user turns the Settings
         *  toggle on, so the mirror populates without waiting for the next periodic window. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ContactContractSyncWorker>().build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        /** Default: once an hour. Contacts don't need Zaman-calendar-style freshness, and this
         *  keeps the mirror caught up even on a day with no local edits and no server changes
         *  for the sync adapter to react to. */
        fun enqueuePeriodic(context: Context, intervalMinutes: Long = 60L) {
            val clamped = intervalMinutes.coerceAtLeast(15L)
            val request = PeriodicWorkRequestBuilder<ContactContractSyncWorker>(clamped, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
        }
    }
}
