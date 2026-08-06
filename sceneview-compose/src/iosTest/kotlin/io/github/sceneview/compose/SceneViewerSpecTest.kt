package io.github.sceneview.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `SceneViewerSpec` is the recomposition key the iOS `SceneViewer` publishes through
 * `rememberUpdatedState`, which only notifies when the new value is *unequal*.
 *
 * These tests pin the two properties that makes work:
 *
 *  - equal specs compare equal even though a fresh instance is built on every
 *    composition (it was a plain class with identity equality, so every recomposition
 *    re-notified and re-applied the same model to the Swift renderer);
 *  - the bytes compare by CONTENT, so `ModelSource.Bytes`' own `contentEquals` survives
 *    being unpacked into a `ByteArray` field — a `ByteArray`'s own `equals` is reference
 *    equality, which would have silently thrown that guarantee away.
 */
class SceneViewerSpecTest {

    private fun spec(
        assetPath: String? = "models/helmet.glb",
        bytes: ByteArray? = null,
        distance: Float = 4f,
        onTap: (Boolean, Float, Float, Float, Float) -> Unit = { _, _, _, _, _ -> },
        onError: (String) -> Unit = { },
    ) = SceneViewerSpec(
        modelAssetPath = assetPath,
        modelUrl = null,
        modelBytes = bytes,
        cameraTargetX = 0f,
        cameraTargetY = 0f,
        cameraTargetZ = 0f,
        cameraDistance = distance,
        cameraAzimuthDegrees = 0f,
        cameraElevationDegrees = 0f,
        cameraGesturesEnabled = true,
        lightDirectionX = 0.3f,
        lightDirectionY = -1f,
        lightDirectionZ = -0.5f,
        lightIntensity = 100_000f,
        ambientIntensity = 1f,
        castShadows = true,
        environmentKind = "default",
        environmentRed = 0f,
        environmentGreen = 0f,
        environmentBlue = 0f,
        environmentAlpha = 1f,
        environmentHdrPath = null,
        environmentShowSkybox = true,
        onTap = onTap,
        onCameraMoved = { _, _, _ -> },
        onError = onError,
    )

    @Test
    fun two_specs_built_from_the_same_inputs_are_equal() {
        // The exact situation on every recomposition: same arguments, new instance.
        assertEquals(spec(), spec())
        assertEquals(spec().hashCode(), spec().hashCode())
    }

    @Test
    fun bytes_compare_by_content_not_identity() {
        val image = byteArrayOf(1, 2, 3, 4)
        // Two distinct arrays holding the same model — what
        // `ModelSource.Bytes(resource.readBytes())` produces on each composition.
        assertEquals(spec(assetPath = null, bytes = image.copyOf()), spec(assetPath = null, bytes = image.copyOf()))
        assertEquals(
            spec(assetPath = null, bytes = image.copyOf()).hashCode(),
            spec(assetPath = null, bytes = image.copyOf()).hashCode(),
        )
    }

    @Test
    fun different_bytes_are_not_equal() {
        assertNotEquals(
            spec(assetPath = null, bytes = byteArrayOf(1, 2, 3)),
            spec(assetPath = null, bytes = byteArrayOf(1, 2, 4)),
        )
    }

    @Test
    fun absent_bytes_on_both_sides_compare_equal() {
        assertEquals(spec(bytes = null), spec(bytes = null))
    }

    @Test
    fun bytes_present_on_one_side_only_are_not_equal() {
        assertNotEquals(spec(bytes = null), spec(bytes = byteArrayOf(1)))
        assertNotEquals(spec(bytes = byteArrayOf(1)), spec(bytes = null))
    }

    @Test
    fun a_changed_rendering_input_is_not_equal() {
        // The comparison must not be so lax that real changes stop propagating: a moved
        // camera has to reach the Swift side.
        assertNotEquals(spec(distance = 4f), spec(distance = 5f))
    }

    @Test
    fun callbacks_are_excluded_from_the_comparison() {
        // `SceneViewer` passes permanent forwarders that read the app's current lambdas
        // out of `rememberUpdatedState`, so they dispatch to the latest handler without
        // the spec changing. Comparing them would compare freshly allocated closures and
        // make every spec unequal — defeating the whole point.
        val a = spec(onTap = { _, _, _, _, _ -> }, onError = { })
        val b = spec(onTap = { _, _, _, _, _ -> }, onError = { })
        assertFalse(a.onTap === b.onTap, "the test itself must use two distinct closures")
        assertEquals(a, b)
    }

    @Test
    fun equality_is_reflexive_and_rejects_other_types() {
        val a = spec()
        assertTrue(a == a)
        assertFalse(a.equals("not a spec"))
    }
}
