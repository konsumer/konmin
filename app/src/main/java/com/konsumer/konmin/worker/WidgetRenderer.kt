package com.konsumer.konmin.worker

import android.content.Context
import android.util.Log
import com.konsumer.konmin.data.AppDatabase
import com.konsumer.konmin.data.WidgetConfig
import com.konsumer.konmin.plugin.ClickedLine
import com.konsumer.konmin.plugin.LayoutMath
import com.konsumer.konmin.plugin.Line
import com.konsumer.konmin.plugin.PluginRepository
import com.konsumer.konmin.plugin.PluginTimeoutException
import com.konsumer.konmin.plugin.QuickJsPluginEngine
import com.konsumer.konmin.plugin.RealPluginContext
import com.konsumer.konmin.plugin.RenderResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Bridges a due WidgetConfig to its plugin: runs the JS in QuickJS, applies
 * the height truncation, and writes back the cached lines plus the next due
 * time. Every widget goes through here, including the shipped clock/date/
 * battery examples — there is no privileged native path.
 *
 * A failed render never blanks the widget: the last good output stays on
 * screen and the widget backs off instead of retrying every tick.
 */
object WidgetRenderer {

    private const val MIN_INTERVAL_MINUTES = 1
    private const val MAX_INTERVAL_MINUTES = 6 * 60
    private const val TAG = "konmin.render"

    suspend fun renderAndPersist(context: Context, config: WidgetConfig, batterySaver: Boolean) {
        val db = AppDatabase.get(context)
        val now = System.currentTimeMillis()

        val outcome: Result<RenderResult> = renderPlugin(context, config)

        val result = outcome.getOrNull()
        val error = outcome.exceptionOrNull()

        val linesJson: String
        val effectiveIntervalMinutes: Int

        if (result != null) {
            val fitted = LayoutMath.truncateToFit(result.lines, config.maxHeightUnits)
            linesJson = Json.encodeToString(fitted)
            effectiveIntervalMinutes = (result.nextCheckMinutes ?: config.manifestIntervalMinutes)
                .coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)
        } else {
            Log.w(TAG, "widget ${config.id} failed: ${error?.message}")
            // Keep whatever was last cached, and back off hard so a broken
            // plugin can't burn the battery retrying on every tick.
            linesJson = config.lastRenderedLinesJson ?: Json.encodeToString(emptyList<Line>())
            effectiveIntervalMinutes = (config.manifestIntervalMinutes * 2)
                .coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)
        }

        // A plugin that can't be interrupted has already cost us a stuck
        // thread; don't hand it another one on the next tick.
        val disable = error is PluginTimeoutException

        val saverMultiplier = if (batterySaver) 2 else 1
        db.widgetConfigDao().update(
            config.copy(
                enabled = if (disable) false else config.enabled,
                lastCheckedAt = now,
                nextDueAt = now + effectiveIntervalMinutes * saverMultiplier * 60_000L,
                lastRenderedLinesJson = linesJson,
                lastError = if (disable) {
                    "disabled after timing out: ${error?.message}"
                } else {
                    error?.message
                }
            )
        )
    }

    /**
     * Runs a plugin's onClick for the line the user tapped.
     *
     * The plugin context is built with launching enabled — the only place
     * that happens — because this is the one path that originates from a
     * deliberate user action rather than the scheduler.
     *
     * A click that returns nothing leaves the cached output untouched; one
     * that returns lines replaces them exactly as a render would, so a plugin
     * can answer a tap by changing what it says instead of opening something.
     */
    suspend fun handleClick(context: Context, config: WidgetConfig, clicked: ClickedLine) {
        val repo = PluginRepository(context)
        val plugin = repo.byId(config.id) ?: return
        val source = repo.readSource(config.id) ?: return

        val ctx = RealPluginContext(context, plugin.manifest, allowLaunch = true)
        val outcome = QuickJsPluginEngine(config.id).click(source, ctx, clicked)

        val error = outcome.exceptionOrNull()
        if (error != null) {
            Log.w(TAG, "widget ${config.id} onClick failed: ${error.message}")
            AppDatabase.get(context).widgetConfigDao()
                .update(config.copy(lastError = error.message))
            return
        }

        val result = outcome.getOrNull() ?: return // nothing to show; leave the cache alone
        val fitted = LayoutMath.truncateToFit(result.lines, config.maxHeightUnits)
        val now = System.currentTimeMillis()
        val minutes = (result.nextCheckMinutes ?: config.manifestIntervalMinutes)
            .coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)

        AppDatabase.get(context).widgetConfigDao().update(
            config.copy(
                lastRenderedLinesJson = Json.encodeToString(fitted),
                lastCheckedAt = now,
                nextDueAt = now + minutes * 60_000L,
                lastError = null
            )
        )
    }

    private suspend fun renderPlugin(context: Context, config: WidgetConfig): Result<RenderResult> {
        val repo = PluginRepository(context)
        val plugin = repo.byId(config.id)
            ?: return Result.failure(IllegalStateException("plugin ${config.id} is not installed"))
        val source = repo.readSource(config.id)
            ?: return Result.failure(IllegalStateException("plugin ${config.id} has no index.js"))

        val engine = QuickJsPluginEngine(config.id)
        val ctx = RealPluginContext(context, plugin.manifest)
        return engine.render(source, ctx)
    }
}
