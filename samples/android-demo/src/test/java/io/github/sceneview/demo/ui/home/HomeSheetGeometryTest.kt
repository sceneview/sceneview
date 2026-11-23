package io.github.sceneview.demo.ui.home

import io.github.sceneview.demo.theme.SceneViewTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSheetGeometryTest {
    @Test
    fun `rest starts at 58 percent including the bottom navigation`() {
        assertEquals(0.58f, SceneViewTokens.Home.sheetRestFraction, 0f)
        for (height in listOf(640f, 800f, 1200f)) {
            for (navigation in listOf(0f, 80f, 104f)) {
                val peek = homeSheetPeekHeight(height, navigation)
                assertEquals(height * 0.58f, height - navigation - peek, 0.001f)
            }
        }
    }

    @Test
    fun `camera follows sheet travel from rest to expanded and clamps overscroll`() {
        val rest = 800f * 0.58f - 80f
        assertEquals(0f, homeSheetCameraProgress(rest, rest), 0f)
        assertEquals(0.5f, homeSheetCameraProgress(rest / 2, rest), 0f)
        assertEquals(1f, homeSheetCameraProgress(0f, rest), 0f)
        assertEquals(0f, homeSheetCameraProgress(rest + 50, rest), 0f)
        assertEquals(1f, homeSheetCameraProgress(-50f, rest), 0f)
        assertEquals(0f, homeSheetCameraProgress(Float.NaN, rest), 0f)
        assertEquals(0f, homeSheetCameraProgress(0f, 0f), 0f)
        val first = nightCameraPose(film = 1f)
        val pulled = nightCameraPose(film = 1f, scroll = homeSheetCameraProgress(rest / 2, rest))
        assertTrue(first.eyeX != pulled.eyeX)
        assertTrue(first.eyeY > pulled.eyeY)
    }

    @Test
    fun `orbit holds two seconds after release then returns smoothly to film`() {
        val orbit = HeroOrbit()
        orbit.begin()
        orbit.drag(0.25f, 0.1f)
        val yaw = orbit.yaw
        val lift = orbit.lift
        orbit.release(1_000_000_000L)
        orbit.frame(3_000_000_000L)
        assertEquals(yaw, orbit.yaw, 0f)
        assertEquals(lift, orbit.lift, 0f)
        orbit.frame(3_016_666_667L)
        assertTrue(kotlin.math.abs(orbit.yaw) < kotlin.math.abs(yaw))
        assertTrue(kotlin.math.abs(orbit.yaw) > kotlin.math.abs(yaw) * 0.9f)
        repeat(240) { orbit.frame(3_033_333_334L + it * 16_666_667L) }
        assertEquals(0f, orbit.yaw, 0.001f)
        assertEquals(0f, orbit.lift, 0.001f)
    }

    @Test
    fun `a new drag takes over a returning orbit without jumping`() {
        val orbit = HeroOrbit()
        orbit.begin()
        orbit.drag(0.1f, 0f)
        orbit.release(1_000_000_000L)
        orbit.frame(3_200_000_000L)
        val yaw = orbit.yaw
        orbit.begin()
        orbit.frame(4_000_000_000L)
        assertEquals(yaw, orbit.yaw, 0f)
        val film = nightCameraPose(film = 3f, tiltX = 0.5f)
        val dragged = nightCameraPose(film = 3f, tiltX = 0.5f, orbitYaw = orbit.yaw)
        assertTrue(film.eyeX != dragged.eyeX)
    }
}
