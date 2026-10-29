package io.github.sceneview.demo.demos.internal

/**
 * The object scope's distance cutoff (#4683) applied to ARCore's sparse capture: its feature points
 * and its planes. The preset's [ScanPreset.farM] already keeps the raw depth and the model's TSDF
 * to arm's length; without raw depth that cutoff never ran, and an object's replay held the whole
 * room around it. Set on the scan's [ArDebugTrace], which applies it as it records, so the live
 * view, the saved file and its replay all keep the same points and planes.
 *
 * - A feature point further than [farM] from the phone that saw it is left out — the depth's own
 *   rule, per frame.
 * - A plane is cut to the square of half side [farM] centred under the middle of the phone's path:
 *   circling an object puts that middle on the object, so its floor patch and its table top stay
 *   and the walls across the room go. A plane cut to nothing is taken off.
 *
 * Pure and main-thread like the trace it is set on.
 */
class ScanCutoff(val farM: Float) {
    init {
        require(farM > 0f) { "farM $farM" }
    }

    private var eyeX = 0f
    private var eyeY = 0f
    private var eyeZ = 0f
    private var sumX = 0.0
    private var sumZ = 0.0
    private var poses = 0

    /** Whether a pose has been seen: before one, nothing can be measured and nothing is cut. */
    val following: Boolean get() = poses > 0

    /** The phone stands at [pose]: the points that follow are measured from it, the planes cut round its path. */
    fun follow(pose: DebugPose) {
        if (!pose.x.isFinite() || !pose.y.isFinite() || !pose.z.isFinite()) return
        eyeX = pose.x
        eyeY = pose.y
        eyeZ = pose.z
        sumX += pose.x
        sumZ += pose.z
        poses++
    }

    /** Whether the point at ([x], [y], [z]) is within [farM] of the phone. */
    fun keeps(x: Float, y: Float, z: Float): Boolean {
        if (!following) return true
        val dx = x - eyeX
        val dy = y - eyeY
        val dz = z - eyeZ
        return dx * dx + dy * dy + dz * dz <= farM * farM
    }

    /**
     * [positions] (flat xyz) and their [confidences] and [colors] without the points further than
     * [farM]; the same arrays when every point is kept.
     */
    fun points(positions: FloatArray, confidences: FloatArray?, colors: IntArray?): Kept {
        val count = positions.size / 3
        val keep = BooleanArray(count) { keeps(positions[it * 3], positions[it * 3 + 1], positions[it * 3 + 2]) }
        val kept = keep.count { it }
        if (kept == count) return Kept(positions, confidences, colors)
        val xyz = FloatArray(kept * 3)
        val conf = confidences?.let { FloatArray(kept) }
        val rgb = colors?.let { IntArray(kept) }
        var j = 0
        for (i in 0 until count) {
            if (!keep[i]) continue
            System.arraycopy(positions, i * 3, xyz, j * 3, 3)
            if (conf != null) conf[j] = confidences.getOrElse(i) { 1f }
            if (rgb != null) rgb[j] = colors.getOrElse(i) { 0 }
            j++
        }
        return Kept(xyz, conf, rgb)
    }

    /** Feature points after the cutoff. */
    class Kept(val positions: FloatArray, val confidences: FloatArray?, val colors: IntArray?)

    /** The middle of the phone's path, on the ground: `(x, z)`; `null` before the first pose. */
    fun centre(): Pair<Float, Float>? = if (following) (sumX / poses).toFloat() to (sumZ / poses).toFloat() else null

    /**
     * Plane [polygon] (flat xyz, world) cut to the square of half side [farM] round [centre];
     * empty when nothing of it is left. Unchanged before the first pose.
     */
    fun clip(polygon: FloatArray): FloatArray {
        val (cx, cz) = centre() ?: return polygon
        var poly = polygon
        // Sutherland–Hodgman against the square's four vertical sides: an ARCore polygon is convex,
        // and a convex polygon cut by a half-space stays one.
        poly = clipAxis(poly, axis = 0, limit = cx - farM, keepAbove = true)
        poly = clipAxis(poly, axis = 0, limit = cx + farM, keepAbove = false)
        poly = clipAxis(poly, axis = 2, limit = cz - farM, keepAbove = true)
        poly = clipAxis(poly, axis = 2, limit = cz + farM, keepAbove = false)
        return if (poly.size < MIN_POLYGON_FLOATS) FloatArray(0) else poly
    }

    private fun clipAxis(poly: FloatArray, axis: Int, limit: Float, keepAbove: Boolean): FloatArray {
        val count = poly.size / 3
        if (count == 0) return poly
        fun inside(i: Int) = if (keepAbove) poly[i * 3 + axis] >= limit else poly[i * 3 + axis] <= limit
        if ((0 until count).all(::inside)) return poly
        val out = ArrayList<Float>(poly.size + 6)
        for (i in 0 until count) {
            val j = (i + 1) % count
            val inI = inside(i)
            val inJ = inside(j)
            if (inI) for (k in 0..2) out += poly[i * 3 + k]
            if (inI != inJ) {
                val a = poly[i * 3 + axis]
                val b = poly[j * 3 + axis]
                val t = (limit - a) / (b - a)
                for (k in 0..2) out += poly[i * 3 + k] + t * (poly[j * 3 + k] - poly[i * 3 + k])
            }
        }
        return out.toFloatArray()
    }

    companion object {
        private const val MIN_POLYGON_FLOATS = 9

        /** The cutoff [preset] asks for: `null` for a room, whose depth is kept as far as ARCore gives it. */
        fun of(preset: ScanPreset): ScanCutoff? =
            if (preset.farM < DepthBackProjection.FAR_M) ScanCutoff(preset.farM) else null
    }
}
