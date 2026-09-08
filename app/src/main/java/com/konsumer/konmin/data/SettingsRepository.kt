package com.konsumer.konmin.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "konmin_settings")

enum class TextSizeOption(val scale: Float) { SMALL(0.85f), MEDIUM(1.0f), LARGE(1.2f) }

/**
 * How the user gets to settings. A long-press is the conventional launcher
 * gesture and keeps the home screen bare, but it's undiscoverable and hard
 * to perform with a motor impairment — holding still for half a second is
 * exactly what a tremor defeats. So a plain tap target is offered too, and
 * both can be on at once.
 */
enum class SettingsAccess {
    LONG_PRESS, ICON, BOTH;

    val showsIcon: Boolean get() = this == ICON || this == BOTH
    val allowsLongPress: Boolean get() = this == LONG_PRESS || this == BOTH
}

data class ThemeSettings(
    val autoAccentFromWallpaper: Boolean = true,
    val fgColorArgb: Int = 0xFFFFFFFF.toInt(),
    /**
     * The home-screen background: wallpaper mode or a plain solid color.
     * A fully transparent value (alpha 0, e.g. 0x00000000) is wallpaper
     * mode — the window requests FLAG_SHOW_WALLPAPER and the launcher
     * draws nothing, so the system wallpaper (live or static) shows
     * through. A fully opaque value (alpha 0xFF) is solid mode — the
     * window must not render the wallpaper and the launcher paints the
     * whole screen that color. Any alpha in between is treated as
     * wallpaper mode (wallpaper shows, overlaid) so translucent scrims
     * stored before solid colors existed still behave; only alpha == 0xFF
     * triggers solid mode.
     */
    val bgColorArgb: Int = 0x00000000,
    /**
     * Halo drawn behind text, for legibility over a busy wallpaper without
     * covering it with a background. A fully transparent value means off,
     * which is the default — most wallpapers don't need it.
     */
    val glowColorArgb: Int = 0x00000000,
    val glowRadius: Float = 3f,
    /**
     * Hides the status bar, reclaiming that strip for the widget stack. The
     * bar still comes back on a swipe from the top, then hides itself again.
     */
    val hideStatusBar: Boolean = false,
    val settingsAccess: SettingsAccess = SettingsAccess.BOTH,
    /** Shows a search field between the widgets and the app list. */
    val searchEnabled: Boolean = true,
    /** Lets search reach apps hidden from the list. Off: hidden means hidden. */
    val searchIncludesHidden: Boolean = false,
    /** Launches as soon as the query narrows to exactly one app. */
    val searchAutoLaunch: Boolean = false,
    val textSize: TextSizeOption = TextSizeOption.MEDIUM,
    val sortOrder: SortOrder = SortOrder.ALPHA
) {
    /** Alpha, not the color, is what switches the halo on. */
    val glowEnabled: Boolean get() = (glowColorArgb ushr 24) != 0 && glowRadius > 0f
}

class SettingsRepository(private val context: Context) {
    private object Keys {
        val AUTO_ACCENT = booleanPreferencesKey("auto_accent")
        val FG_COLOR = intPreferencesKey("fg_color")
        val BG_COLOR = intPreferencesKey("bg_color")
        val GLOW_COLOR = intPreferencesKey("glow_color")
        val GLOW_RADIUS = floatPreferencesKey("glow_radius")
        val HIDE_STATUS_BAR = booleanPreferencesKey("hide_status_bar")
        val SETTINGS_ACCESS = stringPreferencesKey("settings_access")
        val SEARCH_ENABLED = booleanPreferencesKey("search_enabled")
        val SEARCH_HIDDEN = booleanPreferencesKey("search_includes_hidden")
        val SEARCH_AUTO_LAUNCH = booleanPreferencesKey("search_auto_launch")
        val TEXT_SIZE = stringPreferencesKey("text_size")
        val SORT_ORDER = stringPreferencesKey("sort_order")
    }

    val settings: Flow<ThemeSettings> = context.dataStore.data.map { prefs ->
        ThemeSettings(
            autoAccentFromWallpaper = prefs[Keys.AUTO_ACCENT] ?: true,
            fgColorArgb = prefs[Keys.FG_COLOR] ?: 0xFFFFFFFF.toInt(),
            bgColorArgb = prefs[Keys.BG_COLOR] ?: 0x00000000,
            glowColorArgb = prefs[Keys.GLOW_COLOR] ?: 0x00000000,
            glowRadius = prefs[Keys.GLOW_RADIUS] ?: 3f,
            hideStatusBar = prefs[Keys.HIDE_STATUS_BAR] ?: false,
            settingsAccess = prefs[Keys.SETTINGS_ACCESS]
                ?.let { runCatching { SettingsAccess.valueOf(it) }.getOrNull() }
                ?: SettingsAccess.BOTH,
            searchEnabled = prefs[Keys.SEARCH_ENABLED] ?: true,
            searchIncludesHidden = prefs[Keys.SEARCH_HIDDEN] ?: false,
            searchAutoLaunch = prefs[Keys.SEARCH_AUTO_LAUNCH] ?: false,
            textSize = prefs[Keys.TEXT_SIZE]?.let { TextSizeOption.valueOf(it) } ?: TextSizeOption.MEDIUM,
            sortOrder = prefs[Keys.SORT_ORDER]?.let { SortOrder.valueOf(it) } ?: SortOrder.ALPHA
        )
    }

    suspend fun setAutoAccent(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_ACCENT] = enabled }
    }

    suspend fun setColors(fg: Int, bg: Int) {
        context.dataStore.edit {
            it[Keys.FG_COLOR] = fg
            it[Keys.BG_COLOR] = bg
        }
    }

    suspend fun setGlow(colorArgb: Int, radius: Float) {
        context.dataStore.edit {
            it[Keys.GLOW_COLOR] = colorArgb
            it[Keys.GLOW_RADIUS] = radius
        }
    }

    suspend fun setHideStatusBar(hide: Boolean) {
        context.dataStore.edit { it[Keys.HIDE_STATUS_BAR] = hide }
    }

    suspend fun setSettingsAccess(access: SettingsAccess) {
        context.dataStore.edit { it[Keys.SETTINGS_ACCESS] = access.name }
    }

    suspend fun setSearchEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SEARCH_ENABLED] = enabled }
    }

    suspend fun setSearchIncludesHidden(include: Boolean) {
        context.dataStore.edit { it[Keys.SEARCH_HIDDEN] = include }
    }

    suspend fun setSearchAutoLaunch(auto: Boolean) {
        context.dataStore.edit { it[Keys.SEARCH_AUTO_LAUNCH] = auto }
    }

    suspend fun setTextSize(option: TextSizeOption) {
        context.dataStore.edit { it[Keys.TEXT_SIZE] = option.name }
    }

    suspend fun setSortOrder(order: SortOrder) {
        context.dataStore.edit { it[Keys.SORT_ORDER] = order.name }
    }
}
