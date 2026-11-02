package io.github.sceneview

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One value prepared on a background thread ahead of its use, then handed over once to whichever
 * thread [take]s it. Kept free of Android and Filament so its rules are pinned in a JVM test; see
 * [EngineWarmUp] for the value it carries.
 *
 * - [start] prepares a value unless one is ready or already being prepared.
 * - [take] hands the ready value over, waiting for one being prepared, at most `timeoutMillis`.
 * - A value nobody can take any more — prepared after its taker gave up waiting — is passed to
 *   [discard], never handed out later: the taker made its own instead.
 * - A ready value nobody takes within [expireAfterMillis] is passed to [discard] too, so a
 *   warm-up that no engine ever claims does not hold its value for the life of the process.
 *
 * @param discard           Frees a value that will never be handed over. Runs on the preparing
 *                          thread.
 * @param onFailure         Told when a preparation throws: the slot is then empty and [start] may
 *                          retry.
 * @param expireAfterMillis How long a ready value waits to be taken, counted from when it became
 *                          ready. `null`: for as long as the process lives.
 */
internal class WarmUpSlot<T : Any>(
    private val discard: (T) -> Unit,
    private val onFailure: (Throwable) -> Unit = {},
    private val expireAfterMillis: Long? = null,
) {
    private val lock = Any()

    // Guarded by `lock`. At most one of `ready` and `preparing` is set; `claimed` goes with `ready`.
    private var ready: T? = null
    private var claimed: CountDownLatch? = null
    private var preparing: CountDownLatch? = null
    private var abandoned = false

    /** Whether a value is ready to be taken, without waiting. */
    val isReady: Boolean get() = synchronized(lock) { ready != null }

    /**
     * Runs [prepare] on a new thread named [threadName], unless a value is ready or being prepared,
     * then [afterwards] on the same thread, once the value can be taken: a taker never waits for it.
     *
     * @return `true` when a preparation started.
     */
    fun start(threadName: String, afterwards: () -> Unit = {}, prepare: () -> T?): Boolean {
        val latch = synchronized(lock) {
            if (ready != null || preparing != null) return false
            abandoned = false
            CountDownLatch(1).also { preparing = it }
        }
        val thread = Thread({
            val value = try {
                prepare()
            } catch (failure: Throwable) {
                onFailure(failure)
                null
            }
            val whenClaimed = CountDownLatch(1)
            val handedOver = synchronized(lock) {
                preparing = null
                (value != null && !abandoned).also {
                    if (it) {
                        ready = value
                        claimed = whenClaimed
                    }
                }
            }
            val readyAt = System.nanoTime()
            if (!handedOver && value != null) discard(value)
            latch.countDown()
            try {
                afterwards()
            } catch (failure: Throwable) {
                onFailure(failure)
            }
            if (handedOver && value != null && expireAfterMillis != null) {
                val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - readyAt)
                if (!whenClaimed.await((expireAfterMillis - elapsed).coerceAtLeast(0), TimeUnit.MILLISECONDS)) {
                    val expired = synchronized(lock) {
                        (ready === value).also {
                            if (it) {
                                ready = null
                                claimed = null
                            }
                        }
                    }
                    if (expired) discard(value)
                }
            }
        }, threadName)
        thread.isDaemon = true
        thread.start()
        return true
    }

    /**
     * Hands over the prepared value, or `null` when there is none. A value still being prepared is
     * waited for, at most [timeoutMillis]; past that it is abandoned, see the class.
     */
    fun take(timeoutMillis: Long): T? {
        val latch = synchronized(lock) {
            takeReady()?.let { return it }
            preparing?.takeUnless { abandoned } ?: return null
        }
        latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
        synchronized(lock) {
            takeReady()?.let { return it }
            if (preparing === latch) abandoned = true
            return null
        }
    }

    /** The ready value, now claimed, or `null`. Holding `lock`. */
    private fun takeReady(): T? = ready?.also {
        ready = null
        claimed?.countDown()
        claimed = null
    }
}
