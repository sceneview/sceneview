package io.github.sceneview.demo.demos.internal

/**
 * A scan seen from above, for the thumbnail of a scan kept without photos (#4680): its points and
 * the path walked, flattened onto the floor (x, z) and fitted, one scale for both axes, into the
 * unit square. [points] and [path] are `(u, v)` pairs in `0..1`, `v` growing toward the viewer.
 * Pure Kotlin, tested on the JVM.
 */
class ScanFootprint(val points: FloatArray, val path: FloatArray) {
    val pointCount: Int get() = points.size / 2

    companion object {
        /** Most points drawn: a thumbnail is a few dozen dp wide, more would only cost time. */
        const val MAX_POINTS = 1500

        /** Room left on every side, as a share of the square. */
        private const val MARGIN = 0.1f

        /**
         * [capture] from above: the dense cloud when it has one, ARCore's map points otherwise,
         * and the camera's path. `null` when it holds fewer than [MIN_POINTS] points.
         */
        fun of(capture: OpenedCapture, maxPoints: Int = MAX_POINTS): ScanFootprint? {
            val dense = capture.manifest.dense?.let { capture.bytesOf(it.path) }?.let { SvpcCodec.decode(it) }
            val last = capture.trace.frameAt(capture.trace.duration)
            val xyz = dense?.positions?.takeIf { it.size >= MIN_POINTS * 3 } ?: last.mapPoints
            return of(xyz, last.trail, maxPoints)
        }

        /** [xyz] points and [trail] (both `x, y, z` triples) from above; `null` under [MIN_POINTS]. */
        @Suppress("ReturnCount")
        fun of(xyz: FloatArray, trail: FloatArray, maxPoints: Int = MAX_POINTS): ScanFootprint? {
            val count = xyz.size / 3
            if (count < MIN_POINTS) return null
            val step = maxOf(1, (count + maxPoints - 1) / maxPoints)
            val xs = ArrayList<Float>()
            val zs = ArrayList<Float>()
            for (i in 0 until count step step) {
                val x = xyz[i * 3]
                val z = xyz[i * 3 + 2]
                if (x.isFinite() && z.isFinite()) {
                    xs += x
                    zs += z
                }
            }
            if (xs.size < MIN_POINTS) return null
            xs.sort()
            zs.sort()
            // A few stray points far from the rest (a reflection, a window) must not shrink the
            // room to a corner of the square: the bounds leave the outermost [OUTLIERS] out.
            val cut = (xs.size * OUTLIERS).toInt()
            var minX = xs[cut]
            var maxX = xs[xs.size - 1 - cut]
            var minZ = zs[cut]
            var maxZ = zs[zs.size - 1 - cut]
            for (i in 0 until trail.size / 3) {
                val x = trail[i * 3]
                val z = trail[i * 3 + 2]
                if (!x.isFinite() || !z.isFinite()) continue
                minX = minOf(minX, x)
                maxX = maxOf(maxX, x)
                minZ = minOf(minZ, z)
                maxZ = maxOf(maxZ, z)
            }
            val span = maxOf(maxX - minX, maxZ - minZ, MIN_SPAN_M)
            val scale = (1f - 2 * MARGIN) / span
            // Centred: the shorter axis gets the slack on both sides.
            val offsetU = (1f - (maxX - minX) * scale) / 2
            val offsetV = (1f - (maxZ - minZ) * scale) / 2
            fun project(source: FloatArray, stride: Int): FloatArray {
                val n = source.size / 3
                val out = FloatArray(((n + stride - 1) / stride) * 2)
                var w = 0
                for (i in 0 until n step stride) {
                    val x = source[i * 3]
                    val z = source[i * 3 + 2]
                    val u = offsetU + (x - minX) * scale
                    val v = offsetV + (z - minZ) * scale
                    // An outlier past the margin is left out, not drawn on the edge; NaN never
                    // falls in range.
                    if (u in 0f..1f && v in 0f..1f) {
                        out[w++] = u
                        out[w++] = v
                    }
                }
                return out.copyOf(w)
            }
            val points = project(xyz, step)
            if (points.size / 2 < MIN_POINTS) return null
            return ScanFootprint(points, project(trail, 1))
        }

        /** Share of the points left out of the bounds at each end of each axis. */
        private const val OUTLIERS = 0.03f

        /** Fewer points than this draw nothing anyone would read as a room. */
        const val MIN_POINTS = 16

        /** A scan smaller than this (metres) is drawn at this size, not blown up. */
        private const val MIN_SPAN_M = 0.5f
    }
}
