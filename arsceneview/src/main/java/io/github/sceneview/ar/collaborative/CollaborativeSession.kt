package io.github.sceneview.ar.collaborative

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.filament.Engine
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import io.github.sceneview.ar.node.CloudAnchorNode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Orchestrates a collaborative (multi-user) AR session: two or more devices
 * see the **same** anchored content and each other's live camera poses.
 *
 * ### What it is — and is not
 *
 * ARCore has **no** `collaborationData` / `ParticipantManager` (unlike ARKit).
 * Its entire shared-AR story is Cloud Anchors. `CollaborativeSession` is
 * therefore built on the two pieces that *do* exist:
 *
 * 1. **A shared coordinate frame** via the existing [CloudAnchorNode] — one
 *    device [host]s a Cloud Anchor, every other device [resolve]s the same id.
 *    All content and pose data is then expressed relative to that one anchor.
 * 2. **A pluggable [CollaborativeTransport]** that relays app state (the
 *    shared anchor id, participant camera poses, placed-node transforms) as
 *    JSON-lines [CollaborativeWireFormat] messages between peers.
 *
 * The merge / conflict logic lives in the pure-Kotlin [CollaborativeState];
 * this class is the Android/ARCore/Compose wrapper around it.
 *
 * ### Architecture — mirrors `RerunBridge`
 *
 * Like [io.github.sceneview.ar.rerun.RerunBridge] this is a pluggable bridge:
 *
 * - A dedicated **supervisor [CoroutineScope]** owns all message I/O, so one
 *   failing job never tears the session down.
 * - The outbound queue never blocks the caller — [onFrame] may be called
 *   from the AR render loop. It keeps **one pending line per thing a peer
 *   must end up knowing**: the newest camera pose replaces the pose still
 *   waiting (a pose is only worth its latest value), while each node key's
 *   latest placement or removal, the shared anchor id and the `hello` keep
 *   their own slot and are never dropped by a line enqueued behind them.
 * - A lifecycle-bound [rememberCollaborativeSession] helper wires `start` /
 *   `stop` to the composition.
 *
 * ### Threading (⚠️ Filament JNI = main thread)
 *
 * - [host] / [resolve] call into ARCore + create [CloudAnchorNode]s and **must
 *   run on the main thread** — they are documented as main-thread-only and the
 *   composable host already satisfies this.
 * - [onFrame] is designed to be called from the AR render callback (main
 *   thread). It only *reads* the camera pose and does a non-blocking
 *   `trySend` — no Filament/JNI mutation, no blocking I/O.
 * - Inbound messages are merged on the supervisor scope, one at a time and
 *   in arrival order; observers read the results via the Compose-observable
 *   [participants] / [placedNodes].
 *
 * ### Conflict policy
 *
 * Placements ([placeNode]) and removals ([removeNode]) of one node key are
 * ordered by a per-key counter carried on the wire, with the peer id breaking
 * a tie: last-writer-wins, the same on every device whatever order the lines
 * arrive in. A removed node stays removed when a placement written earlier
 * arrives late. [CollaborativeState] documents the order and its two limits:
 * peers that predate the counter are merged in arrival order, and only the
 * most recent [CollaborativeState.MAX_NODES] removals are remembered.
 *
 * A device that joins late is **not** sent the nodes placed before it joined:
 * it sees a node at that node's next write.
 *
 * ### Security & trust model (#2569)
 *
 * Everything a peer sends is **untrusted input**:
 *
 * - **Identity is bound to the transport connection.** Inbound messages are
 *   attributed to the *connection-bound* transport peer id (the connection
 *   they arrived on) — a message whose body claims a different `peer` is
 *   spoofed and dropped. Note this is per-connection integrity, **not**
 *   authentication: the Nearby peer id is the remote's self-advertised name
 *   (cleartext during discovery), so absent a `shouldAcceptConnection`
 *   out-of-band check a new connection can still *advertise* another peer's
 *   id. Only the authentication-digits comparison authenticates a peer.
 * - **Rosters are bounded.** [CollaborativeState] caps participants and placed
 *   nodes ([CollaborativeState.MAX_PARTICIPANTS] / [CollaborativeState.MAX_NODES])
 *   so a malicious peer cannot grow memory without limit via forged keys.
 * - **[PlacedNode.modelKey] is peer-supplied.** Validate it against an
 *   app-side allow-list before mapping it to an asset — never interpolate it
 *   into a file/asset path directly (path-traversal risk in the consuming app).
 *
 * ### Usage
 *
 * Prefer [rememberCollaborativeSession]; this class is public so non-Compose
 * hosts and unit tests can drive it directly.
 *
 * ```kotlin
 * val transport = LoopbackCollaborativeTransport.LoopbackHub().join("me")
 * val session = CollaborativeSession(transport, displayName = "Alice")
 * session.start()
 * // host (first device):
 * session.host(arSession, anchor) { id, ok -> /* share id out of band */ }
 * // or resolve (joining device):
 * session.resolve(engine, arSession, cloudAnchorId)
 * // every AR frame:
 * arSceneView.onSessionUpdated = { s, frame -> session.onFrame(frame) }
 * ```
 *
 * @param transport   the [CollaborativeTransport] relaying messages between peers.
 * @param displayName a human-readable name broadcast to other peers.
 * @param poseRateHz  maximum number of camera-pose broadcasts per second, for
 *   [onFrame] and [broadcastLocalPose] alike. Default 10 Hz; `0` or less sends
 *   every pose.
 * @param tag         Logcat tag for non-fatal warnings.
 * @param ioDispatcher coroutine dispatcher for the message I/O supervisor scope.
 *   Defaults to [Dispatchers.Default]. Inject a [kotlinx.coroutines.test.TestCoroutineDispatcher]
 *   (or [kotlinx.coroutines.test.StandardTestDispatcher]) in unit tests to drive the session
 *   on virtual time via [kotlinx.coroutines.test.advanceUntilIdle].
 */
public class CollaborativeSession
@JvmOverloads
public constructor(
    private val transport: CollaborativeTransport,
    private val displayName: String = transport.localPeerId,
    private val poseRateHz: Int = DEFAULT_POSE_RATE_HZ,
    private val tag: String = "CollaborativeSession",
    ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    public companion object {
        /** Default camera-pose broadcast rate, in Hz. */
        public const val DEFAULT_POSE_RATE_HZ: Int = 10

        // Outbox slots. A node key is appended to the node prefix, so no node
        // key can collide with the three fixed slots.
        private const val OUTBOX_HELLO = "hello"
        private const val OUTBOX_ANCHOR = "anchor"
        private const val OUTBOX_POSE = "pose"
        private const val OUTBOX_NODE_PREFIX = "node:"

        private const val MILLIS_PER_SECOND = 1000L
    }

    private val localPeerId: String = transport.localPeerId
    private val state = CollaborativeState(localPeerId)

    // All message I/O runs here. SupervisorJob so one failed send/merge job
    // never cancels the whole session — same choice as RerunBridge.
    // ioDispatcher is injected so unit tests can use a StandardTestDispatcher
    // and advance the session on virtual time via advanceUntilIdle().
    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())

    // Guards the merged state and the publishes that follow a change.
    // placeNode / removeNode mutate it on the caller's thread while inbound
    // merges run on the session scope, which may be a thread pool.
    private val stateLock = Any()

    // Outbound lines waiting for the writer loop, one slot per thing a peer
    // must end up knowing (see the OUTBOX_* slots). A newer line replaces only
    // the line in its own slot: the latest pose wins under backpressure, and a
    // placement, the anchor id or the hello is never dropped by a later line.
    private val outboxLock = Any()
    private val outbox = LinkedHashMap<String, ByteArray>()
    private val outboxSignal = Channel<Unit>(capacity = Channel.CONFLATED)

    // Inbound lines in arrival order, drained by ONE coroutine: a launch per
    // message on a multi-threaded dispatcher would apply two writes to the same
    // key in either order, on maps that are not thread-safe.
    private val inbox = Channel<Inbound>(capacity = Channel.UNLIMITED)

    private var incomingHandle: AutoCloseable? = null

    @Volatile private var started = false

    // Pose throttle. The first pose goes out at once and opens a window of
    // poseIntervalMillis; while it is open the newest pose waits in
    // pendingPose and goes out when the window closes, which opens the next
    // one. A window that closes with nothing waiting ends the cycle.
    private val poseIntervalMillis: Long =
        if (poseRateHz <= 0) 0L else (MILLIS_PER_SECOND + poseRateHz - 1) / poseRateHz
    private val poseLock = Any()
    private var poseWindowOpen = false
    private var pendingPose: PendingPose? = null

    // The wall clock behind node counters and pose timestamps. A test
    // replaces it to place two writes at the same instant.
    internal var epochMillis: () -> Long = { System.currentTimeMillis() }

    /**
     * The shared coordinate-frame [CloudAnchorNode], once [host] has succeeded
     * or [resolve] has completed. `null` until then. Parent your collaborative
     * content to this node so every device renders it in the same place.
     */
    public var sharedAnchorNode: CloudAnchorNode? = null
        private set

    // ── Compose-observable state ──────────────────────────────────────────

    private var _participants by mutableStateOf<List<Participant>>(emptyList())
    /** Remote participants currently in the session. Excludes the local user. */
    public val participants: List<Participant> get() = _participants

    private var _placedNodes by mutableStateOf<List<PlacedNode>>(emptyList())
    /**
     * Every node placed by any peer, in shared-anchor local space. Reconcile
     * your scene graph against this each frame: instantiate new keys, move
     * existing ones, remove vanished ones. One entry per key, resolved by the
     * deterministic last-writer-wins policy described above.
     */
    public val placedNodes: List<PlacedNode> get() = _placedNodes

    private var _sharedCloudAnchorId by mutableStateOf<String?>(null)
    /**
     * The ARCore Cloud Anchor ID for the session's shared frame, once a host
     * has announced it. A joining device passes this to [resolve].
     */
    public val sharedCloudAnchorId: String? get() = _sharedCloudAnchorId

    /** `true` between [start] and [stop]. */
    public val isStarted: Boolean get() = started

    // ── Lifecycle ─────────────────────────────────────────────────────────

    /**
     * Starts the session: subscribes to the transport, launches the writer
     * loop, and broadcasts a `hello`. Idempotent — a second call is a no-op.
     */
    public fun start() {
        if (started) return
        started = true

        incomingHandle = transport.incoming { peerId, bytes -> onTransportMessage(peerId, bytes) }

        // Drop participants whose transport link died.
        transport.peers
            .onEach { live ->
                synchronized(stateLock) {
                    if (state.retainParticipants(live)) publishParticipants()
                }
            }
            .launchIn(scope)

        // Reader loop — merges inbound lines one at a time, in arrival order.
        scope.launch {
            for (inbound in inbox) merge(inbound.peerId, inbound.bytes)
        }

        // Writer loop — one wake-up drains every pending outbound line, so the
        // conflated signal never costs a line.
        scope.launch {
            while (outboxSignal.receiveCatching().isSuccess) {
                while (true) {
                    val line = nextOutboundLine() ?: break
                    try {
                        transport.send(line)
                    } catch (e: Exception) {
                        logWarning("send failed: ${e.message}")
                    }
                }
            }
        }

        enqueue(OUTBOX_HELLO, CollaborativeWireFormat.hello(localPeerId, displayName))
    }

    /**
     * Stops the session: broadcasts a `bye`, unsubscribes from the transport,
     * and cancels the I/O scope. Does **not** close the [transport] itself —
     * the transport's owner is responsible for that (the composable helper
     * does it on dispose). Safe to call multiple times.
     */
    public fun stop() {
        if (!started) return
        started = false
        try {
            transport.send(CollaborativeWireFormat.bye(localPeerId).toByteArray())
        } catch (e: Exception) {
            logWarning("bye failed: ${e.message}")
        }
        try {
            incomingHandle?.close()
        } catch (e: Exception) {
            logWarning("unsubscribe failed: ${e.message}")
        }
        incomingHandle = null
        scope.cancel()
        outboxSignal.close()
        inbox.close()
    }

    // ── Shared anchor — host / resolve ────────────────────────────────────

    /**
     * Hosts [anchor] as the session's shared coordinate-frame Cloud Anchor and
     * broadcasts the resulting id to every peer.
     *
     * **Main thread only** — creates a [CloudAnchorNode] and calls into ARCore.
     *
     * **Privacy.** Cloud Anchor hosting uploads visual features of the user's
     * surroundings to Google. Surface a clear disclosure — see
     * [CloudAnchorNode.host].
     *
     * @param session   the active ARCore [Session] (needs `CloudAnchorMode.ENABLED`).
     * @param anchor    the local [Anchor] to promote to the shared frame.
     * @param engine    the Filament [Engine] for the created node.
     * @param ttlDays   Cloud Anchor lifetime, `1..365`. Default `1`.
     * @param name      an app-meaningful name for the anchor (registry key).
     * @param onHosted  called on completion with the cloud anchor id (or `null`
     *   on failure) and a success flag.
     */
    @JvmOverloads
    public fun host(
        session: Session,
        anchor: Anchor,
        engine: Engine,
        ttlDays: Int = 1,
        name: String = "session",
        onHosted: ((cloudAnchorId: String?, success: Boolean) -> Unit)? = null,
    ) {
        val node = CloudAnchorNode(engine, anchor)
        sharedAnchorNode = node
        node.host(session, ttlDays) { cloudAnchorId, hostState ->
            val ok = cloudAnchorId != null && !hostState.isError
            if (ok && cloudAnchorId != null) {
                _sharedCloudAnchorId = cloudAnchorId
                enqueue(
                    OUTBOX_ANCHOR,
                    CollaborativeWireFormat.anchor(localPeerId, cloudAnchorId, name),
                )
            } else {
                logWarning("host failed: $hostState")
            }
            onHosted?.invoke(cloudAnchorId, ok)
        }
    }

    /**
     * Resolves the session's shared coordinate-frame Cloud Anchor by id — the
     * id a host broadcast (see [sharedCloudAnchorId]) or one shared out of band.
     *
     * **Main thread only** — creates a [CloudAnchorNode] and calls into ARCore.
     *
     * @param engine        the Filament [Engine] for the created node.
     * @param session       the active ARCore [Session].
     * @param cloudAnchorId the Cloud Anchor ID to resolve.
     * @param onResolved    called on completion with the resolved
     *   [CloudAnchorNode] (or `null` on failure).
     */
    @JvmOverloads
    public fun resolve(
        engine: Engine,
        session: Session,
        cloudAnchorId: String,
        onResolved: ((node: CloudAnchorNode?) -> Unit)? = null,
    ) {
        CloudAnchorNode.resolve(engine, session, cloudAnchorId) { resolveState, node ->
            if (node != null && !resolveState.isError) {
                sharedAnchorNode = node
                _sharedCloudAnchorId = cloudAnchorId
            } else {
                logWarning("resolve failed: $resolveState")
            }
            onResolved?.invoke(node)
        }
    }

    // ── Per-frame pose broadcast ──────────────────────────────────────────

    /**
     * Broadcasts the local camera pose for this AR [frame], expressed in the
     * shared anchor's local space so every peer can place the "other phone"
     * marker consistently.
     *
     * Call from the AR render callback (`onSessionUpdated`). Non-blocking and
     * rate-limited like [broadcastLocalPose]: it only reads the camera pose
     * and queues a line, so it is safe on the main render thread. A no-op
     * until a shared anchor exists ([sharedAnchorNode] non-null) — without a
     * shared frame, a pose is not comparable across devices.
     */
    public fun onFrame(frame: Frame) {
        if (!started) return
        val anchorPose = sharedAnchorNode?.anchor?.pose ?: return
        // Express the camera pose RELATIVE to the shared anchor:
        //   relative = anchorPose⁻¹ ∘ cameraPose
        // so the numbers mean the same thing on every device regardless of
        // where each device started its own world origin.
        val relative = anchorPose.inverse().compose(frame.camera.pose)
        broadcastLocalPose(relative)
    }

    /**
     * Lower-level overload that broadcasts an already-shared-anchor-relative
     * [relativePose]. Useful for non-ARCore hosts or tests. [onFrame] is the
     * convenient path for a real AR session.
     *
     * Rate-limited to `poseRateHz`, so it can be called every frame. The
     * first pose is sent at once. Of the poses that follow within one
     * interval (`1 / poseRateHz` seconds) only the newest is kept, and it is
     * sent when the interval ends — the pose a device stops on always reaches
     * the peers. `poseRateHz = 0` sends every pose.
     */
    public fun broadcastLocalPose(relativePose: Pose) {
        broadcastLocalPose(
            floatArrayOf(relativePose.tx(), relativePose.ty(), relativePose.tz()),
            floatArrayOf(
                relativePose.qx(), relativePose.qy(), relativePose.qz(), relativePose.qw(),
            ),
        )
    }

    /** [broadcastLocalPose] without the ARCore type, for JVM tests. */
    internal fun broadcastLocalPose(translation: FloatArray, quaternion: FloatArray) {
        if (!started) return
        // Checked here, where the caller can see it: a pose that waits is
        // serialized later, on the session scope.
        require(translation.size == 3) { "translation must have 3 components" }
        require(quaternion.size == 4) { "quaternion must have 4 components" }
        // Stamped now, not when the throttle lets it out: the timestamp says
        // when the device was there.
        val pose = PendingPose(epochMillis(), translation, quaternion)
        if (poseIntervalMillis <= 0L) {
            enqueue(OUTBOX_POSE, pose.toLine())
            return
        }
        synchronized(poseLock) {
            if (poseWindowOpen) {
                // Kept as numbers: called every frame, and a pose that a newer
                // one replaces is never serialized.
                pendingPose = pose
                return
            }
            poseWindowOpen = true
            // Queued under the lock, like the trailing pose below, so two
            // poses never reach the outbox in the wrong order.
            enqueue(OUTBOX_POSE, pose.toLine())
        }
        scope.launch {
            while (true) {
                delay(poseIntervalMillis)
                synchronized(poseLock) {
                    val trailing = pendingPose
                    pendingPose = null
                    if (trailing == null) {
                        poseWindowOpen = false
                        return@launch
                    }
                    enqueue(OUTBOX_POSE, trailing.toLine())
                }
            }
        }
    }

    private inner class PendingPose(
        private val epochMs: Long,
        private val translation: FloatArray,
        private val quaternion: FloatArray,
    ) {
        fun toLine(): String =
            CollaborativeWireFormat.pose(localPeerId, epochMs, translation, quaternion)
    }

    // ── Placed-node broadcast ─────────────────────────────────────────────

    /**
     * Broadcasts that the local user placed (or moved) a node, so every peer
     * can mirror it. Calling it again with a [nodeKey] that already exists
     * moves that node — including one a peer placed, which then becomes this
     * user's (see the class-level *Conflict policy*).
     *
     * Coordinates are in the **shared anchor's local space** — compute them as
     * `sharedAnchorNode.anchor.pose.inverse().compose(worldPose)` before
     * calling, or simply parent your content to [sharedAnchorNode] and read
     * the child node's local transform.
     *
     * @param nodeKey     a unique app-defined key for the placed node.
     * @param modelKey    an app-defined key telling peers which asset to load.
     *   ⚠️ On the receiving side this arrives from an **untrusted peer** —
     *   receivers must validate it against an allow-list of known model keys
     *   and never use it directly in an asset/file path (see the class-level
     *   *Security & trust model* section).
     * @param translation `[x,y,z]` in shared-anchor space.
     * @param quaternion  `[x,y,z,w]` in shared-anchor space.
     * @param scale       `[x,y,z]`. Defaults to unit scale.
     */
    @JvmOverloads
    public fun placeNode(
        nodeKey: String,
        modelKey: String,
        translation: FloatArray,
        quaternion: FloatArray,
        scale: FloatArray = floatArrayOf(1f, 1f, 1f),
    ) {
        if (!started) return
        synchronized(stateLock) {
            val logicalClock = state.nextNodeClock(nodeKey, epochMillis())
            // Serialized first: it validates the arrays, so a call it rejects
            // changes nothing on this device either.
            val line = CollaborativeWireFormat.node(
                localPeerId, nodeKey, modelKey, translation, quaternion, scale, logicalClock,
            )
            val placement = CollaborativeMessage.NodeState(
                localPeerId, nodeKey, modelKey, translation, quaternion, scale, logicalClock,
            )
            // Reflected at once, through the same merge as an inbound line.
            if (!state.applyLocal(placement)) {
                logWarning("placeNode($nodeKey) not applied: the key's counter is exhausted")
                return
            }
            publishNodes()
            // Queued under the state lock: two threads writing one key must
            // queue their lines in counter order, or the older line would be
            // the one left in the key's outbox slot.
            enqueue(OUTBOX_NODE_PREFIX + nodeKey, line)
        }
    }

    /**
     * Removes the node [nodeKey] from the shared scene, on this device and on
     * every peer — whoever placed it. It leaves [placedNodes] at once.
     *
     * The removal is ordered like a placement (see the class-level *Conflict
     * policy*): a placement written before it and delivered after it does not
     * bring the node back, and a later [placeNode] with the same key places
     * the node again.
     *
     * A no-op when [nodeKey] is not in [placedNodes].
     *
     * @param nodeKey the key the node was placed with.
     */
    public fun removeNode(nodeKey: String) {
        if (!started) return
        synchronized(stateLock) {
            if (!state.isPlaced(nodeKey)) return
            val logicalClock = state.nextNodeClock(nodeKey, epochMillis())
            val removal = CollaborativeMessage.NodeRemoval(localPeerId, nodeKey, logicalClock)
            if (!state.applyLocal(removal)) {
                logWarning("removeNode($nodeKey) not applied: the key's counter is exhausted")
                return
            }
            publishNodes()
            // Same slot as the key's placements: a removal replaces a
            // placement of that key still waiting to be sent, and the reverse.
            enqueue(
                OUTBOX_NODE_PREFIX + nodeKey,
                CollaborativeWireFormat.remove(localPeerId, nodeKey, logicalClock),
            )
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private class Inbound(val peerId: String, val bytes: ByteArray)

    private fun onTransportMessage(peerId: String, bytes: ByteArray) {
        // Queued, never merged on the transport's thread: the reader loop is
        // the only place inbound state changes, so two lines cannot race or
        // swap. A no-op once stop() has closed the inbox.
        inbox.trySend(Inbound(peerId, bytes))
    }

    private fun merge(peerId: String, bytes: ByteArray) {
        val line = bytes.toString(Charsets.UTF_8)
        val message = CollaborativeWireFormat.parse(line)
        if (message == null) {
            logVerbose("dropped unparseable line from $peerId")
            return
        }
        // Security (#2569): identity is bound to the CONNECTION the
        // message arrived on (the transport peer id) — never to the
        // peer-controlled "peer" body field. No message type
        // is legitimately relayed on another peer's behalf, so a mismatch
        // is impersonation (forged pose, bye-eviction, roster flooding
        // under thousands of distinct forged keys) and is dropped whole.
        if (message.peerId != peerId) {
            logWarning(
                "dropped spoofed message: transport peer '$peerId' " +
                    "claimed to be '${message.peerId}'",
            )
            return
        }
        synchronized(stateLock) {
            if (!state.apply(message)) return
            publishParticipants()
            publishNodes()
            state.sharedAnchor?.let { _sharedCloudAnchorId = it.cloudAnchorId }
        }
    }

    private fun publishParticipants() {
        _participants = state.participants
    }

    private fun publishNodes() {
        _placedNodes = state.placedNodes
    }

    /**
     * Queues a serialized line for the writer loop, in [slot]. Non-blocking, so
     * a render-loop caller is never held up. A line still waiting in the same
     * slot is replaced — that is what conflates poses — and moved to the back,
     * so the newest write of a slot is also the last one sent.
     */
    private fun enqueue(slot: String, line: String) {
        val bytes = line.toByteArray(Charsets.UTF_8)
        synchronized(outboxLock) {
            outbox.remove(slot)
            outbox[slot] = bytes
        }
        outboxSignal.trySend(Unit)
    }

    private fun nextOutboundLine(): ByteArray? = synchronized(outboxLock) {
        val pending = outbox.values.iterator()
        if (pending.hasNext()) pending.next().also { pending.remove() } else null
    }

    private fun logWarning(msg: String) {
        try { Log.w(tag, msg) } catch (_: RuntimeException) { /* unit test stub */ }
    }

    private fun logVerbose(msg: String) {
        try { Log.v(tag, msg) } catch (_: RuntimeException) { /* unit test stub */ }
    }

    /**
     * Test-only hook: feed a raw wire line as if it arrived from [peerId],
     * bypassing the transport. Lets `CollaborativeSessionTest` exercise the
     * merge path without a real ARCore session.
     */
    internal fun testOnlyReceive(peerId: String, line: String) {
        onTransportMessage(peerId, line.toByteArray(Charsets.UTF_8))
    }
}
