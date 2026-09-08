package com.konsumer.konmin.theme

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.palette.graphics.Palette
import com.konsumer.konmin.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Derives the launcher's foreground colour from the current wallpaper.
 *
 * Only the foreground is set — the background stays transparent so the
 * wallpaper shows through, which is the whole point of a launcher like
 * this. The chosen swatch is pushed away from the wallpaper's dominant
 * colour and forced to a readable luminance, since a palette swatch picked
 * purely for prominence is very often unreadable against the thing it
 * was picked from.
 */
object WallpaperAccent {

    private const val TAG = "konmin.accent"
    private const val SAMPLE_MAX_DIMENSION = 256

    suspend fun refresh(context: Context, settingsRepo: SettingsRepository) {
        if (!settingsRepo.settings.first().autoAccentFromWallpaper) return

        // READ_EXTERNAL_STORAGE gates getDrawable() on some OS versions; on
        // others it needs no permission at all. Either way, a refused read is
        // a no-op, not a crash — the user's manual colour stays.
        val drawable = withContext(Dispatchers.IO) {
            runCatching { WallpaperManager.getInstance(context).drawable }.getOrNull()
        } ?: return

        val color = withContext(Dispatchers.Default) { extractForeground(drawable) } ?: return
        val current = settingsRepo.settings.first()
        if (current.fgColorArgb != color) {
            settingsRepo.setColors(fg = color, bg = current.bgColorArgb)
        }
    }

    private fun extractForeground(drawable: Drawable): Int? {
        val bitmap = drawable.toSampledBitmap() ?: return null
        val palette = runCatching { Palette.from(bitmap).clearFilters().generate() }.getOrNull()
        bitmap.recycle()
        if (palette == null) return null

        val dominant = palette.getDominantColor(Color.BLACK)
        val candidate = palette.lightVibrantSwatch?.rgb
            ?: palette.lightMutedSwatch?.rgb
            ?: palette.vibrantSwatch?.rgb
            ?: palette.dominantSwatch?.rgb
            ?: return null

        return ensureReadableAgainst(candidate, dominant)
    }

    /**
     * Nudges [candidate] lighter or darker until it clears a contrast ratio
     * of 4.5:1 against [against] (the WCAG AA threshold for body text), or
     * bottoms out at pure white/black.
     */
    private fun ensureReadableAgainst(candidate: Int, against: Int): Int {
        val goLighter = luminance(against) < 0.5
        var color = candidate
        repeat(20) {
            if (contrastRatio(color, against) >= 4.5) return color
            color = if (goLighter) lighten(color) else darken(color)
        }
        return if (goLighter) Color.WHITE else Color.BLACK
    }

    private fun lighten(color: Int): Int = Color.rgb(
        (Color.red(color) + 12).coerceAtMost(255),
        (Color.green(color) + 12).coerceAtMost(255),
        (Color.blue(color) + 12).coerceAtMost(255)
    )

    private fun darken(color: Int): Int = Color.rgb(
        (Color.red(color) - 12).coerceAtLeast(0),
        (Color.green(color) - 12).coerceAtLeast(0),
        (Color.blue(color) - 12).coerceAtLeast(0)
    )

    private fun contrastRatio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun luminance(color: Int): Double {
        fun channel(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(Color.red(color)) +
            0.7152 * channel(Color.green(color)) +
            0.0722 * channel(Color.blue(color))
    }

    /**
     * Palette on a full 1440x3120 wallpaper is a needless allocation spike;
     * a 256px sample gives the same swatches for a fraction of the memory.
     */
    private fun Drawable.toSampledBitmap(): Bitmap? {
        val srcWidth = intrinsicWidth.takeIf { it > 0 } ?: SAMPLE_MAX_DIMENSION
        val srcHeight = intrinsicHeight.takeIf { it > 0 } ?: SAMPLE_MAX_DIMENSION
        val scale = (SAMPLE_MAX_DIMENSION.toFloat() / maxOf(srcWidth, srcHeight)).coerceAtMost(1f)
        val w = (srcWidth * scale).toInt().coerceAtLeast(1)
        val h = (srcHeight * scale).toInt().coerceAtLeast(1)

        return runCatching {
            if (this is BitmapDrawable && bitmap != null) {
                Bitmap.createScaledBitmap(bitmap, w, h, true)
            } else {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
                    val canvas = Canvas(bmp)
                    setBounds(0, 0, canvas.width, canvas.height)
                    draw(canvas)
                }
            }
        }.onFailure { Log.w(TAG, "wallpaper sample failed: ${it.message}") }.getOrNull()
    }
}

/**
 * Recomputes the accent only when the wallpaper actually changes, rather
 * than paying for a Palette pass on every launcher start.
 */
@Suppress("DEPRECATION") // ACTION_WALLPAPER_CHANGED is deprecated but still the only
// broadcast a live-wallpaper or picture change actually emits.
class WallpaperChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_WALLPAPER_CHANGED) return
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                WallpaperAccent.refresh(appContext, SettingsRepository(appContext))
            } finally {
                pending.finish()
            }
        }
    }
}
