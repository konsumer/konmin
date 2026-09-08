package com.konsumer.konmin.widget

import android.content.Context
import com.konsumer.konmin.data.AppDatabase
import com.konsumer.konmin.data.WidgetConfig
import com.konsumer.konmin.plugin.PluginRepository

/**
 * Keeps the widget_configs table in step with what's actually installed.
 *
 * Runs on every launch, not once: it has to notice plugins the user added
 * since last time, and drop rows for ones they removed. Inserts are
 * IGNORE-on-conflict so re-running never clobbers an enable toggle,
 * position, height budget, or cached output.
 */
object WidgetSeeder {

    /** Enabled on a fresh install, so the launcher isn't a blank screen. */
    private val DEFAULT_ENABLED = setOf("clock", "date", "battery")

    /**
     * Reading order for the shipped examples. Without this they'd be seeded
     * alphabetically, which puts the battery percentage above the clock.
     * Anything not listed sorts after these, in the order it was found.
     */
    private val PREFERRED_ORDER = listOf("clock", "date", "battery", "weather", "agenda")

    suspend fun seed(context: Context) {
        val repo = PluginRepository(context)
        val dao = AppDatabase.get(context).widgetConfigDao()

        // First run: unpack the shipped examples. Never overwrites, so a
        // user's edits to clock.js survive an app restart.
        repo.installBundledExamples(overwrite = false)

        val existing = dao.getAllOnce().associateBy { it.id }
        val installed = repo.list().sortedBy { plugin ->
            PREFERRED_ORDER.indexOf(plugin.manifest.id).takeIf { it >= 0 } ?: PREFERRED_ORDER.size
        }
        var nextPosition = (existing.values.maxOfOrNull { it.position } ?: -1) + 1
        val firstRun = existing.isEmpty()

        installed.forEach { plugin ->
            val manifest = plugin.manifest
            val current = existing[manifest.id]
            if (current == null) {
                dao.insertIfAbsent(
                    WidgetConfig(
                        id = manifest.id,
                        // Only the shipped basics start on. A plugin the user
                        // just added stays off until they enable it, so
                        // dropping in a file never silently starts running
                        // code or hitting the network.
                        enabled = firstRun && manifest.id in DEFAULT_ENABLED,
                        position = nextPosition++,
                        maxHeightUnits = manifest.maxHeightUnits,
                        manifestIntervalMinutes = manifest.intervalMinutes,
                        nextDueAt = 0L, // due immediately, so the first tick fills it in
                        networkHeavy = manifest.networkHeavy
                    )
                )
            } else if (
                current.manifestIntervalMinutes != manifest.intervalMinutes ||
                current.networkHeavy != manifest.networkHeavy
            ) {
                // The user replaced the .js with a newer version that changed
                // its cadence — take the manifest's word for it, but leave
                // their height and position choices alone.
                dao.update(
                    current.copy(
                        manifestIntervalMinutes = manifest.intervalMinutes,
                        networkHeavy = manifest.networkHeavy
                    )
                )
            }
        }

        // Drop rows for plugins whose file was deleted, so the admin list
        // doesn't show ghosts.
        val liveIds = installed.map { it.manifest.id }.toSet()
        existing.values.filter { it.id !in liveIds }.forEach { dao.delete(it.id) }
    }
}
