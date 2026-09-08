package com.konsumer.konmin.worker

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.konsumer.konmin.data.AppDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext

/**
 * While the launcher is actually on screen, the 15-minute WorkManager floor
 * is far too coarse — a clock that updates every quarter hour is useless.
 * The screen is already on and we're already awake, so polling for due
 * widgets here costs essentially nothing extra.
 *
 * This is deliberately the *same* due-date check the background worker
 * does, not a second scheduling policy: a widget that says "ask me again in
 * 60 minutes" is still only asked once an hour, foregrounded or not. The
 * only thing that changes is how promptly a widget that *is* due gets
 * noticed.
 *
 * Cancelled with the composition/lifecycle scope, so it stops dead the
 * moment the launcher leaves the foreground.
 */
object ForegroundTicker {

    private const val TAG = "konmin.tick"
    private const val POLL_INTERVAL_MILLIS = 20_000L

    suspend fun run(context: Context) {
        val db = AppDatabase.get(context)
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        while (currentCoroutineContext().isActive) {
            val now = System.currentTimeMillis()
            val batterySaver = powerManager.isPowerSaveMode
            val due = runCatching { db.widgetConfigDao().getDue(now) }.getOrDefault(emptyList())
            if (due.isNotEmpty()) {
                Log.d(TAG, "due=${due.map { it.id }} batterySaver=$batterySaver")
            }

            for (config in due) {
                if (!config.enabled) continue
                // Under battery saver, leave network-heavy widgets to the
                // background worker's harsher throttle rather than refreshing
                // them just because the screen happens to be on.
                if (batterySaver && config.networkHeavy) continue
                runCatching { WidgetRenderer.renderAndPersist(context, config, batterySaver) }
                    .onFailure { Log.w(TAG, "render ${config.id} threw", it) }
            }

            delay(POLL_INTERVAL_MILLIS)
        }
    }
}
