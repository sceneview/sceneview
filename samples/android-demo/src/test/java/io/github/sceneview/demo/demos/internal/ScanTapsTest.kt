package io.github.sceneview.demo.demos.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A replay's object picker and the coroutines it starts (#4696): what is shown, and what a
 * destroyed picker drops. The pick itself is CPU-bound and never checks for cancellation — each
 * test's work runs [NonCancellable] to stand for it.
 */
class ScanTapsTest {

    private val scope = TestScope(StandardTestDispatcher())

    @Test
    fun `a tap's answer is shown`() {
        val taps = ScanTaps(scope)
        val shown = mutableListOf<Int>()

        taps.tap(work = { 7 }, show = { shown += it })
        scope.runCurrent()

        assertEquals(listOf(7), shown)
    }

    @Test
    fun `a pick that finishes after destroy is never shown`() {
        val taps = ScanTaps(scope)
        val gate = CompletableDeferred<Int>()
        val shown = mutableListOf<Int>()

        taps.tap(work = { withContext(NonCancellable) { gate.await() } }, show = { shown += it })
        scope.runCurrent()
        taps.cancel()
        gate.complete(7)
        scope.runCurrent()

        assertTrue(shown.isEmpty())
        assertFalse(taps.isActive)
    }

    @Test
    fun `a later tap answers for the one before it`() {
        val taps = ScanTaps(scope)
        val first = CompletableDeferred<Int>()
        val shown = mutableListOf<Int>()

        taps.tap(work = { withContext(NonCancellable) { first.await() } }, show = { shown += it })
        scope.runCurrent()
        taps.tap(work = { 2 }, show = { shown += it })
        scope.runCurrent()
        first.complete(1)
        scope.runCurrent()

        assertEquals(listOf(2), shown)
    }

    @Test
    fun `a tap answered at once drops the one under way`() {
        val taps = ScanTaps(scope)
        val gate = CompletableDeferred<Int>()
        val shown = mutableListOf<Int>()

        taps.tap(work = { withContext(NonCancellable) { gate.await() } }, show = { shown += it })
        scope.runCurrent()
        taps.answered()
        gate.complete(1)
        scope.runCurrent()

        assertTrue(shown.isEmpty())
    }

    @Test
    fun `destroying one picker leaves the composition's scope and the next picker alive`() {
        ScanTaps(scope).cancel()
        val next = ScanTaps(scope)
        val shown = mutableListOf<Int>()

        next.tap(work = { 3 }, show = { shown += it })
        scope.runCurrent()

        assertEquals(listOf(3), shown)
    }
}
