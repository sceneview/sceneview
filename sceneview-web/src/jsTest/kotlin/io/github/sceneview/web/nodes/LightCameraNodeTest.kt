package io.github.sceneview.web.nodes

import io.github.sceneview.math.Position
import io.github.sceneview.math.Transform
import io.github.sceneview.web.bindings.Entity
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit tests for the #2024 slice-2b node subtypes ([LightNode], [CameraNode]).
 *
 * Same recording-fake strategy as [NodeSubtypesTest]: pure Kotlin behaviour
 * (config caching, controller flush-on-wire, destroy guards, the per-frame
 * camera sync) is asserted through recording seams. The real Filament
 * `LightManager` instance bindings and `Camera.setModelMatrix` — which Karma
 * cannot load — are proven in-browser by the web-demo `kotlin-bundle.spec.ts`
 * #2024-P1 slice-2b probes instead (the embind probe-first rule).
 */
class LightCameraNodeTest {

    private class RecordingBackend : NodeBackend {
        var destroyCalls = 0
        val adopted = mutableListOf<Entity>()
        override fun setLocalTransform(transform: Transform) = Unit
        override fun setParent(parent: NodeBackend?) = Unit
        override fun adoptChildEntity(child: Entity) { adopted.add(child) }
        override fun destroy() { destroyCalls++ }
    }

    private class RecordingLightController : LightController {
        var intensity: Double? = null
        var color: Triple<Double, Double, Double>? = null
        var direction: Triple<Double, Double, Double>? = null
        var position: Triple<Double, Double, Double>? = null
        var calls = 0
        override fun setIntensity(value: Double) { intensity = value; calls++ }
        override fun setColor(r: Double, g: Double, b: Double) { color = Triple(r, g, b); calls++ }
        override fun setDirection(x: Double, y: Double, z: Double) { direction = Triple(x, y, z); calls++ }
        override fun setPosition(x: Double, y: Double, z: Double) { position = Triple(x, y, z); calls++ }
    }

    private class RecordingCameraController : CameraController {
        var lastMatrix: FloatArray? = null
        var syncs = 0
        override fun setModelMatrix(columnMajorMat4: FloatArray) {
            lastMatrix = columnMajorMat4.copyOf()
            syncs++
        }
    }

    private fun fakeEntity(id: Int): Entity = id.asDynamic().unsafeCast<Entity>()

    /** Round to 3 dp — LightConfig stores Float, applyConfig widens to Double. */
    private fun round3(v: Double): Double = kotlin.math.round(v * 1000.0) / 1000.0

    // --- LightNode -----------------------------------------------------------

    @Test
    fun lightNodeIsANodeAndCarriesItsType() {
        val light = LightNode(RecordingBackend(), LightType.POINT)
        assertEquals(LightType.POINT, light.type)

        val root = Node(RecordingBackend())
        light.parent = root
        assertSame(root, light.parent)
        assertTrue(root.childNodes.contains(light))
    }

    @Test
    fun mutatorsForwardToTheControllerOnceWired() {
        val controller = RecordingLightController()
        val light = LightNode(RecordingBackend(), LightType.DIRECTIONAL)
        light.controller = controller

        light.intensity = 42_000.0
        light.color(0.1, 0.2, 0.3)
        light.direction(1.0, 0.0, 0.0)

        assertEquals(42_000.0, controller.intensity)
        assertEquals(Triple(0.1, 0.2, 0.3), controller.color)
        assertEquals(Triple(1.0, 0.0, 0.0), controller.direction)
    }

    @Test
    fun wiringTheControllerFlushesTheCachedConfig() {
        // A directional light configured BEFORE the controller is wired must
        // still land — the SceneView factory sets the controller after build.
        val light = LightNode(RecordingBackend(), LightType.DIRECTIONAL)
        light.intensity = 7_000.0
        light.color(0.4, 0.5, 0.6)
        light.direction(0.0, -1.0, 0.0)

        val controller = RecordingLightController()
        light.controller = controller

        assertEquals(7_000.0, controller.intensity)
        assertEquals(Triple(0.4, 0.5, 0.6), controller.color)
        assertEquals(Triple(0.0, -1.0, 0.0), controller.direction)
        // A directional light flushes direction, never position.
        assertEquals(null, controller.position)
    }

    @Test
    fun wiringAPointLightControllerFlushesPositionNotDirection() {
        val light = LightNode(RecordingBackend(), LightType.POINT)
        light.position(2.0, 3.0, 4.0)

        val controller = RecordingLightController()
        light.controller = controller

        assertEquals(Triple(2.0, 3.0, 4.0), controller.position)
        assertEquals(null, controller.direction)
    }

    @Test
    fun applyConfigThenWireFlushesTheConfigValuesNotTheDefaults() {
        // The #2024 slice-2b bug: SceneView.addLightNode builds the light from
        // `config`, then wires the controller — whose flush pushes the NODE's
        // fields. If those fields were never seeded from `config`, the flush
        // clobbers the custom light back to the defaults (100k white). This
        // asserts the addLightNode seed order (applyConfig BEFORE wire) lands
        // the CUSTOM values on the controller — the flat-path byte-identity the
        // KDoc + changelog claim. (The existing wiring test sets fields via the
        // node setters, not from a LightConfig, so it does not cover this glue.)
        val config = LightConfig().apply {
            directional()
            intensity(500_000.0)
            color(1.0f, 0.2f, 0.2f)
            direction(0.1f, -0.9f, 0.3f)
        }
        val light = LightNode(RecordingBackend(), config.type)

        light.applyConfig(config)              // addLightNode: seed BEFORE wire
        val controller = RecordingLightController()
        light.controller = controller          // addLightNode: wire → flush

        assertEquals(500_000.0, controller.intensity, "custom intensity must survive the flush")
        val color = controller.color!!
        assertEquals(1.0, round3(color.first), "custom colour R must survive the flush")
        assertEquals(0.2, round3(color.second), "custom colour G must survive the flush")
        assertEquals(0.2, round3(color.third), "custom colour B must survive the flush")
        val direction = controller.direction!!
        assertEquals(0.1, round3(direction.first), "custom direction x must survive the flush")
        assertEquals(-0.9, round3(direction.second), "custom direction y must survive the flush")
        assertEquals(0.3, round3(direction.third), "custom direction z must survive the flush")
        // A directional light must not push a position.
        assertEquals(null, controller.position)
    }

    @Test
    fun applyConfigSeedsPositionForANonDirectionalLight() {
        val config = LightConfig().apply {
            point()
            intensity(80_000.0)
            position(1.5f, 2.5f, 3.5f)
        }
        val light = LightNode(RecordingBackend(), config.type)

        light.applyConfig(config)
        val controller = RecordingLightController()
        light.controller = controller

        assertEquals(80_000.0, controller.intensity)
        val position = controller.position!!
        assertEquals(1.5, round3(position.first))
        assertEquals(2.5, round3(position.second))
        assertEquals(3.5, round3(position.third))
        assertEquals(null, controller.direction, "a point light must not push a direction")
    }

    @Test
    fun lightMutationsAfterDestroyNeverReachTheController() {
        val controller = RecordingLightController()
        val light = LightNode(RecordingBackend(), LightType.POINT)
        light.controller = controller
        val callsAfterWire = controller.calls

        light.destroy()
        light.intensity = 1.0
        light.color(0.0, 0.0, 0.0)
        light.position(9.0, 9.0, 9.0)

        assertEquals(
            callsAfterWire, controller.calls,
            "post-destroy light mutations must not reach the freed light",
        )
    }

    // --- CameraNode ----------------------------------------------------------

    @Test
    fun cameraNodeIsANode() {
        val camera = CameraNode(RecordingBackend())
        val root = Node(RecordingBackend())
        camera.parent = root
        assertTrue(root.childNodes.contains(camera))
    }

    @Test
    fun onFramePushesTheWorldTransformToTheCamera() {
        val controller = RecordingCameraController()
        val camera = CameraNode(RecordingBackend())
        camera.controller = controller
        camera.position = Position(2f, 3f, 4f)

        camera.onFrame(0.016f)

        assertEquals(1, controller.syncs)
        // Column-major mat4: translation lives in elements 12/13/14.
        val m = controller.lastMatrix!!
        assertContentEquals(floatArrayOf(2f, 3f, 4f), floatArrayOf(m[12], m[13], m[14]))
    }

    @Test
    fun onFrameIsANoOpWithoutAController() {
        val camera = CameraNode(RecordingBackend())
        // No controller wired — must not throw.
        camera.onFrame(0.016f)
    }

    @Test
    fun onFrameAfterDestroyNeverSyncsTheCamera() {
        val controller = RecordingCameraController()
        val camera = CameraNode(RecordingBackend())
        camera.controller = controller

        camera.destroy()
        camera.onFrame(0.016f)

        assertEquals(0, controller.syncs, "a destroyed camera node must not touch the camera")
    }

    @Test
    fun destroyingALightNodeDoesNotTouchTheAdoptedLightEntity() {
        val backend = RecordingBackend()
        val light = LightNode(backend, LightType.SPOT)
        light.adoptChildEntity(fakeEntity(11))

        light.destroy()

        // destroy() frees the node's OWN pivot exactly once; the adopted light
        // entity belongs to the SceneView light tracker (slice-2b ownership).
        assertEquals(1, backend.destroyCalls)
        assertEquals(1, backend.adopted.size)
    }
}
