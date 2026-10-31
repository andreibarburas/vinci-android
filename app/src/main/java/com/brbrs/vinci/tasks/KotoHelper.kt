package com.brbrs.vinci.tasks

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Hands a follow-up over to Koto, the BarburasLab Nextcloud Tasks client.
 *
 * Koto's ShareReceiverActivity accepts ACTION_SEND text/plain with EXTRA_SUBJECT (title) +
 * EXTRA_TEXT (notes). It creates the task on the user's default Koto list, headless, and shows
 * a toast; nothing from Vinci leaves the user's Nextcloud.
 */
object KotoHelper {

    private const val KOTO_PACKAGE = "com.brbrs.koto"

    fun isInstalled(context: Context): Boolean =
        try {
            context.packageManager.getPackageInfo(KOTO_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    /**
     * Hands [title] + [notes] to Koto. Returns true if Koto received the share, false if the
     * app couldn't be reached at all.
     */
    fun createTask(
        context: Context,
        title: String,
        notes: String,
    ): Boolean {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(KOTO_PACKAGE)
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, notes)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            try {
                context.startActivity(
                    context.packageManager.getLaunchIntentForPackage(KOTO_PACKAGE)
                        ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        ?: return false
                )
                true
            } catch (e2: Exception) {
                false
            }
        }
    }
}
