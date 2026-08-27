package io.github.sceneview.demo.ai

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The pixel rectangle of a window capture that is actually sent to Gemini Nano, in source
 * coordinates. Pure geometry so it is unit-testable without a device (#3343).
 */
data class AskCaptureRegion(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    /** Edge length the crop is scaled to before being handed to ML Kit. */
    val scaledWidth: Int,
    val scaledHeight: Int,
)

/**
 * Longest edge of the image handed to the Prompt API.
 *
 * ML Kit's own preprocessing (genai-prompt 1.0.0-beta4) copies the bitmap and, if
 * `min(width, height) > 768`, rescales so the **short** edge becomes 768 — it never
 * touches the long edge. A phone-shaped 1080×2424 window capture therefore reached
 * Gemini Nano as a 768×1723 strip: ~2.9× the pixels of a 768×768 frame, most of them
 * ceiling and floor rather than the thing the user tapped, and a vision-token bill that
 * a 4000-token input budget (Nano-v2, the model shipped on Pixel 9-class devices) has no
 * room for once the question is added.
 *
 * Sending a square-ish crop at this size keeps the request inside the budget and points
 * the model at what the tap pointed at.
 */
const val ASK_IMAGE_MAX_EDGE = 768

/** Aspect ratio the crop is squeezed into, longest/shortest. 1.0 = square. */
private const val ASK_IMAGE_MAX_ASPECT = 4f / 3f

/**
 * Chooses the region of a [sourceWidth] × [sourceHeight] window capture to send, centred
 * on the tapped point ([focusX], [focusY], in source pixels) when one is known.
 *
 * Rules, in order:
 *  1. clamp the aspect ratio to [ASK_IMAGE_MAX_ASPECT] by shortening the long axis — a
 *     full-height phone frame is mostly floor and ceiling;
 *  2. slide that window so it contains the focus point, then clamp it inside the source
 *     (never letting it run off an edge, which would otherwise crop to empty);
 *  3. scale the result so its longest edge is at most [ASK_IMAGE_MAX_EDGE].
 *
 * A `null` focus (no tap coordinates — the QA synthetic frame) centres the window.
 * Degenerate sizes are clamped to at least 1 px so a caller can always build a bitmap.
 */
fun askCaptureRegion(
    sourceWidth: Int,
    sourceHeight: Int,
    focusX: Float? = null,
    focusY: Float? = null,
    maxEdge: Int = ASK_IMAGE_MAX_EDGE,
): AskCaptureRegion {
    val srcW = max(1, sourceWidth)
    val srcH = max(1, sourceHeight)

    // 1. Clamp the aspect ratio by shortening whichever axis is the long one.
    val shortEdge = min(srcW, srcH)
    val longCap = (shortEdge * ASK_IMAGE_MAX_ASPECT).roundToInt().coerceAtLeast(1)
    val cropW = if (srcW >= srcH) min(srcW, longCap) else srcW
    val cropH = if (srcH > srcW) min(srcH, longCap) else srcH

    // 2. Centre on the tap, then clamp inside the source.
    val centreX = focusX?.takeIf { it.isFinite() } ?: (srcW / 2f)
    val centreY = focusY?.takeIf { it.isFinite() } ?: (srcH / 2f)
    val x = (centreX - cropW / 2f).roundToInt().coerceIn(0, srcW - cropW)
    val y = (centreY - cropH / 2f).roundToInt().coerceIn(0, srcH - cropH)

    // 3. Downscale so the longest edge fits the model's budget.
    val cap = max(1, maxEdge)
    val longest = max(cropW, cropH)
    val scale = if (longest > cap) cap.toFloat() / longest else 1f
    return AskCaptureRegion(
        x = x,
        y = y,
        width = cropW,
        height = cropH,
        scaledWidth = (cropW * scale).roundToInt().coerceAtLeast(1),
        scaledHeight = (cropH * scale).roundToInt().coerceAtLeast(1),
    )
}
