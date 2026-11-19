package io.github.sceneview.splat

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises SplatNode's actual upload decision without an Engine or a consuming frame. */
class SplatOrderUploadsTest {
    private class Backend {
        val uploaded = mutableListOf<SplatOrderUpload>()
        val releases = ArrayDeque<() -> Unit>()
        var deferredFrames = 0
        var maxInFlight = 0
        val orders = SplatOrderUploads(
            upload = { value, onReleased ->
                uploaded += value
                releases.addLast(onReleased)
                maxInFlight = maxOf(maxInFlight, releases.size)
            },
            onDeferredUpload = { deferredFrames++ },
        )

        fun consume() = releases.removeFirst().invoke()
    }

    private fun order(revision: Int, isSorted: Boolean = true) = SplatOrderUpload(
        SplatBuffers.packOrder(intArrayOf(revision, revision + 1, revision + 2), 2),
        sortedCount = revision % 4,
        isSorted = isSorted,
    )

    @Test
    fun `initial order and twenty thousand sorts without consumption keep one upload in flight`() {
        val backend = Backend()
        val initial = order(0, isSorted = false)
        backend.orders.submit(initial)
        var latest = initial

        repeat(20_000) { revision ->
            latest = order(revision + 1)
            backend.orders.submit(latest)
        }

        assertEquals("The construction upload must share the sort upload gate", 1, backend.maxInFlight)
        assertEquals(listOf(initial), backend.uploaded)
        assertEquals(1, backend.releases.size)
        assertEquals("No deferred order has reached Filament yet", 0, backend.deferredFrames)
        assertFalse(initial.isSorted)

        backend.consume()

        assertEquals(2, backend.uploaded.size)
        assertSame("The last order and its filtered count must travel together", latest, backend.uploaded.last())
        assertTrue(backend.uploaded.last().isSorted)
        assertArrayEquals(
            intArrayOf(20_000, 20_001, 20_002, 0),
            IntArray(4).also { backend.uploaded.last().order.duplicate().get(it) },
        )
        assertEquals(1, backend.deferredFrames)
        assertEquals(1, backend.maxInFlight)
        backend.consume()
        assertTrue(backend.releases.isEmpty())
    }

    @Test
    fun `each resumed frame sends only the latest order and filtered slot count`() {
        val backend = Backend()
        backend.orders.submit(order(0))

        repeat(3) { burst ->
            var latest = order(0)
            repeat(5_001) { revision ->
                latest = order(burst * 5_001 + revision + 1)
                backend.orders.submit(latest)
            }
            assertEquals(burst + 1, backend.uploaded.size)
            backend.consume()
            assertSame(latest, backend.uploaded.last())
            assertEquals(latest.sortedCount, backend.uploaded.last().sortedCount)
            assertEquals(1, backend.maxInFlight)
        }

        assertEquals(3, backend.deferredFrames)
        backend.consume()
        val next = order(50_000)
        backend.orders.submit(next)
        assertSame("Consumption reopens the gate for an immediate update", next, backend.uploaded.last())
        assertEquals("Immediate updates need no deferred frame request", 3, backend.deferredFrames)
    }

    @Test
    fun `destroy drops the pending order before a late release and refuses later submissions`() {
        val backend = Backend()
        val initial = order(0)
        backend.orders.submit(initial)
        repeat(10_000) { backend.orders.submit(order(it + 1)) }

        backend.orders.destroy()
        backend.consume()
        backend.orders.submit(order(30_000))

        assertEquals(listOf(initial), backend.uploaded)
        assertTrue(backend.releases.isEmpty())
        assertEquals(0, backend.deferredFrames)
    }

    @Test
    fun `node gates both producers and changes draw slots only when an order is issued`() {
        val source = (
            File("src/main/java/io/github/sceneview/node/SplatNode.kt").takeIf { it.exists() }
                ?: File("sceneview/src/main/java/io/github/sceneview/node/SplatNode.kt")
            ).readText()
        assertEquals(2, Regex("""orderUploads\.submit\(""").findAll(source).count())
        assertEquals(1, Regex("""(?<!fun )uploadOrder\(""").findAll(source).count())
        val sort = source.substringAfter("private fun maybeResort()").substringBefore("private fun sortCameraWorldPosition()")
        assertTrue(sort.contains("orderUploads.submit(SplatOrderUpload(packedOrder, sorted))"))
        assertFalse("A pending count must not change the still-displayed order", sort.contains("showDrawSlots("))
        val issue = source.substringAfter("private fun uploadOrder(").substringBefore("private fun floatBufferOf(")
        assertTrue(issue.contains("setCallback(orderReleaseHandler, Runnable { onReleased() })"))
        assertTrue(issue.contains("sortedCount = value.sortedCount"))
        assertTrue(issue.contains("showDrawSlots(min(splatCount, sortedCount))"))
        assertTrue(source.contains("onDeferredUpload = { requestRender() }"))
        assertTrue(source.contains("Handler(Looper.getMainLooper())"))
        assertTrue(source.substringAfter("override fun destroy()").contains("orderUploads.destroy()"))
    }
}
