package io.github.sceneview.demo.ai

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Logcat tag shared by every Point & Ask capture / inference path. */
const val ASK_LOG_TAG = "PointAndAskDemo"

/** Which layer the frame handed to the model was read back from. */
enum class AskCaptureSource(val label: String) {
    /** `PixelCopy` on the Filament `SurfaceView` itself — camera + virtual objects, no chrome. */
    ArSurface("AR surface"),

    /** `TextureView.getBitmap()` — the surface type QA mode uses for the backdrop (#3308). */
    ArTexture("AR texture"),

    /** `PixelCopy` on the whole window — the pre-#3407 path, now only a fallback. */
    Window("window composite"),
}

/** A frame that passed [inspectAskFrame] and is ready to be sent. Caller owns [bitmap]. */
class AskFrame(val bitmap: Bitmap, val source: AskCaptureSource)

/** Result of trying every capture path in order. */
sealed interface AskCaptureOutcome {
    data class Captured(val frame: AskFrame) : AskCaptureOutcome

    /**
     * Every path either failed or produced a frame the model could not use. [verdict] is the
     * *best* (most informative) rejection seen, so the card can name what was wrong rather
     * than falling back to a generic "couldn't capture".
     */
    data class Rejected(val verdict: AskFrameVerdict, val source: AskCaptureSource?) :
        AskCaptureOutcome
}

/**
 * Reads back the AR frame and returns the one that will actually be sent to Gemini Nano —
 * cropped around the tap, downscaled to the model's budget, and **validated** (#3407).
 *
 * Why the order matters. The demo used to read back the whole **window** and check only for
 * an `alpha == 0` hole. But the AR scene is a Filament `SurfaceView`: a separate compositor
 * layer that "punches a hole through the window" (the repo says so itself, in
 * `QaCameraBackdrop`'s KDoc), and whose absence from a window read-back has already been
 * observed with a SUCCESS result (#2654). When that layer comes back **opaque black** rather
 * than transparent — the shape an opaque window background produces — the alpha probe passes,
 * the #3343 crop then centres tightly on that very region, and the model is handed a flat
 * frame. That is the "sees nothing on the AR frame" half of #3407.
 *
 * So the AR view is now read back **directly**, by the API meant for it, and the window
 * composite is kept only as a fallback. Each candidate is validated before it is accepted, so
 * a defect in one path silently promotes the next instead of reaching the model.
 *
 * @param focusX/[focusY] tap position in **window** coordinates; translated per candidate.
 */
suspend fun captureAskFrame(
    activity: Activity,
    focusX: Float?,
    focusY: Float?,
): AskCaptureOutcome {
    val decor = activity.window?.decorView
    if (decor == null || decor.width <= 0 || decor.height <= 0) {
        return AskCaptureOutcome.Rejected(
            AskFrameVerdict.TooSmall(decor?.width ?: 0, decor?.height ?: 0),
            source = null,
        )
    }

    var bestRejection: Pair<AskFrameVerdict, AskCaptureSource>? = null
    for (candidate in captureCandidates(activity, decor)) {
        val raw = runCatching { candidate.capture() }.getOrElse {
            Log.w(ASK_LOG_TAG, "Capture via ${candidate.source.label} threw (#3407).", it)
            null
        } ?: continue

        val offsetX = focusX?.minus(candidate.originX)
        val offsetY = focusY?.minus(candidate.originY)
        val framed = frameForModel(raw, offsetX, offsetY)
        val verdict = inspectFrameForModel(framed)
        if (verdict == AskFrameVerdict.Usable) {
            return AskCaptureOutcome.Captured(AskFrame(framed, candidate.source))
        }
        Log.w(
            ASK_LOG_TAG,
            "Frame from ${candidate.source.label} rejected: $verdict " +
                "(${framed.width}×${framed.height}) — trying the next capture path (#3407).",
        )
        framed.recycle()
        // Keep the most informative rejection: a lost layer / blank frame explains more than
        // a degenerate size, and the first path tried is the most representative one.
        if (bestRejection == null || bestRejection.first is AskFrameVerdict.TooSmall) {
            bestRejection = verdict to candidate.source
        }
    }
    return AskCaptureOutcome.Rejected(
        bestRejection?.first ?: AskFrameVerdict.TooSmall(decor.width, decor.height),
        bestRejection?.second,
    )
}

/** One capture path: where its pixels come from, and where its origin sits in the window. */
private class CaptureCandidate(
    val source: AskCaptureSource,
    val originX: Float,
    val originY: Float,
    val capture: suspend () -> Bitmap?,
)

/** The capture paths to try, best first. */
private fun captureCandidates(activity: Activity, decor: View): List<CaptureCandidate> {
    val candidates = mutableListOf<CaptureCandidate>()
    val arView = decor.findArRenderView()
    if (arView != null && arView.width > 0 && arView.height > 0) {
        val location = IntArray(2).also { arView.getLocationInWindow(it) }
        when (arView) {
            is TextureView -> candidates += CaptureCandidate(
                source = AskCaptureSource.ArTexture,
                originX = location[0].toFloat(),
                originY = location[1].toFloat(),
                capture = { arView.bitmap },
            )

            is SurfaceView -> candidates += CaptureCandidate(
                source = AskCaptureSource.ArSurface,
                originX = location[0].toFloat(),
                originY = location[1].toFloat(),
                capture = { copySurfaceView(arView) },
            )
        }
    }
    candidates += CaptureCandidate(
        source = AskCaptureSource.Window,
        originX = 0f,
        originY = 0f,
        capture = { copyWindow(activity, decor.width, decor.height) },
    )
    return candidates
}

/**
 * Depth-first search for the view the AR scene renders into. `SceneView` attaches Filament to
 * either a `SurfaceView` or a `TextureView` depending on `surfaceType` (QA-backdrop runs pick
 * the latter), and the demo composes exactly one of them, so the first hit is the right one.
 */
internal fun View.findArRenderView(): View? {
    if (this is SurfaceView || this is TextureView) return this
    val group = this as? ViewGroup ?: return null
    for (i in 0 until group.childCount) {
        group.getChildAt(i).findArRenderView()?.let { return it }
    }
    return null
}

/** `PixelCopy` straight off the AR `SurfaceView` — the layer the window read-back can lose. */
private suspend fun copySurfaceView(view: SurfaceView): Bitmap? {
    if (!view.holder.surface.isValid) return null
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    return suspendCancellableCoroutine { continuation ->
        try {
            PixelCopy.request(
                view,
                bitmap,
                { result ->
                    if (result == PixelCopy.SUCCESS) {
                        continuation.resume(bitmap)
                    } else {
                        bitmap.recycle()
                        Log.w(ASK_LOG_TAG, "PixelCopy on the AR surface failed: $result (#3407).")
                        continuation.resume(null)
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        } catch (e: IllegalArgumentException) {
            // Surface torn down between the validity check and the request.
            bitmap.recycle()
            Log.w(ASK_LOG_TAG, "AR surface went away mid-capture (#3407).", e)
            continuation.resume(null)
        }
    }
}

/** The pre-#3407 path, kept as a fallback for devices where the surface read-back is refused. */
private suspend fun copyWindow(activity: Activity, width: Int, height: Int): Bitmap? {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    return suspendCancellableCoroutine { continuation ->
        try {
            PixelCopy.request(
                activity.window,
                bitmap,
                { result ->
                    if (result == PixelCopy.SUCCESS) {
                        continuation.resume(bitmap)
                    } else {
                        bitmap.recycle()
                        Log.w(ASK_LOG_TAG, "PixelCopy on the window failed: $result (#3343).")
                        continuation.resume(null)
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        } catch (e: IllegalArgumentException) {
            bitmap.recycle()
            Log.w(ASK_LOG_TAG, "Window not capturable (#3343).", e)
            continuation.resume(null)
        }
    }
}

/**
 * Crops [capture] to [askCaptureRegion] around ([focusX], [focusY]) and downscales it to the
 * model's budget, recycling the oversized original. Returns [capture] unchanged when it is
 * already within budget, so the caller's ownership contract holds either way.
 *
 * Why this exists rather than trusting ML Kit's own resize: genai-prompt 1.0.0-beta4 rescales
 * only when `min(width, height) > 768`, and only the SHORT edge — a 1080×2424 window capture
 * arrives as 768×1723. See `ASK_IMAGE_MAX_EDGE` (#3343).
 */
internal fun frameForModel(capture: Bitmap, focusX: Float?, focusY: Float?): Bitmap {
    val region = askCaptureRegion(
        sourceWidth = capture.width,
        sourceHeight = capture.height,
        focusX = focusX,
        focusY = focusY,
    )
    val unchanged = region.x == 0 && region.y == 0 &&
        region.width == capture.width && region.height == capture.height &&
        region.scaledWidth == capture.width && region.scaledHeight == capture.height
    if (unchanged) return capture
    return runCatching {
        val cropped = Bitmap.createBitmap(
            capture, region.x, region.y, region.width, region.height,
        )
        val scaled = if (
            cropped.width == region.scaledWidth && cropped.height == region.scaledHeight
        ) {
            cropped
        } else {
            Bitmap.createScaledBitmap(
                cropped, region.scaledWidth, region.scaledHeight, true,
            ).also { if (it !== cropped) cropped.recycle() }
        }
        // `createBitmap`/`createScaledBitmap` may return the source itself when nothing had
        // to change; only recycle the capture when a genuinely new bitmap came back.
        if (scaled !== capture) capture.recycle()
        scaled
    }.getOrElse {
        Log.w(ASK_LOG_TAG, "Could not reframe the capture for the model; sending it whole.", it)
        capture
    }
}

/** Strided sampling stride, both axes. Every 4th pixel is plenty at ≤ 768 px. */
private const val ASK_FRAME_SAMPLE_STEP = 4

/**
 * Reads a strided sample out of [bitmap] and hands it to the pure [inspectAskFrame]. The only
 * Android-flavoured half of the validation, kept to one function on purpose.
 */
internal fun inspectFrameForModel(bitmap: Bitmap): AskFrameVerdict {
    if (bitmap.width < ASK_FRAME_MIN_EDGE || bitmap.height < ASK_FRAME_MIN_EDGE) {
        return AskFrameVerdict.TooSmall(bitmap.width, bitmap.height)
    }
    val row = IntArray(bitmap.width)
    val samples = ArrayList<Int>((bitmap.width / ASK_FRAME_SAMPLE_STEP + 1) *
        (bitmap.height / ASK_FRAME_SAMPLE_STEP + 1))
    var y = 0
    while (y < bitmap.height) {
        bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
        var x = 0
        while (x < row.size) {
            samples += row[x]
            x += ASK_FRAME_SAMPLE_STEP
        }
        y += ASK_FRAME_SAMPLE_STEP
    }
    return inspectAskFrame(bitmap.width, bitmap.height, samples.toIntArray())
}
