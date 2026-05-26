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
 *  * [PlaneRenderer] — the **default** renderer (V1) again as of v4.16.1. Draws each
 *    detected plane as a flat polygon textured with a procedural soft grid. Battle-tested.
 *  * [PlaneRendererV2] — **experimental opt-in**. v4.16.0 briefly shipped V2 as the default
 *    (depth-driven PBR mesh + HDR reflection + type-aware shading + scan-in) but on-device
 *    QA showed the visual output not matching the design intent, so the default was
 *    reverted in v4.16.1 while V2 is polished. The code remains in place so early adopters
 *    can opt in (`Version.V2`) and help shape the redesign — see
 *    [#2203](https://github.com/sceneview/sceneview/issues/2203).
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
     * **Default is [V1]** as of v4.16.1 — the proven flat-polygon procedural-grid renderer.
     * [V2] is opt-in experimental pending polish work — see
     * [#2203](https://github.com/sceneview/sceneview/issues/2203). Switching versions at
     * runtime triggers a renderer rebuild (the parameter is wired into the surrounding
     * `remember(...)` keys), so toggling is safe but **not free** — pick once at composition
     * root.
     */
    enum class Version {
        /**
         * Default plane renderer — flat polygon + procedural unlit grid. Battle-tested,
         * matches every existing AR demo. Default as of v4.16.1.
         */
        V1,

        /**
         * Experimental plane renderer — depth-driven mesh, PBR materials lit by ARCore's
         * HDR estimate, type-aware floor / ceiling / wall material identities, and an
         * 800 ms scan-in ring on first detection.
         *
         * **Visual quality not yet at the V1 polish level.** On-device QA in v4.16.0 showed
         * the V2 output not matching the design intent, so the default was reverted to V1
         * in v4.16.1 while V2 is polished. Opt in to help shape the redesign — see
         * [#2203](https://github.com/sceneview/sceneview/issues/2203) for the research
         * notes (`.claude/plans/v2-*.md`) and the discussion.
         */
        V2
    }
}
