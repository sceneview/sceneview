package io.github.sceneview.demo.demos.internal

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * The end of a scan's two reconstructions side by side, on the furnished synthetic room walked
 * with a noisy depth sensor: what each meshes, how far it lies from the room, what of the room
 * it misses, the holes it leaves, and how long the end of the scan takes.
 */

/** A scan of the synthetic room: its noisy depth frames as the recorder fuses them. */
internal class BenchScan(val name: String, val poses: List<DebugPose>) {
    /** The frames as the sensor gives them: [ReconstructionBench.noisy], one seed a frame. */
    fun frames(): Sequence<DepthFrame> = poses.asSequence().mapIndexed { i, pose ->
        ReconstructionBench.noisy(RerunSyntheticRoom.scene.render(pose), i)
    }

    /** The TSDF a live scan fuses as it goes. */
    fun liveTsdf(): RerunTsdf = RerunTsdf().also { t -> frames().forEach { t.integrate(it) } }

    /** The surfels the scan keeps ([DenseFusion]), as a reopened scan reads them back. */
    fun cloud(): DenseCloud {
        val fusion = DenseFusion()
        frames().forEach { fusion.add(DepthBackProjection.project(it)) }
        return fusion.cloud(minViews = DenseFusion.MIN_VIEWS)
    }
}

/** One reconstruction's figures. */
internal class BenchRow(
    val method: String,
    val triangles: Int,
    val meanErrorM: Double,
    val p90ErrorM: Double,
    /** Share of vertices further than [ReconstructionBench.OFF_M] from the room. */
    val offShare: Double,
    /** Share of the room the scan saw that no vertex is within [ReconstructionBench.MISS_M] of. */
    val missedShare: Double,
    val boundaryEdges: Int,
    val boundaryM: Double,
    val endMs: Long,
) {
    fun line(): String = String.format(
        Locale.US,
        "| %s | %,d | %.1f | %.1f | %.2f %% | %.1f %% | %,d | %.1f | %,d |",
        method, triangles, meanErrorM * MM, p90ErrorM * MM, offShare * PERCENT, missedShare * PERCENT,
        boundaryEdges, boundaryM, endMs,
    )

    companion object {
        const val HEADER = "| Method | Triangles | Mean error (mm) | p90 error (mm) | Vertices > 5 cm off | " +
            "Seen surface missed | Boundary edges | Boundary length (m) | End of scan (ms) |"
        const val RULE = "|---|---:|---:|---:|---:|---:|---:|---:|---:|"
        private const val MM = 1000.0
        private const val PERCENT = 100.0
    }
}

internal object ReconstructionBench {
    /** A vertex this far from the room is off it. */
    const val OFF_M = 0.05f

    /** A seen point of the room with no vertex this close is missed. */
    const val MISS_M = 0.04f

    /** The full walk: three stops, a turn at each, three pitches — 270 frames. */
    val FULL = BenchScan("full", RerunSyntheticRoom.poses())

    /** A hurried scan: one stop, a turn of 12 steps — 36 frames. */
    val QUICK = BenchScan("quick", RerunSyntheticRoom.poses(yawSteps = 12, stops = RerunSyntheticRoom.STOPS.take(1)))

    /** Depth noise: `σ = 2.5 mm + 5 mm · z²` (z in metres), and one pixel in [DROPOUT] lost. */
    private const val NOISE_BASE_M = 0.0025f
    private const val NOISE_SQUARE = 0.005f
    private const val DROPOUT = 0.1
    private const val SEED = 4630L
    private const val TRUTH_STEP = 5
    private const val TRUTH_CELL_M = 0.02f

    /** [frame] as a phone's depth sensor gives it: Gaussian noise growing with the square of the depth, and dropouts. */
    fun noisy(frame: DepthFrame, index: Int): DepthFrame {
        val random = java.util.Random(SEED + index)
        val depth = frame.depthMm.copyOf()
        for (i in depth.indices) {
            val mm = depth[i].toInt() and 0xFFFF
            if (mm == 0) continue
            if (random.nextDouble() < DROPOUT) {
                depth[i] = 0
                continue
            }
            val z = mm / 1000f
            val sigma = NOISE_BASE_M + NOISE_SQUARE * z * z
            depth[i] = ((z + random.nextGaussian().toFloat() * sigma) * 1000f + 0.5f).toInt().coerceIn(1, Short.MAX_VALUE.toInt()).toShort()
        }
        return DepthFrame(frame.width, frame.height, depth, frame.confidence, frame.colors, frame.fx, frame.fy, frame.cx, frame.cy, frame.pose)
    }

    /** How far ([x], [y], [z]) lies from the nearest surface of the room. */
    fun truthDistance(x: Float, y: Float, z: Float): Float {
        var best = Float.MAX_VALUE
        for (shape in RerunSyntheticRoom.scene.shapes) {
            val d = when (shape) {
                is SyntheticShape.Box -> abs(boxDistance(x, y, z, shape.min, shape.max))
                is SyntheticShape.Room -> abs(boxDistance(x, y, z, shape.min, shape.max))
                is SyntheticShape.Sphere -> abs(sqrt(sq(x - shape.center.x) + sq(y - shape.center.y) + sq(z - shape.center.z)) - shape.radius)
                is SyntheticShape.Rooms -> error("not in the room")
            }
            best = min(best, d)
        }
        return best
    }

    /** The room's surface [scan] saw, noise-free: one point a 2 cm cell. */
    fun seen(scan: BenchScan): FloatArray {
        val cells = HashSet<Long>()
        val out = ArrayList<Float>()
        for (pose in scan.poses) {
            val samples = DepthBackProjection.project(RerunSyntheticRoom.scene.render(pose))
            var i = 0
            while (i < samples.count) {
                val x = samples.positions[i * 3]
                val y = samples.positions[i * 3 + 1]
                val z = samples.positions[i * 3 + 2]
                if (cells.add(key(x, y, z, TRUTH_CELL_M))) {
                    out += x
                    out += y
                    out += z
                }
                i += TRUTH_STEP
            }
        }
        return out.toFloatArray()
    }

    /** [mesh]'s figures against the room, [seen] its surface the scan saw. */
    fun measure(method: String, mesh: RerunMesh, seen: FloatArray, endMs: Long): BenchRow {
        val errors = DoubleArray(mesh.vertexCount) { v ->
            truthDistance(mesh.positions[v * 3], mesh.positions[v * 3 + 1], mesh.positions[v * 3 + 2]).toDouble()
        }
        val sorted = errors.sortedArray()
        val p90 = if (sorted.isEmpty()) 0.0 else sorted[(sorted.size * 9 / 10).coerceAtMost(sorted.size - 1)]
        val (edges, length) = boundary(mesh)
        return BenchRow(
            method = method,
            triangles = mesh.triangleCount,
            meanErrorM = if (errors.isEmpty()) 0.0 else errors.average(),
            p90ErrorM = p90,
            offShare = errors.count { it > OFF_M }.toDouble() / errors.size.coerceAtLeast(1),
            missedShare = missed(mesh, seen),
            boundaryEdges = edges,
            boundaryM = length,
            endMs = endMs,
        )
    }

    /** Edges of a single triangle, and their length: the rims of the holes and of the surface. */
    fun boundary(mesh: RerunMesh): Pair<Int, Double> {
        val uses = HashMap<Long, Int>()
        for (t in 0 until mesh.triangleCount) {
            for (k in 0 until 3) {
                val a = mesh.indices[t * 3 + k]
                val b = mesh.indices[t * 3 + (k + 1) % 3]
                uses.merge(min(a, b).toLong() shl 32 or max(a, b).toLong(), 1, Int::plus)
            }
        }
        var edges = 0
        var length = 0.0
        for ((edge, n) in uses) {
            if (n != 1) continue
            val a = (edge ushr 32).toInt()
            val b = (edge and 0xFFFFFFFFL).toInt()
            edges++
            length += sqrt(
                sq(mesh.positions[a * 3] - mesh.positions[b * 3]) +
                    sq(mesh.positions[a * 3 + 1] - mesh.positions[b * 3 + 1]) +
                    sq(mesh.positions[a * 3 + 2] - mesh.positions[b * 3 + 2]),
            ).toDouble()
        }
        return edges to length
    }

    /** The share of [seen] with no vertex of [mesh] within [MISS_M]. */
    private fun missed(mesh: RerunMesh, seen: FloatArray): Double {
        val grid = HashMap<Long, MutableList<Int>>()
        for (v in 0 until mesh.vertexCount) {
            grid.getOrPut(key(mesh.positions[v * 3], mesh.positions[v * 3 + 1], mesh.positions[v * 3 + 2], MISS_M)) { ArrayList(4) } += v
        }
        val count = seen.size / 3
        var missed = 0
        for (i in 0 until count) {
            val x = seen[i * 3]
            val y = seen[i * 3 + 1]
            val z = seen[i * 3 + 2]
            if (!near(mesh, grid, x, y, z)) missed++
        }
        return missed.toDouble() / count.coerceAtLeast(1)
    }

    private fun near(mesh: RerunMesh, grid: Map<Long, List<Int>>, x: Float, y: Float, z: Float): Boolean {
        val cx = floor(x / MISS_M).toInt()
        val cy = floor(y / MISS_M).toInt()
        val cz = floor(z / MISS_M).toInt()
        for (dz in -1..1) for (dy in -1..1) for (dx in -1..1) {
            val cell = grid[RerunTsdf.blockKey(cx + dx, cy + dy, cz + dz)] ?: continue
            for (v in cell) {
                val d = sq(mesh.positions[v * 3] - x) + sq(mesh.positions[v * 3 + 1] - y) + sq(mesh.positions[v * 3 + 2] - z)
                if (d <= MISS_M * MISS_M) return true
            }
        }
        return false
    }

    private fun key(x: Float, y: Float, z: Float, cell: Float): Long =
        RerunTsdf.blockKey(floor(x / cell).toInt(), floor(y / cell).toInt(), floor(z / cell).toInt())

    /** Signed distance to the box's surface: negative inside. */
    private fun boxDistance(x: Float, y: Float, z: Float, lo: Vec3, hi: Vec3): Float {
        val qx = abs(x - (lo.x + hi.x) / 2) - (hi.x - lo.x) / 2
        val qy = abs(y - (lo.y + hi.y) / 2) - (hi.y - lo.y) / 2
        val qz = abs(z - (lo.z + hi.z) / 2) - (hi.z - lo.z) / 2
        val outside = sqrt(sq(max(qx, 0f)) + sq(max(qy, 0f)) + sq(max(qz, 0f)))
        return outside + min(max(qx, max(qy, qz)), 0f)
    }

    private fun sq(v: Float) = v * v
}
