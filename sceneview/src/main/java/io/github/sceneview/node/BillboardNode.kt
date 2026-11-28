package io.github.sceneview.node

import android.graphics.Bitmap
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size

/** Resolves optional world-space dimensions while preserving the bitmap aspect ratio. */
internal fun billboardSize(
    bitmapWidth: Int,
    bitmapHeight: Int,
    widthMeters: Float?,
    heightMeters: Float?
): Size? = when {
    widthMeters != null && heightMeters != null -> Size(widthMeters, heightMeters)
    widthMeters != null -> Size(
        widthMeters,
        widthMeters * bitmapHeight.toFloat() / bitmapWidth.toFloat()
    )
    heightMeters != null -> Size(
        heightMeters * bitmapWidth.toFloat() / bitmapHeight.toFloat(),
        heightMeters
    )
    else -> null
}

/**
 * An [ImageNode] — a flat quad showing a bitmap — that can turn to face the camera.
 *
 * It faces the camera **only** when it has a [cameraPositionProvider]; without one it is a plain
 * image quad that keeps the orientation of its parent. Call [setBitmap] to change the picture at
 * any time.
 *
 * ## What "facing the camera" means
 *
 * A full look-at, the same on [BillboardNode], [TextNode] and [ViewNode]: the front of the quad
 * points at the camera position and its top edge stays toward world `+Y` — it yaws and pitches,
 * and never rolls. While a provider is set the node owns its orientation: a `rotation` or
 * `quaternion` written by hand is overwritten the next time the camera, the node or its parent
 * moves.
 *
 * For an upright sign that only turns about the vertical axis, level the position you report with
 * the node: `cameraPositionProvider = { cameraNode.worldPosition.copy(y = signHeight) }`.
 *
 * Usage inside a [io.github.sceneview.SceneScope]:
 * ```kotlin
 * val cameraNode = rememberCameraNode(engine)
 * SceneView(cameraNode = cameraNode) {
 *     BillboardNode(
 *         bitmap = myBitmap,
 *         widthMeters = 0.5f,
 *         heightMeters = 0.25f,
 *         cameraPositionProvider = { cameraNode.worldPosition }
 *     )
 * }
 * ```
 *
 * @param materialLoader         MaterialLoader used to create the image material instance.
 * @param bitmap                 The bitmap texture to display on the quad.
 * @param widthMeters            Width of the quad in world-space meters. When [heightMeters] is
 *                               `null`, the height is derived from the bitmap's aspect ratio.
 * @param heightMeters           Height of the quad in world-space meters. When [widthMeters] is
 *                               `null`, the width is derived from the bitmap's aspect ratio. When
 *                               both dimensions are `null`, the longer edge is 1 unit.
 * @param cameraPositionProvider Initial value of [BillboardNode.cameraPositionProvider]: the camera
 *                               world position to face, read every frame. `null` (default) leaves
 *                               the orientation alone.
 */
open class BillboardNode(
    materialLoader: MaterialLoader,
    bitmap: Bitmap,
    widthMeters: Float? = null,
    heightMeters: Float? = null,
    cameraPositionProvider: (() -> Position)? = null
) : ImageNode(
    materialLoader = materialLoader,
    bitmap = bitmap,
    size = billboardSize(bitmap.width, bitmap.height, widthMeters, heightMeters)
) {

    private val cameraFacing = CameraFacing(NodeCameraFacingTarget(this), cameraPositionProvider)

    /**
     * Where the camera is, in world space. The node turns to face it; `null` leaves the
     * orientation alone.
     *
     * Read every frame, and settable at any time: clearing it stops the re-orientation and leaves
     * the node as it was last turned, setting it starts again on the next frame. It costs nothing
     * while the camera, the node and its parent hold still — the scene still parks under
     * [io.github.sceneview.FrameRatePolicy.OnDemand].
     *
     * Before #4387 this was fixed at construction.
     */
    var cameraPositionProvider: (() -> Position)?
        get() = cameraFacing.cameraPositionProvider
        set(value) {
            cameraFacing.cameraPositionProvider = value
        }

    init {
        // `internalOnFrame`, not `onFrame`: the public slot belongs to the caller (setting it on a
        // BillboardNode used to silently overwrite this) and it pins the render loop. The shared
        // [CameraFacing] answers for its own activity through the provider below.
        internalOnFrame = { _ -> cameraFacing.onFrame() }
        // Frame-active while the camera is somewhere this node has not yet turned to face (#3718).
        // The camera moves without telling this node, so the scene asks on every tick (#3724).
        addFrameActivityProvider { cameraFacing.isPending }
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
        billboardSize(newBitmap.width, newBitmap.height, widthMeters, heightMeters)?.let {
            updateGeometry(size = it)
        }
    }

}
