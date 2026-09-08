package com.konsumer.konmin.plugin

import kotlinx.serialization.Serializable

/**
 * A single line of output from a plugin's render() call.
 * `size` is relative to the user's global base text size, clamped to
 * [-2, 2] and mapped through SIZE_MULTIPLIERS in [LayoutMath].
 */
@Serializable
data class Line(
    val text: String,
    val size: Int = 0,
    val weight: Int = 0,               // 0 = normal, 1 = bold
    val align: LineAlign = LineAlign.START
)

@Serializable
enum class LineAlign { START, CENTER, END }

/**
 * What a plugin's render() returns. Total visual height is derived from
 * summing each line's size multiplier — see LayoutMath.heightUnits().
 * nextCheckMinutes lets the plugin self-report its own next cadence
 * (e.g. weather says "ask again in 60"), overriding the manifest default
 * for one cycle. Host clamps this to a sane floor/ceiling.
 */
@Serializable
data class RenderResult(
    val lines: List<Line>,
    val nextCheckMinutes: Int? = null
)

/**
 * Describes a plugin. Declared by the plugin's own JS as a top-level
 * `manifest` object, so a plugin is a single self-contained .js file the
 * user can pick from a file browser — there is no second file to keep in
 * sync and no archive format to unpack.
 *
 *     const manifest = {
 *       id: 'weather',
 *       name: 'Weather',
 *       intervalMinutes: 60,
 *       maxHeightUnits: 3,
 *       networkHeavy: true,
 *       permissions: ['NETWORK', 'LOCATION'],
 *       domains: ['api.open-meteo.com']
 *     }
 *
 * The host caches the extracted manifest next to the source so listing
 * installed plugins doesn't have to boot a JS engine per plugin.
 */
@Serializable
data class PluginManifest(
    val id: String,
    val name: String,
    val intervalMinutes: Int = 30,
    val maxHeightUnits: Float = 4f,
    val networkHeavy: Boolean = false,
    val permissions: List<PluginPermission> = emptyList(),
    /**
     * Hosts ctx.fetch() is allowed to reach, e.g. ["api.open-meteo.com"].
     * Enforced host-side; a plugin cannot widen this at runtime.
     */
    val domains: List<String> = emptyList(),
    /** Description shown in the plugin admin list. */
    val description: String = "",
    /**
     * Whether the source defines onClick(ctx). Detected when the plugin is
     * installed, not declared — a plugin claiming to handle clicks when it
     * doesn't would give the user dead tap targets.
     */
    val handlesClick: Boolean = false
)

@Serializable
enum class PluginPermission { NETWORK, LOCATION, CALENDAR, STORAGE, LAUNCH }

object LayoutMath {
    val SIZE_MULTIPLIERS = mapOf(-2 to 0.70f, -1 to 0.85f, 0 to 1.00f, 1 to 1.15f, 2 to 1.30f)

    fun clampSize(size: Int): Int = size.coerceIn(-2, 2)

    fun heightUnits(lines: List<Line>): Float =
        lines.sumOf { SIZE_MULTIPLIERS.getValue(clampSize(it.size)).toDouble() }.toFloat()

    /**
     * Truncate a plugin's lines to fit its allotted height budget.
     * Keeps lines in order while the running sum fits; if even the first
     * line doesn't fit, returns empty (caller should fall back to the
     * widget's last cached good render instead of showing a partial cut).
     */
    fun truncateToFit(lines: List<Line>, maxHeightUnits: Float): List<Line> {
        val out = mutableListOf<Line>()
        var running = 0f
        for (line in lines) {
            val h = SIZE_MULTIPLIERS.getValue(clampSize(line.size))
            if (running + h > maxHeightUnits) break
            out.add(line)
            running += h
        }
        return out
    }
}
