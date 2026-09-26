/*
 * Ported from earcut-kotlin-multiplatform
 * (https://github.com/Monkey-Maestro/earcut-kotlin-multiplatform), itself a port of
 * mapbox/earcut (https://github.com/mapbox/earcut), and modified by the SceneView
 * contributors. The notices of both upstream projects follow.
 *
 * ------------------------------------------------------------------------------
 * earcut-kotlin-multiplatform
 *
 * MIT License
 *
 * Copyright (c) 2020 urbanistic
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 * ------------------------------------------------------------------------------
 * mapbox/earcut
 *
 * ISC License
 *
 * Copyright (c) Mapbox
 *
 * Permission to use, copy, modify, and/or distribute this software for any purpose
 * with or without fee is hereby granted, provided that the above copyright notice
 * and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH
 * REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT,
 * INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS
 * OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER
 * TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF
 * THIS SOFTWARE.
 */
package io.github.sceneview.triangulation

import dev.romainguy.kotlin.math.Float2
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Kotlin Multiplatform Port of [Earcut](https://github.com/mapbox/earcut)
 *
 * Ported from [earcut-kotlin-multiplatform](https://github.com/Monkey-Maestro/earcut-kotlin-multiplatform)
 * (MIT) — see the license notices at the top of this file.
 *
 * Thanks [Monkey-Maestro](https://github.com/Monkey-Maestro)
 */
object Earcut {

    /**
     * Bundled input for triangulation: vertex coordinates, hole start-indices, and vertex dimension.
     *
     * @param vertices Flat array of vertex coordinates (e.g. [x0,y0,x1,y1,...] for 2D).
     * @param holeIndices Vertex indices where each hole ring starts.
     * @param dimensions Number of coordinates per vertex (2 for 2D, 3 for 3D).
     */
    class Input(val vertices: DoubleArray, val holeIndices: IntArray, val dimensions: Int)

    class VertexIndex(var i: Int, var x: Double, var y: Double) {
        lateinit var prev: VertexIndex
        lateinit var next: VertexIndex

        var z: Double = 0.0

        var nextZ: VertexIndex? = null
        var prevZ: VertexIndex? = null

        var steiner: Boolean = false
    }

    val xComparator = Comparator { a: VertexIndex, b: VertexIndex -> compareValues(a.x, b.x) }

    /**
     * Projects 3D polygon vertices onto the XY plane by computing the polygon's
     * normal and applying a rotation transform. Required before triangulating 3D polygons.
     */
    fun toXY(data: DoubleArray): DoubleArray {
        val normal = normal(data)
        val anyToXYTransform = AnyToXYTransform(normal[0], normal[1], normal[2])
        val result = data.copyOf()
        anyToXYTransform.transform(result)
        return result
    }

    fun triangulate(input: Input): List<Int> {
        return triangulate(input.vertices, input.holeIndices, input.dimensions)
    }

    fun triangulate(path: List<Float2>, holeIndices: List<Int> = listOf()) = triangulate(
        path.flatMap { listOf(it.x.toDouble(), it.y.toDouble()) }.toDoubleArray(),
        holeIndices.toIntArray()
    )

    /**
     * Triangulate a polygon with 3d coordinates with optional hole
     *
     * @param path flat array of vertex coordinates like [x0,y0, x1,y1, x2,y2, ...].
     * @param holeIndices An array of hole indices if any (e.g. [5, 8] for a 12-vertex input would
     * mean one hole with vertices 5–7 and another with 8–11).
     * @param dimensions is the number of coordinates per vertex in the input array (2 by default).
     * Only two are used for triangulation (x and y), and the rest are ignored.
     *
     * @return Each group of three vertex indices in the resulting array forms a triangle.
     * e.g. [1,0,3, 3,2,1]
     */
    fun triangulate(
        path: DoubleArray,
        holeIndices: IntArray = intArrayOf(),
        dimensions: Int = 2
    ): List<Int> {
        val hasHoles = holeIndices.isNotEmpty()
        val outerLen = if (hasHoles) holeIndices[0] * dimensions else path.size
        var outerVertexIndex: VertexIndex? = linkedList(path, 0, outerLen, dimensions, true)
        val triangles: MutableList<Int> = mutableListOf()

        if (outerVertexIndex == null || outerVertexIndex.next === outerVertexIndex.prev) return triangles

        var minX = 0.0
        var minY = 0.0
        var maxX: Double
        var maxY: Double

        var x: Double
        var y: Double

        var invSize = 0.0

        if (hasHoles) outerVertexIndex =
            eliminateHoles(path, holeIndices, outerVertexIndex, dimensions)

        if (path.size > 80 * dimensions) {
            maxX = path[0]
            minX = path[0]
            maxY = path[1]
            minY = path[1]

            for (i in dimensions until outerLen step dimensions) {
                x = path[i]
                y = path[i + 1]
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
            }

            invSize = max(maxX - minX, maxY - minY)
            invSize = if (invSize != 0.0) 32767 / invSize else 0.0
        }

        earcutLinked(outerVertexIndex, triangles, dimensions, minX, minY, invSize, 0)

        return triangles
    }

    private fun normal(vertices: DoubleArray): DoubleArray {
        val ccw = true

        val points = arrayListOf<Point3>()
        for (i in 0 until (vertices.size / 3)) {
            points.add(Point3(vertices[i * 3], vertices[i * 3 + 1], vertices[i * 3 + 2]))
        }

        var m3: Point3 = points[points.size - 2]
        var m2 = points[points.size - 1]

        var c123 = Point3()
        var v32: Point3
        var v12: Point3

        for (i in points.indices) {
            val m1 = points[i]

            v32 = m3 - m2
            v12 = m1 - m2

            c123 = if (!ccw) {
                c123 + v32.cross(v12)
            } else {
                c123 + v12.cross(v32)
            }

            m3 = m2
            m2 = m1
        }

        c123.normalize()

        return doubleArrayOf(c123.x, c123.y, c123.z)
    }

    private fun linkedList(
        data: DoubleArray,
        start: Int,
        end: Int,
        dim: Int,
        clockwise: Boolean
    ): VertexIndex? {
        var last: VertexIndex? = null

        if (clockwise == (signedArea(data, start, end, dim) > 0)) {
            for (i in start until end step dim) {
                last = insertNode(i, data[i], data[i + 1], last)
            }
        } else {
            for (i in (end - dim) downTo start step dim) {
                last = insertNode(i, data[i], data[i + 1], last)
            }
        }

        if (last != null && equals(last, last.next)) {
            removeNode(last)
            last = last.next
        }

        return last
    }

    private fun filterPoints(start: VertexIndex?, _end: VertexIndex?): VertexIndex? {
        var end: VertexIndex? = _end

        if (start == null) return start
        if (end == null) end = start

        var p: VertexIndex = start
        var again: Boolean

        do {
            again = false

            if (!p.steiner && (equals(p, p.next) || area(p.prev, p, p.next) == 0.0)) {
                removeNode(p)
                p = p.prev
                end = p.prev
                if (p === p.next) break
                again = true
            } else {
                p = p.next
            }
        } while (again || p !== end)

        return end
    }

    private fun earcutLinked(
        _ear: VertexIndex?,
        triangles: MutableList<Int>,
        dim: Int,
        minX: Double,
        minY: Double,
        invSize: Double,
        pass: Int
    ) {
        var ear: VertexIndex = _ear ?: return

        if (pass == 0 && invSize != 0.0) indexCurve(ear, minX, minY, invSize)

        var stop: VertexIndex = ear
        var prev: VertexIndex
        var next: VertexIndex

        while (ear.prev !== ear.next) {
            prev = ear.prev
            next = ear.next

            if (if (invSize != 0.0) isEarHashed(ear, minX, minY, invSize) else isEar(ear)) {
                triangles.add(prev.i / dim or 0)
                triangles.add(ear.i / dim or 0)
                triangles.add(next.i / dim or 0)

                removeNode(ear)

                ear = next.next
                stop = next.next

                continue
            }

            ear = next

            if (ear === stop) {
                when (pass) {
                    0 -> {
                        earcutLinked(filterPoints(ear, null), triangles, dim, minX, minY, invSize, 1)
                    }
                    1 -> {
                        ear = cureLocalIntersections(filterPoints(ear, null)!!, triangles, dim)!!
                        earcutLinked(ear, triangles, dim, minX, minY, invSize, 2)
                    }
                    2 -> {
                        splitEarcut(ear, triangles, dim, minX, minY, invSize)
                    }
                }

                break
            }
        }
    }

    private fun isEar(ear: VertexIndex): Boolean {
        val a: VertexIndex = ear.prev
        val b: VertexIndex = ear
        val c: VertexIndex = ear.next

        if (area(a, b, c) >= 0) return false

        val ax = a.x; val bx = b.x; val cx = c.x
        val ay = a.y; val by = b.y; val cy = c.y

        val x0 = if (ax < bx) (if (ax < cx) ax else cx) else if (bx < cx) bx else cx
        val y0 = if (ay < by) (if (ay < cy) ay else cy) else if (by < cy) by else cy
        val x1 = if (ax > bx) (if (ax > cx) ax else cx) else if (bx > cx) bx else cx
        val y1 = if (ay > by) (if (ay > cy) ay else cy) else if (by > cy) by else cy

        var p = c.next
        while (p !== a) {
            if (p.x >= x0 && p.x <= x1 && p.y >= y0 && p.y <= y1 &&
                pointInTriangle(ax, ay, bx, by, cx, cy, p.x, p.y) &&
                area(p.prev, p, p.next) >= 0
            ) {
                return false
            }
            p = p.next
        }

        return true
    }

    private fun isEarHashed(
        ear: VertexIndex, minX: Double, minY: Double, invSize: Double
    ): Boolean {
        val a: VertexIndex = ear.prev
        val b: VertexIndex = ear
        val c: VertexIndex = ear.next

        if (area(a, b, c) >= 0) return false

        val ax = a.x; val bx = b.x; val cx = c.x
        val ay = a.y; val by = b.y; val cy = c.y

        val x0 = if (ax < bx) (if (ax < cx) ax else cx) else if (bx < cx) bx else cx
        val y0 = if (ay < by) (if (ay < cy) ay else cy) else if (by < cy) by else cy
        val x1 = if (ax > bx) (if (ax > cx) ax else cx) else if (bx > cx) bx else cx
        val y1 = if (ay > by) (if (ay > cy) ay else cy) else if (by > cy) by else cy

        val minZ = zOrder(x0, y0, minX, minY, invSize)
        val maxZ = zOrder(x1, y1, minX, minY, invSize)

        var p = ear.prevZ
        var n = ear.nextZ

        while (p != null && p.z >= minZ && n != null && n.z <= maxZ) {
            if (p.x >= x0 && p.x <= x1 && p.y >= y0 && p.y <= y1 && p !== a && p !== c &&
                pointInTriangle(ax, ay, bx, by, cx, cy, p.x, p.y) && area(p.prev, p, p.next) >= 0
            ) {
                return false
            }
            p = p.prevZ

            if (n.x >= x0 && n.x <= x1 && n.y >= y0 && n.y <= y1 && n !== a && n !== c &&
                pointInTriangle(ax, ay, bx, by, cx, cy, n.x, n.y) && area(n.prev, n, n.next) >= 0
            ) {
                return false
            }
            n = n.nextZ
        }

        while (p != null && p.z >= minZ) {
            if (p.x >= x0 && p.x <= x1 && p.y >= y0 && p.y <= y1 && p !== a && p !== c &&
                pointInTriangle(ax, ay, bx, by, cx, cy, p.x, p.y) && area(p.prev, p, p.next) >= 0
            ) {
                return false
            }
            p = p.prevZ
        }

        while (n != null && n.z <= maxZ) {
            if (n.x >= x0 && n.x <= x1 && n.y >= y0 && n.y <= y1 && n !== a && n !== c &&
                pointInTriangle(ax, ay, bx, by, cx, cy, n.x, n.y) && area(n.prev, n, n.next) >= 0
            ) {
                return false
            }
            n = n.nextZ
        }

        return true
    }

    private fun cureLocalIntersections(
        _start: VertexIndex, triangles: MutableList<Int>, dim: Int
    ): VertexIndex? {
        var start: VertexIndex = _start
        var p: VertexIndex = start

        do {
            val a: VertexIndex = p.prev
            val b: VertexIndex = p.next.next

            if (!equals(a, b) && intersects(a, p, p.next, b) && locallyInside(a, b) && locallyInside(b, a)) {
                triangles.add(a.i / dim or 0)
                triangles.add(p.i / dim or 0)
                triangles.add(b.i / dim or 0)

                removeNode(p)
                removeNode(p.next)

                start = b
                p = b
            }
            p = p.next
        } while (p !== start)

        return filterPoints(p, null)
    }

    private fun splitEarcut(
        start: VertexIndex, triangles: MutableList<Int>, dim: Int,
        minX: Double, minY: Double, invSize: Double
    ) {
        var a: VertexIndex? = start
        do {
            var b: VertexIndex? = a!!.next.next
            while (b !== a!!.prev) {
                if (a!!.i != b!!.i && isValidDiagonal(a, b)) {
                    var c: VertexIndex? = splitPolygon(a, b)

                    a = filterPoints(a, a.next)
                    c = filterPoints(c!!, c.next)

                    earcutLinked(a, triangles, dim, minX, minY, invSize, 0)
                    earcutLinked(c, triangles, dim, minX, minY, invSize, 0)
                    return
                }
                b = b.next
            }
            a = a!!.next
        } while (a !== start)
    }

    private fun eliminateHoles(
        data: DoubleArray, holeIndices: IntArray, _outerVertexIndex: VertexIndex, dim: Int
    ): VertexIndex? {
        var outerVertexIndex: VertexIndex? = _outerVertexIndex

        val queue: MutableList<VertexIndex> = mutableListOf()

        var start: Int
        var end: Int
        var list: VertexIndex

        val len: Int = holeIndices.size
        for (i in 0 until len) {
            start = holeIndices[i] * dim
            end = if (i < len - 1) holeIndices[i + 1] * dim else data.size
            list = linkedList(data, start, end, dim, false)!!

            if (list === list.next) list.steiner = true
            queue.add(getLeftmost(list))
        }

        queue.sortWith(xComparator)

        for (ii in 0 until queue.size) {
            outerVertexIndex = eliminateHole(queue[ii], outerVertexIndex!!)
        }

        return outerVertexIndex
    }

    private fun eliminateHole(hole: VertexIndex, outerVertexIndex: VertexIndex): VertexIndex {
        val bridge = findHoleBridge(hole, outerVertexIndex)
        if (bridge == null) {
            return outerVertexIndex
        }

        val bridgeReverse = splitPolygon(bridge, hole)

        filterPoints(bridgeReverse, bridgeReverse.next)
        return filterPoints(bridge, bridge.next)!!
    }

    private fun findHoleBridge(hole: VertexIndex, outerVertexIndex: VertexIndex): VertexIndex? {
        var p: VertexIndex = outerVertexIndex
        val hx: Double = hole.x
        val hy: Double = hole.y
        var qx = -Double.MAX_VALUE
        var m: VertexIndex? = null

        do {
            if (hy <= p.y && hy >= p.next.y && p.next.y != p.y) {
                val x: Double = p.x + (hy - p.y) * (p.next.x - p.x) / (p.next.y - p.y)
                if (x <= hx && x > qx) {
                    qx = x
                    m = if (p.x < p.next.x) p else p.next
                    if (x == hx) return m
                }
            }
            p = p.next
        } while (p !== outerVertexIndex)

        if (m == null) return null

        val stop: VertexIndex = m
        val mx: Double = m.x
        val my: Double = m.y
        var tanMin = Double.MAX_VALUE
        var tan: Double

        p = m

        do {
            if (hx >= p.x && p.x >= mx && hx != p.x &&
                pointInTriangle(
                    if (hy < my) hx else qx, hy, mx, my,
                    if (hy < my) qx else hx, hy, p.x, p.y
                )
            ) {
                tan = abs(hy - p.y) / (hx - p.x)

                if (locallyInside(p, hole) &&
                    (tan < tanMin || tan == tanMin && (p.x > m!!.x || p.x == m.x && sectorContainsSector(m, p)))
                ) {
                    m = p
                    tanMin = tan
                }
            }

            p = p.next
        } while (p !== stop)

        return m
    }

    private fun sectorContainsSector(m: VertexIndex, p: VertexIndex): Boolean {
        return area(m.prev, m, p.prev) < 0 && area(p.next, m, m.next) < 0
    }

    private fun indexCurve(start: VertexIndex, minX: Double, minY: Double, invSize: Double) {
        var p: VertexIndex? = start
        do {
            if (p!!.z == 0.0) p.z = zOrder(p.x, p.y, minX, minY, invSize)
            p.prevZ = p.prev
            p.nextZ = p.next
            p = p.next
        } while (p !== start)

        p.prevZ!!.nextZ = null
        p.prevZ = null

        sortLinked(p)
    }

    private fun sortLinked(_list: VertexIndex): VertexIndex {
        var list: VertexIndex? = _list
        var p: VertexIndex?
        var q: VertexIndex?
        var e: VertexIndex?
        var tail: VertexIndex?
        var numMerges: Int
        var pSize: Int
        var qSize: Int
        var inSize = 1

        do {
            p = list
            list = null
            tail = null
            numMerges = 0

            while (p != null) {
                numMerges++
                q = p
                pSize = 0

                for (i in 0 until inSize) {
                    pSize++
                    q = q!!.nextZ
                    if (q == null) break
                }
                qSize = inSize

                while (pSize > 0 || (qSize > 0 && q != null)) {
                    if (pSize != 0 && (qSize == 0 || q == null || p!!.z <= q.z)) {
                        e = p
                        p = p!!.nextZ
                        pSize--
                    } else {
                        e = q
                        q = q!!.nextZ
                        qSize--
                    }

                    if (tail != null) tail.nextZ = e else list = e

                    e!!.prevZ = tail
                    tail = e
                }

                p = q
            }

            tail!!.nextZ = null
            inSize *= 2
        } while (numMerges > 1)

        return list!!
    }

    private fun zOrder(
        x0: Double, y0: Double, minX: Double, minY: Double, invSize: Double
    ): Double {
        var x = ((x0 - minX) * invSize).toInt() or 0
        var y = ((y0 - minY) * invSize).toInt() or 0

        x = (x or (x shl 8)) and 0x00FF00FF
        x = (x or (x shl 4)) and 0x0F0F0F0F
        x = (x or (x shl 2)) and 0x33333333
        x = (x or (x shl 1)) and 0x55555555

        y = (y or (y shl 8)) and 0x00FF00FF
        y = (y or (y shl 4)) and 0x0F0F0F0F
        y = (y or (y shl 2)) and 0x33333333
        y = (y or (y shl 1)) and 0x55555555

        return (x or (y shl 1)).toDouble()
    }

    private fun getLeftmost(start: VertexIndex): VertexIndex {
        var p: VertexIndex = start
        var leftmost: VertexIndex = start

        do {
            if (p.x < leftmost.x || p.x == leftmost.x && p.y < leftmost.y) leftmost = p
            p = p.next
        } while (p !== start)

        return leftmost
    }

    private fun pointInTriangle(
        ax: Double, ay: Double, bx: Double, by: Double,
        cx: Double, cy: Double, px: Double, py: Double
    ): Boolean {
        return (cx - px) * (ay - py) >= (ax - px) * (cy - py) &&
                (ax - px) * (by - py) >= (bx - px) * (ay - py) &&
                (bx - px) * (cy - py) >= (cx - px) * (by - py)
    }

    private fun isValidDiagonal(a: VertexIndex, b: VertexIndex): Boolean {
        return a.next.i != b.i && a.prev.i != b.i && !intersectsPolygon(a, b) &&
                (locallyInside(a, b) && locallyInside(b, a) && middleInside(a, b) &&
                        (area(a.prev, a, b.prev) != 0.0 || area(a, b.prev, b) != 0.0) ||
                        equals(a, b) && area(a.prev, a, a.next) > 0 && area(b.prev, b, b.next) > 0)
    }

    private fun area(p: VertexIndex, q: VertexIndex, r: VertexIndex): Double {
        return (q.y - p.y) * (r.x - q.x) - (q.x - p.x) * (r.y - q.y)
    }

    private fun equals(p1: VertexIndex?, p2: VertexIndex?): Boolean {
        if (p1 == null && p2 == null) return true
        if (p1 == null || p2 == null) return false
        return p1.x == p2.x && p1.y == p2.y
    }

    private fun intersects(p1: VertexIndex, q1: VertexIndex, p2: VertexIndex, q2: VertexIndex): Boolean {
        val o1 = sign(area(p1, q1, p2))
        val o2 = sign(area(p1, q1, q2))
        val o3 = sign(area(p2, q2, p1))
        val o4 = sign(area(p2, q2, q1))

        if (o1 != o2 && o3 != o4) return true

        if (o1 == 0 && onSegment(p1, p2, q1)) return true
        if (o2 == 0 && onSegment(p1, q2, q1)) return true
        if (o3 == 0 && onSegment(p2, p1, q2)) return true
        if (o4 == 0 && onSegment(p2, q1, q2)) return true

        return false
    }

    private fun onSegment(p: VertexIndex, q: VertexIndex, r: VertexIndex): Boolean {
        return q.x <= max(p.x, r.x) &&
                q.x >= min(p.x, r.x) &&
                q.y <= max(p.y, r.y) &&
                q.y >= min(p.y, r.y)
    }

    private fun sign(num: Double): Int {
        return if (num > 0) 1 else if (num < 0) -1 else 0
    }

    private fun intersectsPolygon(a: VertexIndex, b: VertexIndex): Boolean {
        var p: VertexIndex = a
        do {
            if (p.i != a.i && p.next.i != a.i && p.i != b.i && p.next.i != b.i && intersects(p, p.next, a, b)) {
                return true
            }
            p = p.next
        } while (p !== a)

        return false
    }

    private fun locallyInside(a: VertexIndex, b: VertexIndex): Boolean {
        return if (area(a.prev, a, a.next) < 0) {
            area(a, b, a.next) >= 0 && area(a, a.prev, b) >= 0
        } else {
            area(a, b, a.prev) < 0 || area(a, a.next, b) < 0
        }
    }

    private fun middleInside(a: VertexIndex, b: VertexIndex): Boolean {
        var p: VertexIndex = a
        var inside = false
        val px = (a.x + b.x) / 2
        val py = (a.y + b.y) / 2
        do {
            if (((p.y > py) != (p.next.y > py)) && p.next.y != p.y && (px < (p.next.x - p.x) * (py - p.y) / (p.next.y - p.y) + p.x)) {
                inside = !inside
            }
            p = p.next
        } while (p !== a)

        return inside
    }

    private fun splitPolygon(a: VertexIndex, b: VertexIndex): VertexIndex {
        val a2 = VertexIndex(a.i, a.x, a.y)
        val b2 = VertexIndex(b.i, b.x, b.y)
        val an = a.next
        val bp = b.prev

        a.next = b
        b.prev = a

        a2.next = an
        an.prev = a2

        b2.next = a2
        a2.prev = b2

        bp.next = b2
        b2.prev = bp

        return b2
    }

    private fun insertNode(i: Int, x: Double, y: Double, last: VertexIndex?): VertexIndex {
        val p = VertexIndex(i, x, y)

        if (last == null) {
            p.prev = p
            p.next = p
        } else {
            p.next = last.next
            p.prev = last
            last.next.prev = p
            last.next = p
        }
        return p
    }

    private fun removeNode(p: VertexIndex) {
        p.next.prev = p.prev
        p.prev.next = p.next

        if (p.prevZ != null) p.prevZ!!.nextZ = p.nextZ
        if (p.nextZ != null) p.nextZ!!.prevZ = p.prevZ
    }

    private fun signedArea(data: DoubleArray, start: Int, end: Int, dim: Int): Double {
        var sum = 0.0
        var i = start
        var j = end - dim
        while (i < end) {
            sum += (data[j] - data[i]) * (data[i + 1] + data[j + 1])
            j = i
            i += dim
        }
        return sum
    }

    /**
     * Computes the deviation between polygon area and triangulated area.
     * Returns 0 for a perfect triangulation; useful for validating results.
     */
    fun deviation(input: Input, triangles: List<Int>): Double {
        return deviation(input.vertices, input.holeIndices, input.dimensions, triangles)
    }

    /**
     * Computes the deviation between polygon area and triangulated area.
     * Returns 0 for a perfect triangulation; useful for validating results.
     */
    fun deviation(
        data: DoubleArray, holeIndices: IntArray?, dim: Int, triangles: List<Int>
    ): Double {
        val hasHoles = holeIndices != null && holeIndices.isNotEmpty()
        val outerLen = if (hasHoles) holeIndices!![0] * dim else data.size

        var polygonArea: Double = abs(signedArea(data, 0, outerLen, dim))
        if (hasHoles) {
            val len = holeIndices!!.size
            for (i in 0 until len) {
                val start = holeIndices[i] * dim
                val end = if (i < len - 1) holeIndices[i + 1] * dim else data.size
                polygonArea -= abs(signedArea(data, start, end, dim))
            }
        }

        var trianglesArea = 0.0
        for (i in triangles.indices step 3) {
            val a = triangles[i] * dim
            val b = triangles[i + 1] * dim
            val c = triangles[i + 2] * dim
            trianglesArea += abs((data[a] - data[c]) * (data[b + 1] - data[a + 1]) - (data[a] - data[b]) * (data[c + 1] - data[a + 1]))
        }

        return if (polygonArea == 0.0 && trianglesArea == 0.0) {
            0.0
        } else {
            abs((trianglesArea - polygonArea) / polygonArea)
        }
    }

    /**
     * Flattens a nested polygon array (outer ring + holes) into an [Input].
     *
     * @param data Array of rings, each ring is an array of coordinate arrays.
     *             `data[0]` is the outer ring; `data[1..]` are holes.
     */
    fun flatten(data: Array<Array<DoubleArray>>): Input {
        val dim: Int = data[0][0].size
        val rVertices = mutableListOf<Double>()
        val rHoles = mutableListOf<Int>()

        var holeIndex = 0

        for (i in data.indices) {
            for (j in data[i].indices) {
                for (d in 0 until dim) {
                    rVertices.add(data[i][j][d])
                }
            }
            if (i > 0) {
                holeIndex += data[i - 1].size
                rHoles.add(holeIndex)
            }
        }

        return Input(rVertices.toDoubleArray(), rHoles.toIntArray(), dim)
    }

    class Point3(var x: Double = 0.0, var y: Double = 0.0, var z: Double = 0.0) {
        fun set(x: Double, y: Double, z: Double) {
            this.x = x
            this.y = y
            this.z = z
        }

        operator fun minus(v2: Point3): Point3 {
            return Point3(x - v2.x, y - v2.y, z - v2.z)
        }

        operator fun plus(v2: Point3): Point3 {
            return Point3(v2.x + x, v2.y + y, v2.z + z)
        }

        operator fun times(sc: Double): Point3 {
            return Point3(x * sc, y * sc, z * sc)
        }

        operator fun div(sc: Double): Point3 {
            return Point3(x / sc, y / sc, z / sc)
        }

        fun normalize() {
            val d = sqrt(x * x + y * y + z * z)
            if (d != 0.0) {
                x /= d
                y /= d
                z /= d
            }
        }

        fun cross(b: Point3): Point3 {
            return Point3(
                y * b.z - b.y * z,
                z * b.x - b.z * x,
                x * b.y - b.x * y
            )
        }
    }

    class AnyToXYTransform(nx: Double, ny: Double, nz: Double) {

        protected var m00 = 0.0
        protected var m01 = 0.0
        protected var m02 = 0.0
        protected var m10 = 0.0
        protected var m11 = 0.0
        protected var m12 = 0.0
        protected var m20 = 0.0
        protected var m21 = 0.0
        protected var m22 = 0.0

        fun setSourceNormal(nx: Double, ny: Double, nz: Double) {
            val h: Double
            val f: Double
            val hvx: Double
            val vx: Double = -ny
            val vy: Double = nx
            val c: Double = nz
            h = (1 - c) / (1 - c * c)
            hvx = h * vx
            f = if (c < 0) -c else c
            if (f < 1.0 - 1.0E-4) {
                m00 = c + hvx * vx
                m01 = hvx * vy
                m02 = -vy
                m10 = hvx * vy
                m11 = c + h * vy * vy
                m12 = vx
                m20 = vy
                m21 = -vx
                m22 = c
            } else {
                m00 = 1.0
                m01 = 0.0
                m02 = 0.0
                m10 = 0.0
                m11 = 1.0
                m12 = 0.0
                m20 = 0.0
                m21 = 0.0
                m22 = if (c > 0) 1.0 else -1.0
            }
        }

        init {
            setSourceNormal(nx, ny, nz)
        }

        fun transform(p: Point3) {
            val px: Double = p.x
            val py: Double = p.y
            val pz: Double = p.z
            p.set(
                m00 * px + m01 * py + m02 * pz,
                m10 * px + m11 * py + m12 * pz,
                m20 * px + m21 * py + m22 * pz
            )
        }

        fun transform(data: DoubleArray) {
            for (i in 0 until (data.size / 3)) {
                val point = Point3(data[i * 3], data[i * 3 + 1], data[i * 3 + 2])
                transform(point)
                data[i * 3] = point.x
                data[i * 3 + 1] = point.y
                data[i * 3 + 2] = point.z
            }
        }
    }
}
