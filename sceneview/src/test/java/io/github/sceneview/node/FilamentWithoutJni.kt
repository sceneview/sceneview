package io.github.sceneview.node

import com.google.android.filament.Engine
import com.google.android.filament.gltfio.Animator
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.FilamentInstance
import io.github.sceneview.model.ModelInstance

/**
 * Real Filament objects built without their native side, for Robolectric tests that instrument
 * `com.google.android.filament` (every native method then returns zero, `false` or `null`).
 */
internal object FilamentWithoutJni {

    fun engine(): Engine {
        val constructor = Engine::class.java.getDeclaredConstructor(
            Long::class.javaPrimitiveType,
            Engine.Config::class.java
        )
        constructor.isAccessible = true
        return constructor.newInstance(1L, null)
    }

    /** A glTF instance of [engine]: no entity, no skin, animations of zero length. */
    fun modelInstance(engine: Engine): ModelInstance {
        val asset = FilamentAsset::class.java
            .getDeclaredConstructor(Engine::class.java, Long::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .newInstance(engine, 1L)
        val instance = FilamentInstance::class.java
            .getDeclaredConstructor(FilamentAsset::class.java, Long::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .newInstance(asset, 1L)
        // The instance would build its animator around the zero the native lookup returns here,
        // and an animator refuses to work on a null handle: hand it one that has a handle.
        val animator = Animator::class.java
            .getDeclaredConstructor(Long::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .newInstance(1L)
        FilamentInstance::class.java.getDeclaredField("mAnimator")
            .apply { isAccessible = true }
            .set(instance, animator)
        return instance
    }
}
