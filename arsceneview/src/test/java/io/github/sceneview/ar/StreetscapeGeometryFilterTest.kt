package io.github.sceneview.ar

import com.google.ar.core.StreetscapeGeometry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM regression test for the `types` + `minQuality` filter landed by #1772 on
 * `ARSceneScope.StreetscapeGeometryNode`.
 *
 * The composable runs at the AR render thread and the underlying
 * [com.google.ar.core.StreetscapeGeometry] is JNI-only — its constructor takes a native handle.
 * So the filter logic is split: the production composable calls
 * `streetscapeGeometryPasses(geometry, types, minQuality)` which forwards to a JVM-friendly
 * overload taking the enum values directly. This test pins the JVM-friendly contract — that's
 * the same logic that runs on-device, only without the Filament/ARCore handles.
 *
 * What we pin:
 *  - Type filter is a set-membership check.
 *  - Quality filter is an ordinal comparison against ARCore's declaration order
 *    (`NONE < BUILDING_LOD_1 < BUILDING_LOD_2`).
 *  - Both filters compose with AND semantics.
 *  - Default arguments on the composable side equal "pass everything" (`{BUILDING, TERRAIN}`,
 *    `Quality.NONE`).
 */
class StreetscapeGeometryFilterTest {

    @Test
    fun `default filter passes every type and quality`() {
        // The composable defaults: setOf(BUILDING, TERRAIN), Quality.NONE.
        val defaultTypes = setOf(
            StreetscapeGeometry.Type.BUILDING,
            StreetscapeGeometry.Type.TERRAIN
        )
        val defaultMinQuality = StreetscapeGeometry.Quality.NONE

        StreetscapeGeometry.Type.values().forEach { t ->
            StreetscapeGeometry.Quality.values().forEach { q ->
                assertTrue(
                    "Default filter must accept type=$t quality=$q",
                    streetscapeGeometryPasses(t, q, defaultTypes, defaultMinQuality)
                )
            }
        }
    }

    @Test
    fun `type filter drops geometries whose type is not in the set`() {
        val buildingsOnly = setOf(StreetscapeGeometry.Type.BUILDING)
        assertTrue(
            "BUILDING geometry passes BUILDING-only filter",
            streetscapeGeometryPasses(
                actualType = StreetscapeGeometry.Type.BUILDING,
                actualQuality = StreetscapeGeometry.Quality.BUILDING_LOD_1,
                types = buildingsOnly,
                minQuality = StreetscapeGeometry.Quality.NONE
            )
        )
        assertFalse(
            "TERRAIN geometry is dropped by BUILDING-only filter",
            streetscapeGeometryPasses(
                actualType = StreetscapeGeometry.Type.TERRAIN,
                actualQuality = StreetscapeGeometry.Quality.BUILDING_LOD_1,
                types = buildingsOnly,
                minQuality = StreetscapeGeometry.Quality.NONE
            )
        )
    }

    @Test
    fun `empty type set rejects everything`() {
        StreetscapeGeometry.Type.values().forEach { t ->
            assertFalse(
                "Empty type set must drop type=$t",
                streetscapeGeometryPasses(
                    actualType = t,
                    actualQuality = StreetscapeGeometry.Quality.BUILDING_LOD_2,
                    types = emptySet(),
                    minQuality = StreetscapeGeometry.Quality.NONE
                )
            )
        }
    }

    @Test
    fun `quality filter drops geometries below the threshold`() {
        // ARCore declares: NONE.ordinal=0, BUILDING_LOD_1.ordinal=1, BUILDING_LOD_2.ordinal=2.
        // minQuality = BUILDING_LOD_2 must reject NONE and BUILDING_LOD_1, accept BUILDING_LOD_2.
        val allTypes = setOf(
            StreetscapeGeometry.Type.BUILDING,
            StreetscapeGeometry.Type.TERRAIN
        )

        assertFalse(
            "NONE quality < BUILDING_LOD_2 → drop",
            streetscapeGeometryPasses(
                actualType = StreetscapeGeometry.Type.BUILDING,
                actualQuality = StreetscapeGeometry.Quality.NONE,
                types = allTypes,
                minQuality = StreetscapeGeometry.Quality.BUILDING_LOD_2
            )
        )
        assertFalse(
            "BUILDING_LOD_1 < BUILDING_LOD_2 → drop",
            streetscapeGeometryPasses(
                actualType = StreetscapeGeometry.Type.BUILDING,
                actualQuality = StreetscapeGeometry.Quality.BUILDING_LOD_1,
                types = allTypes,
                minQuality = StreetscapeGeometry.Quality.BUILDING_LOD_2
            )
        )
        assertTrue(
            "BUILDING_LOD_2 == BUILDING_LOD_2 → keep",
            streetscapeGeometryPasses(
                actualType = StreetscapeGeometry.Type.BUILDING,
                actualQuality = StreetscapeGeometry.Quality.BUILDING_LOD_2,
                types = allTypes,
                minQuality = StreetscapeGeometry.Quality.BUILDING_LOD_2
            )
        )
    }

    @Test
    fun `quality NONE threshold accepts everything from a quality standpoint`() {
        // minQuality = NONE.ordinal = 0 — every other Quality ordinal is >= 0.
        val allTypes = setOf(
            StreetscapeGeometry.Type.BUILDING,
            StreetscapeGeometry.Type.TERRAIN
        )
        StreetscapeGeometry.Quality.values().forEach { q ->
            assertTrue(
                "Quality=$q must pass minQuality=NONE",
                streetscapeGeometryPasses(
                    actualType = StreetscapeGeometry.Type.BUILDING,
                    actualQuality = q,
                    types = allTypes,
                    minQuality = StreetscapeGeometry.Quality.NONE
                )
            )
        }
    }

    @Test
    fun `type and quality filters compose with AND`() {
        // BUILDING-only + LOD_2 minimum: TERRAIN/LOD_2 → drop (type), BUILDING/LOD_1 → drop
        // (quality), BUILDING/LOD_2 → keep.
        val buildings = setOf(StreetscapeGeometry.Type.BUILDING)
        val lod2 = StreetscapeGeometry.Quality.BUILDING_LOD_2

        assertFalse(
            "TERRAIN/LOD_2 → dropped by type filter",
            streetscapeGeometryPasses(
                StreetscapeGeometry.Type.TERRAIN, lod2, buildings, lod2
            )
        )
        assertFalse(
            "BUILDING/LOD_1 → dropped by quality filter",
            streetscapeGeometryPasses(
                StreetscapeGeometry.Type.BUILDING,
                StreetscapeGeometry.Quality.BUILDING_LOD_1,
                buildings,
                lod2
            )
        )
        assertTrue(
            "BUILDING/LOD_2 → passes both filters",
            streetscapeGeometryPasses(
                StreetscapeGeometry.Type.BUILDING, lod2, buildings, lod2
            )
        )
    }
}
