package io.github.sceneview.web

/**
 * Tracks the most recently loaded glTF [FilamentAsset] per *logical model* and
 * guarantees leak-free replacement and teardown.
 *
 * The web `SceneView` creates a Filament `FilamentAsset` in
 * [SceneView.loadModel]. Before issue #1597 every load was simply appended to
 * the `models` list and a prior asset for the *same logical model* (same URL)
 * was never destroyed, so calling `loadModel` repeatedly on the same URL —
 * e.g. a demo that hot-swaps the displayed model — orphaned every previous
 * `FilamentAsset` on the GPU until `destroy()` finally ran.
 *
 * This mirrors the [EnvironmentResourceTracker] state machine that fixed the
 * identical IBL/skybox replace-leak (issue #1496): "destroy the old handle
 * before adopting a new one, destroy whatever is held on teardown". The only
 * difference is that several distinct logical models can be live at once, so a
 * resource is tracked per key (the model URL) rather than as a single slot.
 *
 * Isolating the bookkeeping from the Filament.js bindings keeps it directly
 * unit-testable without a WebGL context — handles can be plain values and the
 * `destroyer` simply records every release.
 *
 * @param T the handle type (`FilamentAsset`, or a test stand-in)
 * @param destroyer invoked with a handle that must be released on the GPU
 */
internal class AssetResourceTracker<T>(
    private val destroyer: (T) -> Unit,
) {

    private val byKey = mutableMapOf<String, T>()

    /** The handle currently held for [key], or `null` if none. */
    fun current(key: String): T? = byKey[key]

    /**
     * Adopt [handle] as the current resource for [key], destroying the
     * previously held handle for the same key (if any) first. This is the
     * leak-free replacement path for a 2nd `loadModel` of the same URL.
     */
    fun replaceWith(key: String, handle: T) {
        byKey[key]?.let(destroyer)
        byKey[key] = handle
    }

    /**
     * Destroy every tracked handle and clear all slots. Idempotent — a second
     * call is a no-op. Called from [SceneView.destroy].
     */
    fun release() {
        byKey.values.forEach(destroyer)
        byKey.clear()
    }
}
