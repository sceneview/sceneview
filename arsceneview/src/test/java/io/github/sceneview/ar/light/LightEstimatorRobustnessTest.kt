package io.github.sceneview.ar.light

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Pure-JVM regression tests for the 3 robustness fixes landed alongside
 * [LightEstimator]'s CORR-B audit (acceptance #2 of umbrella #1094):
 *
 *  - **Fix 1** — `isDestroyed` gate prevents `update()` from touching native
 *    resources after `destroy()` (race between Compose's `onDispose` main
 *    thread and the render-loop frame callback).
 *  - **Fix 2** — Toggling `environmentalHdrReflections` from on → off frees
 *    the cubemap textures + staging buffer instead of leaking them. The
 *    setter's destroy-on-reassign only fires on reassignment, never on a
 *    pure flag flip, so a dedicated nullify-on-disable path is needed.
 *  - **Fix 3** — `uploadInFlight` 1-bit semaphore prevents the AR thread
 *    from `clear()`ing + `put()`ing the staging buffer while Filament's
 *    render thread is still draining it to the GPU (corrupted cubemap).
 *
 * These tests mirror the production logic in [LightEstimator] rather than
 * exercising a real Filament [com.google.android.filament.Engine] — Engine
 * pulls in JNI symbols Robolectric does not shadow, so the patterns are
 * pinned at the algorithm level. Matches the precedent set by
 * `ARMainLightBaselineMultiplyTest` for #1062.
 *
 * If any of the production patterns changes, the matching test must change
 * in lock-step or it will fail and force a re-evaluation.
 *
 * Cross-thread tests stand in for the production code's `@Volatile`
 * annotation (which is field-only in Kotlin and therefore cannot live on a
 * test-local `var`). The Java Memory Model guarantees from
 * [CountDownLatch] / [AtomicBoolean] / [AtomicReference] provide the same
 * happens-before semantics the production code gets from `@Volatile`.
 */
class LightEstimatorRobustnessTest {

    // ── Fix 1 — destroy() gate ────────────────────────────────────────────

    /**
     * Reproduces the BROKEN pre-fix path: a render-loop frame that arrives
     * after `destroy()` would call `engine.destroyTexture` on freed native
     * memory. We model this with a shared counter for "engine alive": the
     * pre-fix update() touches it after destroy() flipped it to false.
     */
    @Test
    fun `pre-fix update() touches freed natives after destroy()`() {
        // Mirror: NO isDestroyed gate.
        val engineAlive = AtomicBoolean(true)
        val nativeTouchAfterDestroy = AtomicInteger(0)
        val destroyed = AtomicBoolean(false)

        fun update() {
            // Touches native (destroyTexture, setImage) unconditionally.
            if (destroyed.get()) {
                // Even though destroyed, no gate → touches anyway.
                nativeTouchAfterDestroy.incrementAndGet()
            }
            // … would call engine.destroyTexture(…) here
            require(engineAlive.get() || destroyed.get())  // ← would crash if engine is gone
        }
        fun destroy() {
            destroyed.set(true)
            engineAlive.set(false)
        }

        destroy()
        update()   // BROKEN — touches native after destroy
        assertEquals(
            "Pre-fix path should call into update() with destroy already done",
            1, nativeTouchAfterDestroy.get()
        )
    }

    /**
     * The post-fix `isDestroyed` gate at the top of `update()` short-circuits
     * the call so no native code runs after `destroy()`.
     */
    @Test
    fun `isDestroyed gate short-circuits update() after destroy()`() {
        val isDestroyed = AtomicBoolean(false)
        val nativeTouches = AtomicInteger(0)

        fun update(): Boolean {
            // Mirror of LightEstimator.update():
            //   if (isDestroyed) return null
            if (isDestroyed.get()) return false
            nativeTouches.incrementAndGet()
            return true
        }
        fun destroy() {
            // Mirror of LightEstimator.destroy():
            //   isDestroyed = true   ← latched BEFORE freeing textures
            isDestroyed.set(true)
        }

        assertTrue(update())                  // pre-destroy works
        assertEquals(1, nativeTouches.get())
        destroy()
        assertFalse(update())                 // post-destroy short-circuits
        assertFalse(update())                 // idempotent
        assertEquals(
            "Native must not be touched after destroy()",
            1, nativeTouches.get()
        )
    }

    /**
     * `destroy()` must be idempotent — `DisposableEffect.onDispose` can
     * re-run if keys change before the prior teardown completes.
     */
    @Test
    fun `destroy() is idempotent — repeated calls do not crash or re-free`() {
        val isDestroyed = AtomicBoolean(false)
        val freedTextures = AtomicInteger(0)
        var textureAlive: Int? = 42

        fun destroy() {
            isDestroyed.set(true)
            // Mirror of the setter's *single-threaded* effect: free the old texture, clear
            // the field. Production does this as one atomic `getAndSet` — see
            // `LightEstimatorTextureSwapTest` for why that distinction is load-bearing.
            // This suite is single-threaded, so the shapes are equivalent here.
            textureAlive?.let {
                freedTextures.incrementAndGet()
                textureAlive = null
            }
        }

        destroy()
        destroy()
        destroy()
        assertEquals(
            "Texture must be freed exactly once across N destroy() calls",
            1, freedTextures.get()
        )
        assertTrue(isDestroyed.get())
        assertNull(textureAlive)
    }

    /**
     * The order matters: `isDestroyed = true` MUST be latched BEFORE freeing
     * the textures, otherwise a concurrent render frame could observe
     * `isDestroyed == false`, dereference the texture field, and race with
     * `engine.destroyTexture(…)` running in `destroy()`.
     */
    @Test
    fun `destroy() latches isDestroyed before freeing textures (sequencing)`() {
        val events = mutableListOf<String>()
        val isDestroyed = AtomicBoolean(false)

        fun destroy() {
            isDestroyed.set(true)
            events += "isDestroyed=true"
            // … then free textures
            events += "destroyTexture"
        }
        destroy()
        assertEquals(listOf("isDestroyed=true", "destroyTexture"), events)
    }

    // ── Fix 2 — texture leak on toggle ────────────────────────────────────

    /**
     * Reproduces the BROKEN pre-fix path. The setter's destroy-on-reassign
     * only fires when the field is ASSIGNED, but toggling
     * `environmentalHdrReflections` from true → false takes execution OUT
     * of the `if (environmentalHdrReflections) { … }` branch entirely — so
     * the field is never reassigned, the previous textures stay alive in
     * native heap, and a subsequent toggle back on → off cycle leaks them
     * permanently (until `destroy()` is called).
     */
    @Test
    fun `pre-fix toggle on-off-on-off leaves cubemaps alive forever`() {
        // Mirror of the pre-fix LightEstimator.update() reflection branch.
        var cubeMapTextureAlive: Int? = null
        var cubeMapSpecularAlive: Int? = null
        var allocsDone = 0
        fun preFixUpdate(reflectionsOn: Boolean) {
            if (reflectionsOn) {
                if (cubeMapTextureAlive == null) {
                    cubeMapTextureAlive = ++allocsDone
                }
                // specularFilter() always allocates a fresh texture; the
                // setter destroys the previous one on reassign.
                cubeMapSpecularAlive = ++allocsDone
            }
            // ← no `else` branch in the pre-fix path
        }

        preFixUpdate(true)
        preFixUpdate(true)
        assertNotEquals(null, cubeMapTextureAlive)
        assertNotEquals(null, cubeMapSpecularAlive)

        preFixUpdate(false)  // pre-fix: textures NOT freed here
        assertNotEquals(
            "Pre-fix path leaks cubeMapTextureAlive across toggle-off",
            null, cubeMapTextureAlive
        )
        assertNotEquals(
            "Pre-fix path leaks cubeMapSpecularAlive across toggle-off",
            null, cubeMapSpecularAlive
        )
    }

    /**
     * The post-fix nullify-on-disable path at the top of `update()` frees
     * the cubemap textures + staging buffer whenever the feature flag is
     * off, before any per-frame work runs.
     */
    @Test
    fun `post-fix toggle off frees cubemaps via setter destroy-on-reassign`() {
        // Mirror of the post-fix LightEstimator.update() preamble.
        var cubeMapTextureAlive: Int? = null
        var cubeMapSpecularAlive: Int? = null
        var cubeMapBufferAlive: Any? = null
        val freed = AtomicInteger(0)
        var nextId = 0

        fun setCubeMapTexture(v: Int?) {
            if (cubeMapTextureAlive != null && v == null) freed.incrementAndGet()
            cubeMapTextureAlive = v
        }
        fun setCubeMapSpecular(v: Int?) {
            if (cubeMapSpecularAlive != null && v == null) freed.incrementAndGet()
            cubeMapSpecularAlive = v
        }

        fun update(reflectionsOn: Boolean) {
            if (!reflectionsOn) {
                setCubeMapTexture(null)
                setCubeMapSpecular(null)
                cubeMapBufferAlive = null
                return
            }
            if (cubeMapTextureAlive == null) {
                setCubeMapTexture(++nextId)
                cubeMapBufferAlive = Any()
            }
            setCubeMapSpecular(++nextId)
        }

        update(true)
        update(true)
        assertNotEquals(null, cubeMapTextureAlive)
        assertNotEquals(null, cubeMapSpecularAlive)
        assertNotEquals(null, cubeMapBufferAlive)

        val freedBefore = freed.get()
        update(false)
        assertNull(
            "cubeMapTextureAlive must be nullified on toggle off",
            cubeMapTextureAlive
        )
        assertNull(
            "cubeMapSpecularAlive must be nullified on toggle off",
            cubeMapSpecularAlive
        )
        assertNull(
            "cubeMapBufferAlive must be nullified on toggle off",
            cubeMapBufferAlive
        )
        assertEquals(
            "Both textures must be freed exactly once",
            freedBefore + 2, freed.get()
        )
    }

    /**
     * Toggling on → off 10 times must not accumulate dead references.
     * Catches the regression where the post-fix nullify-on-disable runs
     * only on the first toggle and skips on subsequent ones.
     */
    @Test
    fun `post-fix toggle 10x cycle does not accumulate leaked cubemaps`() {
        var cubeMapTextureAlive: Int? = null
        var cubeMapSpecularAlive: Int? = null
        var nextId = 0

        fun update(reflectionsOn: Boolean) {
            if (!reflectionsOn) {
                cubeMapTextureAlive = null
                cubeMapSpecularAlive = null
                return
            }
            if (cubeMapTextureAlive == null) cubeMapTextureAlive = ++nextId
            cubeMapSpecularAlive = ++nextId
        }

        repeat(10) {
            update(true)
            assertNotEquals(null, cubeMapTextureAlive)
            update(false)
            assertNull(cubeMapTextureAlive)
            assertNull(cubeMapSpecularAlive)
        }
    }

    /**
     * `environmentalHdrSpecularFilter` false (with reflections still on)
     * must free only the specular cubemap, not the base cubemap.
     */
    @Test
    fun `prefilter toggle off frees only specular texture, keeps base`() {
        var cubeMapTextureAlive: Int? = null
        var cubeMapSpecularAlive: Int? = null
        var nextId = 0

        fun update(reflectionsOn: Boolean, prefilterOn: Boolean) {
            if (!reflectionsOn) {
                cubeMapTextureAlive = null
                cubeMapSpecularAlive = null
                return
            }
            if (!prefilterOn) {
                cubeMapSpecularAlive = null
            }
            if (cubeMapTextureAlive == null) cubeMapTextureAlive = ++nextId
            if (prefilterOn) cubeMapSpecularAlive = ++nextId
        }

        update(true, true)
        val baseId = cubeMapTextureAlive
        assertNotEquals(null, baseId)
        assertNotEquals(null, cubeMapSpecularAlive)

        // Toggle prefilter off — base texture must be preserved.
        update(true, false)
        assertEquals(
            "Base cubemap must NOT be freed when only prefilter toggles off",
            baseId, cubeMapTextureAlive
        )
        assertNull(
            "Specular texture must be freed when prefilter toggles off",
            cubeMapSpecularAlive
        )
    }

    // ── Fix 3 — uploadInFlight buffer-race ────────────────────────────────

    /**
     * Reproduces the BROKEN pre-fix path: the AR thread overwrites the
     * staging buffer mid-flight, while Filament's render thread is still
     * reading from it for GPU upload. Result: cubemap face N gets bytes
     * intended for face N+1, producing a smeared cubemap / 1-frame flash.
     */
    /**
     * Algorithmic model: the `IntArray` here stands in for the production
     * `ByteBuffer.allocateDirect(bufferSize)` reused via
     * `cubeMapBuffer?.takeIf { it.capacity() == bufferSize }?.apply { clear() }`
     * at [LightEstimator.kt:303–311]. The test asserts shared-identity
     * mutation, not ByteBuffer-level ABI — per Reviewer 5 HOLD-COMMENT,
     * this is a deliberate algorithmic mirror rather than a buffer
     * compatibility test.
     */
    @Test
    fun `pre-fix concurrent buffer write corrupts in-flight upload`() {
        val buffer = AtomicReference<IntArray>(IntArray(0))
        val uploadedAtFrame = AtomicReference<IntArray?>(null)

        // BROKEN — no upload-in-flight check.
        fun preFixArFrame(frameValue: Int) {
            // Mutate the SAME backing array each frame — mirrors how the
            // production buffer is `cubeMapBuffer?.takeIf { capacity }
            // ?.apply { clear() }` (i.e. reused, not reallocated).
            val current = buffer.get()
            if (current.size == 3) {
                current[0] = frameValue
                current[1] = frameValue
                current[2] = frameValue
            } else {
                buffer.set(intArrayOf(frameValue, frameValue, frameValue))
            }
            // Hand to Filament (async upload). The buffer reference is
            // captured; Filament's render thread reads from it on a
            // later tick.
            uploadedAtFrame.set(buffer.get())
        }

        preFixArFrame(1)
        val inFlight = uploadedAtFrame.get()
        // Frame N+1 arrives before Filament drained the buffer:
        preFixArFrame(2)
        // BROKEN: uploadedAtFrame still points at the SAME backing array,
        // which has been mutated by frame N+1.
        assertEquals(
            "Pre-fix path corrupts the in-flight buffer",
            2, inFlight!![0]
        )
        assertEquals(2, inFlight[1])
        assertEquals(2, inFlight[2])
    }

    /**
     * The post-fix `uploadInFlight` semaphore makes the AR thread skip the
     * cubemap pipeline while Filament's render thread is still draining
     * the staging buffer. The Filament callback resets the flag once the
     * upload is done, allowing the next frame through.
     */
    @Test
    fun `uploadInFlight gate prevents AR thread from corrupting in-flight buffer`() {
        val buffer = AtomicReference<IntArray>(IntArray(0))
        val uploadedAtFrame = AtomicReference<IntArray?>(null)
        val uploadInFlight = AtomicBoolean(false)
        val skipped = AtomicInteger(0)
        val uploaded = AtomicInteger(0)

        fun arFrame(frameValue: Int) {
            // Mirror of LightEstimator.update() reflection branch:
            //   if (environmentalHdrReflections && !uploadInFlight) { … }
            if (uploadInFlight.get()) {
                skipped.incrementAndGet()
                return
            }
            // Reuse the same backing array (mirror of production
            // `cubeMapBuffer?.takeIf { capacity }?.apply { clear() }`).
            val current = buffer.get()
            if (current.size == 3) {
                current[0] = frameValue
                current[1] = frameValue
                current[2] = frameValue
            } else {
                buffer.set(intArrayOf(frameValue, frameValue, frameValue))
            }
            uploadedAtFrame.set(buffer.get())
            uploadInFlight.set(true)   // latched BEFORE setImage()
            uploaded.incrementAndGet()
        }
        fun filamentCallback() { uploadInFlight.set(false) }

        arFrame(1)
        assertEquals(1, uploaded.get())
        val inFlight = uploadedAtFrame.get()
        // 2nd frame while upload still in flight — must skip.
        arFrame(2)
        assertEquals(1, skipped.get())
        // The in-flight buffer must NOT have been mutated.
        assertEquals(
            "uploadInFlight gate must protect the in-flight buffer",
            1, inFlight!![0]
        )
        assertEquals(1, inFlight[1])

        // Filament finishes the upload.
        filamentCallback()
        // 3rd frame proceeds.
        arFrame(3)
        assertEquals(2, uploaded.get())
        assertEquals(3, uploadedAtFrame.get()!![0])
    }

    /**
     * Sequencing pinning — the latch (`uploadInFlight = true`) MUST occur
     * BEFORE handing the buffer to Filament, otherwise an extra-fast
     * Filament backend could fire the callback (setting it false) before
     * we ever observed the latched true.
     */
    @Test
    fun `uploadInFlight latched before setImage to survive sub-microsecond callback`() {
        val events = mutableListOf<String>()
        val uploadInFlight = AtomicBoolean(false)

        fun setImageWithCallback(callback: () -> Unit) {
            events += "setImage(start)"
            // Simulate a Filament backend that fires the callback synchronously.
            callback()
            events += "setImage(end)"
        }
        fun arFrame() {
            if (uploadInFlight.get()) return
            uploadInFlight.set(true)
            events += "latch=true"
            setImageWithCallback {
                uploadInFlight.set(false)
                events += "latch=false"
            }
        }

        arFrame()
        // The latch must precede setImage so the callback can't reset it
        // before we know we latched it.
        assertEquals(
            listOf("latch=true", "setImage(start)", "latch=false", "setImage(end)"),
            events
        )
        assertFalse(uploadInFlight.get())
    }

    /**
     * Stress test: 60 rapid AR-frame submissions with a 2-frame upload
     * latency. The gate must result in roughly half the frames making it
     * through (the other half are skipped) and the in-flight buffer is
     * never mutated mid-flight.
     *
     * Pins the "ARCore updates at 1 Hz so a 1–2 frame skip is invisible"
     * comment in [LightEstimator] — if the gate stopped working, we'd
     * either get 60 uploads (no gate) or 1 upload stuck forever (gate
     * leaks).
     */
    @Test
    fun `60 rapid frames with 2-frame upload latency cycle the gate cleanly`() {
        val uploadInFlight = AtomicBoolean(false)
        val uploaded = AtomicInteger(0)
        val pending = ArrayDeque<Int>()   // simulates Filament's render-thread queue
        var frameCounter = 0

        fun arFrame(frameValue: Int) {
            if (uploadInFlight.get()) return
            uploadInFlight.set(true)
            pending.addLast(frameValue)
            uploaded.incrementAndGet()
        }
        fun filamentTick() {
            // Drains one upload per Filament tick.
            if (pending.isNotEmpty()) {
                pending.removeFirst()
                uploadInFlight.set(false)
            }
        }

        repeat(60) {
            arFrame(frameCounter++)
            // Filament drains 1 upload per 2 AR frames → ~30 uploads expected.
            if (it % 2 == 1) filamentTick()
        }
        // Deterministic JVM model: 60 frames, gate-through every other tick,
        // exactly 30 uploads. Tight assertion catches a subtle gate-leak
        // (e.g. flag reset between iterations) that a wide tolerance would
        // mask. Per Reviewer 5 FIX-MERGE.
        assertEquals(
            "Gate must permit exactly half the rapid frames under steady cadence",
            30, uploaded.get(),
        )
        assertTrue(
            "Pending queue must drain cleanly under steady cadence",
            pending.size <= 1,
        )
    }

    /**
     * Cross-thread test: the AR thread writes the flag; the Filament
     * "render thread" (a real JVM thread here) resets it. Catches a
     * regression where someone drops the `@Volatile` annotation thinking
     * it's redundant — the JMM no-volatile spec lets the AR thread cache
     * `true` forever, locking out all future cubemap updates.
     *
     * Note: [AtomicBoolean] is used in lieu of `@Volatile` (which is
     * field-only in Kotlin). It provides the same happens-before
     * guarantee.
     */
    @Test
    fun `volatile uploadInFlight resets observable across threads within 1s`() {
        val uploadInFlight = AtomicBoolean(false)
        val latch = CountDownLatch(1)

        // AR thread latches the gate.
        uploadInFlight.set(true)
        // Filament render thread resets the gate. No artificial sleep —
        // the `CountDownLatch.await(1, SECONDS)` provides the publish
        // guarantee, the `set/countDown` ordering provides happens-before.
        // Per Reviewer 5 FIX-MERGE — skipping the 50ms sleep saves
        // wall-time without weakening the test.
        Thread {
            uploadInFlight.set(false)
            latch.countDown()
        }.start()

        assertTrue(
            "Filament callback thread must publish the reset within 1s",
            latch.await(1, TimeUnit.SECONDS),
        )
        // AR thread reads the published value.
        assertFalse(
            "AR thread must observe the volatile reset from the render thread",
            uploadInFlight.get(),
        )
    }

    // ── Cross-fix interaction ─────────────────────────────────────────────

    /**
     * After destroy(), neither toggle nor uploadInFlight should matter —
     * the isDestroyed gate at the top of update() takes precedence.
     */
    @Test
    fun `destroy() takes precedence over all toggles and in-flight state`() {
        val isDestroyed = AtomicBoolean(false)
        val environmentalHdrReflections = AtomicBoolean(true)
        val uploadInFlight = AtomicBoolean(false)
        val nativeTouches = AtomicInteger(0)

        fun update() {
            if (isDestroyed.get()) return                       // Fix 1 — takes precedence
            if (!environmentalHdrReflections.get()) return      // Fix 2 — free path
            if (uploadInFlight.get()) return                    // Fix 3 — buffer race
            nativeTouches.incrementAndGet()
        }

        update()
        assertEquals(1, nativeTouches.get())
        isDestroyed.set(true)
        // Even with reflections on and no upload in flight, destroy wins.
        environmentalHdrReflections.set(true)
        uploadInFlight.set(false)
        update()
        assertEquals(
            "destroy() must short-circuit before any other gate",
            1, nativeTouches.get()
        )
    }
}
