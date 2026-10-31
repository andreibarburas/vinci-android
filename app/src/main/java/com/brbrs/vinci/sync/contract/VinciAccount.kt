package com.brbrs.vinci.sync.contract

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.provider.ContactsContract

/**
 * Feature #ContactContract -- the account ContactsContract rows attach to. Same stub-account
 * trick Zaman uses for CalendarContract (and the same one DAVx5 itself used): there's no real
 * login here, Vinci's actual Nextcloud credentials live in AuthRepository/DataStore. This
 * account exists purely because RawContacts rows are required to belong to some Android account.
 */
object VinciAccount {
    const val ACCOUNT_TYPE = "com.brbrs.vinci"
    const val ACCOUNT_NAME = "Vinci"

    /** Returns the existing account, or creates it (and marks it syncable for the contacts
     *  provider) the first time ContactContract sync is turned on. */
    fun get(context: Context): Account {
        val manager = AccountManager.get(context)
        val existing = manager.getAccountsByType(ACCOUNT_TYPE).firstOrNull()
        if (existing != null) return existing
        val account = Account(ACCOUNT_NAME, ACCOUNT_TYPE)
        manager.addAccountExplicitly(account, null, null)
        ContentResolver.setIsSyncable(account, ContactsContract.AUTHORITY, 1)
        ContentResolver.setSyncAutomatically(account, ContactsContract.AUTHORITY, true)
        return account
    }

    /** Called when the user turns ContactContract sync off in Settings -- removing the account
     *  cascades and removes every RawContacts/Data row that belonged to it, so nothing orphaned
     *  lingers in the system Contacts app. */
    fun remove(context: Context) {
        val manager = AccountManager.get(context)
        manager.getAccountsByType(ACCOUNT_TYPE).forEach { account ->
            @Suppress("DEPRECATION")
            manager.removeAccountExplicitly(account)
        }
    }

    fun existsOrNull(context: Context): Account? =
        AccountManager.get(context).getAccountsByType(ACCOUNT_TYPE).firstOrNull()
}
