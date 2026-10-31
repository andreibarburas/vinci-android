package com.brbrs.vinci.receiver

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.brbrs.vinci.data.ContactDao
import com.brbrs.vinci.work.FollowUpWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles the "Mark done" action from the follow-up summary notification: clears followUpDue
 * on every overdue contact at the time of tap, then dismisses the notification.
 */
@AndroidEntryPoint
class FollowUpActionReceiver : BroadcastReceiver() {

    @Inject lateinit var contactDao: ContactDao

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != FollowUpWorker.ACTION_MARK_DONE) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val overdue = contactDao.getFollowUpsDue(System.currentTimeMillis()).first()
                overdue.forEach { contactDao.setFollowUp(it.id, 0L) }
            } finally {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(FollowUpWorker.NOTIFICATION_ID)
                pending.finish()
            }
        }
    }
}
