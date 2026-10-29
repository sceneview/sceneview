package io.github.sceneview.demo.demos.internal

/**
 * A long synthetic depth scan (#4629): a phone walked through [ROOMS] furnished rooms side by
 * side — [RerunSyntheticRoom], [ROOM_SPACING_M] apart — then back into the first, the way a user
 * scans a flat and returns to the start. Its frames carry a deterministic ±[NOISE] of the depth,
 * as a real sensor's do, so a surface is a few voxels thick rather than one.
 *
 * Where no camera sees a room: the JVM tests that bound the dense map's memory over a whole
 * flat, and the emulator's long-scan QA state.
 */
class ScanWalkthrough(
    /** Where the first room's origin sits in the world, so the walk can be placed in a scene. */
    val origin: Vec3 = Vec3(0f, 0f, 0f),
    /** Steps of a full turn at each stop: 30 is the bundled room's walk, fewer a quicker one. */
    val yawSteps: Int = 30,
    /** Whether the walk ends back in the first room. */
    val revisit: Boolean = true,
) {
    private val roomPoses = RerunSyntheticRoom.poses(yawSteps)

    /** The rooms each frame stands in, in walking order: 0, 1, …, [ROOMS] − 1, then 0 again. */
    val rooms: List<Int> = (0 until ROOMS).toList() + if (revisit) listOf(0) else emptyList()

    /** How many depth frames the whole walk has. */
    val frameCount: Int get() = rooms.size * roomPoses.size

    /** The room frame [index] of the walk stands in. */
    fun roomAt(index: Int): Int = rooms[index / roomPoses.size]

    /** Depth frame [index] of the walk, rendered on demand: about 15 k depth pixels. */
    fun frame(index: Int): DepthFrame {
        val room = roomAt(index)
        val local = roomPoses[index % roomPoses.size]
        val rendered = RerunSyntheticRoom.scene.render(local)
        val depth = rendered.depthMm
        for (i in depth.indices) {
            val mm = depth[i].toInt()
            if (mm == 0) continue
            depth[i] = (mm + mm * noise(index, i)).toInt().coerceIn(1, Short.MAX_VALUE.toInt()).toShort()
        }
        val at = offset(room)
        val pose = local.copy(x = local.x + at.x, y = local.y + at.y, z = local.z + at.z)
        return DepthFrame(
            rendered.width, rendered.height, depth, rendered.confidence, rendered.colors,
            rendered.fx, rendered.fy, rendered.cx, rendered.cy, pose,
        )
    }

    /** Every frame of the walk, in order. */
    fun frames(): Sequence<DepthFrame> = (0 until frameCount).asSequence().map(::frame)

    /** Where room [room]'s origin sits: a row along +X, [ROOM_SPACING_M] apart. */
    fun offset(room: Int): Vec3 = Vec3(origin.x + room * ROOM_SPACING_M, origin.y, origin.z)

    /** Points of [cloud] in each room, by room index: how much of each the scan keeps. */
    fun pointsPerRoom(cloud: DenseCloud): IntArray {
        val counts = IntArray(ROOMS)
        for (i in 0 until cloud.count) {
            val x = cloud.positions[i * 3]
            val z = cloud.positions[i * 3 + 2]
            val room = ((x - origin.x - RerunSyntheticRoom.MIN.x) / ROOM_SPACING_M).toInt()
            if (room !in 0 until ROOMS) continue
            val lx = x - offset(room).x
            val lz = z - origin.z
            val inside = lx >= RerunSyntheticRoom.MIN.x - MARGIN_M && lx <= RerunSyntheticRoom.MAX.x + MARGIN_M &&
                lz >= RerunSyntheticRoom.MIN.z - MARGIN_M && lz <= RerunSyntheticRoom.MAX.z + MARGIN_M
            if (inside) counts[room]++
        }
        return counts
    }

    /** A depth error in [−NOISE, NOISE], the same for the same frame and pixel on every run. */
    private fun noise(frame: Int, pixel: Int): Float {
        var h = frame * HASH_A + pixel * HASH_B
        h = h xor (h ushr HASH_SHIFT)
        h *= HASH_C
        h = h xor (h ushr HASH_SHIFT)
        val unit = (h and HASH_MASK) / HASH_MASK.toFloat() // 0..1
        return (unit * 2f - 1f) * NOISE
    }

    companion object {
        /** Rooms the walk passes through: a flat, not a single room. */
        const val ROOMS = 4

        /** From one room's origin to the next: a 4 m room and a 2 m corridor. */
        const val ROOM_SPACING_M = 6f

        /** A phone's raw depth is good to about one percent of the distance. */
        const val NOISE = 0.01f

        /** Noisy points a little past a wall still count for its room. */
        private const val MARGIN_M = 0.2f

        private const val HASH_A = 0x9E3779B1.toInt()
        private const val HASH_B = 0x85EBCA6B.toInt()
        private const val HASH_C = 0xC2B2AE35.toInt()
        private const val HASH_SHIFT = 15
        private const val HASH_MASK = 0xFFFF
    }
}
