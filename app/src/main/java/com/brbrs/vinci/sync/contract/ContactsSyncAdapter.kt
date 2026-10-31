package com.brbrs.vinci.sync.contract

import android.accounts.Account
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.os.Bundle
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * Feature #ContactContract -- the OS-facing sync adapter. Bound whenever Android decides a sync
 * for Vinci's stub account is due: a DIRTY row under that account (the user edited a contact in
 * the system Contacts app), a pull-to-refresh there, or the periodic schedule VinciAccount.get()
 * turns on via ContentResolver.setSyncAutomatically.
 */
class ContactsSyncAdapter(
    context: Context,
    autoInitialize: Boolean,
    private val contractRepository: ContactContractRepository,
) : AbstractThreadedSyncAdapter(context, autoInitialize) {

    override fun onPerformSync(
        account: Account,
        extras: Bundle,
        authority: String,
        provider: ContentProviderClient,
        syncResult: SyncResult,
    ) {
        runCatching {
            runBlocking { contractRepository.sync() }
        }.onFailure {
            syncResult.stats.numIoExceptions++
        }
    }
}

/** Bound by the OS to obtain the sync adapter -- see res/xml/vinci_syncadapter.xml and the
 *  manifest's <service> entry for it. */
@AndroidEntryPoint
class ContactsSyncAdapterService : Service() {

    @Inject lateinit var contractRepository: ContactContractRepository

    override fun onBind(intent: Intent?): IBinder {
        synchronized(lock) {
            val existing = adapterInstance
            if (existing != null) return existing.syncAdapterBinder
            val created = ContactsSyncAdapter(applicationContext, true, contractRepository)
            adapterInstance = created
            return created.syncAdapterBinder
        }
    }

    companion object {
        private val lock = Any()
        @Volatile private var adapterInstance: ContactsSyncAdapter? = null
    }
}
