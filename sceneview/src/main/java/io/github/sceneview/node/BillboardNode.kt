package io.github.sceneview.node

import android.graphics.Bitmap
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size

/**
 * A node that always faces the camera (billboard behaviour).
 *
 * A [BillboardNode] is an [ImageNode] (flat quad with a bitmap texture) that rotates toward the
 * camera every frame. Pass a [Bitmap] to display on the quad; call [setBitmap] to update it at
 * any time.
 *
 * Usage inside a [io.github.sceneview.SceneScope]:
 * ```kotlin
 * SceneView(onFrame = { cameraPos = cameraNode.worldPosition }) {
 *     BillboardNode(
 *         materialLoader = materialLoader,
 *         bitmap = myBitmap,
 *         widthMeters = 0.5f,
 *         heightMeters = 0.25f,
 *         cameraPositionProvider = { cameraPos }
 *     )
 * }
 * ```
 *
 * @param materialLoader         MaterialLoader used to create the image material instance.
 * @param bitmap                 The bitmap texture to display on the quad.
 * @param widthMeters            Width of the quad in world-space meters. Pass `null` to derive from
 *                               the bitmap's aspect ratio (longer edge = 1 unit).
 * @param heightMeters           Height of the quad in world-space meters. Pass `null` to derive
 *                               from the bitmap's aspect ratio.
 * @param cameraPositionProvider Lambda invoked every frame to obtain the current camera world
 *                               position. The node rotates to face this position.
 */
open class BillboardNode(
    materialLoader: MaterialLoader,
    bitmap: Bitmap,
    widthMeters: Float? = null,
    heightMeters: Float? = null,
    private val cameraPositionProvider: (() -> Position)? = null
) : ImageNode(
    materialLoader = materialLoader,
    bitmap = bitmap,
    size = if (widthMeters != null && heightMeters != null) Size(widthMeters, heightMeters) else null
) {

    /**
     * The camera position this node's orientation was last built from, or `null` if it has never
     * been oriented. Drives both halves of render-on-demand below.
     */
    private var appliedCameraPosition: Position? = null

    /**
     * True while the camera has moved somewhere this node has not yet turned to face.
     *
     * Two things had to be true at once for a billboard to behave under
     * [io.github.sceneview.FrameRatePolicy.OnDemand], and the naive version got both wrong in
     * opposite directions (#3718).
     *
     * *It must not pin the loop.* The re-orientation used the public [onFrame] slot, which
     * [Node.isFrameActive] reads as a standing request for frames — so any scene holding a
     * `BillboardNode` or a [TextNode] ran at full cadence forever. It now uses
     * [Node.internalOnFrame], which carries no such meaning, and answers for itself here.
     *
     * *It must not lag a frame either.* The orientation is built from a camera position the caller
     * reads in `SceneView(onFrame = …)`, which fires *after* its frame was presented — so on the
     * frame a camera move causes, the provider still reports the previous position. Writing the
     * transform unconditionally papered over that by re-pushing a frame every tick, which is the
     * self-wake this whole mechanism exists to remove. Instead the write is skipped when nothing
     * moved, and this term keeps the loop running for as long as the applied position and the
     * reported one disagree. It converges by construction: applying sets one to the other.
     *
     * The write lands before the picture it belongs to. `Node.onFrame(frameTimeNanos)` runs in
     * `SceneView`'s update block, ahead of the GPU submit and ahead of the `shouldPresent` vote,
     * so the frame that carries a camera move also carries the orientation built for it.
     */
    override val isFrameActive: Boolean
        get() = cameraMovedSinceApplied() || super.isFrameActive

    private fun cameraMovedSinceApplied(): Boolean =
        cameraMovedSinceApplied(cameraPositionProvider?.invoke(), appliedCameraPosition)

    init {
        // Keep the node facing the camera every frame.
        // kotlin-math `lookTowards(direction)` builds Mat4(right, up, -direction, eye), so
        // local +Z maps to -direction in world space. To make local +Z (the plane's front
        // face, with correct UVs) point toward the camera, we pass `worldPosition - camPos`
        // — local +Z then aligns with `camPos - worldPosition`, i.e. faces the camera.
        // `lookAt(camPos)` would do the opposite: -Z toward camera, +Z away → mirrored UVs.
        // `lookTowards` normalizes its argument internally, so no explicit normalize() needed.
        //
        // `internalOnFrame`, not `onFrame`: the public slot belongs to the caller (setting it on a
        // BillboardNode used to silently overwrite this) and it pins the render loop. See
        // `isFrameActive` above.
        internalOnFrame = { _ ->
            cameraPositionProvider?.invoke()?.let { camPos ->
                // Only write when the camera actually moved. `lookTowards` goes through the
                // world-transform setter, which is a push source: writing an identical quaternion
                // every tick would request a frame every tick, forever.
                if (cameraMovedSinceApplied()) {
                    val direction = worldPosition - camPos
                    val lengthSq =
                        direction.x * direction.x +
                            direction.y * direction.y +
                            direction.z * direction.z
                    // `lengthSq > epsilon` rejects both the zero vector AND any NaN component
                    // (NaN comparisons always return false), avoiding `normalize(0,0,0) → NaN`.
                    if (lengthSq > 1e-12f) {
                        lookTowards(lookDirection = direction)
                    }
                    // Recorded even when the direction was degenerate, so a camera sitting exactly
                    // on the node does not spin the loop trying to face itself.
                    appliedCameraPosition = camPos
                }
            }
        }
    }

    /**
     * Convenience setter — updates the displayed bitmap and (optionally) re-sizes the quad.
     */
    fun updateBitmap(
        newBitmap: Bitmap,
        widthMeters: Float? = null,
        heightMeters: Float? = null
    ) {
        bitmap = newBitmap
        if (widthMeters != null && heightMeters != null) {
            updateGeometry(size = Size(widthMeters, heightMeters))
        }
    }

}

/**
 * Squared world-space distance below which a camera counts as not having moved: 1 mm, two orders of
 * magnitude under what re-orienting a quad can show.
 */
private const val CAMERA_EPSILON_SQ = 1e-6f

/**
 * Whether [camPos] is somewhere a billboard oriented for [applied] is not yet facing.
 *
 * The whole of [BillboardNode]'s render-on-demand behaviour turns on this predicate, in both
 * directions, so it is a function rather than a condition buried in a callback:
 * - `false` skips the transform write, and the transform write is a *push* source — writing an
 *   identical quaternion every tick is what used to keep every scene with a billboard in it awake;
 * - `false` also lets `isFrameActive` report idle, and `true` keeps the loop running until the
 *   orientation has caught up with a camera position the caller can only publish one frame late.
 *
 * A `null` camera means no provider was given: the node never re-orients, so it never needs a frame.
 * A `null` [applied] means it has never been oriented, which always needs one.
 */
internal fun cameraMovedSinceApplied(camPos: Position?, applied: Position?): Boolean {
    if (camPos == null) return false
    if (applied == null) return true
    val dx = camPos.x - applied.x
    val dy = camPos.y - applied.y
    val dz = camPos.z - applied.z
    return dx * dx + dy * dy + dz * dz > CAMERA_EPSILON_SQ
}
