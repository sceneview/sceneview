package io.github.sceneview.demo.demos.internal

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import kotlin.random.Random

class RerunMeshSimplifierTest {
    @Test
    fun `room with an opening and bumps reaches budget without changing topology or bounds`() {
        val input = room(40)
        val result = checkReduction("box-hole-bumps", input, 4_000)
        assertTrue(result.triangleCount in 3_999..4_000)
        assertEquals(boundarySegments(input), boundarySegments(result))
        assertEquals(euler(input), euler(result))
        assertEquals(1f, RerunTsdfTest.faceShare(result) { center, normal ->
            normal.dot(center - Vec3(1f, 0.3f, 1f)) > 0f
        }, 0f)
        assertTrue(result.colors.all { it == 0xFFB08457.toInt() })
        assertArrayEquals(RerunMeshGlb.writeShared(input, targetTriangles = 4_000),
            RerunMeshGlb.writeShared(input, targetTriangles = 4_000))
        parseAndCheck(result, RerunMeshGlb.writeShared(input, targetTriangles = 4_000))
    }

    @Test
    fun `marching cubes fixtures stay valid and reach a smaller budget`() {
        val noise = RerunTsdf()
        val random = Random(4242)
        for (z in 0..20) for (y in 0..20) for (x in 0..20) {
            val border = x == 0 || y == 0 || z == 0 || x == 20 || y == 20 || z == 20
            noise.update(x, y, z, if (border) noise.truncationM else
                (random.nextFloat() - 0.4f) * noise.truncationM, 1f)
        }
        val balls = RerunTsdf()
        fun ball(cx: Int, radius: Float) {
            val reach = radius.toInt() + 4
            for (z in -reach..reach) for (y in -reach..reach) for (x in -reach..reach) {
                balls.update(cx + x, y, z, (sqrt((x*x+y*y+z*z).toFloat()) - radius) * balls.voxelM, 1f)
            }
        }
        ball(0, 12f)
        ball(60, 2f)
        val synthetic = RerunTsdf()
        RerunSyntheticRoom.frames().take(30).forEach { synthetic.integrate(it) }
        val firstThirty = RerunMarchingCubes.extract(synthetic).mesh
        RerunSyntheticRoom.frames().drop(30).forEach { synthetic.integrate(it) }
        val fixtures = listOf(
            "noise-20" to RerunMarchingCubes.extract(noise, minComponentTriangles = 0).mesh,
            "balls-all" to RerunMarchingCubes.extract(balls, minComponentTriangles = 0).mesh,
            "balls-kept" to RerunMarchingCubes.extract(balls).mesh,
            "room-30-frames" to firstThirty,
            "room-all-frames" to RerunMarchingCubes.extract(synthetic).mesh,
        )
        for ((name, mesh) in fixtures) {
            val target = if (name.startsWith("room")) 100_000 else mesh.triangleCount / 2
            val result = checkReduction(name, mesh, target)
            assertTrue("$name: ${result.triangleCount} > $target",
                result.triangleCount <= max(target + 2, (target * 1.05).toInt()))
            if (name.startsWith("balls") || name.startsWith("noise")) {
                RerunTsdfTest.assertClosed(result)
                assertEquals(euler(mesh), euler(result))
                if (name.startsWith("balls")) assertEquals(1f,
                    RerunTsdfTest.faceShare(result) { center, normal ->
                        val ballCenter = if (center.x > 1f) Vec3(60 * RerunTsdf.VOXEL_M, 0f, 0f) else Vec3.Zero
                        normal.dot(center - ballCenter) > 0f
                    }, 0f)
            }
            assertEquals(boundarySegments(mesh), boundarySegments(result))
            parseAndCheck(result, RerunMeshGlb.writeShared(mesh, targetTriangles = target))
        }
    }

    @Test
    fun `strong colour edge retains painted detail and interpolated colours stay in range`() {
        val input = room(28)
        for (v in input.colors.indices) {
            val x = input.positions[v * 3]
            val y = input.positions[v * 3 + 1]
            input.colors[v] = if (x in 0.1f..1.2f && y in -0.1f..0.8f) 0xFF2030D0.toInt() else 0xFFB08457.toInt()
        }
        val result = checkReduction("painted-room", input, 3_000)
        for (shift in 0..16 step 8) {
            val low = input.colors.minOf { (it ushr shift) and 255 }
            val high = input.colors.maxOf { (it ushr shift) and 255 }
            assertTrue(result.colors.all { ((it ushr shift) and 255) in low..high })
        }
        assertTrue(result.colors.any { it == 0xFF2030D0.toInt() })
        // Every original edge spanning the two paints survives in the output at its exact endpoints.
        val inputEdges = edges(input)
        val outputEdges = edges(result).keys.map { edgePositions(result, it) }.toSet()
        for (edge in inputEdges.keys) {
            val a = (edge ushr 32).toInt()
            val b = edge.toInt()
            if (input.colors[a] != input.colors[b]) assertTrue(edgePositions(input, edge) in outputEdges)
        }
    }

    @Test
    fun `invalid and duplicate faces are removed and tetrahedron cannot collapse to duplicate faces`() {
        val tetra = RerunMesh(floatArrayOf(0f,0f,0f, 1f,0f,0f, 0f,1f,0f, 0f,0f,1f),
            FloatArray(12), IntArray(4) { 0xFF808080.toInt() },
            intArrayOf(0,2,1, 0,1,3, 0,3,2, 1,2,3, 0,2,1, 0,0,1))
        val result = RerunMeshSimplifier.simplify(tetra, 0)
        assertEquals(4, result.triangleCount)
        valid(result)
        RerunTsdfTest.assertClosed(result)
        assertEquals(0, RerunMeshSimplifier.simplify(RerunMesh.Empty).triangleCount)
    }

    @Test
    fun `writer supports both index widths and world space full resolution`() {
        for (n in listOf(65_535, 65_536)) {
            val positions = FloatArray(n * 3)
            positions[3] = 1f
            positions[7] = 1f
            val mesh = RerunMesh(positions, FloatArray(n * 3), IntArray(n) { -1 }, intArrayOf(0,1,2))
            val glb = RerunMeshGlb.writeShared(mesh, fullResolution = true, floorOrigin = false)
            val file = RerunGlbFile.parse(glb, "width")
            assertEquals(if (n == 65_535) 5123 else 5125,
                (file.json.objects("accessors")[3]["componentType"] as Number).toInt())
            assertEquals(n, (file.json.objects("accessors")[0]["count"] as Number).toInt())
        }
        val mesh = room(4)
        val data = RerunMeshGlb.writeShared(mesh, fullResolution = true, floorOrigin = false)
        val file = RerunGlbFile.parse(data, "world")
        val bin = ByteBuffer.wrap(file.bin!!).order(ByteOrder.LITTLE_ENDIAN)
        for (position in mesh.positions) assertEquals(position, bin.float, 0f)
        assertArrayEquals(data, RerunMeshGlb.writeShared(mesh, fullResolution = true, floorOrigin = false))
    }

    @Test
    fun `400k triangle room prints timing and allocation budget`() {
        val mesh = room(184)
        assertTrue(mesh.triangleCount in 400_000..410_000)
        val started = System.nanoTime()
        val result = checkReduction("400k-room", mesh, 100_000)
        println("400k simplification and GLB: ${(System.nanoTime() - started) / 1_000_000} ms; " +
            "workspace ${RerunMeshSimplifier.workspaceBytes(mesh.vertexCount, mesh.triangleCount)} bytes")
        assertTrue(result.triangleCount in 99_999..100_000)
    }

    @Test
    fun `progress runs from zero to one and throwing from it stops the work`() {
        val mesh = room(60)
        val steps = ArrayList<Float>()
        val light = RerunMeshGlb.writeShared(mesh, targetTriangles = 4_000) { steps += it }
        assertEquals(0f, steps.first(), 0f)
        assertEquals(1f, steps.last(), 0f)
        assertTrue("progress went back", steps.zipWithNext().all { (a, b) -> b >= a })
        assertTrue("too few steps to follow or cancel: ${steps.size}", steps.size > 4)
        assertArrayEquals(light, RerunMeshGlb.writeShared(mesh, targetTriangles = 4_000))

        class Stop : RuntimeException()
        var calls = 0
        assertThrows(Stop::class.java) {
            RerunMeshSimplifier.simplify(mesh, 4_000) { if (++calls == 3) throw Stop() }
        }
        assertEquals(3, calls)
        assertEquals(
            106L * mesh.vertexCount + 84L * mesh.triangleCount,
            RerunMeshSimplifier.workspaceBytes(mesh.vertexCount, mesh.triangleCount),
        )
    }

    /**
     * Not a check: writes the synthetic room's two models — world-space full resolution and the
     * shared light one — into the directory `RERUN_GLB_DUMP_DIR` names, for a side-by-side render.
     */
    @Test
    fun `dumps the full and the light room for a visual comparison`() {
        val dir = System.getenv("RERUN_GLB_DUMP_DIR")
        assumeTrue("set RERUN_GLB_DUMP_DIR to write the two models", !dir.isNullOrBlank())
        val tsdf = RerunTsdf()
        RerunSyntheticRoom.frames().forEach { tsdf.integrate(it) }
        val mesh = RerunMarchingCubes.extract(tsdf).mesh
        val started = System.nanoTime()
        val light = RerunMeshSimplifier.simplify(mesh)
        val ms = (System.nanoTime() - started) / 1_000_000
        val out = File(dir!!).apply { mkdirs() }
        val fullGlb = RerunMeshGlb.writeShared(mesh, fullResolution = true)
        val lightGlb = RerunMeshGlb.writeShared(light, fullResolution = true)
        File(out, "room-full.glb").writeBytes(fullGlb)
        File(out, "room-light.glb").writeBytes(lightGlb)
        println("dump: ${mesh.triangleCount} T / ${mesh.vertexCount} V / ${fullGlb.size} bytes -> " +
            "${light.triangleCount} T / ${light.vertexCount} V / ${lightGlb.size} bytes in $ms ms")
    }

    private fun checkReduction(name: String, input: RerunMesh, target: Int): RerunMesh {
        val originalPositions = input.positions.copyOf()
        val result = RerunMeshSimplifier.simplify(input, target)
        valid(result)
        val before = input.bounds()
        val after = result.bounds()
        for (i in 0..5) assertEquals("$name bounds $i", before[i], after[i], RerunTsdf.VOXEL_M)
        assertArrayEquals(originalPositions, input.positions, 0f)
        val full = RerunMeshGlb.writeShared(input, fullResolution = true, floorOrigin = false)
        val light = RerunMeshGlb.writeShared(result, fullResolution = true)
        val oldPayload = input.vertexCount * 28L + input.indices.size * 4L
        val lightPayload = result.vertexCount * 28L + result.indices.size * if (result.vertexCount <= 65_535) 2L else 4L
        println("$name: ${input.triangleCount} T / ${input.vertexCount} V / $oldPayload original BIN bytes -> " +
            "${result.triangleCount} T / ${result.vertexCount} V / $lightPayload BIN bytes; " +
            "${full.size} full-export / ${light.size} light GLB bytes")
        return result
    }

    private fun valid(mesh: RerunMesh) {
        RerunTsdfTest.assertFinite(mesh)
        val faces = HashSet<List<Int>>()
        for (t in mesh.indices.indices step 3) {
            val vertices = mesh.indices.slice(t..t + 2)
            assertEquals(3, vertices.toSet().size)
            assertTrue("duplicate face", faces.add(vertices.sorted()))
            val a = RerunTsdfTest.position(mesh, vertices[0])
            val b = RerunTsdfTest.position(mesh, vertices[1])
            val c = RerunTsdfTest.position(mesh, vertices[2])
            assertTrue("zero-area face", (b - a).cross(c - a).length() > 1e-12f)
        }
        assertTrue("non-manifold edge", edges(mesh).values.all { it <= 2 })
    }

    private fun parseAndCheck(mesh: RerunMesh, bytes: ByteArray) {
        val file = RerunGlbFile.parse(bytes, "light")
        val accessors = file.json.objects("accessors")
        val views = file.json.objects("bufferViews")
        assertEquals("2.0", file.json.obj("asset")!!["version"])
        assertEquals(1, file.json.objects("scenes").size)
        assertEquals(listOf(0), file.json.objects("scenes")[0]["nodes"])
        assertEquals(1, file.json.objects("nodes").size)
        assertEquals(mesh.vertexCount, (accessors[0]["count"] as Number).toInt())
        assertEquals(mesh.triangleCount * 3, (accessors[3]["count"] as Number).toInt())
        assertEquals(5121, (accessors[2]["componentType"] as Number).toInt())
        assertEquals("VEC4", accessors[2]["type"])
        assertEquals(true, accessors[2]["normalized"])
        val bin = ByteBuffer.wrap(file.bin!!).order(ByteOrder.LITTLE_ENDIAN)
        for (view in views) {
            val offset = (view["byteOffset"] as Number).toInt()
            assertEquals(0, offset % 4)
            assertTrue(offset + (view["byteLength"] as Number).toInt() <= bin.capacity())
        }
        val bounds = FloatArray(6) { if (it < 3) Float.POSITIVE_INFINITY else Float.NEGATIVE_INFINITY }
        for (v in 0 until mesh.vertexCount) for (axis in 0..2) {
            val x = bin.float
            bounds[axis] = min(bounds[axis], x)
            bounds[axis + 3] = max(bounds[axis + 3], x)
        }
        fun declared(key: String, axis: Int) = ((accessors[0][key] as List<*>)[axis] as Number).toDouble()
        for (axis in 0..2) {
            assertEquals(bounds[axis].toDouble(), declared("min", axis), 1e-6)
            assertEquals(bounds[axis + 3].toDouble(), declared("max", axis), 1e-6)
        }
        assertEquals(0f, bounds[1], 1e-6f)
        assertEquals(0f, bounds[0] + bounds[3], 1e-6f)
        assertEquals(0f, bounds[2] + bounds[5], 1e-6f)
        val before = mesh.bounds()
        for (axis in 0..2) assertEquals(before[axis + 3] - before[axis], bounds[axis + 3] - bounds[axis], 1e-5f)
        bin.position((views[3]["byteOffset"] as Number).toInt())
        val short = (accessors[3]["componentType"] as Number).toInt() == 5123
        for (index in mesh.indices) assertEquals(index, if (short) bin.short.toInt() and 65535 else bin.int)
    }

    private fun edges(mesh: RerunMesh): Map<Long, Int> {
        val edges = HashMap<Long, Int>()
        for (t in mesh.indices.indices step 3) for (k in 0..2) {
            val a = mesh.indices[t + k]
            val b = mesh.indices[t + (k + 1) % 3]
            val key = (min(a,b).toLong() shl 32) or max(a,b).toLong()
            edges[key] = (edges[key] ?: 0) + 1
        }
        return edges
    }

    private fun euler(mesh: RerunMesh) = mesh.vertexCount - edges(mesh).size + mesh.triangleCount
    private fun edgePositions(mesh: RerunMesh, key: Long): Set<List<Float>> =
        setOf(mesh.positions.slice((key ushr 32).toInt() * 3 until (key ushr 32).toInt() * 3 + 3),
            mesh.positions.slice(key.toInt() * 3 until key.toInt() * 3 + 3))
    private fun boundarySegments(mesh: RerunMesh) = edges(mesh).filterValues { it == 1 }.keys
        .map { edgePositions(mesh, it) }.toSet()

    /** Shared grid vertices on a box, a ceiling hole, and three smooth bumps in the floor. */
    private fun room(n: Int): RerunMesh {
        val positions = ArrayList<Float>()
        val indices = ArrayList<Int>()
        val vertices = HashMap<Long, Int>()
        fun vertex(axis: Int, side: Int, u: Int, v: Int): Int {
            val xyz = IntArray(3)
            xyz[axis] = side * n
            xyz[(axis + 1) % 3] = u
            xyz[(axis + 2) % 3] = v
            val key = (xyz[0].toLong() shl 40) or (xyz[1].toLong() shl 20) or xyz[2].toLong()
            return vertices.getOrPut(key) {
                val x = xyz[0].toFloat() / n
                val y = xyz[1].toFloat() / n
                val z = xyz[2].toFloat() / n
                val bump = if (xyz[1] == 0) {
                    (0..2).sumOf { i -> 0.08 * exp(-120 * ((x - 0.25 - i * 0.2).pow(2) + (z - 0.45).pow(2))) }
                } else 0.0
                val id = positions.size / 3
                positions.add(x * 4 - 1)
                positions.add((y * 2.6 - 1 + bump * sin(PI*x) * sin(PI*z)).toFloat())
                positions.add(z * 3.6f - 0.8f)
                id
            }
        }
        val hole = n / 3 until n / 2
        fun quad(axis: Int, side: Int, u: Int, v: Int) {
            val ceiling = axis == 1 && side == 1
            if (ceiling && u in hole && v in hole) return
            val a = vertex(axis, side, u, v)
            val b = vertex(axis, side, u + 1, v)
            val c = vertex(axis, side, u + 1, v + 1)
            val d = vertex(axis, side, u, v + 1)
            indices.addAll(if (side == 1) listOf(a, b, c, a, c, d) else listOf(a, c, b, a, d, c))
        }
        for (face in 0 until 6) for (cell in 0 until n * n) quad(face / 2, face % 2, cell / n, cell % n)
        return RerunMesh(positions.toFloatArray(), FloatArray(positions.size) { if (it % 3 == 1) 1f else 0f },
            IntArray(positions.size / 3) { 0xFFB08457.toInt() }, indices.toIntArray())
    }
}
