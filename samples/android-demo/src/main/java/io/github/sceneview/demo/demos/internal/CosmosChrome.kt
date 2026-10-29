package io.github.sceneview.demo.demos.internal

/**
 * What the Cosmos 2D layer says and offers, as pure functions of the demo's state — so the
 * rules behind the chrome are covered by JVM tests, apart from the Compose that draws them.
 *
 * The layer is three things, bottom up:
 *
 * - the **dock**: Autoplay (the voyage through the four scenes, play / pause, labelled) and
 *   Settings. No scene lives in the dock any more: a scene one slot away from Settings opened
 *   the settings sheet on a near miss, and the sheet faded the whole bar out (#4601).
 * - the **scene switch**: one opaque segmented pill, Galaxy | Star | Burst | Flow, its checked
 *   segment always the scene on screen — the voyage moves it as it travels.
 * - a **status line** over it: the shot or the scene, in a sentence.
 */
internal object CosmosChrome {

    /** The scene switch's segments, left to right. */
    val switchOrder: List<CosmosScene> = CosmosScene.entries

    /**
     * Whether the Autoplay item shows as playing (checked, pause glyph): the voyage is on and
     * holds the camera. A voyage that is on but waiting for an idle user to let go shows as
     * paused, and a tap on it plays at once.
     */
    fun autoplayPlaying(voyageOn: Boolean, voyageCaption: String?): Boolean =
        voyageOn && voyageCaption != null

    /**
     * Whether picking [target] in the scene switch changes anything. The scene on screen is a
     * no-op — re-picking Star in Spacetime must not drop back to Starlight.
     */
    fun pickChangesScene(target: CosmosScene, current: CosmosScene): Boolean = target != current

    /**
     * The status line over the scene switch: the voyage's shot while it plays, Spacetime's
     * legend, the Star scene's tap hint or close-up, otherwise the scene's own caption.
     */
    fun status(
        scene: CosmosScene,
        voyageCaption: String?,
        spacetime: Boolean,
        focus: CosmosFocus,
        spacetimeLegend: String,
    ): String = when {
        voyageCaption != null -> voyageCaption
        scene == CosmosScene.Star && spacetime -> spacetimeLegend
        scene == CosmosScene.Star -> focus.caption
        else -> scene.caption
    }

    /** The scene switch's test tag for [scene]: `cosmos_scene_galaxy` … `cosmos_scene_flow`. */
    fun switchTestTag(scene: CosmosScene): String = "cosmos_scene_" + scene.name.lowercase()
}
