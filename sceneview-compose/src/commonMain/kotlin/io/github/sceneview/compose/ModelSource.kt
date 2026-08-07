package io.github.sceneview.compose

import dev.romainguy.kotlin.math.Float3

/**
 * Where a model's bytes come from.
 *
 * **Format support is not uniform, and this is the one place it bites.** Android and
 * desktop read glTF and GLB and cannot read `usdz`; iOS is the mirror image — RealityKit
 * reads `usdz` and `reality` and cannot read glTF at all. There is no format every
 * platform accepts, so a cross-platform app ships both and picks per platform, or
 * converts with `tools/convert-usdz.sh`.
 *
 * **A failed load is not visible on screen.** Whatever the cause — an unsupported
 * format, a malformed file, an HTTP error, a size-capped download — the viewport keeps
 * showing the environment, which looks exactly like a load still in progress. The
 * failure is reported to logcat under the `SceneViewer` tag; there is no `onError`
 * callback and no failed state in this API yet. Check the log before concluding that a
 * model is merely slow.
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
     * has nothing to resolve those against and will load incomplete. Use [Asset] for
     * multi-file models — it is the only source that resolves sibling resources.
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
     *
     * Must be **self-contained**, exactly like [Bytes]: this fetches one file and hands
     * the bytes to the loader, so a `.gltf` referencing an external `.bin` or texture
     * loads incomplete — and silently, since nothing surfaces the missing resource. Use
     * a GLB.
     *
     * Only `http` and `https` are accepted, and that is checked **here** rather than in
     * each platform's downloader: this type's contract says http/https, so an app that
     * forwards a user-supplied string must get the same refusal on every platform. A
     * `file://` slipped into a deep link would otherwise turn into a local-file read on
     * whichever platform happened not to re-check.
     *
     * @throws IllegalArgumentException if [url] is not an absolute http/https URL.
     */
    public data class Url(val url: String) : ModelSource {
        init {
            require(HTTP_URL.matches(url)) {
                "ModelSource.Url only accepts absolute http/https URLs, got '$url'"
            }
        }
    }
}

// Scheme + authority only; the rest of the URL is the platform downloader's business.
// Deliberately anchored, so a string merely *containing* "http://" does not pass.
private val HTTP_URL = Regex("^https?://[^/?#]+.*$", RegexOption.IGNORE_CASE)

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
