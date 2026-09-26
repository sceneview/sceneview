package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Pins the Geospatial Anchors demo's decisions (#3832) — the parts the emulator cannot
 * show because it cannot run ARCore (#2754).
 */
class GeospatialFlowTest {

    // ── Localization state machine ────────────────────────────────────────────────────

    private val start = GeospatialLocalizationTracker()

    @Test
    fun `no earth tracking stays pretracking`() {
        val next = start.update(earthTracking = false, horizontalAccuracyM = 3.0, yawAccuracyDeg = 5.0, nowMillis = 0)
        assertEquals(GeospatialLocalization.Pretracking, next.phase)
    }

    @Test
    fun `tracking without an accuracy reading stays pretracking`() {
        val next = start.update(earthTracking = true, horizontalAccuracyM = null, yawAccuracyDeg = 5.0, nowMillis = 0)
        assertEquals(GeospatialLocalization.Pretracking, next.phase)
    }

    @Test
    fun `poor first fix starts localizing and stamps the start time`() {
        val next = start.update(true, 30.0, 40.0, nowMillis = 1_000)
        assertEquals(GeospatialLocalization.Localizing, next.phase)
        assertEquals(1_000, next.localizingSinceMillis)
    }

    @Test
    fun `good first fix is localized straight away`() {
        val next = start.update(true, 3.0, 5.0, nowMillis = 0)
        assertEquals(GeospatialLocalization.Localized, next.phase)
    }

    @Test
    fun `both thresholds must hold to localize`() {
        val localizing = start.update(true, 30.0, 40.0, 0)
        assertEquals(
            GeospatialLocalization.Localizing,
            localizing.update(true, 5.0, LOCALIZED_YAW_ACCURACY_DEG + 1, 10).phase,
        )
        assertEquals(
            GeospatialLocalization.Localizing,
            localizing.update(true, LOCALIZED_HORIZONTAL_ACCURACY_M + 1, 5.0, 10).phase,
        )
        assertEquals(
            GeospatialLocalization.Localized,
            localizing.update(true, LOCALIZED_HORIZONTAL_ACCURACY_M, LOCALIZED_YAW_ACCURACY_DEG, 10).phase,
        )
    }

    @Test
    fun `localized survives accuracy inside the hysteresis band`() {
        val localized = start.update(true, 3.0, 5.0, 0)
        val breathing = localized.update(
            true,
            LOCALIZED_HORIZONTAL_ACCURACY_M + LOCALIZED_HYSTERESIS_M,
            LOCALIZED_YAW_ACCURACY_DEG + LOCALIZED_HYSTERESIS_DEG,
            10,
        )
        assertEquals(GeospatialLocalization.Localized, breathing.phase)
    }

    @Test
    fun `localized drops back past the hysteresis band and restarts the timeout`() {
        val localized = start.update(true, 3.0, 5.0, 0)
        val lost = localized.update(true, LOCALIZED_HORIZONTAL_ACCURACY_M + LOCALIZED_HYSTERESIS_M + 0.1, 5.0, 50_000)
        assertEquals(GeospatialLocalization.Localizing, lost.phase)
        assertEquals(50_000, lost.localizingSinceMillis)

        val lostHeading = localized.update(true, 3.0, LOCALIZED_YAW_ACCURACY_DEG + LOCALIZED_HYSTERESIS_DEG + 0.1, 60_000)
        assertEquals(GeospatialLocalization.Localizing, lostHeading.phase)
    }

    @Test
    fun `localizing past the timeout is taking long, and still recovers`() {
        val localizing = start.update(true, 30.0, 40.0, 0)
        assertEquals(GeospatialLocalization.Localizing, localizing.update(true, 30.0, 40.0, LOCALIZING_TIMEOUT_MILLIS).phase)
        val stuck = localizing.update(true, 30.0, 40.0, LOCALIZING_TIMEOUT_MILLIS + 1)
        assertEquals(GeospatialLocalization.TakingLong, stuck.phase)
        assertEquals(GeospatialLocalization.TakingLong, stuck.update(true, 30.0, 40.0, LOCALIZING_TIMEOUT_MILLIS + 5_000).phase)
        assertEquals(GeospatialLocalization.Localized, stuck.update(true, 4.0, 8.0, LOCALIZING_TIMEOUT_MILLIS + 6_000).phase)
    }

    @Test
    fun `losing earth tracking resets to pretracking from any phase`() {
        val localized = start.update(true, 3.0, 5.0, 0)
        assertEquals(GeospatialLocalization.Pretracking, localized.update(false, 3.0, 5.0, 10).phase)
    }

    // ── One overlay at a time ─────────────────────────────────────────────────────────

    @Test
    fun `camera scrim narrating keeps the bottom silent - one loader`() {
        val frame = GeospatialFrame(cameraReady = false, scrimNarrating = true)
        assertEquals(GeospatialOverlay.Silent, frame.overlay())
    }

    @Test
    fun `scrim timed out on a stuck camera hands the line to one pill`() {
        val frame = GeospatialFrame(cameraReady = false, scrimNarrating = false)
        assertEquals(GeospatialOverlay.StartingCamera, frame.overlay())
    }

    @Test
    fun `frames flowing but motion tracking not started is the card, not a second pill`() {
        val frame = GeospatialFrame(cameraReady = true, scrimNarrating = false, cameraTracking = false)
        assertEquals(GeospatialOverlay.Status, frame.overlay())
        val card = frame.statusCard()
        assertEquals("Sensing your surroundings…", card.title)
        assertEquals(GeospatialIndicator.Working, card.indicator)
        assertEquals(0, card.meterSegments)
        assertNull(card.accuracy)
        assertEquals("Move your phone slowly.", card.hint)
    }

    @Test
    fun `losing motion tracking disables drop even with a fix, and keeps the last drop line`() {
        val drop = DropFeedback(GeospatialAnchorMode.Terrain, DropOutcome.Anchored, 4.0, -1.0)
        val frame = GeospatialFrame(
            cameraReady = true,
            scrimNarrating = false,
            cameraTracking = false,
            localization = GeospatialLocalization.Localized,
            hasFix = true,
            lastDrop = drop,
        )
        assertFalse(frame.primaryAction().enabled)
        assertEquals(drop, frame.statusCard().drop)
    }

    @Test
    fun `tracking shows the status card, even before earth has a fix`() {
        val frame = GeospatialFrame(cameraReady = true, scrimNarrating = false, cameraTracking = true)
        assertEquals(GeospatialOverlay.Status, frame.overlay())
    }

    @Test
    fun `a blocker wins over every loader`() {
        assertEquals(GeospatialOverlay.Blocker, GeospatialFrame(blocked = true).overlay())
        assertEquals(
            GeospatialOverlay.Blocker,
            GeospatialFrame(blocked = true, cameraReady = true, cameraTracking = true).overlay(),
        )
    }

    @Test
    fun `ARCore unavailable leaves the SDK card alone`() {
        assertEquals(GeospatialOverlay.Silent, GeospatialFrame(arUnavailable = true, scrimNarrating = false).overlay())
    }

    // ── Status card ───────────────────────────────────────────────────────────────────

    private val tracking = GeospatialFrame(cameraReady = true, scrimNarrating = false, cameraTracking = true)

    @Test
    fun `pretracking card has no accuracy, an empty meter and a hint`() {
        val card = tracking.statusCard()
        assertEquals("Finding your location…", card.title)
        assertNull(card.accuracy)
        assertEquals(0, card.meterSegments)
        assertNotNull(card.hint)
    }

    @Test
    fun `localizing card shows accuracy and tells the user to go outside`() {
        val card = tracking.copy(
            localization = GeospatialLocalization.Localizing,
            hasFix = true,
            horizontalAccuracyM = 18.0,
            yawAccuracyDeg = 24.0,
        ).statusCard()
        assertEquals("±18 m position · ±24° heading", card.accuracy)
        assertEquals(2, card.meterSegments)
        assertEquals(GeospatialTone.Progress, card.meterTone)
        assertTrue(card.hint!!.contains("outside"))
    }

    @Test
    fun `localizing never lights the third segment, even inside the thresholds`() {
        val card = tracking.copy(
            localization = GeospatialLocalization.Localizing,
            horizontalAccuracyM = 5.0,
            yawAccuracyDeg = 5.0,
        ).statusCard()
        assertEquals(2, card.meterSegments)
    }

    @Test
    fun `very poor accuracy lights one guidance segment`() {
        val card = tracking.copy(
            localization = GeospatialLocalization.Localizing,
            horizontalAccuracyM = 60.0,
            yawAccuracyDeg = 50.0,
        ).statusCard()
        assertEquals(1, card.meterSegments)
        assertEquals(GeospatialTone.Guidance, card.meterTone)
    }

    @Test
    fun `localized card is full, success, and has no hint`() {
        val card = tracking.copy(
            localization = GeospatialLocalization.Localized,
            horizontalAccuracyM = 2.44,
            yawAccuracyDeg = 6.2,
        ).statusCard()
        assertEquals("Location locked", card.title)
        assertEquals("±2.4 m position · ±6° heading", card.accuracy)
        assertEquals(GEOSPATIAL_ACCURACY_SEGMENTS, card.meterSegments)
        assertEquals(GeospatialTone.Success, card.meterTone)
        assertNull(card.hint)
    }

    @Test
    fun `taking long card says accuracy is still low and to step outside`() {
        val card = tracking.copy(
            localization = GeospatialLocalization.TakingLong,
            horizontalAccuracyM = 42.0,
            yawAccuracyDeg = 38.0,
        ).statusCard()
        assertEquals("Accuracy is still low here", card.title)
        assertTrue(card.hint!!.contains("outside"))
    }

    @Test
    fun `coverage line follows the VPS check`() {
        assertNull(tracking.copy(vps = VpsCoverage.Unknown).statusCard().coverage)
        assertNull(tracking.copy(vps = VpsCoverage.Error).statusCard().coverage)
        assertTrue(tracking.copy(vps = VpsCoverage.Checking).statusCard().coverage!!.startsWith("Checking"))
        assertTrue(tracking.copy(vps = VpsCoverage.Available).statusCard().coverage!!.contains("best accuracy"))
        assertTrue(tracking.copy(vps = VpsCoverage.Unavailable).statusCard().coverage!!.contains("GPS"))
    }

    @Test
    fun `status copy never names ARCore internals`() {
        val jargon = listOf("ARCore", "VPS", "tracking", "EUS", "ERROR_", "Earth")
        val lines = GeospatialScenario.entries.flatMap { scenario ->
            val card = scenario.frame().statusCard()
            listOfNotNull(card.title, card.coverage, card.hint, card.drop?.message())
        } + DropOutcome.entries.flatMap { outcome ->
            GeospatialAnchorMode.entries.map { DropFeedback(it, outcome).message() }
        }
        lines.forEach { line ->
            jargon.forEach { word -> assertFalse("\"$line\" contains $word", line.contains(word)) }
        }
    }

    @Test
    fun `indicator is a spinner while waiting, the move glyph while improving, a check once locked`() {
        assertEquals(GeospatialIndicator.Working, tracking.statusCard().indicator)
        assertEquals(
            GeospatialIndicator.Move,
            tracking.copy(localization = GeospatialLocalization.Localizing).statusCard().indicator,
        )
        assertEquals(
            GeospatialIndicator.Move,
            tracking.copy(localization = GeospatialLocalization.TakingLong).statusCard().indicator,
        )
        assertEquals(
            GeospatialIndicator.Done,
            tracking.copy(localization = GeospatialLocalization.Localized).statusCard().indicator,
        )
    }

    @Test
    fun `earth errors other than the shared cloud failures get their own blocker line`() {
        assertNull(earthErrorMessage(null))
        assertNull(earthErrorMessage("ENABLED"))
        // Rendered by the shared CloudServiceStatusBanner instead (#3262).
        assertNull(earthErrorMessage("ERROR_NOT_AUTHORIZED"))
        assertNull(earthErrorMessage("ERROR_RESOURCE_EXHAUSTED"))
        assertTrue(earthErrorMessage("ERROR_APK_VERSION_TOO_OLD")!!.contains("Update"))
        assertNotNull(earthErrorMessage("ERROR_GEOSPATIAL_MODE_DISABLED"))
        assertNotNull(earthErrorMessage("ERROR_INTERNAL"))
        assertNotNull(earthErrorMessage("ERROR_SOMETHING_NEW"))
    }

    @Test
    fun `resolve states map to drop outcomes, and success without a node is a failure`() {
        assertEquals(DropOutcome.Anchored, dropOutcomeOf("SUCCESS", hasNode = true))
        assertEquals(DropOutcome.Failed, dropOutcomeOf("SUCCESS", hasNode = false))
        assertEquals(DropOutcome.NoDataHere, dropOutcomeOf("ERROR_UNSUPPORTED_LOCATION", hasNode = false))
        assertEquals(DropOutcome.NotAuthorized, dropOutcomeOf("ERROR_NOT_AUTHORIZED", hasNode = false))
        assertEquals(DropOutcome.Failed, dropOutcomeOf("ERROR_INTERNAL", hasNode = false))
        assertEquals(DropOutcome.Failed, dropOutcomeOf("TASK_IN_PROGRESS", hasNode = false))
    }

    @Test
    fun `accuracy label keeps one decimal under 10 m and whole metres above`() {
        assertEquals("±9.6 m position · ±12° heading", accuracyLabel(9.64, 12.4))
        assertEquals("±12 m position · ±3° heading", accuracyLabel(12.4, 2.6))
        assertNull(accuracyLabel(null, 3.0))
    }

    // ── Primary action ────────────────────────────────────────────────────────────────

    @Test
    fun `drop is disabled until earth gives a fix`() {
        val action = tracking.primaryAction()
        assertFalse(action.enabled)
        assertNotNull(action.reason)
    }

    @Test
    fun `drop is live while accuracy is still improving`() {
        val action = tracking.copy(localization = GeospatialLocalization.Localizing, hasFix = true).primaryAction()
        assertTrue(action.enabled)
        assertNull(action.reason)
    }

    @Test
    fun `drop label follows the mode`() {
        assertEquals("Drop anchor", tracking.primaryAction().label)
        assertEquals("Drop on rooftop", tracking.copy(mode = GeospatialAnchorMode.Rooftop).primaryAction().label)
    }

    // ── Drop feedback ─────────────────────────────────────────────────────────────────

    @Test
    fun `every drop outcome says something`() {
        GeospatialAnchorMode.entries.forEach { mode ->
            DropOutcome.entries.forEach { outcome ->
                assertTrue(DropFeedback(mode, outcome).message().isNotBlank())
            }
        }
    }

    @Test
    fun `anchored ahead reports the distance only`() {
        val feedback = DropFeedback(GeospatialAnchorMode.Terrain, DropOutcome.Anchored, 4.2, -1.1)
        assertEquals("Anchored 4 m away.", feedback.message())
        assertEquals(GeospatialTone.Success, feedback.tone())
    }

    @Test
    fun `terrain anchor well below the user says it is at street level`() {
        val feedback = DropFeedback(GeospatialAnchorMode.Terrain, DropOutcome.Anchored, 4.0, -9.4)
        assertEquals("Anchored 4 m away, 9 m below you at street level.", feedback.message())
    }

    @Test
    fun `rooftop anchor above the user says it is on the rooftop`() {
        val feedback = DropFeedback(GeospatialAnchorMode.Rooftop, DropOutcome.Anchored, 12.0, 14.0)
        assertEquals("Anchored 12 m away, 14 m above you on the rooftop.", feedback.message())
    }

    @Test
    fun `distance never rounds down to zero metres`() {
        val feedback = DropFeedback(GeospatialAnchorMode.Terrain, DropOutcome.Anchored, 0.2, 0.0)
        assertEquals("Anchored 1 m away.", feedback.message())
    }

    @Test
    fun `failures explain why and wear the blocked tone`() {
        val noData = DropFeedback(GeospatialAnchorMode.Terrain, DropOutcome.NoDataHere)
        assertTrue(noData.message().contains("no terrain data"))
        assertEquals(GeospatialTone.Blocked, noData.tone())
        assertTrue(DropFeedback(GeospatialAnchorMode.Rooftop, DropOutcome.NoDataHere).message().contains("building"))
        assertTrue(DropFeedback(GeospatialAnchorMode.Terrain, DropOutcome.NotAuthorized).message().contains("API key"))
        assertEquals(GeospatialTone.Progress, DropFeedback(GeospatialAnchorMode.Terrain, DropOutcome.Resolving).tone())
    }

    // ── Drop pose ─────────────────────────────────────────────────────────────────────

    /** Rotates +Z by the pose's quaternion (pure Y rotation) and returns its X/Z. */
    private fun DropPose.facing(): Pair<Float, Float> {
        val theta = 2f * atan2(qy, qw)
        return kotlin.math.sin(theta) to kotlin.math.cos(theta)
    }

    @Test
    fun `drop lands ahead of a level camera, below it, facing it`() {
        // Camera looking down world -Z: its +Z axis is world +Z.
        val pose = dropPoseAhead(
            cameraX = 1f, cameraY = 1.5f, cameraZ = 2f,
            cameraZAxis = floatArrayOf(0f, 0f, 1f),
            cameraYAxis = floatArrayOf(0f, 1f, 0f),
            distance = 4f, below = 1.3f,
        )
        assertEquals(1f, pose.x, 1e-4f)
        assertEquals(0.2f, pose.y, 1e-4f)
        assertEquals(-2f, pose.z, 1e-4f)
        val (fx, fz) = pose.facing()
        // Faces back toward the camera: +Z.
        assertEquals(0f, fx, 1e-4f)
        assertEquals(1f, fz, 1e-4f)
    }

    @Test
    fun `drop follows a camera turned to face east`() {
        // Camera looking down world +X: its +Z axis is world -X.
        val pose = dropPoseAhead(0f, 1.5f, 0f, floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), 4f, 1.3f)
        assertEquals(4f, pose.x, 1e-4f)
        assertEquals(0f, pose.z, 1e-4f)
        val (fx, fz) = pose.facing()
        assertEquals(-1f, fx, 1e-4f)
        assertEquals(0f, fz, 1e-4f)
    }

    @Test
    fun `a tilted camera still drops at the full horizontal distance`() {
        // Pitched 45 degrees down, looking toward -Z.
        val s = sqrt(0.5f)
        val pose = dropPoseAhead(0f, 1.5f, 0f, floatArrayOf(0f, s, s), floatArrayOf(0f, s, -s), 4f, 1.3f)
        assertEquals(4f, sqrt(pose.x * pose.x + pose.z * pose.z), 1e-4f)
        assertTrue(pose.z < 0f)
    }

    @Test
    fun `a camera pointing straight down drops toward the top of the screen`() {
        // Looking straight down: +Z axis is world up; the screen's top edge points to -Z.
        val pose = dropPoseAhead(0f, 1.5f, 0f, floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, -1f), 4f, 1.3f)
        assertEquals(0f, pose.x, 1e-4f)
        assertEquals(-4f, pose.z, 1e-4f)
    }

    @Test
    fun `drop rotation is a unit quaternion about the vertical only`() {
        val pose = dropPoseAhead(0f, 0f, 0f, floatArrayOf(0.3f, 0.2f, -0.9f), floatArrayOf(0f, 1f, 0f), 4f, 1f)
        assertEquals(0f, pose.qx, 0f)
        assertEquals(0f, pose.qz, 0f)
        assertEquals(1f, pose.qy * pose.qy + pose.qw * pose.qw, 1e-4f)
    }

    // ── QA scenarios ──────────────────────────────────────────────────────────────────

    @Test
    fun `scenario names parse in every spelling`() {
        assertEquals(GeospatialScenario.AnchoredBelow, geospatialScenarioOf("anchored-below"))
        assertEquals(GeospatialScenario.AnchoredBelow, geospatialScenarioOf("anchored_below"))
        assertEquals(GeospatialScenario.AnchoredBelow, geospatialScenarioOf("AnchoredBelow"))
        assertNull(geospatialScenarioOf("nope"))
        assertNull(geospatialScenarioOf(null))
    }

    @Test
    fun `every scenario renders the status card`() {
        GeospatialScenario.entries.forEach { scenario ->
            assertEquals(scenario.name, GeospatialOverlay.Status, scenario.frame().overlay())
        }
    }

    @Test
    fun `scenarios produce the state they are named after`() {
        assertEquals("Sensing your surroundings…", GeospatialScenario.Starting.frame().statusCard().title)
        assertFalse(GeospatialScenario.Starting.frame().primaryAction().enabled)
        assertFalse(GeospatialScenario.Finding.frame().primaryAction().enabled)
        assertEquals(GeospatialLocalization.Localizing, GeospatialScenario.Improving.frame().localization)
        assertEquals(GeospatialLocalization.Localized, GeospatialScenario.Locked.frame().localization)
        assertNull(GeospatialScenario.Locked.frame().lastDrop)
        assertEquals(GeospatialLocalization.TakingLong, GeospatialScenario.TakingLong.frame().localization)
        assertEquals(DropOutcome.Resolving, GeospatialScenario.Resolving.frame().lastDrop?.outcome)
        assertEquals(DropOutcome.Anchored, GeospatialScenario.Anchored.frame().lastDrop?.outcome)
        assertTrue(GeospatialScenario.AnchoredBelow.frame().lastDrop!!.message().contains("below you"))
        assertEquals(DropOutcome.NoDataHere, GeospatialScenario.DropFailed.frame().lastDrop?.outcome)
        assertEquals(GeospatialAnchorMode.Rooftop, GeospatialScenario.Rooftop.frame().mode)
        assertTrue(abs(GeospatialScenario.Rooftop.frame().lastDrop!!.heightDeltaM!!) > 3.0)
    }
}
