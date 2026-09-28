package io.github.sceneview.node

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size

/**
 * Maps a picked point on a [ViewNode]'s quad, expressed in **node-local space**, to a pixel
 * coordinate inside the rendered [View] (#2845).
 *
 * The quad is a [io.github.sceneview.geometries.Plane] lying in the node's local XY plane: it
 * spans [size] units, is centred on [center] and its UVs put `v = 1` (the first pixel row of the
 * view, i.e. its **top**) at `+y`. So the mapping is a plain normalise-then-scale, with the Y axis
 * flipped because view pixels grow downwards while local Y grows upwards.
 *
 * The returned point is **not clamped**: a hit outside the quad yields a coordinate outside
 * `0..widthPx` / `0..heightPx`, which is exactly what Android expects — a view un-presses itself
 * when the pointer moves out of its bounds.
 *
 * @param mirrorX Whether the rendered content is mirrored horizontally on screen — see
 * [shouldMirrorX], which is what the caller should pass here.
 * @return the view pixel coordinate, or `null` when the quad or the view has no usable size (a
 * [ViewNode] whose content has not been measured yet).
 */
internal fun viewTouchPixels(
    localPosition: Position,
    center: Position,
    size: Size,
    widthPx: Int,
    heightPx: Int,
    mirrorX: Boolean = false
): Float2? {
    if (widthPx <= 0 || heightPx <= 0) return null
    if (size.x <= 0.0f || size.y <= 0.0f) return null

    val u = (localPosition.x - center.x) / size.x + 0.5f
    val v = 0.5f - (localPosition.y - center.y) / size.y
    return Float2(if (mirrorX) (1.0f - u) * widthPx else u * widthPx, v * heightPx)
}

/**
 * Whether the quad was picked on its **back** face, from the node-local direction of the picking
 * ray (#3329).
 *
 * A [ViewNode]'s quad lies in the local XY plane and reads correctly when looked at from `+Z`. A
 * ray cast from a camera on that side therefore travels towards `-Z`; one travelling towards `+Z`
 * started behind the quad, so what the user is looking at — and aiming at — is the back face.
 *
 * A ray exactly parallel to the quad (`z == 0`) cannot hit it in any meaningful way; it counts as
 * front-facing so the mapping stays the default one.
 */
internal fun isBackFaceHit(localRayDirection: Float3): Boolean = localRayDirection.z > 0.0f

/**
 * Whether [viewTouchPixels] has to mirror its X axis, i.e. whether the content the user sees is
 * horizontally mirrored relative to the plain front-face mapping.
 *
 * Two independent mirrors, which cancel when both apply:
 *
 * - **[invertFrontFaceWinding]** — that flag sets the material's `uvOffset.x` to `1`, and both
 *   `view_texture_*.mat` then shade `uv.x = uv.x + uvOffset.x * (1 - 2 * uv.x)` — plain `1 - uv.x`.
 *   Pixels move on screen but the quad does not, so the mapping has to mirror with them: without
 *   this, in a `Row { Button("Cancel"); Button("OK") }`, tapping the visible "Cancel" fires "OK".
 * - **Back-face pick** ([isBackFaceHit]) — the material is double-sided and un-mirrors its UVs on
 *   the back face, so a quad orbited past edge-on keeps reading correctly on screen. The mapping
 *   used to stay front-face regardless (the "known limitation" this replaces), so every touch on a
 *   quad turned away from the viewer landed on the horizontally mirrored pixel: on a spinning card
 *   the "Tap me" button simply stopped working for half of every turn (#3329).
 */
internal fun shouldMirrorX(
    invertFrontFaceWinding: Boolean,
    localRayDirection: Float3
): Boolean = invertFrontFaceWinding != isBackFaceHit(localRayDirection)

/**
 * Dispatches picked [MotionEvent]s into an embedded [View] hierarchy, and owns the touch-target
 * state machine that goes with it (#2845).
 *
 * ### Why a state machine is needed
 *
 * A [ViewNode]'s events arrive from the scene's picking ray, not from Android's view dispatch, so
 * the usual guarantees of [View.dispatchTouchEvent] have to be re-established here:
 *
 * - **A stream is a unit.** Once the embedded view consumes an `ACTION_DOWN`, every later event of
 *   that gesture belongs to it — including the ones the ray no longer hits ([onExit]). Otherwise a
 *   press dragged off the quad would never get its `UP` and would stay stuck in its pressed state,
 *   ripple and all.
 * - **A stream ends exactly once.** When the pointer leaves the quad the view receives a single
 *   synthetic `ACTION_CANCEL`; the rest of the gesture is then swallowed (it belongs to no one)
 *   rather than being handed mid-stream to the gesture/camera detectors, which never saw its
 *   `DOWN`.
 *
 * ### Why a drag is handed back to the scene (#4033)
 *
 * A Material `Card`, a `Surface`, a `Button`: nearly every Compose container consumes the `DOWN`
 * it is hit by, clickable or not. Holding the whole stream on that alone meant a one-finger drag
 * that happened to start on a card never orbited the camera, and the scene read as broken.
 *
 * Android solves the same conflict between a `ScrollView` and the button under the finger with
 * `requestDisallowInterceptTouchEvent`: the parent takes the gesture over once it becomes a drag,
 * unless a child that wants the drag (a scrollable list, a slider) has claimed it. This class plays
 * the parent: once the pointer has travelled further than the touch slop from its `DOWN`, or a
 * second pointer comes down, and nothing inside [target] has claimed the gesture
 * ([onContentClaimedGesture]), the embedded view gets an `ACTION_CANCEL` and the rest of the
 * stream is handed back ([handedBack]). Compose claims it for every pointer-input node that
 * consumes movement — `AndroidComposeView` calls `requestDisallowInterceptTouchEvent(true)` when a
 * scroll, a drag or a slider takes the change — so an inner list keeps scrolling exactly as it did.
 * A tap never travels that far and still lands on the button.
 *
 * Extracted from [ViewNode] so this logic can be unit-tested without a Filament engine.
 *
 * @param target The view the events are dispatched into — a [ViewNode]'s `layout`.
 * @param touchSlopPx How far, in scene pixels, a pointer may travel from its `DOWN` before an
 * unclaimed stream becomes a drag the scene takes back.
 */
internal class ViewTouchForwarder(
    private val target: View,
    private val touchSlopPx: Float = ViewConfiguration.get(target.context).scaledTouchSlop.toFloat(),
) {

    /** True while a stream that started on the quad still belongs to [target]. */
    var ownsStream: Boolean = false
        private set

    /** True while [target] has a live (started, not yet ended or cancelled) stream. */
    private var isStreamLive: Boolean = false

    /**
     * True once something inside [target] asked for the current gesture — see
     * [onContentClaimedGesture]. A claimed stream is never handed back.
     */
    private var isClaimedByContent: Boolean = false

    /** Scene-space position of the current stream's `DOWN`, the origin of the touch-slop test. */
    private var downX: Float = 0.0f
    private var downY: Float = 0.0f

    /**
     * Latched when a stream [target] owned is handed back to the scene mid-gesture (#4033), and
     * cleared by [takeHandBack]. The scene's detectors never saw that stream's `DOWN`, so whoever
     * routes the rest of it to them has to replay one first.
     */
    var handedBack: Boolean = false
        private set

    /**
     * Something inside [target] called `requestDisallowInterceptTouchEvent` — a list that started
     * scrolling, a slider being dragged, any Compose pointer input that consumed movement. The
     * gesture is theirs: it will not be handed back to the scene.
     */
    fun onContentClaimedGesture(disallowIntercept: Boolean) {
        if (disallowIntercept && ownsStream) isClaimedByContent = true
    }

    /** Returns [handedBack] and clears it: the caller replays the missed `DOWN` exactly once. */
    fun takeHandBack(): Boolean = handedBack.also { handedBack = false }

    /**
     * Forwards [e], picked at the view pixel ([x], [y]), into [target].
     *
     * @return true when [target] owns the stream and the event must not be routed anywhere else.
     */
    fun onHit(e: MotionEvent, x: Float, y: Float): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            // Defensive: a stream left open (forwarding disabled mid-gesture, node re-added to the
            // scene, …) must not leak its pressed state into the new one.
            cancelLiveStream(e)
            isClaimedByContent = false
            handedBack = false
            downX = e.x
            downY = e.y
            ownsStream = dispatch(e, x, y)
            isStreamLive = ownsStream
            return ownsStream
        }
        if (!ownsStream) return false
        if (isStreamLive) {
            dispatch(e, x, y)
        }
        // After the dispatch: a list inside the card claims the drag on the very MOVE that
        // crosses its own slop, which is the same MOVE that crosses this one.
        if (handBackIfDrag(e)) return false
        endStreamIfTerminal(e)
        return true
    }

    /**
     * Continues a captured stream whose event no longer hits the quad: cancels the embedded view's
     * press on the first such event, then swallows the remainder of the gesture.
     *
     * An `ACTION_DOWN` here means a *new* gesture started off the quad, so it ends the captured
     * stream as an `UP` would. Its caller discards the return value in that case — a fresh gesture
     * belongs to whatever it hits.
     *
     * @return true when [target] still owns the stream.
     */
    fun onExit(e: MotionEvent): Boolean {
        if (!ownsStream) return false
        cancelLiveStream(e)
        // A drag that left the quad is still a drag: the scene takes it, as it would on the quad.
        if (handBackIfDrag(e)) return false
        endStreamIfTerminal(e)
        return true
    }

    /**
     * Hands an unclaimed stream back to the scene once it has become a drag or a multi-touch
     * gesture (#4033): cancels [target]'s press and releases the stream.
     *
     * @return true when the stream was just handed back and [e] belongs to the scene.
     */
    private fun handBackIfDrag(e: MotionEvent): Boolean {
        if (isClaimedByContent) return false
        val isDrag = when (e.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> true
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                val dy = e.y - downY
                dx * dx + dy * dy > touchSlopPx * touchSlopPx
            }
            else -> false
        }
        if (!isDrag) return false
        cancelLiveStream(e)
        ownsStream = false
        handedBack = true
        return true
    }

    /** Sends a synthetic `ACTION_CANCEL` to [target] if it currently has a live stream. */
    private fun cancelLiveStream(e: MotionEvent) {
        if (!isStreamLive) return
        isStreamLive = false
        val cancel = MotionEvent.obtain(e)
        try {
            cancel.action = MotionEvent.ACTION_CANCEL
            target.dispatchTouchEvent(cancel)
        } finally {
            cancel.recycle()
        }
    }

    /**
     * Releases the stream on its last event. `ACTION_DOWN` is terminal too, but only reachable
     * from [onExit]: in [onHit] it starts a stream instead and is handled before this is called.
     */
    private fun endStreamIfTerminal(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_DOWN -> {
                ownsStream = false
                isStreamLive = false
            }
        }
    }

    /**
     * Dispatches a copy of [e] relocated to the view pixel ([x], [y]). The original event belongs
     * to the scene's dispatcher and is left untouched.
     *
     * **Single-pointer by construction.** The caller picks the scene once per event and hands one
     * ([x], [y]) here, so `setLocation` moves the whole event to that pixel: extra pointers keep
     * their screen-space offsets instead of being picked individually, and a batched MOVE's
     * historical samples travel with the event rather than being re-projected one by one. Taps,
     * presses and one-finger scrolling — the documented surface — are exact; a pinch or a
     * two-finger drag inside the content is not. Per-pointer picking would need one ray-cast per
     * pointer per sample, which is a different feature, not a tightening of this one.
     */
    private fun dispatch(e: MotionEvent, x: Float, y: Float): Boolean {
        val copy = MotionEvent.obtain(e)
        return try {
            copy.setLocation(x, y)
            target.dispatchTouchEvent(copy)
        } finally {
            copy.recycle()
        }
    }
}
