package io.github.sceneview.node

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.Filament
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.utils.Utils
import io.github.sceneview.createEglContext
import io.github.sceneview.createEngine
import io.github.sceneview.math.Position
import io.github.sceneview.safeDestroy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for [PhysicsBody] — the lightweight Euler-integration physics simulation.
 */
@RunWith(AndroidJUnit4::class)
class PhysicsBodyTest {

    private lateinit var engine: com.google.android.filament.Engine

    @Before
    fun setup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            Gltfio.init(); Filament.init(); Utils.init()
            val eglContext = createEglContext()
            engine = createEngine(eglContext)
        }
    }

    @After
    fun teardown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            engine.safeDestroy()
        }
    }

    @Test
    fun step_gravityPullsDown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val node = Node(engine).apply {
                position = Position(0f, 5f, 0f)
            }
            val body = PhysicsBody(
                node = node,
                restitution = 0f,
                floorY = 0f,
                radius = 0f,
                initialVelocity = Position(0f, 0f, 0f)
            )

            // Simulate ~16ms at 60fps
            val t0 = 0L
            val t1 = 16_000_000L // 16ms in nanos
            body.step(t1, t0)

            assertTrue("position should have moved down", node.position.y < 5f)
            node.destroy()
        }
    }

    @Test
    fun step_floorCollisionBounces() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val node = Node(engine).apply {
                position = Position(0f, 0.001f, 0f) // just above floor
            }
            val body = PhysicsBody(
                node = node,
                restitution = 0.5f,
                floorY = 0f,
                radius = 0f,
                initialVelocity = Position(0f, -5f, 0f) // falling fast
            )

            body.step(16_000_000L, 0L)

            // After floor collision, y should be at or above floor
            assertTrue("should be at floor level", node.position.y >= 0f)
            // Velocity should have reversed direction (bounced up)
            assertTrue("velocity should have bounced up", body.velocity.y >= 0f)

            node.destroy()
        }
    }

    @Test
    fun step_sleepsWhenBounceIsNegligible() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val node = Node(engine).apply {
                position = Position(0f, 0.001f, 0f)
            }
            val body = PhysicsBody(
                node = node,
                restitution = 0.01f, // very inelastic
                floorY = 0f,
                radius = 0f,
                initialVelocity = Position(0f, -0.1f, 0f) // gentle fall
            )

            assertFalse("should not be asleep initially", body.isAsleep)

            // Step multiple times to exhaust bounce energy
            var t = 0L
            repeat(100) {
                body.step(t + 16_000_000L, t)
                t += 16_000_000L
            }

            assertTrue("should be asleep after many bounces", body.isAsleep)
            node.destroy()
        }
    }

    @Test
    fun step_asleepBody_doesNotMove() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val node = Node(engine).apply {
                position = Position(0f, 0f, 0f)
            }
            val body = PhysicsBody(
                node = node,
                restitution = 0f,
                floorY = 0f,
                radius = 0f,
                initialVelocity = Position(0f, -0.01f, 0f)
            )

            // Force sleep by simulating many steps
            var t = 0L
            repeat(200) {
                body.step(t + 16_000_000L, t)
                t += 16_000_000L
            }

            val yBefore = node.position.y
            body.step(t + 16_000_000L, t)
            assertEquals("position should not change when asleep", yBefore, node.position.y, 0.001f)

            node.destroy()
        }
    }

    @Test
    fun step_radiusOffsetsFloorContact() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val radius = 0.5f
            val node = Node(engine).apply {
                position = Position(0f, radius + 0.001f, 0f)
            }
            val body = PhysicsBody(
                node = node,
                restitution = 0f,
                floorY = 0f,
                radius = radius,
                initialVelocity = Position(0f, -10f, 0f)
            )

            body.step(16_000_000L, 0L)

            // Centre of sphere should rest at floorY + radius
            assertTrue("sphere centre should be at floorY + radius",
                node.position.y >= radius - 0.01f)

            node.destroy()
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun mass_doesNotAffectFall() {
        // #1599: gravity is an acceleration and therefore mass-independent. A heavy body and a
        // light body released from the same height must fall identically. This pins the honest
        // contract behind the now-deprecated `mass` parameter (it is a documented no-op).
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val lightNode = Node(engine).apply { position = Position(0f, 5f, 0f) }
            val heavyNode = Node(engine).apply { position = Position(0f, 5f, 0f) }
            val light = PhysicsBody(node = lightNode, mass = 0.1f, restitution = 0f, floorY = -100f)
            val heavy = PhysicsBody(node = heavyNode, mass = 1000f, restitution = 0f, floorY = -100f)

            var t = 0L
            repeat(30) {
                light.step(t + 16_000_000L, t)
                heavy.step(t + 16_000_000L, t)
                t += 16_000_000L
            }

            assertEquals(
                "mass must not affect a gravity-only fall (mass is a no-op)",
                lightNode.position.y, heavyNode.position.y, 0.0001f
            )
            lightNode.destroy()
            heavyNode.destroy()
        }
    }

    @Test
    fun step_horizontalVelocity_isPreserved() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val node = Node(engine).apply {
                position = Position(0f, 5f, 0f)
            }
            val body = PhysicsBody(
                node = node,
                initialVelocity = Position(2f, 0f, -1f)
            )

            body.step(16_000_000L, 0L)

            assertTrue("x should have moved right", node.position.x > 0f)
            assertTrue("z should have moved toward -z", node.position.z < 0f)

            node.destroy()
        }
    }
}
