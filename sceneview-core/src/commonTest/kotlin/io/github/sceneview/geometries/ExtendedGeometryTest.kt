package io.github.sceneview.geometries

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.cross
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.length
import dev.romainguy.kotlin.math.normalize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    /**
     * Index-connectivity / topology regression for #2465.
     *
     * The previous implementation emitted three independently-ordered vertex blocks
     * (top hemisphere, cylinder, bottom hemisphere) but stitched them with one
     * continuous-grid index loop. That bridged the north-pole row onto the cylinder rim
     * (an inverted cap funnel), produced a degenerate zero-area band where the cylinder
     * bottom coincides with the bottom-hemisphere equator, and left the bottom hemisphere
     * wound inward. None of those defects move any vertex *position*, so the earlier tests
     * (which only inspect vertices / the bounding box) all passed on a broken mesh.
     *
     * This asserts the *connectivity*: every triangle is non-degenerate, every triangle's
     * winding-derived face normal points outward (agrees with its vertex normals), and no
     * triangle bridges non-adjacent vertex rows. It fails on the pre-fix topology.
     */
    private fun assertCapsuleTopologyValid(
        radius: Float,
        height: Float,
        slices: Int,
        hemisphereStacks: Int,
        cylinderStacks: Int
    ) {
        val capsule = generateCapsule(
            radius = radius,
            height = height,
            slices = slices,
            hemisphereStacks = hemisphereStacks,
            cylinderStacks = cylinderStacks
        )
        val verts = capsule.vertices
        val idx = capsule.indices

        assertTrue(idx.size % 3 == 0, "Index count ${idx.size} is not a multiple of 3")
        assertTrue(idx.isNotEmpty(), "No indices generated")

        // Every index in range.
        for (i in idx) {
            assertTrue(i in 0 until verts.size, "Index $i out of bounds (vertex count ${verts.size})")
        }

        // Rows are runs of (slices + 1) vertices. A well-formed quad-grid triangle spans at
        // most two adjacent rows; a cross-block bridge connects rows further apart.
        val verticesPerRow = slices + 1
        var triCount = 0
        var coveredVertices = 0
        val touched = BooleanArray(verts.size)

        for (t in idx.indices step 3) {
            val ia = idx[t]
            val ib = idx[t + 1]
            val ic = idx[t + 2]
            touched[ia] = true; touched[ib] = true; touched[ic] = true

            val rowA = ia / verticesPerRow
            val rowB = ib / verticesPerRow
            val rowC = ic / verticesPerRow
            val rowSpan = maxOf(rowA, rowB, rowC) - minOf(rowA, rowB, rowC)
            assertTrue(
                rowSpan <= 1,
                "Triangle [$ia,$ib,$ic] bridges non-adjacent rows " +
                    "($rowA,$rowB,$rowC) — a cross-block stitch"
            )

            val pa = verts[ia].position
            val pb = verts[ib].position
            val pc = verts[ic].position

            // Non-degenerate: the edge cross-product (twice the triangle area) must be
            // meaningfully non-zero. Catches the coincident cylinder-bottom/equator band.
            val faceCross = cross(pb - pa, pc - pa)
            val twiceArea = length(faceCross)
            assertTrue(
                twiceArea > 1e-7f,
                "Degenerate (zero-area) triangle [$ia,$ib,$ic], 2*area=$twiceArea"
            )

            // Outward winding: the winding-derived face normal must agree with the mean of
            // the three vertex normals. Catches the inverted top funnel and the inverted
            // bottom hemisphere. A pole vertex's collapsed ring is skipped (its mean normal
            // is dominated by the axis and can be near-orthogonal to the thin face normal),
            // but a genuinely inverted *band* of full-radius rings is not near a pole.
            val faceNormal = normalize(faceCross)
            val na = verts[ia].normal!!
            val nb = verts[ib].normal!!
            val nc = verts[ic].normal!!
            val meanNormal = (na + nb + nc) / 3f
            val meanLen = length(meanNormal)
            if (meanLen > 0.2f) {
                val alignment = dot(faceNormal, meanNormal / meanLen)
                assertTrue(
                    alignment > 0f,
                    "Inverted/back-facing triangle [$ia,$ib,$ic]: " +
                        "dot(faceNormal, vertexNormals)=$alignment <= 0"
                )
            }
            triCount++
        }

        for (v in touched.indices) if (touched[v]) coveredVertices++
        assertTrue(coveredVertices > 0, "No vertices are referenced by triangles")
        assertTrue(triCount > 0, "No triangles generated")

        // Watertight-ish: there must be no *positional* hole. A vertex may legitimately go
        // unreferenced — a pole apex's duplicate seam column, or the whole cylinder ring when
        // height == 2*radius collapses it to zero length — but only if some *referenced*
        // vertex sits at (essentially) the same position, so the surface still has no gap
        // there. The gross "half the mesh was never stitched" bug leaves whole distinct rings
        // uncovered and fails this. A small tolerance absorbs the ~1e-8 cos(PI/2) pole jitter.
        val coverEps = maxOf(radius, height) * 1e-4f
        val coverEps2 = coverEps * coverEps + 1e-10f
        for (v in touched.indices) {
            if (!touched[v]) {
                val p = verts[v].position
                var covered = false
                for (u in touched.indices) {
                    if (touched[u]) {
                        val diff = verts[u].position - p
                        if (dot(diff, diff) <= coverEps2) { covered = true; break }
                    }
                }
                assertTrue(
                    covered,
                    "Unreferenced vertex $v at $p has no covering referenced vertex — a hole"
                )
            }
        }
    }

    @Test
    fun capsuleGeometryTopologyDefaultIsWatertightAndOutward() {
        // Default parameters — the case the #2465 trace is written against.
        assertCapsuleTopologyValid(
            radius = 0.5f, height = 2f, slices = 24, hemisphereStacks = 8, cylinderStacks = 1
        )
    }

    @Test
    fun capsuleGeometryTopologyNonDefaultParamsAreWatertightAndOutward() {
        // A coarse capsule.
        assertCapsuleTopologyValid(
            radius = 0.25f, height = 1.5f, slices = 6, hemisphereStacks = 3, cylinderStacks = 2
        )
        // A fatter, taller capsule with more cylinder stacks.
        assertCapsuleTopologyValid(
            radius = 1f, height = 5f, slices = 16, hemisphereStacks = 6, cylinderStacks = 4
        )
        // A near-spherical capsule (cylinder collapses to zero height).
        assertCapsuleTopologyValid(
            radius = 1f, height = 2f, slices = 12, hemisphereStacks = 8, cylinderStacks = 1
        )
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
