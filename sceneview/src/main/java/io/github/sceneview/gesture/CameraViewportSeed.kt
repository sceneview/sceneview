package io.github.sceneview.gesture

/**
 * Helpers that keep a [CameraGestureDetector.CameraManipulator]'s viewport in sync with the
 * render surface, **including across runtime manipulator swaps**.
 *
 * ### Why this exists (#2514)
 *
 * Filament's ORBIT-mode `Manipulator` maps a two-finger pan to a world-space camera translation
 * by raycasting the touch pixel through the camera frustum — a computation that divides the
 * screen coordinate by the manipulator's viewport (`width`, `height`). If the manipulator is
 * left at its uninitialised **1×1** viewport, that division explodes the pan delta by roughly
 * three orders of magnitude: a 10 px two-finger drag translates the camera ~50 m instead of
 * ~1 cm, throwing the model clean off-screen on the slightest touch.
 *
 * `SceneView` only pushes the viewport to the manipulator from the surface-resize callback,
 * which fires when the **surface size** changes. But the manipulator can be replaced at runtime
 * without a resize — e.g. an auto-fit viewer that rebuilds its manipulator once the model loads
 * and its framing (orbit radius / target) is known. The new manipulator then never receives a
 * `setViewport` and keeps its 1×1 default. These helpers let `SceneView` cache the last surface
 * size and re-seed a freshly-swapped manipulator with it, so pan math is correct from the very
 * first gesture.
 *
 * The viewport is cached as a single packed [Long] so it can live in an
 * `AtomicLong` alongside the other gesture state in `SceneView` without a dedicated holder type.
 */
internal object CameraViewportSeed {

    /** Sentinel meaning "no surface size known yet". [packViewport] never produces this for a
     *  validly-sized (`>= 1`) surface, so it is an unambiguous "unset" marker. */
    const val UNSET: Long = 0L

    /**
     * Packs a surface [width] and [height] (both expected to be non-negative pixel counts) into a
     * single [Long]: the width in the high 32 bits, the height in the low 32 bits.
     */
    fun packViewport(width: Int, height: Int): Long =
        (width.toLong() shl 32) or (height.toLong() and 0xFFFFFFFFL)

    /** Unpacks the width from a value produced by [packViewport]. */
    fun unpackWidth(packed: Long): Int = (packed ushr 32).toInt()

    /** Unpacks the height from a value produced by [packViewport]. */
    fun unpackHeight(packed: Long): Int = (packed and 0xFFFFFFFFL).toInt()

    /**
     * Applies a previously-[packViewport]ed surface size to [manipulator] via
     * [CameraGestureDetector.CameraManipulator.setViewport]. A `null` manipulator or an [UNSET]
     * [packed] size is a no-op — the manipulator will get its viewport from the next real
     * surface-resize instead.
     *
     * @return `true` if a `setViewport` call was made, `false` if it was skipped.
     */
    fun seed(
        manipulator: CameraGestureDetector.CameraManipulator?,
        packed: Long,
    ): Boolean {
        if (manipulator == null || packed == UNSET) return false
        manipulator.setViewport(unpackWidth(packed), unpackHeight(packed))
        return true
    }
}
