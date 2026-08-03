package io.github.sceneview.compose

import dev.romainguy.kotlin.math.Float3

/**
 * Where a model's bytes come from.
 *
 * Every platform accepts glTF and GLB. `usdz` is Apple-only: passing one on Android or
 * desktop fails to load rather than silently rendering nothing, so the failure is
 * visible during development instead of at review time.
 */
public sealed interface ModelSource {

    /**
     * A file bundled with the application.
     *
     * The path is resolved per platform: Android assets, the iOS main bundle, and the
     * JVM classpath on desktop. Use the same relative path on all three and place the
     * file in each platform's asset location — or use a Compose Multiplatform resource
     * and pass the bytes through [Bytes] instead.
     */
    public data class Asset(val path: String) : ModelSource

    /**
     * Model bytes already in memory — a downloaded file, or a CMP resource.
     *
     * Must be **self-contained**: a GLB, or a glTF with embedded buffers and textures.
     * Bytes carry no location, so a `.gltf` referencing an external `.bin` or texture
     * has nothing to resolve those against and will load incomplete. Use [Asset] or
     * [Url] for multi-file models — both resolve sibling resources.
     */
    public data class Bytes(val bytes: ByteArray) : ModelSource {

        override fun equals(other: Any?): Boolean =
            this === other || (other is Bytes && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /**
     * A model fetched over the network.
     *
     * The download happens off the main thread; the viewport renders the environment
     * until it completes. No caching is performed — wrap it yourself if you need one.
     */
    public data class Url(val url: String) : ModelSource
}

/**
 * A tap that landed on the model.
 *
 * @property position the hit point, in world space.
 * @property distance the distance from the camera to [position], in scene units.
 */
public data class ModelHit(
    val position: Float3,
    val distance: Float,
)
