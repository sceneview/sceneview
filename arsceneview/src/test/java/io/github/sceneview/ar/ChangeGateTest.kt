package io.github.sceneview.ar

import com.google.ar.core.Config
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM regression test for [ChangeGate], the fix for #2573: `ARSceneView`'s reactive
 * `LaunchedEffect(flashMode)` / `LaunchedEffect(<Config.*Mode>)` blocks used to fire once on the
 * composable's initial composition — using the parameter's mount-time value — and silently revert
 * whatever the `sessionConfiguration` callback had just set on the session, because nothing
 * distinguished "the caller changed this parameter" from "this is the first, mount-time run".
 *
 * [ChangeGate] is seeded with the parameter's mount-time value so that exact first call is a
 * no-op, and only a genuine, later change to a *different* value returns `true`.
 */
class ChangeGateTest {

    @Test
    fun `the first call with the seeded (mount-time) value does not apply`() {
        val gate = ChangeGate(Config.DepthMode.DISABLED)

        assertFalse(gate.shouldApply(Config.DepthMode.DISABLED))
    }

    @Test
    fun `a call with a value different from the seed applies`() {
        val gate = ChangeGate(Config.DepthMode.DISABLED)

        assertTrue(gate.shouldApply(Config.DepthMode.AUTOMATIC))
    }

    @Test
    fun `repeating the same new value only applies once`() {
        val gate = ChangeGate(Config.DepthMode.DISABLED)

        assertTrue(gate.shouldApply(Config.DepthMode.AUTOMATIC))
        assertFalse(gate.shouldApply(Config.DepthMode.AUTOMATIC))
    }

    @Test
    fun `changing back to the original seed after a real change applies again`() {
        val gate = ChangeGate(Config.DepthMode.DISABLED)

        assertTrue(gate.shouldApply(Config.DepthMode.AUTOMATIC))
        assertTrue(gate.shouldApply(Config.DepthMode.DISABLED))
    }

    @Test
    fun `works for any equatable type, not just Config enums`() {
        val gate = ChangeGate(0)

        assertFalse(gate.shouldApply(0))
        assertTrue(gate.shouldApply(1))
        assertFalse(gate.shouldApply(1))
    }
}
