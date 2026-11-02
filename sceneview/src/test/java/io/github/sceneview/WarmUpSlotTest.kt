package io.github.sceneview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The hand-over rules of [WarmUpSlot], which carries [EngineWarmUp]'s prepared EGL context from its
 * background thread to the thread that builds the engine. A broken rule here is a context handed to
 * two engines, or one leaked: neither shows up until a device runs out of contexts.
 */
class WarmUpSlotTest {

    private val discarded = Collections.synchronizedList(mutableListOf<Any>())
    private val failures = Collections.synchronizedList(mutableListOf<Throwable>())
    private val slot = WarmUpSlot<Any>(discard = { discarded += it }, onFailure = { failures += it })

    private fun awaitReady() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!slot.isReady) {
            check(System.nanoTime() < deadline) { "the slot never became ready" }
            Thread.sleep(1)
        }
    }

    @Test
    fun a_prepared_value_is_handed_over_once() {
        val value = Any()
        assertTrue(slot.start("test") { value })
        awaitReady()

        assertSame(value, slot.take(timeoutMillis = 0))
        assertNull("a value must never reach two takers", slot.take(timeoutMillis = 0))
        assertTrue(discarded.isEmpty())
    }

    @Test
    fun take_waits_for_a_preparation_under_way() {
        val release = CountDownLatch(1)
        val value = Any()
        slot.start("test") { release.await(); value }

        Thread { Thread.sleep(50); release.countDown() }.start()
        assertSame(value, slot.take(timeoutMillis = 5_000))
    }

    @Test
    fun a_value_prepared_after_its_taker_gave_up_is_discarded_never_handed_out() {
        val release = CountDownLatch(1)
        val value = Any()
        slot.start("test") { release.await(); value }

        assertNull(slot.take(timeoutMillis = 10))
        // The next taker does not wait on an abandoned preparation either: it returns at once.
        val started = System.nanoTime()
        assertNull(slot.take(timeoutMillis = 5_000))
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1))

        release.countDown()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (discarded.isEmpty()) {
            check(System.nanoTime() < deadline) { "the late value was never discarded" }
            Thread.sleep(1)
        }
        assertEquals(listOf(value), discarded.toList())
        assertNull(slot.take(timeoutMillis = 0))
    }

    @Test
    fun start_does_nothing_while_a_value_is_ready_or_being_prepared() {
        val calls = AtomicInteger()
        val release = CountDownLatch(1)
        assertTrue(slot.start("test") { calls.incrementAndGet(); release.await(); Any() })
        assertFalse("already being prepared", slot.start("test") { calls.incrementAndGet(); Any() })

        release.countDown()
        awaitReady()
        assertFalse("already ready", slot.start("test") { calls.incrementAndGet(); Any() })
        assertEquals(1, calls.get())
    }

    @Test
    fun a_taken_value_lets_the_next_start_prepare_another() {
        val first = Any()
        val second = Any()
        slot.start("test") { first }
        awaitReady()
        assertSame(first, slot.take(timeoutMillis = 0))

        assertTrue(slot.start("test") { second })
        awaitReady()
        assertSame(second, slot.take(timeoutMillis = 0))
    }

    @Test
    fun a_failed_preparation_leaves_the_slot_empty_and_reports_it() {
        val failure = IllegalStateException("no GPU")
        slot.start("test") { throw failure }

        assertNull(slot.take(timeoutMillis = 5_000))
        assertEquals(listOf<Throwable>(failure), failures.toList())
        assertTrue(slot.start("test") { Any() })
    }

    @Test
    fun the_work_after_a_preparation_never_delays_its_taker() {
        val value = Any()
        val release = CountDownLatch(1)
        val ran = CountDownLatch(1)
        slot.start("test", afterwards = { release.await(); ran.countDown() }) { value }

        assertSame(value, slot.take(timeoutMillis = 5_000))
        assertEquals("still running", 1L, ran.count)
        release.countDown()
        assertTrue(ran.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun a_failure_after_the_preparation_is_reported_and_keeps_the_value() {
        val value = Any()
        val failure = IllegalStateException("no engine")
        slot.start("test", afterwards = { throw failure }) { value }

        assertSame(value, slot.take(timeoutMillis = 5_000))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (failures.isEmpty()) {
            check(System.nanoTime() < deadline) { "the failure was never reported" }
            Thread.sleep(1)
        }
        assertEquals(listOf<Throwable>(failure), failures.toList())
    }

    @Test
    fun a_preparation_that_yields_nothing_leaves_the_slot_empty() {
        slot.start("test") { null }
        assertNull(slot.take(timeoutMillis = 5_000))
        assertTrue(discarded.isEmpty())
    }
}
