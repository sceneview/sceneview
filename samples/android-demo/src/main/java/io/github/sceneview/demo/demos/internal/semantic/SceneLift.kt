package io.github.sceneview.demo.demos.internal.semantic

import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.ReplayLens
import io.github.sceneview.demo.demos.internal.ScanBox
import io.github.sceneview.demo.demos.internal.ScanPick
import io.github.sceneview.demo.demos.internal.Vec3

/**
 * The camera a keyframe was taken with: its [pose] (looking down its own −Z, +Y up) and its lens,
 * as the half-extents of the image plane one metre ahead — [ReplayLens]'s. A fraction of the photo
 * is (0, 0) at its top left and (1, 1) at its bottom right.
 */
class SceneCamera(val pose: DebugPose, val halfWidthPerDepth: Float, val halfHeightPerDepth: Float) {
    constructor(pose: DebugPose, lens: ReplayLens) : this(pose, lens.halfWidthPerDepth, lens.halfHeightPerDepth)

    val eye: Vec3 get() = pose.position

    /** The ray through the photo's fraction ([fx], [fy]), as a unit world direction. */
    fun ray(fx: Float, fy: Float): Vec3 =
        pose.rotate((2 * fx - 1) * halfWidthPerDepth, (1 - 2 * fy) * halfHeightPerDepth, -1f).normalized()

    /** Where [p] falls in the photo, as fractions (unclipped); `null` behind or in the lens's face. */
    fun project(p: Vec3): FloatArray? {
        val inverse = DebugPose(0f, 0f, 0f, -pose.qx, -pose.qy, -pose.qz, pose.qw)
        val local = inverse.rotate(p.x - pose.x, p.y - pose.y, p.z - pose.z)
        if (local.z > -NEAR_M) return null
        val u = local.x / -local.z / halfWidthPerDepth
        val v = local.y / -local.z / halfHeightPerDepth
        return floatArrayOf((u + 1f) / 2f, (1f - v) / 2f)
    }

    /**
     * The rectangle [box] covers in the photo — left, top, right, bottom, clipped to it —, `null`
     * when a corner is behind the camera or the box is out of the picture.
     */
    fun rectOf(box: ScanBox): FloatArray? {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (corner in box.corners()) {
            val (x, y) = project(corner) ?: return null
            l = minOf(l, x)
            t = minOf(t, y)
            r = maxOf(r, x)
            b = maxOf(b, y)
        }
        l = l.coerceIn(0f, 1f)
        t = t.coerceIn(0f, 1f)
        r = r.coerceIn(0f, 1f)
        b = b.coerceIn(0f, 1f)
        return if (r - l <= 0f || b - t <= 0f) null else floatArrayOf(l, t, r, b)
    }

    companion object {
        /** As the replay's own projection: nearer than this, a point is in the lens's face. */
        const val NEAR_M = 0.1f
    }
}

/** A detection of keyframe [view] given its place in the room: the [box] and [surfels] it is. */
class SceneObservation(
    val label: SceneLabel,
    val score: Float,
    val view: Int,
    val box: ScanBox,
    val surfels: IntArray,
    /** How well the box, seen from the keyframe, covers the detection's rectangle. */
    val iou: Float,
)

/**
 * From a 2D detection to the thing in the room: rays are cast through a few points of the
 * detection's rectangle into the scan's cloud, each grows the object it hits — the very picker a
 * tap uses ([io.github.sceneview.demo.demos.internal.ScanObjects.pick]) —, and the object kept is
 * the one whose box, seen back from the keyframe, best covers the rectangle. A ray that lands on
 * the wall behind a chair's legs, or on the table the lamp stands on, grows something else, and its
 * box covers another rectangle: refused.
 */
object SceneLift {
    /** Where the rays go through the rectangle, as fractions of it: the middle first, then around it. */
    val SAMPLES: List<Pair<Float, Float>> = listOf(
        0.5f to 0.5f,
        0.5f to 0.65f,
        0.5f to 0.35f,
        0.3f to 0.5f,
        0.7f to 0.5f,
    )

    /** The least overlap between the box seen back and the detection for the two to be one thing. */
    const val MIN_IOU = 0.25f

    /** Past this overlap, the first box is the answer: no more rays. */
    const val GOOD_IOU = 0.6f

    /**
     * [detection], seen from [camera] in keyframe [view], as the object of the scan it is; `null`
     * when it is no room part or no box grown from it covers it. [pick] is the scan's picker,
     * a ray's origin and unit direction in.
     */
    fun lift(
        detection: Detection2D,
        view: Int,
        camera: SceneCamera,
        pick: (origin: Vec3, direction: Vec3) -> ScanPick,
    ): SceneObservation? {
        val label = detection.label ?: return null
        var best: SceneObservation? = null
        var seen: IntArray? = null
        for ((sx, sy) in SAMPLES) {
            val fx = detection.left + sx * detection.width
            val fy = detection.top + sy * detection.height
            val found = pick(camera.eye, camera.ray(fx, fy)) as? ScanPick.Found ?: continue
            // A second ray into the same object grows the same region: no need to judge it again.
            if (seen != null && found.surfels.contentEquals(seen)) continue
            seen = found.surfels
            val rect = camera.rectOf(found.box) ?: continue
            val iou = detection.iou(rect[0], rect[1], rect[2], rect[3])
            if (iou >= MIN_IOU && iou > (best?.iou ?: 0f)) {
                best = SceneObservation(label, detection.score, view, found.box, found.surfels, iou)
                if (iou >= GOOD_IOU) break
            }
        }
        return best
    }
}
