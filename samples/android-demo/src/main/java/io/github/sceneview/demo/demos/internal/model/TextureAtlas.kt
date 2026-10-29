package io.github.sceneview.demo.demos.internal.model

import io.github.sceneview.demo.demos.internal.BakePhoto
import io.github.sceneview.demo.demos.internal.DebugPose
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The atlas a labelled mesh is textured from: its charts (faces that touch and share a photo),
 * each chart's footprint in that photo copied with the photo's exposure gains, shelf-packed, and
 * levelled across the seams.
 */
internal class TextureAtlas(
    private val mesh: WorkMesh,
    private val faces: FaceGeometry,
    private val topology: MeshTopology,
    private val views: List<PhotoTexturing.View>,
    private val gains: FloatArray,
    private val label: IntArray,
    /** Called between the levelling's sweeps: where a cancelled build stops. */
    private val tick: () -> Unit = {},
) {
    class Built(
        val chart: IntArray,
        val uv: FloatArray,
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        val charts: Int,
        val texturedArea: Float,
        val seamRaw: Float,
        val seamExposed: Float,
        val seamFinal: Float,
        val texelsPerMetre: Float,
    )

    private val faceCount = faces.count
    private val chart = IntArray(faceCount) { -1 }
    private var charts = 0
    private lateinit var chartView: IntArray

    /** Per corner: where its vertex lands in its chart's photo, pixel edges at whole numbers. */
    private val photoX = FloatArray(faceCount * 3)
    private val photoY = FloatArray(faceCount * 3)

    // Per chart: its footprint in the photo, and its rectangle in the atlas (padding included).
    private lateinit var boxX0: FloatArray
    private lateinit var boxY0: FloatArray
    private lateinit var rectX: IntArray
    private lateinit var rectY: IntArray
    private lateinit var rectW: IntArray
    private lateinit var rectH: IntArray
    private var scale = 1f
    private var width = 0
    private var height = 0
    private lateinit var pixels: IntArray

    fun build(): Built {
        findCharts()
        place()
        pack()
        fill()
        val uv = FloatArray(faceCount * 6)
        for (f in 0 until faceCount) {
            val c = chart[f]
            if (c < 0) continue
            for (k in 0 until 3) {
                uv[(f * 3 + k) * 2] = atlasX(c, photoX[f * 3 + k]) / width
                uv[(f * 3 + k) * 2 + 1] = atlasY(c, photoY[f * 3 + k]) / height
            }
        }
        val seams = Seams()
        val seamRaw = seams.step(inAtlas = false)
        val seamExposed = seams.step(inAtlas = true)
        seams.level()
        val seamFinal = seams.step(inAtlas = true)
        var textured = 0.0
        var texelWeighted = 0.0
        for (f in 0 until faceCount) {
            if (chart[f] < 0) continue
            val area = faces.areas[f]
            textured += area
            val i = f * 6
            val ux = (uv[i + 2] - uv[i]) * width
            val uy = (uv[i + 3] - uv[i + 1]) * height
            val vx = (uv[i + 4] - uv[i]) * width
            val vy = (uv[i + 5] - uv[i + 1]) * height
            val texels = abs(ux * vy - uy * vx) / 2f
            if (area > MIN_AREA) texelWeighted += area * sqrt(texels / area)
        }
        return Built(
            chart = chart,
            uv = uv,
            pixels = pixels,
            width = width,
            height = height,
            charts = charts,
            texturedArea = textured.toFloat(),
            seamRaw = seamRaw,
            seamExposed = seamExposed,
            seamFinal = seamFinal,
            texelsPerMetre = if (textured > 0.0) (texelWeighted / textured).toFloat() else 0f,
        )
    }

    /** Charts: the pieces faces of one label make, edge to edge. */
    private fun findCharts() {
        val views = ArrayList<Int>()
        val stack = IntArray(faceCount)
        for (seed in 0 until faceCount) {
            if (label[seed] < 0 || chart[seed] >= 0) continue
            val id = charts++
            views += label[seed]
            chart[seed] = id
            var top = 0
            stack[top++] = seed
            while (top > 0) {
                val f = stack[--top]
                for (k in 0 until 3) {
                    val g = topology.neighbours[f * 3 + k]
                    if (g < 0 || chart[g] >= 0 || label[g] != label[seed]) continue
                    chart[g] = id
                    stack[top++] = g
                }
            }
        }
        chartView = views.toIntArray()
    }

    /** Each corner's place in its chart's photo, and each chart's footprint there. */
    private fun place() {
        boxX0 = FloatArray(charts) { Float.MAX_VALUE }
        boxY0 = FloatArray(charts) { Float.MAX_VALUE }
        val boxX1 = FloatArray(charts) { -Float.MAX_VALUE }
        val boxY1 = FloatArray(charts) { -Float.MAX_VALUE }
        val p = mesh.positions
        for (f in 0 until faceCount) {
            val c = chart[f]
            if (c < 0) continue
            val camera = views[chartView[c]].camera
            for (k in 0 until 3) {
                val v = mesh.indices[f * 3 + k]
                // Every corner of a labelled face was seen in front of this camera.
                camera.project(p[v * 3], p[v * 3 + 1], p[v * 3 + 2])
                val x = camera.pixelX
                val y = camera.pixelY
                photoX[f * 3 + k] = x
                photoY[f * 3 + k] = y
                boxX0[c] = min(boxX0[c], x)
                boxY0[c] = min(boxY0[c], y)
                boxX1[c] = max(boxX1[c], x)
                boxY1[c] = max(boxY1[c], y)
            }
        }
        var footprint = 0.0
        for (c in 0 until charts) {
            boxX0[c] = floor(boxX0[c])
            boxY0[c] = floor(boxY0[c])
            footprint += (ceil(boxX1[c]) - boxX0[c] + 2 * PAD) * (ceil(boxY1[c]) - boxY0[c] + 2 * PAD).toDouble()
        }
        scale = min(1.0, sqrt(FILL * MAX_SIZE * MAX_SIZE / footprint.coerceAtLeast(1.0))).toFloat()
        rectW = IntArray(charts)
        rectH = IntArray(charts)
        spans = FloatArray(charts * 2) { i ->
            val c = i / 2
            if (i % 2 == 0) ceil(boxX1[c]) - boxX0[c] else ceil(boxY1[c]) - boxY0[c]
        }
    }

    private lateinit var spans: FloatArray

    /** Shelf packing, tallest charts first, into the smallest atlas that holds them; smaller texels if none does. */
    private fun pack() {
        rectX = IntArray(charts)
        rectY = IntArray(charts)
        while (true) {
            for (c in 0 until charts) {
                rectW[c] = max(1, ceil(spans[c * 2] * scale).toInt()) + 2 * PAD
                rectH[c] = max(1, ceil(spans[c * 2 + 1] * scale).toInt()) + 2 * PAD
            }
            val order = (0 until charts).sortedByDescending { rectH[it] }
            var bestArea = Long.MAX_VALUE
            for (w in WIDTHS) {
                val h = shelves(order, w, place = false)
                if (h in 1..MAX_SIZE && w.toLong() * h < bestArea) {
                    bestArea = w.toLong() * h
                    width = w
                    height = h
                }
            }
            if (bestArea != Long.MAX_VALUE) {
                shelves(order, width, place = true)
                return
            }
            scale *= SHRINK
        }
    }

    /** The atlas height shelf packing needs at [width] (a multiple of 16), `-1` when a chart is wider. */
    private fun shelves(order: List<Int>, width: Int, place: Boolean): Int {
        var x = 0
        var y = 0
        var shelf = 0
        for (c in order) {
            if (rectW[c] > width) return -1
            if (x + rectW[c] > width) {
                y += shelf
                x = 0
                shelf = 0
            }
            if (place) {
                rectX[c] = x
                rectY[c] = y
            }
            x += rectW[c]
            shelf = max(shelf, rectH[c])
        }
        val used = y + shelf
        return (used + ROW_ALIGN - 1) / ROW_ALIGN * ROW_ALIGN
    }

    /** Where a photo position of chart [c] lands in the atlas, in texels from its left / top edge. */
    private fun atlasX(c: Int, photoX: Float) = rectX[c] + PAD + (photoX - boxX0[c]) * scale
    private fun atlasY(c: Int, photoY: Float) = rectY[c] + PAD + (photoY - boxY0[c]) * scale

    /** Copies each chart's footprint from its photo, with that photo's gains. */
    private fun fill() {
        pixels = IntArray(width * height) { BACKGROUND }
        for (c in 0 until charts) {
            val view = chartView[c]
            val photo = views[view].photo
            val gr = gains[view * 3]
            val gg = gains[view * 3 + 1]
            val gb = gains[view * 3 + 2]
            for (j in 0 until rectH[c]) {
                val py = boxY0[c] + (j - PAD + HALF) / scale - HALF
                for (i in 0 until rectW[c]) {
                    val px = boxX0[c] + (i - PAD + HALF) / scale - HALF
                    val color = PhotoTexturing.sample(photo, px, py)
                    pixels[(rectY[c] + j) * width + rectX[c] + i] = PhotoTexturing.rgb(
                        ((color shr 16 and 0xFF) * gr + HALF).toInt(),
                        ((color shr 8 and 0xFF) * gg + HALF).toInt(),
                        ((color and 0xFF) * gb + HALF).toInt(),
                    )
                }
            }
        }
    }

    /**
     * The seams: one chart-vertex per (mesh vertex, chart). Where a vertex is in several charts,
     * each chart's colour there is pulled to their mean; the offset that takes spreads over the
     * chart's inside (a harmonic fill: open borders free) and is added to its texels.
     */
    private inner class Seams {
        val vertexOf: IntArray
        val chartOf: IntArray
        val cornerId = IntArray(faceCount * 3) { -1 }

        /** A photo position for each chart-vertex (its first corner's). */
        val atX: FloatArray
        val atY: FloatArray

        init {
            val ids = HashMap<Long, Int>()
            val vertices = ArrayList<Int>()
            val chartList = ArrayList<Int>()
            val xs = ArrayList<Float>()
            val ys = ArrayList<Float>()
            for (f in 0 until faceCount) {
                val c = chart[f]
                if (c < 0) continue
                for (k in 0 until 3) {
                    val v = mesh.indices[f * 3 + k]
                    val key = (v.toLong() shl 32) or c.toLong()
                    cornerId[f * 3 + k] = ids.getOrPut(key) {
                        vertices += v
                        chartList += c
                        xs += photoX[f * 3 + k]
                        ys += photoY[f * 3 + k]
                        vertices.size - 1
                    }
                }
            }
            vertexOf = vertices.toIntArray()
            chartOf = chartList.toIntArray()
            atX = xs.toFloatArray()
            atY = ys.toFloatArray()
        }

        val count = vertexOf.size

        /** Chart-vertices by mesh vertex, for the vertices in two charts or more. */
        val shared: List<IntArray> = run {
            val byVertex = HashMap<Int, MutableList<Int>>()
            for (cv in 0 until count) byVertex.getOrPut(vertexOf[cv]) { ArrayList(2) } += cv
            byVertex.values.filter { it.size > 1 }.map { it.toIntArray() }
        }

        /** The atlas's colour around chart-vertex [cv]: what a viewer shows there. */
        fun atlasColor(cv: Int): FloatArray {
            val c = chartOf[cv]
            return box(atlasPhoto, atlasX(c, atX[cv]) - HALF, atlasY(c, atY[cv]) - HALF, 1f)
        }

        private val atlasPhoto get() = BakePhoto(ORIGIN, width, height, pixels)

        /** Seam edges, as face × 3 + edge: the face's chart differs from the one across, counted once. */
        private val edges: IntArray = run {
            val list = ArrayList<Int>()
            for (f in 0 until faceCount) {
                if (chart[f] < 0) continue
                for (k in 0 until 3) {
                    val g = topology.neighbours[f * 3 + k]
                    if (g > f && chart[g] >= 0 && chart[g] != chart[f]) list += f * 3 + k
                }
            }
            list.toIntArray()
        }

        /**
         * The mean colour step (0–255, channels averaged) across the seams, read on both sides at
         * points along each seam edge — between its vertices, where [level] sets nothing outright:
         * the photos' own colours with [inAtlas] false, the atlas's otherwise.
         */
        fun step(inAtlas: Boolean): Float {
            var sum = 0.0
            var n = 0
            for (e in edges) {
                val f = e / 3
                val next = f * 3 + (e % 3 + 1) % 3
                val g = topology.neighbours[e]
                val ga = cornerOf(g, mesh.indices[e])
                val gb = cornerOf(g, mesh.indices[next])
                for (t in SEAM_POINTS) {
                    val p = colorAlong(chart[f], e, next, t, inAtlas)
                    val q = colorAlong(chart[g], ga, gb, t, inAtlas)
                    sum += (abs(p[0] - q[0]) + abs(p[1] - q[1]) + abs(p[2] - q[2])) / 3.0
                    n++
                }
            }
            return if (n == 0) 0f else (sum / n).toFloat()
        }

        private fun cornerOf(f: Int, vertex: Int): Int {
            for (k in 0 until 3) if (mesh.indices[f * 3 + k] == vertex) return f * 3 + k
            error("vertex $vertex is not a corner of face $f")
        }

        /** Chart [c]'s colour at [t] of the way from corner [from] to corner [to]. */
        private fun colorAlong(c: Int, from: Int, to: Int, t: Float, inAtlas: Boolean): FloatArray {
            val x = photoX[from] + (photoX[to] - photoX[from]) * t
            val y = photoY[from] + (photoY[to] - photoY[from]) * t
            return if (inAtlas) {
                box(atlasPhoto, atlasX(c, x) - HALF, atlasY(c, y) - HALF, 1f)
            } else {
                box(views[chartView[c]].photo, x - HALF, y - HALF, 1f)
            }
        }

        fun level() {
            if (shared.isEmpty()) return
            val offset = FloatArray(count * 3)
            val fixed = BooleanArray(count)
            for (group in shared) {
                val colors = group.map { atlasColor(it) }
                for (channel in 0 until 3) {
                    val mean = colors.sumOf { it[channel].toDouble() }.toFloat() / colors.size
                    group.forEachIndexed { i, cv -> offset[cv * 3 + channel] = mean - colors[i][channel] }
                }
                for (cv in group) fixed[cv] = true
            }
            diffuse(offset, fixed)
            apply(offset)
        }

        /** Gauss–Seidel on each chart's inside: every free chart-vertex takes its neighbours' mean offset. */
        private fun diffuse(offset: FloatArray, fixed: BooleanArray) {
            val degree = IntArray(count + 1)
            for (f in 0 until faceCount) {
                if (chart[f] < 0) continue
                for (k in 0 until 3) degree[cornerId[f * 3 + k] + 1] += 2
            }
            for (i in 0 until count) degree[i + 1] += degree[i]
            val links = IntArray(degree[count])
            val fillAt = degree.copyOf(count)
            for (f in 0 until faceCount) {
                if (chart[f] < 0) continue
                for (k in 0 until 3) {
                    val a = cornerId[f * 3 + k]
                    links[fillAt[a]++] = cornerId[f * 3 + (k + 1) % 3]
                    links[fillAt[a]++] = cornerId[f * 3 + (k + 2) % 3]
                }
            }
            repeat(DIFFUSE_ITERATIONS) { iteration ->
                if (iteration % TICK_EVERY == 0) tick()
                var change = 0f
                for (cv in 0 until count) {
                    if (fixed[cv] || degree[cv + 1] == degree[cv]) continue
                    var r = 0f
                    var g = 0f
                    var b = 0f
                    for (i in degree[cv] until degree[cv + 1]) {
                        val o = links[i] * 3
                        r += offset[o]
                        g += offset[o + 1]
                        b += offset[o + 2]
                    }
                    val n = (degree[cv + 1] - degree[cv]).toFloat()
                    change = max(change, abs(r / n - offset[cv * 3]))
                    offset[cv * 3] = r / n
                    offset[cv * 3 + 1] = g / n
                    offset[cv * 3 + 2] = b / n
                }
                if (change < DIFFUSE_SETTLED) return
            }
        }

        /** Adds each chart's offsets to its texels: interpolated over its faces, grown into its padding. */
        private fun apply(offset: FloatArray) {
            val facesOf = IntArray(charts + 1)
            for (f in 0 until faceCount) if (chart[f] >= 0) facesOf[chart[f] + 1]++
            for (c in 0 until charts) facesOf[c + 1] += facesOf[c]
            val list = IntArray(facesOf[charts])
            val fillAt = facesOf.copyOf(charts)
            for (f in 0 until faceCount) if (chart[f] >= 0) list[fillAt[chart[f]]++] = f
            for (c in 0 until charts) {
                val w = rectW[c]
                val h = rectH[c]
                val add = FloatArray(w * h * 3)
                val covered = BooleanArray(w * h)
                for (i in facesOf[c] until facesOf[c + 1]) {
                    rasterise(list[i], c, offset, add, covered, w, h)
                }
                grow(add, covered, w, h)
                for (j in 0 until h) {
                    for (i in 0 until w) {
                        val local = j * w + i
                        if (!covered[local]) continue
                        val at = (rectY[c] + j) * width + rectX[c] + i
                        val color = pixels[at]
                        pixels[at] = PhotoTexturing.rgb(
                            ((color shr 16 and 0xFF) + add[local * 3] + HALF).toInt(),
                            ((color shr 8 and 0xFF) + add[local * 3 + 1] + HALF).toInt(),
                            ((color and 0xFF) + add[local * 3 + 2] + HALF).toInt(),
                        )
                    }
                }
            }
        }

        private fun rasterise(f: Int, c: Int, offset: FloatArray, add: FloatArray, covered: BooleanArray, w: Int, h: Int) {
            val x = FloatArray(3) { atlasX(c, photoX[f * 3 + it]) - rectX[c] }
            val y = FloatArray(3) { atlasY(c, photoY[f * 3 + it]) - rectY[c] }
            val area = (x[1] - x[0]) * (y[2] - y[0]) - (y[1] - y[0]) * (x[2] - x[0])
            if (abs(area) < MIN_TEXEL_AREA) return
            val inv = 1f / area
            val x0 = max(0, floor(min(x[0], min(x[1], x[2]))).toInt())
            val x1 = min(w - 1, ceil(max(x[0], max(x[1], x[2]))).toInt())
            val y0 = max(0, floor(min(y[0], min(y[1], y[2]))).toInt())
            val y1 = min(h - 1, ceil(max(y[0], max(y[1], y[2]))).toInt())
            val o0 = cornerId[f * 3] * 3
            val o1 = cornerId[f * 3 + 1] * 3
            val o2 = cornerId[f * 3 + 2] * 3
            for (j in y0..y1) {
                val py = j + HALF
                for (i in x0..x1) {
                    val px = i + HALF
                    val w0 = ((x[1] - px) * (y[2] - py) - (y[1] - py) * (x[2] - px)) * inv
                    val w1 = ((x[2] - px) * (y[0] - py) - (y[2] - py) * (x[0] - px)) * inv
                    val w2 = 1f - w0 - w1
                    if (w0 < -EDGE_SLACK || w1 < -EDGE_SLACK || w2 < -EDGE_SLACK) continue
                    val local = j * w + i
                    for (channel in 0 until 3) {
                        add[local * 3 + channel] = w0 * offset[o0 + channel] + w1 * offset[o1 + channel] +
                            w2 * offset[o2 + channel]
                    }
                    covered[local] = true
                }
            }
        }

        /** Grows the covered offsets out over the rest of the chart's rectangle, a ring a pass. */
        private fun grow(add: FloatArray, covered: BooleanArray, w: Int, h: Int) {
            repeat(GROW_PASSES) {
                val next = covered.copyOf()
                for (j in 0 until h) {
                    for (i in 0 until w) {
                        val local = j * w + i
                        if (covered[local]) continue
                        var n = 0
                        var r = 0f
                        var g = 0f
                        var b = 0f
                        for ((di, dj) in NEIGHBOURS) {
                            val ni = i + di
                            val nj = j + dj
                            if (ni !in 0 until w || nj !in 0 until h) continue
                            val nl = nj * w + ni
                            if (!covered[nl]) continue
                            r += add[nl * 3]
                            g += add[nl * 3 + 1]
                            b += add[nl * 3 + 2]
                            n++
                        }
                        if (n == 0) continue
                        add[local * 3] = r / n
                        add[local * 3 + 1] = g / n
                        add[local * 3 + 2] = b / n
                        next[local] = true
                    }
                }
                next.copyInto(covered)
            }
        }
    }

    /** The mean of 3 × 3 bilinear samples [step] pixels apart around ([fx], [fy]), pixel-centre coordinates. */
    private fun box(photo: BakePhoto, fx: Float, fy: Float, step: Float): FloatArray {
        val out = FloatArray(3)
        for (dy in -1..1) {
            for (dx in -1..1) {
                val c = PhotoTexturing.sample(photo, fx + dx * step, fy + dy * step)
                out[0] += (c shr 16 and 0xFF).toFloat()
                out[1] += (c shr 8 and 0xFF).toFloat()
                out[2] += (c and 0xFF).toFloat()
            }
        }
        for (i in 0 until 3) out[i] /= BOX_SAMPLES
        return out
    }

    private companion object {
        /** Texels of margin around each chart: bilinear filtering stays inside down to the 4th mip level. */
        const val PAD = 4
        const val MAX_SIZE = 2048
        val WIDTHS = intArrayOf(256, 512, 1024, 2048)
        const val ROW_ALIGN = 16

        /** The share of a full-size atlas the charts may fill before their texels shrink. */
        const val FILL = 0.7
        const val SHRINK = 0.9f
        const val BACKGROUND = 0xFF808080.toInt()
        const val DIFFUSE_ITERATIONS = 300
        const val DIFFUSE_SETTLED = 0.05f
        const val TICK_EVERY = 25
        val SEAM_POINTS = floatArrayOf(0.25f, 0.5f, 0.75f)
        const val GROW_PASSES = PAD + 2
        const val EDGE_SLACK = 0.02f
        const val MIN_TEXEL_AREA = 1e-6f
        const val MIN_AREA = 1e-9f
        const val BOX_SAMPLES = 9f
        const val HALF = 0.5f
        val NEIGHBOURS = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
        val ORIGIN = DebugPose(0f, 0f, 0f)
    }
}
