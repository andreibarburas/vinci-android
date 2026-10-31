package com.brbrs.vinci.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.brbrs.vinci.network.LinkedItem
import com.brbrs.vinci.network.LinkedItemSource

/**
 * Opens a [LinkedItem] in its owning suite app.
 *
 * Each source app resolves `<scheme>://<itemPath>/<id>` to the matching item once it adopts
 * the matching deep-link intent filter (see each app's own "deep linking" work item) --
 * Tavla's `tavla://card/{id}`, Koto's `koto://task/{uid}`, Nóta's `nota://note/{id}`. Until an
 * app ships that filter, Android simply can't resolve the URI and we fall back to opening the
 * app's main screen instead, so tapping a linked item is never a dead end even mid-rollout.
 */
object LinkedAppLauncher {

    private fun packageFor(source: LinkedItemSource): String = when (source) {
        LinkedItemSource.TAVLA -> "com.brbrs.tavla"
        LinkedItemSource.KOTO  -> "com.brbrs.koto"
        LinkedItemSource.NOTA  -> "com.brbrs.nota"
    }

    private fun deepLinkUriFor(item: LinkedItem): Uri {
        val encodedId = Uri.encode(item.itemId)
        return when (item.source) {
            LinkedItemSource.TAVLA -> Uri.parse("tavla://card/$encodedId")
            LinkedItemSource.KOTO  -> Uri.parse("koto://task/$encodedId")
            LinkedItemSource.NOTA  -> Uri.parse("nota://note/$encodedId")
        }
    }

    fun isInstalled(context: Context, source: LinkedItemSource): Boolean =
        try {
            context.packageManager.getPackageInfo(packageFor(source), 0)
            true
        } catch (_: PackageManager.NameNotFoundException) { false }

    /** Opens [item] to its exact place in its app, falling back to that app's main screen (or
     * the Play Store if it isn't installed) if the deep link can't be resolved. */
    fun open(context: Context, item: LinkedItem) {
        val pkg = packageFor(item.source)
        if (!isInstalled(context, item.source)) {
            openPlayStore(context, pkg)
            return
        }

        val deepLink = Intent(Intent.ACTION_VIEW, deepLinkUriFor(item)).apply {
            setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resolvable = deepLink.resolveActivity(context.packageManager) != null
        if (resolvable) {
            context.startActivity(deepLink)
            return
        }

        // Deep link not (yet) handled by that app -- open its main screen instead.
        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        if (launchIntent != null) context.startActivity(launchIntent)
        else openPlayStore(context, pkg)
    }

    private fun openPlayStore(context: Context, pkg: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
                    .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        }.onFailure {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
                    .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        }
    }
}
