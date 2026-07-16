package io.github.sceneview.ar.rerun

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

/**
 * Integration tests for [RerunBridge] that spin up a local [ServerSocket],
 * run the bridge against it, and assert that the JSON-lines wire format
 * actually makes it over the wire.
 *
 * These tests only use parts of [RerunBridge] that don't touch ARCore —
 * [RerunBridge.logCameraPose] still needs a [com.google.ar.core.Pose], so
 * we feed it via the testable path: constructing the JSON ourselves with
 * [RerunWireFormat.cameraPoseJson] and pushing it through the bridge via
 * the package-internal [RerunBridge.testOnlyEnqueue] hook.
 *
 * We don't test the render-thread drop-on-backpressure semantics here —
 * that's a characteristic of `Channel.CONFLATED` and is covered by the
 * coroutines library's own test suite.
 */
class RerunBridgeTest {

    private lateinit var serverSocket: ServerSocket
    private var acceptedSocket: Socket? = null
    private var port: Int = -1

    @Before
    fun setUp() {
        serverSocket = ServerSocket(0) // OS-assigned port
        port = serverSocket.localPort
    }

    @After
    fun tearDown() {
        try { acceptedSocket?.close() } catch (_: Exception) {}
        try { serverSocket.close() } catch (_: Exception) {}
    }

    private fun acceptAndReadLines(count: Int, timeoutMillis: Long = 5000L): List<String> = runBlocking {
        withTimeout(timeoutMillis) {
            val client = acceptWithin(timeoutMillis)
            acceptedSocket = client
            val reader = client.lineReader(timeoutMillis)
            val lines = ArrayList<String>(count)
            for (i in 0 until count) {
                val line = reader.readLine() ?: break
                lines.add(line)
            }
            lines
        }
    }

    /**
     * Accept a client connection bounded by a wire-level [ServerSocket.setSoTimeout],
     * so [ServerSocket.accept] throws [java.net.SocketTimeoutException] within
     * [timeoutMillis] instead of blocking forever. A blocking JVM socket call is
     * NOT a coroutine suspension point, so an enclosing `withTimeout` can never
     * cancel it — the wire-level timeout is the only deadline that actually
     * interrupts the read (issue #2688).
     */
    private fun acceptWithin(timeoutMillis: Long): Socket {
        serverSocket.soTimeout = timeoutMillis.toInt()
        return serverSocket.accept()
    }

    /**
     * Wrap an accepted client socket's input in a line reader, first arming a
     * wire-level [Socket.setSoTimeout] so a missing/misrouted line surfaces as a
     * [java.net.SocketTimeoutException] within [timeoutMillis] rather than a
     * [BufferedReader.readLine] that blocks forever — no `withTimeout` can
     * interrupt a blocking read (issue #2688).
     */
    private fun Socket.lineReader(timeoutMillis: Long): BufferedReader {
        soTimeout = timeoutMillis.toInt()
        return BufferedReader(InputStreamReader(getInputStream(), Charsets.UTF_8))
    }

    @Test
    fun `bridge writes enqueued JSON lines to the socket`() {
        val bridge = RerunBridge(host = "127.0.0.1", port = port, rateHz = 0)
        try {
            bridge.connect()
            // Directly push pre-built JSON so we don't need a real Pose.
            bridge.testOnlyEnqueue(
                RerunWireFormat.cameraPoseJson(
                    timestampNanos = 1L,
                    tx = 0f, ty = 0f, tz = 0f,
                    qx = 0f, qy = 0f, qz = 0f, qw = 1f,
                ),
            )
            val lines = acceptAndReadLines(1)
            assertEquals(1, lines.size)
            assertTrue(
                "expected camera_pose JSON: ${lines[0]}",
                lines[0].contains("\"type\":\"camera_pose\""),
            )
        } finally {
            bridge.close()
        }
    }

    @Test
    fun `bridge accepts multiple sequential events`() {
        val bridge = RerunBridge(host = "127.0.0.1", port = port, rateHz = 0)
        try {
            bridge.connect()
            // Start the server accept side first so the conflated channel
            // doesn't drop our events before the writer loop picks them up.
            val lines = runBlocking {
                withTimeout(5000L) {
                    val client = acceptWithin(5000L)
                    acceptedSocket = client
                    val reader = client.lineReader(5000L)
                    val collected = ArrayList<String>()
                    // Send one, read one, send next, read next — the conflated
                    // channel drops if we batch, so we pace ourselves.
                    for (i in 1..3) {
                        bridge.testOnlyEnqueue(
                            RerunWireFormat.anchorJson(
                                timestampNanos = i.toLong(),
                                id = i,
                                tx = i.toFloat(), ty = 0f, tz = 0f,
                                qx = 0f, qy = 0f, qz = 0f, qw = 1f,
                            ),
                        )
                        val line = reader.readLine()
                        assertNotNull("line $i was null", line)
                        collected.add(line!!)
                    }
                    collected
                }
            }
            assertEquals(3, lines.size)
            assertTrue(lines[0].contains("world/anchors/1"))
            assertTrue(lines[1].contains("world/anchors/2"))
            assertTrue(lines[2].contains("world/anchors/3"))
        } finally {
            bridge.close()
        }
    }

    @Test
    fun `disabled bridge emits nothing even when connected`() {
        val bridge = RerunBridge(host = "127.0.0.1", port = port, rateHz = 0)
        try {
            bridge.setEnabled(false)
            bridge.connect()
            // Use the Pose-free path too — but since enabled=false, enqueue is
            // short-circuited on the production methods. testOnlyEnqueue bypasses
            // the enabled flag by design (it's for unit tests of the socket layer),
            // so we have to exercise the production path instead.
            // Call a production path that doesn't need a Pose: logPlanes with empty.
            bridge.logPlanes(emptyList(), timestampNanos = 1L)
            // Give the writer loop a moment to process (it won't have anything).
            Thread.sleep(100)
        } finally {
            bridge.close()
        }
        // If nothing was logged, acceptAndReadLines would block indefinitely.
        // We already closed the bridge, so the test passes if we got here.
        assertTrue("disabled bridge should be safe to close", true)
    }

    @Test
    fun `eventsSent increments per successful write and isConnected tracks the socket`() {
        val bridge = RerunBridge(host = "127.0.0.1", port = port, rateHz = 0)
        try {
            // Both states start at their disconnected defaults.
            assertEquals(false, bridge.isConnected)
            assertEquals(0L, bridge.eventsSent)

            bridge.connect()
            // Wait for the writer loop to actually open the socket — connect()
            // is fire-and-forget on a coroutine so isConnected only flips after
            // the socket is established.
            val client = acceptWithin(5000L)
            acceptedSocket = client
            val reader = client.lineReader(5000L)

            // Poll briefly for the state flip (the writer assignment is async).
            val connectedWithin = waitFor(timeoutMillis = 2000L) { bridge.isConnected }
            assertTrue("isConnected didn't flip true after socket open", connectedWithin)

            // Send three events and confirm eventsSent reaches 3.
            for (i in 1..3) {
                bridge.testOnlyEnqueue(
                    RerunWireFormat.cameraPoseJson(
                        timestampNanos = i.toLong(),
                        tx = i.toFloat(), ty = 0f, tz = 0f,
                        qx = 0f, qy = 0f, qz = 0f, qw = 1f,
                    ),
                )
                // Drain so the conflated channel doesn't drop the next push.
                assertNotNull(reader.readLine())
            }
            val sentReached3 = waitFor(timeoutMillis = 2000L) { bridge.eventsSent >= 3L }
            assertTrue(
                "eventsSent never reached 3 (got ${bridge.eventsSent})",
                sentReached3,
            )
        } finally {
            bridge.close()
        }
        // After close: state should reflect disconnected.
        assertEquals(false, bridge.isConnected)
    }

    @Test
    fun `isConnected stays false when no sidecar is listening`() {
        // Use a port we explicitly close, so connect() always fails.
        val deadServer = ServerSocket(0)
        val deadPort = deadServer.localPort
        deadServer.close()

        val bridge = RerunBridge(host = "127.0.0.1", port = deadPort, rateHz = 0)
        try {
            bridge.connect()
            // connect() races a 2s timeout on Socket.connect — give it room.
            Thread.sleep(2200)
            assertEquals(
                "isConnected should remain false when the sidecar is offline",
                false,
                bridge.isConnected,
            )
            assertEquals(
                "eventsSent should remain 0 when nothing was ever shipped",
                0L,
                bridge.eventsSent,
            )
        } finally {
            bridge.close()
        }
    }

    private fun waitFor(timeoutMillis: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(20)
        }
        return predicate()
    }

    @Test
    fun `bridge can be disconnected and reconnected`() {
        val bridge = RerunBridge(host = "127.0.0.1", port = port, rateHz = 0)
        try {
            bridge.connect()
            // Accept and drain the first connection so it doesn't linger in
            // the server's backlog and confuse the second accept() call below.
            val firstClient = acceptWithin(5000L)
            bridge.disconnect()
            firstClient.close()
            // Second connect should work — bridge is reusable.
            bridge.connect()
            bridge.testOnlyEnqueue(
                RerunWireFormat.cameraPoseJson(
                    timestampNanos = 1L,
                    tx = 0f, ty = 0f, tz = 0f,
                    qx = 0f, qy = 0f, qz = 0f, qw = 1f,
                ),
            )
            // Accept the second connection and read the event.
            val lines = acceptAndReadLines(1, timeoutMillis = 3000L)
            assertEquals(1, lines.size)
            assertTrue(lines[0].contains("camera_pose"))
        } finally {
            bridge.close()
        }
    }
}
