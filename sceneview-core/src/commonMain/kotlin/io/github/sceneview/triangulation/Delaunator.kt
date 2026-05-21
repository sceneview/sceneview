package io.github.sceneview.triangulation

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

private val kEpsilon = 2.0.pow(-52.0)

/**
 * Fast [Delaunay triangulation](https://en.wikipedia.org/wiki/Delaunay_triangulation) of 2D points
 * implemented in Kotlin.
 *
 * This code was ported from [Delaunator C# project](https://github.com/nol1fe/delaunator-sharp)
 * (C#) which is a port from [Mapbox's Delaunator project](https://github.com/mapbox/delaunator)
 * (JavaScript).
 *
 * Copyright [Delaunator-Kt](https://github.com/ygdrasil-io/Delaunator-Kt)
 *
 * Thanks [ygdrasil-io](https://github.com/ygdrasil-io)
 *
 * ### Thread-safety
 *
 * Each `Delaunator` instance owns its own working memory ([edgeStack]) — different
 * instances on different threads are safe. A single instance is **NOT** safe for
 * concurrent calls; construct one per worker.
 *
 * Prior versions used a file-level `edgeStack` array shared across instances, which
 * silently corrupted the legalize pass when two `Delaunator` constructors ran in
 * parallel (e.g. a multi-window app pre-baking shape data on different threads).
 */
class Delaunator<out T : Delaunator.IPoint>(val points: List<T>) {

    /**
     * Per-instance scratch buffer used by [legalize]; replaces the old file-level mutable array.
     *
     * Grows on demand (see [legalize]). A previous fixed 512-slot version silently dropped
     * overflowing edges, producing a non-Delaunay triangulation for complex shapes (#2041);
     * growing the buffer matches the upstream Mapbox/delaunator-sharp behaviour.
     */
    private var edgeStack = IntArray(512)

    var triangles: Array<Int>
    var halfEdges: Array<Int>

    private val hashSize: Int
    private val hullPrev: MutableList<Int>
    private val hullNext: MutableList<Int>
    private val hullTri: MutableList<Int>
    private val hullHash: Array<Int>

    private var cx: Double
    private var cy: Double

    private var trianglesLen: Int
    private val coordinates: Array<Double>
    private var hullStart: Int
    private var hullSize: Int
    private val hull: Array<Int>


    init {
        if (points.size < 3) {
            throw IndexOutOfBoundsException("Need at least 3 points")
        }

        coordinates = Array(points.size * 2) { .0 }

        points.forEachIndexed { index, point ->
            coordinates[2 * index] = point.x
            coordinates[2 * index + 1] = point.y
        }

        val n = coordinates.size shr 1
        val maxTriangles = 2 * n - 5

        triangles = Array(maxTriangles * 3) { 0 }

        halfEdges = Array(maxTriangles * 3) { 0 }
        hashSize = ceil(sqrt(n.toDouble())).toInt()

        hullPrev = MutableList(n) { 0 }
        hullNext = MutableList(n) { 0 }
        hullTri = MutableList(n) { 0 }
        hullHash = Array(hashSize) { 0 }

        val ids = Array(n) { 0 }

        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY

        for (i in 0 until n) {
            val x = coordinates[2 * i]
            val y = coordinates[2 * i + 1]
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
            ids[i] = i
        }

        val cx = (minX + maxX) / 2
        val cy = (minY + maxY) / 2

        var minDist = Double.POSITIVE_INFINITY
        var i0 = 0
        var i1 = 0
        var i2 = 0

        // pick a seed point close to the center
        for (i in 0 until n) {
            val d = dist(cx, cy, coordinates[2 * i], coordinates[2 * i + 1])
            if (d < minDist) {
                i0 = i
                minDist = d
            }
        }
        val i0x = coordinates[2 * i0]
        val i0y = coordinates[2 * i0 + 1]

        minDist = Double.POSITIVE_INFINITY

        // find the point closest to the seed
        for (i in 0 until n) {
            if (i == i0) continue
            val d = dist(i0x, i0y, coordinates[2 * i], coordinates[2 * i + 1])
            if (d < minDist && d > 0) {
                i1 = i
                minDist = d
            }
        }

        var i1x = coordinates[2 * i1]
        var i1y = coordinates[2 * i1 + 1]

        var minRadius = Double.POSITIVE_INFINITY

        // find the third point which forms the smallest circumcircle with the first two
        for (i in 0 until n) {
            if (i == i0 || i == i1) continue
            val r = circumRadius(i0x, i0y, i1x, i1y, coordinates[2 * i], coordinates[2 * i + 1])
            if (r < minRadius) {
                i2 = i
                minRadius = r
            }
        }
        var i2x = coordinates[2 * i2]
        var i2y = coordinates[2 * i2 + 1]

        if (minRadius == Double.POSITIVE_INFINITY) {
            throw IllegalArgumentException("No Delaunay triangulation exists for this input.")
        }

        if (orient(i0x, i0y, i1x, i1y, i2x, i2y)) {
            val i = i1
            val x = i1x
            val y = i1y
            i1 = i2
            i1x = i2x
            i1y = i2y
            i2 = i
            i2x = x
            i2y = y
        }

        val center = circumCenter(i0x, i0y, i1x, i1y, i2x, i2y)
        this.cx = center.x
        this.cy = center.y

        val dists = Array(n) { i ->
            dist(coordinates[2 * i], coordinates[2 * i + 1], center.x, center.y)
        }

        // sort the points by distance from the seed triangle circumcenter
        quicksort(ids, dists, 0, n - 1)

        // set up the seed triangle as the starting hull
        hullStart = i0
        hullSize = 3

        hullPrev[i2] = i1
        hullNext[i0] = i1
        hullPrev[i0] = i2
        hullNext[i1] = i2
        hullPrev[i1] = i0
        hullNext[i2] = i0

        hullTri[i0] = 0
        hullTri[i1] = 1
        hullTri[i2] = 2

        hullHash[hashKey(i0x, i0y)] = i0
        hullHash[hashKey(i1x, i1y)] = i1
        hullHash[hashKey(i2x, i2y)] = i2

        trianglesLen = 0
        addTriangle(i0, i1, i2, -1, -1, -1)

        var xp = .0
        var yp = .0

        for (k in ids.indices) {
            val i = ids[k]
            val x = coordinates[2 * i]
            val y = coordinates[2 * i + 1]

            // skip near-duplicate points
            if (k > 0 && abs(x - xp) <= kEpsilon && abs(y - yp) <= kEpsilon) continue
            xp = x
            yp = y

            // skip seed triangle points
            if (i == i0 || i == i1 || i == i2) continue

            // find a visible edge on the convex hull using edge hash
            var start = 0
            for (j in 0 until hashSize) {
                val key = hashKey(x, y)
                start = hullHash[(key + j) % hashSize]
                if (start != -1 && start != hullNext[start]) break
            }


            start = hullPrev[start]
            var e = start
            var q = hullNext[e]

            while (!orient(
                    x,
                    y,
                    coordinates[2 * e],
                    coordinates[2 * e + 1],
                    coordinates[2 * q],
                    coordinates[2 * q + 1]
                )
            ) {
                e = q
                if (e == start) {
                    e = Int.MAX_VALUE
                    break
                }

                q = hullNext[e]
            }

            if (e == Int.MAX_VALUE) continue // likely a near-duplicate point; skip it

            // add the first triangle from the point
            var t = addTriangle(e, i, hullNext[e], -1, -1, hullTri[e])

            // recursively flip triangles from the point until they satisfy the Delaunay condition
            hullTri[i] = legalize(t + 2)
            hullTri[e] = t // keep track of boundary triangles on the hull
            hullSize++

            // walk forward through the hull, adding more triangles and flipping recursively
            var next = hullNext[e]
            q = hullNext[next]

            while (orient(
                    x,
                    y,
                    coordinates[2 * next],
                    coordinates[2 * next + 1],
                    coordinates[2 * q],
                    coordinates[2 * q + 1]
                )
            ) {
                t = addTriangle(next, i, q, hullTri[i], -1, hullTri[next])
                hullTri[i] = legalize(t + 2)
                hullNext[next] = next // mark as removed
                hullSize--
                next = q

                q = hullNext[next]
            }

            // walk backward from the other side, adding more triangles and flipping
            if (e == start) {
                q = hullPrev[e]

                while (orient(
                        x,
                        y,
                        coordinates[2 * q],
                        coordinates[2 * q + 1],
                        coordinates[2 * e],
                        coordinates[2 * e + 1]
                    )
                ) {
                    t = addTriangle(q, i, e, -1, hullTri[e], hullTri[q])
                    legalize(t + 2)
                    hullTri[q] = t
                    hullNext[e] = e // mark as removed
                    hullSize--
                    e = q

                    q = hullPrev[e]
                }
            }

            // update the hull indices
            hullPrev[i] = e
            hullStart = e
            hullPrev[next] = i
            hullNext[e] = i
            hullNext[i] = next

            // save the two new edges in the hash table
            hullHash[hashKey(x, y)] = i
            hullHash[hashKey(coordinates[2 * e], coordinates[2 * e + 1])] = e
        }

        hull = Array(hullSize) { 0 }
        var s = hullStart
        for (i in 0 until hullSize) {
            hull[i] = s
            s = hullNext[s]
        }

        // get rid of temporary arrays
        hullPrev.clear()
        hullNext.clear()
        hullTri.clear()

        //// trim typed triangle mesh arrays
        triangles = triangles.take(trianglesLen).toTypedArray()
        halfEdges = halfEdges.take(trianglesLen).toTypedArray()
    }

    private fun hashKey(x: Double, y: Double): Int {
        return (floor(pseudoAngle(x - cx, y - cy) * hashSize) % hashSize).toInt()
    }

    private fun pseudoAngle(dx: Double, dy: Double): Double {
        val p = dx / (abs(dx) + abs(dy))
        return (if (dy > 0) 3 - p else 1 + p) / 4 // [0..1]
    }

    private fun legalize(index: Int): Int {
        var a = index
        var i = 0
        var ar: Int

        while (true) {
            val b = halfEdges[a]

            val a0 = a - a % 3
            ar = a0 + (a + 2) % 3

            if (b == -1) {
                if (i == 0) break
                a = edgeStack[--i]
                continue
            }

            val b0 = b - b % 3
            val al = a0 + (a + 1) % 3
            val bl = b0 + (b + 2) % 3

            val p0 = triangles[ar]
            val pr = triangles[a]
            val pl = triangles[al]
            val p1 = triangles[bl]

            val illegal = inCircle(
                coordinates[2 * p0], coordinates[2 * p0 + 1],
                coordinates[2 * pr], coordinates[2 * pr + 1],
                coordinates[2 * pl], coordinates[2 * pl + 1],
                coordinates[2 * p1], coordinates[2 * p1 + 1]
            )

            if (illegal) {
                triangles[a] = p1
                triangles[b] = p0

                val hbl = halfEdges[bl]

                if (hbl == -1) {
                    var e = hullStart
                    do {
                        if (hullTri[e] == bl) {
                            hullTri[e] = a
                            break
                        }
                        e = hullNext[e]
                    } while (e != hullStart)
                }
                link(a, hbl)
                link(b, halfEdges[ar])
                link(ar, bl)

                val br = b0 + (b + 1) % 3

                // Grow the scratch buffer instead of dropping the edge: a dropped edge
                // skips a recursive Delaunay flip and silently corrupts the result (#2041).
                if (i >= edgeStack.size) {
                    edgeStack = edgeStack.copyOf(edgeStack.size * 2)
                }
                edgeStack[i++] = br
            } else {
                if (i == 0) break
                a = edgeStack[--i]
            }
        }

        return ar
    }

    private fun inCircle(
        ax: Double, ay: Double,
        bx: Double, by: Double,
        cx: Double, cy: Double,
        px: Double, py: Double
    ): Boolean {
        val dx = ax - px
        val dy = ay - py
        val ex = bx - px
        val ey = by - py
        val fx = cx - px
        val fy = cy - py
        val ap = dx * dx + dy * dy
        val bp = ex * ex + ey * ey
        val cp = fx * fx + fy * fy
        return dx * (ey * cp - bp * fy) -
                dy * (ex * cp - bp * fx) +
                ap * (ex * fy - ey * fx) < 0
    }

    private fun link(a: Int, b: Int) {
        halfEdges[a] = b
        if (b != -1) halfEdges[b] = a
    }

    private fun circumRadius(
        ax: Double, ay: Double,
        bx: Double, by: Double,
        cx: Double, cy: Double
    ): Double {
        val dx = bx - ax
        val dy = by - ay
        val ex = cx - ax
        val ey = cy - ay
        val bl = dx * dx + dy * dy
        val cl = ex * ex + ey * ey
        val d = 0.5 / (dx * ey - dy * ex)
        val x = (ey * bl - dy * cl) * d
        val y = (dx * cl - ex * bl) * d
        return x * x + y * y
    }

    private fun quicksort(ids: Array<Int>, dists: Array<Double>, left: Int, right: Int) {
        if (right - left <= 20) {
            for (i in left + 1..right) {
                val temp = ids[i]
                val tempDist = dists[temp]
                var j = i - 1
                while (j >= left && dists[ids[j]] > tempDist) ids[j + 1] = ids[j--]
                ids[j + 1] = temp
            }
        } else {
            val median = left + right shr 1
            var i = left + 1
            var j = right
            swap(ids, median, i)
            if (dists[ids[left]] > dists[ids[right]]) swap(ids, left, right)
            if (dists[ids[i]] > dists[ids[right]]) swap(ids, i, right)
            if (dists[ids[left]] > dists[ids[i]]) swap(ids, left, i)
            val temp = ids[i]
            val tempDist = dists[temp]
            while (true) {
                do i++ while (dists[ids[i]] < tempDist)
                do j-- while (dists[ids[j]] > tempDist)
                if (j < i) break
                swap(ids, i, j)
            }
            ids[left + 1] = ids[j]
            ids[j] = temp
            if (right - i + 1 >= j - left) {
                quicksort(ids, dists, i, right)
                quicksort(ids, dists, left, j - 1)
            } else {
                quicksort(ids, dists, left, j - 1)
                quicksort(ids, dists, i, right)
            }
        }
    }

    private fun swap(arr: Array<Int>, i: Int, j: Int) {
        val tmp = arr[i]
        arr[i] = arr[j]
        arr[j] = tmp
    }

    private fun circumCenter(
        ax: Double, ay: Double,
        bx: Double, by: Double,
        cx: Double, cy: Double
    ): Point {
        val dx = bx - ax
        val dy = by - ay
        val ex = cx - ax
        val ey = cy - ay
        val bl = dx * dx + dy * dy
        val cl = ex * ex + ey * ey
        val d = 0.5 / (dx * ey - dy * ex)
        val x = ax + (ey * bl - dy * cl) * d
        val y = ay + (dx * cl - ex * bl) * d
        return Point(x, y)
    }

    private fun orient(
        px: Double, py: Double,
        qx: Double, qy: Double,
        rx: Double, ry: Double
    ): Boolean {
        return (qy - py) * (rx - qx) - (qx - px) * (ry - qy) < 0
    }

    private fun addTriangle(i0: Int, i1: Int, i2: Int, a: Int, b: Int, c: Int): Int {
        val t = trianglesLen
        triangles[t] = i0
        triangles[t + 1] = i1
        triangles[t + 2] = i2
        link(t, a)
        link(t + 1, b)
        link(t + 2, c)
        trianglesLen += 3
        return t
    }

    private fun dist(ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = ax - bx
        val dy = ay - by
        return dx * dx + dy * dy
    }

    private fun createHull(points: List<T>): List<IEdge> {
        return points.mapIndexed { index: Int, point: T ->
            if (points.lastIndex == index) {
                Edge(0, point, points.first())
            } else {
                Edge(0, point, points[index + 1])
            }
        }
    }

    private fun getHullPoints(): List<T> {
        return hull.map { x -> points[x] }
    }

    fun getHullEdges(): List<IEdge> {
        return createHull(getHullPoints())
    }

    fun getVoronoiCells(): Sequence<VoronoiCell> {
        return sequence {
            val seen = HashSet<Int>()
            for (triangleId in triangles.indices) {
                val id = triangles[nextHalfedgeIndex(triangleId)]
                if (!seen.contains(id)) {
                    seen.add(id)
                    val edges = edgesAroundPoint(triangleId)
                    val triangles = edges.map { x -> triangleOfEdge(x) }
                    val vertices = triangles.map { x -> getTriangleCenter(x) }
                    yield(VoronoiCell(id, vertices.toList()))
                }
            }
        }
    }

    private fun getTriangleCenter(t: Int): IPoint {
        val vertices = getTrianglePoints(t)
        return getCentroid(vertices)
    }

    private fun getCentroid(points: List<IPoint>): IPoint {
        var accumulatedArea = 0.0
        var centerX = 0.0
        var centerY = 0.0
        var j = points.size - 1
        for (i in points.indices) {
            val temp = points[i].x * points[j].y - points[j].x * points[i].y
            accumulatedArea += temp
            centerX += (points[i].x + points[j].x) * temp
            centerY += (points[i].y + points[j].y) * temp
            j = i
        }

        accumulatedArea *= 3.0
        return Point(
            centerX / accumulatedArea,
            centerY / accumulatedArea
        )
    }

    private fun getTrianglePoints(t: Int): List<IPoint> {
        return pointsOfTriangle(t).map { p -> points[p] }
    }

    private fun pointsOfTriangle(t: Int): List<Int> {
        return edgesOfTriangle(t).map { e -> triangles[e] }
    }

    private fun edgesOfTriangle(t: Int): List<Int> {
        return listOf(3 * t, 3 * t + 1, 3 * t + 2)
    }

    private fun triangleOfEdge(e: Int): Int {
        return floor(e / 3.0).toInt()
    }

    private fun edgesAroundPoint(start: Int): Sequence<Int> {
        return sequence {
            var incoming = start
            do {
                yield(incoming)
                val outgoing = nextHalfedgeIndex(incoming)
                incoming = halfEdges[outgoing]
            } while (incoming != -1 && incoming != start)
        }
    }

    private fun nextHalfedgeIndex(e: Int): Int {
        return if (e % 3 == 2) e - 2 else e + 1
    }

    fun getEdges(): Sequence<IEdge> {
        return sequence {
            for (e in triangles.indices) {
                if (e > halfEdges[e]) {
                    val p = points[triangles[e]]
                    val q = points[triangles[nextHalfedgeIndex(e)]]
                    yield(Edge(e, p, q))
                }
            }
        }
    }

    /** A 2D point with mutable x/y coordinates. */
    interface IPoint {
        var x: Double
        var y: Double
    }

    class Point(override var x: Double, override var y: Double) : IPoint {
        operator fun minus(other: Point) = Point(x - other.x, y - other.y)
        operator fun plus(other: Point) = Point(x + other.x, y + other.y)
        operator fun div(other: Int) = Point(x / other, y / other)
        override fun toString() = "{$x},{$y}"
    }

    interface IEdge {
        val p: IPoint
        val q: IPoint
        val index: Int
    }

    class Edge(override val index: Int, override val p: IPoint, override val q: IPoint) :
        IEdge

    interface ITriangle {
        val points: List<IPoint>
        val Index: Int
    }

    class Triangle(override val points: List<IPoint>, override val Index: Int) :
        ITriangle

    interface IVoronoiCell {
        val points: List<IPoint>
        val index: Int
    }

    class VoronoiCell(override val index: Int, override val points: List<IPoint>) :
        IVoronoiCell
}
