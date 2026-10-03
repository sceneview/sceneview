package io.github.sceneview.environment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RetainedResourceStateTest {

    @Test
    fun `request keeps previous value until replacement is ready`() {
        val released = mutableListOf<Resource>()
        val state = RetainedResourceState<Resource>(released::add)
        val first = Resource("first")
        val second = Resource("second")

        state.complete(state.beginRequest(), first)
        val replacement = state.beginRequest()

        assertSame(first, state.value)
        assertTrue(state.complete(replacement, second))
        assertSame(second, state.value)
        assertTrue(released.isEmpty())
        state.releaseRetired()
        assertEquals(listOf(first), released)
    }

    @Test
    fun `last request wins and superseded result is released`() {
        val released = mutableListOf<Resource>()
        val state = RetainedResourceState<Resource>(released::add)
        val firstRequest = state.beginRequest()
        val lastRequest = state.beginRequest()
        val stale = Resource("stale")
        val latest = Resource("latest")

        assertFalse(state.complete(firstRequest, stale))
        assertNull(state.value)
        assertTrue(state.complete(lastRequest, latest))
        assertSame(latest, state.value)
        assertEquals(listOf(stale), released)
    }

    @Test
    fun `failed replacement retains current value`() {
        val released = mutableListOf<Resource>()
        val state = RetainedResourceState<Resource>(released::add)
        val current = Resource("current")
        state.complete(state.beginRequest(), current)

        assertFalse(state.complete(state.beginRequest(), null))

        assertSame(current, state.value)
        assertTrue(released.isEmpty())
    }

    @Test
    fun `replacement stale result and clear each release exactly once on caller thread`() {
        val releases = mutableListOf<Pair<Resource, String>>()
        val state = RetainedResourceState<Resource>(release = {
            releases += it to Thread.currentThread().name
        })
        val first = Resource("first")
        val second = Resource("second")
        state.complete(state.beginRequest(), first)
        state.complete(state.beginRequest(), second)
        state.releaseRetired()
        val staleRequest = state.beginRequest()
        val latestRequest = state.beginRequest()
        val stale = Resource("stale")
        state.complete(staleRequest, stale)
        state.complete(latestRequest, null)

        state.clear()
        state.clear()

        assertNull(state.value)
        assertEquals(listOf(first, stale, second), releases.map { it.first })
        assertTrue(releases.all { it.second == Thread.currentThread().name })
    }

    private class Resource(val name: String) {
        override fun toString(): String = name
    }
}
