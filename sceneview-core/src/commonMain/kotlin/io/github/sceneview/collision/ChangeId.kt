// Derived from Google Sceneform (Apache-2.0), modified by the SceneView contributors.
package io.github.sceneview.collision

/**
 * Used to identify when the state of an object has changed by incrementing an integer id. Other
 * classes can determine when this object has changed by polling to see if the id has changed.
 *
 * This is useful as an alternative to an event listener subscription model when there is no safe
 * point in the lifecycle of an object to unsubscribe from the event listeners. Unlike event
 * listeners, this cannot cause memory leaks.
 */
class ChangeId {
    companion object {
        /** The id value that represents "never changed yet". */
        const val EMPTY_ID = 0
    }

    private var id = EMPTY_ID

    /** Returns the current change id. Store it and later pass it to [checkChanged] to detect mutations. */
    fun get(): Int = id

    /** Returns `true` if no change has been recorded yet (the id is still [EMPTY_ID]). */
    fun isEmpty(): Boolean = id == EMPTY_ID

    /**
     * Returns `true` if the object has changed since the snapshot [id] was taken.
     *
     * @param id A previously captured value from [get].
     * @return `false` if no change has ever occurred, otherwise whether the id differs.
     */
    fun checkChanged(id: Int): Boolean = this.id != id && !isEmpty()

    /** Records a change by advancing the id. Skips [EMPTY_ID] on wrap-around. */
    fun update() {
        id++

        // Skip EMPTY_ID if the id has cycled all the way around.
        if (id == EMPTY_ID) {
            id++
        }
    }
}
