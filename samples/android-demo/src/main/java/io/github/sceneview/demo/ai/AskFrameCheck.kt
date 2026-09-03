package io.github.sceneview.demo.ai

/**
 * Verdict on the frame Point & Ask is about to hand to Gemini Nano (#3407).
 *
 * Before this existed the only check was "is a meaningful share of the capture fully
 * transparent" (`hasTransparentHole`, #2654/#3276). That catches exactly ONE shape of a lost
 * `SurfaceView` layer — the one where the compositor leaves `alpha == 0` behind. The other,
 * more common shape leaves **opaque black** in the AR region instead, sails through an
 * alpha-only probe, and is then cropped tighter around the tap (#3343) so the frame that
 * reaches the model is uniformly flat. The model is asked to describe a blank image and
 * either says it sees nothing or completes with no text at all — "aucune réponse Gemini qui
 * voit rien sur la frame AR" (#3407).
 *
 * So: a frame is validated for **size**, **transparency** AND **flatness** before it leaves
 * the app, and each failure names its own cause.
 */
sealed interface AskFrameVerdict {

    /** The frame carries real content — send it. */
    data object Usable : AskFrameVerdict

    /** Degenerate capture: zero-size, or too small to carry anything a model could read. */
    data class TooSmall(val width: Int, val height: Int) : AskFrameVerdict

    /**
     * A meaningful share of the frame is fully transparent — the signature of a read-back
     * that skipped the Filament `SurfaceView` layer (camera + placed AR objects) while still
     * reporting success (#2654).
     */
    data class MissingArLayer(val transparentFraction: Float) : AskFrameVerdict

    /**
     * The frame is opaque but essentially one flat colour — a black viewport, a covered
     * lens, or the same lost-layer defect in its opaque form. [lumaSpread] is the observed
     * 1st..99th-percentile luminance span, out of 255.
     */
    data class Blank(val lumaSpread: Int) : AskFrameVerdict
}

/** Shortest edge a frame must have before it is worth a round-trip to the model. */
const val ASK_FRAME_MIN_EDGE = 64

/**
 * Share of fully transparent samples above which the frame is treated as missing its AR
 * layer. Matches the bug-report screenshot probe (#2654): a correctly composited read-back
 * of an opaque app window contains no `alpha == 0` pixels at all, so any real share of them
 * means a layer was punched out.
 */
const val ASK_FRAME_TRANSPARENT_FRACTION = 0.05f

/**
 * Luminance span (1st..99th percentile, out of 255) below which the frame is treated as
 * flat. Deliberately conservative: a real camera frame of a bare white wall still spans
 * well over this once sensor noise and vignetting are in it, while a black or single-colour
 * composite spans 0. The percentiles — rather than min/max — keep a stray bright pixel (a
 * status-bar glyph clipped into the crop, a hot pixel) from vouching for an otherwise empty
 * frame.
 */
const val ASK_FRAME_MIN_LUMA_SPREAD = 8

/**
 * Inspects a strided sample of the frame that is about to be sent to the model.
 *
 * Pure on purpose — no `Bitmap`, no Android types — so every branch is unit-testable on the
 * JVM (`AskFrameCheckTest`). The Android side (`inspectFrameForModel`) only reads the pixels
 * out of the bitmap and calls this.
 *
 * @param width  width of the frame the samples came from, in pixels
 * @param height height of the frame the samples came from, in pixels
 * @param samples ARGB_8888 pixels sampled across the frame, in any order
 */
fun inspectAskFrame(width: Int, height: Int, samples: IntArray): AskFrameVerdict {
    if (width < ASK_FRAME_MIN_EDGE || height < ASK_FRAME_MIN_EDGE || samples.isEmpty()) {
        return AskFrameVerdict.TooSmall(width, height)
    }

    var transparent = 0
    val histogram = IntArray(256)
    var opaque = 0
    for (pixel in samples) {
        if (pixel ushr 24 == 0) {
            transparent++
            continue
        }
        opaque++
        // Rec. 601 luma, integer-only — the exact weights do not matter here, only that a
        // flat region maps to a single bucket and a textured one does not.
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        histogram[(299 * r + 587 * g + 114 * b) / 1000]++
    }

    val transparentFraction = transparent.toFloat() / samples.size
    if (transparentFraction >= ASK_FRAME_TRANSPARENT_FRACTION) {
        return AskFrameVerdict.MissingArLayer(transparentFraction)
    }
    // Every sample transparent-but-under-threshold is impossible (the branch above would
    // have caught it), so `opaque` is non-zero here; guard anyway rather than divide by it
    // on faith.
    if (opaque == 0) return AskFrameVerdict.Blank(lumaSpread = 0)

    val low = percentileLuma(histogram, opaque, 0.01f)
    val high = percentileLuma(histogram, opaque, 0.99f)
    val spread = high - low
    if (spread < ASK_FRAME_MIN_LUMA_SPREAD) return AskFrameVerdict.Blank(spread)

    return AskFrameVerdict.Usable
}

/** The luminance bucket at [fraction] of [total] samples, walking [histogram] from 0. */
private fun percentileLuma(histogram: IntArray, total: Int, fraction: Float): Int {
    val target = (total * fraction).toInt().coerceIn(0, total - 1)
    var seen = 0
    for (luma in histogram.indices) {
        seen += histogram[luma]
        if (seen > target) return luma
    }
    return histogram.lastIndex
}

/** The user-facing failure a rejected frame maps to. [AskFrameVerdict.Usable] has none. */
fun AskFrameVerdict.asFailure(): AskFailure? = when (this) {
    AskFrameVerdict.Usable -> null
    is AskFrameVerdict.TooSmall -> AskFailure.CaptureFailed
    is AskFrameVerdict.MissingArLayer -> AskFailure.CaptureMissingArLayer
    is AskFrameVerdict.Blank -> AskFailure.CaptureBlank
}
