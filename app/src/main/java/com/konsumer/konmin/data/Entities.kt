package com.konsumer.konmin.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class SortOrder { ALPHA, RECENT, MOST_USED }

@Entity(tableName = "app_entries")
data class AppEntry(
    @PrimaryKey val packageName: String,
    val label: String,
    val lastUsed: Long = 0L,
    val launchCount: Int = 0,
    val hidden: Boolean = false
)

@Entity(tableName = "widget_configs")
data class WidgetConfig(
    @PrimaryKey val id: String,              // matches plugin manifest id, e.g. "weather", "clock"
    val enabled: Boolean = true,
    val position: Int = 0,                    // stack order, top = 0
    val maxHeightUnits: Float = 4f,           // user-adjustable cap, in "default line" units
    val manifestIntervalMinutes: Int = 30,    // fallback cadence if plugin gives no hint
    val lastCheckedAt: Long = 0L,
    val nextDueAt: Long = 0L,                 // epoch millis; tick worker only runs widgets past this
    val cacheUntil: Long = 0L,                // network cache expiry (separate from render cadence)
    val lastRenderedLinesJson: String? = null, // cached last-good output, shown if a check fails
    val networkHeavy: Boolean = false,         // throttled harder under Battery Saver
    val lastError: String? = null              // surfaced in settings so a broken plugin is visible
)

/**
 * Per-plugin key/value store backing ctx.storageGet/storageSet. Keyed by
 * plugin id so one plugin can never read another's values.
 */
@Entity(tableName = "plugin_storage", primaryKeys = ["pluginId", "key"])
data class PluginStorageEntry(
    val pluginId: String,
    val key: String,
    val value: String
)

/**
 * Response cache for ctx.fetch. `cacheMinutes` on the JS side decides how
 * long a row stays fresh; the row itself is keyed by plugin + URL so two
 * plugins hitting the same endpoint don't share (or poison) each other's
 * cached body.
 */
@Entity(tableName = "http_cache", primaryKeys = ["pluginId", "url"])
data class HttpCacheEntry(
    val pluginId: String,
    val url: String,
    val body: String,
    val fetchedAt: Long
)
