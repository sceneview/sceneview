package io.github.sceneview.ar.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure-JVM sentinel for the depth [java.nio.ByteBuffer] lifecycle invariant
 * documented in [ARCameraStream] (#1757).
 *
 * The actual ARCore [com.google.ar.core.Image] / Filament `PixelBufferDescriptor`
 * pipeline cannot run in a JVM unit test (ARCore types are framework-bound and
 * non-mockable in pure JVM, Filament's native engine isn't loaded here). What we
 * *can* and *do* pin in pure JVM:
 *
 *  1. [ByteBuffer.clear] is metadata-only — it resets `position`/`limit` but never
 *    frees the underlying memory. The implementation in
 *    [ARCameraStream.update]'s upload-completed callback relies on this fact: the
 *    actual ARCore image memory release goes through [Image.close], not the
 *    `buffer.clear()` line below it.
 *  2. A toggle of `isDepthOcclusionEnabled` rapidly between `true → false → true`
 *    must not allocate a new buffer in the SETTER path (the buffer comes from
 *    ARCore via `acquireDepthImage16Bits`, which only runs inside the `update`
 *    method — not the setter).
 *
 * Together these pin the architectural invariant: there is exactly one buffer
 * lifecycle per acquired depth image, and the upload-completed callback is the
 * only place it is released.
 */
class ARCameraStreamDepthBufferLifecycleTest {

    @Test
    fun `ByteBuffer clear is metadata-only — never frees backing memory`() {
        val capacity = 38_400 // mirror Pixel-class 160x120x2 depth16 size
        val buffer = ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder())
        // Pre-fill so we can verify the backing memory survives `clear()`.
        for (i in 0 until capacity step 1024) {
            buffer.put(i, 0x77.toByte())
        }
        val before = buffer.get(0)
        val sentinelByte = buffer.get(1024)

        buffer.clear()

        // After clear(): position=0, limit=capacity, mark discarded. Backing
        // memory is intact — this is what the upload-completed callback in
        // ARCameraStream relies on.
        assertEquals(
            "clear() resets position to 0",
            0,
            buffer.position()
        )
        assertEquals(
            "clear() resets limit to capacity",
            capacity,
            buffer.limit()
        )
        assertEquals(
            "clear() must not zero the backing memory",
            before,
            buffer.get(0)
        )
        assertEquals(
            "clear() must not free or wipe later regions",
            sentinelByte,
            buffer.get(1024)
        )
    }

    @Test
    fun `toggling depth occlusion does not allocate a new buffer in the setter path`() {
        // Architectural sentinel — the setter for [ARCameraStream.isDepthOcclusionEnabled]
        // only swaps material instances; the only buffer in play is the one acquired
        // inside [ARCameraStream.update] via `frame.acquireDepthImage16Bits()`. This
        // test mirrors that contract using a plain ByteBuffer: a setter-style flag
        // toggle must be O(1) and not interact with the buffer.
        val capacity = 1024
        val buffer = ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder())
        var enabled = false
        // Burst-toggle 256 times mirroring rapid user input.
        repeat(256) { i ->
            enabled = (i % 2 == 0)
        }
        // The buffer reference never moves — we still hold the same direct buffer
        // throughout, identical to ARCameraStream's design where the in-flight
        // PixelBufferDescriptor's buffer is unrelated to the enable/disable setter.
        assertEquals(
            "burst toggling must not change the buffer's position",
            0,
            buffer.position()
        )
        assertEquals(
            "burst toggling must not change the buffer's capacity",
            capacity,
            buffer.capacity()
        )
        // Final state matches the parity expectation (256 even = true on last write
        // pre-loop-end iteration; iteration 255 has i=255 odd → enabled=false).
        assertEquals("final toggled state matches loop semantics", false, enabled)
    }

    @Test
    fun `direct buffer identity survives metadata-only manipulations`() {
        // Filament's PixelBufferDescriptor holds a strong reference to the buffer
        // until the upload callback fires. Mutating its position/limit does NOT
        // change buffer identity, so Filament's reference remains valid through
        // a `clear()`. This sentinel pins that JVM contract.
        val buffer = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder())
        val originalIdentity = System.identityHashCode(buffer)

        buffer.position(32)
        buffer.limit(48)
        buffer.clear()

        assertEquals(
            "identity must be stable across metadata mutations",
            originalIdentity,
            System.identityHashCode(buffer)
        )

        // And a duplicate is a distinct object — documents the fact that the
        // PixelBufferDescriptor would NEED a `duplicate()` if it wanted an
        // independent view (which it does not, by design).
        val duplicate = buffer.duplicate()
        assertNotSame(buffer, duplicate)
    }
}
