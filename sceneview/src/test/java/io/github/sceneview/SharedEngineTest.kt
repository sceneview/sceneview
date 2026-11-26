package io.github.sceneview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class SharedEngineTest {
    private class Context
    private class Engine(val context: Context)

    private var contextsCreated = 0
    private var enginesCreated = 0
    private val destroyed = mutableListOf<Engine>()
    private val destroyedContexts = mutableListOf<Context>()
    private val pool = EnginePool(
        defaultContextCreator = { contextsCreated++; Context() },
        defaultEngineCreator = { context: Context -> enginesCreated++; Engine(context) },
        destroyOwned = { engine, _ -> destroyed += engine },
        destroyContext = { destroyedContexts += it },
    )

    @Test
    fun `simultaneous acquisitions share a lazily created engine and count every user`() {
        assertEquals(0, enginesCreated)
        assertEquals(0, contextsCreated)
        val first = pool.acquire()
        val second = pool.acquire()
        assertSame(first.engine, second.engine)
        assertEquals(1, enginesCreated)
        assertEquals(1, contextsCreated)
        assertEquals(2, pool.referenceCount)

        first.release()
        assertEquals(1, pool.referenceCount)
        second.release()
        assertEquals(0, pool.referenceCount)
        assertEquals(emptyList<Engine>(), destroyed)
        assertEquals(emptyList<Context>(), destroyedContexts)
    }

    @Test
    fun `five hundred screen opens reuse the engine even after the count reaches zero`() {
        val first = pool.acquire()
        first.release()
        repeat(500) {
            val next = pool.acquire()
            assertSame(first.engine, next.engine)
            assertEquals(1, pool.referenceCount)
            next.release()
            assertEquals(0, pool.referenceCount)
        }
        assertEquals(1, enginesCreated)
        assertEquals(1, contextsCreated)
        assertEquals(emptyList<Engine>(), destroyed)
        assertEquals(emptyList<Context>(), destroyedContexts)
    }

    @Test
    fun `a custom engine creator opts out even when it delegates to the default behaviour`() {
        val shared = pool.acquire()
        val creator: (Context) -> Engine = { Engine(it) }
        val first = pool.acquire(engineCreator = creator)
        val second = pool.acquire(engineCreator = creator)
        assertNotSame(shared.engine, first.engine)
        assertNotSame(first.engine, second.engine)
        assertNotSame(first.engine.context, second.engine.context)
        assertEquals(1, pool.referenceCount)

        first.onForgotten()
        second.onForgotten()
        shared.onForgotten()
        assertEquals(listOf(first.engine, second.engine), destroyed)
        assertEquals(0, pool.referenceCount)
    }

    @Test
    fun `a custom context creator alone also opts out`() {
        val shared = pool.acquire()
        val owned = pool.acquire(contextCreator = { Context() })
        assertNotSame(shared.engine, owned.engine)
        owned.release()
        assertEquals(listOf(owned.engine), destroyed)
        assertEquals(1, pool.referenceCount)
        shared.release()
    }

    @Test
    fun `reacquiring the shared engine restores its context after a custom engine changed it`() {
        var currentContext: Context? = null
        val restoring = EnginePool(
            defaultContextCreator = { Context().also { currentContext = it } },
            defaultEngineCreator = { context: Context -> Engine(context) },
            destroyOwned = { engine, _ -> destroyed += engine },
            destroyContext = { destroyedContexts += it },
            onSharedAcquired = { currentContext = it },
        )
        val shared = restoring.acquire()
        shared.release()
        val owned = restoring.acquire(contextCreator = { Context().also { currentContext = it } })
        assertSame(owned.engine.context, currentContext)
        owned.release()
        val next = restoring.acquire()
        assertSame(shared.engine, next.engine)
        assertSame(shared.engine.context, currentContext)
        next.release()
        assertEquals(listOf(owned.engine), destroyed)
    }

    @Test
    fun `an abandoned composition releases its acquisition once without destroying the shared engine`() {
        val lease = pool.acquire()
        lease.onAbandoned()
        lease.onForgotten()
        lease.release()
        assertEquals(0, pool.referenceCount)
        assertSame(lease.engine, pool.acquire().engine)
        assertEquals(emptyList<Engine>(), destroyed)
    }

    @Test
    fun `an abandoned owned engine follows its normal teardown once`() {
        val lease = pool.acquire(engineCreator = { Engine(it) })
        lease.onAbandoned()
        lease.onForgotten()
        assertEquals(listOf(lease.engine), destroyed)
        assertEquals(0, pool.referenceCount)
    }

    @Test
    fun `a failed shared engine creation releases its context and the next call retries`() {
        var attempts = 0
        val retrying = EnginePool(
            defaultContextCreator = { Context() },
            defaultEngineCreator = { context: Context ->
                check(++attempts > 1) { "Couldn't create Engine" }
                Engine(context)
            },
            destroyOwned = { engine, _ -> destroyed += engine },
            destroyContext = { destroyedContexts += it },
        )
        assertThrows(IllegalStateException::class.java) { retrying.acquire() }
        assertEquals(0, retrying.referenceCount)
        assertEquals(1, destroyedContexts.size)
        val lease = retrying.acquire()
        assertNotSame(destroyedContexts.single(), lease.engine.context)
        assertEquals(1, retrying.referenceCount)
        lease.release()
    }

    @Test
    fun `thread checks run before creating or releasing native resources`() {
        var mainThread = false
        var creations = 0
        val checked = EnginePool(
            defaultContextCreator = { creations++; Context() },
            defaultEngineCreator = { context: Context -> Engine(context) },
            destroyOwned = { engine, _ -> destroyed += engine },
            destroyContext = { destroyedContexts += it },
            checkThread = { check(mainThread) },
        )
        assertThrows(IllegalStateException::class.java) { checked.acquire() }
        assertEquals(0, creations)
        mainThread = true
        val lease = checked.acquire()
        mainThread = false
        assertThrows(IllegalStateException::class.java) { lease.release() }
        assertEquals(1, checked.referenceCount)
        mainThread = true
        lease.release()
        assertEquals(0, checked.referenceCount)
    }
}
