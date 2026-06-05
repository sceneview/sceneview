package io.github.sceneview.components

import androidx.annotation.IntRange
import com.google.android.filament.LightManager
import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.EntityInstance
import io.github.sceneview.managers.getDirection
import io.github.sceneview.managers.getPosition
import io.github.sceneview.managers.getQuaternion
import io.github.sceneview.managers.setDirection
import io.github.sceneview.managers.setPosition
import io.github.sceneview.managers.setQuaternion
import io.github.sceneview.math.Color
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.toColor

/**
 * ECS component for Filament lights (directional, point, spot, sun).
 *
 * Provides typed accessors to [LightManager] properties such as intensity, colour,
 * direction, falloff, and shadow configuration.
 */
interface LightComponent : Component {

    /** The Filament [LightManager] that owns all light instances. */
    val lightManager get() = engine.lightManager

    /**
     * The light-manager instance handle for this entity.
     *
     * **Caching contract (#2328 / #2402 MED-2).** This interface default resolves the handle with a
     * `getInstance` JNI thunk on *every* read, and every [LightComponent] getter/setter funnels
     * through it — reactive light setups (`rememberMainLightNode`'s `SideEffect { node.apply(…) }`)
     * re-apply on each recomposition, so it is read at recomposition frequency. A Kotlin interface
     * property cannot hold a backing field, so the cache must live in the implementer:
     * [io.github.sceneview.node.LightNode] overrides this with a lazy-once cached handle (the handle
     * is stable for the entity's lifetime; a `0` "not built yet" result is never frozen).
     * **Any implementer that reads light properties frequently MUST cache this the same way;** the
     * uncached default is only safe for one-shot or test implementers.
     */
    val lightInstance: EntityInstance get() = lightManager.getInstance(entity)

    /** The type of this light (directional, point, spot, or sun). */
    val type: LightManager.Type get() = lightManager.getType(lightInstance)

    /**
     * Enables or disables a light channel.
     * Light channel 0 is enabled by default.
     *
     * @param channel Light channel to set
     * @param enable true to enable, false to disable
     *
     * @see LightManager.Builder.lightChannel
     */
    fun setLightChannel(@IntRange(from = 0, to = 7) channel: Int, enable: Boolean) =
        lightManager.setLightChannel(lightInstance, channel, enable)

    /**
     * Returns whether a light channel is enabled on a specified renderable.
     *
     * @param channel Light channel to query
     * @return true if the light channel is enabled, false otherwise
     */
    fun getLightChannel(@IntRange(from = 0, to = 7) channel: Int): Boolean =
        lightManager.getLightChannel(lightInstance, channel)

    /**
     * The light's position in world space
     *
     * @see LightManager.getDirection
     * @see LightManager.setDirection
     */
    var lightPosition: Position
        get() = lightManager.getPosition(lightInstance)
        set(value) {
            lightManager.setPosition(lightInstance, value)
        }

    /**
     * The light's rotation in world space
     *
     * @see LightManager.getDirection
     * @see LightManager.setDirection
     */
    var lightQuaternion: Quaternion
        get() = lightManager.getQuaternion(lightInstance)
        set(value) {
            lightManager.setQuaternion(lightInstance, value)
        }

    /**
     * The light's direction in world space
     *
     * @see LightManager.getDirection
     * @see LightManager.setDirection
     */
    var lightDirection: Direction
        get() = lightManager.getDirection(lightInstance)
        set(value) {
            lightManager.setDirection(lightInstance, value)
        }

    /**
     * Dynamically updates the light's hue as linear sRGB
     *
     * @see LightManager.getColor
     * @see LightManager.setColor
     */
    var color: Color
        get() = FloatArray(3).apply {
            lightManager.getColor(lightInstance, this)
        }.toColor()
        set(value) = lightManager.setColor(lightInstance, value.r, value.g, value.b)

    /**
     * Dynamically updates the light's intensity
     *
     * The intensity can be negative.
     *
     * This parameter depends on the [LightManager.Type], for directional lights, it specifies the
     * illuminance in <i>lux</i> (or <i>lumen/m^2</i>).
     * For point lights and spot lights, it specifies the luminous power in <i>lumen</i>.
     * For example, the sun's illuminance is about 100,000 lux.
     *
     * This method is equivalent to calling setIntensity for directional lights
     * ([LightManager.Type.DIRECTIONAL] or [LightManager.Type.SUN]).
     *
     * @see LightManager.getIntensity
     * @see LightManager.setIntensity
     */
    var intensity: Float
        get() = lightManager.getIntensity(lightInstance)
        set(value) = lightManager.setIntensity(lightInstance, value)

    /**
     * Dynamically updates the light's intensity in candela. The intensity can be negative.
     *
     * This method is equivalent to calling setIntensity for directional lights
     * ([LightManager.Type.DIRECTIONAL] or [LightManager.Type.SUN]).
     *
     * @param intensity Luminous intensity in *candela*.
     *
     * @see LightManager.Builder.intensityCandela
     */
    fun setIntensityCandela(intensity: Float) =
        lightManager.setIntensityCandela(lightInstance, intensity)

    /**
     * Dynamically updates the light's intensity. The intensity can be negative.
     *
     * Lightbulb type  | Efficiency
     * -----------------+------------
     * Incandescent |  2.2%
     * Halogen  |  7.0%
     * LED  |  8.7%
     * Fluorescent  | 10.7%
     *
     * This call is equivalent to:
     * ```
     * Builder.intensity(efficiency * 683 * watts);
     * ```
     *
     * @param watts Energy consumed by a lightbulb. It is related to the energy produced and
     * ultimately the brightness by the efficiency parameter. This value is often available on the
     * packaging of commercial lightbulbs.
     * @param efficiency Efficiency in percent. This depends on the type of lightbulb used.
     */
    fun setIntensity(watts: Float, efficiency: Float) =
        lightManager.setIntensity(lightInstance, watts, efficiency)

    /**
     * The falloff distance for point lights and spot lights.
     *
     * Falloff distance in world units. Default is 1 meter.
     *
     * @see LightManager.getFalloff
     * @see LightManager.setFalloff
     */
    var falloff: Float
        get() = lightManager.getFalloff(lightInstance)
        set(value) = lightManager.setFalloff(lightInstance, value)

    /**
     * Dynamically updates a spot light's cone as angles
     *
     * @param inner inner cone angle in *radians* between 0 and pi/2
     * @param outer outer cone angle in *radians* between inner and pi/2
     *
     * @see LightManager.Builder.spotLightCone
     */
    fun setSpotLightCone(inner: Float, outer: Float) =
        lightManager.setSpotLightCone(lightInstance, inner, outer)

    /**
     * Dynamically updates the angular radius of a Type.SUN light.
     *
     * The Sun as seen from Earth has an angular size of 0.526° to 0.545°
     *
     * @see LightManager.getSunAngularRadius
     * @see LightManager.setSunAngularRadius
     */
    var sunAngularRadius: Float
        get() = lightManager.getSunAngularRadius(lightInstance)
        set(value) = lightManager.setSunAngularRadius(lightInstance, value)

    /**
     * Dynamically updates the halo radius of a Type.SUN light
     *
     * The radius of the halo is defined as a multiplier of the sun angular radius.
     * Radius multiplier. Default is 10.0.
     *
     * @see LightManager.getSunHaloSize
     * @see LightManager.setSunHaloSize
     */
    var sunHaloSize: Float
        get() = lightManager.getSunHaloSize(lightInstance)
        set(value) = lightManager.setSunHaloSize(lightInstance, value)

    /**
     * Dynamically updates the halo falloff of a Type.SUN light.
     *
     * The falloff is a dimensionless number used as an exponent.
     *
     * Halo falloff. Default is 80.0.
     *
     * @see LightManager.getSunHaloFalloff
     * @see LightManager.setSunHaloFalloff
     */
    var sunHaloFalloff: Float
        get() = lightManager.getSunHaloFalloff(lightInstance)
        set(value) = lightManager.setSunHaloFalloff(lightInstance, value)

    /**
     * Whether this Light casts shadows (disabled by default)
     *
     * @see LightManager.isShadowCaster
     * @see LightManager.setShadowCaster
     */
    var isShadowCaster: Boolean
        get() = lightManager.isShadowCaster(lightInstance)
        set(value) = lightManager.setShadowCaster(lightInstance, value)

    /** The outer cone angle of this spot light in radians. */
    val outerConeAngle: Float get() = lightManager.getOuterConeAngle(lightInstance)

    /** The inner cone angle of this spot light in radians (full-intensity region). */
    val innerConeAngle: Float get() = lightManager.getInnerConeAngle(lightInstance)
}