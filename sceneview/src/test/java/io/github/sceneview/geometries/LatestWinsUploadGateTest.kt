package io.github.sceneview.geometries

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #4365 — the Lines & Paths demo aborted with `JNI ERROR (app bug): global reference table
 * overflow (max=51200)` when its animated tubes were updated on every Compose frame while Filament
 * presented none.
 *
 * Every `setBufferAt` pins its buffer (four JNI global references for a direct one) until
 * Filament's backend thread has consumed the upload, and `Geometry.update` issued one per
 * attribute stream on every call — so the references alive were *updates not yet consumed ×
 * streams × 4*, with no upper bound. [LatestWinsUploadGate] is that bound: one upload in flight,
 * everything else collapsed into the latest.
 *
 * The backend here is a fake that consumes only when the test says so — the "no frame is being
 * presented" condition the demo hit, made deterministic. The same scenario against a real Filament
 * engine, where the process actually aborts without the gate, is
 * `GeometryUploadBackPressureTest` (instrumented).
 */
class LatestWinsUploadGateTest {

    /** A consumer that holds what it is given until [consume] is called. */
    private class Backend {
        val uploaded = mutableListOf<Int>()
        val deferred = mutableListOf<Int>()
        var alive = true
        private val held = ArrayDeque<() -> Unit>()

        /** Uploads the gate has handed over and that have not been released yet. */
        val inFlight get() = held.size

        val gate = LatestWinsUploadGate<Int>(
            merge = { _, next -> next },
            canUpload = { alive },
            upload = { value, onReleased ->
                uploaded += value
                held += onReleased
            },
            onDeferredUpload = { deferred += it },
        )

        /** The backend thread catches up with one upload. */
        fun consume() = held.removeFirst().invoke()
    }

    // ── The bound ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a backend that consumes nothing is handed one upload however many updates are pushed`() {
        val backend = Backend()

        // 20 000 updates of a 3-stream geometry is 240 000 global references without the gate —
        // nearly five times ART's 51 200 cap.
        repeat(STALLED_UPDATES) { backend.gate.submit(it) }

        assertEquals(
            "Every update handed to a stalled backend pins buffers that nothing releases (#4365)",
            1,
            backend.inFlight,
        )
        assertEquals(listOf(0), backend.uploaded)
        assertTrue(backend.gate.hasPending)
    }

    // ── Nothing the caller set last is lost ──────────────────────────────────────────────────────

    @Test
    fun `the latest update goes out as soon as the in-flight one is released`() {
        val backend = Backend()
        repeat(STALLED_UPDATES) { backend.gate.submit(it) }

        backend.consume()

        assertEquals(
            "The state set last must be the one that reaches Filament; the ones in between are " +
                "skipped",
            listOf(0, STALLED_UPDATES - 1),
            backend.uploaded,
        )
        assertEquals(1, backend.inFlight)
        assertFalse(backend.gate.hasPending)

        backend.consume()
        assertTrue("Nothing in flight, nothing waiting", backend.gate.isIdle)
        assertEquals(0, backend.inFlight)
    }

    @Test
    fun `a backend that keeps up receives every update`() {
        val backend = Backend()

        repeat(5) {
            backend.gate.submit(it)
            backend.consume()
        }

        assertEquals(listOf(0, 1, 2, 3, 4), backend.uploaded)
        assertTrue(backend.gate.isIdle)
    }

    @Test
    fun `pending submissions are folded with merge, pending first`() {
        // Two independent streams, as a geometry's vertices and indices are: the newest of each
        // must survive, not just the newest submission.
        val uploaded = mutableListOf<Pair<String?, String?>>()
        val held = ArrayDeque<() -> Unit>()
        val gate = LatestWinsUploadGate<Pair<String?, String?>>(
            merge = { pending, next -> (next.first ?: pending.first) to (next.second ?: pending.second) },
            canUpload = { true },
            upload = { value, onReleased ->
                uploaded += value
                held += onReleased
            },
        )

        gate.submit("v0" to "i0")
        gate.submit("v1" to null)
        gate.submit(null to "i1")
        gate.submit("v2" to null)
        held.removeFirst().invoke()

        assertEquals(listOf("v0" to "i0", "v2" to "i1"), uploaded)
    }

    // ── Waking the view ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `only an upload that had to wait is reported as deferred`() {
        val backend = Backend()

        backend.gate.submit(1)
        assertEquals(
            "An upload issued inside the caller's own submit needs no extra frame request",
            emptyList<Int>(),
            backend.deferred,
        )

        backend.gate.submit(2)
        backend.gate.submit(3)
        backend.consume()

        assertEquals(
            "The upload left from a release callback: nothing else tells the view to draw it",
            listOf(3),
            backend.deferred,
        )
    }

    // ── A target that went away while an update waited ───────────────────────────────────────────

    @Test
    fun `a pending update is dropped when its target is gone, and the gate reopens`() {
        val backend = Backend()
        backend.gate.submit(1)
        backend.gate.submit(2)

        backend.alive = false
        backend.consume()

        assertEquals("Nothing may be uploaded into a destroyed buffer", listOf(1), backend.uploaded)
        assertEquals(emptyList<Int>(), backend.deferred)
        assertTrue(backend.gate.isIdle)
    }

    @Test
    fun `discardPending forgets what was waiting but keeps the gate closed until release`() {
        val backend = Backend()
        backend.gate.submit(1)
        backend.gate.submit(2)

        backend.gate.discardPending()
        assertFalse(backend.gate.hasPending)
        assertFalse("The first upload is still with the backend", backend.gate.isIdle)

        backend.consume()
        assertEquals(listOf(1), backend.uploaded)
        assertTrue(backend.gate.isIdle)
    }

    // ── Releases the gate must survive ───────────────────────────────────────────────────────────

    @Test
    fun `an upload released synchronously leaves the gate open`() {
        val uploaded = mutableListOf<Int>()
        val gate = LatestWinsUploadGate<Int>(
            merge = { _, next -> next },
            canUpload = { true },
            upload = { value, onReleased ->
                uploaded += value
                onReleased()
            },
        )

        repeat(3) { gate.submit(it) }

        assertEquals(listOf(0, 1, 2), uploaded)
        assertTrue(gate.isIdle)
    }

    @Test
    fun `an upload that throws reopens the gate and its late release is ignored`() {
        val uploaded = mutableListOf<Int>()
        val releases = mutableListOf<() -> Unit>()
        val gate = LatestWinsUploadGate<Int>(
            merge = { _, next -> next },
            canUpload = { true },
            upload = { value, onReleased ->
                // One stream is handed over, then the next one fails.
                releases += onReleased
                if (value < 0) error("bad vertices")
                uploaded += value
            },
        )

        assertThrows(IllegalStateException::class.java) { gate.submit(-1) }
        assertTrue("A failed upload must not close the gate for good", gate.isIdle)

        gate.submit(1)
        gate.submit(2)
        // The stream of the failed upload that did reach the backend reports back now. It is not
        // the upload in flight: letting it reopen the gate would put two uploads in flight.
        releases[0].invoke()
        assertEquals(listOf(1), uploaded)

        releases[1].invoke()
        assertEquals(listOf(1, 2), uploaded)
    }

    @Test
    fun `a release reported twice opens the gate once`() {
        val held = mutableListOf<() -> Unit>()
        val uploaded = mutableListOf<Int>()
        val gate = LatestWinsUploadGate<Int>(
            merge = { _, next -> next },
            canUpload = { true },
            upload = { value, onReleased ->
                uploaded += value
                held += onReleased
            },
        )
        gate.submit(1)
        gate.submit(2)
        gate.submit(3)

        held[0].invoke()
        gate.submit(4)
        held[0].invoke()

        assertEquals(
            "The second report belongs to an upload already released; 3 is still in flight",
            listOf(1, 3),
            uploaded,
        )
        assertTrue(gate.hasPending)

        held[1].invoke()
        assertEquals(listOf(1, 3, 4), uploaded)
    }

    // ── ReleaseCountdown: one upload is several Filament calls ───────────────────────────────────

    @Test
    fun `countdown fires once, on the last stream released`() {
        var fired = 0
        val countdown = ReleaseCountdown(3) { fired++ }

        countdown.release()
        countdown.release()
        assertEquals("Position and tangents are back, UVs are still pinned", 0, fired)

        countdown.release()
        assertEquals(1, fired)

        countdown.release()
        assertEquals("A stray extra release must not reopen the gate a second time", 1, fired)
    }

    @Test
    fun `countdown rejects an upload with no stream`() {
        assertThrows(IllegalArgumentException::class.java) { ReleaseCountdown(0) {} }
    }

    // ── Call-site contract: every Geometry upload after the Builder's goes through the gate ──────

    private val geometrySource: String by lazy {
        (
            File("src/main/java/io/github/sceneview/geometries/Geometry.kt").takeIf { it.exists() }
                ?: File("sceneview/src/main/java/io/github/sceneview/geometries/Geometry.kt")
            ).readText()
    }

    /** `Geometry`'s own members, from past the `Builder` (whose one-shot upload is not gated). */
    private val updateMembers: String by lazy {
        val start = geometrySource.indexOf("private val uploads = LatestWinsUploadGate")
        assertTrue("Geometry no longer owns an upload gate", start >= 0)
        val end = geometrySource.indexOf("private class GeometryUpload", start)
        assertTrue("Could not find the end of Geometry's members", end > start)
        geometrySource.substring(start, end)
    }

    @Test
    fun `update is the one place that submits to the gate`() {
        // #4344: `setVertices` and `setPrimitivesIndices` are `update` with one argument, so that
        // a list of another size takes the rebuild path instead of overrunning the buffers.
        assertEquals(
            "Geometry.update is the single entry point: it alone hands uploads to the gate",
            1,
            Regex("""uploads\.submit\(""").findAll(updateMembers).count(),
        )
        listOf("fun setVertices(", "fun setPrimitivesIndices(").forEach { setter ->
            val body = updateMembers.substringAfter(setter).substringBefore("\n    }")
            assertTrue(
                "$setter must delegate to update, which decides between rewrite and rebuild",
                body.contains("update(engine, "),
            )
        }
    }

    @Test
    fun `a buffer rebuild is issued by the gate and reports its release`() {
        // #4344 × #4365: a rebuild pins a buffer per stream like any upload. Issued outside the
        // gate — or without the release callback — a geometry that changes size on every update
        // during a stall pins without bound. The instrumented twin, where the process actually
        // aborts, is `GeometryUploadBackPressureTest.resizesWithNoFrame_…`.
        val gateUpload = updateMembers.substringAfter("upload = {").substringBefore("\n")
        assertTrue(
            "The gate's upload must go through issueUpload, which picks rewrite or rebuild",
            gateUpload.contains("issueUpload(upload, onReleased)"),
        )
        assertEquals(
            "rebuildBuffers must be reachable from issueUpload only",
            1,
            Regex("""(?<!fun )rebuildBuffers\(""").findAll(updateMembers).count(),
        )
        val rebuild = updateMembers.substringAfter("private fun rebuildBuffers(")
        assertTrue(
            "The rebuild must hand Filament the release callback, or the gate never reopens",
            rebuild.contains(".buildBuffers(engine, releaseThreadHandler(), released)"),
        )
        assertTrue(
            "The gate reopens once every stream of the new buffers is released",
            rebuild.contains("ReleaseCountdown(layout.streamCount, onReleased)"),
        )
    }

    @Test
    fun `the gate checks the buffers the geometry holds now, not the ones it was built with`() {
        // In a property initializer the bare `vertexBuffer` is the constructor parameter. After
        // the first rebuild that buffer is destroyed, the check fails and every waiting update is
        // dropped: `resizesWithNoFrame_…` then ends on stale buffers.
        assertTrue(
            "canUpload must read this.vertexBuffer / this.indexBuffer, which a rebuild replaces",
            updateMembers.contains("it.targets(this.vertexBuffer, this.indexBuffer)"),
        )
    }

    @Test
    fun `destroying a geometry forgets the update that was waiting`() {
        val destroy = geometrySource
            .substringAfter("internal fun destroy(engine: Engine) {")
            .substringBefore("\n    }")
        assertTrue(
            "A waiting update describes buffers that are gone: Geometry.destroy must discard it " +
                "rather than leave it to rebuild a destroyed geometry",
            destroy.contains("uploads.discardPending()"),
        )
        assertTrue(
            "The gate must refuse a waiting update once the geometry is destroyed",
            updateMembers.contains("canUpload = { !lifetime.isDestroyed && "),
        )
    }

    @Test
    fun `no update entry point uploads to Filament directly`() {
        // The pre-#4365 shape: `boundingBox = vertexBuffer.setVertices(engine, vertices)` straight
        // from Geometry.setVertices, once per call, unbounded.
        listOf("vertexBuffer.setVertices(", "indexBuffer.setIndices(", "setBufferAt(", "setBuffer(")
            .forEach { call ->
                assertFalse(
                    "Geometry's update path calls $call directly — it must go through the " +
                        "upload gate or updates pile up unbounded (#4365)",
                    updateMembers.contains(call),
                )
            }
    }

    @Test
    fun `gated uploads ask Filament to report the release`() {
        val issue = geometrySource
            .substringAfter("fun issue(")
            .substringBefore("private val mainThreadHandler")
        assertEquals(
            "The vertex and the index upload must both pass the release callback — a gated " +
                "upload that never reports back closes the gate for good",
            2,
            Regex("""handler, released\b""").findAll(issue).count(),
        )
    }

    private companion object {
        const val STALLED_UPDATES = 20_000
    }
}
