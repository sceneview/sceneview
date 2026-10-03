package io.github.sceneview.ar

/**
 * Who runs the device camera among the AR sessions of one process.
 *
 * A device has one camera for ARCore, so two sessions cannot be resumed at once. Two
 * [ARSceneView]s are nevertheless on screen together whenever one AR screen animates into
 * another (a navigation transition, a predictive back preview): the outgoing one is still
 * composed and still resumed while the incoming one starts.
 *
 * The rule is "the last one to resume runs": [claim] returns the session that has to pause
 * first, and once the running one is destroyed [holder] is the session that takes over again.
 * A session that pauses simply leaves the queue, with no hand back: an activity pausing two
 * sessions in a row must not reopen the camera between them.
 *
 * Main thread only, like every ARCore session call it arbitrates. Kept generic and free of
 * ARCore types so the ordering is a plain JVM test.
 */
internal class CameraHandover<T : Any> {

    /** Sessions that want the camera, oldest first. The last one is the one running. */
    private val claims = ArrayList<T>()

    /** The session entitled to run, or `null` when none wants the camera. */
    val holder: T? get() = claims.lastOrNull()

    /**
     * [owner] takes the camera.
     *
     * @return the session that was running and must be paused before [owner] resumes, or
     * `null` when the camera was free or [owner] already had it.
     */
    fun claim(owner: T): T? {
        val previous = holder
        remove(owner)
        claims.add(owner)
        return previous?.takeIf { it !== owner }
    }

    /** [owner] no longer wants the camera. Safe to call for a session that never claimed. */
    fun release(owner: T) {
        remove(owner)
    }

    // By identity: two sessions are never the same claim, whatever their `equals` says.
    private fun remove(owner: T) {
        val index = claims.indexOfFirst { it === owner }
        if (index >= 0) claims.removeAt(index)
    }
}
