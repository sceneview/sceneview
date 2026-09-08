package io.github.sceneview.core.threemf

/**
 * What a unit-less mesh format (STL, OBJ, PLY) most likely meant by "1" — #3543.
 *
 * None of the three formats records a length unit, so the loaders default to [ThreeMfUnit.Default]
 * (millimetres), the convention of the printing tools that write most of them. That default is
 * right for a part exported from a slicer and wrong for a mesh exported from a game engine, a
 * photogrammetry scan or Blender, where the working unit is the metre.
 *
 * Coordinate magnitude tells the two apart well enough to *ask*: a printable part is tens to
 * hundreds of millimetres across, so it reads as tens to hundreds of units; a metre-authored scene
 * reads as a handful of units. When the longest side of a file's bounding box lands in
 * [MetreLikeExtent] — 5 cm to 50 m read as metres — millimetres would make the object smaller than
 * a grain of sand, and metres is the better guess.
 *
 * The guess is never applied silently: the demo offers it ("Looks like metres") and the user
 * decides. This object only answers the question, so the answer can be unit-tested and shared by
 * every platform that asks it.
 */
object ModelUnitGuess {

    /**
     * Longest-side range, in the file's own units, that reads as metre-authored. Below 0.05 the
     * file is more likely a badly-scaled scan than a 5 cm object stored in metres; above 50 the
     * millimetre reading (5 cm to 50 m) is already plausible on its own.
     */
    val MetreLikeExtent: ClosedFloatingPointRange<Float> = 0.05f..50f

    /**
     * The unit to *offer* for a unit-less file whose bounding box is [maxExtent] units on its
     * longest side, or `null` when [ThreeMfUnit.Default] is the sensible reading and nothing
     * should be asked.
     *
     * `ModelUnitGuess.suggest(2f)` → [ThreeMfUnit.METER] (a 2-unit mesh is 2 m, not 2 mm).
     * `ModelUnitGuess.suggest(70f)` → `null` (a 70-unit mesh is a 70 mm part).
     */
    fun suggest(maxExtent: Float): ThreeMfUnit? =
        ThreeMfUnit.METER.takeIf { maxExtent.isFinite() && maxExtent in MetreLikeExtent }

    /**
     * Same question asked from a model already loaded at [loadedUnit]: [maxExtentMeters] is the
     * longest side of the world-space bounding box, in metres, which is the only measurement a
     * renderer has once the file is converted. Returns `null` when the loaded unit is already the
     * suggestion, so a re-open is never offered for the size it is showing.
     */
    fun suggestFromLoaded(
        maxExtentMeters: Float,
        loadedUnit: ThreeMfUnit = ThreeMfUnit.Default,
    ): ThreeMfUnit? = suggest(maxExtentMeters / loadedUnit.meters)?.takeIf { it != loadedUnit }
}
