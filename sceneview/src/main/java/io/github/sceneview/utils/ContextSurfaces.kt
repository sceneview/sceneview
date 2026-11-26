package io.github.sceneview.utils

/**
 * The surface each live context was made current on, so that the code that destroys a context it
 * only holds as a context can destroy that surface too (#5202). Thread-safe: contexts are adopted
 * and released on the thread that creates or destroys an engine, not necessarily the same one.
 *
 * EGL-free for JVM tests.
 */
internal class ContextSurfaces<C : Any, S : Any> {
    private val surfaces = HashMap<C, S>()

    val size: Int get() = synchronized(surfaces) { surfaces.size }

    /** Records [surface] as [context]'s. Adopting a context again replaces its surface. */
    fun adopt(context: C, surface: S) {
        synchronized(surfaces) { surfaces[context] = surface }
    }

    /** Looks up the pbuffer without transferring ownership, for a shared context's next user. */
    fun surface(context: C): S? = synchronized(surfaces) { surfaces[context] }

    /** [context]'s surface, forgotten, or `null` when it was not adopted or already released. */
    fun release(context: C): S? = synchronized(surfaces) { surfaces.remove(context) }
}
