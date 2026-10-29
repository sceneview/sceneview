package io.github.sceneview.demo.demos.internal.model

import io.github.sceneview.demo.demos.internal.BakePhoto
import io.github.sceneview.demo.demos.internal.RerunScanModel
import io.github.sceneview.demo.demos.sharedRerunModel
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.RerunGlbMesh
import io.github.sceneview.demo.demos.internal.RerunMarchingCubes
import io.github.sceneview.demo.demos.internal.RerunMeshGlb
import io.github.sceneview.demo.demos.internal.RerunSyntheticRoom
import io.github.sceneview.demo.demos.internal.RerunTsdf
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole finished-model pipeline on the ray-cast room: its depth fused and meshed as a
 * recording's is, then cleaned, simplified, textured from its key photos and written — against
 * the vertex-coloured marching-cubes model the app wrote before.
 */
class CleanModelSyntheticTest {
    @Test
    fun `the synthetic room comes out textured, sharper and lighter than its raw model`() {
        val tsdf = RerunTsdf()
        TexturedSyntheticRoom.frames().forEach { tsdf.integrate(it) }

        val beforeStart = System.nanoTime()
        val raw = RerunMarchingCubes.extractWhole(tsdf, RerunMarchingCubes.DEFAULT_MIN_WEIGHT).mesh
        val beforeGlb = RerunMeshGlb.write(raw)
        val beforeMs = (System.nanoTime() - beforeStart) / NANOS_PER_MS

        val shots = TexturedSyntheticRoom.photos()
        val photos = ModelPhotos(TexturedSyntheticRoom.PHOTO_LENS, shots.map { shot -> ModelPhoto(shot.pose) { shot } })
        val result = CleanModel.build(raw, photos, JvmAtlasEncoder)
        val stats = result.stats
        val texture = checkNotNull(stats.texture) { "the room's photos should texture it" }

        val rawError = colorError(raw.positions, raw.indices) { f, _ -> meanColor(raw.colors, raw.indices, f) }
        val model = checkNotNull(result.model)
        val atlas = checkNotNull(model.texture).let { BakePhoto(ORIGIN, it.width, it.height, it.atlas) }
        val allIndices = model.texturedIndices + model.plainIndices
        val texturedFaces = model.texturedIndices.size / 3
        val afterError = colorError(model.positions, allIndices) { f, w ->
            if (f < texturedFaces) {
                val uv = checkNotNull(model.uv)
                var u = 0f
                var v = 0f
                for (k in 0 until 3) {
                    val i = allIndices[f * 3 + k]
                    u += uv[i * 2] * w[k]
                    v += uv[i * 2 + 1] * w[k]
                }
                PhotoTexturing.sample(atlas, u * atlas.width - HALF, v * atlas.height - HALF)
            } else {
                meanColor(model.colors, allIndices, f)
            }
        }

        // The floor the eye reads, rug included: as much of it as the scan saw, and no hole opened.
        val rawFloor = FloorArea.of(raw.positions, raw.indices)
        val finalFloor = FloorArea.of(model.positions, allIndices, rawFloor.height)
        val floorRatio = finalFloor.area / rawFloor.area

        val report = (
            "clean model, synthetic room (${shots.size} photos ${TexturedSyntheticRoom.PHOTO_WIDTH}x" +
                "${TexturedSyntheticRoom.PHOTO_HEIGHT}):\n" +
                "  before: ${raw.triangleCount} triangles, ${raw.vertexCount} vertices, " +
                "glb ${beforeGlb.size / KB} KB, ${beforeMs} ms, colour error $rawError\n" +
                "  after:  ${stats.finalTriangles} triangles (${stats.simplifiedTriangles} simplified), " +
                "${stats.finalVertices} vertices, glb ${stats.glbBytes / KB} KB, " +
                "atlas ${stats.atlasWidth}x${stats.atlasHeight}, colour error $afterError\n" +
                "  time:   clean ${stats.cleanMs} ms, simplify ${stats.simplifyMs} ms, " +
                "texture ${stats.textureMs} ms, write ${stats.writeMs} ms = ${stats.totalMs} ms\n" +
                "  geometry: pieces ${stats.geometry.piecesIn} -> ${stats.geometry.piecesOut}, " +
                "${stats.geometry.planes} planes, flat ${"%.0f".format(stats.geometry.flatShare * 100f)}%, " +
                "${stats.geometry.holesFilled} holes filled, ${stats.geometry.doubledTriangles} doubled triangles\n" +
                "  texture: ${texture.photosUsed}/${texture.photosIn} photos, ${texture.charts} charts, " +
                "textured ${"%.1f".format(texture.texturedShare * 100f)}%, gains ${"%.2f".format(texture.gainMin)}.." +
                "${"%.2f".format(texture.gainMax)}, seams ${"%.1f".format(texture.seamRaw)} -> " +
                "${"%.1f".format(texture.seamExposed)} -> ${"%.1f".format(texture.seamFinal)}, " +
                "${"%.0f".format(texture.texelsPerMetre)} texels/m (${"%.2f".format(CM_PER_M / texture.texelsPerMetre)} cm/texel)\n" +
                "  floor: ${"%.3f".format(floorRatio)} of the raw one's area, holes ${rawFloor.innerLoops} -> ${finalFloor.innerLoops}"
        )
        println(report)

        assertTrue("textured share ${texture.texturedShare}", texture.texturedShare > MIN_TEXTURED_SHARE)
        // Read along the seam edges, between the vertices the levelling pins: what a viewer sees.
        assertTrue(
            "seams ${texture.seamRaw} -> ${texture.seamExposed} -> ${texture.seamFinal}",
            texture.seamFinal < texture.seamRaw && texture.seamFinal < texture.seamExposed,
        )
        assertTrue("triangles ${stats.finalTriangles}", stats.finalTriangles < raw.triangleCount / 2)
        assertTrue("glb ${stats.glbBytes} vs ${beforeGlb.size}", stats.glbBytes < beforeGlb.size)
        // The photos' detail: the poster's checks and the floor's seams, finer than a voxel.
        assertTrue("detail $rawError -> $afterError", afterError[Part.DETAIL] < rawError[Part.DETAIL] * DETAIL_GAIN)
        assertTrue("floor $rawError -> $afterError", afterError[Part.FLOOR] < rawError[Part.FLOOR])
        // The photos' exposures differ by up to ±12 %, the depth's colours not at all: levelled,
        // what is left on the plain walls stays within a couple of percent of the paint.
        assertTrue("overall $afterError", afterError.matched < MAX_MATCHED_ERROR)
        assertTrue("floor ${"%.3f".format(floorRatio)} of the raw one's", floorRatio >= MIN_FLOOR_KEPT)
        assertTrue("floor holes ${rawFloor.innerLoops} -> ${finalFloor.innerLoops}", finalFloor.innerLoops <= rawFloor.innerLoops)
        assertTrue("${stats.totalMs} ms", stats.totalMs < DEV_JVM_BUDGET_MS)

        assertEquals(emptyList<String>(), GlbCheck.problems(result.glb))
        assertEquals(emptyList<String>(), GlbCheck.problems(beforeGlb))
        // The app's own reader (thumbnails, the share's floor origin) still reads the textured file.
        val back = RerunGlbMesh.read(result.glb)
        assertNotNull(back)
        assertEquals(stats.finalTriangles, back!!.triangleCount)
        assertEquals(stats.finalVertices, back.vertexCount)

        // Shared as built — texture kept — and resting on its floor, centred in X/Z.
        assertTrue(ModelGlb.isTextured(result.glb))
        assertTrue(!ModelGlb.isTextured(beforeGlb))
        val mesh = model.asRerunMesh()
        val shared = sharedRerunModel(
            RerunScanModel(RerunScanModel.Kind.Mesh, result.glb, mesh.bounds(), mesh.triangleCount, mesh.vertexCount),
            free = Long.MAX_VALUE,
        )
        assertEquals(emptyList<String>(), GlbCheck.problems(shared.glb))
        assertTrue(ModelGlb.isTextured(shared.glb))
        val json = GlbCheck.json(shared.glb)
        val position = json["accessors"]!!.jsonArray[0].jsonObject
        val min = position["min"]!!.jsonArray.map { it.jsonPrimitive.double }
        val max = position["max"]!!.jsonArray.map { it.jsonPrimitive.double }
        val translation = json["nodes"]!!.jsonArray[0].jsonObject["translation"]!!.jsonArray.map { it.jsonPrimitive.double }
        assertEquals(0.0, min[1] + translation[1], FLOOR_TOLERANCE)
        assertEquals(0.0, (min[0] + max[0]) / 2 + translation[0], FLOOR_TOLERANCE)
        assertEquals(0.0, (min[2] + max[2]) / 2 + translation[2], FLOOR_TOLERANCE)
        assertEquals(result.glb.size.toDouble(), shared.glb.size.toDouble(), SHARE_GROWTH_BYTES)
    }

    @Test
    fun `without photos the model keeps its vertex colours, cleaned and simplified`() {
        val tsdf = RerunTsdf()
        TexturedSyntheticRoom.frames().forEach { tsdf.integrate(it) }
        val raw = RerunMarchingCubes.extractWhole(tsdf, RerunMarchingCubes.DEFAULT_MIN_WEIGHT).mesh
        val result = CleanModel.build(raw, photos = null, encoder = null)
        assertEquals(null, result.stats.texture)
        assertEquals(null, checkNotNull(result.model).uv)
        val json = String(result.glb, 20, result.glb.size - 20, Charsets.ISO_8859_1)
        assertTrue("no texture in the file", "\"images\"" !in json.substringBefore("BIN"))
        assertEquals(emptyList<String>(), GlbCheck.problems(result.glb))
        val back = RerunGlbMesh.read(result.glb)
        assertNotNull(back)
        assertEquals(result.stats.finalTriangles, back!!.triangleCount)
        assertTrue(result.stats.finalTriangles < raw.triangleCount)
        println(
            "clean model, no photos: ${raw.triangleCount} -> ${result.stats.finalTriangles} triangles, " +
                "glb ${result.glb.size / KB} KB, ${result.stats.totalMs} ms",
        )
    }

    /**
     * The area-weighted mean RGB distance (0–441) between what the model shows — [shown] at a
     * face and barycentric weights — and the room's true paint there, over each face's centroid
     * and three points toward its corners. [ColorError.matched] first scales the shown colours by
     * one gain per channel, the one that fits them best to the paint: the photos' overall
     * exposure is unknowable from the photos alone, and a room a few percent brighter overall is
     * not a worse model.
     */
    private fun colorError(positions: FloatArray, indices: IntArray, shown: (Int, FloatArray) -> Int): ColorError {
        val faces = FaceGeometry(positions, indices)
        val samples = ArrayList<IntArray>()
        val weights = ArrayList<Double>()
        val where = ArrayList<Part>()
        for (f in 0 until faces.count) {
            val a = faces.areas[f].toDouble()
            if (a <= 0.0) continue
            for (w in PROBES) {
                var x = 0f
                var y = 0f
                var z = 0f
                for (k in 0 until 3) {
                    val v = indices[f * 3 + k]
                    x += positions[v * 3] * w[k]
                    y += positions[v * 3 + 1] * w[k]
                    z += positions[v * 3 + 2] * w[k]
                }
                val truth = truth(x, y, z, faces.normals, f) ?: continue
                samples += intArrayOf(shown(f, w), truth)
                weights += a
                where += part(x, y, z)
            }
        }
        val gain = DoubleArray(3) { channel ->
            val shift = 16 - 8 * channel
            var st = 0.0
            var ss = 0.0
            for (i in samples.indices) {
                val s = (samples[i][0] shr shift and 0xFF).toDouble()
                val t = (samples[i][1] shr shift and 0xFF).toDouble()
                st += weights[i] * s * t
                ss += weights[i] * s * s
            }
            if (ss > 0.0) st / ss else 1.0
        }
        var raw = 0.0
        var area = 0.0
        val parts = DoubleArray(Part.entries.size)
        val partAreas = DoubleArray(Part.entries.size)
        for (i in samples.indices) {
            val (s, t) = samples[i].let { it[0] to it[1] }
            raw += distance(s, t) * weights[i]
            area += weights[i]
            val part = where[i].ordinal
            parts[part] += distance(scaled(s, gain), t) * weights[i]
            partAreas[part] += weights[i]
        }
        val matched = parts.sum() / area
        return ColorError(raw / area, matched, gain, DoubleArray(parts.size) { parts[it] / partAreas[it].coerceAtLeast(1e-9) })
    }

    /** Where a probe lies: the room's big plain surfaces, its fine detail, or its furniture. */
    private enum class Part { FLOOR, WALLS, DETAIL, FURNITURE }

    private class ColorError(val raw: Double, val matched: Double, val gain: DoubleArray, val parts: DoubleArray) {
        operator fun get(part: Part) = parts[part.ordinal]

        override fun toString() = "%.1f (%.1f at gains %s; %s)".format(
            raw, matched, gain.joinToString("/") { "%.3f".format(it) },
            Part.entries.joinToString { "${it.name.lowercase()} %.1f".format(parts[it.ordinal]) },
        )
    }

    private fun scaled(c: Int, gain: DoubleArray): Int = PhotoTexturing.rgb(
        ((c shr 16 and 0xFF) * gain[0]).toInt().coerceIn(0, 255),
        ((c shr 8 and 0xFF) * gain[1]).toInt().coerceIn(0, 255),
        ((c and 0xFF) * gain[2]).toInt().coerceIn(0, 255),
    )

    private fun part(x: Float, y: Float, z: Float): Part {
        val min = RerunSyntheticRoom.MIN
        val max = RerunSyntheticRoom.MAX
        val wall = z < min.z + SHELL_M || z > max.z - SHELL_M || x < min.x + SHELL_M || x > max.x - SHELL_M
        return when {
            // The poster's 5 cm checks, and the floor's 8 mm plank seams.
            z < min.z + SHELL_M && TexturedSyntheticRoom.onPoster(x, y) -> Part.DETAIL
            y < SHELL_M -> Part.FLOOR
            wall || y > max.y - SHELL_M -> Part.WALLS
            else -> Part.FURNITURE
        }
    }

    /** The room's paint at the surface nearest ([x], [y], [z]) along face [f]'s normal. */
    private fun truth(x: Float, y: Float, z: Float, normals: FloatArray, f: Int): Int? {
        val nx = normals[f * 3]
        val ny = normals[f * 3 + 1]
        val nz = normals[f * 3 + 2]
        val ox = x + nx * LIFT_M
        val oy = y + ny * LIFT_M
        val oz = z + nz * LIFT_M
        var best = Float.POSITIVE_INFINITY
        var color: Int? = null
        for (s in TexturedSyntheticRoom.scene.shapes) {
            val t = s.hit(ox, oy, oz, -nx, -ny, -nz)
            if (t < best) {
                best = t
                color = s.color(ox - nx * t, oy - ny * t, oz - nz * t)
            }
        }
        return color.takeIf { abs(best - LIFT_M) < MAX_MISS_M }
    }

    private fun meanColor(colors: IntArray, indices: IntArray, f: Int): Int {
        var r = 0
        var g = 0
        var b = 0
        for (k in 0 until 3) {
            val c = colors[indices[f * 3 + k]]
            r += c shr 16 and 0xFF
            g += c shr 8 and 0xFF
            b += c and 0xFF
        }
        return PhotoTexturing.rgb(r / 3, g / 3, b / 3)
    }

    private fun distance(a: Int, b: Int): Double {
        val dr = (a shr 16 and 0xFF) - (b shr 16 and 0xFF)
        val dg = (a shr 8 and 0xFF) - (b shr 8 and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return sqrt((dr * dr + dg * dg + db * db).toDouble())
    }

    private companion object {
        const val FLOOR_TOLERANCE = 1e-4
        const val SHARE_GROWTH_BYTES = 256.0
        val PROBES = listOf(
            floatArrayOf(1f / 3, 1f / 3, 1f / 3),
            floatArrayOf(0.6f, 0.2f, 0.2f),
            floatArrayOf(0.2f, 0.6f, 0.2f),
            floatArrayOf(0.2f, 0.2f, 0.6f),
        )
        val ORIGIN = DebugPose(0f, 0f, 0f)
        const val LIFT_M = 0.05f
        const val SHELL_M = 0.03f
        const val MAX_MISS_M = 0.04f
        const val HALF = 0.5f
        const val KB = 1024
        const val CM_PER_M = 100f
        const val NANOS_PER_MS = 1_000_000L
        const val MIN_TEXTURED_SHARE = 0.9f

        /** The finished floor over the raw one's: none of what the scan saw lost. */
        const val MIN_FLOOR_KEPT = 0.97f
        const val DETAIL_GAIN = 0.5
        const val MAX_MATCHED_ERROR = 8.0

        /**
         * A desktop JVM's ceiling, a guard against a slower algorithm: not the phone's budget.
         * A mid-range phone runs this several times slower; its time is measured on the device.
         */
        const val DEV_JVM_BUDGET_MS = 20_000L
    }
}

