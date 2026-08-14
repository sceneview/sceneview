package io.github.sceneview.ar.light

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.Engine
import com.google.android.filament.Filament
import com.google.android.filament.Texture
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.utils.Utils
import io.github.sceneview.createEglContext
import io.github.sceneview.createEngine
import io.github.sceneview.environment.IBLPrefilter
import io.github.sceneview.safeDestroy
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Concurrent stress regression — `acceptance #3` of umbrella
 * [#1094](https://github.com/sceneview/sceneview/issues/1094), demanded by reviewer R1 of
 * CORR-B (PR [#1134](https://github.com/sceneview/sceneview/pull/1134)) as a follow-up to
 * the JVM-level pinning suite (`LightEstimatorRobustnessTest`).
 *
 * ## What this verifies on a real device
 *
 * `LightEstimator.destroy()` runs from the Compose main thread inside
 * `DisposableEffect.onDispose`. A late AR render frame could call `update()` after
 * the engine teardown began, which previously could:
 *   - call `engine.destroyTexture(cubeMapTexture)` on a freed native context (JNI
 *     SIGSEGV on the AR frame thread), or
 *   - re-assign `cubeMapTexture = null`, which routes through the setter and
 *     would `destroyTexture` a texture the destroy() path already disposed.
 *
 * The CORR-B fix added a `@Volatile isDestroyed` gate that is latched in
 * `destroy()` *before* freeing textures, so a concurrent `update()` reads the
 * gate first and returns immediately. This test exercises that gate on a real
 * Filament `Engine` (not a Robolectric shadow) so:
 *
 *  1. JNI symbols actually resolve — a regression that removed `@Volatile` or
 *     moved the latch after the destroy could surface as a sporadic SIGSEGV
 *     here, not just an assertion fail.
 *  2. Reading the gate from up to 4 reader threads while the destroyer fires
 *     mid-run reproduces the worst-case dispose race in production. Asserts
 *     no exception, a monotonic `isDestroyed` transition, and null textures
 *     once the destroyer has joined. It deliberately does NOT assert that the
 *     fields read null the instant a reader sees the gate flip — see the note
 *     on that assertion in [concurrent_update_reads_and_destroy_areCrashFree].
 *  3. `destroy()` is idempotent under contention — 8 threads racing
 *     `destroy()` calls all return cleanly, the textures end up null, and a
 *     subsequent `destroy()` call from a calm thread also no-ops.
 *
 * Test mechanism:
 *
 *  - The real ARCore `update()` path needs a live `Session`/`Frame`/`Camera`,
 *    which an instrumented test can't easily fabricate. Instead, we
 *    pre-populate the private `cubeMapTexture` / `cubeMapTextureSpecular`
 *    fields via reflection (mimicking the post-`acquireEnvironmentalHdrCubeMap`
 *    state) so `destroy()` has actual JNI work to do, and we hammer the public
 *    `isDestroyed` flag from multiple readers + call `destroy()` from one
 *    writer.
 *  - Reader threads check `isDestroyed` and, if false, reflectively read
 *    `cubeMapTexture` — this mirrors the dangerous read path inside the real
 *    `update()` cleanup branch, which reads the gate first and touches the
 *    cubemap fields only while it reads false.
 *
 * The pure-JVM file `LightEstimatorRobustnessTest` still owns the algorithmic
 * pins for the destroy-gate / texture-leak / buffer-race trio shipped in
 * PR #1134. A second JVM file
 * `arsceneview/src/test/.../LightEstimatorConcurrentDestroyTest.kt` (same name,
 * different source set, on purpose) is the algorithmic mirror of the
 * concurrent-destroy stress and runs on every `:arsceneview:test` invocation
 * in CI without a device.
 *
 * This file is the JNI-grounded companion smoke test that catches the failure
 * mode the two JVM files can't see — a freed-engine `destroyTexture` call
 * SIGSEGVing on a native handle, or an `IllegalStateException` from
 * Filament's "use after destroy" check.
 *
 * To run: `./gradlew :arsceneview:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class LightEstimatorConcurrentDestroyTest {

    private lateinit var engine: Engine
    private lateinit var iblPrefilter: IBLPrefilter

    @Before
    fun setup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            Gltfio.init(); Filament.init(); Utils.init()
            val eglContext = createEglContext()
            engine = createEngine(eglContext)
            iblPrefilter = IBLPrefilter(engine)
        }
    }

    @After
    fun teardown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            iblPrefilter.destroy()
            engine.safeDestroy()
        }
    }

    // ── tests ───────────────────────────────────────────────────────────────

    /**
     * Hammers `isDestroyed` reads from 4 threads × 1000 iterations each while a
     * destroyer thread calls `destroy()` at ~50% of the read run. Pins the
     * volatile-gate contract: the gate flips monotonically under contention, no
     * reader or destroyer throws against a live Filament engine, and both
     * texture fields are null once `destroy()` has returned.
     */
    @Test
    fun concurrent_update_reads_and_destroy_areCrashFree() {
        repeat(10) {
            val estimator = LightEstimator(engine, iblPrefilter)
            seedTextures(estimator)

            val readerExceptions = AtomicReference<Throwable?>(null)
            val readerObservedDestroyed = AtomicBoolean(false)

            val readers = (0 until 4).map {
                Thread {
                    try {
                        // 1000 reads × 4 threads × 10 outer iterations = 40_000
                        // gate reads per run, plenty for the destroyer's 5 ms
                        // `Thread.sleep` to land mid-loop on even a turbo
                        // device — pre-fix would have stayed in the reader
                        // body well past the latch on any realistic CPU,
                        // so the destroy actually races live readers.
                        repeat(1_000) {
                            // Mirrors the dangerous read path inside `update()`:
                            // the gate is read FIRST, and the cubemap fields are
                            // touched only while it still reads false. That is
                            // the ordering the production code uses, so it is the
                            // one worth stressing against a live engine.
                            if (estimator.isDestroyed) {
                                readerObservedDestroyed.set(true)
                            } else {
                                getPrivateField(estimator, "cubeMapTexture")
                                getPrivateField(estimator, "cubeMapTextureSpecular")
                            }
                            Thread.yield()
                        }
                    } catch (t: Throwable) {
                        readerExceptions.set(t)
                    }
                }
            }

            val destroyerException = AtomicReference<Throwable?>(null)
            val destroyer = Thread {
                // ~50% into the readers' run — small enough to land mid-loop on
                // a Pixel-class device, large enough that several iterations
                // happen before AND after the gate latches.
                try {
                    Thread.sleep(5)
                    estimator.destroy()
                } catch (t: Throwable) {
                    destroyerException.set(t)
                }
            }

            readers.forEach { it.start() }
            destroyer.start()
            readers.forEach { it.join(5_000) }
            destroyer.join(5_000)

            assertNull(
                "reader threads must not throw — observed: ${readerExceptions.get()}",
                readerExceptions.get()
            )
            assertNull(
                "destroyer must not throw — observed: ${destroyerException.get()}",
                destroyerException.get()
            )
            assertTrue(
                "destroy() must latch isDestroyed = true",
                estimator.isDestroyed
            )
            assertTrue(
                "at least one reader must have observed the gate flip",
                readerObservedDestroyed.get()
            )
            // NOT asserted: "once a reader observes isDestroyed = true, both
            // texture fields read null". `destroy()` latches the gate BEFORE it
            // frees the textures — deliberately, so a late `update()` bails out
            // instead of racing `engine.destroyTexture` — which leaves a window
            // by construction where the gate is true and the fields are still
            // non-null. That window is benign: `update()` early-returns on the
            // gate and never reads the fields afterwards; only this test's
            // reflection could see it. Asserting the opposite made the test pass
            // on timing, not on correctness, and it failed on a Pixel 4a
            // (2026-08-14, first real-device run of this suite) where the slower
            // CPU keeps a reader inside the window long enough to observe it.
            // Post-destroy contract: textures freed.
            assertNull(getPrivateField(estimator, "cubeMapTexture"))
            assertNull(getPrivateField(estimator, "cubeMapTextureSpecular"))

            // Idempotency — a second destroy() must no-op without exception.
            estimator.destroy()
            assertTrue(estimator.isDestroyed)
        }
    }

    /**
     * 8 threads race to call `destroy()` on the same estimator. The setter
     * `field?.let { engine.destroyTexture(it) }` is null-safe and the gate
     * latch is volatile, so we expect no thread to throw and the post-state
     * to be deterministic: gate latched, both texture fields null.
     */
    @Test
    fun many_threads_calling_destroy_inParallel_isIdempotentAndCrashFree() {
        val estimator = LightEstimator(engine, iblPrefilter)
        seedTextures(estimator)

        val errors = AtomicReference<Throwable?>(null)
        val threads = (0 until 8).map {
            Thread {
                try {
                    estimator.destroy()
                } catch (t: Throwable) {
                    errors.set(t)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(5_000) }

        assertNull(
            "no destroy() caller may throw — observed: ${errors.get()}",
            errors.get()
        )
        assertTrue(estimator.isDestroyed)
        assertNull(getPrivateField(estimator, "cubeMapTexture"))
        assertNull(getPrivateField(estimator, "cubeMapTextureSpecular"))
    }

    /**
     * Engine is still operable after 10 estimator allocate→seed→destroy
     * cycles — pins that the destroy path doesn't corrupt the JNI side of the
     * shared Engine.
     */
    @Test
    fun engine_survives_repeatedAllocateAndDestroy_cycles() {
        repeat(10) {
            val estimator = LightEstimator(engine, iblPrefilter)
            seedTextures(estimator)
            estimator.destroy()
        }
        // Exercise the GPU command stream end-to-end: a JNI use-after-free
        // can leave Builder.build() succeeding (just an allocation) while
        // later commands segfault. flushAndWait() drains the queue and
        // surfaces a corrupted handle as a SIGSEGV here rather than in some
        // unrelated later test. The Texture.Builder().build()+destroyTexture
        // round-trip is what actually proves the Engine is operable.
        val canary = Texture.Builder()
            .width(1)
            .height(1)
            .levels(1)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.R11F_G11F_B10F)
            .build(engine)
        engine.destroyTexture(canary)
        engine.flushAndWait()
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * Builds a real 1×1 cubemap [Texture] on [engine] and reflectively assigns
     * it to the private `cubeMapTexture` + `cubeMapTextureSpecular` fields,
     * so a subsequent `destroy()` actually exercises the JNI free path.
     *
     * The setter-routed assignment is bypassed here on purpose — we want to
     * simulate the post-`acquireEnvironmentalHdrCubeMap` state directly,
     * with no destroy-on-reassign side effects from the seeding step itself.
     */
    private fun seedTextures(estimator: LightEstimator) {
        val tex = Texture.Builder()
            .width(1)
            .height(1)
            .levels(1)
            .sampler(Texture.Sampler.SAMPLER_CUBEMAP)
            .format(Texture.InternalFormat.R11F_G11F_B10F)
            .usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE)
            .build(engine)
        val texSpec = Texture.Builder()
            .width(1)
            .height(1)
            .levels(1)
            .sampler(Texture.Sampler.SAMPLER_CUBEMAP)
            .format(Texture.InternalFormat.R11F_G11F_B10F)
            .usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE)
            .build(engine)
        setPrivateField(estimator, "cubeMapTexture", tex)
        setPrivateField(estimator, "cubeMapTextureSpecular", texSpec)
    }

    /**
     * Rename-safety note: `cubeMapTexture` / `cubeMapTextureSpecular` are
     * private fields read here via reflection. If a future refactor
     * renames them, this test will throw `NoSuchFieldException` at the
     * first reflective access — fix by updating the string constants
     * passed to [getPrivateField] / [setPrivateField] below to match.
     * `isDestroyed` is `internal` so it's read via property access (no
     * reflection) and benefits from compile-time rename detection.
     */
    private fun getPrivateField(target: Any, name: String): Any? {
        val f = target.javaClass.getDeclaredField(name)
        f.isAccessible = true
        return f.get(target)
    }

    private fun setPrivateField(target: Any, name: String, value: Any?) {
        val f = target.javaClass.getDeclaredField(name)
        f.isAccessible = true
        f.set(target, value)
    }
}
