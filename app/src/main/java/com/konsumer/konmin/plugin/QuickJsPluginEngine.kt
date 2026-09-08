package com.konsumer.konmin.plugin

import android.util.Log
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.JSObject
import com.whl.quickjs.wrapper.QuickJSContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Runs a plugin's `render(ctx)` inside QuickJS.
 *
 * Threading: a QuickJS context is thread-affine, so every context lives on
 * one dedicated single-thread executor and a fresh context is created per
 * render — plugins never share globals, and a plugin that corrupts its own
 * heap only ruins its own next tick.
 *
 * Async: the host functions bound onto `ctx` are synchronous (we're already
 * on a background thread, so blocking it is fine and it avoids marshalling
 * promise resolution across JNI). Plugins can still be written as
 * `async function render(ctx)` with `await ctx.fetch(...)` — awaiting a
 * non-thenable just defers to the next microtask, and the wrapper drains
 * QuickJS's job queue after each call.
 *
 * Timeout: quickjs-wrapper doesn't expose JS_SetInterruptHandler, so a
 * plugin spinning in `while(true)` genuinely cannot be interrupted. The
 * budget is therefore enforced by *waiting* on the worker with a deadline
 * rather than by cancelling it: coroutine cancellation is cooperative and
 * would never fire against a thread blocked in native code, which would let
 * one bad plugin stall every other widget behind it. On expiry we abandon
 * the daemon thread and report a timeout; WidgetRenderer then disables the
 * widget, so a runaway plugin costs one leaked thread exactly once.
 *
 * The budget has to cover host calls too, since ctx.fetch does real network
 * on this thread — hence seconds, not milliseconds.
 */
class QuickJsPluginEngine(
    private val pluginId: String,
    private val timeoutMillis: Long = 10_000L
) : PluginEngine {

    override suspend fun render(pluginSource: String, ctx: PluginContext): Result<RenderResult> =
        run(pluginSource, ctx, clicked = null).mapCatching { result ->
            result ?: throw PluginException("render() returned nothing")
        }

    override suspend fun click(
        pluginSource: String,
        ctx: PluginContext,
        clicked: ClickedLine
    ): Result<RenderResult?> = run(pluginSource, ctx, clicked)

    private suspend fun run(
        pluginSource: String,
        ctx: PluginContext,
        clicked: ClickedLine?
    ): Result<RenderResult?> =
        withContext(Dispatchers.IO) {
            val executor = Executors.newSingleThreadExecutor { r ->
                Thread(r, "konmin-js-$pluginId").apply { isDaemon = true }
            }
            try {
                val task = executor.submit<Result<RenderResult?>> {
                    runOnJsThread(pluginSource, ctx, clicked)
                }
                try {
                    task.get(timeoutMillis, TimeUnit.MILLISECONDS)
                } catch (timeout: TimeoutException) {
                    // cancel() can't stop a thread inside a native call; it
                    // only stops us waiting on it.
                    task.cancel(true)
                    Result.failure(
                        PluginTimeoutException("render() exceeded ${timeoutMillis}ms")
                    )
                } catch (error: ExecutionException) {
                    Result.failure(
                        PluginException(error.cause?.message ?: "render() failed", error.cause)
                    )
                }
            } finally {
                // Returns immediately; a thread still stuck in QuickJS is a
                // daemon and won't hold the process open.
                executor.shutdownNow()
            }
        }

    private fun runOnJsThread(
        pluginSource: String,
        ctx: PluginContext,
        clicked: ClickedLine?
    ): Result<RenderResult?> {
        var context: QuickJSContext? = null
        return try {
            val qjs = QuickJSContext.create().also { context = it }
            qjs.setMaxStackSize(1 shl 20)          // 1MB JS stack
            qjs.setMemoryLimit(16 * 1024 * 1024)   // 16MB heap ceiling per render
            qjs.setConsole(object : QuickJSContext.Console {
                override fun log(msg: String) { Log.d(TAG, "[$pluginId] $msg") }
                override fun info(msg: String) { Log.i(TAG, "[$pluginId] $msg") }
                override fun warn(msg: String) { Log.w(TAG, "[$pluginId] $msg") }
                override fun error(msg: String) { Log.e(TAG, "[$pluginId] $msg") }
            })

            qjs.globalObject.setProperty(HOST_KEY, bindContext(qjs, ctx))
            qjs.evaluate(CTX_SETUP, "konmin/ctx.js")
            if (clicked != null) {
                // The tapped line, as ctx.clicked, before the plugin runs.
                qjs.globalObject.setProperty(CLICK_KEY, clicked.toJsonString())
                qjs.evaluate(CLICK_SETUP, "konmin/click.js")
            }

            qjs.evaluate(pluginSource, "$pluginId/index.js")
            qjs.evaluate(glueFor(clicked != null), "konmin/glue.js")

            // Host functions are synchronous, so the promise chain above has
            // no real suspension points and settles as soon as the microtask
            // queue drains. Nudge the queue a few times in case the wrapper
            // hasn't flushed it, then give up rather than spin.
            val result = qjs.globalObject.getJSObject("__konmin")
            for (attempt in 0 until 8) {
                if (result.getBoolean("done") == true) break
                qjs.evaluate("void 0")
            }

            when {
                result.getBoolean("done") != true ->
                    Result.failure(PluginException("render() never settled (unresolved promise?)"))
                result.getString("err") != null ->
                    Result.failure(PluginException(result.getString("err")))
                // A click that only launched something returns nothing, and
                // must leave the cached output alone rather than blanking it.
                // JSON.stringify(null) is the *string* "null", so an empty
                // check alone would send it to the render parser and report a
                // perfectly ordinary click as a failure.
                clicked != null && result.getString("out").isEmptyResult() ->
                    Result.success(null)
                else -> parseResult(result.getString("out"))
            }
        } catch (t: Throwable) {
            Result.failure(PluginException(t.message ?: t.toString(), t))
        } finally {
            runCatching { context?.destroy() }
        }
    }

    /** Builds the `ctx` object handed to render(), one host function per API method. */
    private fun bindContext(qjs: QuickJSContext, ctx: PluginContext): JSObject {
        val obj = qjs.createNewJSObject()

        obj.setProperty("now", hostCall(qjs) { ctx.now().toDouble() })

        obj.setProperty("is24Hour", hostCall(qjs) { ctx.is24Hour() })

        obj.setProperty("formatTime", hostCall(qjs) { args ->
            val millis = (args.getOrNull(0) as? Number)?.toLong()
                ?: throw QuickJsHostError("ctx.formatTime(millis, pattern) requires a timestamp")
            val pattern = args.getOrNull(1) as? String
                ?: throw QuickJsHostError("ctx.formatTime(millis, pattern) requires a pattern string")
            ctx.formatTime(millis, pattern)
        })

        obj.setProperty("battery", hostCall(qjs) {
            val status = ctx.battery()
            qjs.createNewJSObject().apply {
                setProperty("level", status.level)
                setProperty("charging", status.charging)
            }
        })

        obj.setProperty("fetch", hostCall(qjs) { args ->
            val url = args.getOrNull(0) as? String
                ?: throw QuickJsHostError("ctx.fetch(url) requires a url string")
            // Second arg is an options object: { cacheMinutes }.
            val cacheMinutes = (args.getOrNull(1) as? JSObject)?.getInteger("cacheMinutes") ?: 15
            runBlocking { ctx.fetch(url, cacheMinutes) }
        })

        obj.setProperty("storageGet", hostCall(qjs) { args ->
            val key = args.getOrNull(0) as? String
                ?: throw QuickJsHostError("ctx.storageGet(key) requires a key string")
            runBlocking { ctx.storageGet(key) }
        })

        obj.setProperty("storageSet", hostCall(qjs) { args ->
            val key = args.getOrNull(0) as? String
                ?: throw QuickJsHostError("ctx.storageSet(key, value) requires a key string")
            val value = args.getOrNull(1)?.toString().orEmpty()
            runBlocking { ctx.storageSet(key, value) }
            null
        })

        obj.setProperty("location", hostCall(qjs) {
            val loc = runBlocking { ctx.location() }
            if (loc == null) {
                null
            } else {
                qjs.createNewJSObject().apply {
                    setProperty("lat", loc.first)
                    setProperty("lon", loc.second)
                }
            }
        })

        obj.setProperty("launchApp", hostCall(qjs) { args ->
            val pkg = args.getOrNull(0) as? String
                ?: throw QuickJsHostError("ctx.launchApp(packageName) requires a package name")
            runBlocking { ctx.launchApp(pkg) }
            null
        })

        obj.setProperty("openUrl", hostCall(qjs) { args ->
            val url = args.getOrNull(0) as? String
                ?: throw QuickJsHostError("ctx.openUrl(url) requires a url string")
            runBlocking { ctx.openUrl(url) }
            null
        })

        obj.setProperty("openCalendar", hostCall(qjs) { args ->
            val at = (args.getOrNull(0) as? Number)?.toLong()
            runBlocking { ctx.openCalendar(at) }
            null
        })

        obj.setProperty("openEvent", hostCall(qjs) { args ->
            val id = (args.getOrNull(0) as? Number)?.toLong()
                ?: throw QuickJsHostError("ctx.openEvent(id) requires an event id")
            runBlocking { ctx.openEvent(id) }
            null
        })

        obj.setProperty("openAlarms", hostCall(qjs) {
            runBlocking { ctx.openAlarms() }
            null
        })

        obj.setProperty("upcomingEvents", hostCall(qjs) { args ->
            val within = (args.getOrNull(0) as? Number)?.toInt() ?: (24 * 60)
            val events = runBlocking { ctx.upcomingEvents(within) }
            qjs.createNewJSArray().apply {
                events.forEachIndexed { i, e ->
                    set(
                        qjs.createNewJSObject().apply {
                            setProperty("id", e.id.toDouble())
                            setProperty("title", e.title)
                            setProperty("startsAt", e.startsAtEpochMillis.toDouble())
                            setProperty("allDay", e.allDay)
                        },
                        i
                    )
                }
            }
        })

        return obj
    }

    /**
     * Wraps a host function so no Java exception can ever escape into
     * QuickJS's native frame.
     *
     * This is not a nicety. JNI aborts the whole process the moment it makes
     * a call with a pending Java exception, so a plugin doing something as
     * ordinary as fetching a URL its manifest doesn't allow would take the
     * launcher down — and, since the widget's failure was never recorded,
     * take it down again on every tick after that.
     *
     * QuickJSContext.throwJSException is no help: it raises a Java
     * QuickJSException of its own, leaving exactly the pending exception we
     * were trying to avoid. So errors cross the boundary as an ordinary
     * return value — a sentinel object — and CTX_SETUP turns it back into a
     * real JS Error on the other side, where plugins can catch it normally.
     */
    private fun hostCall(qjs: QuickJSContext, body: (Array<out Any?>) -> Any?): JSCallFunction =
        JSCallFunction { args ->
            try {
                body(args)
            } catch (t: Throwable) {
                qjs.createNewJSObject().apply {
                    setProperty(ERROR_KEY, t.message ?: t.toString())
                }
            }
        }

    /**
     * Parses the JSON round-trip of whatever render() resolved to. Untrusted
     * output, so every field is validated and clamped rather than decoded
     * straight into the data class (a wrong type should drop one line, not
     * fail the whole widget).
     */
    private fun parseResult(outJson: String?): Result<RenderResult?> {
        if (outJson.isEmptyResult()) {
            return Result.failure(PluginException("returned nothing"))
        }
        return runCatching {
            // Guarded non-null above: isEmptyResult() already returned for
            // null/blank. The extension has no contract, so the compiler can't
            // smart-cast — assert it once here instead of repeating the check.
            val payload = requireNotNull(outJson) { "render() returned nothing" }
            val root = json.parseToJsonElement(payload) as? JsonObject
                ?: throw PluginException("render() must return an object")

            val lines = (root["lines"] as? JsonArray).orEmpty().mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null
                val text = (o["text"] as? JsonPrimitive)?.contentOrNullIfJsNull() ?: return@mapNotNull null
                Line(
                    text = text.take(MAX_LINE_CHARS),
                    size = LayoutMath.clampSize((o["size"] as? JsonPrimitive)?.intOrNull ?: 0),
                    weight = if (((o["weight"] as? JsonPrimitive)?.intOrNull ?: 0) != 0) 1 else 0,
                    align = runCatching {
                        LineAlign.valueOf(
                            ((o["align"] as? JsonPrimitive)?.contentOrNullIfJsNull() ?: "START").uppercase()
                        )
                    }.getOrDefault(LineAlign.START)
                )
            }.take(MAX_LINES)

            if (lines.isEmpty()) throw PluginException("returned no usable lines")

            RenderResult(
                lines = lines,
                nextCheckMinutes = (root["nextCheckMinutes"] as? JsonPrimitive)?.intOrNull
            )
        }
    }

    /** Covers every way JS can hand back "no value" across the JSON bridge. */
    private fun String?.isEmptyResult(): Boolean =
        isNullOrBlank() || this == "null" || this == "undefined"

    private fun JsonPrimitive.contentOrNullIfJsNull(): String? =
        if (this is kotlinx.serialization.json.JsonNull) null else content

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()

    private companion object {
        const val TAG = "konmin.js"
        const val MAX_LINES = 12
        const val MAX_LINE_CHARS = 200
        const val HOST_KEY = "__konminHost"
        const val ERROR_KEY = "__konminError"

        /** Every method exposed on `ctx`. Kept in step with bindContext. */
        val API = listOf(
            "now", "is24Hour", "formatTime", "battery",
            "fetch", "storageGet", "storageSet", "location", "upcomingEvents",
            "launchApp", "openUrl", "openCalendar", "openEvent", "openAlarms"
        )

        /**
         * Builds the `ctx` a plugin actually sees: a thin JS shim over the
         * raw host bindings that rethrows the host's error sentinel as a
         * normal JS Error. The raw bindings are dropped from the global scope
         * afterwards so a plugin can only reach the wrapped versions.
         */
        val CTX_SETUP = """
            globalThis.ctx = (function (host) {
              var api = ${API.joinToString(prefix = "[", postfix = "]") { "'" + it + "'" }};
              var out = {};
              api.forEach(function (name) {
                out[name] = function () {
                  var r = host[name].apply(host, arguments);
                  if (r && typeof r === 'object' && typeof r.$ERROR_KEY === 'string') {
                    throw new Error(r.$ERROR_KEY);
                  }
                  return r;
                };
              });
              return out;
            })(globalThis.$HOST_KEY);
            delete globalThis.$HOST_KEY;
        """.trimIndent()

        val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Wiring for ctx.clicked. Set as a JSON string and parsed here, for
         * the same reason results come back as JSON — one string across JNI
         * rather than building an object graph through the bridge.
         */
        const val CLICK_KEY = "__konminClick"

        val CLICK_SETUP = """
            ctx.clicked = JSON.parse(globalThis.$CLICK_KEY);
            delete globalThis.$CLICK_KEY;
        """.trimIndent()

        /**
         * onClick is optional and may legitimately return nothing, so unlike
         * render it resolves to null rather than throwing when absent.
         */
        fun glueFor(isClick: Boolean): String {
            val body = if (isClick) {
                "if (typeof onClick !== 'function') return null; return await onClick(ctx);"
            } else {
                "if (typeof render !== 'function') throw new Error('plugin defines no render(ctx) function'); " +
                    "return await render(ctx);"
            }
            return GLUE_TEMPLATE.replace("__BODY__", body)
        }

        val GLUE_TEMPLATE = """
            globalThis.__konmin = { out: null, err: null, done: false };
            (async function () {
              __BODY__
            })().then(
              function (r) { __konmin.out = JSON.stringify(r === undefined ? null : r); __konmin.done = true; },
              function (e) {
                // QuickJS's e.stack carries only frames, not the message, so
                // reporting the stack alone loses the actual reason.
                var msg = (e && e.message) ? e.message : String(e);
                var where = (e && e.stack) ? String(e.stack).trim().split('\n')[0].trim() : '';
                __konmin.err = where ? (msg + ' (' + where + ')') : msg;
                __konmin.done = true;
              }
            );
        """.trimIndent()
    }
}

private fun ClickedLine.toJsonString(): String =
    Json.encodeToString(ClickedLineJson(index = index, text = text))

@kotlinx.serialization.Serializable
private data class ClickedLineJson(val index: Int, val text: String)

open class PluginException(message: String, cause: Throwable? = null) : Exception(message, cause)
class PluginTimeoutException(message: String) : PluginException(message)

/** Thrown from a host function to surface as a JS-side exception. */
class QuickJsHostError(message: String) : RuntimeException(message)
