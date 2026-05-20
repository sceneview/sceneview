package io.github.sceneview.ar.node

import com.google.android.filament.Engine
import com.google.ar.core.Anchor
import com.google.ar.core.Anchor.CloudAnchorState
import com.google.ar.core.HostCloudAnchorFuture
import com.google.ar.core.Session
import com.google.ar.core.TrackingState

/**
 * An [AnchorNode] that supports ARCore Cloud Anchors for persistent, cross-device AR experiences.
 *
 * Cloud Anchors allow an anchor's pose to be hosted to the Google Cloud ARCore API and later
 * resolved on a different device or session using the returned cloud anchor ID.
 *
 * ### Hosting
 * After creating a `CloudAnchorNode` with a local [Anchor], call [host] to upload it. On success,
 * [onHosted] is invoked with the cloud anchor ID that can be shared with other devices.
 *
 * ### Resolving
 * Use [CloudAnchorNode.resolve] (companion) to resolve a previously hosted cloud anchor by ID.
 *
 * Requires `Config.CloudAnchorMode.ENABLED` in the ARCore session configuration.
 *
 * @param engine                 The Filament [Engine].
 * @param anchor                 The local [Anchor] to associate with a cloud anchor.
 * @param cloudAnchorId          The cloud anchor ID if already resolved; `null` when hosting.
 * @param onTrackingStateChanged Callback invoked when tracking state changes.
 * @param onUpdated              Callback invoked each frame while the anchor is updated.
 * @param onHosted               Callback invoked when cloud hosting completes (success or fail).
 */
open class CloudAnchorNode constructor(
    engine: Engine,
    anchor: Anchor,
    cloudAnchorId: String? = null,
    onTrackingStateChanged: ((TrackingState) -> Unit)? = null,
    onUpdated: ((Anchor?) -> Unit)? = null,
    var onHosted: ((cloudAnchorId: String?, state: CloudAnchorState) -> Unit)? = null
) : AnchorNode(
    engine = engine,
    anchor = anchor,
    onTrackingStateChanged = onTrackingStateChanged,
    onUpdated = onUpdated
) {
    var cloudAnchorId: String? = cloudAnchorId
        private set

    /**
     * The current cloud anchor state of the [anchor].
     */
    var hostState = CloudAnchorState.NONE
        private set

    var hostTask: HostCloudAnchorFuture? = null

    /**
     * Hosts a Cloud Anchor based on the [Anchor], returning the underlying ARCore
     * [HostCloudAnchorFuture] so callers can cancel the pending request.
     *
     * **Persistence — `ttlDays`.** ARCore lets the host specify how many days the cloud anchor
     * lives on Google's ARCore API before being auto-deleted. The valid range is **1..365** days
     * (inclusive). For short-lived collaborative sessions, `ttlDays = 1` (the default and ARCore's
     * legacy `hostCloudAnchor` behaviour) is enough. For "leave behind" multi-day or multi-session
     * experiences (e.g. a treasure hunt that persists for a week), pass a larger value. Pair this
     * with a [io.github.sceneview.ar.CloudAnchorRegistry] to remember which IDs your app has
     * hosted/resolved across launches.
     *
     * **Privacy disclosure (required).** ARCore Cloud Anchors upload feature points from the
     * user's surroundings to Google. Apps using Cloud Anchors must surface a clear in-app
     * disclosure to the user explaining this data collection and link to Google's ARCore data
     * policy: https://developers.google.com/ar/data-privacy. See the ARCloudAnchorDemo sample
     * for a reference disclosure UI.
     *
     * Cloud anchor hosting hits Google Cloud and accrues a billing event whether the
     * caller is still listening or not. **Always cancel the returned future when the
     * UI scope is leaving** — e.g. from `DisposableEffect.onDispose { future.cancel() }`
     * in a Compose host. The internal `hostTask` field is still cleared on cancel and
     * on completion so the node lifecycle (`destroy()` → `cancelHost()`) keeps working
     * without callers having to.
     *
     * @param ttlDays    The lifetime of the anchor in days. Must be in `1..365` (inclusive);
     *                   any value outside this range will throw [IllegalArgumentException].
     *                   Defaults to `1` for parity with the legacy ARCore default.
     * @param onCompleted Called when the task completes successfully or with an error
     *                    — **not invoked if the future is cancelled** (matches ARCore).
     *
     * @return the [HostCloudAnchorFuture]; cancel via [Future.cancel] to stop observing
     *         and free the network request.
     *
     * @throws IllegalArgumentException if [ttlDays] is not in `1..365`.
     * @see [Session.hostCloudAnchorAsync] for more details.
     */
    fun host(
        session: Session,
        ttlDays: Int = 1,
        onCompleted: ((cloudAnchorId: String?, state: CloudAnchorState) -> Unit)? = null
    ): HostCloudAnchorFuture {
        require(ttlDays in TTL_DAYS_RANGE) {
            "ttlDays must be in $TTL_DAYS_RANGE (ARCore Cloud Anchor persistence limit), was $ttlDays."
        }
        cancelHost()
        val future = session.hostCloudAnchorAsync(anchor, ttlDays) { cloudAnchorId, state ->
            onHosted(cloudAnchorId, state)
            onCompleted?.invoke(cloudAnchorId, state)
            hostTask = null
        }
        hostTask = future
        return future
    }

    open fun onHosted(cloudAnchorId: String?, state: CloudAnchorState) {
        this.hostState = state
        this.cloudAnchorId = cloudAnchorId
        onHosted?.invoke(cloudAnchorId, state)
    }

    fun cancelHost() {
        hostTask?.cancel()
        hostState = CloudAnchorState.NONE
        hostTask = null
    }

    override fun destroy() {
        cancelHost()

        super.destroy()
    }

    companion object {
        /**
         * Valid range (inclusive) for [host]'s `ttlDays` parameter, mirroring the
         * ARCore Cloud Anchor persistence limit of 1..365 days.
         */
        val TTL_DAYS_RANGE: IntRange = 1..365

        /**
         * Resolves a Cloud Anchor, returning the underlying ARCore
         * [com.google.ar.core.ResolveCloudAnchorFuture] so callers can cancel the
         * pending request.
         *
         * Cloud anchor resolution hits Google Cloud and accrues a billing event
         * whether the caller is still listening or not. **Always cancel the returned
         * future when the UI scope is leaving** — e.g. from
         * `DisposableEffect.onDispose { future.cancel() }` in a Compose host.
         *
         * The [anchor] is replaced with a new anchor returned by [Session.resolveCloudAnchorAsync].
         *
         * @param cloudAnchorId The Cloud Anchor ID of the Cloud Anchor.
         * @param onCompleted   Called when the task completes successfully or with an
         *                      error — **not invoked if the future is cancelled**
         *                      (matches ARCore).
         *
         * @return the [com.google.ar.core.ResolveCloudAnchorFuture]; cancel via
         *         [com.google.ar.core.Future.cancel] to stop observing.
         *
         * @see [Session.resolveCloudAnchorAsync] for more details.
         */
        fun resolve(
            engine: Engine,
            session: Session,
            cloudAnchorId: String,
            onCompleted: (state: CloudAnchorState, node: CloudAnchorNode?) -> Unit
        ): com.google.ar.core.ResolveCloudAnchorFuture =
            session.resolveCloudAnchorAsync(cloudAnchorId) { anchor, state ->
                onCompleted(state, anchor?.takeIf { !state.isError }?.let {
                    CloudAnchorNode(engine, it, cloudAnchorId)
                })
            }
    }
}