package io.github.sceneview.demo.demos.internal

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * A room ray-cast into the depth frames ARCore would give: the Rerun model's pipeline, TSDF
 * fusion and marching cubes, run end to end where no camera sees a room — the JVM tests, and the
 * emulator's QA state. The frames have the phone's depth image size and lens (160 × 90, the
 * texture's focal length scaled down), full confidence and the surfaces' own colours.
 */

/** A surface the synthetic camera sees: where a ray first meets it, and its colour and normal there. */
sealed interface SyntheticShape {
    /** Distance along the unit ray ([ox], [oy], [oz]) + t·([dx], [dy], [dz]) to the first hit, [Float.POSITIVE_INFINITY] for none. */
    fun hit(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float): Float

    /** The colour at the surface point ([x], [y], [z]), `0xFFRRGGBB`. */
    fun color(x: Float, y: Float, z: Float): Int

    /** An axis-aligned box seen from outside. */
    class Box(
        val min: Vec3,
        val max: Vec3,
        private val paint: (x: Float, y: Float, z: Float) -> Int,
    ) : SyntheticShape {
        override fun hit(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float): Float {
            // Slabs, one axis at a time, without allocating: this runs for every pixel of every frame.
            val missed = outside(ox, dx, min.x, max.x) ||
                outside(oy, dy, min.y, max.y) ||
                outside(oz, dz, min.z, max.z)
            if (missed) return Float.POSITIVE_INFINITY
            val near = maxOf(
                entry(ox, dx, min.x, max.x),
                entry(oy, dy, min.y, max.y),
                entry(oz, dz, min.z, max.z),
            )
            val far = minOf(exit(ox, dx, min.x, max.x), exit(oy, dy, min.y, max.y), exit(oz, dz, min.z, max.z))
            return if (near > 0f && near <= far) near else Float.POSITIVE_INFINITY
        }

        /** Parallel to this axis's slab and outside it: never inside the box. */
        private fun outside(o: Float, d: Float, lo: Float, hi: Float) = abs(d) < PARALLEL && (o < lo || o > hi)

        private fun entry(o: Float, d: Float, lo: Float, hi: Float): Float =
            if (abs(d) < PARALLEL) Float.NEGATIVE_INFINITY else minOf((lo - o) / d, (hi - o) / d)

        private fun exit(o: Float, d: Float, lo: Float, hi: Float): Float =
            if (abs(d) < PARALLEL) Float.POSITIVE_INFINITY else maxOf((lo - o) / d, (hi - o) / d)

        override fun color(x: Float, y: Float, z: Float) = paint(x, y, z)
    }

    /** The inside of an axis-aligned box: a room's floor, walls and ceiling. */
    class Room(
        val min: Vec3,
        val max: Vec3,
        private val paint: (x: Float, y: Float, z: Float) -> Int,
    ) : SyntheticShape {
        override fun hit(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float): Float {
            var t = Float.POSITIVE_INFINITY
            if (dx > PARALLEL) t = minOf(t, (max.x - ox) / dx) else if (dx < -PARALLEL) t = minOf(t, (min.x - ox) / dx)
            if (dy > PARALLEL) t = minOf(t, (max.y - oy) / dy) else if (dy < -PARALLEL) t = minOf(t, (min.y - oy) / dy)
            if (dz > PARALLEL) t = minOf(t, (max.z - oz) / dz) else if (dz < -PARALLEL) t = minOf(t, (min.z - oz) / dz)
            return if (t > 0f) t else Float.POSITIVE_INFINITY
        }

        override fun color(x: Float, y: Float, z: Float) = paint(x, y, z)
    }

    /** A sphere seen from outside. */
    class Sphere(val center: Vec3, val radius: Float, private val rgb: Int) : SyntheticShape {
        override fun hit(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float): Float {
            val lx = ox - center.x
            val ly = oy - center.y
            val lz = oz - center.z
            val b = lx * dx + ly * dy + lz * dz
            val c = lx * lx + ly * ly + lz * lz - radius * radius
            val disc = b * b - c
            if (disc < 0f) return Float.POSITIVE_INFINITY
            val t = -b - sqrt(disc)
            return if (t > 0f) t else Float.POSITIVE_INFINITY
        }

        override fun color(x: Float, y: Float, z: Float) = rgb
    }

    private companion object {
        const val PARALLEL = 1e-7f
    }
}

/** A set of [SyntheticShape]s a synthetic depth camera renders. */
class SyntheticScene(val shapes: List<SyntheticShape>) {
    /**
     * The depth frame a camera at [pose] would see: [width] × [height] millimetres of depth
     * along the view axis (ARCore's DEPTH16 is z-depth, not ray length), full confidence, the
     * hit surface's colour, and the lens ([fx], [fy], [cx], [cy]) at that resolution.
     */
    @Suppress("LongParameterList")
    fun render(
        pose: DebugPose,
        width: Int = RerunSyntheticRoom.DEPTH_WIDTH,
        height: Int = RerunSyntheticRoom.DEPTH_HEIGHT,
        fx: Float = RerunSyntheticRoom.FOCAL,
        fy: Float = RerunSyntheticRoom.FOCAL,
        cx: Float = width / 2f,
        cy: Float = height / 2f,
    ): DepthFrame {
        val depth = ShortArray(width * height)
        val colors = IntArray(width * height)
        for (py in 0 until height) {
            for (px in 0 until width) {
                // Camera-space ray through the pixel at unit depth: DepthBackProjection's mapping.
                val lx = (px - cx) / fx
                val ly = -(py - cy) / fy
                val length = sqrt(lx * lx + ly * ly + 1f)
                val d = pose.rotate(lx / length, ly / length, -1f / length)
                var best = Float.POSITIVE_INFINITY
                var shape: SyntheticShape? = null
                for (s in shapes) {
                    val t = s.hit(pose.x, pose.y, pose.z, d.x, d.y, d.z)
                    if (t < best) {
                        best = t
                        shape = s
                    }
                }
                val z = best / length // ray length → depth along the view axis
                val mm = (z * MM_PER_M + HALF).toInt()
                if (shape == null || mm <= 0 || mm > Short.MAX_VALUE) continue
                depth[py * width + px] = mm.toShort()
                colors[py * width + px] = shape.color(pose.x + d.x * best, pose.y + d.y * best, pose.z + d.z * best)
            }
        }
        return DepthFrame(width, height, depth, null, colors, fx, fy, cx, cy, pose)
    }

    private companion object {
        const val MM_PER_M = 1000f
        const val HALF = 0.5f
    }
}

/**
 * A furnished room, 4 × 3.6 m and 2.6 m high: a planked floor with a rug, three pale walls and a
 * sage one, a sofa, a table on four legs, a cabinet and a ball. [frames] walks a phone around it
 * the way a scan does: three stops, a full turn at each, looking down, level and up.
 */
object RerunSyntheticRoom {
    const val DEPTH_WIDTH = 160
    const val DEPTH_HEIGHT = 90

    /** A Pixel's texture focal length (~500 px at 640 × 360), at the depth image's size. */
    const val FOCAL = 125f

    val scene: SyntheticScene by lazy { SyntheticScene(shapes()) }

    /** The scan's depth frames, in the order a phone would take them. */
    fun frames(): Sequence<DepthFrame> = poses().asSequence().map { scene.render(it) }

    /** Camera poses: every [YAW_STEPS]th of a turn, at [PITCHES_DEG], from [STOPS]. */
    fun poses(): List<DebugPose> = buildList {
        for (stop in STOPS) {
            for (pitchDeg in PITCHES_DEG) {
                for (step in 0 until YAW_STEPS) {
                    val yaw = Math.toRadians(step * FULL_TURN_DEG / YAW_STEPS).toFloat()
                    add(lookPose(stop, yaw, Math.toRadians(pitchDeg.toDouble()).toFloat()))
                }
            }
        }
    }

    /** A camera at [at] turned [yaw] about +Y, then tilted [pitch] about its own +X. */
    fun lookPose(at: Vec3, yaw: Float, pitch: Float): DebugPose {
        val sy = sin(yaw / 2)
        val cy = cos(yaw / 2)
        val sp = sin(pitch / 2)
        val cp = cos(pitch / 2)
        return DebugPose(at.x, at.y, at.z, qx = cy * sp, qy = sy * cp, qz = -sy * sp, qw = cy * cp)
    }

    private fun shapes(): List<SyntheticShape> {
        val room = SyntheticShape.Room(Vec3(-2f, 0f, -1.8f), Vec3(2f, 2.6f, 1.8f)) { x, y, z ->
            when {
                y < SURFACE -> floor(x, z)
                y > 2.6f - SURFACE -> CEILING
                z < -1.8f + SURFACE -> SAGE
                else -> WALL
            }
        }
        val legs = listOf(0.33f to -0.27f, 1.27f to -0.27f, 0.33f to 0.37f, 1.27f to 0.37f).map { (x, z) ->
            SyntheticShape.Box(Vec3(x - LEG, 0f, z - LEG), Vec3(x + LEG, 0.7f, z + LEG)) { _, _, _ -> WOOD_DARK }
        }
        return listOf(
            room,
            // The sofa: seat and back.
            SyntheticShape.Box(Vec3(-1.7f, 0f, -1.8f), Vec3(-0.3f, 0.45f, -0.95f)) { _, _, _ -> SLATE },
            SyntheticShape.Box(Vec3(-1.7f, 0.45f, -1.8f), Vec3(-0.3f, 0.9f, -1.55f)) { _, _, _ -> SLATE_DARK },
            // The table.
            SyntheticShape.Box(Vec3(0.3f, 0.7f, -0.3f), Vec3(1.3f, 0.76f, 0.4f)) { _, _, _ -> WOOD },
            // The cabinet, against the right wall.
            SyntheticShape.Box(Vec3(1.55f, 0f, 0.6f), Vec3(2f, 1.6f, 1.6f)) { _, y, _ ->
                if ((y * 1000).toInt() / DRAWER_MM % 2 == 0) CABINET else CABINET_DARK
            },
            SyntheticShape.Sphere(Vec3(-1.1f, 0.3f, 1f), 0.3f, BALL),
        ) + legs
    }

    private fun floor(x: Float, z: Float): Int {
        if (x in -1.2f..0.9f && z in -0.9f..1.1f) return if (x in -1.1f..0.8f && z in -0.8f..1.0f) RUG else RUG_EDGE
        val plank = ((x + 2f) / PLANK_M).toInt()
        return if (plank % 2 == 0) OAK else OAK_DARK
    }

    private val STOPS = listOf(Vec3(0f, 1.4f, 0.6f), Vec3(-0.7f, 1.3f, 0.2f), Vec3(0.7f, 1.5f, 1.1f))
    private val PITCHES_DEG = listOf(-40, -12, 18)
    private const val YAW_STEPS = 30
    private const val FULL_TURN_DEG = 360.0
    private const val SURFACE = 1e-3f
    private const val LEG = 0.03f
    private const val PLANK_M = 0.18f
    private const val DRAWER_MM = 400

    // Scene content, not UI: the colours of the things in the room.
    private const val OAK = 0xFFB08457.toInt()
    private const val OAK_DARK = 0xFF9A7049.toInt()
    private const val RUG = 0xFFB5523B.toInt()
    private const val RUG_EDGE = 0xFFE3D3B8.toInt()
    private const val WALL = 0xFFE8E2D6.toInt()
    private const val SAGE = 0xFF8FA58A.toInt()
    private const val CEILING = 0xFFF4F2EE.toInt()
    private const val SLATE = 0xFF4F6D8A.toInt()
    private const val SLATE_DARK = 0xFF3E5670.toInt()
    private const val WOOD = 0xFF7A5234.toInt()
    private const val WOOD_DARK = 0xFF5A3B25.toInt()
    private const val CABINET = 0xFFD9CBB2.toInt()
    private const val CABINET_DARK = 0xFFC7B79C.toInt()
    private const val BALL = 0xFFE39A2D.toInt()
}
