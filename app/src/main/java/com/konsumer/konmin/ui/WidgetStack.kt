package com.konsumer.konmin.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.konsumer.konmin.data.WidgetConfig
import com.konsumer.konmin.data.WidgetConfigDao
import com.konsumer.konmin.plugin.LayoutMath
import com.konsumer.konmin.plugin.Line
import com.konsumer.konmin.plugin.LineAlign
import kotlinx.serialization.json.Json

/**
 * Renders the stack of enabled widgets top-to-bottom, each widget's lines
 * pulled from its cached lastRenderedLinesJson (written by WidgetRenderer
 * on the tick cycle). This composable never triggers network activity or
 * JS execution itself — it only displays what's already cached, which
 * keeps scrolling and recomposition free of any battery cost.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WidgetStack(
    widgetConfigDao: WidgetConfigDao,
    fgColor: Color,
    baseSizeSp: Float,
    glow: GlowStyle?,
    /** Null when the user has turned the long-press gesture off. */
    onLongPress: (() -> Unit)?,
    /** Ids of plugins that define onClick; only their lines become tappable. */
    clickableWidgetIds: Set<String>,
    onLineClick: (widgetId: String, lineIndex: Int, text: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val configs by widgetConfigDao.getAll().collectAsState(initial = emptyList())
    val visible = configs.filter { it.enabled }.sortedBy { it.position }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // The app list fills the rest of the screen, so this band is the
            // one reliable place to long-press that isn't an app row. Given a
            // minimum height so it stays a usable target even with every
            // widget disabled.
            .heightIn(min = 132.dp)
            .then(
                if (onLongPress == null) {
                    Modifier
                } else {
                    Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = onLongPress,
                        indication = null,
                        interactionSource = MutableInteractionSource()
                    )
                }
            )
            .padding(top = 24.dp, start = 24.dp, end = 24.dp)
    ) {
        if (visible.isEmpty()) {
            GlowText(
                text = "No widgets enabled",
                color = fgColor.copy(alpha = 0.45f),
                fontSize = (baseSizeSp * 0.85f).sp,
                glow = glow
            )
            GlowText(
                text = if (onLongPress != null) {
                    "Long-press here to open settings"
                } else {
                    "Open settings with the gear icon"
                },
                color = fgColor.copy(alpha = 0.45f),
                fontSize = (baseSizeSp * 0.7f).sp,
                glow = glow
            )
            return@Column
        }

        visible.forEach { config ->
            WidgetLines(
                config = config,
                fgColor = fgColor,
                baseSizeSp = baseSizeSp,
                glow = glow,
                clickable = config.id in clickableWidgetIds,
                onLineClick = { index, text -> onLineClick(config.id, index, text) },
                onLongPress = onLongPress
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WidgetLines(
    config: WidgetConfig,
    fgColor: Color,
    baseSizeSp: Float,
    glow: GlowStyle?,
    clickable: Boolean,
    onLineClick: (Int, String) -> Unit,
    onLongPress: (() -> Unit)?
) {
    val lines: List<Line> = rememberLines(config.lastRenderedLinesJson)

    lines.forEachIndexed { index, line ->
        val multiplier = LayoutMath.SIZE_MULTIPLIERS.getValue(LayoutMath.clampSize(line.size))
        GlowText(
            text = line.text,
            color = fgColor,
            fontSize = (baseSizeSp * multiplier).sp,
            glow = glow,
            fontWeight = if (line.weight > 0) FontWeight.Bold else FontWeight.Normal,
            textAlign = when (line.align) {
                LineAlign.START -> TextAlign.Start
                LineAlign.CENTER -> TextAlign.Center
                LineAlign.END -> TextAlign.End
            },
            fillWidth = true,
            // Only plugins that actually define onClick get tap targets, so a
            // purely informational widget doesn't look interactive. The
            // long-press has to be re-attached here: a clickable child would
            // otherwise swallow the gesture before it reaches the band.
            modifier = if (!clickable) {
                Modifier
            } else {
                Modifier.combinedClickable(
                    onClick = { onLineClick(index, line.text) },
                    onLongClick = onLongPress,
                    indication = null,
                    interactionSource = MutableInteractionSource()
                )
            }
        )
    }
}

private val lineJson = Json { ignoreUnknownKeys = true }

/** Decodes the cached JSON once per distinct payload, not on every recomposition. */
@Composable
private fun rememberLines(json: String?): List<Line> = remember(json) {
    if (json.isNullOrBlank()) {
        emptyList()
    } else {
        runCatching { lineJson.decodeFromString<List<Line>>(json) }.getOrDefault(emptyList())
    }
}
