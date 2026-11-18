package io.github.sceneview.ar.camera

import com.google.android.filament.Engine
import com.google.android.filament.VertexBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import java.nio.Buffer
import java.util.concurrent.Executor

/** Exercises the production upload calls; JNI completion requires an explicit dispatcher. */
@RunWith(RobolectricTestRunner::class)
@Config(
    instrumentedPackages = ["com.google.android.filament"],
    shadows = [CameraStreamUploadTest.StubEngine::class, CameraStreamUploadTest.RecordingVertexBuffer::class],
)
class CameraStreamUploadTest {
    private val engine = Engine::class.java
        .getDeclaredConstructor(Long::class.javaPrimitiveType, Engine.Config::class.java)
        .apply { isAccessible = true }.newInstance(1L, null)
    private val vertexBuffer = VertexBuffer::class.java
        .getDeclaredConstructor(Long::class.javaPrimitiveType)
        .apply { isAccessible = true }.newInstance(1L)
    private val backend: RecordingVertexBuffer = Shadow.extract(vertexBuffer)

    @Test
    fun `all three uploads dispatch completion before a ring slot can be reused`() {
        val ring = CameraStreamUploadRing(3)
        val slots = List(3) { requireNotNull(ring.acquire()) }
        slots.forEach { ARCameraStream.uploadCoordinates(engine, vertexBuffer, it) }
        assertEquals(listOf(0, 1, 2, 0, 1, 2, 0, 1, 2), backend.uploads.map { it.index })
        assertNull(ring.acquire())

        slots.forEachIndexed { index, slot ->
            val uploads = backend.uploads.subList(index * 3, index * 3 + 3)
            assertSame(slot.positions, uploads[0].buffer)
            assertSame(slot.imageUvs, uploads[1].buffer)
            assertSame(slot.cameraUvs, uploads[2].buffer)
            uploads.forEach {
                assertTrue("Filament needs an Executor to deliver completion", it.dispatcher is Executor)
                assertSame(backend.uploads.first().dispatcher, it.dispatcher)
                assertNotNull(it.callback)
            }
        }

        backend.uploads[0].consume()
        backend.uploads[1].consume()
        assertNull(ring.acquire())
        backend.uploads[2].consume()
        assertSame(slots[0], ring.acquire())
        backend.uploads.subList(3, 9).forEach { it.consume() }
        assertSame(slots[1], ring.acquire())
        assertSame(slots[2], ring.acquire())
        assertNull(ring.acquire())
    }

    @Test
    fun `dispatched completions keep uploads running beyond three frames`() {
        val ring = CameraStreamUploadRing(3)
        val slots = mutableSetOf<CameraStreamUploadRing.Slot>()
        repeat(300) {
            val slot = requireNotNull(ring.acquire()) { "Coordinate uploads stalled after $it frames" }
            slots += slot
            ARCameraStream.uploadCoordinates(engine, vertexBuffer, slot)
            assertEquals(3, backend.uploads.size)
            backend.uploads.forEach { it.consume() }
            backend.uploads.clear()
        }
        assertEquals(3, slots.size)
    }

    @Implements(Engine::class)
    class StubEngine {
        @Implementation
        @Suppress("UNUSED_PARAMETER")
        fun __constructor__(nativeObject: Long, config: Engine.Config?) = Unit
    }

    @Implements(VertexBuffer::class)
    class RecordingVertexBuffer {
        val uploads = mutableListOf<Upload>()

        @Implementation
        @Suppress("UNUSED_PARAMETER")
        fun setBufferAt(
            engine: Engine,
            bufferIndex: Int,
            buffer: Buffer,
            destOffsetInBytes: Int,
            count: Int,
            handler: Any?,
            callback: Runnable?,
        ) {
            uploads += Upload(bufferIndex, buffer, handler, callback)
        }
    }

    data class Upload(val index: Int, val buffer: Buffer, val dispatcher: Any?, val callback: Runnable?) {
        // Match Filament's releaseCallbackJni: null dispatchers silently drop the callback.
        fun consume() {
            if (dispatcher is Executor && callback != null) dispatcher.execute(callback)
        }
    }
}
