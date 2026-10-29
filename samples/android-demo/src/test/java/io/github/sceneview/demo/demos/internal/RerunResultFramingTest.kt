package io.github.sceneview.demo.demos.internal

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.cross
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.demo.demos.RAW_FIRST_TRIANGLES
import io.github.sceneview.demo.demos.RerunModelSource
import io.github.sceneview.demo.demos.buildScanModel
import io.github.sceneview.demo.demos.internal.model.Box
import io.github.sceneview.demo.demos.internal.model.CleanScenes
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * The result opens a room at most of the screen's narrower side ([RerunResultView.NARROW_FILL]),
 * the pieces depth leaves beyond its walls — through a window, in a mirror — or not. A big scan's
 * raw surface still has them when it is shown, and the finished model that replaces it is shown
 * in its framing. Robolectric for the build's `Log` lines.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RerunResultFramingTest {
    @Test
    fun `a room with pieces scattered beyond its walls opens at most of the screen, raw surface and finished model alike`() {
        val tsdf = CleanScenes.fuse(FUSED, rippleM = 0.004f, noiseM = 0.004f) { x, y, z ->
            // The room's inside; far enough out, the free space the stray pieces float in.
            val wall = ROOM.sdf(x, y, z)
            if (wall < STRAY_ZONE_M) -wall else STRAYS.minOf { it.sdf(x, y, z) }
        }
        val shown = ArrayList<RerunScanModel>()
        val model = buildScanModel(RerunModelSource.Live(tsdf, cloud = null), onRaw = { shown += it }) {}
        assertNotNull(model)
        val raw = shown.single()
        assertTrue("${raw.triangles} raw triangles: not a big scan", raw.triangles >= RAW_FIRST_TRIANGLES)
        val seen = listOf("raw surface" to raw, "finished model" to model!!).map { (name, framed) ->
            val room = projected(ROOM.corners(), RerunResultView.home(framed.bounds, FOV_DEGREES, PORTRAIT, CHROME))
            val across = (room[2] - room[0]) / 2f
            println(
                "$name: ${framed.triangles} triangles, framed ${extent(framed.bounds)}, " +
                    "the room across %.0f %% of the screen".format(across * 100f),
            )
            Triple(name, room, across)
        }
        for ((name, room, across) in seen) {
            assertTrue("$name: the room across ${across * 100f} % of the screen", across >= MIN_ACROSS)
            assertTrue("$name: the room past the screen's edge: ${room.toList()}", room.all { it in -1f - SLACK..1f + SLACK })
        }
    }

    private fun Box.corners() = floatArrayOf(x0, y0, z0, x1, y1, z1)

    private fun extent(b: FloatArray) = "%.2f × %.2f × %.2f m".format(b[3] - b[0], b[4] - b[1], b[5] - b[2])

    /** [bounds]' eight corners seen from [pose]: `[left, bottom, right, top]`, -1..1 across the screen. */
    private fun projected(bounds: FloatArray, pose: OrbitPose): FloatArray {
        val eye = CameraRig.eye(pose)
        val forward = normalize(pose.target - eye)
        val right = normalize(cross(forward, Float3(0f, 1f, 0f)))
        val up = cross(right, forward)
        val tanV = tan(Math.toRadians(FOV_DEGREES / 2.0)).toFloat()
        val tanH = tanV * PORTRAIT
        val out = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (corner in 0 until 8) {
            val point = Float3(
                bounds[if (corner and 1 == 0) 0 else 3],
                bounds[if (corner and 2 == 0) 1 else 4],
                bounds[if (corner and 4 == 0) 2 else 5],
            ) - eye
            val depth = dot(point, forward)
            out[0] = min(out[0], dot(point, right) / (depth * tanH))
            out[1] = min(out[1], dot(point, up) / (depth * tanV))
            out[2] = max(out[2], dot(point, right) / (depth * tanH))
            out[3] = max(out[3], dot(point, up) / (depth * tanV))
        }
        return out
    }

    private companion object {
        /** A living room, 4 m by 3.2 m, its ceiling at 2.5 m. */
        val ROOM = Box(0f, 0f, 0f, 4f, 2.5f, 3.2f)

        /** Eight pieces 20 cm across, 1.2 m to 2 m beyond the walls, at every height. */
        val STRAYS = listOf(
            Box(-2.0f, 0.6f, 1.0f, -1.8f, 0.8f, 1.2f),
            Box(-1.4f, 1.8f, 2.6f, -1.2f, 2.0f, 2.8f),
            Box(5.6f, 0.3f, 0.4f, 5.8f, 0.5f, 0.6f),
            Box(5.3f, 1.4f, 2.2f, 5.5f, 1.6f, 2.4f),
            Box(1.0f, 1.0f, -2.0f, 1.2f, 1.2f, -1.8f),
            Box(2.8f, 2.0f, -1.5f, 3.0f, 2.2f, -1.3f),
            Box(0.6f, 0.4f, 4.6f, 0.8f, 0.6f, 4.8f),
            Box(3.2f, 1.6f, 5.1f, 3.4f, 1.8f, 5.3f),
        )

        /** Past this far beyond the walls, the field is the stray pieces' alone. */
        const val STRAY_ZONE_M = 0.5f

        val FUSED = Box(-2.3f, -0.4f, -2.3f, 6.1f, 2.9f, 5.6f)

        const val FOV_DEGREES = 46.4

        /** A phone held upright. */
        const val PORTRAIT = 0.46f

        /** The bar above and the sheet below, at rest. */
        val CHROME = FramingInsets(left = 0.04f, top = 0.14f, right = 0.04f, bottom = 0.22f)

        /** Most of [RerunResultView.NARROW_FILL]: the room, not the pieces around it, sets the size. */
        const val MIN_ACROSS = 0.72f

        const val SLACK = 0.02f
    }
}
