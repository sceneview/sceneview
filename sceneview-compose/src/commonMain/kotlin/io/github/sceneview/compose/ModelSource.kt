package io.github.sceneview.compose

import dev.romainguy.kotlin.math.Float3

/**
 * Where a model's bytes come from.
 *
 * Every platform accepts glTF and GLB. `usdz` is Apple-only: passing one on Android or
 * desktop fails to load.
 *
 * **A failed load is not visible on screen.** Whatever the cause — an unsupported
 * format, a malformed file, an HTTP error, a size-capped download — the viewport keeps
 * showing the environment, which looks exactly like a load still in progress. Pass
 * `onError` to [SceneViewer] to observe the failure and render your own state for it;
 * see [SceneViewerError]. Handling it is optional, and with no handler the failure is
 * still written to the platform log under the `SceneViewer` tag — so check the log
 * before concluding that a model is merely slow.
 */
public sealed interface ModelSource {

    /**
     * A file bundled with the application.
     *
     * The path is resolved per platform: Android assets, the iOS main bundle, and the
     * JVM classpath on desktop. Use the same relative path on all three and place the
     * file in each platform's asset location — or use a Compose Multiplatform resource
     * and pass the bytes through [Bytes] instead.
     *
     * The path must be **relative and scheme-less**, and that is checked here for the
     * same reason [Url]'s http/https rule is: a bundled-file type whose string can name
     * something other than a bundled file is a hole an app opens without noticing. On
     * Android the loader dispatches on URI scheme, so `content://`, `file://` and
     * `https://` each resolve — reading a private ContentProvider under the app's own
     * uid, or an arbitrary local file, and bypassing the timeouts and the 64 MB cap that
     * [Url] applies. An app that resolves a deep link or a server-supplied id into a
     * path now gets the same refusal on every platform.
     *
     * @throws IllegalArgumentException if [path] carries a URI scheme.
     */
    public data class Asset(val path: String) : ModelSource {
        init {
            require(!URI_SCHEME.containsMatchIn(path)) {
                "ModelSource.Asset takes a path relative to the app's bundled assets, " +
                    "not a URI — got '$path'. Use ModelSource.Url for http/https, or read " +
                    "the bytes yourself and pass ModelSource.Bytes."
            }
        }
    }

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

// RFC 3986 §3.1 scheme, anchored at the start. Anchored on purpose: a Windows-style
// "C:/models" is not a URI scheme by this definition (a scheme needs a letter first,
// which `C` satisfies — so `C:` IS rejected, and that is deliberate too: an absolute
// path is not a bundled asset either). A bare "models/car.glb" has no colon and passes.
private val URI_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*:")

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
