package com.brbrs.vinci.sync.contract

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val CHANNEL_ID = "vinci_contact_contract_sync"
private const val NOTIFICATION_ID = 7301

/**
 * Shows a low-priority progress notification while a ContactContract sync is in flight, and a
 * brief "done" notification after. Before this, the only way to tell a sync was actually doing
 * anything was opening Settings and watching the contact count change on manual refresh -- for a
 * ~1300-contact address book taking several minutes, that read as "not doing anything".
 */
@Singleton
class ContactContractSyncNotifier @Inject constructor(@ApplicationContext private val context: Context) {
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannel() {
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Contact sync", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress while Vinci syncs contacts with Nextcloud"
                }
            )
        }
    }

    private fun hasPermission(): Boolean =
        ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun showProgress(processed: Int, total: Int) {
        if (!hasPermission()) return
        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Syncing contacts")
            .setContentText(if (total > 0) "$processed of $total" else "Checking for changes…")
            .setProgress(total.coerceAtLeast(1), processed, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun showFinished(count: Int) {
        if (!hasPermission()) return
        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Contacts synced")
            .setContentText("$count contacts up to date")
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun cancel() {
        manager.cancel(NOTIFICATION_ID)
    }
}
