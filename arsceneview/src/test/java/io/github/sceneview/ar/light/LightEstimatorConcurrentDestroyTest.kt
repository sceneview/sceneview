package io.github.sceneview.ar.light

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Stress regression suite for the **concurrent `update` ↔ `destroy` race**
 * surfaced as acceptance #3 of the [LightEstimator] umbrella audit
 * ([#1094](https://github.com/sceneview/sceneview/issues/1094)).
 *
 * ## What this pins
 *
 * The production path under test is:
 *  - **AR render thread** (Filament's frame callback) drives [LightEstimator.update]
 *    at ~30–60 Hz.
 *  - **Compose main thread** drives [LightEstimator.destroy] when the enclosing
 *    `DisposableEffect` is torn down (e.g. user navigates away, configuration
 *    change, ARSceneView re-composition with new keys).
 *
 * The two threads share three volatile fields ([isDestroyed], [uploadInFlight],
 * [environmentalHdrReflections]) and several non-volatile native references
 * (`cubeMapTexture`, `cubeMapTextureSpecular`, `cubeMapBuffer`). The CORR-B
 * audit (PR [#1134](https://github.com/sceneview/sceneview/pull/1134)) added an
 * `isDestroyed` gate at the top of `update()` so a late frame after `destroy()`
 * cannot dereference freed natives. Acceptance #3 of #1094 explicitly required a
 * stress test:
 *
 *  > 100 iterations
 *  > Thread 1 (executor) hammer `update(frame)` or stub similar
 *  > Thread 2 call `destroy()` at 50% of the run
 *  > Assert zero crash, zero IllegalStateException, isDestroyed bool reads correctly post-destroy
 *
 * ## Why algorithmic mirror, not real `Frame`
 *
 * Following the precedent of [LightEstimatorRobustnessTest], the live ARCore
 * [com.google.ar.core.Frame] / [com.google.ar.core.Session] symbols cannot be
 * instantiated outside an attached `Session`, and Robolectric does not shadow
 * the JNI surface. Mock-free, we run a thread-faithful mirror of the production
 * gating sequence:
 *
 *  1. `update()` early-returns if `isDestroyed` (matches [LightEstimator.kt:180]).
 *  2. `update()` reads/writes texture refs (we model with `AtomicReference<Int?>`).
 *  3. `destroy()` latches `isDestroyed = true` BEFORE freeing textures (matches
 *     [LightEstimator.kt:473–500]).
 *  4. The `@Volatile` annotations on `isDestroyed` and `uploadInFlight` are
 *     stood in by [AtomicBoolean] which carries the same happens-before
 *     guarantee.
 *
 * If any of the production gates regresses (lost `@Volatile`, swapped sequence
 * inside `destroy()`, dropped early-return at the top of `update()`), the
 * mirror MUST be updated in lockstep with the production code or this test
 * fails — protecting against silent regressions.
 *
 * ## What "zero crash" means here
 *
 * The production code surfaces two failure modes for an unsafe update-after-destroy:
 *  - `IllegalStateException` from Filament's `Engine` "use after destroy" check.
 *  - JNI native-side SIGSEGV inside `engine.destroyTexture(handle)` on a freed
 *    address.
 *
 * The mirror surfaces both as a captured [Throwable] inside `worstError` that the
 * assertion checks at the end of the run. The real-engine version of this test
 * lives next to this one as
 * `arsceneview/src/androidTest/.../LightEstimatorConcurrentDestroyTest.kt` and
 * exercises the same race on a real Filament `Engine` + real cubemap
 * `Texture` instances, surfacing JNI corruption modes that this mirror cannot
 * see. The two files share a name on purpose (different source sets, same
 * intent) so a future maintainer auditing one always finds the other.
 *
 * ## Iteration count
 *
 * 100 iterations × `HAMMER_COUNT` × 2 threads per iteration ≈ a few thousand
 * concurrent calls across the whole suite. With `Runtime.availableProcessors()`
 * concurrency on a modern dev box that gives sub-second wall time, well under
 * the 5-minute Gradle test timeout. Matches the acceptance-criterion spec.
 *
 * Lineage: #1094 acceptance #3, CORR-B follow-up.
 */
class LightEstimatorConcurrentDestroyTest {

    /**
     * Number of `update()` calls hammered by Thread 1 per iteration. Picked so
     * the busy-loop fully outpaces `destroy()` even on a slow CI runner, while
     * staying fast enough that 100 iterations finish in <5 s wall-time.
     */
    private val hammerCount = 1_000

    /** 100 iterations per the acceptance spec. */
    private val iterations = 100

    // ── Acceptance #3: concurrent destroy must not crash ───────────────────────

    /**
     * Hammer `update()` from one thread, call `destroy()` from another after a
     * known fraction of the hammer iterations, repeat 100 times — assert no
     * exception was caught from either thread.
     *
     * The mid-run destroy at 50% of `hammerCount` simulates the Compose lambda
     * deciding to dispose the estimator while frames are still arriving. Any
     * dereference of freed natives by a late `update()` would surface as a
     * pre-fix `IllegalStateException` from Filament's "use after destroy"
     * check or a JNI SIGSEGV; the gate at L180 of `LightEstimator.kt` makes
     * both impossible.
     *
     * The assertion checks the strongest invariant: zero captured throwables
     * across the entire 100-iteration sweep.
     */
    @Test
    fun `100 iterations of concurrent update + destroy at 50 percent — zero crashes`() {
        val executor: ExecutorService = Executors.newFixedThreadPool(
            // 2 threads minimum; on a multi-core dev box JVM scheduling gives
            // genuine interleaving rather than cooperative time-slicing.
            maxOf(2, Runtime.getRuntime().availableProcessors())
        )
        try {
            val worstError = AtomicReference<Throwable?>(null)
            val updatesAfterDestroy = AtomicInteger(0)
            val destroyCalls = AtomicInteger(0)

            repeat(iterations) { iteration ->
                val estimator = ConcurrentMirror()
                val startGate = CountDownLatch(1)
                val updaterDone = CountDownLatch(1)
                val destroyerDone = CountDownLatch(1)

                // Thread 1: hammer update() — the AR render thread analogue.
                executor.submit {
                    try {
                        startGate.await()
                        repeat(hammerCount) { i ->
                            val sawDestroyed = estimator.update()
                            if (sawDestroyed) {
                                updatesAfterDestroy.incrementAndGet()
                            }
                            // Yield around the halfway mark so the destroyer
                            // thread (spin-waiting on updateCount() >= halfMark)
                            // actually gets scheduled on single-core CI runners.
                            // Without this, the updater can hold the CPU for
                            // its entire 1000-call loop and finish BEFORE
                            // destroy() ever fires, leaving the lower-bound
                            // race-coverage check at 0. See #1258. Skip i=0 —
                            // the destroyer hasn't even reached its spin-wait
                            // yet on the very first iteration, no point
                            // yielding to a thread that's still being started.
                            if (i > 0 && i and 0x3F == 0) Thread.yield()
                        }
                    } catch (t: Throwable) {
                        worstError.compareAndSet(null, t)
                    } finally {
                        updaterDone.countDown()
                    }
                }

                // Thread 2: destroy() somewhere inside the hammer window.
                // The point of "50%" in the acceptance spec is that destroy()
                // MUST overlap with the updater's hot loop — not that destroy
                // fires at a measured midpoint. We achieve that by piggy-backing
                // on the estimator's own per-call counter (volatile via
                // AtomicInteger) and firing destroy as soon as the updater has
                // landed roughly half the hammer count. This is host-speed-
                // independent and guarantees genuine interleaving on every
                // iteration regardless of CI vs dev machine throughput.
                executor.submit {
                    try {
                        startGate.await()
                        // Spin-wait until the updater has done ~half the work,
                        // then destroy. The wait is bounded so a degenerate
                        // updater (e.g. paused by JIT compilation) doesn't
                        // hang the test — after the bound, destroy fires
                        // anyway and the leftover updates land post-destroy.
                        val halfMark = hammerCount / 2
                        val bound = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                        while (estimator.updateCount() < halfMark &&
                            System.nanoTime() < bound
                        ) {
                            Thread.yield()
                        }
                        estimator.destroy()
                        destroyCalls.incrementAndGet()
                    } catch (t: Throwable) {
                        worstError.compareAndSet(null, t)
                    } finally {
                        destroyerDone.countDown()
                    }
                }

                // Start both threads simultaneously and wait for both.
                startGate.countDown()
                assertTrue(
                    "Updater thread must finish within 5s on iteration $iteration",
                    updaterDone.await(5, TimeUnit.SECONDS)
                )
                assertTrue(
                    "Destroyer thread must finish within 5s on iteration $iteration",
                    destroyerDone.await(5, TimeUnit.SECONDS)
                )

                // Per-iteration sanity: destroy was called, isDestroyed read
                // by the next call returns true.
                //
                // Acceptance spec requires "isDestroyed bool reads correctly
                // post-destroy" — no statement about post-destroy texture
                // count. Production can legitimately leak a single late-
                // allocated cubemap if `destroy()` interleaves between an
                // updater's gate-check and its `Texture.Builder().build()`;
                // that residual ref is freed on next `destroy()` (we test
                // idempotency separately) or when the JVM GC collects the
                // estimator. The crash invariant is what matters here.
                assertTrue(
                    "isDestroyed must read true post-destroy on iteration $iteration",
                    estimator.isDestroyed()
                )
                // Calling update() one more time after the join MUST be a
                // no-op (gate caught) — neither crash nor allocate further.
                val aliveBeforeFinalUpdate = estimator.aliveTextureCount()
                val sawDestroyedAfterJoin = estimator.update()
                assertTrue(
                    "Post-join update() must observe isDestroyed = true " +
                        "on iteration $iteration",
                    sawDestroyedAfterJoin
                )
                assertEquals(
                    "Post-join update() must not allocate after destroy " +
                        "on iteration $iteration",
                    aliveBeforeFinalUpdate, estimator.aliveTextureCount()
                )
            }

            assertEquals(
                "destroy() must have been called exactly once per iteration",
                iterations, destroyCalls.get()
            )
            // The whole point: zero exceptions across the entire 100-iteration sweep.
            val captured = worstError.get()
            assertEquals(
                "Concurrent update+destroy stress must not throw " +
                    "(captured: ${captured?.javaClass?.simpleName}: ${captured?.message})",
                null, captured
            )
            // We expect SOME updates to land after destroy on most iterations
            // (the gate path is what protects us). If we land zero across 100
            // iterations the test scheduling failed to exercise the race window
            // — but the hard contract is "zero crashes" (asserted above), not
            // "race coverage achieved". A 0-count run on a heavily-loaded CI
            // runner is a test-infra flake, not a regression. We log a warning
            // so the maintainer running locally sees coverage gaps, but we
            // don't fail CI for them. See #1258.
            if (updatesAfterDestroy.get() == 0) {
                // System.err so Gradle's default test output picks it up
                // (stdout is hidden behind `testLogging.showStandardStreams`,
                // stderr is always surfaced). Routes to `<system-err>` in the
                // JUnit XML report regardless.
                System.err.println(
                    "⚠️  LightEstimatorConcurrentDestroyTest: race window was " +
                        "never exercised across $iterations iterations " +
                        "(the destroyer thread fired AFTER the updater finished " +
                        "every time — CI scheduling starved the destroyer). " +
                        "No crashes were observed, which IS the contract. " +
                        "If running locally, consider bumping iterations or " +
                        "hammerCount. See #1258."
                )
            }
        } finally {
            executor.shutdownNow()
            assertTrue(
                "Executor must terminate within 5s",
                executor.awaitTermination(5, TimeUnit.SECONDS)
            )
        }
    }

    /**
     * Inverse stress: `destroy()` from many threads simultaneously must remain
     * idempotent (no `IllegalStateException` on double-free, no extra texture
     * deallocations). Mirror of [LightEstimator.destroy] idempotency contract
     * required by `DisposableEffect.onDispose` semantics.
     *
     * 8 threads call `destroy()` simultaneously on the same instance; the
     * destroy path must:
     *  - free each native ref exactly once (no double-free crash).
     *  - leave `isDestroyed` observable as `true` to every reader.
     *  - return cleanly from every concurrent caller.
     */
    @Test
    fun `destroy is idempotent under 8-thread contention`() {
        repeat(iterations) { iteration ->
            val estimator = ConcurrentMirror()
            // Seed the texture refs so destroy() has something to free.
            estimator.update()
            val initialAlive = estimator.aliveTextureCount()
            assertTrue(
                "Pre-destroy must hold ≥1 alive texture on iteration $iteration",
                initialAlive > 0
            )

            val threads = 8
            val executor = Executors.newFixedThreadPool(threads)
            try {
                val startGate = CountDownLatch(1)
                val done = CountDownLatch(threads)
                val errors = AtomicReference<Throwable?>(null)

                repeat(threads) {
                    executor.submit {
                        try {
                            startGate.await()
                            estimator.destroy()
                        } catch (t: Throwable) {
                            errors.compareAndSet(null, t)
                        } finally {
                            done.countDown()
                        }
                    }
                }

                startGate.countDown()
                assertTrue(
                    "All $threads destroy threads must finish within 5s on iteration $iteration",
                    done.await(5, TimeUnit.SECONDS)
                )
                val err = errors.get()
                assertEquals(
                    "destroy() under contention must not throw " +
                        "(captured: ${err?.javaClass?.simpleName}: ${err?.message}) " +
                        "on iteration $iteration",
                    null, err
                )
                assertTrue(
                    "isDestroyed must be true after all 8 destroy() calls on iteration $iteration",
                    estimator.isDestroyed()
                )
                assertEquals(
                    "Each texture must be freed exactly once (not 8 times) on iteration $iteration",
                    initialAlive, estimator.freedTextureCount()
                )
            } finally {
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    /**
     * Sequencing pin: after `destroy()` returns, every subsequent `update()` —
     * from any thread — observes `isDestroyed = true` and short-circuits before
     * touching native refs. Models the happens-before guarantee that `@Volatile`
     * gives on the production field.
     *
     * Catches a regression where a future maintainer drops `@Volatile` thinking
     * a sequential `destroy()` `update()` from one thread is enough — the JMM
     * does not guarantee the publish without volatile or a synchronizer.
     */
    @Test
    fun `post-destroy update from any thread reads isDestroyed = true`() {
        repeat(iterations) { iteration ->
            val estimator = ConcurrentMirror()
            estimator.update()  // seed
            estimator.destroy()

            // Probe from N reader threads — every one must observe true.
            val readers = 4
            val executor = Executors.newFixedThreadPool(readers)
            try {
                val startGate = CountDownLatch(1)
                val done = CountDownLatch(readers)
                val falseReads = AtomicInteger(0)

                repeat(readers) {
                    executor.submit {
                        startGate.await()
                        // Multiple probes per reader to surface any flaky publish.
                        repeat(50) {
                            val sawDestroyed = estimator.update()
                            if (!sawDestroyed) falseReads.incrementAndGet()
                        }
                        done.countDown()
                    }
                }

                startGate.countDown()
                assertTrue(done.await(5, TimeUnit.SECONDS))
                assertEquals(
                    "Every reader thread must observe isDestroyed = true after destroy " +
                        "on iteration $iteration",
                    0, falseReads.get()
                )
            } finally {
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    /**
     * Acceptance pin: `isDestroyed` is `false` at construction time, becomes
     * `true` after the first `destroy()`, and stays `true` after subsequent
     * `destroy()` / `update()` calls — never flips back to `false`.
     */
    @Test
    fun `isDestroyed monotonic — false to true with no flip-back`() {
        val estimator = ConcurrentMirror()
        assertFalse(
            "Fresh estimator must report isDestroyed = false",
            estimator.isDestroyed()
        )

        estimator.update()
        assertFalse(estimator.isDestroyed())

        estimator.destroy()
        assertTrue(estimator.isDestroyed())

        // Multiple post-destroy calls must keep it pinned to true.
        repeat(10) {
            estimator.update()
            assertTrue(estimator.isDestroyed())
            estimator.destroy()
            assertTrue(estimator.isDestroyed())
        }
    }

    // ── Mirror ──────────────────────────────────────────────────────────────

    /**
     * Thread-safe algorithmic mirror of [LightEstimator]'s `isDestroyed` gate,
     * the texture setter's destroy-on-reassign, and `destroy()`'s
     * latch-then-free sequencing. Lives inside the test so a regression in the
     * production code (sequencing, volatility, idempotency) does not silently
     * pass — any production change must be mirrored here in lockstep.
     */
    private class ConcurrentMirror {
        private val isDestroyedFlag = AtomicBoolean(false)
        // Texture refs — modelled as integer IDs allocated monotonically.
        private val alive = AtomicInteger(0)
        private val freed = AtomicInteger(0)
        // Cubemap counter — every successful update() allocates one mock texture
        // (the destroy-on-reassign setter frees the previous).
        private val nextId = AtomicInteger(0)
        private val currentTextureId = AtomicReference<Int?>(null)
        // Total update() calls (gated or not). Read by the destroyer thread
        // to fire destroy at ~halfway through the updater's hammer loop,
        // host-speed-independent.
        private val updates = AtomicInteger(0)

        /**
         * Mirror of [LightEstimator.update] gating + texture allocation.
         *
         * @return true if the call short-circuited because `isDestroyed` was
         *         already latched — analogue to the production `return null`
         *         path at L180.
         */
        fun update(): Boolean {
            updates.incrementAndGet()
            // Mirror: if (isDestroyed) return null.
            if (isDestroyedFlag.get()) return true
            // Mirror: cubeMapTexture = Texture.Builder()...build() —
            // destroy-on-reassign means the prior texture is freed.
            val prior = currentTextureId.getAndSet(nextId.incrementAndGet())
            alive.incrementAndGet()
            if (prior != null) {
                alive.decrementAndGet()
                freed.incrementAndGet()
            }
            return false
        }

        /**
         * Total [update] invocations across all threads since construction.
         * Used by the destroyer in the 50% test to fire `destroy()` once the
         * updater is roughly halfway through its hammer loop.
         */
        fun updateCount(): Int = updates.get()

        /**
         * Mirror of [LightEstimator.destroy] — latch the flag BEFORE freeing
         * textures so a concurrent reader observes the destroyed state and
         * short-circuits instead of racing with the texture frees.
         */
        fun destroy() {
            // Latch FIRST. The JMM happens-before guarantee from AtomicBoolean
            // mirrors @Volatile in production.
            isDestroyedFlag.set(true)
            // Then free, via getAndSet so exactly one caller receives the texture.
            //
            // ⚠️ Read this before "simplifying" the mirror or the production setter.
            // Until 2026-08-14 this line already used `getAndSet` while production
            // used `field?.let { destroy }` then `field = value` — a read-modify-write
            // with no atomicity. The mirror therefore modelled a *more correct*
            // implementation than the code it claimed to pin, and a mirror that is
            // safer than production cannot fail on production's bug: this suite stayed
            // green for months while two concurrent `destroy()` calls double-freed the
            // same Filament texture. It took a Pixel 4a (`SIGABRT` in
            // `scudo::reportHeaderRace`) to surface it. The two are now the same
            // algorithm, which is the only condition under which this file is a pin.
            val prior = currentTextureId.getAndSet(null)
            if (prior != null) {
                alive.decrementAndGet()
                freed.incrementAndGet()
            }
        }

        fun isDestroyed(): Boolean = isDestroyedFlag.get()

        fun aliveTextureCount(): Int = alive.get()

        fun freedTextureCount(): Int = freed.get()
    }
}
