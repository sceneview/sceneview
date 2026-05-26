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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
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
 * - The outbound queue is a **`CONFLATED` [Channel]** — [onFrame] may be
 *   called from the AR render loop, so enqueuing is non-blocking and the
 *   newest pose silently wins under backpressure.
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
 * - Inbound messages are merged on the supervisor scope; observers read the
 *   results via the Compose-observable [participants] / [placedNodes].
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
 * @param poseRateHz  maximum number of camera-pose broadcasts per second.
 *   Later poses inside a tick are dropped. Default 10 Hz; `0` disables throttling.
 * @param tag         Logcat tag for non-fatal warnings.
 */
public class CollaborativeSession
@JvmOverloads
public constructor(
    private val transport: CollaborativeTransport,
    private val displayName: String = transport.localPeerId,
    private val poseRateHz: Int = DEFAULT_POSE_RATE_HZ,
    private val tag: String = "CollaborativeSession",
) {

    public companion object {
        /** Default camera-pose broadcast rate, in Hz. */
        public const val DEFAULT_POSE_RATE_HZ: Int = 10
    }

    private val localPeerId: String = transport.localPeerId
    private val state = CollaborativeState(localPeerId)

    // All message I/O runs here. SupervisorJob so one failed send/merge job
    // never cancels the whole session — same choice as RerunBridge.
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Conflated: enqueuing from the render loop never blocks; the newest
    // outbound line wins if the writer is momentarily behind.
    private val outbox = Channel<ByteArray>(capacity = Channel.CONFLATED)

    private var incomingHandle: AutoCloseable? = null

    @Volatile private var started = false
    @Volatile private var lastPoseEmitNanos: Long = 0L
    private val minPoseIntervalNanos: Long =
        if (poseRateHz <= 0) 0L else 1_000_000_000L / poseRateHz

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
     * existing ones, remove vanished ones.
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
                if (state.retainParticipants(live)) publishParticipants()
            }
            .launchIn(scope)

        // Writer loop — drains the conflated outbox to the transport.
        scope.launch {
            for (line in outbox) {
                try {
                    transport.send(line)
                } catch (e: Exception) {
                    logWarning("send failed: ${e.message}")
                }
            }
        }

        enqueue(CollaborativeWireFormat.hello(localPeerId, displayName))
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
        outbox.close()
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
                enqueue(CollaborativeWireFormat.anchor(localPeerId, cloudAnchorId, name))
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
     * rate-limited to [poseRateHz]: it only reads the camera pose and does a
     * `trySend`, so it is safe on the main render thread. A no-op until a
     * shared anchor exists ([sharedAnchorNode] non-null) — without a shared
     * frame, a pose is not comparable across devices.
     */
    public fun onFrame(frame: Frame) {
        if (!started) return
        val anchorPose = sharedAnchorNode?.anchor?.pose ?: return
        if (!shouldEmitPose(frame.timestamp)) return
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
     */
    public fun broadcastLocalPose(relativePose: Pose) {
        if (!started) return
        val t = floatArrayOf(relativePose.tx(), relativePose.ty(), relativePose.tz())
        val q = floatArrayOf(
            relativePose.qx(), relativePose.qy(), relativePose.qz(), relativePose.qw(),
        )
        enqueue(
            CollaborativeWireFormat.pose(localPeerId, System.currentTimeMillis(), t, q),
        )
    }

    // ── Placed-node broadcast ─────────────────────────────────────────────

    /**
     * Broadcasts that the local user placed (or moved) a node, so every peer
     * can mirror it.
     *
     * Coordinates are in the **shared anchor's local space** — compute them as
     * `sharedAnchorNode.anchor.pose.inverse().compose(worldPose)` before
     * calling, or simply parent your content to [sharedAnchorNode] and read
     * the child node's local transform.
     *
     * @param nodeKey     a unique app-defined key for the placed node.
     * @param modelKey    an app-defined key telling peers which asset to load.
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
        // Reflect the local placement into our own state so placedNodes is a
        // complete picture (it would otherwise only contain remote nodes —
        // CollaborativeState ignores self-originated messages).
        val placed = PlacedNode(nodeKey, modelKey, translation, quaternion, scale, localPeerId)
        if (mergeLocalNode(placed)) publishNodes()
        enqueue(
            CollaborativeWireFormat.node(
                localPeerId, nodeKey, modelKey, translation, quaternion, scale,
            ),
        )
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private val localNodes = LinkedHashMap<String, PlacedNode>()

    private fun mergeLocalNode(placed: PlacedNode): Boolean {
        val previous = localNodes.put(placed.nodeKey, placed)
        return previous != placed
    }

    private fun onTransportMessage(peerId: String, bytes: ByteArray) {
        // Marshal everything onto the session scope so merge + publish never
        // race with the writer loop or the peers flow.
        scope.launch {
            val line = bytes.toString(Charsets.UTF_8)
            val message = CollaborativeWireFormat.parse(line) ?: run {
                logVerbose("dropped unparseable line from $peerId")
                return@launch
            }
            if (state.apply(message)) {
                publishParticipants()
                publishNodes()
                state.sharedAnchor?.let { _sharedCloudAnchorId = it.cloudAnchorId }
            }
        }
    }

    private fun publishParticipants() {
        _participants = state.participants
    }

    private fun publishNodes() {
        // placedNodes is the union of remote nodes (from CollaborativeState)
        // and the local user's own placements (localNodes) — remote entries
        // for a key shadow nothing, but a local key the network also knows
        // about resolves to whichever side wrote last via lastWriterWins.
        val merged = LinkedHashMap<String, PlacedNode>()
        localNodes.forEach { (k, v) -> merged[k] = v }
        state.placedNodes.forEach { merged[it.nodeKey] = it }
        _placedNodes = merged.values.toList()
    }

    /**
     * Enqueue a serialized line for the writer loop. Non-blocking: `trySend`
     * onto the conflated outbox, so a render-loop caller is never blocked and
     * the newest line wins under backpressure.
     */
    private fun enqueue(line: String) {
        outbox.trySend(line.toByteArray(Charsets.UTF_8))
    }

    private fun shouldEmitPose(timestampNanos: Long): Boolean {
        if (minPoseIntervalNanos <= 0L) return true
        val delta = timestampNanos - lastPoseEmitNanos
        if (delta < minPoseIntervalNanos) return false
        lastPoseEmitNanos = timestampNanos
        return true
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
