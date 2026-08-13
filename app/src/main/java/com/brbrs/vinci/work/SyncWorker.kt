package com.brbrs.vinci.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.brbrs.vinci.network.WebDavRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Runs the same "catch up with Nextcloud" work as a regular Sync -- upload anything logged
 * locally while offline, pull down anything logged elsewhere, and clean up any duplicates --
 * but in the background, triggered by WorkManager once a network connection is available
 * rather than requiring the app to be open.
 *
 * This exists specifically for the case where an interaction was logged with no connection to
 * the (VPN/home-network-only) Nextcloud server: the interaction itself saves instantly and
 * locally regardless, but the upload attempt right afterwards can fail. See
 * SyncScheduler.scheduleRetry(), called from that failure path.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val webDavRepository: WebDavRepository,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            webDavRepository.syncPendingLogs()
            webDavRepository.restoreFromNextcloud()
            webDavRepository.dedupeCallLogs()
            Result.success()
        } catch (e: Exception) {
            // Likely still no real connection to the Nextcloud server specifically (e.g. general
            // internet is up but the VPN isn't) even though WorkManager's NetworkType.CONNECTED
            // constraint was satisfied. Ask WorkManager to back off and try again later rather
            // than giving up -- whatever's pending stays safely queued either way.
            Result.retry()
        }
    }
}
