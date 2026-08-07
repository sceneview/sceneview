package io.github.sceneview.compose

/**
 * Something a [SceneViewer] could not load.
 *
 * A failed load has no pixels of its own: the viewport keeps showing the environment,
 * which is indistinguishable from a load still in progress. This type is how that
 * failure becomes observable — pass `onError` to [SceneViewer] and render whatever your
 * app wants (a retry button, a placeholder, a toast).
 *
 * Handling it is optional. With no `onError` the failure is still written to the
 * platform log under the `SceneViewer` tag, exactly as before; nothing throws, and the
 * viewport keeps rendering.
 *
 * @property message what failed, in English, naming the source — e.g.
 *   `loading asset 'models/helmet.glb'`. Intended for logs and bug reports, not for
 *   display to end users: it is not localised and its wording is not part of the API
 *   contract.
 * @property cause the underlying platform exception when there was one. `null` when the
 *   platform reported a failure without one — a loader returning "no model" rather than
 *   throwing, or an error forwarded from a non-Kotlin renderer.
 */
public class SceneViewerError internal constructor(
    public val message: String,
    public val cause: Throwable? = null,
) {
    override fun toString(): String =
        if (cause != null) "SceneViewerError($message, cause=$cause)" else "SceneViewerError($message)"
}
