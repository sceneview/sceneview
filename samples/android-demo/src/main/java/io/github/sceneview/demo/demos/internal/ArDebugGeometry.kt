package io.github.sceneview.demo.demos.internal

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * [ArDebugFrame] → triangles, for the Rerun demo's in-app 3D view (#3950).
 *
 * Pure: positions and indices in plain arrays, no Filament, so the JVM tests pin exactly what the
 * GPU receives. The view uploads each [DebugLayer] as one renderable with one flat unlit colour —
 * SceneView has no per-vertex-colour material, so a gradient (the trail fading from its tail to
 * its head) is several layers, each one step of the ramp.
 *
 * Everything is real geometry, not GL lines: a hairline is one pixel on a phone's 400+ dpi screen
 * (#3397) and reads as noise. Widths are given in **screen pixels** and converted with the
 * [ArDebugStyle.metresPerPixel] of the current orbit distance, which is what keeps a point a
 * point and a line a line at any zoom — the Rerun viewer's look.
 */

/** A growable triangle list: xyz positions and triangle indices. */
class DebugMesh(initialVertices: Int = 64) {
    var positions = FloatArray(initialVertices * 3)
        private set
    var indices = IntArray(initialVertices * 3)
        private set
    var vertexCount = 0
        private set
    var indexCount = 0
        private set

    val triangleCount: Int get() = indexCount / 3
    val isEmpty: Boolean get() = indexCount == 0

    fun clear() {
        vertexCount = 0
        indexCount = 0
    }

    fun vertex(x: Float, y: Float, z: Float): Int {
        if ((vertexCount + 1) * 3 > positions.size) positions = positions.copyOf(max(positions.size * 2, 48))
        positions[vertexCount * 3] = x
        positions[vertexCount * 3 + 1] = y
        positions[vertexCount * 3 + 2] = z
        return vertexCount++
    }

    fun vertex(v: Vec3): Int = vertex(v.x, v.y, v.z)

    /**
     * Texture coordinates, two per vertex, for the textured layers (photos, the colour atlas).
     * Only meaningful when every vertex of the mesh went through the five-argument [vertex].
     */
    var uvs = FloatArray(initialVertices * 2)
        private set

    /** A vertex with texture coordinates ([u], [v]); `v = 0` is the image's top row. */
    fun vertex(x: Float, y: Float, z: Float, u: Float, v: Float): Int {
        val i = vertex(x, y, z)
        if ((i + 1) * 2 > uvs.size) uvs = uvs.copyOf(max(uvs.size * 2, (i + 1) * 2))
        uvs[i * 2] = u
        uvs[i * 2 + 1] = v
        return i
    }

    fun triangle(a: Int, b: Int, c: Int) {
        if (indexCount + 3 > indices.size) indices = indices.copyOf(max(indices.size * 2, 48))
        indices[indexCount++] = a
        indices[indexCount++] = b
        indices[indexCount++] = c
    }

    fun quad(a: Int, b: Int, c: Int, d: Int) {
        triangle(a, b, c)
        triangle(a, c, d)
    }

    /** Axis-aligned bounds of the vertices, `[minX, minY, minZ, maxX, maxY, maxZ]`, or null. */
    fun bounds(): FloatArray? {
        if (vertexCount == 0) return null
        val b = FloatArray(6) { if (it < 3) Float.MAX_VALUE else -Float.MAX_VALUE }
        for (i in 0 until vertexCount) {
            for (axis in 0..2) {
                val v = positions[i * 3 + axis]
                if (v < b[axis]) b[axis] = v
                if (v > b[axis + 3]) b[axis + 3] = v
            }
        }
        return b
    }
}

/**
 * Every layer the view draws, back to front in intent. Each is one renderable with one colour
 * (see `ArDebugPalette` in the view); the layer toggles in the legend switch whole groups.
 */
enum class DebugLayer(val group: DebugGroup) {
    GridMinor(DebugGroup.Stage),
    GridMajor(DebugGroup.Stage),
    AxisX(DebugGroup.Stage),
    AxisY(DebugGroup.Stage),
    AxisZ(DebugGroup.Stage),
    PlaneFloor(DebugGroup.Planes),
    PlaneWall(DebugGroup.Planes),
    PlaneOther(DebugGroup.Planes),
    OutlineFloor(DebugGroup.Planes),
    OutlineWall(DebugGroup.Planes),
    OutlineOther(DebugGroup.Planes),
    MapPoints(DebugGroup.Points),
    LivePoints(DebugGroup.Points),
    Trail0(DebugGroup.Trail),
    Trail1(DebugGroup.Trail),
    Trail2(DebugGroup.Trail),
    Trail3(DebugGroup.Trail),
    Trail4(DebugGroup.Trail),
    Trail5(DebugGroup.Trail),
    Trail6(DebugGroup.Trail),
    Trail7(DebugGroup.Trail),
    TrailHead(DebugGroup.Trail),
    Keyframes(DebugGroup.Trail),
    Frustum(DebugGroup.Trail),
    FrustumFace(DebugGroup.Trail),
    Anchors(DebugGroup.Anchors);

    companion object {
        /** The trail's gradient steps, tail first. */
        val trailSteps: List<DebugLayer> = listOf(Trail0, Trail1, Trail2, Trail3, Trail4, Trail5, Trail6, Trail7)
    }
}

/** What the legend toggles. [Stage] (grid, axes) is always on. */
enum class DebugGroup { Stage, Trail, Points, Planes, Anchors }

/**
 * Sizes, in screen pixels, turned into metres through [metresPerPixel] — the world size of one
 * pixel at the orbit target's distance.
 */
data class ArDebugStyle(
    val metresPerPixel: Float,
    /** How much thicker the camera path is drawn than its default, bounds included. */
    val trailWeight: Float = 1f,
) {
    private fun px(pixels: Float, minMetres: Float, maxMetres: Float) =
        (pixels * metresPerPixel).coerceIn(minMetres, maxMetres)

    val mapPointRadius get() = px(2.4f, 0.004f, 0.05f)
    val livePointRadius get() = px(3.8f, 0.006f, 0.07f)
    val trailRadius get() = px(2.2f * trailWeight, 0.004f * trailWeight, 0.05f * trailWeight)
    val trailHeadRadius get() = px(3.4f * trailWeight, 0.006f * trailWeight, 0.08f * trailWeight)
    val frustumEdge get() = px(1.3f, 0.002f, 0.03f)
    val keyframeEdge get() = px(0.9f, 0.0015f, 0.02f)
    val outlineHalfWidth get() = px(1.3f, 0.002f, 0.03f)
    val gridMinorHalfWidth get() = px(0.55f, 0.001f, 0.015f)
    val gridMajorHalfWidth get() = px(0.9f, 0.0015f, 0.025f)
    val axisRadius get() = px(1.6f, 0.003f, 0.04f)
    val anchorHalfWidth get() = px(1.8f, 0.003f, 0.04f)

    companion object {
        /**
         * The style for an orbit at [distance] seen through a [verticalFovDegrees] lens on a
         * [viewportHeightPx]-tall view, **quantised** to steps of ×[BUCKET]: every step rebuilds
         * the meshes, so the style must not change on every frame of a pinch.
         */
        fun forOrbit(distance: Float, verticalFovDegrees: Double, viewportHeightPx: Int): ArDebugStyle {
            val raw = CameraRig.worldPerPixel(distance, verticalFovDegrees, viewportHeightPx)
            if (raw <= 0f || !raw.isFinite()) return ArDebugStyle(0.002f)
            val step = floor(kotlin.math.ln(raw) / kotlin.math.ln(BUCKET))
            return ArDebugStyle(Math.pow(BUCKET.toDouble(), step.toDouble()).toFloat())
        }

        const val BUCKET = 1.25f
    }
}

/** Frustum shape: a portrait phone camera, drawn [depth] metres deep. */
object DebugFrustum {
    const val DEPTH = 0.22f
    const val KEYFRAME_DEPTH = 0.12f

    /** Half extents of the image plane at depth 1: a ~62° × 50° portrait field of view. */
    const val HALF_WIDTH_PER_DEPTH = 0.46f
    const val HALF_HEIGHT_PER_DEPTH = 0.60f
}

/** Builds each layer's triangles for one [ArDebugFrame]. */
@Suppress("TooManyFunctions") // one small builder per layer, kept together on purpose
object ArDebugGeometry {

    // ---------------------------------------------------------------------------------------
    // Primitives
    // ---------------------------------------------------------------------------------------

    /**
     * A point as a small tetrahedron — four vertices, four faces, readable from every angle, and
     * light enough for ten thousand of them (a sphere would be ten times the triangles for a dot
     * that is three pixels wide).
     */
    fun addTetra(mesh: DebugMesh, x: Float, y: Float, z: Float, r: Float) {
        val s = r * 0.94f
        val a = mesh.vertex(x + s, y + s, z + s)
        val b = mesh.vertex(x + s, y - s, z - s)
        val c = mesh.vertex(x - s, y + s, z - s)
        val d = mesh.vertex(x - s, y - s, z + s)
        mesh.triangle(a, b, c)
        mesh.triangle(a, d, b)
        mesh.triangle(a, c, d)
        mesh.triangle(b, d, c)
    }

    /** A point as an octahedron — rounder than [addTetra], for the few bright live points. */
    fun addOcta(mesh: DebugMesh, x: Float, y: Float, z: Float, r: Float) {
        val px = mesh.vertex(x + r, y, z)
        val nx = mesh.vertex(x - r, y, z)
        val py = mesh.vertex(x, y + r, z)
        val ny = mesh.vertex(x, y - r, z)
        val pz = mesh.vertex(x, y, z + r)
        val nz = mesh.vertex(x, y, z - r)
        mesh.triangle(py, pz, px); mesh.triangle(py, px, nz); mesh.triangle(py, nz, nx); mesh.triangle(py, nx, pz)
        mesh.triangle(ny, px, pz); mesh.triangle(ny, nz, px); mesh.triangle(ny, nx, nz); mesh.triangle(ny, pz, nx)
    }

    /** A thick line: a square prism from [a] to [b], [radius] in half-width. Nothing if a == b. */
    fun addSegment(mesh: DebugMesh, a: Vec3, b: Vec3, radius: Float) {
        val axis = b - a
        val length = axis.length()
        if (length < 1e-6f) return
        val dir = axis * (1f / length)
        val (u, v) = perpendiculars(dir)
        val su = u * radius
        val sv = v * radius
        val base = mesh.vertexCount
        for (end in listOf(a, b)) {
            mesh.vertex(end + su + sv)
            mesh.vertex(end - su + sv)
            mesh.vertex(end - su - sv)
            mesh.vertex(end + su - sv)
        }
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            mesh.quad(base + i, base + j, base + 4 + j, base + 4 + i)
        }
    }

    /**
     * A flat strip from [a] to [b] lying in the plane of normal [normal], [halfWidth] each side,
     * lengthened by [halfWidth] at both ends so consecutive strips close their corners.
     */
    fun addRibbon(mesh: DebugMesh, a: Vec3, b: Vec3, normal: Vec3, halfWidth: Float) {
        val axis = b - a
        val length = axis.length()
        if (length < 1e-6f) return
        val dir = axis * (1f / length)
        var side = normal.cross(dir)
        if (side.length() < 1e-6f) side = perpendiculars(dir).first
        side = side.normalized() * halfWidth
        val a2 = a - dir * halfWidth
        val b2 = b + dir * halfWidth
        mesh.quad(mesh.vertex(a2 - side), mesh.vertex(b2 - side), mesh.vertex(b2 + side), mesh.vertex(a2 + side))
    }

    /**
     * A tube along points [from] until [until] of the flat [path], [sides]-gon section, with
     * parallel-transported frames so it never twists at a turn. Consecutive points closer than a
     * millimetre are skipped (a zero-length segment has no direction).
     */
    fun addTube(mesh: DebugMesh, path: FloatArray, from: Int, until: Int, radius: Float, sides: Int = 6) {
        val pts = ArrayList<Vec3>(until - from)
        for (i in from until until) {
            val p = Vec3(path[i * 3], path[i * 3 + 1], path[i * 3 + 2])
            if (pts.isEmpty() || (p - pts.last()).length() > 1e-3f) pts.add(p)
        }
        if (pts.size < 2) return
        var tangent = (pts[1] - pts[0]).normalized()
        var normal = perpendiculars(tangent).first
        val rings = pts.size
        val base = mesh.vertexCount
        for (i in 0 until rings) {
            val next = if (i < rings - 1) (pts[i + 1] - pts[i]).normalized() else tangent
            val t = if (i == 0) next else (tangent + next).normalized().takeIf { it.length() > 0.5f } ?: next
            // Parallel transport: remove from the previous normal its component along the new
            // tangent, which rotates the frame by the minimum needed to follow the curve.
            normal = (normal - t * normal.dot(t)).normalized().takeIf { it.length() > 0.5f } ?: perpendiculars(t).first
            val binormal = t.cross(normal)
            for (s in 0 until sides) {
                val angle = (2.0 * Math.PI * s / sides)
                val offset = normal * (cos(angle).toFloat() * radius) + binormal * (sin(angle).toFloat() * radius)
                mesh.vertex(pts[i] + offset)
            }
            tangent = next
        }
        for (i in 0 until rings - 1) {
            for (s in 0 until sides) {
                val s2 = (s + 1) % sides
                val ring = base + i * sides
                val nextRing = ring + sides
                mesh.quad(ring + s, ring + s2, nextRing + s2, nextRing + s)
            }
        }
        // Caps, so the tube does not look hollow when orbited end-on.
        for (end in listOf(0, rings - 1)) {
            val centre = mesh.vertex(pts[end])
            for (s in 0 until sides) mesh.triangle(centre, base + end * sides + s, base + end * sides + (s + 1) % sides)
        }
    }

    /** A convex polygon (flat xyz) filled as a fan around its centroid. */
    fun addFan(mesh: DebugMesh, polygon: FloatArray) {
        val n = polygon.size / 3
        if (n < 3) return
        var cx = 0f
        var cy = 0f
        var cz = 0f
        for (i in 0 until n) {
            cx += polygon[i * 3]; cy += polygon[i * 3 + 1]; cz += polygon[i * 3 + 2]
        }
        val centre = mesh.vertex(cx / n, cy / n, cz / n)
        val first = mesh.vertexCount
        for (i in 0 until n) mesh.vertex(polygon[i * 3], polygon[i * 3 + 1], polygon[i * 3 + 2])
        for (i in 0 until n) mesh.triangle(centre, first + i, first + (i + 1) % n)
    }

    /** The outline of a polygon (flat xyz) as ribbons lying in its own plane. */
    fun addOutline(mesh: DebugMesh, polygon: FloatArray, halfWidth: Float) {
        val n = polygon.size / 3
        if (n < 2) return
        val normal = polygonNormal(polygon)
        for (i in 0 until n) {
            val j = (i + 1) % n
            addRibbon(
                mesh,
                Vec3(polygon[i * 3], polygon[i * 3 + 1], polygon[i * 3 + 2]),
                Vec3(polygon[j * 3], polygon[j * 3 + 1], polygon[j * 3 + 2]),
                normal,
                halfWidth,
            )
        }
    }

    /** A camera frustum at [pose]: four side edges and the image-plane rectangle. */
    fun addFrustumEdges(
        mesh: DebugMesh,
        pose: DebugPose,
        depth: Float,
        edge: Float,
        lens: ReplayLens = ReplayLens.Default,
    ) {
        val apex = pose.position
        val corners = frustumCorners(pose, depth, lens)
        for (i in 0 until 4) {
            addSegment(mesh, apex, corners[i], edge)
            addSegment(mesh, corners[i], corners[(i + 1) % 4], edge)
        }
        // A small "up" tick over the top edge — which way the phone was held, at a glance.
        val topMid = (corners[0] + corners[1]) * 0.5f
        val up = pose.rotate(0f, 1f, 0f)
        addSegment(mesh, topMid, topMid + up * (depth * 0.18f), edge)
    }

    /** The image plane of the frustum at [pose], as two triangles. */
    fun addFrustumFace(mesh: DebugMesh, pose: DebugPose, depth: Float, lens: ReplayLens = ReplayLens.Default) {
        val c = frustumCorners(pose, depth, lens)
        mesh.quad(mesh.vertex(c[0]), mesh.vertex(c[1]), mesh.vertex(c[2]), mesh.vertex(c[3]))
    }

    /** Corners of the image plane at [depth]: top-left, top-right, bottom-right, bottom-left. */
    fun frustumCorners(pose: DebugPose, depth: Float, lens: ReplayLens = ReplayLens.Default): List<Vec3> {
        val hw = lens.halfWidthPerDepth * depth
        val hh = lens.halfHeightPerDepth * depth
        return listOf(
            pose.transform(-hw, hh, -depth),
            pose.transform(hw, hh, -depth),
            pose.transform(hw, -hh, -depth),
            pose.transform(-hw, -hh, -depth),
        )
    }

    /** A flat ring of [radius] around [centre], in the plane of [normal]. */
    fun addRing(mesh: DebugMesh, centre: Vec3, normal: Vec3, radius: Float, halfWidth: Float, segments: Int = 32) {
        val (u, v) = perpendiculars(normal.normalized())
        val base = mesh.vertexCount
        for (i in 0 until segments) {
            val angle = 2.0 * Math.PI * i / segments
            val dir = u * cos(angle).toFloat() + v * sin(angle).toFloat()
            mesh.vertex(centre + dir * (radius - halfWidth))
            mesh.vertex(centre + dir * (radius + halfWidth))
        }
        for (i in 0 until segments) {
            val j = (i + 1) % segments
            mesh.quad(base + i * 2, base + i * 2 + 1, base + j * 2 + 1, base + j * 2)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /** Two unit vectors perpendicular to the unit [dir] and to each other. */
    fun perpendiculars(dir: Vec3): Pair<Vec3, Vec3> {
        val helper = if (kotlin.math.abs(dir.y) < 0.9f) Vec3.Up else Vec3(1f, 0f, 0f)
        val u = dir.cross(helper).normalized()
        val v = dir.cross(u).normalized()
        return u to v
    }

    /** Newell's normal of a (flat xyz) polygon — robust to collinear first vertices. */
    fun polygonNormal(polygon: FloatArray): Vec3 {
        val n = polygon.size / 3
        var nx = 0f
        var ny = 0f
        var nz = 0f
        for (i in 0 until n) {
            val j = (i + 1) % n
            val x0 = polygon[i * 3]; val y0 = polygon[i * 3 + 1]; val z0 = polygon[i * 3 + 2]
            val x1 = polygon[j * 3]; val y1 = polygon[j * 3 + 1]; val z1 = polygon[j * 3 + 2]
            nx += (y0 - y1) * (z0 + z1)
            ny += (z0 - z1) * (x0 + x1)
            nz += (x0 - x1) * (y0 + y1)
        }
        val normal = Vec3(nx, ny, nz)
        return if (normal.length() < 1e-9f) Vec3.Up else normal.normalized()
    }

    /**
     * Thins a trail (flat xyz) so consecutive kept points are at least [minStep] apart, the last
     * point always kept, and at most about [maxPoints] kept — the step widens for a long walk.
     * A 20 000-pose session would otherwise be a 120 000-vertex tube rebuilt at every frame.
     */
    fun simplifyTrail(trail: FloatArray, minStep: Float, maxPoints: Int): FloatArray {
        val n = trail.size / 3
        if (n <= 2) return trail.copyOf()
        var length = 0f
        for (i in 1 until n) length += dist(trail, i - 1, i)
        val step = max(minStep, length / maxPoints)
        val out = FloatArray(trail.size)
        var count = 0
        fun keep(i: Int) {
            out[count * 3] = trail[i * 3]; out[count * 3 + 1] = trail[i * 3 + 1]; out[count * 3 + 2] = trail[i * 3 + 2]
            count++
        }
        keep(0)
        var last = 0
        for (i in 1 until n - 1) {
            if (dist(trail, last, i) >= step) {
                keep(i)
                last = i
            }
        }
        keep(n - 1)
        return out.copyOf(count * 3)
    }

    /**
     * Splits [count] trail points into [chunks] contiguous ranges that **share** their boundary
     * point, so the gradient steps join without a gap. Fewer points than chunks → fewer ranges,
     * all placed at the head end (the tail colours are the ones that go unused).
     */
    fun trailChunks(count: Int, chunks: Int): List<IntRange?> {
        val out = MutableList<IntRange?>(chunks) { null }
        if (count < 2) return out
        val segments = count - 1
        val used = min(chunks, segments)
        for (k in 0 until used) {
            val from = segments * k / used
            val to = segments * (k + 1) / used
            out[chunks - used + k] = from..to
        }
        return out
    }

    private fun dist(a: FloatArray, i: Int, j: Int): Float {
        val dx = a[i * 3] - a[j * 3]
        val dy = a[i * 3 + 1] - a[j * 3 + 1]
        val dz = a[i * 3 + 2] - a[j * 3 + 2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    // ---------------------------------------------------------------------------------------
    // Layers
    // ---------------------------------------------------------------------------------------

    /** The trail's head section: its last [HEAD_LENGTH_M] metres, drawn thicker and brighter. */
    const val HEAD_LENGTH_M = 0.35f

    /** Maximum points of the simplified trail tube. */
    const val TRAIL_MAX_POINTS = 1200

    /** Trail gradient + head. Writes [DebugLayer.trailSteps] and [DebugLayer.TrailHead]. */
    fun buildTrail(trail: FloatArray, style: ArDebugStyle, out: (DebugLayer) -> DebugMesh) {
        val simplified = simplifyTrail(trail, minStep = 0.01f, maxPoints = TRAIL_MAX_POINTS)
        val n = simplified.size / 3
        val ranges = trailChunks(n, DebugLayer.trailSteps.size)
        DebugLayer.trailSteps.forEachIndexed { k, layer ->
            val range = ranges[k] ?: return@forEachIndexed
            addTube(out(layer), simplified, range.first, range.last + 1, style.trailRadius)
        }
        if (n >= 2) {
            // Walk back from the head until HEAD_LENGTH_M of path is covered.
            var from = n - 1
            var covered = 0f
            while (from > 0 && covered < HEAD_LENGTH_M) {
                covered += dist(simplified, from - 1, from)
                from--
            }
            addTube(out(DebugLayer.TrailHead), simplified, from, n, style.trailHeadRadius, sides = 8)
        }
    }

    /**
     * The keyframe frustums and the live one. The replay passes its real [lens] and deeper
     * frustums, which carry the camera's photos, and drops the tinted [DebugLayer.FrustumFace]
     * ([face]) the photo replaces.
     */
    fun buildCamera(
        frame: ArDebugFrame,
        style: ArDebugStyle,
        out: (DebugLayer) -> DebugMesh,
        lens: ReplayLens = ReplayLens.Default,
        depth: Float = DebugFrustum.DEPTH,
        keyframeDepth: Float = DebugFrustum.KEYFRAME_DEPTH,
        face: Boolean = true,
    ) {
        for (pose in frame.keyframes) {
            addFrustumEdges(out(DebugLayer.Keyframes), pose, keyframeDepth, style.keyframeEdge, lens)
        }
        val camera = frame.camera ?: return
        addFrustumEdges(out(DebugLayer.Frustum), camera, depth, style.frustumEdge, lens)
        if (face) addFrustumFace(out(DebugLayer.FrustumFace), camera, depth, lens)
    }

    fun buildMapPoints(points: FloatArray, style: ArDebugStyle, mesh: DebugMesh) {
        val r = style.mapPointRadius
        for (i in 0 until points.size / 3) addTetra(mesh, points[i * 3], points[i * 3 + 1], points[i * 3 + 2], r)
    }

    fun buildLivePoints(points: FloatArray, style: ArDebugStyle, mesh: DebugMesh) {
        val r = style.livePointRadius
        for (i in 0 until points.size / 3) addOcta(mesh, points[i * 3], points[i * 3 + 1], points[i * 3 + 2], r)
    }

    fun fillLayerOf(kind: DebugPlaneKind) = when (kind) {
        DebugPlaneKind.Floor -> DebugLayer.PlaneFloor
        DebugPlaneKind.Wall -> DebugLayer.PlaneWall
        else -> DebugLayer.PlaneOther
    }

    fun outlineLayerOf(kind: DebugPlaneKind) = when (kind) {
        DebugPlaneKind.Floor -> DebugLayer.OutlineFloor
        DebugPlaneKind.Wall -> DebugLayer.OutlineWall
        else -> DebugLayer.OutlineOther
    }

    /**
     * Fills and outlines; a plane [textured] elsewhere (the replay's photo) keeps its outline only.
     * With a [layering], each is drawn at its own depth — the one its photo is drawn at — so an
     * outline never z-fights its photo, nor a fill its neighbour.
     */
    fun buildPlanes(
        planes: List<DebugPlane>,
        style: ArDebugStyle,
        out: (DebugLayer) -> DebugMesh,
        layering: PlaneLayering? = null,
        textured: (Int) -> Boolean = { false },
    ) {
        for (plane in planes) {
            if (!textured(plane.id)) addFan(out(fillLayerOf(plane.kind)), layering?.fill(plane) ?: plane.polygon)
            val outline = layering?.outline(plane) ?: plane.polygon
            addOutline(out(outlineLayerOf(plane.kind)), outline, style.outlineHalfWidth)
        }
    }

    /** Anchor radius: the ring drawn around each anchor's foot. */
    const val ANCHOR_RING_M = 0.16f

    fun buildAnchors(anchors: List<DebugAnchor>, style: ArDebugStyle, mesh: DebugMesh) {
        for (anchor in anchors) {
            val centre = anchor.pose.position
            val up = anchor.pose.rotate(0f, 1f, 0f)
            addRing(mesh, centre + up * 0.003f, up, ANCHOR_RING_M, style.anchorHalfWidth)
            addRing(mesh, centre + up * 0.003f, up, ANCHOR_RING_M * 0.35f, style.anchorHalfWidth * 0.8f, segments = 20)
        }
    }

    /** Grid cell size: half a metre, a unit that reads as room scale. */
    const val GRID_CELL_M = 0.5f

    /** Every n-th grid line is a major (1 m) line. */
    const val GRID_MAJOR_EVERY = 2

    /** Axis gizmo arm length at the session origin. */
    const val AXIS_LENGTH_M = 0.3f

    /**
     * The floor grid at height [y], covering [bounds] (`[minX, _, minZ, maxX, _, maxZ]`) plus a
     * metre of margin, snapped to [GRID_CELL_M] so it does not crawl as the bounds grow, and the
     * RGB axis gizmo at the world origin — where the session started.
     */
    fun buildStage(bounds: FloatArray, y: Float, style: ArDebugStyle, out: (DebugLayer) -> DebugMesh) {
        val cell = GRID_CELL_M
        val margin = 1f
        val minX = floor((bounds[0] - margin) / cell).toInt()
        val maxX = ceil((bounds[3] + margin) / cell).toInt()
        val minZ = floor((bounds[2] - margin) / cell).toInt()
        val maxZ = ceil((bounds[5] + margin) / cell).toInt()
        val up = Vec3.Up
        for (i in minX..maxX) {
            val major = i % GRID_MAJOR_EVERY == 0
            addRibbon(
                out(if (major) DebugLayer.GridMajor else DebugLayer.GridMinor),
                Vec3(i * cell, y, minZ * cell), Vec3(i * cell, y, maxZ * cell), up,
                if (major) style.gridMajorHalfWidth else style.gridMinorHalfWidth,
            )
        }
        for (k in minZ..maxZ) {
            val major = k % GRID_MAJOR_EVERY == 0
            addRibbon(
                out(if (major) DebugLayer.GridMajor else DebugLayer.GridMinor),
                Vec3(minX * cell, y, k * cell), Vec3(maxX * cell, y, k * cell), up,
                if (major) style.gridMajorHalfWidth else style.gridMinorHalfWidth,
            )
        }
        val o = Vec3.Zero
        addSegment(out(DebugLayer.AxisX), o, Vec3(AXIS_LENGTH_M, 0f, 0f), style.axisRadius)
        addSegment(out(DebugLayer.AxisY), o, Vec3(0f, AXIS_LENGTH_M, 0f), style.axisRadius)
        addSegment(out(DebugLayer.AxisZ), o, Vec3(0f, 0f, AXIS_LENGTH_M), style.axisRadius)
    }

    /**
     * Where the floor is: the lowest upward-facing plane, else 1.3 m under the first camera pose
     * (a phone held at chest height), else 0.
     */
    fun floorHeight(frame: ArDebugFrame): Float {
        val floors = frame.planes.filter { it.kind == DebugPlaneKind.Floor && it.vertexCount >= 3 }
        if (floors.isNotEmpty()) {
            return floors.minOf { plane ->
                (0 until plane.vertexCount).map { plane.polygon[it * 3 + 1] }.average().toFloat()
            }
        }
        if (frame.trail.size >= 3) return frame.trail[1] - 1.3f
        return 0f
    }

    /**
     * Bounds of what the camera should frame: the trail, the planes and the anchors. Points are
     * left out on purpose — a single far outlier would zoom the whole view out.
     */
    fun contentBounds(frame: ArDebugFrame): FloatArray? {
        val b = FloatArray(6) { if (it < 3) Float.MAX_VALUE else -Float.MAX_VALUE }
        var any = false
        fun add(x: Float, y: Float, z: Float) {
            any = true
            b[0] = minOf(b[0], x)
            b[1] = minOf(b[1], y)
            b[2] = minOf(b[2], z)
            b[3] = maxOf(b[3], x)
            b[4] = maxOf(b[4], y)
            b[5] = maxOf(b[5], z)
        }
        for (i in 0 until frame.trail.size / 3) add(frame.trail[i * 3], frame.trail[i * 3 + 1], frame.trail[i * 3 + 2])
        for (plane in frame.planes) for (i in 0 until plane.vertexCount) {
            add(plane.polygon[i * 3], plane.polygon[i * 3 + 1], plane.polygon[i * 3 + 2])
        }
        for (anchor in frame.anchors) add(anchor.pose.x, anchor.pose.y, anchor.pose.z)
        return if (any) b else null
    }
}
