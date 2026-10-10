package io.github.sceneview.ar

import androidx.annotation.MainThread

/**
 * Who runs the device camera among the AR sessions of one process.
 *
 * A device has one camera for ARCore, so two sessions cannot be resumed at once. Two
 * [ARSceneView]s are nevertheless on screen together whenever one AR screen animates into
 * another (a navigation transition, a predictive back preview): the outgoing one is still
 * composed and still resumed while the incoming one starts.
 *
 * The rule is "the last one to resume runs": [claim] returns the session that has to pause
 * first, and once the running one is gone [holder] is the session that takes over again.
 *
 * The rule knows nothing about what is on screen, which gives two known limits:
 *
 *  - **Predictive back from one AR screen to another.** The screen previewed underneath is
 *    composed during the gesture and resumes last, so it takes the camera: the screen under the
 *    finger shows its last frame for the length of the gesture. A cancelled gesture gives the
 *    camera back.
 *  - **Several AR screens kept composed by the host** (two AR pages of a `ViewPager2`). The
 *    last one to register runs, whichever is visible. Bind each to its own page lifecycle.
 *
 * A claim is a strong reference, held from resume until pause or destroy. The queue is locked,
 * so a stray call from another thread cannot corrupt it, but the order only means something on
 * the main thread, where every ARCore session call it arbitrates is made. Kept generic and free
 * of ARCore types so the ordering is a plain JVM test.
 */
internal class CameraHandover<T : Any> {

    /** Sessions that want the camera, oldest first. The last one is the one running. */
    private val claims = ArrayList<T>()

    /** The session entitled to run, or `null` when none wants the camera. */
    val holder: T? get() = synchronized(claims) { claims.lastOrNull() }

    /**
     * [owner] takes the camera.
     *
     * @return the session that was running and must be paused before [owner] resumes, or
     * `null` when the camera was free or [owner] already had it.
     */
    fun claim(owner: T): T? = synchronized(claims) {
        val previous = claims.lastOrNull()
        remove(owner)
        claims.add(owner)
        previous?.takeIf { it !== owner }
    }

    /** [owner] no longer wants the camera. Safe to call for a session that never claimed. */
    fun release(owner: T) {
        synchronized(claims) { remove(owner) }
    }

    // By identity: two sessions are never the same claim, whatever their `equals` says.
    private fun remove(owner: T) {
        val index = claims.indexOfFirst { it === owner }
        if (index >= 0) claims.removeAt(index)
    }
}

/** What [CameraArbiter] needs to know and do about one AR session. */
internal interface CameraClient {

    /** `true` while the session is resumed: it is the one running the camera. */
    val isRunning: Boolean

    /** `true` when there is a session that can be resumed: created, and not closed. */
    val canRun: Boolean

    /** Resumes the session. Throws whatever `Session.resume()` throws. */
    fun start()

    /** Pauses the session. */
    fun stop()

    /** [start] threw [exception]: the session is not running. */
    fun onStartFailed(exception: Exception)
}

/**
 * Applies [CameraHandover] to sessions: who is paused, who is resumed, and what happens when a
 * resume fails. This is everything `ARCore` does about sharing the camera, kept apart from the
 * ARCore types so that it is a JVM test.
 *
 * @param postToMainThread runs a block on the main thread after the current dispatch.
 */
internal class CameraArbiter(private val postToMainThread: (() -> Unit) -> Unit) {

    private val handover = CameraHandover<CameraClient>()

    /**
     * [client] resumes: the session that was running pauses first.
     *
     * A resume that fails must not leave the screen it took the camera from frozen, nor escape
     * into the lifecycle observer that called it: the claim is dropped, the failure goes to
     * [CameraClient.onStartFailed], and the camera returns to the previous session.
     */
    @MainThread
    fun resume(client: CameraClient) {
        if (!client.canRun) return
        handover.claim(client)?.takeIf { it.isRunning }?.stop()
        if (!start(client)) {
            handover.release(client)
            handBack()
        }
    }

    /**
     * [client] pauses and gives up its turn.
     *
     * A session it had taken the camera from is resumed, but only after the current dispatch:
     * an activity pausing two sessions in a row must not reopen the camera between them. By the
     * time the posted block runs, the second one has left the queue too. What it serves is a
     * screen paused on its own by a narrower lifecycle while the activity, and a session under
     * it, stay resumed.
     */
    @MainThread
    fun pause(client: CameraClient) {
        handover.release(client)
        if (client.isRunning) client.stop()
        if (handover.holder != null) postToMainThread(::handBack)
    }

    /** [client] is closed by [close]; the camera goes back to the session entitled to it. */
    @MainThread
    fun destroy(client: CameraClient, close: () -> Unit) {
        handover.release(client)
        close()
        handBack()
    }

    /**
     * Resumes the session entitled to the camera when it is not running: a predictive back
     * gesture that previews an AR screen and is then cancelled leaves the first screen in place,
     * and its camera has to come back. A session that cannot take it is skipped.
     */
    private fun handBack() {
        val next = handover.holder ?: return
        if (next.isRunning) return
        if (!next.canRun || !start(next)) {
            handover.release(next)
            handBack()
        }
    }

    /** @return `false` when the resume threw; the failure went to the session's own host. */
    private fun start(client: CameraClient): Boolean = try {
        client.start()
        true
    } catch (e: Exception) {
        client.onStartFailed(e)
        false
    }
}
