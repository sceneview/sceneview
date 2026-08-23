package io.github.sceneview.compose

/**
 * Whether the viewport should be drawn opaque for this environment.
 *
 * Shared by the Android (`isOpaque` on `SceneView`) and desktop
 * (`transparent` on `FilamentSceneView`) actuals — one decision, two renderers.
 */
internal val EnvironmentSource.isOpaque: Boolean
    get() = when (this) {
        is EnvironmentSource.Default -> true
        is EnvironmentSource.Color -> alpha >= 1f
        // With no skybox there is nothing to draw behind the model, so the surface must
        // be transparent for the Compose content underneath to show through — which is
        // exactly what `showSkybox = false` promises. An opaque surface would render
        // black instead.
        is EnvironmentSource.Hdr -> showSkybox
    }
