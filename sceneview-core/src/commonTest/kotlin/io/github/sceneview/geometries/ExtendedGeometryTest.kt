package io.github.sceneview.geometries

import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.cross
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.length
import dev.romainguy.kotlin.math.normalize
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Asserts every triangle of [geometry] is wound counter-clockwise / outward-facing: its
 * geometric face normal (right-hand rule over the index order) points the same way as the
 * mean of its three vertices' outward shading normals (`dot > 0`). A clockwise/inward
 * winding flips the face normal, making `dot < 0`, which renders the surface inside-out
 * under a single-sided material (Filament / Filament.js back-face-cull clockwise triangles).
 */
private fun assertOutwardWinding(geometry: GeometryData, label: String) {
    val vertices = geometry.vertices
    val indices = geometry.indices
    var inverted = 0
    var checked = 0
    var i = 0
    while (i + 2 < indices.size) {
        val ia = indices[i]
        val ib = indices[i + 1]
        val ic = indices[i + 2]
        val pa = vertices[ia].position
        val pb = vertices[ib].position
        val pc = vertices[ic].position
        val faceNormal = cross(pb - pa, pc - pa)
        // Skip degenerate triangles (zero-area) — their normal is undefined.
        if (length(faceNormal) > 1e-6f) {
            val na = vertices[ia].normal!!
            val nb = vertices[ib].normal!!
            val nc = vertices[ic].normal!!
            val meanNormal = normalize(na + nb + nc)
            if (dot(normalize(faceNormal), meanNormal) <= 0f) inverted++
            checked++
        }
        i += 3
    }
    assertTrue(checked > 0, "$label: no non-degenerate triangles checked")
    assertEquals(
        0, inverted,
        "$label: $inverted/$checked triangles are wound inward (inside-out)"
    )
}

class ExtendedGeometryTest {

    // ----- Torus -----

    @Test
    fun torusHasVerticesAndIndices() {
        val torus = generateTorus()
        assertTrue(torus.vertices.isNotEmpty())
        assertTrue(torus.indices.isNotEmpty())
        assertTrue(torus.indices.size % 3 == 0)
    }

    @Test
    fun torusVertexCount() {
        val major = 12
        val minor = 8
        val torus = generateTorus(majorSegments = major, minorSegments = minor)
        // (major+1) * (minor+1)
        assertEquals((major + 1) * (minor + 1), torus.vertices.size)
    }

    @Test
    fun torusNormalsAreUnitLength() {
        val torus = generateTorus(majorSegments = 8, minorSegments = 6)
        for (v in torus.vertices) {
            val n = v.normal!!
            val len = length(n)
            if (len > 1e-6f) {
                assertEquals(1f, len, 1e-3f, "Normal not unit length: $len")
            }
        }
    }

    @Test
    fun torusUvsInRange() {
        val torus = generateTorus(majorSegments = 8, minorSegments = 6)
        for (v in torus.vertices) {
            val uv = v.uvCoordinate!!
            assertTrue(uv.x in -0.01f..1.01f, "U out of range: ${uv.x}")
            assertTrue(uv.y in -0.01f..1.01f, "V out of range: ${uv.y}")
        }
    }

    @Test
    fun torusIndicesInBounds() {
        val torus = generateTorus(majorSegments = 8, minorSegments = 6)
        for (idx in torus.indices) {
            assertTrue(idx in 0 until torus.vertices.size, "Index $idx out of bounds")
        }
    }

    @Test
    fun torusWindingIsOutwardDefault() {
        // Regression for #2475: the core Torus generator must wind triangles
        // counter-clockwise (outward) so the donut is not rendered inside-out.
        assertOutwardWinding(generateTorus(), "Torus(default)")
    }

    @Test
    fun torusWindingIsOutwardNonDefault() {
        // A non-default tessellation must be outward-wound too.
        assertOutwardWinding(
            generateTorus(majorRadius = 1.5f, minorRadius = 0.5f, majorSegments = 16, minorSegments = 10),
            "Torus(non-default)"
        )
    }

    // ----- Capsule Geometry -----

    @Test
    fun capsuleGeometryHasVerticesAndIndices() {
        val capsule = generateCapsule()
        assertTrue(capsule.vertices.isNotEmpty())
        assertTrue(capsule.indices.isNotEmpty())
    }

    @Test
    fun capsuleGeometryNormalsAreUnitLength() {
        val capsule = generateCapsule(slices = 8, hemisphereStacks = 4)
        for (v in capsule.vertices) {
            val n = v.normal!!
            val len = length(n)
            if (len > 1e-6f) {
                assertEquals(1f, len, 0.02f, "Normal not unit length: $len")
            }
        }
    }

    @Test
    fun capsuleGeometryIndicesInBounds() {
        val capsule = generateCapsule(slices = 8, hemisphereStacks = 4)
        for (idx in capsule.indices) {
            assertTrue(idx in 0 until capsule.vertices.size, "Index $idx out of bounds (max ${capsule.vertices.size})")
        }
    }

    @Test
    fun capsuleGeometryBoundingBox() {
        val capsule = generateCapsule(radius = 0.5f, height = 2f)
        val bb = capsule.boundingBox()
        // Height should span from -1 to +1 (approximately)
        assertTrue(bb.halfExtent.y > 0.9f, "Height too small: ${bb.halfExtent.y}")
    }

    // ----- Rounded Cube -----

    @Test
    fun roundedCubeHasVerticesAndIndices() {
        val rc = generateRoundedCube()
        assertTrue(rc.vertices.isNotEmpty())
        assertTrue(rc.indices.isNotEmpty())
    }

    @Test
    fun roundedCubeNormalsAreUnitLength() {
        val rc = generateRoundedCube(segments = 2)
        for (v in rc.vertices) {
            val n = v.normal!!
            val len = length(n)
            if (len > 1e-6f) {
                assertEquals(1f, len, 0.02f, "Normal not unit length: $len")
            }
        }
    }

    @Test
    fun roundedCubeIndicesInBounds() {
        val rc = generateRoundedCube(segments = 2)
        for (idx in rc.indices) {
            assertTrue(idx in 0 until rc.vertices.size, "Index $idx out of bounds")
        }
    }

    // ----- Lathe -----

    @Test
    fun latheHasVerticesAndIndices() {
        val profile = listOf(Float2(0.5f, 0f), Float2(1f, 0.5f), Float2(0.5f, 1f))
        val lathe = generateLathe(profile, segments = 8)
        assertTrue(lathe.vertices.isNotEmpty())
        assertTrue(lathe.indices.isNotEmpty())
    }

    @Test
    fun latheRequiresAtLeastTwoProfilePoints() {
        var threw = false
        try { generateLathe(listOf(Float2(1f, 0f))) }
        catch (_: IllegalArgumentException) { threw = true }
        assertTrue(threw)
    }

    @Test
    fun latheIndicesInBounds() {
        val profile = listOf(Float2(0.5f, 0f), Float2(1f, 0.5f), Float2(0.5f, 1f))
        val lathe = generateLathe(profile, segments = 8)
        for (idx in lathe.indices) {
            assertTrue(idx in 0 until lathe.vertices.size, "Index $idx out of bounds")
        }
    }

    @Test
    fun latheVertexCount() {
        val profile = listOf(Float2(0.5f, 0f), Float2(1f, 0.5f), Float2(0.5f, 1f))
        val segments = 12
        val lathe = generateLathe(profile, segments = segments)
        // (segments + 1) * profileSize
        assertEquals((segments + 1) * profile.size, lathe.vertices.size)
    }

    @Test
    fun latheOpenProducesDifferentTopologyThanClosed() {
        // #2490: closed=false must omit the final wrap strip, yielding an OPEN lathe
        // with fewer indices than the closed surface. Pre-fix both were byte-for-byte
        // identical (the `closed` flag was dead).
        val profile = listOf(Float2(1f, 0f), Float2(0.5f, 1f), Float2(0.8f, 2f))
        val segments = 4

        val closed = generateLathe(profile, segments = segments, closed = true)
        val open = generateLathe(profile, segments = segments, closed = false)

        // Closed stitches `segments` strips, open stitches `segments - 1`.
        // Each strip = (profileSize - 1) quads * 6 indices.
        val indicesPerStrip = (profile.size - 1) * 6
        assertEquals(segments * indicesPerStrip, closed.indices.size, "Closed index count")
        assertEquals((segments - 1) * indicesPerStrip, open.indices.size, "Open index count")
        assertTrue(
            open.indices.size < closed.indices.size,
            "Open lathe must have fewer indices than closed (${open.indices.size} vs ${closed.indices.size})"
        )
        // The vertex grid is unchanged (seam rings kept for UVs in both cases).
        assertEquals(closed.vertices.size, open.vertices.size, "Vertex count unchanged by closed flag")
        // All open indices must still be in bounds.
        for (idx in open.indices) {
            assertTrue(idx in 0 until open.vertices.size, "Open index $idx out of bounds")
        }
    }

    // ----- Extrude -----

    @Test
    fun extrudeHasVerticesAndIndices() {
        val crossSection = listOf(
            Float2(-0.5f, -0.5f), Float2(0.5f, -0.5f), Float2(0.5f, 0.5f), Float2(-0.5f, 0.5f)
        )
        val path = listOf(Float3(0f, 0f, 0f), Float3(0f, 0f, 1f), Float3(0f, 0f, 2f))
        val extrude = generateExtrude(crossSection, path)
        assertTrue(extrude.vertices.isNotEmpty())
        assertTrue(extrude.indices.isNotEmpty())
    }

    @Test
    fun extrudeIndicesInBounds() {
        val crossSection = listOf(
            Float2(-0.5f, -0.5f), Float2(0.5f, -0.5f), Float2(0f, 0.5f)
        )
        val path = listOf(Float3(0f, 0f, 0f), Float3(0f, 1f, 0f), Float3(0f, 2f, 0f))
        val extrude = generateExtrude(crossSection, path)
        for (idx in extrude.indices) {
            assertTrue(idx in 0 until extrude.vertices.size, "Index $idx out of bounds (max ${extrude.vertices.size})")
        }
    }

    @Test
    fun extrudeRequiresMinimumPoints() {
        var threw = false
        try {
            generateExtrude(
                listOf(Float2(0f, 0f), Float2(1f, 0f)), // only 2 CS points
                listOf(Float3(0f), Float3(1f, 0f, 0f))
            )
        } catch (_: IllegalArgumentException) { threw = true }
        assertTrue(threw)
    }

    // ----- CSG -----

    @Test
    fun csgMerge() {
        val cubeA = generateCube(size = Float3(1f))
        val cubeB = generateCube(size = Float3(1f), center = Float3(3f, 0f, 0f))
        val merged = CSG.merge(cubeA, cubeB)
        assertEquals(cubeA.vertices.size + cubeB.vertices.size, merged.vertices.size)
        assertEquals(cubeA.indices.size + cubeB.indices.size, merged.indices.size)
    }

    @Test
    fun csgUnionNonOverlapping() {
        val cubeA = generateCube(size = Float3(1f))
        val cubeB = generateCube(size = Float3(1f), center = Float3(3f, 0f, 0f))
        val result = CSG.union(cubeA, cubeB)
        // Non-overlapping: should keep all triangles
        assertEquals(cubeA.vertices.size + cubeB.vertices.size, result.vertices.size)
    }

    @Test
    fun csgSubtractOverlapping() {
        val cubeA = generateCube(size = Float3(2f))
        val cubeB = generateCube(size = Float3(1f)) // Fully inside A
        val result = CSG.subtract(cubeA, cubeB)
        // Should have fewer triangles from A but inverted B triangles
        assertTrue(result.vertices.isNotEmpty())
        assertTrue(result.indices.isNotEmpty())
    }

    @Test
    fun csgInvertNormals() {
        val cube = generateCube()
        val inverted = CSG.invertNormals(cube)
        assertEquals(cube.vertices.size, inverted.vertices.size)
        // Check that normals are flipped
        for (i in cube.vertices.indices) {
            val orig = cube.vertices[i].normal!!
            val inv = inverted.vertices[i].normal!!
            assertEquals(-orig.x, inv.x, 1e-5f)
            assertEquals(-orig.y, inv.y, 1e-5f)
            assertEquals(-orig.z, inv.z, 1e-5f)
        }
    }

    // ----- Icosphere -----

    @Test
    fun icosphereSubdivision0Has12Vertices() {
        val ico = generateIcosphere(subdivisions = 0)
        assertEquals(12, ico.vertices.size)
    }

    @Test
    fun icosphereSubdivision1Has42Vertices() {
        val ico = generateIcosphere(subdivisions = 1)
        assertEquals(42, ico.vertices.size)
    }

    @Test
    fun icosphereSubdivision2Has162Vertices() {
        val ico = generateIcosphere(subdivisions = 2)
        assertEquals(162, ico.vertices.size)
    }

    @Test
    fun icosphereVerticesOnSurface() {
        val radius = 2f
        val ico = generateIcosphere(radius = radius, subdivisions = 2)
        for (v in ico.vertices) {
            val dist = length(v.position)
            assertEquals(radius, dist, 0.01f, "Vertex not on sphere surface: dist=$dist")
        }
    }

    @Test
    fun icosphereNormalsAreUnitLength() {
        val ico = generateIcosphere(subdivisions = 1)
        for (v in ico.vertices) {
            val n = v.normal!!
            assertEquals(1f, length(n), 0.01f, "Normal not unit length")
        }
    }

    @Test
    fun icosphereIndicesInBounds() {
        val ico = generateIcosphere(subdivisions = 2)
        for (idx in ico.indices) {
            assertTrue(idx in 0 until ico.vertices.size, "Index $idx out of bounds")
        }
    }

    @Test
    fun icosphereTriangleCount() {
        // Icosahedron has 20 faces. Each subdivision quadruples the count.
        // Sub 0: 20, Sub 1: 80, Sub 2: 320
        val ico0 = generateIcosphere(subdivisions = 0)
        assertEquals(60, ico0.indices.size) // 20 * 3
        val ico1 = generateIcosphere(subdivisions = 1)
        assertEquals(240, ico1.indices.size) // 80 * 3
    }

    // ----- Heightmap -----

    @Test
    fun heightmapFlatTerrain() {
        val terrain = generateHeightmap(width = 4f, depth = 4f, segmentsX = 4, segmentsZ = 4)
        assertTrue(terrain.vertices.isNotEmpty())
        assertTrue(terrain.indices.isNotEmpty())
        // All Y should be 0 for flat terrain
        for (v in terrain.vertices) {
            assertEquals(0f, v.position.y, 1e-5f)
        }
    }

    @Test
    fun heightmapVertexCount() {
        val sx = 8
        val sz = 6
        val terrain = generateHeightmap(segmentsX = sx, segmentsZ = sz)
        assertEquals((sx + 1) * (sz + 1), terrain.vertices.size)
    }

    @Test
    fun heightmapIndexCount() {
        val sx = 4
        val sz = 4
        val terrain = generateHeightmap(segmentsX = sx, segmentsZ = sz)
        // Each cell = 2 triangles * 3 indices = 6
        assertEquals(sx * sz * 6, terrain.indices.size)
    }

    @Test
    fun heightmapWithFunction() {
        val terrain = generateHeightmap(
            width = 4f, depth = 4f,
            segmentsX = 4, segmentsZ = 4,
            heightFunction = { _, _ -> 5f }
        )
        for (v in terrain.vertices) {
            assertEquals(5f, v.position.y, 1e-5f)
        }
    }

    @Test
    fun heightmapNormalsAreUnitLength() {
        val terrain = generateHeightmap(segmentsX = 4, segmentsZ = 4)
        for (v in terrain.vertices) {
            val n = v.normal!!
            assertEquals(1f, length(n), 0.01f, "Normal not unit length")
        }
    }

    @Test
    fun heightmapIndicesInBounds() {
        val terrain = generateHeightmap(segmentsX = 8, segmentsZ = 8)
        for (idx in terrain.indices) {
            assertTrue(idx in 0 until terrain.vertices.size, "Index $idx out of bounds")
        }
    }

    @Test
    fun heightmapUvsInRange() {
        val terrain = generateHeightmap(segmentsX = 4, segmentsZ = 4)
        for (v in terrain.vertices) {
            val uv = v.uvCoordinate!!
            assertTrue(uv.x in -0.01f..1.01f, "U out of range: ${uv.x}")
            assertTrue(uv.y in -0.01f..1.01f, "V out of range: ${uv.y}")
        }
    }

    @Test
    fun perlinTerrainHasVariation() {
        val terrain = generatePerlinTerrain(segmentsX = 16, segmentsZ = 16, heightScale = 2f)
        val heights = terrain.vertices.map { it.position.y }
        val minH = heights.min()
        val maxH = heights.max()
        // Should have some variation
        assertTrue(maxH - minH > 0.01f, "Terrain should have height variation")
    }
}
