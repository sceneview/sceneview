package io.github.sceneview.ar.scene

import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pure-JVM coverage for the decisions [PlaneRenderer] takes per plane:
 *
 *  1. which mark and tint a plane gets from its `Plane.Type` ([planeMaterialPresetFor]) — a
 *     wall must not read as a floor (#3507, #4307);
 *  2. which planes are too small to be drawn at all ([isPlaneLargeEnough], #4307).
 *
 * Constructing the renderer touches Filament JNI (material loading), which Robolectric does
 * not host: the on-screen result is checked on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PlaneRendererTest {

    // ── Per-type tint (#3507) ───────────────────────────────────────────────────────────────

    @Test
    fun `the floor gets white marks, the strongest of the three`() {
        val floor = planeMaterialPresetFor(Plane.Type.HORIZONTAL_UPWARD_FACING)
        assertEquals(1.0f, floor.gridR, 0f)
        assertEquals(1.0f, floor.gridG, 0f)
        assertEquals(1.0f, floor.gridB, 0f)
        val ceiling = planeMaterialPresetFor(Plane.Type.HORIZONTAL_DOWNWARD_FACING)
        val wall = planeMaterialPresetFor(Plane.Type.VERTICAL)
        // The floor is where things get placed: it must read first.
        assertTrue(floor.markAlpha > wall.markAlpha && floor.markAlpha > ceiling.markAlpha)
    }

    @Test
    fun `the ceiling is warm — more red than blue`() {
        val ceiling = planeMaterialPresetFor(Plane.Type.HORIZONTAL_DOWNWARD_FACING)
        assertTrue("ceiling must be warm: $ceiling", ceiling.gridR > ceiling.gridB)
    }

    @Test
    fun `walls are blue — more blue than red`() {
        val wall = planeMaterialPresetFor(Plane.Type.VERTICAL)
        assertTrue("wall must be blue: $wall", wall.gridB > wall.gridR)
    }

    @Test
    fun `all three known plane types are mapped without falling back to floor`() {
        val floor = planeMaterialPresetFor(Plane.Type.HORIZONTAL_UPWARD_FACING)
        val ceiling = planeMaterialPresetFor(Plane.Type.HORIZONTAL_DOWNWARD_FACING)
        val wall = planeMaterialPresetFor(Plane.Type.VERTICAL)
        val distinct = setOf(floor, ceiling, wall)
        assertEquals(
            "Each of floor/ceiling/wall must produce a distinct preset — got $distinct",
            3, distinct.size,
        )
    }

    @Test
    fun `every preset stays in the unit range`() {
        Plane.Type.values().forEach { type ->
            val preset = planeMaterialPresetFor(type)
            listOf(preset.gridR, preset.gridG, preset.gridB, preset.markAlpha).forEach {
                assertTrue("$type: $it out of [0, 1]", it in 0f..1f)
            }
        }
    }

    @Test
    fun `every Plane Type currently exposed by ARCore maps to a non-crashing preset`() {
        // Defensive: walk every enum value ARCore declares at this ARCore version. The
        // helper must produce a non-null preset for each one — including any future
        // enum value not enumerated in the when {} arms (those fall back to floor).
        // This is the test the brief calls "nonsense future type": at this ARCore
        // version (1.40+ — VERTICAL added long before then) there is no extra entry,
        // so we use `lastOrNull()` to grab whatever the highest-indexed enum is and
        // assert the helper handles it without throwing.
        val lastType = Plane.Type.values().lastOrNull()
        assertNotNull("Plane.Type must expose at least one value", lastType)
        val preset = planeMaterialPresetFor(lastType!!)
        assertNotNull("planeMaterialPresetFor must never return null", preset)
    }

    // ── Per-type mark (#4307) ───────────────────────────────────────────────────────────────

    @Test
    fun `each plane type draws its own mark — dots, upright dashes, rings`() {
        assertEquals(
            PlaneSurfaceKind.FLOOR,
            planeMaterialPresetFor(Plane.Type.HORIZONTAL_UPWARD_FACING).kind,
        )
        assertEquals(PlaneSurfaceKind.WALL, planeMaterialPresetFor(Plane.Type.VERTICAL).kind)
        assertEquals(
            PlaneSurfaceKind.CEILING,
            planeMaterialPresetFor(Plane.Type.HORIZONTAL_DOWNWARD_FACING).kind,
        )
    }

    @Test
    fun `a wall and a floor differ by shape, not by tint alone`() {
        // Device feedback on 4.52.0: with a faint tint as the only difference, a wall and a
        // floor read the same on screen. The shape must differ even with the tints equalised.
        val floor = planeMaterialPresetFor(Plane.Type.HORIZONTAL_UPWARD_FACING)
        val wall = planeMaterialPresetFor(Plane.Type.VERTICAL)
        val wallInFloorTint = wall.copy(
            gridR = floor.gridR, gridG = floor.gridG, gridB = floor.gridB,
            markAlpha = floor.markAlpha,
        )
        assertTrue(wallInFloorTint != floor)
    }

    @Test
    fun `wall and ceiling marks stay readable on white paint in daylight`() {
        // A pale tint over a white wall is no mark at all (emulator preview, bright background,
        // #4307). Linear relative luminance, white = 1.
        fun luminance(preset: PlaneMaterialPreset) =
            0.2126f * preset.gridR + 0.7152f * preset.gridG + 0.0722f * preset.gridB
        val wall = luminance(planeMaterialPresetFor(Plane.Type.VERTICAL))
        val ceiling = luminance(planeMaterialPresetFor(Plane.Type.HORIZONTAL_DOWNWARD_FACING))
        assertTrue("wall luminance $wall must stay below 0.5", wall < 0.5f)
        assertTrue("ceiling luminance $ceiling must stay below 0.5", ceiling < 0.5f)
        // ...and bright enough to read in a dark room.
        assertTrue("wall luminance $wall must stay above 0.2", wall > 0.2f)
        assertTrue("ceiling luminance $ceiling must stay above 0.2", ceiling > 0.2f)
    }

    @Test
    fun `the dark halo also outlines the reticle ring`() {
        val source = java.io.File("src/main/materials/plane_renderer.mat").readText()
        assertTrue(source.contains("shade = max(shade, ringRim * contrast"))
    }

    @Test
    fun `the shader values match the branches of plane_renderer mat`() {
        // `surfaceKind` in the material: below 0.5 dots, 0.5..1.5 dashes, above 1.5 rings.
        assertEquals(0f, PlaneSurfaceKind.FLOOR.shaderValue, 0f)
        assertEquals(1f, PlaneSurfaceKind.WALL.shaderValue, 0f)
        assertEquals(2f, PlaneSurfaceKind.CEILING.shaderValue, 0f)
        val source = java.io.File("src/main/materials/plane_renderer.mat").readText()
        assertTrue(source.contains("float wall = (kind > 0.5 && kind < 1.5) ? 1.0 : 0.0;"))
        assertTrue(source.contains("float ceiling = kind > 1.5 ? 1.0 : 0.0;"))
        assertTrue(
            "the renderer and the material must agree on the parameter name",
            source.contains("\"name\": \"${PlaneRenderer.MATERIAL_SURFACE_KIND}\""),
        )
    }

    @Test
    fun `every material parameter the renderer sets is declared by the material`() {
        val source = java.io.File("src/main/materials/plane_renderer.mat").readText()
        listOf(
            PlaneRenderer.MATERIAL_UV_SCALE,
            PlaneRenderer.MATERIAL_SURFACE_KIND,
            PlaneRenderer.MATERIAL_GRID_TINT,
            PlaneRenderer.MATERIAL_GRID_ALPHA,
            PlaneRenderer.MATERIAL_SURFACE_ALPHA,
            PlaneRenderer.MATERIAL_CONTRAST,
            PlaneRenderer.MATERIAL_SCAN_PROGRESS,
            PlaneRenderer.MATERIAL_SCAN_PLANE_RADIUS,
            PlaneRenderer.MATERIAL_OPACITY,
            PlaneRenderer.MATERIAL_FOCUS,
        ).forEach { name ->
            // setParameter on an undeclared name throws at runtime, on the first detected plane.
            assertTrue("`$name` is not declared", source.contains("\"name\": \"$name\""))
        }
    }

    // ── Planes too small to be a surface (#4307) ────────────────────────────────────────────

    @Test
    fun `a sliver of wall on a bag or a door edge is not drawn`() {
        assertFalse(isPlaneLargeEnough(Plane.Type.VERTICAL, extentX = 0.30f, extentZ = 0.30f))
        assertFalse(isPlaneLargeEnough(Plane.Type.VERTICAL, extentX = 0.60f, extentZ = 0.10f))
        assertFalse(isPlaneLargeEnough(Plane.Type.VERTICAL, extentX = 0.10f, extentZ = 0.60f))
    }

    @Test
    fun `a wall is drawn from 40 by 20 centimetres, in either orientation`() {
        assertTrue(isPlaneLargeEnough(Plane.Type.VERTICAL, MIN_WALL_LONGEST_M, MIN_WALL_SHORTEST_M))
        assertTrue(isPlaneLargeEnough(Plane.Type.VERTICAL, MIN_WALL_SHORTEST_M, MIN_WALL_LONGEST_M))
        assertTrue(isPlaneLargeEnough(Plane.Type.VERTICAL, extentX = 2f, extentZ = 1.2f))
    }

    @Test
    fun `a floor is drawn early — a table top must not wait`() {
        for (type in listOf(Plane.Type.HORIZONTAL_UPWARD_FACING, Plane.Type.HORIZONTAL_DOWNWARD_FACING)) {
            assertTrue(isPlaneLargeEnough(type, MIN_HORIZONTAL_LONGEST_M, MIN_HORIZONTAL_SHORTEST_M))
            assertTrue(isPlaneLargeEnough(type, extentX = 0.25f, extentZ = 0.25f))
            assertFalse(isPlaneLargeEnough(type, extentX = 0.15f, extentZ = 0.15f))
            assertFalse(isPlaneLargeEnough(type, extentX = 0.50f, extentZ = 0.05f))
        }
        assertTrue(
            "walls are held to a higher bar than floors: that is where the stray planes are",
            MIN_WALL_LONGEST_M > MIN_HORIZONTAL_LONGEST_M && MIN_WALL_SHORTEST_M > MIN_HORIZONTAL_SHORTEST_M,
        )
    }

    @Test
    fun `a plane below the minimum size gets no visualizer`() {
        assertEquals(
            PlaneVisualizerAction.SKIP,
            planeVisualizerAction(
                TrackingState.TRACKING, isSubsumed = false, hasVisualizer = false,
                isLargeEnough = false,
            ),
        )
        assertEquals(
            PlaneVisualizerAction.CREATE,
            planeVisualizerAction(
                TrackingState.TRACKING, isSubsumed = false, hasVisualizer = false,
                isLargeEnough = true,
            ),
        )
    }

    @Test
    fun `a plane already drawn keeps its visualizer when ARCore shrinks it`() {
        // No flicker at the threshold: the size gates creation only.
        assertEquals(
            PlaneVisualizerAction.UPDATE,
            planeVisualizerAction(
                TrackingState.TRACKING, isSubsumed = false, hasVisualizer = true,
                isLargeEnough = false,
            ),
        )
    }

    @Test
    fun `the size never rescues a subsumed or stopped plane`() {
        assertEquals(
            PlaneVisualizerAction.SKIP,
            planeVisualizerAction(
                TrackingState.TRACKING, isSubsumed = true, hasVisualizer = false,
                isLargeEnough = true,
            ),
        )
        assertEquals(
            PlaneVisualizerAction.SKIP,
            planeVisualizerAction(
                TrackingState.STOPPED, isSubsumed = false, hasVisualizer = true,
                isLargeEnough = true,
            ),
        )
    }
}
