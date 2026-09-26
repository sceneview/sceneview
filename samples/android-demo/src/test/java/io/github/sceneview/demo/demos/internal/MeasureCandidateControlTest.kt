package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MeasureCandidateControlTest {
    @Test fun `Add point commits the displayed candidate without resolving another ray`() {
        val control = MeasureCandidateControl<Any>()
        val older = Any()
        val displayed = Any()
        control.update(older)
        control.update(displayed)
        var anchored: Any? = null
        val result = control.consume { candidate -> anchored = candidate; "point" }
        assertSame(displayed, anchored)
        assertEquals("point", result)
        assertNull(control.current)
    }

    @Test fun `tracking loss clears the target and cannot commit its stale candidate`() {
        val control = MeasureCandidateControl<String>()
        control.update("surface")
        control.update(null)
        var commits = 0
        assertNull(control.consume { commits++; it })
        assertEquals(0, commits)
    }

    @Test fun `one activation consumes one candidate and a new frame rearms it`() {
        val control = MeasureCandidateControl<String>()
        var commits = 0
        control.update("first")
        assertEquals("first", control.consume { commits++; it })
        assertNull(control.consume { commits++; it })
        control.update("second")
        assertEquals("second", control.consume { commits++; it })
        assertEquals(2, commits)
    }

    @Test fun `anchor failure adds no point and cannot reuse the failed candidate`() {
        val control = MeasureCandidateControl<String>()
        control.update("unanchorable")
        assertNull(control.consume<String> { null })
        assertNull(control.current)
    }
}
