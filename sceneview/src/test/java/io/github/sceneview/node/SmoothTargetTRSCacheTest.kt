package io.github.sceneview.node

import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Transform
import io.github.sceneview.math.quaternion
import io.github.sceneview.math.slerp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Behaviour-preservation + cache-invalidation proof for [SmoothTargetTRSCache] (#2324).
 *
 * #2324 caches the smooth-transform target's decomposed `(position, quaternion, scale)`
 * so the per-frame slerp stops re-decomposing a stable target `Mat4` every frame. The cache
 * must be **byte-identical** to decomposing the target each frame; only the redundant work
 * is removed. These tests run pure math (sceneview-core, no Filament `Engine`) so they need
 * no device. They assert:
 *
 *  1. the cached target TRS equals decomposing the target directly (the pre-#2324 path);
 *  2. the slerp output fed by the cached TRS is identical to slerp fed the freshly-decomposed
 *     target, across several interpolation factors `t` — i.e. the trajectory is unchanged;
 *  3. re-assigning a *different* target invalidates the cache and updates the result;
 *  4. mutating the *same* target `Mat4` instance in place also invalidates the cache.
 */
class SmoothTargetTRSCacheTest {

    private fun target(
        position: Position,
        rotation: Rotation,
        scale: Scale
    ): Transform = Transform(position = position, rotation = rotation, scale = scale)

    private fun assertTRSEquals(expected: Transform, actual: Triple<Position, Quaternion, Scale>) {
        val (p, q, s) = actual
        // The pre-#2324 path decomposed the target via exactly these three accessors.
        assertEquals(expected.position, p)
        assertEquals(expected.quaternion, q)
        assertEquals(expected.scale, s)
    }

    @Test
    fun `cached TRS equals decomposing the target directly`() {
        val cache = SmoothTargetTRSCache()
        val targetTransform = target(Position(1f, 2f, 3f), Rotation(10f, 20f, 30f), Scale(2f, 3f, 4f))

        // First call decomposes; subsequent calls on the same stable target return the cache.
        repeat(5) {
            assertTRSEquals(targetTransform, cache.get(targetTransform))
        }
    }

    @Test
    fun `slerp fed cached target is byte-identical to slerp fed freshly-decomposed target`() {
        val cache = SmoothTargetTRSCache()
        val start = Transform(Position(-5f, 0f, 1f), Rotation(0f, 0f, 0f), Scale(1f))
        val targetTransform = target(Position(4f, -2f, 7f), Rotation(15f, 45f, 90f), Scale(2f, 1f, 3f))

        val startPosition = start.position
        val startQuaternion = start.quaternion
        val startScale = start.scale

        // Several interpolation factors spanning the full animation (via varying deltaSeconds).
        // At a fixed speed, a larger deltaSeconds yields a larger lerp factor t.
        listOf(0.0, 0.001, 0.016, 0.033, 0.1, 0.5, 5.0).forEach { dt ->
            val speed = 5.0f

            // Pre-#2324 path: decompose the target every frame.
            val uncached = slerp(
                startPosition = startPosition,
                startQuaternion = startQuaternion,
                startScale = startScale,
                endPosition = targetTransform.position,
                endQuaternion = targetTransform.quaternion,
                endScale = targetTransform.scale,
                deltaSeconds = dt,
                speed = speed
            )

            // #2324 path: feed slerp the cached target TRS.
            val (cp, cq, cs) = cache.get(targetTransform)
            val cached = slerp(
                startPosition = startPosition,
                startQuaternion = startQuaternion,
                startScale = startScale,
                endPosition = cp,
                endQuaternion = cq,
                endScale = cs,
                deltaSeconds = dt,
                speed = speed
            )

            assertEquals("position at dt=$dt", uncached.first, cached.first)
            assertEquals("quaternion at dt=$dt", uncached.second, cached.second)
            assertEquals("scale at dt=$dt", uncached.third, cached.third)
        }
    }

    @Test
    fun `re-assigning a different target invalidates the cache`() {
        val cache = SmoothTargetTRSCache()
        val first = target(Position(1f, 0f, 0f), Rotation(0f, 0f, 0f), Scale(1f))
        val second = target(Position(0f, 9f, 0f), Rotation(0f, 90f, 0f), Scale(5f, 5f, 5f))

        // Prime the cache with the first target.
        assertTRSEquals(first, cache.get(first))

        // A different target instance/value must yield the second target's TRS, not the stale first.
        val (p, q, s) = cache.get(second)
        assertEquals(second.position, p)
        assertEquals(second.quaternion, q)
        assertEquals(second.scale, s)
        assertNotEquals("position must not be the stale first target", first.position, p)
    }

    @Test
    fun `mutating the same target Mat4 instance in place invalidates the cache`() {
        val cache = SmoothTargetTRSCache()
        // A single mutable Mat4 instance reused as the target across both calls.
        val mutableTarget = Transform(Position(1f, 1f, 1f), Rotation(0f, 0f, 0f), Scale(1f))

        val before = cache.get(mutableTarget)
        assertEquals(Position(1f, 1f, 1f), before.first)

        // Mutate the SAME instance in place (translation column). Keying the cache on value
        // (not identity) must detect this and re-decompose.
        val moved = Transform(Position(8f, -3f, 2f), Rotation(0f, 0f, 0f), Scale(1f))
        mutableTarget.x = moved.x
        mutableTarget.y = moved.y
        mutableTarget.z = moved.z
        mutableTarget.w = moved.w

        val after = cache.get(mutableTarget)
        assertEquals("in-place mutation must update the cached position", moved.position, after.first)
        assertNotEquals("must not return the stale pre-mutation position", before.first, after.first)
    }
}
