package io.github.sceneview.web

import io.github.sceneview.web.nodes.CameraConfig
import io.github.sceneview.web.nodes.GeometryConfig
import io.github.sceneview.web.nodes.GeometryType
import io.github.sceneview.web.nodes.LightConfig
import io.github.sceneview.web.nodes.LightType
import io.github.sceneview.web.nodes.ModelConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CameraConfigTest {

    @Test
    fun defaultValues() {
        val config = CameraConfig()
        assertEquals(0.0, config.eyeX)
        assertEquals(1.5, config.eyeY)
        assertEquals(5.0, config.eyeZ)
        assertEquals(0.0, config.targetX)
        assertEquals(0.0, config.targetY)
        assertEquals(0.0, config.targetZ)
        assertEquals(45.0, config.fovDegrees)
        assertEquals(0.1, config.nearPlane)
        assertEquals(1000.0, config.farPlane)
    }

    @Test
    fun eyePosition() {
        val config = CameraConfig().apply {
            eye(1.0, 2.0, 3.0)
        }
        assertEquals(1.0, config.eyeX)
        assertEquals(2.0, config.eyeY)
        assertEquals(3.0, config.eyeZ)
    }

    @Test
    fun targetPosition() {
        val config = CameraConfig().apply {
            target(4.0, 5.0, 6.0)
        }
        assertEquals(4.0, config.targetX)
        assertEquals(5.0, config.targetY)
        assertEquals(6.0, config.targetZ)
    }

    @Test
    fun fovAndClipPlanes() {
        val config = CameraConfig().apply {
            fov(60.0)
            near(0.01)
            far(500.0)
        }
        assertEquals(60.0, config.fovDegrees)
        assertEquals(0.01, config.nearPlane)
        assertEquals(500.0, config.farPlane)
    }

    @Test
    fun exposure() {
        val config = CameraConfig().apply {
            exposure(2.8, 1.0 / 60.0, 200.0)
        }
        assertEquals(2.8, config.aperture)
        assertEquals(200.0, config.sensitivity)
    }
}

class LightConfigTest {

    @Test
    fun defaultIsDirectional() {
        val config = LightConfig()
        assertEquals(LightType.DIRECTIONAL, config.type)
        assertEquals(10_000.0, config.intensity)
    }

    @Test
    fun pointLight() {
        val config = LightConfig().apply {
            point()
            intensity(50_000.0)
            position(1f, 2f, 3f)
        }
        assertEquals(LightType.POINT, config.type)
        assertEquals(50_000.0, config.intensity)
        assertEquals(1f, config.positionX)
        assertEquals(2f, config.positionY)
        assertEquals(3f, config.positionZ)
    }

    @Test
    fun spotLight() {
        val config = LightConfig().apply {
            spot()
            color(0.8f, 0.9f, 1.0f)
            direction(0f, -1f, 0f)
        }
        assertEquals(LightType.SPOT, config.type)
        assertEquals(0.8f, config.colorR)
        assertEquals(0.9f, config.colorG)
        assertEquals(1.0f, config.colorB)
    }
}

class ModelConfigTest {

    @Test
    fun urlIsSet() {
        val config = ModelConfig("models/test.glb")
        assertEquals("models/test.glb", config.url)
        assertNull(config.onLoaded)
        assertTrue(config.autoAnimate)
        assertEquals(1.0f, config.scale)
    }

    @Test
    fun customScale() {
        val config = ModelConfig("models/big.glb").apply {
            scale(0.5f)
            autoAnimate(false)
        }
        assertEquals(0.5f, config.scale)
        assertEquals(false, config.autoAnimate)
    }
}

class GeometryConfigTest {

    @Test
    fun defaultsToLitPbr() {
        val config = GeometryConfig()
        assertEquals(GeometryType.CUBE, config.geometryType)
        assertEquals(0.8, config.colorR)
        assertEquals(0.8, config.colorG)
        assertEquals(0.8, config.colorB)
        assertEquals(1.0, config.colorA)
        assertFalse(config.unlit, "GeometryConfig must default to lit PBR — unlit is opt-in.")
    }

    @Test
    fun unlitToggle() {
        // `unlit()` and `unlit(true)` are equivalent; pin the default-arg shape so a
        // future signature change can't silently break the no-arg call.
        val a = GeometryConfig().apply { unlit() }
        assertTrue(a.unlit)
        val b = GeometryConfig().apply { unlit(false) }
        assertFalse(b.unlit)
    }

    @Test
    fun colorAndUnlitCompose() {
        val config = GeometryConfig().apply {
            sphere()
            color(1.0, 0.0, 0.0, 0.4)
            unlit()
        }
        assertEquals(GeometryType.SPHERE, config.geometryType)
        assertEquals(1.0, config.colorR)
        assertEquals(0.4, config.colorA)
        assertTrue(config.unlit)
    }
}
