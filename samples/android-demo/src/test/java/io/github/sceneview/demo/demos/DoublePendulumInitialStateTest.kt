package io.github.sceneview.demo.demos

import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The release pose visible before the double pendulum's first simulation step (#4650). */
class DoublePendulumInitialStateTest {

    @Test
    fun `release pose contains only the two pendulum bobs and neither starts at the world origin`() {
        val release = pendulumReleaseState(
            length1 = PENDULUM_DEFAULT_LENGTH_1,
            length2 = PENDULUM_DEFAULT_LENGTH_2,
            gravity = PENDULUM_DEFAULT_GRAVITY,
        )

        val initialBodies = pendulumInitialBodyPositions(release)

        assertEquals("a double pendulum has one joint bob and one tip bob", 2, initialBodies.size)
        initialBodies.forEach { position ->
            assertNotEquals("no bob may appear as a stray ball at the world origin", Position(), position)
        }
        assertEquals(Position(0f, PIVOT_HEIGHT, 0f), release.pivot)
    }
}
