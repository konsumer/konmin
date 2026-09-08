package com.konsumer.konmin.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {

    // Room evaluates the unused CASE branches to NULL and ignores them for
    // ordering, so one query covers all three sort modes.
    @Query(
        """
        SELECT * FROM app_entries WHERE hidden = 0
        ORDER BY
        CASE WHEN :sort = 'RECENT' THEN lastUsed END DESC,
        CASE WHEN :sort = 'MOST_USED' THEN launchCount END DESC,
        label COLLATE NOCASE ASC
        """
    )
    fun getApps(sort: String): Flow<List<AppEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(apps: List<AppEntry>)

    @Query("UPDATE app_entries SET lastUsed = :now, launchCount = launchCount + 1 WHERE packageName = :pkg")
    suspend fun recordLaunch(pkg: String, now: Long)

    /** Every app including hidden ones — the settings list, not the launcher. */
    @Query("SELECT * FROM app_entries ORDER BY label COLLATE NOCASE ASC")
    fun getAllApps(): Flow<List<AppEntry>>

    /** Only the hidden ones, so search can optionally fold them back in. */
    @Query("SELECT * FROM app_entries WHERE hidden = 1 ORDER BY label COLLATE NOCASE ASC")
    fun getHiddenApps(): Flow<List<AppEntry>>

    @Query("UPDATE app_entries SET hidden = :hidden WHERE packageName = :pkg")
    suspend fun setHidden(pkg: String, hidden: Boolean)

    @Query("UPDATE app_entries SET hidden = 0")
    suspend fun unhideAll()

    @Query("SELECT packageName FROM app_entries")
    suspend fun allPackageNames(): List<String>

    @Query("UPDATE app_entries SET label = :label WHERE packageName = :pkg")
    suspend fun updateLabel(pkg: String, label: String)

    @Query("DELETE FROM app_entries WHERE packageName = :pkg")
    suspend fun delete(pkg: String)
}

@Dao
interface WidgetConfigDao {

    @Query("SELECT * FROM widget_configs ORDER BY position ASC")
    fun getAll(): Flow<List<WidgetConfig>>

    @Query("SELECT * FROM widget_configs ORDER BY position ASC")
    suspend fun getAllOnce(): List<WidgetConfig>

    @Query("SELECT * FROM widget_configs WHERE id = :id")
    suspend fun getById(id: String): WidgetConfig?

    @Query("SELECT * FROM widget_configs WHERE enabled = 1 AND nextDueAt <= :now")
    suspend fun getDue(now: Long): List<WidgetConfig>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(config: WidgetConfig): Long

    @Update
    suspend fun update(config: WidgetConfig)

    @Query("DELETE FROM widget_configs WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface PluginStorageDao {

    @Query("SELECT value FROM plugin_storage WHERE pluginId = :pluginId AND key = :key")
    suspend fun get(pluginId: String, key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: PluginStorageEntry)

    @Query("DELETE FROM plugin_storage WHERE pluginId = :pluginId")
    suspend fun clearPlugin(pluginId: String)
}

@Dao
interface HttpCacheDao {

    @Query("SELECT * FROM http_cache WHERE pluginId = :pluginId AND url = :url")
    suspend fun get(pluginId: String, url: String): HttpCacheEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: HttpCacheEntry)

    @Query("DELETE FROM http_cache WHERE fetchedAt < :before")
    suspend fun evictOlderThan(before: Long)

    /** Remove every cached response for one plugin, used on uninstall. */
    @Query("DELETE FROM http_cache WHERE pluginId = :pluginId")
    suspend fun deleteForPlugin(pluginId: String)
}
