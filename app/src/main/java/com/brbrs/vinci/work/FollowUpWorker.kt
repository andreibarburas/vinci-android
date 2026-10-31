package com.brbrs.vinci.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.brbrs.vinci.MainActivity
import com.brbrs.vinci.data.ContactDao
import com.brbrs.vinci.receiver.FollowUpActionReceiver
import com.brbrs.vinci.ui.theme.DisplayPreferencesRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * Daily background check: are any contacts past their follow-up date? If yes, posts one
 * summary notification. The reminder time is user-set in Settings (default 09:00) and the job
 * is scheduled via [FollowUpScheduler] on app start and whenever the time setting changes.
 *
 * The notification has two actions:
 *   - Open  -> deep-links to Home's follow-ups section (via MainActivity extra).
 *   - Mark done -> clears followUpDue on every overdue contact (handled by FollowUpActionReceiver).
 * Logging a real interaction against a contact clears their follow-up as before, so the
 * nag naturally stops the day you've worked through the list.
 */
@HiltWorker
class FollowUpWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val contactDao: ContactDao,
    private val displayPrefs: DisplayPreferencesRepository,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        if (!displayPrefs.preferences.first().followUpReminderEnabled) return Result.success()

        val now      = System.currentTimeMillis()
        val overdue  = contactDao.getFollowUpsDue(now).first()
        if (overdue.isEmpty()) {
            // Clear any stale notification left over from yesterday.
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID)
            return Result.success()
        }

        val ctx = applicationContext
        val nm  = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Follow-up reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Daily nudge for contacts whose follow-up date has arrived"
            }
        )

        val title = "${overdue.size} follow-up${if (overdue.size == 1) "" else "s"} due"
        val names = overdue.take(5).joinToString(", ") { it.displayName }
        val summary = if (overdue.size <= 5) names else "$names and ${overdue.size - 5} more"

        val openIntent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("action", "view_followups")
        }
        val openPending = PendingIntent.getActivity(
            ctx, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val markDonePending = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, FollowUpActionReceiver::class.java).apply { action = ACTION_MARK_DONE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openPending)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_view, "Open", openPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Mark done", markDonePending)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
        return Result.success()
    }

    companion object {
        const val CHANNEL_ID       = "vinci_followups"
        const val NOTIFICATION_ID  = 2001
        const val ACTION_MARK_DONE = "com.brbrs.vinci.action.FOLLOW_UP_MARK_DONE"
    }
}
