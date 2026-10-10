package io.github.sceneview.geometries

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.Engine
import com.google.android.filament.Filament
import com.google.android.filament.IndexBuffer
import com.google.android.filament.VertexBuffer
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.utils.Utils
import io.github.sceneview.createEglContext
import io.github.sceneview.createEngine
import io.github.sceneview.destroyGeometry
import io.github.sceneview.math.Position
import io.github.sceneview.safeDestroy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sin

/**
 * #4365 against a real Filament engine: a geometry updated far more often than Filament consumes
 * uploads must not exhaust ART's JNI global reference table.
 *
 * The Lines & Paths demo re-uploads 17 tubes on every Compose frame. While its view presented no
 * frame — before the first one, on a loaded host — nothing consumed those uploads, each
 * `setBufferAt` kept its buffer pinned (4 global references), and the process aborted with
 * `JNI ERROR (app bug): global reference table overflow (max=51200)`.
 *
 * [updatesWithNoFrame_doNotExhaustTheGlobalReferenceTable] is that stall, made deterministic: an
 * engine that is never flushed, so **nothing** is consumed. Without the upload gate in
 * `Geometry` it does not fail an assertion — the process aborts, and the instrumentation reports
 * `Process crashed`.
 */
@RunWith(AndroidJUnit4::class)
class GeometryUploadBackPressureTest {

    private companion object {
        /**
         * A tube has three attribute streams (position, tangents, UVs): 3 × 4 global references
         * per update, so ART's 51 200 cap falls at update ~4 267. 20 000 is 240 000 references —
         * far enough past it that a partial fix cannot scrape through.
         */
        const val STALLED_UPDATES = 20_000

        const val POINTS = 8
        const val RADIAL_SEGMENTS = 6

        /** How long Filament's release callbacks get to reach the main thread. */
        const val SETTLE_TIMEOUT_MILLIS = 10_000L
    }

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private lateinit var engine: Engine

    @Before
    fun setup() {
        instrumentation.runOnMainSync {
            Gltfio.init(); Filament.init(); Utils.init()
            engine = createEngine(createEglContext())
        }
    }

    @After
    fun teardown() {
        if (!::engine.isInitialized) return
        instrumentation.runOnMainSync { engine.safeDestroy() }
    }

    /** Runs [block] on the main thread — every Filament JNI call must. */
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    /** A path of [POINTS] points whose shape depends on [step], so every update changes it. */
    private fun pathAt(step: Int): List<Position> = List(POINTS) { index ->
        Position(x = index * 0.1f, y = 0.05f * sin(index + step * 0.37f), z = 0f)
    }

    /** [pathAt] with a point count that changes on every step: 8, 9, 10, 11, 8, … */
    private fun resizedPathAt(step: Int): List<Position> = List(POINTS + step % 4) { index ->
        Position(x = index * 0.1f, y = 0.05f * sin(index + step * 0.37f), z = 0f)
    }

    /**
     * Lets Filament consume what it was handed and the release callbacks reach the main thread,
     * until [tube] has nothing in flight and nothing waiting.
     */
    private fun settle(tube: Tube) {
        val deadline = System.currentTimeMillis() + SETTLE_TIMEOUT_MILLIS
        var settled = false
        while (!settled && System.currentTimeMillis() < deadline) {
            onMain { engine.flushAndWait() }
            instrumentation.waitForIdleSync()
            onMain { settled = tube.isUploadSettled }
            if (!settled) Thread.sleep(20)
        }
        assertTrue(
            "The tube still has an upload in flight or waiting ${SETTLE_TIMEOUT_MILLIS} ms after " +
                "Filament was flushed — a release callback never reopened the gate",
            settled,
        )
    }

    @Test
    fun updatesWithNoFrame_doNotExhaustTheGlobalReferenceTable() {
        lateinit var tube: Tube

        onMain {
            tube = Tube.Builder()
                .points(pathAt(0))
                .radialSegments(RADIAL_SEGMENTS)
                .build(engine)
            // No flush, no frame: nothing handed to Filament below is consumed.
            for (step in 1..STALLED_UPDATES) {
                tube.update(engine, points = pathAt(step))
            }
        }

        // Reaching this line is the assertion: pre-#4365 the loop above aborts the process.
        onMain {
            assertEquals(
                "The geometry must report the state set last, whatever was uploaded",
                pathAt(STALLED_UPDATES),
                tube.points,
            )
            assertFalse(
                "With Filament stalled the last update cannot have been consumed yet",
                tube.isUploadSettled,
            )
        }

        settle(tube)
        onMain { engine.destroyGeometry(tube) }
    }

    /**
     * The same stall with a geometry that changes **size** on every update (#4344): each one needs
     * new buffers, and a rebuild pins a buffer per stream exactly like a rewrite. Rebuilt outside
     * the gate this aborts the process the same way — 20 000 rebuilds × 3 streams × 4 references.
     */
    @Test
    fun resizesWithNoFrame_doNotExhaustTheGlobalReferenceTable() {
        lateinit var tube: Tube

        onMain {
            tube = Tube.Builder()
                .points(pathAt(0))
                .radialSegments(RADIAL_SEGMENTS)
                .build(engine)
            val builtVertexBuffer = tube.vertexBuffer
            val builtOffsets = tube.primitivesOffsets
            // Rebuilt inside the call: nothing was in flight.
            tube.update(engine, points = resizedPathAt(1))
            val firstRebuild = tube.vertexBuffer
            assertNotSame("A longer path needs new buffers", builtVertexBuffer, firstRebuild)
            assertNotEquals(builtOffsets, tube.primitivesOffsets)
            val firstOffsets = tube.primitivesOffsets

            // No flush, no frame: nothing handed to Filament below is consumed.
            for (step in 2..STALLED_UPDATES) {
                tube.update(engine, points = resizedPathAt(step))
            }

            // Reaching this line is the first assertion: ungated, the loop aborts the process.
            assertEquals(resizedPathAt(STALLED_UPDATES), tube.points)
            assertSame(
                "A rebuild that waits must not swap the buffers before it is issued",
                firstRebuild,
                tube.vertexBuffer,
            )
            assertEquals(
                "primitivesOffsets describes the buffers still bound, not the waiting state",
                firstOffsets,
                tube.primitivesOffsets,
            )
            assertFalse(tube.isUploadSettled)
        }

        settle(tube)
        onMain {
            assertEquals(
                "Once issued, the waiting rebuild describes its own buffers",
                tube.primitivesIndices.getOffsets(),
                tube.primitivesOffsets,
            )
            assertTrue(engine.isValidVertexBuffer(tube.vertexBuffer))
            assertTrue(engine.isValidIndexBuffer(tube.indexBuffer))
            engine.destroyGeometry(tube)
        }
    }

    @Test
    fun geometryDestroyedWithARebuildWaiting_dropsItInsteadOfRebuilding() {
        lateinit var tube: Tube
        lateinit var destroyedVertexBuffer: VertexBuffer
        lateinit var destroyedIndexBuffer: IndexBuffer

        onMain {
            tube = Tube.Builder()
                .points(pathAt(0))
                .radialSegments(RADIAL_SEGMENTS)
                .build(engine)
            tube.update(engine, points = pathAt(1)) // in flight
            tube.update(engine, points = resizedPathAt(2)) // a rebuild, waiting
            destroyedVertexBuffer = tube.vertexBuffer
            destroyedIndexBuffer = tube.indexBuffer
            engine.destroyGeometry(tube)
        }

        // The release of the in-flight upload arrives after the geometry is gone. Issuing the
        // waiting rebuild then would allocate buffers nothing will ever destroy.
        settle(tube)
        onMain {
            assertSame(
                "A destroyed geometry must not be given new buffers",
                destroyedVertexBuffer,
                tube.vertexBuffer,
            )
            assertSame(destroyedIndexBuffer, tube.indexBuffer)
        }
    }

    @Test
    fun updatesInterleavedWithFrames_settleOnTheLastState() {
        lateinit var tube: Tube

        onMain {
            tube = Tube.Builder()
                .points(pathAt(0))
                .radialSegments(RADIAL_SEGMENTS)
                .build(engine)
        }

        // Three updates per consumed "frame": two of every three have to wait, so the deferred
        // path — upload from Filament's release callback — is what carries most of them.
        for (frame in 0 until 40) {
            onMain {
                for (update in 1..3) tube.update(engine, points = pathAt(frame * 3 + update))
                engine.flushAndWait()
            }
            instrumentation.waitForIdleSync()
        }

        settle(tube)
        onMain {
            assertEquals(pathAt(40 * 3), tube.points)
            engine.destroyGeometry(tube)
        }
    }

    @Test
    fun geometryDestroyedWithAnUpdateWaiting_dropsItInsteadOfUploading() {
        lateinit var tube: Tube

        onMain {
            tube = Tube.Builder()
                .points(pathAt(0))
                .radialSegments(RADIAL_SEGMENTS)
                .build(engine)
            tube.update(engine, points = pathAt(1)) // in flight
            tube.update(engine, points = pathAt(2)) // waiting
            engine.destroyGeometry(tube)
        }

        // The release of the in-flight upload arrives after the buffers are gone. Uploading the
        // waiting update into them would be a use-after-destroy inside Filament.
        settle(tube)
    }
}
