package com.konsumer.konmin.plugin

import com.whl.quickjs.wrapper.QuickJSContext
import kotlinx.serialization.json.Json

/**
 * Pulls the `manifest` object out of a plugin's source by evaluating it in
 * a throwaway QuickJS context with nothing bound — no `ctx`, no network, no
 * storage. A plugin that tries to do real work at load time simply fails to
 * install, which is the behaviour we want.
 *
 * The manifest is read back via a JSON round-trip rather than a property
 * walk, because a top-level `const manifest = {...}` lands in QuickJS's
 * global *lexical* scope and is not reachable as a globalThis property —
 * only evaluated code can see it.
 */
object ManifestExtractor {

    private const val TIMEOUT_MILLIS = 1_000L

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private const val READ_MANIFEST = """
        globalThis.__konminManifest =
          (typeof manifest !== 'undefined') ? JSON.stringify(manifest) : null;
        globalThis.__konminHandlesClick = (typeof onClick === 'function');
    """

    fun extract(source: String): Result<PluginManifest> {
        // A dedicated thread keeps QuickJS's thread affinity intact and stops
        // a pathological source file from wedging the caller forever.
        var outcome: Result<PluginManifest> = Result.failure(
            PluginException("manifest extraction did not finish")
        )
        val worker = Thread({ outcome = extractBlocking(source) }, "konmin-manifest").apply {
            isDaemon = true
            start()
        }
        worker.join(TIMEOUT_MILLIS)
        return outcome
    }

    private fun extractBlocking(source: String): Result<PluginManifest> {
        var context: QuickJSContext? = null
        return runCatching {
            val qjs = QuickJSContext.create().also { context = it }
            qjs.setMaxStackSize(1 shl 18)
            qjs.setMemoryLimit(8 * 1024 * 1024)

            qjs.evaluate(source, "manifest-probe.js")
            qjs.evaluate(READ_MANIFEST, "konmin/manifest.js")

            val raw = qjs.globalObject.getProperty("__konminManifest") as? String
                ?: throw PluginException("plugin declares no top-level `manifest` object")

            // Detected, never trusted from the manifest: a plugin claiming to
            // handle clicks when it doesn't would give the user dead taps.
            val handlesClick = qjs.globalObject.getProperty("__konminHandlesClick") as? Boolean
            json.decodeFromString<PluginManifest>(raw).copy(handlesClick = handlesClick == true)
        }.recoverCatching { error ->
            throw PluginException("could not read manifest: ${error.message}", error)
        }.also {
            runCatching { context?.destroy() }
        }
    }
}
