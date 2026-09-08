package com.konsumer.konmin.plugin

/**
 * The `ctx` object exposed to a plugin's render(ctx) function inside the
 * QuickJS sandbox. Each method here is bound as a JS-callable host
 * function when the engine sets a context up.
 *
 * Deliberately small surface: no raw Android API access, no filesystem, no
 * arbitrary intents. Everything is mediated and permission-gated, so the
 * worst a hostile plugin can do is render bad text.
 *
 * The formatting/battery/clock helpers are here rather than left to JS
 * because QuickJS ships without a full Intl, so date formatting in the
 * user's locale has to come from the host.
 */
interface PluginContext {

    /**
     * Fetch a URL, respecting a cache window so repeated render() calls
     * don't force a network hit every time. Requires PluginPermission.NETWORK
     * in the manifest; the host also enforces the manifest's domain
     * allowlist and https-only.
     */
    suspend fun fetch(url: String, cacheMinutes: Int = 15): String

    /** Small per-plugin key-value store, isolated from other plugins. */
    suspend fun storageGet(key: String): String?
    suspend fun storageSet(key: String, value: String)

    /** Coarse last-known position. Requires LOCATION, declared and granted. */
    suspend fun location(): Pair<Double, Double>?

    /** Requires CALENDAR, declared and granted. */
    suspend fun upcomingEvents(withinMinutes: Int = 24 * 60): List<CalendarEventSummary>

    /** Epoch millis. Cheap, always available — no permission needed. */
    fun now(): Long

    /**
     * Formats an instant with a java.text.SimpleDateFormat pattern in the
     * device's locale and timezone. QuickJS has Date but no real Intl, so
     * this is how a plugin gets a correctly localised string.
     */
    fun formatTime(epochMillis: Long, pattern: String): String

    /** Whether the user's device is set to 24-hour time. */
    fun is24Hour(): Boolean

    /** Battery level 0-100 and charge state. No permission needed. */
    fun battery(): BatteryStatus

    // --- Launching ---
    //
    // All of these need PluginPermission.LAUNCH, and all of them are refused
    // outright during a background render. A widget refresh must never be able
    // to throw an app in the user's face; only a click they actually made can.
    // They are also a fixed, named set rather than a general "start any intent"
    // call, so a plugin can't reach an arbitrary component.

    /** Opens an installed app by package name. */
    suspend fun launchApp(packageName: String)

    /** ACTION_VIEW on an http/https URL. */
    suspend fun openUrl(url: String)

    /** Opens the calendar at a moment in time; defaults to now. */
    suspend fun openCalendar(atEpochMillis: Long? = null)

    /** Opens one calendar event, by the id from upcomingEvents(). */
    suspend fun openEvent(eventId: Long)

    /** The system's alarm list, whichever clock app provides it. */
    suspend fun openAlarms()
}

data class CalendarEventSummary(
    /** Passed back to ctx.openEvent() to open this specific event. */
    val id: Long,
    val title: String,
    val startsAtEpochMillis: Long,
    val allDay: Boolean
)

/** Which line of a widget the user tapped, handed to onClick as ctx.clicked. */
data class ClickedLine(val index: Int, val text: String)

data class BatteryStatus(
    val level: Int,
    val charging: Boolean
)

/**
 * Wraps the JS engine. This interface is the seam so the engine
 * implementation can be swapped without touching widget or scheduling code.
 */
interface PluginEngine {
    /**
     * Executes a plugin's render(ctx) and parses the returned object into a
     * RenderResult. Implementations enforce a hard execution timeout and
     * catch script errors without crashing the host process.
     */
    suspend fun render(pluginSource: String, ctx: PluginContext): Result<RenderResult>

    /**
     * Executes a plugin's optional onClick(ctx). Returns null when the plugin
     * returned nothing — a click that only launched something has no new text
     * to show, and must leave the cached output alone rather than blanking it.
     */
    suspend fun click(
        pluginSource: String,
        ctx: PluginContext,
        clicked: ClickedLine
    ): Result<RenderResult?>
}
