package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position

/**
 * The saving orbit of one save (#4719): started on its first frame from the phone's eye of the
 * moment — the last one ARCore gave the live view ([ScanLens]), else the follow pose — and flown
 * along a [ScanSavingPath] planned once round the cloud, which no longer grows while saving: up
 * out of the room through its top, its ceiling cut off ([ScanOpenTop]), then round it from above (#4742).
 *
 * Seeded on the eye, the orbit's first pose stands where the phone stood, looking where it looked
 * ([ScanFollow.eyePose]), and the camera is put on it at once: the view leaves the camera image
 * without a cut ([EyeHandoff]), then eases out onto the orbit. A view a drag had taken off the
 * phone sets out from where it is instead. [nanoTime] is the clock, a fake one in tests.
 */
class ScanSavingOrbit(private val nanoTime: () -> Long = System::nanoTime) {
    private var path: ScanSavingPath? = null
    private var started = 0L

    /** The pose the orbit set out from, once it has: the eye it was seeded with. */
    val start: OrbitPose? get() = path?.from

    /** The flight planned on the first frame ([ScanSavingPath]): up out of the room, then round it. */
    val flight: ScanSavingPath? get() = path

    /**
     * The pose for this frame. Until [ready] — the room's lid still drawn, its cut on its way
     * ([ScanOpenTop]) — the view holds on the eye it set out from: rising, it would go through
     * the ceiling. The flight starts once it is.
     */
    fun poseFor(phone: OrbitPose?, points: FloatArray, orbit: ArDebugOrbitCamera, ready: Boolean = true): OrbitPose? {
        val flight = path ?: run {
            // The room's lid is not drawn while it saves, nor flown round ([ScanOpenTop]).
            val points = ScanOpenTop.positions(points)
            val centre = ScanFollow.cloudCentre(points) ?: return phone
            ScanSavingPath(
                from = seed(phone, centre, orbit),
                points = points,
                centre = centre,
                radius = ScanFollow.cloudRadius(points, centre),
                verticalFovDegrees = orbit.verticalFovDegrees,
            ).also {
                path = it
                started = nanoTime()
            }
        }
        if (!ready) started = nanoTime()
        return flight.poseAt((nanoTime() - started) / NS_PER_S)
    }

    /** The eye, the camera put on it at once, while the view still is the phone's; else as is. */
    private fun seed(phone: OrbitPose?, around: Position, orbit: ArDebugOrbitCamera): OrbitPose {
        val eye = orbit.firstPerson?.takeIf { it.held && orbit.seesThroughEye }
            ?: return phone ?: orbit.pose
        return ScanFollow.eyePose(eye.cameraToWorld, around).also { orbit.snapTo(it) }
    }

    private companion object {
        const val NS_PER_S = 1e9f
    }
}
