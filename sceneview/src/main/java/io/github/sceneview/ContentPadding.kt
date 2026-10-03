package io.github.sceneview

import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Mat4
import io.github.sceneview.math.Transform

/**
 * The part of a viewport that something else covers — a bottom sheet, a side panel, a title bar —
 * measured in **pixels** inwards from each edge of the surface.
 *
 * It is the pixel form of the `contentPadding` parameter of [io.github.sceneview.SceneView], and
 * what [io.github.sceneview.node.CameraNode.contentPadding] stores. What is left once the four
 * edges are removed is the *visible area*: see [paddedViewport] for what the camera does with it.
 *
 * Negative and non-finite values are read as `0`.
 *
 * @property left   Pixels covered from the left edge.
 * @property top    Pixels covered from the top edge.
 * @property right  Pixels covered from the right edge.
 * @property bottom Pixels covered from the bottom edge.
 */
data class ViewportPadding(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f
) {
    /** `true` when no edge is covered — the visible area is the whole viewport. */
    val isZero: Boolean
        get() = sanitize(left) == 0f && sanitize(top) == 0f &&
            sanitize(right) == 0f && sanitize(bottom) == 0f

    companion object {
        /** No padding: the visible area is the whole viewport. */
        val Zero = ViewportPadding()
    }
}

/**
 * How a camera projects into the visible area of a padded viewport — the output of
 * [paddedViewport].
 *
 * The values are the ones Filament takes: [aspect] goes to `Camera.setLensProjection` /
 * `setProjection`, [scaleX] / [scaleY] to `Camera.setScaling`, [shiftX] / [shiftY] to
 * `Camera.setShift`.
 *
 * @property aspect Width / height of the visible area — the aspect ratio the projection is built
 *                  for.
 * @property scaleX Visible width / viewport width, in `(0, 1]`.
 * @property scaleY Visible height / viewport height, in `(0, 1]`.
 * @property shiftX Horizontal offset of the visible area's centre from the viewport's centre, as
 *                  a fraction of the viewport **width** (Filament's shift unit: `1` is one whole
 *                  viewport, i.e. two NDC units). Positive is right.
 * @property shiftY Vertical offset of the visible area's centre, as a fraction of the viewport
 *                  **height**. Positive is **up**.
 */
data class PaddedViewport(
    val aspect: Double,
    val scaleX: Double,
    val scaleY: Double,
    val shiftX: Double,
    val shiftY: Double
) {
    /** `true` when the visible area is the whole viewport: nothing to scale, nothing to shift. */
    val isIdentity: Boolean
        get() = scaleX == 1.0 && scaleY == 1.0 && shiftX == 0.0 && shiftY == 0.0

    /**
     * The matrix that maps the visible area's clip space onto the viewport's:
     * `renderedProjection = postProjectionTransform * projection`.
     */
    val postProjectionTransform: Transform
        get() = postProjectionTransform(scaleX, scaleY, shiftX, shiftY)
}

/**
 * The contract of `contentPadding`: **the visible area is the camera's viewport.**
 *
 * Given a viewport of [width] × [height] pixels and the [padding] covered on each edge, returns the
 * projection parameters that make the remaining rectangle behave exactly like a surface of that
 * size and position would, while the scene keeps drawing on the whole surface — under the panel
 * too:
 *
 * - the optical centre (where the camera's forward axis lands) is the centre of the visible area;
 * - the field of view the lens was configured for spans the visible area, so the projection's
 *   aspect ratio is the visible area's and a subject is scaled by `visibleHeight / height`;
 * - pixels stay square: [PaddedViewport.scaleX] and [PaddedViewport.scaleY] differ only by the
 *   change of aspect ratio.
 *
 * Nothing here moves the camera: its pose, and an orbit the user has dragged it to, are untouched.
 * The function is linear in [padding], so a padding animated on the curve a panel slides on moves
 * the subject on that same curve.
 *
 * The visible area never collapses: when the padding of two opposite edges would leave less than
 * one pixel, both are reduced in proportion until one pixel is left.
 *
 * A viewport with no pixels (a surface that has not been sized yet) yields the identity with an
 * aspect of `1`.
 */
fun paddedViewport(width: Int, height: Int, padding: ViewportPadding): PaddedViewport {
    if (width <= 0 || height <= 0) return PaddedViewport(1.0, 1.0, 1.0, 0.0, 0.0)
    val w = width.toDouble()
    val h = height.toDouble()
    val (left, right) = fitOpposite(sanitize(padding.left), sanitize(padding.right), w)
    val (top, bottom) = fitOpposite(sanitize(padding.top), sanitize(padding.bottom), h)
    val visibleWidth = w - left - right
    val visibleHeight = h - top - bottom
    return PaddedViewport(
        aspect = visibleWidth / visibleHeight,
        scaleX = visibleWidth / w,
        scaleY = visibleHeight / h,
        // Centre of the visible area minus centre of the viewport is `(left - right) / 2` pixels,
        // and Filament's shift unit is one whole viewport.
        shiftX = (left - right) / (2.0 * w),
        // Screen Y points down, clip-space Y points up.
        shiftY = (bottom - top) / (2.0 * h)
    )
}

/**
 * The matrix Filament multiplies a projection by when a camera carries a scaling and a shift
 * (`Camera.setScaling` / `Camera.setShift`): `renderedProjection = this * projection`.
 *
 * Filament's own `Camera.getProjectionMatrix()` / `getCullingProjectionMatrix()` return the
 * projection **without** it, so anything that converts between the screen and the world has to
 * apply it itself.
 *
 * @param shiftX Horizontal shift in Filament's unit: `1` is one viewport width (two NDC units).
 * @param shiftY Vertical shift, `1` being one viewport height, positive up.
 */
fun postProjectionTransform(
    scaleX: Double,
    scaleY: Double,
    shiftX: Double,
    shiftY: Double
): Transform = Mat4(
    x = Float4(scaleX.toFloat(), 0f, 0f, 0f),
    y = Float4(0f, scaleY.toFloat(), 0f, 0f),
    z = Float4(0f, 0f, 1f, 0f),
    w = Float4((2.0 * shiftX).toFloat(), (2.0 * shiftY).toFloat(), 0f, 1f)
)

private fun sanitize(value: Float): Float = if (value.isFinite() && value > 0f) value else 0f

/** Shrinks two opposite paddings in proportion so at least [MIN_VISIBLE_PIXELS] stay visible. */
private fun fitOpposite(near: Float, far: Float, size: Double): Pair<Double, Double> {
    val total = near.toDouble() + far.toDouble()
    val limit = (size - MIN_VISIBLE_PIXELS).coerceAtLeast(0.0)
    if (total <= limit || total <= 0.0) return near.toDouble() to far.toDouble()
    val factor = limit / total
    return near * factor to far * factor
}

private const val MIN_VISIBLE_PIXELS = 1.0
