package io.github.sceneview.compose

import androidx.compose.runtime.Immutable
import dev.romainguy.kotlin.math.Float3

/**
 * The scene's key light and ambient level.
 *
 * Filament and RealityKit do not share a lighting model, so these values map
 * **approximately** across platforms rather than exactly. A scene tuned on Android will
 * read slightly differently on iOS. That is a property of the façade, not a bug to
 * report — reach for the platform-native API when you need exact control.
 *
 * @property direction the direction the key light travels, in world space. The default
 *   points down and slightly forward, the usual three-quarter key.
 * @property intensity key light intensity, in lux. Roughly: 100 000 for direct sunlight,
 *   10 000 for an overcast sky, 1 000 for interior lighting.
 * @property ambientIntensity multiplier on the image-based light from [EnvironmentSource].
 *   `1.0` uses the environment as authored; `0.0` removes ambient light entirely and
 *   leaves only the key light.
 * @property castShadows whether the key light casts shadows. Shadow quality and cost
 *   differ per renderer.
 */
@Immutable
public data class Lighting(
    val direction: Float3 = Float3(0.3f, -1f, -0.5f),
    val intensity: Float = 100_000f,
    val ambientIntensity: Float = 1f,
    val castShadows: Boolean = true,
)

/**
 * The scene's background and image-based lighting.
 *
 * Both come from the same source, as they do in every physically based renderer: the
 * environment you see behind the model is the environment lighting it.
 */
public sealed interface EnvironmentSource {

    /**
     * The renderer's built-in neutral environment.
     *
     * Chosen per platform to light a model sensibly with no setup. It is *not* identical
     * across platforms — if you need a reproducible look, ship an [Hdr] and use it
     * everywhere.
     */
    public data object Default : EnvironmentSource

    /**
     * A flat background colour with no image-based lighting.
     *
     * Components are linear, in `0..1`. With this source a model is lit by the key light
     * alone, so [Lighting.ambientIntensity] has no effect.
     *
     * @property alpha `0f` makes the viewport transparent, letting Compose content behind
     *   it show through. Transparency has a real cost on some platforms — leave it at
     *   `1f` unless you need it.
     */
    public data class Color(
        val red: Float,
        val green: Float,
        val blue: Float,
        val alpha: Float = 1f,
    ) : EnvironmentSource

    /**
     * An HDR environment map bundled with the application, used as both skybox and
     * image-based light.
     *
     * The path is resolved the same way as [ModelSource.Asset]. Equirectangular `.hdr`
     * files are accepted on all platforms; pre-filtered formats are not portable.
     *
     * @property showSkybox when `false`, the environment lights the model but is not
     *   drawn — useful for compositing a model over your own UI background.
     */
    public data class Hdr(
        val path: String,
        val showSkybox: Boolean = true,
    ) : EnvironmentSource
}
