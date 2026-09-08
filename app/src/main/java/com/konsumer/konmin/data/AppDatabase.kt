package com.konsumer.konmin.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [AppEntry::class, WidgetConfig::class, PluginStorageEntry::class, HttpCacheEntry::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao
    abstract fun widgetConfigDao(): WidgetConfigDao
    abstract fun pluginStorageDao(): PluginStorageDao
    abstract fun httpCacheDao(): HttpCacheDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "konmin.db"
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
