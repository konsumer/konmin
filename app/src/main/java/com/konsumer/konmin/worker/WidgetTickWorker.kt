package com.konsumer.konmin.worker

import android.content.Context
import android.os.PowerManager
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.konsumer.konmin.data.AppDatabase
import java.util.concurrent.TimeUnit

/**
 * Single scheduler tick — NOT one WorkManager job per plugin. Runs at the
 * OS floor for periodic work (15 min), queries which widgets are actually
 * due, and only invokes those. While the launcher is foregrounded,
 * ForegroundTicker checks the same due dates more granularly, since the
 * screen's already on and that's essentially free.
 */
class WidgetTickWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val db = AppDatabase.get(applicationContext)
        val now = System.currentTimeMillis()
        val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val batterySaver = powerManager.isPowerSaveMode

        // Cached fetch bodies older than a day are dead weight; nothing reads
        // them, since ctx.fetch caps cacheMinutes at 24h.
        runCatching { db.httpCacheDao().evictOlderThan(now - 24 * 60 * 60_000L) }

        val due = db.widgetConfigDao().getDue(now)
        for (config in due) {
            // Skip network-heavy widgets more aggressively under battery saver.
            if (batterySaver && config.networkHeavy) {
                val extended = config.copy(nextDueAt = now + config.manifestIntervalMinutes * 3 * 60_000L)
                db.widgetConfigDao().update(extended)
                continue
            }
            WidgetRenderer.renderAndPersist(applicationContext, config, batterySaver)
        }
        return Result.success()
    }
}

object TickScheduler {
    private const val WORK_NAME = "konmin_widget_tick"

    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<WidgetTickWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
