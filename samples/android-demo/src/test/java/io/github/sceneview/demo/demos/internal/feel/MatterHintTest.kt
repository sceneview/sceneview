package io.github.sceneview.demo.demos.internal.feel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatterHintTest {
    private class FakeStore(override var done: Boolean = false) : MatterHint.Store {
        var writes = 0
        override fun markDone() { done = true; writes++ }
    }

    @Test fun hiddenUntilTheStageIsReadyThenShown() {
        val hint = MatterHint(FakeStore())
        hint.update(stageReady = false, pressed = false)
        assertFalse(hint.visible)
        hint.update(stageReady = true, pressed = false)
        assertTrue(hint.visible)
    }

    @Test fun firstPressHidesItAndPersistsOnce() {
        val store = FakeStore()
        val hint = MatterHint(store)
        hint.update(stageReady = true, pressed = false)
        hint.update(stageReady = true, pressed = true)
        assertFalse(hint.visible)
        assertTrue(store.done)
        hint.update(stageReady = true, pressed = true)
        hint.update(stageReady = true, pressed = false)
        assertFalse(hint.visible)
        assertEquals(1, store.writes)
    }

    @Test fun neverReturnsOnTheNextVisit() {
        val store = FakeStore()
        MatterHint(store).update(stageReady = true, pressed = true)
        val next = MatterHint(store)
        next.update(stageReady = true, pressed = false)
        assertFalse(next.visible)
    }

    @Test fun aPressBeforeTheStageIsReadyAlsoCounts() {
        val store = FakeStore()
        val hint = MatterHint(store)
        hint.update(stageReady = false, pressed = true)
        hint.update(stageReady = true, pressed = false)
        assertFalse(hint.visible)
        assertTrue(store.done)
    }

    @Test fun staysUpWhileTheUserOnlySwipes() {
        val hint = MatterHint(FakeStore())
        hint.update(stageReady = true, pressed = false)
        // A switch makes the stage briefly not ready; the hint does not flicker away.
        hint.update(stageReady = false, pressed = false)
        assertTrue(hint.visible)
    }
}
