package io.github.sceneview.demo.demos.internal.model

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

/** A plane `normal · p = offset`, unit [nx], [ny], [nz] (+Y up). */
internal data class Plane(val nx: Float, val ny: Float, val nz: Float, val offset: Float) {
    fun distance(x: Float, y: Float, z: Float): Float = nx * x + ny * y + nz * z - offset
}

/** What [PlaneFlattening.flatten] found: the planes, each triangle's plane (-1 for none), and how much it moved. */
internal class Flattening(
    val planes: List<Plane>,
    val facePlane: IntArray,
    val flatArea: Float,
    val totalArea: Float,
    val snappedVertices: Int,
    /** How far the flat regions' vertices lay from their planes before the snap, RMS: the scan's own noise. */
    val noiseM: Float = 0f,
)

/*
 * Makes the room's big flat surfaces — floor, walls, ceiling, worktops — exactly flat. A depth
 * scan meshes a wall as a gentle ripple; the eye reads that as "scanned", not as "a wall". This
 * finds the large near-planar regions of the mesh, fits each one a plane (snapped to gravity
 * when it is within a few degrees of level or plumb — a floor that really slopes keeps its
 * slope, [LEVEL_SNAP_RMS_M]), and moves the vertices onto it: onto the
 * plane inside a region, onto the line where two meet, onto the corner where three do. Anything
 * that is not part of a large flat region — a chair, a plant, a lamp — is left as it is.
 */
internal object PlaneFlattening {
    fun flatten(
        mesh: WorkMesh,
        minAreaM2: Float = MIN_PLANE_AREA_M2,
        maxAngleDeg: Float = GROW_ANGLE_DEG,
        maxDistanceM: Float = GROW_DISTANCE_M,
        maxSnapM: Float = MAX_SNAP_M,
        tick: () -> Unit = {},
    ): Flattening {
        val n = mesh.triangleCount
        val faces = FaceGeometry(mesh.positions, mesh.indices)
        val totalArea = faces.areas.sum()
        if (n == 0) return Flattening(emptyList(), IntArray(0), 0f, 0f, 0)
        val topology = MeshTopology(mesh.vertexCount, mesh.indices)
        val minCos = cos(Math.toRadians(maxAngleDeg.toDouble())).toFloat()
        val grown = grow(topology, faces, minCos, maxDistanceM)
        tick()
        val grownRegions = grown.max() + 1
        val area = FloatArray(grownRegions)
        for (t in 0 until n) area[grown[t]] += faces.areas[t]
        // Small regions are not planes worth flattening on their own: their faces stay free,
        // unless they lie on one of the planes found ([gather]).
        val keepId = IntArray(grownRegions) { -1 }
        var kept = 0
        for (r in 0 until grownRegions) if (area[r] >= minAreaM2) keepId[r] = kept++
        val label = IntArray(n) { keepId[grown[it]] }
        var regions = kept
        var planes = (0 until regions).map { fit(faces, label, it) }
        // One wall split in two by a cabinet is still one wall: merge regions on the same plane.
        val merged = mergeCoplanar(faces, label, planes)
        var anchors = planes
        if (merged.count < regions) {
            for (t in 0 until n) if (label[t] >= 0) label[t] = merged.id[label[t]]
            regions = merged.count
            anchors = merged.anchors
            planes = (0 until regions).map { fit(faces, label, it) }
        }
        if (gather(faces, grown, keepId, label, planes, anchors, minCos, maxSnapM)) {
            planes = (0 until regions).map { fit(faces, label, it) }
        }
        absorb(topology, faces, label, planes)
        tick()
        val noise = noise(mesh, label, planes)
        val snapped = snap(mesh, topology, faces, label, planes, maxSnapM)
        adopt(mesh, topology, label, planes)
        var flatArea = 0f
        for (t in 0 until n) if (label[t] >= 0) flatArea += faces.areas[t]
        return Flattening(planes, label, flatArea, totalArea, snapped, noise)
    }

    /**
     * Gives the small regions a real wall breaks into to the plane they lie on. A scan's wall is
     * lace — holes cut it into islands — and its ripple splits what is left into patches that
     * each turn a little: none reaches [MIN_PLANE_AREA_M2] alone, so [grow] keeps none, and the
     * wall stays as scanned. A region joins a plane when its mean normal is within the growing
     * angle of the plane's, it lies on average within [MERGE_DISTANCE_M] of the plane — a second
     * pass of the same wall, drifted a few centimetres behind it, shows through the first one's
     * holes — every face of it within [maxDistance] (the snap's reach), and it lies within
     * [GATHER_MARGIN_M] of the plane's own extent. Past [FREE_MERGE_M] from its [anchors] plane —
     * the group's widest sheet as fitted before the merge, not the plane fitted after it, which a
     * second pass merged in pulls towards whatever stands behind the wall — it must also lie where
     * the plane's own faces are ([Footprint.share] of at least [MIN_SHARE]): a second pass overlaps
     * the first, while a TV or a door's leaf hides the wall behind it, so the plane has a hole
     * exactly where it stands. A curtain's pleats, a cushion, a shelf, a TV turn away or stand
     * off: they stay free. Returns whether any region joined.
     */
    @Suppress("LongParameterList", "CyclomaticComplexMethod", "NestedBlockDepth")
    private fun gather(
        faces: FaceGeometry,
        grown: IntArray,
        keepId: IntArray,
        label: IntArray,
        planes: List<Plane>,
        anchors: List<Plane>,
        minCos: Float,
        maxDistance: Float,
    ): Boolean {
        if (planes.isEmpty()) return false
        val n = faces.count
        val extent = FloatArray(planes.size * 6)
        for (p in planes.indices) {
            for (axis in 0 until 3) {
                extent[p * 6 + axis] = Float.MAX_VALUE
                extent[p * 6 + 3 + axis] = -Float.MAX_VALUE
            }
        }
        for (t in 0 until n) {
            val p = label[t]
            if (p < 0) continue
            for (axis in 0 until 3) {
                val c = faces.centroids[t * 3 + axis]
                if (c < extent[p * 6 + axis]) extent[p * 6 + axis] = c
                if (c > extent[p * 6 + 3 + axis]) extent[p * 6 + 3 + axis] = c
            }
        }
        // The faces of each region, and of each plane, contiguous; each plane's footprint.
        val regions = keepId.size
        val byRegion = Members(grown, regions)
        val start = byRegion.start
        val members = byRegion.members
        val byPlane = Members(label, planes.size)
        val grids = Array(planes.size) { Grid(planes[it]) }
        val planeCells = Array(planes.size) {
            grids[it].footprint(faces, byPlane.members, byPlane.start[it], byPlane.start[it + 1])
        }
        var joined = false
        for (r in 0 until regions) {
            if (keepId[r] >= 0) continue
            var nx = 0.0
            var ny = 0.0
            var nz = 0.0
            var cx = 0.0
            var cy = 0.0
            var cz = 0.0
            var a = 0.0
            for (i in start[r] until start[r + 1]) {
                val t = members[i]
                val w = faces.areas[t].toDouble() + AREA_EPSILON
                nx += faces.normals[t * 3] * w
                ny += faces.normals[t * 3 + 1] * w
                nz += faces.normals[t * 3 + 2] * w
                cx += faces.centroids[t * 3] * w
                cy += faces.centroids[t * 3 + 1] * w
                cz += faces.centroids[t * 3 + 2] * w
                a += w
            }
            val length = sqrt(nx * nx + ny * ny + nz * nz)
            if (length <= 0.0) continue
            cx /= a
            cy /= a
            cz /= a
            var best = -1
            var bestOff = maxDistance
            for (p in planes.indices) {
                val plane = planes[p]
                if ((nx * plane.nx + ny * plane.ny + nz * plane.nz) / length < minCos) continue
                val o = p * 6
                if (cx < extent[o] - GATHER_MARGIN_M || cx > extent[o + 3] + GATHER_MARGIN_M ||
                    cy < extent[o + 1] - GATHER_MARGIN_M || cy > extent[o + 4] + GATHER_MARGIN_M ||
                    cz < extent[o + 2] - GATHER_MARGIN_M || cz > extent[o + 5] + GATHER_MARGIN_M
                ) {
                    continue
                }
                val gap = abs(plane.distance(cx.toFloat(), cy.toFloat(), cz.toFloat()))
                if (gap > MERGE_DISTANCE_M) continue
                if (abs(anchors[p].distance(cx.toFloat(), cy.toFloat(), cz.toFloat())) > FREE_MERGE_M) {
                    val own = grids[p].footprint(faces, members, start[r], start[r + 1])
                    if (planeCells[p].share(own) < MIN_SHARE) continue
                }
                var worst = 0f
                for (i in start[r] until start[r + 1]) {
                    val t = members[i]
                    val d = abs(plane.distance(faces.centroids[t * 3], faces.centroids[t * 3 + 1], faces.centroids[t * 3 + 2]))
                    if (d > worst) worst = d
                    if (worst > bestOff) break
                }
                if (worst <= bestOff) {
                    best = p
                    bestOff = worst
                }
            }
            if (best < 0) continue
            for (i in start[r] until start[r + 1]) label[members[i]] = best
            joined = true
        }
        return joined
    }

    /** The RMS distance of the flat faces' vertices to their planes, before the snap moves them. */
    private fun noise(mesh: WorkMesh, label: IntArray, planes: List<Plane>): Float {
        var sum = 0.0
        var count = 0L
        val p = mesh.positions
        for (t in label.indices) {
            val id = label[t]
            if (id < 0) continue
            val plane = planes[id]
            for (k in 0 until 3) {
                val v = mesh.indices[t * 3 + k] * 3
                val d = plane.distance(p[v], p[v + 1], p[v + 2]).toDouble()
                sum += d * d
                count++
            }
        }
        return if (count > 0) sqrt(sum / count).toFloat() else 0f
    }

    /** Region growing across edges from the flattest faces first; every face ends in a region. */
    private fun grow(topology: MeshTopology, faces: FaceGeometry, minCos: Float, maxDistance: Float): IntArray {
        val n = faces.count
        val flatness = FloatArray(n)
        for (t in 0 until n) {
            var worst = 1f
            for (k in 0 until 3) {
                val u = topology.neighbours[t * 3 + k]
                worst = if (u < 0) minOf(worst, 0f) else minOf(worst, faces.dot(t, u))
            }
            flatness[t] = worst
        }
        // Flattest first, ties by index: one primitive sort, no boxed index per face.
        val order = LongArray(n) { t ->
            val bits = flatness[t].toRawBits()
            val sortable = bits xor ((bits shr 31) and Int.MAX_VALUE)
            (sortable.inv().toLong() shl 32) or t.toLong()
        }
        order.sort()
        val label = IntArray(n) { -1 }
        val queue = IntArray(n)
        var regions = 0
        for (key in order) {
            val seed = (key and LOW_INT).toInt()
            if (label[seed] >= 0) continue
            val id = regions++
            var head = 0
            var tail = 0
            queue[tail++] = seed
            label[seed] = id
            var sumNx = 0.0
            var sumNy = 0.0
            var sumNz = 0.0
            var sumCx = 0.0
            var sumCy = 0.0
            var sumCz = 0.0
            var sumA = 0.0
            while (head < tail) {
                val t = queue[head++]
                val a = faces.areas[t].toDouble() + AREA_EPSILON
                sumNx += faces.normals[t * 3] * a
                sumNy += faces.normals[t * 3 + 1] * a
                sumNz += faces.normals[t * 3 + 2] * a
                sumCx += faces.centroids[t * 3] * a
                sumCy += faces.centroids[t * 3 + 1] * a
                sumCz += faces.centroids[t * 3 + 2] * a
                sumA += a
                val length = sqrt(sumNx * sumNx + sumNy * sumNy + sumNz * sumNz)
                if (length <= 0.0) continue
                val nx = (sumNx / length).toFloat()
                val ny = (sumNy / length).toFloat()
                val nz = (sumNz / length).toFloat()
                val cx = (sumCx / sumA).toFloat()
                val cy = (sumCy / sumA).toFloat()
                val cz = (sumCz / sumA).toFloat()
                for (k in 0 until 3) {
                    val u = topology.neighbours[t * 3 + k]
                    if (u < 0 || label[u] >= 0) continue
                    val facing = faces.normals[u * 3] * nx + faces.normals[u * 3 + 1] * ny + faces.normals[u * 3 + 2] * nz
                    if (facing < minCos) continue
                    val off = nx * (faces.centroids[u * 3] - cx) +
                        ny * (faces.centroids[u * 3 + 1] - cy) +
                        nz * (faces.centroids[u * 3 + 2] - cz)
                    if (abs(off) > maxDistance) continue
                    label[u] = id
                    queue[tail++] = u
                }
            }
        }
        return label
    }

    /**
     * The least-squares plane of region [id]'s face centroids, area-weighted, snapped to gravity:
     * made plumb within [GRAVITY_SNAP_DEG] of it, made level within [GRAVITY_SNAP_DEG] of it when
     * that moves its faces by no more than [LEVEL_SNAP_RMS_M].
     */
    private fun fit(faces: FaceGeometry, label: IntArray, id: Int): Plane {
        var a = 0.0
        var cx = 0.0
        var cy = 0.0
        var cz = 0.0
        var mx = 0.0
        var my = 0.0
        var mz = 0.0
        for (t in 0 until faces.count) {
            if (label[t] != id) continue
            val w = faces.areas[t].toDouble() + AREA_EPSILON
            a += w
            cx += faces.centroids[t * 3] * w
            cy += faces.centroids[t * 3 + 1] * w
            cz += faces.centroids[t * 3 + 2] * w
            mx += faces.normals[t * 3] * w
            my += faces.normals[t * 3 + 1] * w
            mz += faces.normals[t * 3 + 2] * w
        }
        cx /= a
        cy /= a
        cz /= a
        val cov = DoubleArray(9)
        for (t in 0 until faces.count) {
            if (label[t] != id) continue
            val w = faces.areas[t].toDouble() + AREA_EPSILON
            val dx = faces.centroids[t * 3] - cx
            val dy = faces.centroids[t * 3 + 1] - cy
            val dz = faces.centroids[t * 3 + 2] - cz
            cov[0] += w * dx * dx
            cov[1] += w * dx * dy
            cov[2] += w * dx * dz
            cov[4] += w * dy * dy
            cov[5] += w * dy * dz
            cov[8] += w * dz * dz
        }
        cov[3] = cov[1]
        cov[6] = cov[2]
        cov[7] = cov[5]
        var normal = smallestEigenvector(cov)
        if (normal[0] * mx + normal[1] * my + normal[2] * mz < 0) normal = doubleArrayOf(-normal[0], -normal[1], -normal[2])
        // Floors, ceilings and worktops are level, walls are plumb: snap the near misses.
        val level = cos(Math.toRadians(GRAVITY_SNAP_DEG))
        val plumb = kotlin.math.sin(Math.toRadians(GRAVITY_SNAP_DEG))
        if (abs(normal[1]) >= level) {
            // Levelled about its centroid, a face (dx, dz) away from it moves by (nx dx + nz dz) / ny.
            val shift = normal[0] * normal[0] * cov[0] + 2.0 * normal[0] * normal[2] * cov[2] + normal[2] * normal[2] * cov[8]
            if (sqrt(shift.coerceAtLeast(0.0) / a) / abs(normal[1]) <= LEVEL_SNAP_RMS_M) {
                normal = doubleArrayOf(0.0, if (normal[1] > 0) 1.0 else -1.0, 0.0)
            }
        } else if (abs(normal[1]) <= plumb) {
            val h = sqrt(normal[0] * normal[0] + normal[2] * normal[2])
            normal = doubleArrayOf(normal[0] / h, 0.0, normal[2] / h)
        }
        val offset = normal[0] * cx + normal[1] * cy + normal[2] * cz
        return Plane(normal[0].toFloat(), normal[1].toFloat(), normal[2].toFloat(), offset.toFloat())
    }

    /**
     * Gives a plane the faces the snap laid on it: a face no region took whose three vertices
     * all lie on its neighbour's plane now. The smoothing tips a face at the rim of a hole past
     * [ABSORB_ANGLE_DEG]; its vertices, shared with the plane's faces, go onto the plane all the
     * same, and the face with them. Left off the plane, it would keep the hole from closing on it.
     */
    private fun adopt(mesh: WorkMesh, topology: MeshTopology, label: IntArray, planes: List<Plane>) {
        val p = mesh.positions
        val idx = mesh.indices
        val queue = IntArray(label.size)
        var tail = 0
        for (t in label.indices) if (label[t] >= 0) queue[tail++] = t
        var head = 0
        while (head < tail) {
            val t = queue[head++]
            val plane = planes[label[t]]
            for (k in 0 until 3) {
                val u = topology.neighbours[t * 3 + k]
                if (u < 0 || label[u] >= 0) continue
                var on = true
                for (c in 0 until 3) {
                    val v = idx[u * 3 + c] * 3
                    if (abs(plane.distance(p[v], p[v + 1], p[v + 2])) > ON_PLANE_M) on = false
                }
                if (!on) continue
                label[u] = label[t]
                queue[tail++] = u
            }
        }
    }

    /**
     * Gives the faces no region took — noise spikes inside a wall, its last centimetres before
     * a corner — to the plane next to them when they lie close to it, so the wall flattens
     * whole instead of keeping pock-marks.
     */
    private fun absorb(topology: MeshTopology, faces: FaceGeometry, label: IntArray, planes: List<Plane>) {
        val minCos = cos(Math.toRadians(ABSORB_ANGLE_DEG)).toFloat()
        val queue = IntArray(faces.count)
        var tail = 0
        for (t in 0 until faces.count) if (label[t] >= 0) queue[tail++] = t
        var head = 0
        while (head < tail) {
            val t = queue[head++]
            val plane = planes[label[t]]
            for (k in 0 until 3) {
                val u = topology.neighbours[t * 3 + k]
                if (u < 0 || label[u] >= 0) continue
                val facing = faces.normals[u * 3] * plane.nx + faces.normals[u * 3 + 1] * plane.ny +
                    faces.normals[u * 3 + 2] * plane.nz
                // A face turned over (|facing| near 1, negative) lying in the plane is the scan's noise
                // folding over itself: it belongs to the plane, and unfolds there.
                if (abs(facing) < minCos) continue
                val d = plane.distance(faces.centroids[u * 3], faces.centroids[u * 3 + 1], faces.centroids[u * 3 + 2])
                if (abs(d) > ABSORB_DISTANCE_M) continue
                label[u] = label[t]
                queue[tail++] = u
            }
        }
    }

    /**
     * Groups planes within [MERGE_ANGLE_DEG] and [MERGE_DISTANCE_M] of a group's largest: the new
     * id of each, the count, and each group's anchor plane ([Merge]). Largest first, so each group is anchored on its widest sheet, and
     * a plane joins the nearest group that takes it, measured from where the plane's faces lie to
     * the anchor's plane. Past [FREE_MERGE_M] from the anchor, the group's faces, all of them, must
     * cover [MIN_SHARE] of the plane's faces ([Footprint.share]): a wall's second pass overlaps
     * the wall, while a TV or a door's leaf stands where the wall has no faces at all. Against the
     * group, not against one sheet of it: a door within a centimetre of a second pass beside it is
     * no closer to the wall for that.
     */
    @Suppress("NestedBlockDepth")
    private fun mergeCoplanar(faces: FaceGeometry, label: IntArray, planes: List<Plane>): Merge {
        val byPlane = Members(label, planes.size)
        // Each plane's area, and where its faces lie on average: a gap is measured there, as
        // [gather] measures it, not between offsets — two planes a degree apart can share an
        // offset and stand 3.5 cm apart 2 m from the origin.
        val area = FloatArray(planes.size)
        val centre = FloatArray(planes.size * 3)
        for (t in label.indices) {
            val p = label[t]
            if (p < 0) continue
            val w = faces.areas[t]
            area[p] += w
            for (axis in 0 until 3) centre[p * 3 + axis] += faces.centroids[t * 3 + axis] * w
        }
        for (p in planes.indices) if (area[p] > 0f) for (axis in 0 until 3) centre[p * 3 + axis] /= area[p]
        val minCos = cos(Math.toRadians(MERGE_ANGLE_DEG)).toFloat()
        val group = IntArray(planes.size) { -1 }
        val anchors = ArrayList<Int>()
        val grids = ArrayList<Grid>()
        val covered = ArrayList<Footprint>()
        fun cells(g: Int, plane: Int): Footprint =
            grids[g].footprint(faces, byPlane.members, byPlane.start[plane], byPlane.start[plane + 1])
        for (i in planes.indices.sortedByDescending { area[it] }) {
            val q = planes[i]
            var best = -1
            var bestGap = 0f
            for (g in anchors.indices) {
                val p = planes[anchors[g]]
                val facing = p.nx * q.nx + p.ny * q.ny + p.nz * q.nz
                val gap = abs(p.distance(centre[i * 3], centre[i * 3 + 1], centre[i * 3 + 2]))
                if (facing < minCos || gap > MERGE_DISTANCE_M) continue
                if (best >= 0 && gap >= bestGap) continue
                if (gap > FREE_MERGE_M && covered[g].share(cells(g, i)) < MIN_SHARE) continue
                best = g
                bestGap = gap
            }
            if (best < 0) {
                best = anchors.size
                anchors += i
                grids += Grid(q)
                covered += cells(best, i)
            } else {
                covered[best] = covered[best] + cells(best, i)
            }
            group[i] = best
        }
        // Numbered as the planes come, as before any merge: a plane on its own keeps its id.
        val id = IntArray(planes.size) { -1 }
        val groupId = IntArray(anchors.size) { -1 }
        var count = 0
        for (i in planes.indices) {
            val g = group[i]
            if (groupId[g] < 0) groupId[g] = count++
            id[i] = groupId[g]
        }
        val anchorOf = IntArray(count)
        for (g in anchors.indices) anchorOf[groupId[g]] = anchors[g]
        return Merge(id, count, List(count) { planes[anchorOf[it]] })
    }

    /** [mergeCoplanar]'s groups: each plane's new [id], their [count], and each group's [anchors] plane. */
    private class Merge(val id: IntArray, val count: Int, val anchors: List<Plane>)

    /**
     * The cells of [FOOTPRINT_CELL_M] a set of faces lies in, sorted, without repeats, and how many
     * face centroids each holds.
     */
    private class Footprint(val cells: LongArray, val counts: IntArray) {
        /** This and [other] together: a cell both hold counts the centroids of both. */
        operator fun plus(other: Footprint): Footprint {
            val a = cells
            val b = other.cells
            val keys = LongArray(a.size + b.size)
            val sums = IntArray(a.size + b.size)
            var i = 0
            var j = 0
            var n = 0
            while (i < a.size || j < b.size) {
                when {
                    j >= b.size || (i < a.size && a[i] < b[j]) -> {
                        keys[n] = a[i]
                        sums[n] = counts[i++]
                    }
                    i >= a.size || b[j] < a[i] -> {
                        keys[n] = b[j]
                        sums[n] = other.counts[j++]
                    }
                    else -> {
                        keys[n] = a[i]
                        sums[n] = counts[i++] + other.counts[j++]
                    }
                }
                n++
            }
            return Footprint(keys.copyOf(n), sums.copyOf(n))
        }

        /**
         * The share of [own]'s face centroids this footprint covers. A centroid is covered when
         * each of the four windows of 2×2 cells around its cell holds [MIN_CELL_CENTROIDS] of this
         * footprint's: the wall lies all round it, within a window's width, whichever way a grid
         * would fall. A hole a scan leaves in a wall, narrower than a window, is covered; a frame
         * hung off the wall is covered along its rim alone, at most a window's width in. One grid
         * of windows alone covered a postcard frame whole when its edges fell inside its cells.
         *
         * Weighed by [own]'s centroids, not by its cells: only the frame's rim lies where its wall
         * does, so the frame reads covered for that rim alone.
         */
        fun share(own: Footprint): Float {
            var total = 0L
            var shared = 0L
            for (i in own.cells.indices) {
                total += own.counts[i]
                if (covers(own.cells[i])) shared += own.counts[i]
            }
            return if (total == 0L) 0f else shared.toFloat() / total
        }

        private fun covers(cell: Long): Boolean {
            val u = (cell shr Int.SIZE_BITS).toInt()
            val v = cell.toInt()
            for (du in 0..1) {
                for (dv in 0..1) {
                    var held = 0
                    for (a in 0..1) for (b in 0..1) held += count(u - du + a, v - dv + b)
                    if (held < MIN_CELL_CENTROIDS) return false
                }
            }
            return true
        }

        private fun count(u: Int, v: Int): Int {
            val i = cells.binarySearch(key(u, v))
            return if (i >= 0) counts[i] else 0
        }
    }

    private fun key(u: Int, v: Int): Long = (u.toLong() shl Int.SIZE_BITS) or (v.toLong() and LOW_INT)

    /** The faces of each group — a region, a plane — contiguous: a counting sort on the group id, -1 left out. */
    private class Members(group: IntArray, count: Int) {
        val start = IntArray(count + 1)
        val members: IntArray

        init {
            for (g in group) if (g >= 0) start[g + 1]++
            for (r in 0 until count) start[r + 1] += start[r]
            members = IntArray(start[count])
            val fill = start.copyOf(count)
            for (t in group.indices) {
                val g = group[t]
                if (g >= 0) members[fill[g]++] = t
            }
        }
    }

    /** Cells of [FOOTPRINT_CELL_M] across [plane]: where on it a set of faces lies. */
    private class Grid(plane: Plane) {
        private val ux: Float
        private val uy: Float
        private val uz: Float
        private val vx: Float
        private val vy: Float
        private val vz: Float

        init {
            // u across the normal, away from whichever world axis the normal is closest to; v = n × u.
            val (ax, ay, az) = if (abs(plane.ny) < LEVEL_NORMAL_Y) Triple(0f, 1f, 0f) else Triple(1f, 0f, 0f)
            var x = ay * plane.nz - az * plane.ny
            var y = az * plane.nx - ax * plane.nz
            var z = ax * plane.ny - ay * plane.nx
            val length = sqrt(x * x + y * y + z * z)
            x /= length
            y /= length
            z /= length
            ux = x
            uy = y
            uz = z
            vx = plane.ny * z - plane.nz * y
            vy = plane.nz * x - plane.nx * z
            vz = plane.nx * y - plane.ny * x
        }

        /** The cells the centroids of faces `members[from until to]` fall in, and how many fall in each. */
        fun footprint(faces: FaceGeometry, members: IntArray, from: Int, to: Int): Footprint {
            val keys = LongArray(to - from)
            val c = faces.centroids
            for (i in from until to) {
                val t = members[i] * 3
                val u = floor((ux * c[t] + uy * c[t + 1] + uz * c[t + 2]) / FOOTPRINT_CELL_M).toInt()
                val v = floor((vx * c[t] + vy * c[t + 1] + vz * c[t + 2]) / FOOTPRINT_CELL_M).toInt()
                keys[i - from] = key(u, v)
            }
            keys.sort()
            val counts = IntArray(keys.size)
            var cells = 0
            for (i in keys.indices) {
                if (i == 0 || keys[i] != keys[i - 1]) keys[cells++] = keys[i]
                counts[cells - 1]++
            }
            return Footprint(keys.copyOf(cells), counts.copyOf(cells))
        }
    }

    /** Moves each vertex of a flat region onto its plane, its two planes' line or three planes' corner. */
    @Suppress("LongParameterList")
    private fun snap(
        mesh: WorkMesh,
        topology: MeshTopology,
        faces: FaceGeometry,
        label: IntArray,
        planes: List<Plane>,
        maxSnap: Float,
    ): Int {
        val p = mesh.positions
        val ids = IntArray(MAX_PLANES_AT_VERTEX)
        val weight = FloatArray(MAX_PLANES_AT_VERTEX)
        // What may slide afterwards: on one plane (1), along two planes' line (2), nothing (0).
        val slide = ByteArray(mesh.vertexCount)
        val slideA = IntArray(mesh.vertexCount)
        val slideB = IntArray(mesh.vertexCount)
        // The rim of a scan's hole holds still: relaxed, a border vertex is pulled inwards by its
        // neighbours, all on one side, and the lace opens a little more each round.
        val border = BooleanArray(mesh.vertexCount)
        for (c in topology.neighbours.indices) {
            if (topology.neighbours[c] >= 0) continue
            border[mesh.indices[c]] = true
            border[mesh.indices[c - c % 3 + (c % 3 + 1) % 3]] = true
        }
        var snapped = 0
        for (v in 0 until mesh.vertexCount) {
            var count = 0
            var free = false
            for (i in topology.vertexStart[v] until topology.vertexStart[v + 1]) {
                val t = topology.vertexTriangles[i]
                val id = label[t]
                if (id < 0) {
                    free = true
                    continue
                }
                var slot = -1
                for (s in 0 until count) if (ids[s] == id) slot = s
                if (slot < 0 && count < MAX_PLANES_AT_VERTEX) {
                    slot = count++
                    ids[slot] = id
                    weight[slot] = 0f
                }
                if (slot >= 0) weight[slot] += faces.areas[t] + AREA_EPSILON.toFloat()
            }
            if (count == 0) continue
            sortByWeight(ids, weight, count)
            val x = p[v * 3]
            val y = p[v * 3 + 1]
            val z = p[v * 3 + 2]
            val lineTarget = if (count == 2) line(planes[ids[0]], planes[ids[1]], x, y, z) else null
            val target = when {
                count >= 3 -> corner(planes[ids[0]], planes[ids[1]], planes[ids[2]])
                    ?: line(planes[ids[0]], planes[ids[1]], x, y, z)
                else -> lineTarget
            } ?: onto(planes[ids[0]], x, y, z)
            val dx = target[0] - x
            val dy = target[1] - y
            val dz = target[2] - z
            if (dx * dx + dy * dy + dz * dz > maxSnap * maxSnap) {
                // Too far for a corner or an edge: the region's own plane is the safe move.
                val fallback = onto(planes[ids[0]], x, y, z)
                val d = planes[ids[0]].distance(x, y, z)
                if (abs(d) > maxSnap) continue
                p[v * 3] = fallback[0]
                p[v * 3 + 1] = fallback[1]
                p[v * 3 + 2] = fallback[2]
            } else {
                p[v * 3] = target[0]
                p[v * 3 + 1] = target[1]
                p[v * 3 + 2] = target[2]
                if (!free && !border[v] && count == 1) {
                    slide[v] = 1
                    slideA[v] = ids[0]
                } else if (!free && !border[v] && count == 2 && lineTarget != null) {
                    slide[v] = 2
                    slideA[v] = ids[0]
                    slideB[v] = ids[1]
                }
            }
            snapped++
        }
        relax(mesh, topology, planes, slide, slideA, slideB)
        return snapped
    }

    /**
     * Projected onto its plane, a noisy surface keeps its noise sideways: where the scan folded
     * over itself, a triangle now lies flipped in the plane — a dark speck, z-fighting its
     * neighbours. A few rounds of in-plane averaging (along the line, for a crease) spread the
     * vertices evenly again and unfold them; corners, the rim of each region and the rim of each
     * hole hold still.
     */
    @Suppress("LongParameterList")
    private fun relax(
        mesh: WorkMesh,
        topology: MeshTopology,
        planes: List<Plane>,
        slide: ByteArray,
        slideA: IntArray,
        slideB: IntArray,
    ) {
        val p = mesh.positions
        val idx = mesh.indices
        val next = FloatArray(p.size)
        repeat(RELAX_PASSES) {
            for (v in 0 until mesh.vertexCount) {
                if (slide[v].toInt() == 0) continue
                var sx = 0f
                var sy = 0f
                var sz = 0f
                var count = 0
                for (i in topology.vertexStart[v] until topology.vertexStart[v + 1]) {
                    val t = topology.vertexTriangles[i]
                    for (k in 0 until 3) {
                        val u = idx[t * 3 + k]
                        if (u == v) continue
                        // Along a crease, only the crease's own vertices pull.
                        if (slide[v].toInt() == 2 && (slide[u].toInt() == 1)) continue
                        sx += p[u * 3]
                        sy += p[u * 3 + 1]
                        sz += p[u * 3 + 2]
                        count++
                    }
                }
                if (count == 0) {
                    next[v * 3] = p[v * 3]
                    next[v * 3 + 1] = p[v * 3 + 1]
                    next[v * 3 + 2] = p[v * 3 + 2]
                    continue
                }
                val ax = sx / count
                val ay = sy / count
                val az = sz / count
                val target = if (slide[v].toInt() == 1) {
                    onto(planes[slideA[v]], ax, ay, az)
                } else {
                    line(planes[slideA[v]], planes[slideB[v]], ax, ay, az) ?: floatArrayOf(p[v * 3], p[v * 3 + 1], p[v * 3 + 2])
                }
                next[v * 3] = target[0]
                next[v * 3 + 1] = target[1]
                next[v * 3 + 2] = target[2]
            }
            for (v in 0 until mesh.vertexCount) {
                if (slide[v].toInt() == 0) continue
                p[v * 3] = next[v * 3]
                p[v * 3 + 1] = next[v * 3 + 1]
                p[v * 3 + 2] = next[v * 3 + 2]
            }
        }
    }

    private fun sortByWeight(ids: IntArray, weight: FloatArray, count: Int) {
        for (i in 1 until count) {
            var j = i
            while (j > 0 && weight[j] > weight[j - 1]) {
                val w = weight[j]
                weight[j] = weight[j - 1]
                weight[j - 1] = w
                val id = ids[j]
                ids[j] = ids[j - 1]
                ids[j - 1] = id
                j--
            }
        }
    }

    private fun onto(plane: Plane, x: Float, y: Float, z: Float): FloatArray {
        val d = plane.distance(x, y, z)
        return floatArrayOf(x - plane.nx * d, y - plane.ny * d, z - plane.nz * d)
    }

    /** The nearest point to (x, y, z) on the line where [a] and [b] meet; null when they are near parallel. */
    private fun line(a: Plane, b: Plane, x: Float, y: Float, z: Float): FloatArray? {
        // Direction of the line, then the point of the line nearest to p: solve with the third
        // plane through p perpendicular to the line.
        val dx = a.ny * b.nz - a.nz * b.ny
        val dy = a.nz * b.nx - a.nx * b.nz
        val dz = a.nx * b.ny - a.ny * b.nx
        val length = sqrt(dx * dx + dy * dy + dz * dz)
        if (length < MIN_CREASE_SIN) return null
        val c = Plane(dx / length, dy / length, dz / length, (dx * x + dy * y + dz * z) / length)
        return corner(a, b, c)
    }

    /** Where three planes meet (Cramer's rule); null when they do not meet in one point. */
    private fun corner(a: Plane, b: Plane, c: Plane): FloatArray? {
        val det = a.nx * (b.ny * c.nz - b.nz * c.ny) -
            a.ny * (b.nx * c.nz - b.nz * c.nx) +
            a.nz * (b.nx * c.ny - b.ny * c.nx)
        if (abs(det) < MIN_CORNER_DET) return null
        // p = (d1 (b × c) + d2 (c × a) + d3 (a × b)) / det
        val bcX = b.ny * c.nz - b.nz * c.ny
        val bcY = b.nz * c.nx - b.nx * c.nz
        val bcZ = b.nx * c.ny - b.ny * c.nx
        val caX = c.ny * a.nz - c.nz * a.ny
        val caY = c.nz * a.nx - c.nx * a.nz
        val caZ = c.nx * a.ny - c.ny * a.nx
        val abX = a.ny * b.nz - a.nz * b.ny
        val abY = a.nz * b.nx - a.nx * b.nz
        val abZ = a.nx * b.ny - a.ny * b.nx
        val px = (a.offset * bcX + b.offset * caX + c.offset * abX) / det
        val py = (a.offset * bcY + b.offset * caY + c.offset * abY) / det
        val pz = (a.offset * bcZ + b.offset * caZ + c.offset * abZ) / det
        if (!px.isFinite() || !py.isFinite() || !pz.isFinite()) return null
        return floatArrayOf(px, py, pz)
    }

    /** The eigenvector of the smallest eigenvalue of the symmetric 3×3 [m] (row-major), by Jacobi rotations. */
    internal fun smallestEigenvector(m: DoubleArray): DoubleArray {
        val a = m.copyOf()
        val v = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        repeat(JACOBI_SWEEPS) {
            for ((p, q) in arrayOf(0 to 1, 0 to 2, 1 to 2)) {
                val apq = a[p * 3 + q]
                if (abs(apq) < JACOBI_EPSILON) continue
                val app = a[p * 3 + p]
                val aqq = a[q * 3 + q]
                val theta = (aqq - app) / (2 * apq)
                val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
                val c = 1 / sqrt(t * t + 1)
                val s = t * c
                for (k in 0 until 3) {
                    val akp = a[k * 3 + p]
                    val akq = a[k * 3 + q]
                    a[k * 3 + p] = c * akp - s * akq
                    a[k * 3 + q] = s * akp + c * akq
                }
                for (k in 0 until 3) {
                    val apk = a[p * 3 + k]
                    val aqk = a[q * 3 + k]
                    a[p * 3 + k] = c * apk - s * aqk
                    a[q * 3 + k] = s * apk + c * aqk
                }
                for (k in 0 until 3) {
                    val vkp = v[k * 3 + p]
                    val vkq = v[k * 3 + q]
                    v[k * 3 + p] = c * vkp - s * vkq
                    v[k * 3 + q] = s * vkp + c * vkq
                }
            }
        }
        var best = 0
        for (i in 1 until 3) if (a[i * 3 + i] < a[best * 3 + best]) best = i
        val x = v[best]
        val y = v[3 + best]
        val z = v[6 + best]
        val length = sqrt(x * x + y * y + z * z)
        return doubleArrayOf(x / length, y / length, z / length)
    }

    /** A flat region smaller than this is furniture detail, not a surface to flatten. */
    const val MIN_PLANE_AREA_M2 = 0.25f

    /** A face joins a region when its normal is within this of the region's... */
    const val GROW_ANGLE_DEG = 18f

    /** ...and its centre within this of the region's plane. */
    const val GROW_DISTANCE_M = 0.03f

    /** No vertex moves further than this to reach its plane, edge or corner. */
    const val MAX_SNAP_M = 0.05f

    /** Within this of level or plumb, a plane is made exactly level or plumb. */
    const val GRAVITY_SNAP_DEG = 4.0

    /**
     * A plane near level is made level only when that moves its faces by no more than this, RMS:
     * the tilt a scan's drift gives a level floor. A floor that really slopes moves more — 1° over
     * 4 m is 7 cm end to end — and keeps its slope: levelled about its middle, its high end would
     * sink under the furniture standing on it, and where it meets a level part of the floor it
     * would leave a step. On an even slope the farthest face moves under twice this.
     */
    const val LEVEL_SNAP_RMS_M = 0.004

    /** How far past a plane's own faces a region may lie and still join it: a hole's width or two. */
    private const val GATHER_MARGIN_M = 0.3f
    private const val RELAX_PASSES = 12
    private const val LOW_INT = 0xFFFFFFFFL
    private const val ABSORB_ANGLE_DEG = 45.0
    private const val ABSORB_DISTANCE_M = 0.02f

    /** A vertex this close to a plane lies on it: the snap puts it there to float precision. */
    private const val ON_PLANE_M = 1e-4f
    private const val MERGE_ANGLE_DEG = 5.0

    /**
     * Two planes facing the same way this close are one surface: a wall a pass of drifted depth
     * left doubled a few centimetres behind itself, under two voxels apart — closer than the
     * TSDF can tell a real panel from its wall.
     */
    private const val MERGE_DISTANCE_M = 0.045f

    /**
     * Two sheets of one wall this close join on distance alone: a wall's patches, fitted apart,
     * differ by under a centimetre; a TV stands 3 or more off its wall.
     */
    private const val FREE_MERGE_M = 0.02f

    /** Past [FREE_MERGE_M], how much of a sheet's faces its wall must cover for the two to be one. */
    private const val MIN_SHARE = 0.6f

    /**
     * The footprint's cell, half a window of [Footprint.share]: windows 10 cm wide, wider than the
     * holes a scan leaves in a wall, much narrower than a TV, and four of them round each cell, so
     * a frame of postcard or A4 size 3 cm off its wall reads covered along its rim alone,
     * whichever way the grid falls on it. One 10 cm grid covered two of its four offsets whole;
     * 5 cm cells alone uncovered a wall's second pass through its holes.
     */
    private const val FOOTPRINT_CELL_M = 0.05f

    /** The face centroids that make a window covered ([Footprint.share]): one may be a stray face. */
    private const val MIN_CELL_CENTROIDS = 2
    private const val LEVEL_NORMAL_Y = 0.9f
    private const val MAX_PLANES_AT_VERTEX = 6
    private const val MIN_CREASE_SIN = 0.34f // planes meeting at under ~20° do not make a crease
    private const val MIN_CORNER_DET = 0.2f
    private const val AREA_EPSILON = 1e-9
    private const val JACOBI_SWEEPS = 12
    private const val JACOBI_EPSILON = 1e-18
}
