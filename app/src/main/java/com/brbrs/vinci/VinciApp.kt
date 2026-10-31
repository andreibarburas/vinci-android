package com.brbrs.vinci

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.brbrs.vinci.auth.AuthRepository
import com.brbrs.vinci.ui.theme.DisplayPreferencesRepository
import com.brbrs.vinci.sync.contract.ContactContractPreferences
import com.brbrs.vinci.sync.contract.ContactContractSyncStatus
import com.brbrs.vinci.sync.contract.ContactContractSyncWorker
import com.brbrs.vinci.work.CalendarNudgeScheduler
import com.brbrs.vinci.work.FollowUpScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class VinciApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var displayPrefs: DisplayPreferencesRepository
    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var contactContractPreferences: ContactContractPreferences
    @Inject lateinit var contactContractSyncStatus: ContactContractSyncStatus

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // Only queue Nextcloud sync retry when in Nextcloud mode.
            // Local-mode users have no server to sync to.
            val storageMode = authRepository.storageMode.first()
            if (storageMode != "local") {
                com.brbrs.vinci.work.SyncScheduler.scheduleRetry(this@VinciApp)
            }

            // One-off schedule of the periodic jobs based on current prefs. Settings reschedules
            // them whenever the user changes the time or toggles them on/off.
            val prefs = displayPrefs.preferences.first()
            if (prefs.followUpReminderEnabled) {
                FollowUpScheduler.schedule(
                    this@VinciApp,
                    prefs.followUpReminderHour,
                    prefs.followUpReminderMinute,
                )
            } else {
                FollowUpScheduler.cancel(this@VinciApp)
            }
            if (prefs.calendarNudgesEnabled) {
                CalendarNudgeScheduler.schedule(this@VinciApp)
            } else {
                CalendarNudgeScheduler.cancel(this@VinciApp)
            }

            // Feature #ContactContract -- re-arm the periodic mirror on every app launch,
            // same reasoning as the two schedulers above: an unconditional re-enqueue is a
            // cheap no-op when nothing changed (ExistingPeriodicWorkPolicy.UPDATE), and it's
            // what reschedules things correctly after e.g. an app update resets WorkManager.
            // Also fire an immediate one-shot (ExistingWorkPolicy.KEEP, so this is a no-op if
            // one's already running): enqueuePeriodic() alone only guarantees a run within the
            // next ~60 minutes, so a sync left mid-way through a large address book when the
            // app process was killed (an app update, Android reclaiming memory, the user
            // swiping it away) would otherwise sit unfinished until that window arrives instead
            // of picking back up on next launch.
            // Seed the "last synced" line in Settings from what was persisted before this
            // process started, so it has something to show even before a new sync runs.
            contactContractSyncStatus.restoreLast(
                contactContractPreferences.lastSyncedAt.first(),
                contactContractPreferences.lastSyncedCount.first(),
            )

            if (contactContractPreferences.enabled.first()) {
                ContactContractSyncWorker.enqueuePeriodic(this@VinciApp)
                ContactContractSyncWorker.enqueue(this@VinciApp)
            } else {
                ContactContractSyncWorker.cancelPeriodic(this@VinciApp)
            }
        }
    }
}
