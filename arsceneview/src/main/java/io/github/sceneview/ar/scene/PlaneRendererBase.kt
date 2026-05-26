package io.github.sceneview.ar.scene

import android.util.Size
import com.google.ar.core.Frame
import com.google.ar.core.Session

/**
 * Shared contract for SceneView's ARCore plane renderers.
 *
 * SceneView ships **two implementations** of detected-plane visualization, selectable via
 * [ARSceneView][io.github.sceneview.ar.ARSceneView]'s `planeRendererVersion` parameter:
 *
 *  * [PlaneRendererV2] — the **default** renderer as of this release. Detected planes ship
 *    as a depth-driven PBR mesh lit by ARCore's HDR estimate (environmental cubemap +
 *    ambient SH), with type-aware floor / ceiling / wall material identities and an 800 ms
 *    scan-in ring on first detection. See umbrella issue
 *    [#2203](https://github.com/sceneview/sceneview/issues/2203).
 *  * [PlaneRenderer] — the legacy ("V1") renderer. Draws each detected plane as a flat
 *    polygon textured with a procedural soft grid. Now `@Deprecated`; kept available for
 *    one release cycle so apps that subclass [PlaneRenderer] or override
 *    `plane_renderer.mat` have time to port their custom styling to the V2 material.
 *
 * ### Migration timeline
 *
 * | PR  | Change                                                                    |
 * |-----|---------------------------------------------------------------------------|
 * | #1  | V2 skeleton + opt-in flag. V2 is byte-equivalent to V1 at that point.     |
 * | #2  | Depth-driven tessellation + displacement on V2.                           |
 * | #3  | PBR + HDR reflections + animated scan-in on V2.                           |
 * | #4  | Type-aware shading (floor / ceiling / wall) on V2.                        |
 * | #5  | V2 becomes the default. [PlaneRenderer] V1 + [Version.V1] are annotated   |
 * |     | `@Deprecated` with a `ReplaceWith` hint pointing at `Version.V2`. V1      |
 * |     | stays available for one release cycle so external apps that override      |
 * |     | `plane_renderer.mat` have time to port their custom styling.              |
 *
 * **You almost certainly want [Version.V2] — it is the default.** Stay on V1 only long
 * enough to port a custom plane material; the V1 path will be removed in a future release.
 *
 * @see PlaneRenderer
 * @see PlaneRendererV2
 * @see Version
 */
sealed interface PlaneRendererBase {

    /**
     * Display size (in pixels) of the surface this renderer draws into.
     *
     * Used by V1 to drive its centre-screen hit-test in
     * [PlaneRenderer.PlaneRendererMode.RENDER_CENTER]. Set by `ARSceneView` whenever the
     * surface is resized.
     */
    var viewSize: Size

    /**
     * Master switch — when `false`, plane visualization is fully suspended and no per-frame
     * work runs in [update].
     */
    var isEnabled: Boolean

    /**
     * Controls visibility of the plane grid overlay. When `false`, no planes are drawn but
     * shadows can still be received independently — see [isShadowReceiver].
     */
    var isVisible: Boolean

    /**
     * Controls whether scene renderables cast shadows onto detected planes.
     *
     * Independent of [isVisible]: a plane can be hidden but still receive shadows (useful
     * for grounding placed objects on an invisible surface).
     */
    var isShadowReceiver: Boolean

    /**
     * Per-frame update hook — invoked once per AR frame from `ARSceneView`'s render loop
     * with the live ARCore [Session] and [Frame]. Pulls the updated planes, builds /
     * destroys per-plane visualizers as needed, and feeds plane geometry to Filament.
     */
    fun update(session: Session, frame: Frame)

    /**
     * Releases every native resource owned by the renderer (Filament materials, textures,
     * vertex / index buffers, entities). Must be called exactly once when the host
     * `ARSceneView` is disposed.
     */
    fun destroy()

    /**
     * Selects which plane-renderer implementation `ARSceneView` instantiates.
     *
     * **Default is [V2]** as of this release — the depth-driven PBR plane mesh shipped by
     * [#2203](https://github.com/sceneview/sceneview/issues/2203). [V1] remains available
     * behind this enum for one release cycle and is now `@Deprecated`. Switching versions
     * at runtime triggers a renderer rebuild (the parameter is wired into the surrounding
     * `remember(...)` keys), so toggling is safe but **not free** — pick once at composition
     * root.
     */
    enum class Version {
        /**
         * Legacy plane renderer — flat polygon + procedural unlit grid.
         *
         * **Deprecated** as of #2203 PR #5. [V2] is the default and delivers depth-driven
         * mesh + PBR + HDR reflection + type-aware shading + scan-in. V1 stays available
         * for one release cycle so apps that subclass [PlaneRenderer] or override
         * `plane_renderer.mat` can port their custom styling — it will be removed in a
         * future release.
         */
        @Deprecated(
            message = "V1 renders detected planes as a flat unlit polygon. " +
                "V2 ships depth-driven mesh, PBR lighting, HDR reflection, " +
                "type-aware shading, and scan-in animation. Migrate to V2 " +
                "via ARSceneView(planeRendererVersion = PlaneRendererBase.Version.V2). " +
                "V1 will be removed in a future release. See #2203.",
            replaceWith = ReplaceWith("PlaneRendererBase.Version.V2"),
            level = DeprecationLevel.WARNING,
        )
        V1,

        /**
         * Next-generation plane renderer — depth-driven PBR mesh lit by ARCore's HDR
         * estimate, type-aware floor / ceiling / wall material identities, and an 800 ms
         * scan-in ring on first detection. **Default** as of this release. See
         * [#2203](https://github.com/sceneview/sceneview/issues/2203).
         */
        V2
    }
}
