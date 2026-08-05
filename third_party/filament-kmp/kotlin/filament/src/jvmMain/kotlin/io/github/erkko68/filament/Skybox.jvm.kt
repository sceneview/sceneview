package io.github.erkko68.filament

import io.github.erkko68.filament.ffm.FilamentC
import java.lang.foreign.MemorySegment

actual class Skybox(internal var nativeHandle: MemorySegment?) {
    actual class Builder actual constructor() {
        private val nativeBuilder = FilamentC.FilaSkyboxBuilder_create()

        actual fun environment(cubemap: Texture): Builder {
            FilamentC.FilaSkyboxBuilder_environment(nativeBuilder, cubemap.nativeHandle)
            return this
        }

        actual fun showSun(show: Boolean): Builder {
            FilamentC.FilaSkyboxBuilder_showSun(nativeBuilder, show)
            return this
        }

        actual fun intensity(envIntensity: Float): Builder {
            FilamentC.FilaSkyboxBuilder_intensity(nativeBuilder, envIntensity)
            return this
        }

        actual fun color(r: Float, g: Float, b: Float, a: Float): Builder {
            FilamentC.FilaSkyboxBuilder_color(nativeBuilder, r, g, b, a)
            return this
        }

        actual fun priority(priority: Int): Builder {
            FilamentC.FilaSkyboxBuilder_priority(nativeBuilder, priority.toByte())
            return this
        }

        actual fun build(engine: Engine): Skybox {
            val handle = FilamentC.FilaSkyboxBuilder_build(nativeBuilder, engine.nativeHandle)
            FilamentC.FilaSkyboxBuilder_destroy(nativeBuilder)
            return Skybox(handle)
        }
    }

    actual fun setColor(r: Float, g: Float, b: Float, a: Float) {
        FilamentC.FilaSkybox_setColor(nativeHandle, r, g, b, a)
    }
    actual val intensity: Float get() = FilamentC.FilaSkybox_getIntensity(nativeHandle)
    actual val layerMask: Int get() = FilamentC.FilaSkybox_getLayerMask(nativeHandle).toInt()
    actual val texture: Texture? get() = FilamentC.FilaSkybox_getTexture(nativeHandle).let { if (it.isNullPtr()) null else Texture(it) }
    actual fun setLayerMask(select: Int, value: Int) = FilamentC.FilaSkybox_setLayerMask(nativeHandle, select.toByte(), value.toByte())
}
