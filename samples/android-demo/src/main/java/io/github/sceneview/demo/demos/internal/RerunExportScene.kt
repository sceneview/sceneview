package io.github.sceneview.demo.demos.internal

import kotlin.math.sqrt

/**
 * A captured space, flattened into what an export needs: the coloured point cloud, the camera's
 * path and photos, the planes (with their photo textures when the capture has them) and the
 * placed models. Every exporter (`.ply`, `.glb`, `.rrd`) reads this one value — the iOS demo's
 * `RerunExportScene`, field for field, so both apps export a capture the same way.
 *
 * Coordinates are the session's world space: metres, Y up, right-handed. Entity names follow the
 * Rerun bridge's wire format (`world/camera`, `world/points`, `world/planes/<id>`,
 * `world/anchors/<id>`).
 */
data class RerunExportScene(
    /** Human-readable name of the capture, used for file names and scene titles. */
    val title: String,
    val lens: Lens?,
    /** Every map point, world space. */
    val points: List<Vec3>,
    /** One sRGB colour per point, same count as [points] (or empty). */
    val pointColors: List<Rgb>,
    /** The camera's path, oldest first. */
    val cameraPath: List<CameraSample>,
    val keyframes: List<Keyframe>,
    /** Encoded photos by path (`frames/012.webp`): the keyframes' images, and every one of [photos]. */
    val images: Map<String, ByteArray>,
    val planes: List<Plane>,
    val anchors: List<Anchor>,
    /**
     * Every photo the session recorded, oldest first, not only the keyframes' — what a replay's
     * camera view plays back. Empty when the source has none beyond [keyframes].
     */
    val photos: List<Keyframe> = emptyList(),
    /** What the camera saw over time: which of [points] each point-cloud observation held. */
    val pointObservations: List<PointObservation> = emptyList(),
    /**
     * A `.svscan` v2's dense cloud (Rerun v2, tiers `lidar` / `depth`): surfels of [denseVoxelM]
     * with colours and unit normals, world space. `null` for a v1 or sparse-tier capture — which
     * then exports exactly as before.
     */
    val dense: DenseCloud? = null,
    val denseVoxelM: Float = DenseFusion.VOXEL_M,
) {
    /** One point-cloud observation at [time] (seconds): [points] are indices into [RerunExportScene.points]. */
    data class PointObservation(val time: Double, val points: List<Int>)

    /** A pinhole lens, in pixels of the recorded frames (portrait: `width < height`). */
    data class Lens(val width: Int, val height: Int, val fx: Float, val fy: Float, val cx: Float, val cy: Float)

    /** A rotation as a quaternion, `(x, y, z)` imaginary and `w` real — glTF's and Rerun's order. */
    data class Quat(val x: Float, val y: Float, val z: Float, val w: Float) {
        val length: Float get() = sqrt(x * x + y * y + z * z + w * w)

        /** Rotates [v] by this (unit) quaternion. */
        fun act(v: Vec3): Vec3 = DebugPose(0f, 0f, 0f, x, y, z, w).rotate(v.x, v.y, v.z)

        fun isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite() && w.isFinite()

        companion object {
            val Identity = Quat(0f, 0f, 0f, 1f)
        }
    }

    /** An sRGB colour, each channel 0..255. */
    data class Rgb(val r: Int, val g: Int, val b: Int) {
        companion object {
            val White = Rgb(255, 255, 255)
        }
    }

    /**
     * One camera pose on the timeline. Cameras look down their local -Z, local +Y is the photo's
     * up. [time] is seconds since the first event of the session.
     */
    data class CameraSample(val time: Double, val position: Vec3, val orientation: Quat)

    /** One recorded photo on the timeline: [imagePath] keys [images]; [pose] is where it was taken. */
    data class Keyframe(val time: Double, val imagePath: String, val pose: CameraSample)

    /**
     * A plane's photo, laid on it: [origin] is the world position of texel (0, 0), [u] and [v] the
     * texture's two full edges in world space, `v = 0` the image's top row.
     */
    class PlaneTexture(val imageData: ByteArray, val origin: Vec3, val u: Vec3, val v: Vec3) {
        override fun equals(other: Any?): Boolean = other is PlaneTexture && imageData.contentEquals(other.imageData) &&
            origin == other.origin && u == other.u && v == other.v

        override fun hashCode(): Int = listOf(imageData.contentHashCode(), origin, u, v).hashCode()
    }

    /**
     * A plane: [kind] is the wire format's (`horizontal_upward`, `horizontal_downward`,
     * `vertical`, `unknown`); [polygon] its convex boundary in world space, in order.
     */
    data class Plane(val id: Int, val kind: String, val polygon: List<Vec3>, val texture: PlaneTexture?)

    /** A placed anchor; [modelName] is the bundled model placed on it (`"shiba"`), `null` for none. */
    data class Anchor(val id: Int, val position: Vec3, val orientation: Quat, val modelName: String?)
}

/** All three coordinates finite. */
internal fun Vec3.isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()
