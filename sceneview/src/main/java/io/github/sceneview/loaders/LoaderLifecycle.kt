package io.github.sceneview.loaders

/**
 * Whether a [ModelLoader] may still call into gltfio, kept Filament-free so the rules are pinned in
 * a JVM test.
 *
 * A Filament precondition that fails is a C++ `PreconditionPanic`, and on Android that is an
 * `abort()` of the whole process: nothing on the Java side can catch it. So each rule here is
 * checked *before* the JNI call it protects:
 *
 * - once [ModelLoader.destroy] was asked for, no new model or instance is created, and a second
 *   destroy does nothing;
 * - once the loader's gltfio objects are freed, nothing touches them any more — not even the
 *   per-frame [ModelLoader.updateLoad], which the render loop can still run for a frame after a
 *   view is disposed. gltfio's Java `ResourceLoader.destroy()` keeps its native handle, so that call
 *   would read freed memory;
 * - nothing calls into an [engine][isEngineAlive] that was already destroyed.
 */
internal class LoaderLifecycle(private val isEngineAlive: () -> Boolean) {

    @Volatile
    var isDestroyRequested = false
        private set

    @Volatile
    var isReleased = false
        private set

    /** Whether gltfio may still be asked for anything: its objects exist and so does the engine. */
    val canTouchNatives: Boolean get() = !isReleased && isEngineAlive()

    /** Whether a new model or instance may be created. */
    val canCreate: Boolean get() = !isDestroyRequested && canTouchNatives

    /** `true` the first time only: the caller tears down, every later call does nothing. */
    fun requestDestroy(): Boolean {
        if (isDestroyRequested) return false
        isDestroyRequested = true
        return true
    }

    /**
     * Marks the gltfio objects as freed. Returns whether the caller should free them: not when the
     * engine is already gone, since freeing the material provider's materials calls into it. The
     * engine's own destroy freed what it owned; the loaders themselves are then left to leak.
     */
    fun release(): Boolean {
        val engineAlive = isEngineAlive()
        isReleased = true
        return engineAlive
    }

    /** Throws an [IllegalStateException] naming [what] when no model may be created. */
    fun checkCanCreate(what: String) {
        check(!isDestroyRequested) { "Cannot $what: this ModelLoader was destroyed" }
        check(isEngineAlive()) { "Cannot $what: the Engine of this ModelLoader was destroyed" }
        check(!isReleased) { "Cannot $what: this ModelLoader was destroyed" }
    }
}
