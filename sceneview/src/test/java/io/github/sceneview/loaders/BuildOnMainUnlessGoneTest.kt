package io.github.sceneview.loaders

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Contract of the Main half of `EnvironmentLoader.loadHDREnvironment` (#4174 review): a caller
 * that left while the HDR decoded never reaches the engine, a dead engine is never touched, and an
 * environment built for a caller cancelled mid-build is destroyed, not leaked.
 *
 * A real `EnvironmentLoader` needs a Filament `Engine`, so this drives the Filament-free
 * [buildOnMainUnlessGone] it is built on, with a single-thread executor standing in for Main and a
 * log standing in for the Filament build and destroy.
 */
class BuildOnMainUnlessGoneTest {

    private val main: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val log = Collections.synchronizedList(mutableListOf<String>())

    @After
    fun tearDown() {
        main.close()
    }

    private suspend fun load(isEngineValid: () -> Boolean = { true }, build: () -> String = { "env" }) =
        buildOnMainUnlessGone(
            mainDispatcher = main,
            isEngineValid = isEngineValid,
            destroy = { log += "destroy($it)" },
        ) {
            log += "build"
            build()
        }

    @Test
    fun `a live caller on a live engine gets the environment, nothing destroyed`() = runBlocking {
        assertEquals("env", load())
        assertEquals(listOf("build"), log)
    }

    @Test
    fun `an engine destroyed while the HDR decoded is never touched`() = runBlocking {
        assertNull(load(isEngineValid = { false }))
        assertTrue(log.isEmpty())
    }

    @Test
    fun `a caller cancelled before Main runs never builds`() = runBlocking {
        // Main is busy (the frame that disposes the composable); the caller is cancelled before the
        // queued build gets its turn — the composable left while the decode ran.
        val mainBusy = CountDownLatch(1)
        val mainBlocked = CountDownLatch(1)
        main.executor.execute {
            mainBlocked.countDown()
            mainBusy.await(5, TimeUnit.SECONDS)
        }
        assertTrue(mainBlocked.await(5, TimeUnit.SECONDS))
        // Undispatched: the load runs up to its hop to Main before the cancel below.
        val job = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { load() }
        job.cancel()
        mainBusy.countDown()
        try {
            job.await()
            fail("expected the load to be cancelled")
        } catch (_: CancellationException) {
            // expected
        }
        // Let Main drain whatever was queued before asserting nothing was built.
        withContext(main) {}
        assertTrue(log.isEmpty())
    }

    @Test
    fun `a caller cancelled during the build gets it destroyed and the cancellation rethrown`() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val cancelled = CountDownLatch(1)
            val job = async(Dispatchers.Default) {
                load {
                    started.complete(Unit)
                    // The build does not suspend: it finishes even though the caller is cancelled.
                    assertTrue(cancelled.await(5, TimeUnit.SECONDS))
                    "env"
                }
            }
            started.await()
            job.cancel()
            cancelled.countDown()
            try {
                job.await()
                fail("expected the load to be cancelled")
            } catch (_: CancellationException) {
                // expected
            }
            assertEquals(listOf("build", "destroy(env)"), log)
        }

    @Test
    fun `a build orphaned after the engine died is left to the engine`() = runBlocking {
        val engineValid = AtomicBoolean(true)
        val started = CompletableDeferred<Unit>()
        val cancelled = CountDownLatch(1)
        val job = async(Dispatchers.Default) {
            load(isEngineValid = { engineValid.get() }) {
                started.complete(Unit)
                assertTrue(cancelled.await(5, TimeUnit.SECONDS))
                // The same frame goes on to destroy the engine, which reclaims everything.
                engineValid.set(false)
                "env"
            }
        }
        started.await()
        job.cancel()
        cancelled.countDown()
        try {
            job.await()
            fail("expected the load to be cancelled")
        } catch (_: CancellationException) {
            // expected
        }
        assertEquals(listOf("build"), log)
    }
}
