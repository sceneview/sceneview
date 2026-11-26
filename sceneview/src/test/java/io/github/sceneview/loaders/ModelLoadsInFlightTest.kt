package io.github.sceneview.loaders

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelLoadsInFlightTest {

    @Test
    fun `nothing abandoned returns at once`() = runBlocking {
        assertTrue(ModelLoadsInFlight.awaitAbandoned(timeoutMillis = 1))
    }

    @Test
    fun `a running load that was not cancelled is not waited for`() = runBlocking {
        val inStep = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val load = launch(Dispatchers.Default) {
            ModelLoadsInFlight.track { inStep.complete(Unit); release.await() }
        }
        inStep.await()
        assertEquals(0, ModelLoadsInFlight.abandonedCount)
        assertTrue(ModelLoadsInFlight.awaitAbandoned(timeoutMillis = 1))
        release.complete(Unit)
        load.join()
    }

    @Test
    fun `a cancelled load still in a step that does not suspend is waited for until it ends`() = runBlocking {
        val inStep = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val load = launch(Dispatchers.Default) {
            ModelLoadsInFlight.track {
                // Stands in for a parse on Dispatchers.Default: cancellation cannot stop it midway.
                withContext(NonCancellable) { inStep.complete(Unit); release.await() }
            }
        }
        inStep.await()
        load.cancel()
        yield()
        assertEquals(1, ModelLoadsInFlight.abandonedCount)
        // Still in its step: a short wait times out.
        assertFalse(ModelLoadsInFlight.awaitAbandoned(timeoutMillis = 50))
        launch { release.complete(Unit) }
        assertTrue(ModelLoadsInFlight.awaitAbandoned(timeoutMillis = 5_000))
        load.join()
        assertEquals(0, ModelLoadsInFlight.abandonedCount)
    }

    @Test
    fun `a load that throws leaves the registry`() = runBlocking {
        val failure = runCatching { ModelLoadsInFlight.track<Unit> { error("unreadable") } }
        assertTrue(failure.isFailure)
        assertEquals(0, ModelLoadsInFlight.abandonedCount)
        assertTrue(ModelLoadsInFlight.awaitAbandoned(timeoutMillis = 1))
    }

    @Test
    fun `a cancelled scope with no load in flight waits for nothing`() = runBlocking {
        val scopeJob = launch { ModelLoadsInFlight.track { } }
        scopeJob.join()
        scopeJob.cancel()
        assertTrue(ModelLoadsInFlight.awaitAbandoned(timeoutMillis = 1))
    }
}
