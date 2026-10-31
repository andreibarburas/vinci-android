package com.brbrs.vinci.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import com.brbrs.vinci.MainActivity
import com.brbrs.vinci.data.CallLogDao
import com.brbrs.vinci.data.ContactDao
import com.brbrs.vinci.ui.theme.CALLER_PREVIEW_CONTEXT
import com.brbrs.vinci.ui.theme.CALLER_PREVIEW_OFF
import com.brbrs.vinci.ui.theme.CALLER_PREVIEW_PROMINENCE
import com.brbrs.vinci.ui.theme.DisplayPreferencesRepository
import com.brbrs.vinci.util.normalizePhone
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Listens for PHONE_STATE broadcasts.
 *
 * - On RINGING: looks up the incoming number against past Vinci interactions
 *   (logged against a contact OR as an unknown-number log) and shows a
 *   "Caller history" notification immediately, while the phone is still ringing.
 * - On IDLE (call ended): shows a "Log this call?" notification, prioritizing
 *   the number captured at RINGING/OFFHOOK over a CallLog query (which can
 *   race with the system writing the new entry).
 *
 * State is persisted in SharedPreferences because BroadcastReceiver
 * instances are not reused between broadcasts -- instance variables are lost.
 *
 * Dual-SIM: on IDLE we also read the latest CallLog row's PHONE_ACCOUNT_ID (the
 * subscription id) and look up the label the user has set for that SIM in Settings.
 * The label is passed through the notification tap to pre-tag the interaction.
 *
 * VoIP: calls routed through WhatsApp/Signal/etc. come in via a non-SIM phone
 * account. We detect that by querying TelecomManager for the account's
 * capabilities (CAPABILITY_SIM_SUBSCRIPTION missing = VoIP) and show a simpler
 * "call from another app" prompt without the quick-note reply, since the number
 * and contact match aren't reliable for VoIP.
 */
@AndroidEntryPoint
class CallReceiver : BroadcastReceiver() {

    @Inject lateinit var callLogDao: CallLogDao
    @Inject lateinit var contactDao: ContactDao
    @Inject lateinit var displayPrefs: DisplayPreferencesRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.intent.action.PHONE_STATE") return

        val state       = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val incomingNum = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""
        val prefs       = prefs(context)

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                prefs.edit()
                    .putBoolean(KEY_IN_PROGRESS, true)
                    .putString(KEY_NUMBER, incomingNum)
                    .putBoolean(KEY_IS_OUTGOING, false)
                    .apply()

                if (incomingNum.isNotBlank()) {
                    checkCallerHistory(context, incomingNum)
                }
            }

            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (!prefs.getBoolean(KEY_IN_PROGRESS, false)) {
                    // No ringing phase = outgoing call. Number not known yet here.
                    prefs.edit()
                        .putBoolean(KEY_IN_PROGRESS, true)
                        .putBoolean(KEY_IS_OUTGOING, true)
                        .putLong(KEY_START_TIME, System.currentTimeMillis())
                        .apply()
                } else {
                    prefs.edit()
                        .putLong(KEY_START_TIME, System.currentTimeMillis())
                        .apply()
                }
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                val wasInProgress = prefs.getBoolean(KEY_IN_PROGRESS, false)
                val startTime     = prefs.getLong(KEY_START_TIME, 0L)
                val ringingNumber = prefs.getString(KEY_NUMBER, "") ?: ""
                val isOutgoing    = prefs.getBoolean(KEY_IS_OUTGOING, false)
                val duration      = if (startTime > 0) ((System.currentTimeMillis() - startTime) / 1000).toInt() else 0

                prefs.edit().clear().apply()

                if (!wasInProgress) return
                if (duration < 2) return

                // The latest CallLog row carries the number for outgoing calls (not populated in
                // EXTRA_INCOMING_NUMBER) AND the PhoneAccountHandle for every call, which is
                // what tells us whether this was a SIM call or a VoIP one. The row needs a brief
                // moment to appear so we wait on outgoing calls (ringingNumber is blank there).
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        if (isOutgoing && ringingNumber.isBlank()) Thread.sleep(1200)
                        val latest = resolveLatestCallInfo(context)

                        val resolvedNumber = when {
                            ringingNumber.isNotBlank() -> ringingNumber
                            latest != null             -> latest.number
                            else                       -> ""
                        }

                        val isVoip = latest != null && !isSimAccount(context, latest.componentName, latest.accountId)

                        if (isVoip) {
                            showVoipCallNotification(context)
                            return@launch
                        }

                        // SIM call: look up the user-set label for the subscription this call
                        // came in on, if any. Blank is fine -- no chip is prefilled.
                        val simLabel = displayPrefs.simLabelFor(latest?.accountId)
                        val contactId = resolveContactId(context, resolvedNumber)

                        showLogCallNotification(
                            context    = context,
                            number     = resolvedNumber,
                            contactId  = contactId,
                            isOutgoing = isOutgoing,
                            duration   = duration,
                            simLabel   = simLabel,
                        )
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("vinci_call_state", Context.MODE_PRIVATE)

    // -- Caller history (on RINGING) -------------------------------------------

    /**
     * Looks up any past Vinci interactions for this number and shows a notification while
     * the phone is still ringing. The query is intentionally broad:
     *
     *  • If the number resolves to a known contact, we use [getRecentLogsForContact] which
     *    returns both primary logs AND interactions where they appeared as a participant
     *    (e.g. a group meeting logged from someone else's profile). This was the original
     *    bug: participant interactions have no normalizedPhone, so a number-only query
     *    missed them entirely.
     *
     *  • For unknown numbers we fall back to the number-only query.
     */
    private fun checkCallerHistory(context: Context, incomingNumber: String) {
        val normalized = normalizePhone(incomingNumber)
        if (normalized.isBlank()) return

        // The BroadcastReceiver may be destroyed once onReceive returns --
        // use goAsync() to keep it alive briefly for the DB query.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val mode = displayPrefs.preferences.first().callerPreviewMode
                if (mode == CALLER_PREVIEW_OFF) return@launch

                // Try to resolve a known contact first so we can include participant logs.
                val contact = runCatching { contactDao.getContactByPhone(normalized) }.getOrNull()
                val recent = if (contact != null) {
                    callLogDao.getRecentLogsForContact(
                        contactId  = contact.id,
                        contactUid = contact.cardavUid,
                        limit      = 3,
                    )
                } else {
                    callLogDao.getRecentLogsForNumber(normalized, limit = 3)
                }

                if (recent.isNotEmpty()) {
                    showCallerHistoryNotification(context, incomingNumber, recent, mode)
                }
            } catch (e: Exception) {
                // best-effort -- ignore failures
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showCallerHistoryNotification(
        context: Context,
        number: String,
        logs: List<com.brbrs.vinci.data.CallLogEntity>,
        mode: String,
    ) {
        val channelId = "vinci_caller_history"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // IMPORTANCE_MAX unlocks heads-up + full-screen presentation; IMPORTANCE_HIGH is for
        // the quieter "More context" mode. The channel is created once per importance level
        // so switching modes rebuilds it correctly after an uninstall/reinstall.
        val importance = if (mode == CALLER_PREVIEW_PROMINENCE) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_HIGH
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Caller history", importance).apply {
                description = "Shows your past notes about an incoming caller"
            }
        )

        val sdf     = SimpleDateFormat("MMM d", Locale.getDefault())
        val latest  = logs.first()
        val lines   = logs.map { log ->
            val dateStr = sdf.format(Date(log.callTimestamp))
            val note    = log.notes.ifBlank { log.reason.ifBlank { log.outcome } }
            if (note.isNotBlank()) "$dateStr · $note" else "$dateStr · ${log.interactionType}"
        }
        val summaryText = lines.joinToString("\n")
        val contentText = lines.first()

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("action", "view_history")
            if (latest.contactId != null) putExtra("contact_id", latest.contactId)
            putExtra("phone", number)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, (number + "_hist").hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val inboxStyle = NotificationCompat.InboxStyle().also { style ->
            lines.forEach { style.addLine(it) }
            style.setBigContentTitle("Caller history: ${latest.contactName.ifBlank { number }}")
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("Caller history: ${latest.contactName.ifBlank { number }}")
            .setContentText(contentText)
            .setStyle(inboxStyle)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        if (mode == CALLER_PREVIEW_PROMINENCE) {
            // Full-screen intent asks Android to present the notification as a heads-up card
            // over the ringing screen on devices that honor it, without the "Draw over other
            // apps" permission that a true floating overlay would require.
            builder.setFullScreenIntent(pendingIntent, true)
            builder.setCategory(NotificationCompat.CATEGORY_CALL)
        }

        nm.notify(CALLER_HISTORY_NOTIFICATION_ID, builder.build())
    }

    // -- Post-call log notification ---------------------------------------------

    /** Minimal view of the most recent CallLog row: enough to identify the SIM/account. */
    private data class LatestCallInfo(
        val number: String,
        val componentName: String?,
        val accountId: String?,
    )

    private fun resolveLatestCallInfo(context: Context): LatestCallInfo? {
        return try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME,
                    CallLog.Calls.PHONE_ACCOUNT_ID,
                ),
                null, null,
                "${CallLog.Calls.DATE} DESC",
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                LatestCallInfo(
                    number        = cursor.getString(0).orEmpty(),
                    componentName = cursor.getString(1),
                    accountId     = cursor.getString(2),
                )
            }
        } catch (e: SecurityException) { null }
    }

    /**
     * True if the given PhoneAccount is backed by a SIM subscription (regular telephony). A
     * null account is treated as a SIM call by default so we don't fall back to the VoIP
     * prompt when permissions block the lookup.
     */
    private fun isSimAccount(context: Context, componentName: String?, accountId: String?): Boolean {
        if (componentName.isNullOrBlank() || accountId.isNullOrBlank()) return true
        val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return true
        val cn = ComponentName.unflattenFromString(componentName) ?: return true
        val handle = PhoneAccountHandle(cn, accountId)
        val account = try { tm.getPhoneAccount(handle) } catch (e: SecurityException) { null } ?: return true
        return account.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)
    }

    private fun resolveContactId(context: Context, number: String): Long? {
        if (number.isBlank()) return null
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number)
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup._ID),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
        } catch (e: Exception) { null }
    }

    private fun showLogCallNotification(
        context: Context,
        number: String,
        contactId: Long?,
        isOutgoing: Boolean,
        duration: Int,
        simLabel: String,
    ) {
        val channelId = "vinci_call_log"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        nm.createNotificationChannel(
            NotificationChannel(channelId, "Call log reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Prompts you to log a call after it ends"
            }
        )

        // Open intent -- deep link into log screen. contactId == null routes to
        // "no contact" mode (unknown-number interaction) in CallLogScreen.
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("action", "log_call")
            if (contactId != null) putExtra("contact_id", contactId)
            putExtra("phone", number)
            putExtra("is_outgoing", isOutgoing)
            putExtra("duration", duration)
            putExtra("sim_label", simLabel)
        }
        val requestCode = System.currentTimeMillis().toInt()
        val pendingIntent = PendingIntent.getActivity(
            context, requestCode, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val direction    = if (isOutgoing) "Outgoing" else "Incoming"
        val durationStr  = if (duration >= 60) "${duration / 60}m ${duration % 60}s" else "${duration}s"
        val contactLabel = contactId?.let { resolveContactName(context, it) } ?: number.ifBlank { "Unknown" }
        val titlePrefix  = if (simLabel.isNotBlank()) "$simLabel · " else ""

        // Quick-add action -- inline text reply that saves a note without opening the app
        val remoteInput = RemoteInput.Builder(QuickNoteReceiver.KEY_NOTE)
            .setLabel("Add a quick note...")
            .build()

        val quickAddIntent = Intent(context, QuickNoteReceiver::class.java).apply {
            putExtra("phone", number)
            putExtra("contact_id", contactId ?: -1L)
            putExtra("contact_name", contactLabel)
            putExtra("is_outgoing", isOutgoing)
            putExtra("duration", duration)
            putExtra("sim_label", simLabel)
            putExtra("notification_id", NOTIFICATION_ID)
        }
        val quickAddPendingIntent = PendingIntent.getBroadcast(
            context, requestCode + 1, quickAddIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val quickAddAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_edit, "Quick note", quickAddPendingIntent,
        ).addRemoteInput(remoteInput).build()

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("${titlePrefix}Log call with $contactLabel?")
            .setContentText("$direction call · $durationStr")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(quickAddAction)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Fallback prompt for a VoIP call (WhatsApp, Signal, Telegram...) where we don't have a
     * reliable number or contact match. No quick-note here -- the user picks the contact and
     * fills in details on the full log screen.
     */
    private fun showVoipCallNotification(context: Context) {
        val channelId = "vinci_call_log"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Call log reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Prompts you to log a call after it ends"
            }
        )

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("action", "log_voip_call")
        }
        val pendingIntent = PendingIntent.getActivity(
            context, System.currentTimeMillis().toInt(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Call from another app")
            .setContentText("Looks like a WhatsApp or similar call. Record an interaction?")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "It seems like you had a call from a different app. Would you like to record an interaction?"
            ))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        nm.notify(VOIP_NOTIFICATION_ID, notification)
    }

    private fun resolveContactName(context: Context, contactId: Long): String? {
        return try {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                "${ContactsContract.Contacts._ID} = ?",
                arrayOf(contactId.toString()),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) { null }
    }

    companion object {
        private const val KEY_IN_PROGRESS = "in_progress"
        private const val KEY_NUMBER      = "number"
        private const val KEY_IS_OUTGOING = "is_outgoing"
        private const val KEY_START_TIME  = "start_time"
        const val NOTIFICATION_ID = 1001
        const val CALLER_HISTORY_NOTIFICATION_ID = 1002
        const val VOIP_NOTIFICATION_ID = 1003
    }
}
