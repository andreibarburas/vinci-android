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
import com.brbrs.vinci.calendar.CalendarMatcher
import com.brbrs.vinci.data.ContactDao
import com.brbrs.vinci.ui.theme.DisplayPreferencesRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * Periodic worker (every 15 min -- WorkManager minimum) that scans the device calendar for
 * events that ended recently and have at least one matched Vinci contact as an attendee.
 * For each match it posts a "log this meeting" nudge; the matching event id is remembered in
 * SharedPreferences so the same event doesn't nudge twice. All-day events are skipped and
 * Proton Calendar is invisible to this (events never reach CalendarContract).
 */
@HiltWorker
class CalendarNudgeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val contactDao: ContactDao,
    private val displayPrefs: DisplayPreferencesRepository,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        if (!displayPrefs.preferences.first().calendarNudgesEnabled) return Result.success()

        val now        = System.currentTimeMillis()
        // Trigger window: an event that ended in the last 30 minutes AND at least 2 minutes
        // ago. The 2-minute cushion stops us firing the nudge before an event's real end time
        // (phones sometimes nudge "just after" slightly early), and 30 minutes keeps late
        // check-ins from surfacing yesterday's meetings.
        val windowEnd   = now - TRIGGER_DELAY_MS
        val windowStart = now - MAX_LOOKBACK_MS

        val contacts = contactDao.getAllContactsSuspend()
        val matches  = CalendarMatcher.findEndedEventsWithContacts(
            context  = applicationContext,
            contacts = contacts,
            sinceMs  = windowStart,
            untilMs  = windowEnd,
        )
        if (matches.isEmpty()) return Result.success()

        val sp = applicationContext.getSharedPreferences(NUDGED_PREFS, Context.MODE_PRIVATE)
        val alreadyNudged = sp.getStringSet(NUDGED_IDS, emptySet()).orEmpty().toMutableSet()
        // GC very old ids so this doesn't grow forever; keep up to 500.
        val gc = alreadyNudged.toList().takeLast(500).toMutableSet()

        val ctx = applicationContext
        val nm  = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Meeting nudges", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Prompts you to log a meeting after it ends"
            }
        )

        for (match in matches) {
            val key = "${match.event.id}-${match.event.end}"
            if (key in gc) continue
            gc += key

            val title = match.event.title
            val names = match.matchedContacts.joinToString(", ") { it.displayName }

            val openIntent = Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("action", "log_meeting")
                putExtra("event_title", title)
                putExtra("event_end", match.event.end)
                putExtra("contact_ids", match.matchedContacts.map { it.id }.toLongArray())
            }
            val pending = PendingIntent.getActivity(
                ctx, match.event.id.toInt(), openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
                .setContentTitle("Just finished: $title")
                .setContentText("Log this with $names?")
                .setStyle(NotificationCompat.BigTextStyle().bigText("Log this with $names?"))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()

            nm.notify(NOTIFICATION_ID_BASE + (match.event.id and 0xFFFFF).toInt(), notification)
        }

        sp.edit().putStringSet(NUDGED_IDS, gc).apply()
        return Result.success()
    }

    companion object {
        const val CHANNEL_ID          = "vinci_calendar_nudges"
        const val NOTIFICATION_ID_BASE = 3000
        private const val NUDGED_PREFS = "vinci_calendar_nudged"
        private const val NUDGED_IDS   = "ids"
        private const val TRIGGER_DELAY_MS = 2L * 60 * 1000
        private const val MAX_LOOKBACK_MS  = 30L * 60 * 1000
    }
}
