package com.konsumer.konmin.plugin

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Plugins are single .js files kept in app-private storage:
 *
 *   filesDir/plugins/<id>.js         the source the user picked
 *   filesDir/plugins/<id>.json       manifest extracted from it at install
 *
 * A plugin declares its own manifest inside the JS, so installing one is
 * literally "pick a .js file" — there's no second file to keep in sync and
 * no archive to unpack. The extracted manifest is cached beside the source
 * so listing installed plugins never has to boot a JS engine.
 *
 * The examples in assets/plugins are installed the same way on first run:
 * they're ordinary plugins, editable and removable like any other, which is
 * the point — they're the documentation.
 */
class PluginRepository(private val context: Context) {

    val root: File get() = File(context.filesDir, "plugins").apply { mkdirs() }

    data class InstalledPlugin(val manifest: PluginManifest, val source: File)

    fun list(): List<InstalledPlugin> {
        val files = root.listFiles { f -> f.isFile && f.name.endsWith(".js") } ?: return emptyList()
        return files.mapNotNull { js -> load(js.nameWithoutExtension) }
            .sortedBy { it.manifest.name.lowercase() }
    }

    fun byId(id: String): InstalledPlugin? = load(id)

    fun readSource(id: String): String? = sourceFile(id).takeIf { it.isFile }?.readText()

    fun sourceFile(id: String): File = File(root, "$id.js")

    private fun manifestFile(id: String): File = File(root, "$id.json")

    /**
     * Installs (or replaces) a plugin from raw JS. The manifest is read out
     * of the source itself, which also serves as a validation pass: a file
     * that doesn't declare a well-formed manifest never reaches the store.
     */
    fun install(source: String): Result<PluginManifest> = runCatching {
        val manifest = ManifestExtractor.extract(source).getOrThrow()
        require(manifest.id.isNotBlank() && manifest.id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "plugin id must be non-empty, letters/digits/dash/underscore only"
        }
        require(manifest.name.isNotBlank()) { "plugin must declare a name" }

        sourceFile(manifest.id).writeText(source)
        manifestFile(manifest.id).writeText(json.encodeToString(manifest))
        manifest
    }

    /**
     * Reads a user-picked file through the content resolver, then installs it.
     * The read is capped rather than checked afterwards, so picking a 4GB
     * video by mistake costs one buffer, not the whole file.
     */
    fun installFromUri(uri: Uri): Result<PluginManifest> = runCatching {
        val source = context.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = CharArray(MAX_SOURCE_CHARS + 1)
            val reader = stream.bufferedReader()
            var filled = 0
            while (filled < buffer.size) {
                val read = reader.read(buffer, filled, buffer.size - filled)
                if (read < 0) break
                filled += read
            }
            require(filled <= MAX_SOURCE_CHARS) {
                "that file is over ${MAX_SOURCE_CHARS / 1024}KB — is it really a plugin?"
            }
            String(buffer, 0, filled)
        } ?: throw IllegalArgumentException("could not read the selected file")
        install(source).getOrThrow()
    }

    /**
     * Removes a user-installed plugin.
     *
     * Bundled examples are deliberately not removable — they're the API
     * documentation, and a user who deletes one has no obvious way back
     * ("restore examples" only helps if you already know it exists).
     * Disabling one costs nothing and leaves the source in place to read.
     * Enforced here rather than only in the UI so no caller can strand them.
     */
    fun uninstall(id: String): Result<Unit> = runCatching {
        require(!isBundled(id)) {
            "$id is a bundled example — disable it instead of removing it"
        }
        sourceFile(id).delete()
        manifestFile(id).delete()
    }

    /** Ids of the examples shipped in assets. Fixed for the life of the APK. */
    val bundledIds: Set<String> by lazy {
        (context.assets.list(ASSET_DIR) ?: emptyArray())
            .filter { it.endsWith(".js") }
            .map { it.removeSuffix(".js") }
            .toSet()
    }

    fun isBundled(id: String): Boolean = id in bundledIds

    /**
     * Copies the shipped examples into the store. `overwrite = false` on
     * first run so a user's edits survive; `true` when they explicitly ask
     * to restore the originals.
     */
    fun installBundledExamples(overwrite: Boolean): List<PluginManifest> {
        val assets = context.assets.list(ASSET_DIR)?.filter { it.endsWith(".js") } ?: return emptyList()
        return assets.mapNotNull { name ->
            val id = name.removeSuffix(".js")
            if (!overwrite && sourceFile(id).exists()) return@mapNotNull null
            runCatching {
                val source = context.assets.open("$ASSET_DIR/$name").use { it.bufferedReader().readText() }
                install(source).getOrThrow()
            }.onFailure { Log.w(TAG, "bundled example $name failed to install: ${it.message}") }
                .getOrNull()
        }
    }

    private fun load(id: String): InstalledPlugin? {
        val source = sourceFile(id)
        if (!source.isFile) return null

        val cached = manifestFile(id)
        if (cached.isFile) {
            runCatching { json.decodeFromString<PluginManifest>(cached.readText()) }
                .getOrNull()
                ?.let { return InstalledPlugin(it, source) }
        }

        // No cache (hand-pushed via adb, say) — extract it now and write one.
        return ManifestExtractor.extract(source.readText())
            .onFailure { Log.w(TAG, "plugin $id has no usable manifest: ${it.message}") }
            .onSuccess { runCatching { cached.writeText(json.encodeToString(it)) } }
            .map { InstalledPlugin(it, source) }
            .getOrNull()
    }

    private companion object {
        const val TAG = "konmin.plugins"
        const val ASSET_DIR = "plugins"
        const val MAX_SOURCE_CHARS = 512 * 1024
        val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    }
}
