package io.github.sceneview.demo.demos.internal

import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.google.android.filament.Colors
import com.google.android.filament.MaterialInstance
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.loaders.MaterialLoader

/** The compiled `ar_mesh_tint.mat` (see its header for the blend and why). */
internal const val AR_MESH_TINT_ASSET = "materials/ar_mesh_tint.filamat"

/**
 * A mesh overlay drawn **into** the AR camera image (#4623): Streetscape terrain and buildings,
 * plane markers. The material tints the camera feed instead of covering it with a translucent
 * colour, so [color] stays saturated over a white wall and over a dark corner alike, and the
 * room still reads through it.
 *
 * Use the `SceneViewTokens.ArOverlay.mesh*` colours: `meshGround` for the ground, `meshStructure`
 * for what stands on it. [strength] defaults to `ar-mesh-fill-alpha`.
 *
 * Creates a Filament material, a JNI call: composition runs on the main thread, which is why
 * this is a composable and not something to call from a coroutine.
 */
@Composable
fun rememberArMeshTintInstance(
    materialLoader: MaterialLoader,
    color: Color,
    strength: Float = SceneViewTokens.ArOverlay.MESH_FILL_ALPHA,
): MaterialInstance = remember(materialLoader, color, strength) {
    createArMeshTintInstance(materialLoader, color, strength)
}

/** [rememberArMeshTintInstance] outside composition. Main thread only. */
@MainThread
fun createArMeshTintInstance(
    materialLoader: MaterialLoader,
    color: Color,
    strength: Float = SceneViewTokens.ArOverlay.MESH_FILL_ALPHA,
): MaterialInstance =
    materialLoader.createInstance(materialLoader.createMaterial(AR_MESH_TINT_ASSET)).apply {
        // sRGB in, Filament linearises: the token is authored as the sRGB hex DESIGN.md lists.
        setParameter("color", Colors.RgbType.SRGB, color.red, color.green, color.blue)
        setParameter("strength", strength.coerceIn(0f, 1f))
    }
