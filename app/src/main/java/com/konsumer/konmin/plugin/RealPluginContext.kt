package com.konsumer.konmin.plugin

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.CancellationSignal
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.text.format.DateFormat
import androidx.core.content.ContextCompat
import com.konsumer.konmin.data.AppDatabase
import com.konsumer.konmin.data.HttpCacheEntry
import com.konsumer.konmin.data.PluginStorageEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * The host side of the plugin API. Everything a plugin can reach goes
 * through here, and every capability is checked twice: once against what
 * the plugin's manifest declared, once against what Android actually
 * granted. A plugin that asks for CALENDAR and was denied at the OS level
 * gets an error, not a crash and not somebody else's data.
 */
class RealPluginContext(
    private val context: Context,
    private val manifest: PluginManifest,
    /**
     * Only true while handling a click. A scheduled background render must
     * never be able to start an activity — that would let a widget refresh
     * yank the user out of whatever they were doing.
     */
    private val allowLaunch: Boolean = false
) : PluginContext {

    private val db = AppDatabase.get(context)

    override fun now(): Long = System.currentTimeMillis()

    override fun formatTime(epochMillis: Long, pattern: String): String =
        runCatching {
            SimpleDateFormat(pattern, Locale.getDefault()).format(Date(epochMillis))
        }.getOrElse { throw QuickJsHostError("ctx.formatTime: bad pattern \"$pattern\"") }

    override fun is24Hour(): Boolean = DateFormat.is24HourFormat(context)

    override fun battery(): BatteryStatus {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 } ?: -1

        // Reading the sticky broadcast leaves no receiver registered, and is
        // the only way to tell charging from discharging.
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        return BatteryStatus(level = level, charging = charging)
    }

    override suspend fun fetch(url: String, cacheMinutes: Int): String = withContext(Dispatchers.IO) {
        requireDeclared(PluginPermission.NETWORK, "ctx.fetch")

        val parsed = runCatching { URL(url) }.getOrNull()
            ?: throw QuickJsHostError("ctx.fetch: malformed url")
        if (parsed.protocol != "https") {
            throw QuickJsHostError("ctx.fetch: only https is allowed (got ${parsed.protocol})")
        }
        if (!hostAllowed(parsed.host)) {
            throw QuickJsHostError(
                "ctx.fetch: ${parsed.host} is not in this plugin's manifest domains ${manifest.domains}"
            )
        }

        val clampedCache = cacheMinutes.coerceIn(0, 24 * 60)
        val cacheDao = db.httpCacheDao()
        val cached = cacheDao.get(manifest.id, url)
        if (cached != null && now() - cached.fetchedAt < clampedCache * 60_000L) {
            return@withContext cached.body
        }

        val body = runCatching { httpGet(parsed) }.getOrElse { error ->
            // A stale cached body beats a blank widget on a flaky network.
            cached?.body ?: throw QuickJsHostError("ctx.fetch failed: ${error.message}")
        }

        cacheDao.put(HttpCacheEntry(manifest.id, url, body, now()))
        body
    }

    override suspend fun storageGet(key: String): String? {
        requireDeclared(PluginPermission.STORAGE, "ctx.storageGet")
        return db.pluginStorageDao().get(manifest.id, key.take(MAX_KEY_CHARS))
    }

    override suspend fun storageSet(key: String, value: String) {
        requireDeclared(PluginPermission.STORAGE, "ctx.storageSet")
        if (value.length > MAX_VALUE_CHARS) {
            throw QuickJsHostError("ctx.storageSet: value exceeds $MAX_VALUE_CHARS chars")
        }
        db.pluginStorageDao().put(PluginStorageEntry(manifest.id, key.take(MAX_KEY_CHARS), value))
    }

    override suspend fun location(): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        requireDeclared(PluginPermission.LOCATION, "ctx.location")
        requireGranted(Manifest.permission.ACCESS_COARSE_LOCATION, "location")

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return@withContext null

        // Prefer a cached fix: it's instant and costs nothing.
        val cached = lm.allProviders
            .mapNotNull { provider -> runCatching { lm.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }

        // Nothing cached. On a phone where no other app has asked for location
        // recently there may never be one, so fall back to a single-shot
        // request — one fix, then done. This is not continuous tracking and
        // never registers a standing listener, which is what would actually
        // cost battery.
        val fix = cached ?: requestSingleFix(lm)
        val best = fix ?: return@withContext null

        // Coarse permission only, so round to ~1km and don't pretend to more.
        Pair(
            String.format(Locale.US, "%.2f", best.latitude).toDouble(),
            String.format(Locale.US, "%.2f", best.longitude).toDouble()
        )
    }

    /**
     * Asks every available provider for one fix at once and takes whichever
     * answers first, rather than trying them in sequence — a provider with no
     * fix doesn't fail, it just never calls back, so sequential attempts would
     * spend the entire budget waiting on the first dud.
     */
    @SuppressLint("MissingPermission") // checked by requireGranted() above
    private suspend fun requestSingleFix(lm: LocationManager): Location? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        val providers = SINGLE_FIX_PROVIDERS.filter { it in lm.allProviders }
        if (providers.isEmpty()) return null

        val executor = Executors.newSingleThreadExecutor()
        return try {
            withTimeoutOrNull(SINGLE_FIX_TIMEOUT_MILLIS) {
                suspendCancellableCoroutine { continuation ->
                    val signal = CancellationSignal()
                    continuation.invokeOnCancellation { runCatching { signal.cancel() } }
                    providers.forEach { provider ->
                        runCatching {
                            lm.getCurrentLocation(provider, signal, executor) { location ->
                                // Providers that have nothing report null; wait
                                // for a real fix or let the timeout end it.
                                if (location != null && continuation.isActive) {
                                    continuation.resume(location)
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            executor.shutdown()
        }
    }

    override suspend fun upcomingEvents(withinMinutes: Int): List<CalendarEventSummary> =
        withContext(Dispatchers.IO) {
            requireDeclared(PluginPermission.CALENDAR, "ctx.upcomingEvents")
            requireGranted(Manifest.permission.READ_CALENDAR, "calendar")

            val start = now()
            val end = start + withinMinutes.coerceIn(1, 30 * 24 * 60) * 60_000L

            // Instances (not Events) so recurring meetings expand into the
            // individual occurrences that actually fall in the window.
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                .let { ContentUris.appendId(it, start); ContentUris.appendId(it, end); it.build() }

            val projection = arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.ALL_DAY
            )

            val out = mutableListOf<CalendarEventSummary>()
            context.contentResolver.query(
                uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { cursor ->
                while (cursor.moveToNext() && out.size < MAX_EVENTS) {
                    out.add(
                        CalendarEventSummary(
                            id = cursor.getLong(0),
                            title = cursor.getString(1) ?: "(untitled)",
                            startsAtEpochMillis = cursor.getLong(2),
                            allDay = cursor.getInt(3) == 1
                        )
                    )
                }
            }
            out
        }

    override suspend fun launchApp(packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: throw QuickJsHostError("ctx.launchApp: $packageName is not installed")
        start(intent, "ctx.launchApp")
    }

    override suspend fun openUrl(url: String) {
        val parsed = runCatching { URL(url) }.getOrNull()
            ?: throw QuickJsHostError("ctx.openUrl: malformed url")
        if (parsed.protocol != "https" && parsed.protocol != "http") {
            throw QuickJsHostError("ctx.openUrl: only http(s) links can be opened")
        }
        start(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "ctx.openUrl")
    }

    override suspend fun openCalendar(atEpochMillis: Long?) {
        val at = atEpochMillis ?: now()
        val uri = CalendarContract.CONTENT_URI.buildUpon()
            .appendPath("time")
            .appendPath(at.toString())
            .build()
        start(Intent(Intent.ACTION_VIEW, uri), "ctx.openCalendar")
    }

    override suspend fun openEvent(eventId: Long) {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        start(Intent(Intent.ACTION_VIEW, uri), "ctx.openEvent")
    }

    override suspend fun openAlarms() {
        // A standard action every clock app registers, so this works without
        // knowing which clock the device ships.
        start(Intent(AlarmClock.ACTION_SHOW_ALARMS), "ctx.openAlarms")
    }

    private suspend fun start(intent: Intent, api: String) {
        requireDeclared(PluginPermission.LAUNCH, api)
        if (!allowLaunch) {
            throw QuickJsHostError("$api can only be called from onClick, not from render")
        }
        // The launcher isn't an Activity context here, so the new task flag is
        // required. Starting from a click means the app is foregrounded, so
        // background-activity-start restrictions don't apply.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        withContext(Dispatchers.Main) {
            runCatching { context.startActivity(intent) }.getOrElse {
                throw QuickJsHostError("$api: nothing on this device can open that")
            }
        }
    }

    /**
     * Exact host match, or a subdomain of a declared host. Prefix matching
     * alone would let "evil-api.example.com" through a declaration of
     * "api.example.com", hence the explicit dot boundary.
     */
    private fun hostAllowed(host: String): Boolean {
        val h = host.lowercase()
        return manifest.domains.any { declared ->
            val d = declared.lowercase().removePrefix("*.")
            h == d || h.endsWith(".$d")
        }
    }

    private fun requireDeclared(permission: PluginPermission, api: String) {
        if (permission !in manifest.permissions) {
            throw QuickJsHostError("$api needs \"$permission\" in the plugin manifest's permissions")
        }
    }

    private fun requireGranted(androidPermission: String, label: String) {
        val granted = ContextCompat.checkSelfPermission(context, androidPermission) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            throw QuickJsHostError("$label permission was requested by this plugin but not granted")
        }
    }

    private fun httpGet(url: URL): String {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 5_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("User-Agent", "konmin/0.1 (+plugin:${manifest.id})")
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw QuickJsHostError("HTTP $code")
            if (body.length > MAX_BODY_CHARS) {
                throw QuickJsHostError("response larger than $MAX_BODY_CHARS chars")
            }
            return body
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val MAX_BODY_CHARS = 512 * 1024
        const val MAX_KEY_CHARS = 128
        const val MAX_VALUE_CHARS = 64 * 1024
        const val MAX_EVENTS = 50
        const val SINGLE_FIX_TIMEOUT_MILLIS = 5_000L

        /** Cheapest usable provider first; GPS is the last resort. */
        val SINGLE_FIX_PROVIDERS = listOf(
            LocationManager.FUSED_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER
        )
    }
}
