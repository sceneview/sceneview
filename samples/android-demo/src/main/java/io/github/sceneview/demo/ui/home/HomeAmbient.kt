package io.github.sceneview.demo.ui.home

import android.content.res.Resources
import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * `home-row-ambient` — the colour a Home row takes from its own picture (`DESIGN.md`).
 *
 * A row is not a grey tile with a picture pasted on it: the picture dissolves into the
 * row, and the row is the picture's own colour carried on under the text — a navy row
 * under the night-time Rerun capture, a warm one under the fox, a pale green one under
 * the splat in light. The hue and saturation come from the picture; the lightness does
 * not. It is solved so the tint lands on one fixed relative luminance per scheme
 * ([AMBIENT_LUMINANCE_DARK], [AMBIENT_LUMINANCE_LIGHT]), so `on-surface` and
 * `on-surface-variant` hold the same contrast on every row whatever the picture is.
 *
 * iOS computes the same colour from the same image with the same arithmetic
 * (`HomeAmbient.swift`); keep the two in step.
 */
internal object HomeAmbient {
    private val cache = HashMap<Long, Color>()

    /** The ambient tint of drawable [res] for the scheme, decoded once and cached. */
    fun tint(resources: Resources, @DrawableRes res: Int, dark: Boolean): Color {
        val key = (res.toLong() shl 1) or (if (dark) 1L else 0L)
        synchronized(cache) { cache[key]?.let { return it } }
        val seed = decodeSeed(resources, res)
        val color = ambientTint(seed, dark)
        synchronized(cache) { cache[key] = color }
        return color
    }

    /**
     * The picture decoded at 1/16 (50 x 40 for an 800 x 640 preview) — plenty for an
     * average, and cheap enough to run on first composition of a row.
     */
    private fun decodeSeed(resources: Resources, @DrawableRes res: Int): Color {
        val options = BitmapFactory.Options().apply { inSampleSize = AMBIENT_SAMPLE_SIZE }
        val bitmap = BitmapFactory.decodeResource(resources, res, options) ?: return Color.Gray
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        bitmap.recycle()
        return ambientSeed(pixels)
    }
}

/** Downsampling of the picture before it is averaged. */
private const val AMBIENT_SAMPLE_SIZE = 16

/**
 * Relative luminance of the ambient tint in dark: `surface-container-high` (#2C3546) —
 * the grey tile the rows replace — so the text keeps the contrast it had there:
 * `on-surface` 11.2:1, `on-surface-variant` 5.3:1.
 */
internal const val AMBIENT_LUMINANCE_DARK = 0.035f

/**
 * Relative luminance of the ambient tint in light: a pale wash one step off the white
 * page. `on-surface` 14:1, `on-surface-variant` 8.2:1.
 */
internal const val AMBIENT_LUMINANCE_LIGHT = 0.84f

/** The ambient tint never goes past this HSL saturation: a colour, never a poster. */
internal const val AMBIENT_MAX_SATURATION = 0.5f

/**
 * The picture's colour: the mean of [argb] where each pixel weighs `0.1 + chroma`, so a
 * fox on a grey studio floor reads orange, not grey, while a neutral picture stays
 * neutral. Pure, shared with iOS (`HomeAmbient.seed`).
 */
internal fun ambientSeed(argb: IntArray): Color {
    var r = 0.0
    var g = 0.0
    var b = 0.0
    var total = 0.0
    for (pixel in argb) {
        val pr = ((pixel shr 16) and 0xFF) / 255.0
        val pg = ((pixel shr 8) and 0xFF) / 255.0
        val pb = (pixel and 0xFF) / 255.0
        val weight = 0.1 + (max(pr, max(pg, pb)) - min(pr, min(pg, pb)))
        r += pr * weight
        g += pg * weight
        b += pb * weight
        total += weight
    }
    if (total == 0.0) return Color.Gray
    return Color((r / total).toFloat(), (g / total).toFloat(), (b / total).toFloat())
}

/**
 * The row colour for a picture whose colour is [seed]: the seed's hue, its saturation
 * capped at [AMBIENT_MAX_SATURATION], and the HSL lightness that puts the result on the
 * scheme's fixed relative luminance.
 */
internal fun ambientTint(seed: Color, dark: Boolean): Color {
    val (hue, saturation) = hueSaturation(seed.red, seed.green, seed.blue)
    val s = min(saturation * 1.2f, AMBIENT_MAX_SATURATION)
    val target = if (dark) AMBIENT_LUMINANCE_DARK else AMBIENT_LUMINANCE_LIGHT
    // Luminance grows monotonically with HSL lightness at a fixed hue and saturation.
    var low = 0f
    var high = 1f
    repeat(24) {
        val mid = (low + high) / 2f
        if (luminance(hslToRgb(hue, s, mid)) < target) low = mid else high = mid
    }
    val (r, g, b) = hslToRgb(hue, s, (low + high) / 2f)
    return Color(r, g, b)
}

private fun hueSaturation(r: Float, g: Float, b: Float): Pair<Float, Float> {
    val maxC = max(r, max(g, b))
    val minC = min(r, min(g, b))
    val delta = maxC - minC
    val l = (maxC + minC) / 2f
    if (delta < 1e-6f) return 0f to 0f
    val s = delta / (1f - abs(2f * l - 1f))
    val h = when (maxC) {
        r -> 60f * (((g - b) / delta).mod(6f))
        g -> 60f * ((b - r) / delta + 2f)
        else -> 60f * ((r - g) / delta + 4f)
    }
    return h to s.coerceIn(0f, 1f)
}

private fun hslToRgb(h: Float, s: Float, l: Float): Triple<Float, Float, Float> {
    val c = (1f - abs(2f * l - 1f)) * s
    val x = c * (1f - abs((h / 60f).mod(2f) - 1f))
    val m = l - c / 2f
    val (r, g, b) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Triple((r + m).coerceIn(0f, 1f), (g + m).coerceIn(0f, 1f), (b + m).coerceIn(0f, 1f))
}

/** WCAG relative luminance of an sRGB colour. */
internal fun luminance(rgb: Triple<Float, Float, Float>): Float {
    fun linear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    return 0.2126f * linear(rgb.first) + 0.7152f * linear(rgb.second) + 0.0722f * linear(rgb.third)
}
