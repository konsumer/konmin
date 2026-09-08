package com.konsumer.konmin.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import com.konsumer.konmin.data.ThemeSettings

/**
 * An optional outline behind text, so the launcher stays readable over a
 * busy wallpaper without painting a background over it.
 *
 * It's a stroked outline rather than a blurred drop shadow. A shadow was
 * the obvious cheap version, but blur spreads the same ink over more
 * pixels, so the hard case — white text on a white wallpaper — comes out
 * washed out and grey rather than cut out. Stroking the glyph outline
 * keeps every pixel of the halo at full opacity.
 *
 * The cost is drawing each line twice, so it's only paid when the user has
 * actually turned a glow on; with it off this is a single plain Text.
 */
data class GlowStyle(val color: Color, val width: Float)

fun ThemeSettings.glowStyle(): GlowStyle? =
    if (glowEnabled) GlowStyle(Color(glowColorArgb), glowRadius) else null

@Composable
fun GlowText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    glow: GlowStyle?,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    fillWidth: Boolean = false
) {
    if (glow == null) {
        Text(
            text = text,
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            textAlign = textAlign,
            modifier = if (fillWidth) modifier.fillMaxWidth() else modifier
        )
        return
    }

    // The two passes must lay out identically or the outline drifts off the
    // glyphs, so they differ only in colour and draw style.
    val inner = if (fillWidth) Modifier.fillMaxWidth() else Modifier
    Box(modifier = modifier) {
        Text(
            text = text,
            color = glow.color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            textAlign = textAlign,
            // Round joins stop the outline spiking at sharp corners, which
            // is very visible on letters like A, W and 4.
            style = TextStyle(drawStyle = Stroke(width = glow.width, join = StrokeJoin.Round)),
            modifier = inner
        )
        Text(
            text = text,
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            textAlign = textAlign,
            modifier = inner
        )
    }
}

/**
 * Glow as a blurred shadow instead of a stroke.
 *
 * The stroked version needs two draw passes over the same text, which a
 * text *field* can't do — there'd be two carets. Editable text falls back
 * to a shadow: softer than the outline used elsewhere, but it's one short
 * line the user is actively looking at, so the weaker contrast doesn't
 * matter the way it does for the app list.
 */
fun GlowStyle.asShadowStyle(): TextStyle =
    TextStyle(shadow = Shadow(color = color, offset = Offset.Zero, blurRadius = width))
