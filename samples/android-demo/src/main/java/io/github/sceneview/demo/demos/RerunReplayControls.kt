package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.DebugGroup
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.ReplayLens

/*
 * The pure rules behind the Room Scan replay's controls (#4597): which layers the sheet
 * lists, and where a world point lands on a recorded camera frame.
 */

/** One layer row of the replay's settings sheet: the group it shows or hides, its name, and what it draws. */
internal data class RerunLayer(val group: DebugGroup, val label: String, val hint: String)

internal object RerunReplayControls {
    /**
     * The layers the replay lists, in the sheet's order. The camera's photos are their own row,
     * apart from the path: a long recording stacks dozens of frusta over the room. Anchors are
     * not listed — a room scan places none, and the row read as noise.
     */
    val layers: List<RerunLayer> = listOf(
        RerunLayer(DebugGroup.Trail, "Camera path", "Where the phone went, stronger is later"),
        RerunLayer(DebugGroup.Cameras, "Camera photos", "A frame every few steps, in its frustum"),
        RerunLayer(DebugGroup.Planes, "Planes", "Floors in blue, walls in lilac"),
        RerunLayer(DebugGroup.Points, "Points", "Everything the camera saw"),
    )

    /**
     * Where the world point ([x], [y], [z]) lands on the upright picture taken from [pose]
     * through [lens], as fractions of its width and height — `(0, 0)` its top-left corner — or
     * `null` when the point is behind the camera, nearer than [NEAR_M], or off the picture.
     * The inverse of the frustum the replay draws a photo in, as `PlaneBake` reads it.
     */
    fun project(pose: DebugPose, lens: ReplayLens, x: Float, y: Float, z: Float): FloatArray? {
        val inverse = DebugPose(0f, 0f, 0f, -pose.qx, -pose.qy, -pose.qz, pose.qw)
        val local = inverse.rotate(x - pose.x, y - pose.y, z - pose.z)
        if (local.z > -NEAR_M) return null
        val u = local.x / -local.z / lens.halfWidthPerDepth
        val v = local.y / -local.z / lens.halfHeightPerDepth
        if (u !in -1f..1f || v !in -1f..1f) return null
        return floatArrayOf((u + 1f) / 2f, (1f - v) / 2f)
    }

    /** Nearer than this, a point is in the lens's face: drawn, it would be a blot. */
    const val NEAR_M = 0.1f
}
