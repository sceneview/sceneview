package io.github.sceneview.web

import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectionResolutionTest {

    private val autoNear = 0.02
    private val autoFar = 2400.0

    private fun resolve(fov: Double? = null, near: Double? = null, far: Double? = null) =
        ContentCentering.resolveProjection(fov, near, far, autoNear, autoFar)

    @Test
    fun noExplicitValuesUseDefaultFovAndAutomaticPlanes() {
        assertEquals(ContentCentering.Projection(45.0, autoNear, autoFar), resolve())
    }

    @Test
    fun explicitNearOnlyKeepsAutomaticFar() {
        assertEquals(
            ContentCentering.Projection(45.0, 0.001, autoFar),
            resolve(near = 0.001),
        )
    }

    @Test
    fun explicitFarOnlyKeepsAutomaticNear() {
        assertEquals(
            ContentCentering.Projection(45.0, autoNear, 5000.0),
            resolve(far = 5000.0),
        )
    }

    @Test
    fun bothExplicitPlanesWin() {
        assertEquals(
            ContentCentering.Projection(45.0, 0.001, 5000.0),
            resolve(near = 0.001, far = 5000.0),
        )
    }

    @Test
    fun explicitFovWins() {
        assertEquals(ContentCentering.Projection(90.0, autoNear, autoFar), resolve(fov = 90.0))
    }

    @Test
    fun invalidFovIsIgnored() {
        for (fov in listOf(0.0, 180.0, Double.NaN)) {
            assertEquals(45.0, resolve(fov = fov).fovDegrees, "fov=$fov")
        }
    }

    @Test
    fun invalidNearIsIgnored() {
        for (near in listOf(0.0, -1.0, Double.NaN)) {
            assertEquals(autoNear, resolve(near = near).nearPlane, "near=$near")
        }
    }

    @Test
    fun farAtOrBehindEffectiveNearIsIgnored() {
        assertEquals(autoFar, resolve(far = autoNear).farPlane)
        assertEquals(autoFar, resolve(near = 2.0, far = 2.0).farPlane)
        assertEquals(autoFar, resolve(near = 2.0, far = 1.0).farPlane)
    }

    @Test
    fun nonFiniteFarIsIgnored() {
        for (far in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            assertEquals(autoFar, resolve(far = far).farPlane, "far=$far")
        }
    }
}
