package com.brbrs.vinci

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class VinciApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Safety net: if the app was killed (or the last session ended) with interactions
        // still unsynced -- e.g. process death while offline -- this makes sure they still
        // get a background retry the next time a connection is available, even if the app is
        // never reopened. SyncScheduler.scheduleRetry() is also called right after any failed
        // best-effort upload, so this call is mostly a backstop for that edge case.
        com.brbrs.vinci.work.SyncScheduler.scheduleRetry(this)
    }
}
