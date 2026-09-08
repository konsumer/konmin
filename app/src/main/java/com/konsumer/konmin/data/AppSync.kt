package com.konsumer.konmin.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Queries all launchable apps via PackageManager and upserts them into
 * Room, preserving existing lastUsed/launchCount for apps already tracked.
 * Called on launcher start and from PackageChangeReceiver whenever a
 * package is installed, removed or replaced.
 */
object AppSync {

    suspend fun sync(context: Context) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != context.packageName } // don't list ourselves

        val db = AppDatabase.get(context)
        val dao = db.appDao()
        val existing = dao.allPackageNames().toSet()

        val (known, fresh) = resolved.partition { it.activityInfo.packageName in existing }

        // New installs: insert with zeroed usage stats.
        if (fresh.isNotEmpty()) {
            dao.upsertAll(
                fresh.map {
                    AppEntry(
                        packageName = it.activityInfo.packageName,
                        label = it.loadLabel(pm).toString(),
                        lastUsed = 0L,
                        launchCount = 0
                    )
                }
            )
        }

        // Already tracked: refresh the label only (an app update or a locale
        // change can rename it) so usage stats survive the re-sync.
        known.forEach { dao.updateLabel(it.activityInfo.packageName, it.loadLabel(pm).toString()) }

        // Remove entries for apps uninstalled since last sync.
        val currentPackages = resolved.map { it.activityInfo.packageName }.toSet()
        (existing - currentPackages).forEach { dao.delete(it) }
    }
}
