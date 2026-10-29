package io.github.sceneview.demo.demos.internal.model

import io.github.sceneview.demo.demos.internal.RerunMarchingCubes
import io.github.sceneview.demo.demos.internal.RerunMesh
import io.github.sceneview.demo.demos.internal.RerunMeshSimplifier
import kotlin.math.floor
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The finished model at the size a long scan of a big room can reach: a closed room surface just
 * under the extraction's cap ([RerunMarchingCubes.MAX_TRIANGLES]), every vertex a few millimetres
 * off its plane as a depth sensor leaves it, textured from the ray-cast room's key photos. The
 * build must stay within a ceiling of time (best of [RUNS], a desktop JVM's figure, not a phone's)
 * and of bytes allocated, the figure that tells a heap's churn and grows with what is kept.
 */
class CleanModelWorstCaseTest {
    @Test
    fun `a surface at the extraction's cap is finished within a bounded time and allocation`() {
        val raw = noisyRoom()
        assertTrue("${raw.triangleCount} triangles", raw.triangleCount <= RerunMarchingCubes.MAX_TRIANGLES)
        assertTrue("${raw.triangleCount} triangles", raw.triangleCount > RerunMarchingCubes.MAX_TRIANGLES * NEAR_CAP)
        val shots = TexturedSyntheticRoom.photos()
        val photos = ModelPhotos(TexturedSyntheticRoom.PHOTO_LENS, shots.map { shot -> ModelPhoto(shot.pose) { shot } })

        var bestMs = Long.MAX_VALUE
        var leastBytes = Long.MAX_VALUE
        var last: CleanModelResult? = null
        repeat(RUNS) {
            System.gc()
            val before = allocatedBytes()
            val start = System.nanoTime()
            val result = CleanModel.build(raw, photos, JvmAtlasEncoder)
            bestMs = minOf(bestMs, (System.nanoTime() - start) / NANOS_PER_MS)
            val after = allocatedBytes()
            if (before != null && after != null) leastBytes = minOf(leastBytes, after - before)
            last = result
        }
        val result = checkNotNull(last)
        val stats = result.stats
        val summary = "worst case: ${raw.triangleCount} triangles, ${raw.vertexCount} vertices in; " +
            "best of $RUNS ${bestMs} ms, allocated ${leastBytes / MB} MB " +
            "(simplifier workspace at the cap ${RerunMeshSimplifier.workspaceBytes(raw.vertexCount, raw.triangleCount) / MB} MB); " +
            "$stats"
        println(summary)

        assertEquals(emptyList<String>(), GlbCheck.problems(result.glb))
        assertTrue(summary, stats.cleanInputTriangles <= CleanModel.CLEAN_INPUT_TRIANGLES)
        assertTrue(summary, stats.finalTriangles <= RerunMeshSimplifier.DEFAULT_TARGET_TRIANGLES)
        assertTrue(summary, bestMs < MAX_MS)
        assertTrue(summary, leastBytes < MAX_ALLOCATED_BYTES)
    }

    /**
     * The ray-cast room's shell (4 × 2.6 × 3.6 m) as a closed lattice of ~1.2 cm squares, each
     * split in two, wound to face inwards as a scan's surface faces the camera: every vertex
     * moved by up to [NOISE_M] on each axis, planks on the floor, paint on the walls, a little
     * colour noise everywhere.
     */
    private fun noisyRoom(): RerunMesh {
        val cells = intArrayOf(CELLS_X, CELLS_Y, CELLS_Z)
        val step = floatArrayOf(SIZE_X / CELLS_X, SIZE_Y / CELLS_Y, SIZE_Z / CELLS_Z)
        val origin = floatArrayOf(MIN_X, MIN_Y, MIN_Z)
        val triangles = 4 * (CELLS_X * CELLS_Y + CELLS_X * CELLS_Z + CELLS_Y * CELLS_Z)
        // A closed surface of genus 0: V = F / 2 + 2.
        val vertexCount = triangles / 2 + 2
        val positions = FloatArray(vertexCount * 3)
        val colors = IntArray(vertexCount)
        val indices = IntArray(triangles * 3)
        val ids = HashMap<Long, Int>(vertexCount * 2)
        var vertices = 0
        var corners = 0

        fun vertex(at: IntArray): Int {
            val key = (at[0].toLong() * (CELLS_Y + 1) + at[1]) * (CELLS_Z + 1) + at[2]
            return ids.getOrPut(key) {
                val v = vertices++
                for (axis in 0 until 3) positions[v * 3 + axis] = origin[axis] + at[axis] * step[axis] + noise(key, axis) * NOISE_M
                colors[v] = paint(at, positions[v * 3], key)
                v
            }
        }

        // Each face's lattice, its axes u and v ordered so that u × v points into the room.
        fun face(axis: Int, at: Int, u: Int, v: Int) {
            val p = IntArray(3)
            for (a in 0 until cells[u]) {
                for (b in 0 until cells[v]) {
                    p[axis] = at
                    fun corner(da: Int, db: Int): Int {
                        p[u] = a + da
                        p[v] = b + db
                        return vertex(p)
                    }
                    val p00 = corner(0, 0)
                    val p10 = corner(1, 0)
                    val p11 = corner(1, 1)
                    val p01 = corner(0, 1)
                    for (i in intArrayOf(p00, p10, p11, p00, p11, p01)) indices[corners++] = i
                }
            }
        }
        face(0, 0, 1, 2)
        face(0, CELLS_X, 2, 1)
        face(1, 0, 2, 0)
        face(1, CELLS_Y, 0, 2)
        face(2, 0, 0, 1)
        face(2, CELLS_Z, 1, 0)
        assertEquals(vertexCount, vertices)
        assertEquals(indices.size, corners)
        return RerunMesh(positions, normals(positions, indices), colors, indices)
    }

    private fun paint(at: IntArray, x: Float, key: Long): Int {
        val base = when {
            at[1] == 0 -> if (floor((x - MIN_X) / PLANK_M).toInt() % 2 == 0) PLANK_LIGHT else PLANK_DARK
            at[1] == CELLS_Y -> CEILING
            at[2] == 0 -> SAGE
            else -> WALL
        }
        val jitter = (noise(key, COLOR_AXIS) * COLOR_NOISE).toInt()
        return PhotoTexturing.rgb(
            ((base shr 16 and 0xFF) + jitter).coerceIn(0, 255),
            ((base shr 8 and 0xFF) + jitter).coerceIn(0, 255),
            ((base and 0xFF) + jitter).coerceIn(0, 255),
        )
    }

    private fun normals(positions: FloatArray, indices: IntArray): FloatArray {
        val normals = FloatArray(positions.size)
        for (t in 0 until indices.size / 3) {
            val a = indices[t * 3] * 3
            val b = indices[t * 3 + 1] * 3
            val c = indices[t * 3 + 2] * 3
            val ux = positions[b] - positions[a]
            val uy = positions[b + 1] - positions[a + 1]
            val uz = positions[b + 2] - positions[a + 2]
            val vx = positions[c] - positions[a]
            val vy = positions[c + 1] - positions[a + 1]
            val vz = positions[c + 2] - positions[a + 2]
            val nx = uy * vz - uz * vy
            val ny = uz * vx - ux * vz
            val nz = ux * vy - uy * vx
            for (o in intArrayOf(a, b, c)) {
                normals[o] += nx
                normals[o + 1] += ny
                normals[o + 2] += nz
            }
        }
        for (v in 0 until normals.size / 3) {
            val o = v * 3
            val length = sqrt(normals[o] * normals[o] + normals[o + 1] * normals[o + 1] + normals[o + 2] * normals[o + 2])
            if (length > 0f) for (i in 0 until 3) normals[o + i] /= length
        }
        return normals
    }

    /** A deterministic value in [-1, 1) for ([key], [axis]): splitmix64. */
    private fun noise(key: Long, axis: Int): Float {
        var h = key * GOLDEN + axis
        h = (h xor (h ushr 30)) * MIX_1
        h = (h xor (h ushr 27)) * MIX_2
        h = h xor (h ushr 31)
        return ((h ushr 11).toDouble() / (1L shl 53) * 2.0 - 1.0).toFloat()
    }

    /**
     * Bytes this thread has allocated so far, `null` where the JVM does not count them. Reached
     * by reflection: the unit tests compile against android.jar, which has no `java.lang.management`.
     */
    private fun allocatedBytes(): Long? = runCatching {
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        Class.forName("com.sun.management.ThreadMXBean").getMethod("getCurrentThreadAllocatedBytes").invoke(bean) as Long
    }.getOrNull()

    private companion object {
        const val RUNS = 2
        const val NEAR_CAP = 0.99
        const val NANOS_PER_MS = 1_000_000L
        const val MB = 1024 * 1024

        /** About twice what CI measures (3.7 s and 861 MB allocated, best of two). */
        const val MAX_MS = 10_000L
        const val MAX_ALLOCATED_BYTES = 1_800L * 1024 * 1024

        // 342 × 222 × 308 cells of ~1.17 cm: 998 544 triangles.
        const val CELLS_X = 342
        const val CELLS_Y = 222
        const val CELLS_Z = 308
        const val SIZE_X = 4f
        const val SIZE_Y = 2.6f
        const val SIZE_Z = 3.6f
        const val MIN_X = -2f
        const val MIN_Y = 0f
        const val MIN_Z = -1.8f
        const val NOISE_M = 0.0015f
        const val COLOR_AXIS = 3
        const val COLOR_NOISE = 6f
        const val PLANK_M = 0.18f
        const val PLANK_LIGHT = 0xFF8B6A4A.toInt()
        const val PLANK_DARK = 0xFF7A5C3E.toInt()
        const val CEILING = 0xFFF2F0EA.toInt()
        const val WALL = 0xFFD9D4C8.toInt()
        const val SAGE = 0xFF9CAF88.toInt()
        const val GOLDEN = -0x61C8864680B583EBL
        const val MIX_1 = -0x40A7B892E31B1A47L
        const val MIX_2 = -0x6B2FB644ECCEEE15L
    }
}
