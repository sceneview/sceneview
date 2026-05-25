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
 *  * [PlaneRenderer] — the original ("V1") renderer. Draws each detected plane as a flat
 *    polygon textured with a procedural soft grid. **Default today.**
 *  * [PlaneRendererV2] — the next-generation renderer. Behaviourally identical to V1 as of
 *    this PR (skeleton only) so existing demos and apps that opt-in see no visual change yet,
 *    but the V2 path will gain depth-driven micro-relief, PBR materials lit by ARCore's HDR
 *    cubemap, type-aware shading (floor / ceiling / wall) and an animated scan-in effect in
 *    follow-up PRs. See umbrella issue
 *    [#2203](https://github.com/sceneview/sceneview/issues/2203).
 *
 * ### Migration timeline
 *
 * | PR  | Change                                                                    |
 * |-----|---------------------------------------------------------------------------|
 * | #1  | This skeleton + opt-in flag. V2 is byte-equivalent to V1 today.           |
 * | #2  | Depth-driven tessellation + displacement on V2.                           |
 * | #3  | PBR + HDR reflections + animated scan-in on V2.                           |
 * | #4  | Type-aware shading (floor / ceiling / wall) on V2.                        |
 * | #5  | V2 becomes the default. [PlaneRenderer] V1 is annotated `@Deprecated`     |
 * |     | with a `ReplaceWith` hint pointing at `Version.V2`. V1 stays available    |
 * |     | for one release cycle so external apps that override `plane_renderer.mat` |
 * |     | have time to port their custom styling to the V2 material.                |
 *
 * **You almost certainly want [Version.V2] once PR #5 ships.** Until then, keep the default
 * unless you want to preview the V2 visuals as they land.
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
     * Default is [V1] for backwards-compatibility; will flip to [V2] in PR #5 once the V2
     * visual upgrades land. Switching versions at runtime triggers a renderer rebuild
     * (the parameter is wired into the surrounding `remember(...)` keys), so toggling
     * is safe but **not free** — pick once at composition root.
     *
     * See umbrella issue [#2203](https://github.com/sceneview/sceneview/issues/2203).
     */
    enum class Version {
        /** Original plane renderer — flat polygon + procedural unlit grid. Default. */
        V1,

        /**
         * Next-generation plane renderer. Identical to [V1] today; will deliver depth-driven
         * micro-relief, PBR materials lit by ARCore's HDR estimate, type-aware shading and
         * an animated scan-in in subsequent PRs of the
         * [#2203](https://github.com/sceneview/sceneview/issues/2203) sprint.
         */
        V2
    }
}
