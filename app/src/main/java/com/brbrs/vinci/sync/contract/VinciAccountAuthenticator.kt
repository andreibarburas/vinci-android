package com.brbrs.vinci.sync.contract

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder

/**
 * Feature #ContactContract -- a near-empty AccountAuthenticator, required by Android for any
 * account type that wants to own ContactsContract rows. There's no real add-account UI behind
 * it -- see VinciAccount's doc comment.
 */
class VinciAccountAuthenticator(context: Context) : AbstractAccountAuthenticator(context) {

    override fun editProperties(response: AccountAuthenticatorResponse?, accountType: String?): Bundle =
        throw UnsupportedOperationException()

    override fun addAccount(
        response: AccountAuthenticatorResponse?,
        accountType: String?,
        authTokenType: String?,
        requiredFeatures: Array<out String>?,
        options: Bundle?,
    ): Bundle = Bundle().apply {
        putString(AccountManager.KEY_ACCOUNT_NAME, VinciAccount.ACCOUNT_NAME)
        putString(AccountManager.KEY_ACCOUNT_TYPE, VinciAccount.ACCOUNT_TYPE)
    }

    override fun confirmCredentials(response: AccountAuthenticatorResponse?, account: Account?, options: Bundle?): Bundle? = null

    override fun getAuthToken(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?,
    ): Bundle = throw UnsupportedOperationException()

    override fun getAuthTokenLabel(authTokenType: String?): String? = null

    override fun updateCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?,
    ): Bundle = throw UnsupportedOperationException()

    override fun hasFeatures(response: AccountAuthenticatorResponse?, account: Account?, features: Array<out String>?): Bundle =
        Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false) }
}

/** Bound by the OS whenever it needs this account type's authenticator -- see
 *  res/xml/vinci_authenticator.xml and the manifest's <service> entry for it. */
class VinciAuthenticatorService : Service() {
    private lateinit var authenticator: VinciAccountAuthenticator

    override fun onCreate() {
        super.onCreate()
        authenticator = VinciAccountAuthenticator(this)
    }

    override fun onBind(intent: Intent?): IBinder = authenticator.iBinder
}
