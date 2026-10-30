package io.github.sceneview.demo.demos.internal

/**
 * Why a Scene Geometry screen (Scene Mesh, Streetscape) has nothing to draw yet.
 *
 * Streetscape geometry only arrives once a whole chain holds: the phone's Location switch
 * is on, Earth has localised, Google has Street View imagery of that spot, and the camera
 * is looking at buildings from outdoors. The screen used to answer every broken link with
 * the same sentence ("needs an outdoor location with Street View coverage… not indoors"),
 * so a user standing in an uncovered street, one with Location switched off and one at
 * their desk all read the same thing and none of them could tell what to change.
 *
 * Pure so the order of the checks is unit-tested: each value names the first link that
 * does not hold.
 */
enum class SceneGeometryWait {
    /** The phone's Location switch is off: Earth can never localise. */
    LocationOff,

    /** Earth is enabled but has no position yet; still inside the grace period. */
    Localizing,

    /** Earth still has no position after the grace period — indoors, or no sky view. */
    NotLocalized,

    /** Earth has a position and Google reports no Street View imagery there. */
    NoCoverage,

    /** Earth has a position; geometry may still be on its way. */
    Looking,

    /** Street View covers this spot and still nothing loaded after the grace period. */
    CoveredNothingInView,

    /** Coverage could not be checked and nothing loaded after the grace period. */
    NothingFound,
}

/**
 * The first broken link between "session running" and "geometry on screen".
 *
 * Only meaningful once the camera tracks, no geometry is on screen, and neither the
 * session nor the Cloud service nor Earth itself reported an error — those have their own
 * banners, which outrank this one.
 *
 * @param locationEnabled the phone's Location switch, not the app permission.
 * @param earthTracking whether `Earth.trackingState` is `TRACKING`.
 * @param vps the Street View coverage answer for Earth's position.
 * @param waitedLong whether the grace period has run out with still nothing to draw.
 */
fun sceneGeometryWait(
    locationEnabled: Boolean,
    earthTracking: Boolean,
    vps: VpsCoverage,
    waitedLong: Boolean,
): SceneGeometryWait = when {
    // Checked before Earth: with Location off Earth never tracks, and "step outside"
    // would send the user out of the door with the switch still off.
    !locationEnabled -> SceneGeometryWait.LocationOff
    !earthTracking ->
        if (waitedLong) SceneGeometryWait.NotLocalized else SceneGeometryWait.Localizing
    // A definite "no imagery here" needs no grace period: waiting cannot change it.
    vps == VpsCoverage.Unavailable -> SceneGeometryWait.NoCoverage
    !waitedLong -> SceneGeometryWait.Looking
    vps == VpsCoverage.Available -> SceneGeometryWait.CoveredNothingInView
    else -> SceneGeometryWait.NothingFound
}

/**
 * The ~100 m cell a coverage answer is cached for: Street View coverage does not change
 * from one step to the next, and Earth's position jitters by more than a step.
 */
fun vpsCell(latitude: Double, longitude: Double): Pair<Int, Int> =
    Math.round(latitude * 1_000).toInt() to Math.round(longitude * 1_000).toInt()
