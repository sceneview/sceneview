package io.github.sceneview.math

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Mat4
import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.inverse
import dev.romainguy.kotlin.math.translation
import dev.romainguy.kotlin.math.scale
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class TransformConversionsTest {

    private val epsilon = 0.01f

    private fun assertFloat3Near(expected: Float3, actual: Float3, msg: String = "") {
        assertTrue(abs(expected.x - actual.x) < epsilon, "$msg x: ${expected.x} vs ${actual.x}")
        assertTrue(abs(expected.y - actual.y) < epsilon, "$msg y: ${expected.y} vs ${actual.y}")
        assertTrue(abs(expected.z - actual.z) < epsilon, "$msg z: ${expected.z} vs ${actual.z}")
    }

    @Test
    fun identityTransformNoChange() {
        val worldTransform = Mat4.identity()
        val worldToLocal = inverse(worldTransform)
        val pos = Position(3f, 4f, 5f)

        val localPos = worldToLocalPosition(pos, worldToLocal)
        assertFloat3Near(pos, localPos, "Identity should not change position")
    }

    @Test
    fun translationConvertsCorrectly() {
        // Node is at (10, 0, 0) in world space
        val worldTransform = translation(Float3(10f, 0f, 0f))
        val worldToLocal = inverse(worldTransform)

        // World point (15, 0, 0) should be (5, 0, 0) in local space
        val localPos = worldToLocalPosition(Position(15f, 0f, 0f), worldToLocal)
        assertFloat3Near(Position(5f, 0f, 0f), localPos)
    }

    @Test
    fun roundTripPositionPreserved() {
        val worldTransform = translation(Float3(5f, -3f, 7f))
        val worldToLocal = inverse(worldTransform)
        val originalPos = Position(1f, 2f, 3f)

        val localPos = worldToLocalPosition(originalPos, worldToLocal)
        val roundTrip = localToWorldPosition(localPos, worldTransform)
        assertFloat3Near(originalPos, roundTrip, "Round-trip should preserve position")
    }

    @Test
    fun scaleConvertsCorrectly() {
        // Node scaled 2x
        val worldTransform = scale(Float3(2f, 2f, 2f))
        val worldToLocal = inverse(worldTransform)

        // World (4, 6, 8) → local (2, 3, 4)
        val localPos = worldToLocalPosition(Position(4f, 6f, 8f), worldToLocal)
        assertFloat3Near(Position(2f, 3f, 4f), localPos)
    }

    @Test
    fun localToWorldScaleIgnoresParentTranslation() {
        // Parent: world translation (10, 0, 0), uniform world scale 2.
        // worldTransform = Translate(10,0,0) · Scale(2,2,2).
        // A scale conversion must NOT pick up the translation — #2489.
        // Pre-fix (Mat4 * Float3 point multiply) this returned (12, 2, 2).
        val worldTransform = translation(Float3(10f, 0f, 0f)) * scale(Float3(2f, 2f, 2f))

        val worldScale = localToWorldScale(Scale(1f, 1f, 1f), worldTransform)
        assertFloat3Near(Scale(2f, 2f, 2f), worldScale, "localToWorldScale must not leak translation")
    }

    @Test
    fun worldToLocalScaleIgnoresParentTranslation() {
        // Inverse direction of #2489. Pre-fix this returned (-4, 1, 1).
        val worldTransform = translation(Float3(10f, 0f, 0f)) * scale(Float3(2f, 2f, 2f))
        val worldToLocal = inverse(worldTransform)

        val localScale = worldToLocalScale(Scale(2f, 2f, 2f), worldToLocal)
        assertFloat3Near(Scale(1f, 1f, 1f), localScale, "worldToLocalScale must not leak translation")
    }

    @Test
    fun scaleRoundTripUnderTranslatingTransform() {
        // Parent with translation on all three axes + non-unit scale: pre-fix the
        // point-multiply corrupts every component; the round-trip must recover the input.
        val worldTransform = translation(Float3(10f, 20f, 30f)) * scale(Float3(2f, 3f, 4f))
        val worldToLocal = inverse(worldTransform)

        // localToWorldScale(unit) must equal the parent's world scale (2, 3, 4).
        val parentWorldScale = localToWorldScale(Scale(1f, 1f, 1f), worldTransform)
        assertFloat3Near(Scale(2f, 3f, 4f), parentWorldScale, "Parent world scale")

        val original = Scale(0.5f, 1.5f, 2f)
        val world = localToWorldScale(original, worldTransform)
        val roundTrip = worldToLocalScale(world, worldToLocal)
        assertFloat3Near(original, roundTrip, "Scale round-trip should preserve scale")
    }

    @Test
    fun localToWorldPositionWithTranslation() {
        val worldTransform = translation(Float3(10f, 20f, 30f))
        val localPos = Position(1f, 2f, 3f)

        val worldPos = localToWorldPosition(localPos, worldTransform)
        assertFloat3Near(Position(11f, 22f, 33f), worldPos)
    }

    @Test
    fun transformRoundTripPreserved() {
        val worldTransform = translation(Float3(5f, 0f, 0f))
        val worldToLocal = inverse(worldTransform)
        val childTransform = translation(Float3(1f, 1f, 1f))

        val localT = worldToLocalTransform(childTransform, worldToLocal)
        val roundTrip = localToWorldTransform(localT, worldTransform)

        // Check the translation component (column 3)
        assertFloat3Near(
            Float3(childTransform[3].x, childTransform[3].y, childTransform[3].z),
            Float3(roundTrip[3].x, roundTrip[3].y, roundTrip[3].z),
            "Transform round-trip"
        )
    }

    @Test
    fun computeWorldToLocalMatchesInverse() {
        val worldTransform = translation(Float3(7f, -2f, 4f))
        val computed = computeWorldToLocal(worldTransform)
        val expected = inverse(worldTransform)

        // Check translation columns match
        assertFloat3Near(
            Float3(expected[3].x, expected[3].y, expected[3].z),
            Float3(computed[3].x, computed[3].y, computed[3].z)
        )
    }

    // --- Direction (#3329) ---

    @Test
    fun worldToLocalDirectionIgnoresTranslation() {
        // The whole point of the w = 0 transform: a direction is a free vector, so moving the
        // node must not move it. The position conversion would return (-7, 2, -3) here.
        val worldToLocal = inverse(translation(Float3(7f, -2f, 4f)))
        assertFloat3Near(
            Float3(0f, 0f, -1f),
            worldToLocalDirection(Float3(0f, 0f, -1f), worldToLocal)
        )
    }

    @Test
    fun worldToLocalDirectionFlipsSignForAHalfTurnedNode() {
        // A quad yawed 180° is seen from behind by a camera that still looks down -Z: in the
        // node's own space the picking ray now travels towards +Z, which is what `isBackFaceHit`
        // reads to mirror the touch mapping.
        val worldTransform = Transform(rotation = Rotation(y = 180f))
        val local = worldToLocalDirection(Float3(0f, 0f, -1f), inverse(worldTransform))
        assertTrue(local.z > 0f, "Expected a back-face ray (local z > 0) but got ${local.z}")
    }

    @Test
    fun localToWorldDirectionRoundTrips() {
        val worldTransform = Transform(position = Position(2f, 5f, -3f), rotation = Rotation(12f, 37f, -8f))
        val direction = Float3(0.2f, -0.4f, -0.9f)
        assertFloat3Near(
            direction,
            worldToLocalDirection(
                localToWorldDirection(direction, worldTransform),
                inverse(worldTransform)
            )
        )
    }
}
