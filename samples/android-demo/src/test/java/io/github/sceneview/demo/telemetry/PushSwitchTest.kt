package io.github.sceneview.demo.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Push on and off against a fake FCM whose topic leave + token delete completes on demand. */
class PushSwitchTest {

    private val backend = object : PushBackend {
        var enables = 0
        var disables = 0
        val pending = ArrayDeque<(Boolean, Boolean) -> Unit>()

        override fun enable(topics: List<String>) {
            enables++
        }

        override fun disable(topics: List<String>, onDone: (left: Boolean, deleted: Boolean) -> Unit) {
            disables++
            pending += onDone
        }

        fun finishDisable(left: Boolean = true, deleted: Boolean = true) = pending.removeFirst()(left, deleted)
    }
    private val store = object : PushDisableStore {
        override var pushDisablePending = false
    }
    private var wanted = false
    private var systemAllows = true
    private val push = PushSwitch(backend, store, listOf("all"), { wanted }, { systemAllows })

    private fun turnOn() {
        wanted = true
        push.sync()
    }

    private fun turnOff() {
        val wasOn = wanted
        wanted = false
        push.turnOff(wasOn)
    }

    @Test
    fun `on again while the token is being deleted re-joins the topics once the delete lands`() {
        turnOn()
        turnOff()
        turnOn() // joins the topics again, on the token about to be deleted
        assertEquals(2, backend.enables)
        backend.finishDisable()
        // The topics left with the deleted token: FCM must be turned on again, on a new one.
        assertEquals(3, backend.enables)
        assertFalse(store.pushDisablePending)
        push.sync()
        assertEquals("still idempotent once back on", 3, backend.enables)
    }

    @Test
    fun `on again without the system permission waits for it instead`() {
        turnOn()
        turnOff()
        systemAllows = false
        turnOn()
        backend.finishDisable()
        assertEquals(1, backend.enables)
        systemAllows = true
        push.sync() // back from system settings
        assertEquals(2, backend.enables)
    }

    @Test
    fun `an opt-out that fails offline stays pending and is retried on the next resume`() {
        turnOn()
        turnOff()
        backend.finishDisable(left = false)
        assertTrue(store.pushDisablePending)
        push.sync()
        assertEquals(2, backend.disables)
        backend.finishDisable()
        assertFalse(store.pushDisablePending)
        push.sync()
        assertEquals("nothing left to retry", 2, backend.disables)
    }

    @Test
    fun `a resume during an opt-out does not start a second one`() {
        turnOn()
        turnOff()
        repeat(3) { push.sync() }
        assertEquals(1, backend.disables)
    }

    @Test
    fun `push never turns on without the user, nor without the system`() {
        push.sync()
        assertEquals(0, backend.enables)
        systemAllows = false
        turnOn()
        assertEquals(0, backend.enables)
        systemAllows = true
        push.sync()
        assertEquals(1, backend.enables)
    }

    @Test
    fun `off when push was never on touches nothing`() {
        turnOff()
        assertEquals(0, backend.disables)
        assertFalse(store.pushDisablePending)
    }
}
