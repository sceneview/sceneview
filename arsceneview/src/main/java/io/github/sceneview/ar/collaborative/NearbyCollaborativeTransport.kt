package io.github.sceneview.ar.collaborative

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A production [CollaborativeTransport] backed by Google's
 * [Nearby Connections API](https://developers.google.com/nearby/connections/overview).
 *
 * This is the **reference peer-to-peer transport** for a collaborative
 * ([CollaborativeSession]) AR experience — the "two phones in the same room"
 * case that is the canonical multi-user AR demo:
 *
 * - **Offline, same-room** — Bluetooth + Wi-Fi, no backend, no API keys, no
 *   privacy surface beyond what the [CloudAnchorNode] shared frame already
 *   requires.
 * - Uses the [`P2P_CLUSTER`][Strategy.P2P_CLUSTER] strategy: every device
 *   advertises **and** discovers simultaneously, so an arbitrary number of
 *   peers form one mesh with no fixed host/client roles. (The *AR* host/resolve
 *   asymmetry is a separate concern handled by [CollaborativeSession].)
 *
 * ### Relationship to [LoopbackCollaborativeTransport]
 *
 * [LoopbackCollaborativeTransport] stays the in-process transport for unit
 * tests and single-device demos. `NearbyCollaborativeTransport` is the real
 * cross-device implementation of the **same** [CollaborativeTransport]
 * contract — `CollaborativeSession` and `CollaborativeWireFormat` are
 * unchanged and unaware of which transport they run on.
 *
 * ### Optional dependency
 *
 * `arsceneview` declares `com.google.android.gms:play-services-nearby` as a
 * `compileOnly` dependency (the same pattern as `androidx.xr.arcore`), so apps
 * that never use collaborative AR do not carry Play Services. **An app that
 * uses this class must add the dependency itself:**
 *
 * ```kotlin
 * implementation("com.google.android.gms:play-services-nearby:19.3.0")
 * ```
 *
 * ### Permissions & runtime disclosure
 *
 * Nearby Connections needs nearby-device permissions; the exact set depends on
 * the OS version (see [REQUIRED_PERMISSIONS_API_31_PLUS] /
 * [REQUIRED_PERMISSIONS_PRE_API_31]). The host **must** request them at runtime
 * before [start] and show a disclosure explaining that the app uses Bluetooth /
 * Wi-Fi to connect to nearby devices. This class never requests permissions
 * itself — that is a UX decision the app owns.
 *
 * ### Threading
 *
 * - [send] is non-blocking per the [CollaborativeTransport] contract: it hands
 *   the payload to [ConnectionsClient.sendPayload], which queues internally.
 * - Nearby invokes its callbacks on the main thread; received payloads are
 *   dispatched straight to registered [incoming] handlers (which, per the
 *   contract, may be called on any thread — [CollaborativeSession] re-marshals
 *   them onto its own scope).
 *
 * ### Usage
 *
 * ```kotlin
 * // After the app has been granted the nearby-device permissions:
 * val transport = NearbyCollaborativeTransport(context, serviceId = "my.ar.app")
 * val session = CollaborativeSession(transport, displayName = "Alice")
 * transport.start()   // begin advertising + discovering
 * session.start()
 * // ... on teardown:
 * session.stop()
 * transport.close()   // stops Nearby, releases all endpoints
 * ```
 *
 * @param context     an Android [Context]; the application context is retained.
 * @param serviceId   a unique id shared by every peer of the same app — only
 *   devices advertising/discovering the same `serviceId` connect. Defaults to
 *   the app's package name.
 * @param localPeerId this device's stable peer id, advertised to peers as the
 *   Nearby connection name. Defaults to a random UUID. Must be unique across
 *   the session and stable for the transport's lifetime.
 * @param tag         Logcat tag for non-fatal warnings.
 */
public class NearbyCollaborativeTransport
@JvmOverloads
public constructor(
    context: Context,
    private val serviceId: String = context.packageName,
    override val localPeerId: String = UUID.randomUUID().toString(),
    private val tag: String = "NearbyTransport",
) : CollaborativeTransport {

    public companion object {
        /**
         * Connection strategy: every peer advertises and discovers at once and
         * an arbitrary number of devices form one mesh. The right fit for
         * collaborative AR — see the class doc.
         */
        // Internal: not part of the public SceneView API — exposing the Nearby
        // `Strategy` type publicly would leak a Play-Services type into arsceneview's
        // API surface (the dependency is `compileOnly`, so consumers couldn't even
        // resolve it). The strategy is an implementation detail, not a knob (#2221 review).
        internal val STRATEGY: Strategy = Strategy.P2P_CLUSTER

        /**
         * Nearby-device permissions required on API 31+ (Android 12+). The host
         * must hold all of these before [start]. `NEARBY_WIFI_DEVICES` is API
         * 33+; on API 31..32 the `BLUETOOTH_*` set plus `ACCESS_FINE_LOCATION`
         * is sufficient — request the subset that applies to the running OS.
         */
        @JvmField
        public val REQUIRED_PERMISSIONS_API_31_PLUS: List<String> = listOf(
            "android.permission.BLUETOOTH_ADVERTISE",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.NEARBY_WIFI_DEVICES",
            "android.permission.ACCESS_FINE_LOCATION",
        )

        /**
         * Nearby-device permissions required before API 31. The host must hold
         * all of these before [start].
         */
        @JvmField
        public val REQUIRED_PERMISSIONS_PRE_API_31: List<String> = listOf(
            "android.permission.BLUETOOTH",
            "android.permission.BLUETOOTH_ADMIN",
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.CHANGE_WIFI_STATE",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_FINE_LOCATION",
        )
    }

    private val appContext: Context = context.applicationContext

    // The Nearby client is created lazily — and only when start()/send() actually
    // need it — so merely constructing the transport never reaches Play Services.
    // `clientCreated` lets close() skip the SDK teardown when start() was never
    // called, keeping a construct-then-close lifecycle Play-Services-free.
    @Volatile private var clientCreated = false
    private val connectionsClient: ConnectionsClient by lazy {
        clientCreated = true
        Nearby.getConnectionsClient(appContext)
    }

    private val registry = NearbyPeerRegistry()
    override val peers: StateFlow<Set<String>> get() = registry.peers

    private val handlers = CopyOnWriteArrayList<(String, ByteArray) -> Unit>()

    @Volatile private var started = false
    @Volatile private var closed = false

    /** `true` between a successful [start] and [close]. */
    public val isStarted: Boolean get() = started

    // ── Lifecycle ─────────────────────────────────────────────────────────

    /**
     * Begins advertising this device and discovering peers over Nearby
     * Connections. Idempotent — a second call while started is a no-op.
     *
     * The app **must** have been granted the nearby-device permissions (see
     * [REQUIRED_PERMISSIONS_API_31_PLUS] / [REQUIRED_PERMISSIONS_PRE_API_31])
     * before calling this; Nearby will fail otherwise.
     *
     * @throws IllegalStateException if the transport has already been [close]d.
     */
    public fun start() {
        check(!closed) { "transport is closed" }
        if (started) return
        started = true

        connectionsClient
            .startAdvertising(
                localPeerId,
                serviceId,
                connectionLifecycleCallback,
                AdvertisingOptions.Builder().setStrategy(STRATEGY).build(),
            )
            .addOnFailureListener { e -> logWarning("startAdvertising failed: ${e.message}") }

        connectionsClient
            .startDiscovery(
                serviceId,
                endpointDiscoveryCallback,
                DiscoveryOptions.Builder().setStrategy(STRATEGY).build(),
            )
            .addOnFailureListener { e -> logWarning("startDiscovery failed: ${e.message}") }
    }

    override fun send(message: ByteArray) {
        if (!started || closed) return
        val framed = try {
            NearbyPayloadFraming.frame(message)
        } catch (e: IllegalArgumentException) {
            logWarning("dropped un-frameable message: ${e.message}")
            return
        }
        val endpoints = registry.connectedEndpointIds()
        if (endpoints.isEmpty()) return
        // sendPayload queues internally — non-blocking, contract-compliant.
        connectionsClient.sendPayload(endpoints, Payload.fromBytes(framed))
    }

    override fun incoming(
        handler: (peerId: String, message: ByteArray) -> Unit,
    ): AutoCloseable {
        handlers.add(handler)
        return AutoCloseable { handlers.remove(handler) }
    }

    override fun close() {
        if (closed) return
        closed = true
        started = false
        handlers.clear()
        registry.clear()
        // Only tear down Nearby if start()/send() ever created the client —
        // a construct-then-close lifecycle must not reach Play Services.
        if (clientCreated) {
            try {
                connectionsClient.stopAllEndpoints()
                connectionsClient.stopAdvertising()
                connectionsClient.stopDiscovery()
            } catch (e: Exception) {
                logWarning("close failed: ${e.message}")
            }
        }
    }

    // ── Nearby callbacks ──────────────────────────────────────────────────

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (closed) return
            // Found a peer advertising our serviceId — request a connection.
            // The lifecycle callback completes the handshake on both sides.
            connectionsClient
                .requestConnection(localPeerId, endpointId, connectionLifecycleCallback)
                .addOnFailureListener { e ->
                    logWarning("requestConnection($endpointId) failed: ${e.message}")
                }
        }

        override fun onEndpointLost(endpointId: String) {
            // The endpoint stopped advertising before a connection completed;
            // a connected endpoint is dropped via onDisconnected instead.
            registry.onDisconnected(endpointId)
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        // endpointId -> peer id read from the incoming ConnectionInfo, held
        // until the connection result arrives so onConnectionResult can map it.
        private val pendingPeerIds = java.util.concurrent.ConcurrentHashMap<String, String>()

        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            if (closed) return
            // The remote's stable peer id is its advertised connection name.
            pendingPeerIds[endpointId] = info.endpointName
            // Auto-accept: this is a same-app collaborative session, so every
            // peer advertising our serviceId is trusted. Apps needing a manual
            // pairing UX can subclass / wrap this transport.
            connectionsClient
                .acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { e ->
                    logWarning("acceptConnection($endpointId) failed: ${e.message}")
                }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            val peerId = pendingPeerIds.remove(endpointId)
            when (resolution.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    if (peerId != null) {
                        registry.onConnected(endpointId, peerId)
                    } else {
                        logWarning("connected endpoint $endpointId had no peer id")
                    }
                }
                else -> {
                    // Rejected or errored — make sure it never lingers in the roster.
                    registry.onDisconnected(endpointId)
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            pendingPeerIds.remove(endpointId)
            registry.onDisconnected(endpointId)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (closed) return
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            val peerId = registry.peerIdFor(endpointId) ?: run {
                logWarning("payload from unknown endpoint $endpointId")
                return
            }
            // One Nearby payload may carry one or several JSON-lines records.
            for (record in NearbyPayloadFraming.unframe(bytes)) {
                dispatch(peerId, record)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // BYTES payloads complete atomically — no partial-transfer handling
            // is needed for the collaborative wire protocol.
        }
    }

    private fun dispatch(peerId: String, message: ByteArray) {
        for (handler in handlers) {
            try {
                handler(peerId, message)
            } catch (e: Exception) {
                logWarning("incoming handler threw: ${e.message}")
            }
        }
    }

    private fun logWarning(msg: String) {
        try { Log.w(tag, msg) } catch (_: RuntimeException) { /* unit test stub */ }
    }
}
