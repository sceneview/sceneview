package io.github.sceneview.demo.demos.internal

import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Everything the Geospatial Anchors demo decides, with no ARCore or Android type in sight
 * ([#3832](https://github.com/sceneview/sceneview/issues/3832)).
 *
 * The emulator cannot run ARCore at all (#2754), so the only way to pin this screen's
 * behaviour off-device is to keep every decision here, as pure functions of plain values,
 * and let the composable do nothing but read ARCore and draw the result:
 *
 *  - [GeospatialLocalizationTracker] — the localizing state machine, with the thresholds
 *    and hysteresis of Google's own `hello_geo` sample;
 *  - [GeospatialFrame.overlay] — which one loader (or card) the bottom of the screen shows,
 *    so the camera start is never narrated twice again;
 *  - [GeospatialFrame.statusCard] — the persistent accuracy / coverage readout that used to
 *    be buried in the settings sheet;
 *  - [GeospatialFrame.primaryAction] — the single Drop button and when it is live;
 *  - [DropFeedback.message] — what the user is told after every drop, including where the
 *    anchor actually landed and why a failed one failed;
 *  - [dropPoseAhead] — where a drop lands: a few metres ahead of the camera, facing it,
 *    rather than straight under the phone where nobody can see it.
 */

/** Horizontal accuracy at or under which Earth counts as localized (`hello_geo`). */
const val LOCALIZED_HORIZONTAL_ACCURACY_M: Double = 10.0

/** Heading (yaw) accuracy at or under which Earth counts as localized (`hello_geo`). */
const val LOCALIZED_YAW_ACCURACY_DEG: Double = 15.0

/**
 * How much worse than the localized thresholds accuracy may get before a localized
 * session drops back to "improving". Without it the card flickers between two states
 * every few frames as the estimate breathes around 10 m (`hello_geo` uses the same 10).
 */
const val LOCALIZED_HYSTERESIS_M: Double = 10.0

/** @see LOCALIZED_HYSTERESIS_M */
const val LOCALIZED_HYSTERESIS_DEG: Double = 10.0

/**
 * How long "improving accuracy" may last before the card admits it is not going to get
 * there here and says what to change. `hello_geo` gives up after three minutes; this
 * demo keeps trying afterwards, because stepping outside mid-session does fix it.
 */
const val LOCALIZING_TIMEOUT_MILLIS: Long = 180_000L

/** Accuracy under which the meter shows two segments out of three. */
private const val FAIR_HORIZONTAL_ACCURACY_M = 25.0

/** @see FAIR_HORIZONTAL_ACCURACY_M */
private const val FAIR_YAW_ACCURACY_DEG = 30.0

/** Segments in the accuracy meter — the same three-segment meter as Cloud Anchors. */
const val GEOSPATIAL_ACCURACY_SEGMENTS: Int = 3

/**
 * The two anchor references the demo offers. Declaration order is the dock order, and
 * `DeepLinkRouter.ALIAS_INITIAL_TAB` indexes into it (`ar-rooftop` = 1). Append, never
 * reorder.
 */
enum class GeospatialAnchorMode {
    /** `Earth.resolveAnchorOnTerrainAsync` — glued to the ground. */
    Terrain,

    /** `Earth.resolveAnchorOnRooftopAsync` — on the building at that point, else terrain. */
    Rooftop,
}

/** Where Earth localization stands. */
enum class GeospatialLocalization {
    /** Earth is not tracking yet: no latitude/longitude to anchor against. */
    Pretracking,

    /** Earth is tracking, but accuracy is still above the localized thresholds. */
    Localizing,

    /** Accuracy is within [LOCALIZED_HORIZONTAL_ACCURACY_M] and [LOCALIZED_YAW_ACCURACY_DEG]. */
    Localized,

    /** Still [Localizing] after [LOCALIZING_TIMEOUT_MILLIS] — say what to change. */
    TakingLong,
}

/**
 * The localizing state machine. Immutable: feed it one frame with [update] and keep the
 * result.
 *
 * Transitions, as in `hello_geo`:
 *  - Earth not tracking (or no accuracy yet) → [GeospatialLocalization.Pretracking], from
 *    anywhere;
 *  - accuracy within both thresholds → [GeospatialLocalization.Localized];
 *  - localized, then accuracy worse than threshold + hysteresis on either axis → back to
 *    [GeospatialLocalization.Localizing], with the timeout restarted;
 *  - localizing for longer than [LOCALIZING_TIMEOUT_MILLIS] →
 *    [GeospatialLocalization.TakingLong], which still recovers to localized.
 *
 * @property localizingSinceMillis when the current localizing attempt started, in the
 *   caller's clock; meaningless outside [GeospatialLocalization.Localizing] and
 *   [GeospatialLocalization.TakingLong].
 */
data class GeospatialLocalizationTracker(
    val phase: GeospatialLocalization = GeospatialLocalization.Pretracking,
    val localizingSinceMillis: Long = 0L,
) {
    fun update(
        earthTracking: Boolean,
        horizontalAccuracyM: Double?,
        yawAccuracyDeg: Double?,
        nowMillis: Long,
    ): GeospatialLocalizationTracker {
        if (!earthTracking || horizontalAccuracyM == null || yawAccuracyDeg == null) {
            return GeospatialLocalizationTracker()
        }
        val accurate = horizontalAccuracyM <= LOCALIZED_HORIZONTAL_ACCURACY_M &&
            yawAccuracyDeg <= LOCALIZED_YAW_ACCURACY_DEG
        return when (phase) {
            GeospatialLocalization.Pretracking ->
                if (accurate) {
                    GeospatialLocalizationTracker(GeospatialLocalization.Localized)
                } else {
                    GeospatialLocalizationTracker(GeospatialLocalization.Localizing, nowMillis)
                }

            GeospatialLocalization.Localizing, GeospatialLocalization.TakingLong -> when {
                accurate -> GeospatialLocalizationTracker(GeospatialLocalization.Localized)
                nowMillis - localizingSinceMillis > LOCALIZING_TIMEOUT_MILLIS ->
                    copy(phase = GeospatialLocalization.TakingLong)
                else -> this
            }

            GeospatialLocalization.Localized -> {
                val lost = horizontalAccuracyM >
                    LOCALIZED_HORIZONTAL_ACCURACY_M + LOCALIZED_HYSTERESIS_M ||
                    yawAccuracyDeg > LOCALIZED_YAW_ACCURACY_DEG + LOCALIZED_HYSTERESIS_DEG
                if (lost) {
                    GeospatialLocalizationTracker(GeospatialLocalization.Localizing, nowMillis)
                } else {
                    this
                }
            }
        }
    }
}

/** Google's visual positioning (VPS) coverage at the user's location. */
enum class VpsCoverage {
    /** Not asked yet — Earth has no latitude/longitude to ask about. */
    Unknown,

    /** The coverage check is in flight. */
    Checking,

    /** Street View imagery covers this spot: accuracy can get down to a metre or two. */
    Available,

    /** No coverage: Earth falls back to GPS and compass, typically ±5–20 m. */
    Unavailable,

    /** The check itself failed (network, key, quota). Coverage stays unknown. */
    Error,
}

/** How a drop turned out, in the demo's own words rather than two ARCore enums. */
enum class DropOutcome {
    /** The resolve request is in flight. */
    Resolving,

    /** The anchor resolved and is in the scene. */
    Anchored,

    /** Google has no terrain (or building) data at that spot. */
    NoDataHere,

    /** The API key is not allowed to use the Geospatial API. */
    NotAuthorized,

    /** Anything else — an internal ARCore error, too many anchors, a thrown call. */
    Failed,
}

/**
 * Maps a resolve callback — an `Anchor.TerrainAnchorState` or `Anchor.RooftopAnchorState`,
 * by enum name, since both enums spell the outcomes the same — to a [DropOutcome].
 * `SUCCESS` without a node counts as a failure: there is nothing to show.
 */
fun dropOutcomeOf(stateName: String, hasNode: Boolean): DropOutcome = when (stateName) {
    "SUCCESS" -> if (hasNode) DropOutcome.Anchored else DropOutcome.Failed
    "ERROR_UNSUPPORTED_LOCATION" -> DropOutcome.NoDataHere
    "ERROR_NOT_AUTHORIZED" -> DropOutcome.NotAuthorized
    else -> DropOutcome.Failed
}

/**
 * Feedback for the latest drop.
 *
 * @property horizontalDistanceM ground distance from the camera to the resolved anchor.
 * @property heightDeltaM the anchor's height minus the camera's; negative is below.
 */
data class DropFeedback(
    val mode: GeospatialAnchorMode,
    val outcome: DropOutcome,
    val horizontalDistanceM: Double? = null,
    val heightDeltaM: Double? = null,
)

/** Past this, a resolved anchor is described as below the user rather than in front. */
private const val BELOW_YOU_M = 2.5

/** Past this, a resolved anchor is described as above the user. */
private const val ABOVE_YOU_M = 3.0

/**
 * The one line the status card shows about the latest drop.
 *
 * Every outcome says something (#3832): the old screen said nothing at all on the main
 * view, so a drop that resolved ten metres under the floor looked exactly like a drop that
 * failed. A resolved anchor well below the camera is the normal result indoors — terrain
 * anchors sit on the outdoor ground — so the line says so instead of leaving the user to
 * look for a model that is under their feet.
 */
fun DropFeedback.message(): String = when (outcome) {
    DropOutcome.Resolving -> "Placing your anchor…"
    DropOutcome.Anchored -> anchoredMessage()
    DropOutcome.NoDataHere -> when (mode) {
        GeospatialAnchorMode.Terrain -> "Couldn't anchor: Google has no terrain data at this spot."
        GeospatialAnchorMode.Rooftop ->
            "Couldn't anchor: Google has no building or terrain data at this spot."
    }
    DropOutcome.NotAuthorized -> "Couldn't anchor: this app's API key isn't allowed to use Geospatial."
    DropOutcome.Failed -> "Couldn't anchor this time. Try again in a moment."
}

private fun DropFeedback.anchoredMessage(): String {
    val distance = horizontalDistanceM ?: return "Anchor placed."
    val away = "Anchored ${metres(distance)} away"
    val height = heightDeltaM ?: return "$away."
    return when {
        height <= -BELOW_YOU_M -> when (mode) {
            GeospatialAnchorMode.Terrain -> "$away, ${metres(-height)} below you at street level."
            GeospatialAnchorMode.Rooftop -> "$away, ${metres(-height)} below you."
        }
        height >= ABOVE_YOU_M -> when (mode) {
            GeospatialAnchorMode.Terrain -> "$away, ${metres(height)} above you."
            GeospatialAnchorMode.Rooftop -> "$away, ${metres(height)} above you on the rooftop."
        }
        else -> "$away."
    }
}

/** Whole metres, never "0 m". */
private fun metres(value: Double): String = "${abs(value).roundToInt().coerceAtLeast(1)} m"

/** The accent a line or meter wears: the same three the Cloud Anchor card uses. */
enum class GeospatialTone {
    /** Waiting on the user to move — `warning`. */
    Guidance,

    /** Usable, or work in flight — `primary`. */
    Progress,

    /** Done — `success`. */
    Success,

    /** Failed until something changes — `error`. */
    Blocked,
}

/** Tone of [DropFeedback.message]. */
fun DropFeedback.tone(): GeospatialTone = when (outcome) {
    DropOutcome.Resolving -> GeospatialTone.Progress
    DropOutcome.Anchored -> GeospatialTone.Success
    DropOutcome.NoDataHere, DropOutcome.NotAuthorized, DropOutcome.Failed -> GeospatialTone.Blocked
}

/**
 * One frame of everything the bottom of the screen depends on.
 *
 * @property blocked a configuration blocker is up — session error, missing or rejected key,
 *   quota, no network, Geospatial unsupported. The demo draws the shared banner for it.
 * @property arUnavailable ARCore ruled the device out; the SDK's own card explains it.
 * @property scrimNarrating the full-screen camera scrim is showing its "Starting camera…"
 *   card, so nothing else may say it (#3825).
 * @property hasFix Earth has given a camera latitude/longitude to drop against.
 */
data class GeospatialFrame(
    val mode: GeospatialAnchorMode = GeospatialAnchorMode.Terrain,
    val blocked: Boolean = false,
    val arUnavailable: Boolean = false,
    val cameraReady: Boolean = false,
    val scrimNarrating: Boolean = true,
    val cameraTracking: Boolean = false,
    val localization: GeospatialLocalization = GeospatialLocalization.Pretracking,
    val hasFix: Boolean = false,
    val horizontalAccuracyM: Double? = null,
    val yawAccuracyDeg: Double? = null,
    val vps: VpsCoverage = VpsCoverage.Unknown,
    val lastDrop: DropFeedback? = null,
)

/** What the bottom of the screen shows. Exactly one of these — never two loaders. */
enum class GeospatialOverlay {
    /** Nothing: the camera scrim or the SDK's availability card owns the screen. */
    Silent,

    /** A configuration blocker: the shared Cloud-service or session-error banner. */
    Blocker,

    /** The camera start outlived the scrim's card — one pill takes the line over. */
    StartingCamera,

    /**
     * The persistent status card and the Drop button, from the first camera frame on.
     * Motion tracking starting up is one of the card's states rather than a pill of its
     * own, so the bottom of the screen never swaps one surface for another mid-start.
     */
    Status,
}

/** The single overlay for this frame. */
fun GeospatialFrame.overlay(): GeospatialOverlay = when {
    blocked -> GeospatialOverlay.Blocker
    arUnavailable -> GeospatialOverlay.Silent
    !cameraReady && scrimNarrating -> GeospatialOverlay.Silent
    !cameraReady -> GeospatialOverlay.StartingCamera
    else -> GeospatialOverlay.Status
}

/**
 * The persistent localization card.
 *
 * @property indicator the glyph leading the title.
 * @property title what is happening, in a few words.
 * @property accuracy "±4 m position · ±9° heading", once Earth reports it.
 * @property meterSegments how many of [GEOSPATIAL_ACCURACY_SEGMENTS] are lit.
 * @property meterTone the lit segments' accent.
 * @property coverage one line about visual positioning coverage, when known.
 * @property hint what to do to get a better lock, when accuracy is not there yet.
 * @property drop the latest drop's line, if any.
 */
data class GeospatialStatusCard(
    val indicator: GeospatialIndicator,
    val title: String,
    val accuracy: String?,
    val meterSegments: Int,
    val meterTone: GeospatialTone,
    val coverage: String?,
    val hint: String?,
    val drop: DropFeedback?,
)

/** The glyph leading the card title, one per localization situation. */
enum class GeospatialIndicator {
    /** Waiting on Earth, nothing for the user to change yet — a spinner. */
    Working,

    /** The user can improve things by moving — the move-your-device glyph. */
    Move,

    /** Localized — a check. */
    Done,
}

/** Filled accuracy-meter segments for this frame, once ARCore is tracking. */
private fun GeospatialFrame.meterSegments(): Int = when (localization) {
    GeospatialLocalization.Pretracking -> 0
    GeospatialLocalization.Localized -> GEOSPATIAL_ACCURACY_SEGMENTS
    // Hysteresis can hold "improving" while the estimate is already inside the
    // thresholds; the meter must not claim a lock the title does not.
    GeospatialLocalization.Localizing, GeospatialLocalization.TakingLong ->
        accuracySegments(horizontalAccuracyM, yawAccuracyDeg)
            .coerceAtMost(GEOSPATIAL_ACCURACY_SEGMENTS - 1)
            .coerceAtLeast(1)
}

/** The card for this frame. Only meaningful when [overlay] is [GeospatialOverlay.Status]. */
fun GeospatialFrame.statusCard(): GeospatialStatusCard {
    if (!cameraTracking) {
        // Earth cannot localize a phone ARCore is not tracking, so nothing below applies
        // yet. Also the state after a tracking loss mid-session, so the title must not
        // say "starting".
        return GeospatialStatusCard(
            indicator = GeospatialIndicator.Working,
            title = "Sensing your surroundings…",
            accuracy = null,
            meterSegments = 0,
            meterTone = GeospatialTone.Guidance,
            coverage = null,
            hint = "Move your phone slowly.",
            drop = lastDrop,
        )
    }
    val segments = meterSegments()
    val tone = when (segments) {
        GEOSPATIAL_ACCURACY_SEGMENTS -> GeospatialTone.Success
        GEOSPATIAL_ACCURACY_SEGMENTS - 1 -> GeospatialTone.Progress
        else -> GeospatialTone.Guidance
    }
    return GeospatialStatusCard(
        indicator = when (localization) {
            GeospatialLocalization.Pretracking -> GeospatialIndicator.Working
            GeospatialLocalization.Localizing, GeospatialLocalization.TakingLong ->
                GeospatialIndicator.Move
            GeospatialLocalization.Localized -> GeospatialIndicator.Done
        },
        title = when (localization) {
            GeospatialLocalization.Pretracking -> "Finding your location…"
            GeospatialLocalization.Localizing -> "Improving accuracy…"
            GeospatialLocalization.Localized -> "Location locked"
            GeospatialLocalization.TakingLong -> "Accuracy is still low here"
        },
        accuracy = if (localization == GeospatialLocalization.Pretracking) {
            null
        } else {
            accuracyLabel(horizontalAccuracyM, yawAccuracyDeg)
        },
        meterSegments = segments,
        meterTone = tone,
        coverage = when (vps) {
            VpsCoverage.Unknown, VpsCoverage.Error -> null
            VpsCoverage.Checking -> "Checking Street View coverage…"
            VpsCoverage.Available -> "Street View coverage here: best accuracy."
            VpsCoverage.Unavailable -> "No Street View coverage here: GPS and compass only."
        },
        hint = when (localization) {
            GeospatialLocalization.Pretracking ->
                "Point your phone at the buildings and street around you."
            GeospatialLocalization.Localizing ->
                "Go outside and slowly pan across nearby buildings."
            GeospatialLocalization.TakingLong ->
                "Geospatial works outdoors. Step outside and look at building fronts."
            GeospatialLocalization.Localized -> null
        },
        drop = lastDrop,
    )
}

/**
 * Meter segments for a raw accuracy pair: three within the localized thresholds, two
 * within [FAIR_HORIZONTAL_ACCURACY_M] / [FAIR_YAW_ACCURACY_DEG], one otherwise, none
 * without a reading.
 */
fun accuracySegments(horizontalAccuracyM: Double?, yawAccuracyDeg: Double?): Int = when {
    horizontalAccuracyM == null || yawAccuracyDeg == null -> 0
    horizontalAccuracyM <= LOCALIZED_HORIZONTAL_ACCURACY_M &&
        yawAccuracyDeg <= LOCALIZED_YAW_ACCURACY_DEG -> 3
    horizontalAccuracyM <= FAIR_HORIZONTAL_ACCURACY_M &&
        yawAccuracyDeg <= FAIR_YAW_ACCURACY_DEG -> 2
    else -> 1
}

/** "±4 m position · ±9° heading", or `null` without a reading. */
fun accuracyLabel(horizontalAccuracyM: Double?, yawAccuracyDeg: Double?): String? {
    if (horizontalAccuracyM == null || yawAccuracyDeg == null) return null
    val position = if (horizontalAccuracyM < 10.0) {
        String.format(Locale.US, "%.1f", horizontalAccuracyM)
    } else {
        horizontalAccuracyM.roundToInt().toString()
    }
    return "±$position m position · ±${yawAccuracyDeg.roundToInt()}° heading"
}

/**
 * The one primary action.
 *
 * @property reason why it is disabled, for its accessible description.
 */
data class GeospatialPrimaryAction(
    val label: String,
    val enabled: Boolean,
    val reason: String?,
)

/**
 * Live as soon as Earth gives a fix — not only once localized. Gating on the 10 m lock
 * would make the demo a dead end indoors, where the fix rarely gets there; instead the
 * card says accuracy is low and every drop reports where it landed. The anchor still
 * appears where it was dropped at the time of the drop, because the same estimate turns
 * it into a latitude/longitude and back; low accuracy shows up as drift afterwards.
 */
fun GeospatialFrame.primaryAction(): GeospatialPrimaryAction {
    val label = when (mode) {
        GeospatialAnchorMode.Terrain -> "Drop anchor"
        GeospatialAnchorMode.Rooftop -> "Drop on rooftop"
    }
    val ready = cameraTracking && localization != GeospatialLocalization.Pretracking && hasFix
    return GeospatialPrimaryAction(
        label = label,
        enabled = ready,
        reason = if (ready) null else "Waiting for your location",
    )
}

/**
 * The blocker line for an `Earth.EarthState` that is neither `ENABLED` nor one of the
 * shared Cloud-service failures (key rejected, quota), by enum name; `null` for states
 * that are not errors.
 *
 * Before #3832 these states left the main view saying nothing useful, with the actual
 * state only in the settings sheet as `Earth not ready (state: …)`.
 */
fun earthErrorMessage(earthStateName: String?): String? = when (earthStateName) {
    null, "ENABLED", "ERROR_NOT_AUTHORIZED", "ERROR_RESOURCE_EXHAUSTED" -> null
    "ERROR_APK_VERSION_TOO_OLD" ->
        "Update Google Play Services for AR to use Geospatial anchors."
    "ERROR_GEOSPATIAL_MODE_DISABLED" ->
        "Geospatial is turned off for this session. Leave the demo and open it again."
    else -> "Geospatial stopped on an internal error. Leave the demo and open it again."
}

/**
 * A pose in the AR session's world frame: position plus a rotation quaternion.
 */
data class DropPose(
    val x: Float,
    val y: Float,
    val z: Float,
    val qx: Float,
    val qy: Float,
    val qz: Float,
    val qw: Float,
)

/** How far ahead of the camera a terrain drop lands: close enough to see the model. */
const val TERRAIN_DROP_DISTANCE_M: Float = 4f

/**
 * How far ahead of the camera a rooftop drop lands: across a street, where the building
 * the user is pointing at usually stands. Closer than that lands on the pavement, and a
 * rooftop anchor with no building under it falls back to terrain.
 */
const val ROOFTOP_DROP_DISTANCE_M: Float = 12f

/** Where the provisional pin sits relative to a phone held at chest height. */
const val PROVISIONAL_PIN_DROP_M: Float = 1.3f

/**
 * Where a drop lands: [distance] metres ahead of the camera on the horizontal plane,
 * [below] metres under it, rotated about the vertical so the model's front (+Z) faces
 * back toward the camera.
 *
 * The old demo resolved every anchor at the camera's own latitude/longitude — straight
 * under the phone, out of the frame — with an identity rotation, so the model faced south
 * whatever way the user stood. Converting this pose with `Earth.getGeospatialPose` gives
 * both the latitude/longitude and the east-up-south rotation to resolve with.
 *
 * ARCore cameras look down their −Z axis. When the phone points straight down or up the
 * horizontal part of that is ~0, so the top edge of the screen (the camera's +Y) is used
 * as "ahead" instead.
 *
 * @param cameraZAxis the camera's +Z axis in world space (`Pose.getZAxis()`).
 * @param cameraYAxis the camera's +Y axis in world space (`Pose.getYAxis()`).
 */
fun dropPoseAhead(
    cameraX: Float,
    cameraY: Float,
    cameraZ: Float,
    cameraZAxis: FloatArray,
    cameraYAxis: FloatArray,
    distance: Float,
    below: Float,
): DropPose {
    var forwardX = -cameraZAxis[0]
    var forwardZ = -cameraZAxis[2]
    var length = sqrt(forwardX * forwardX + forwardZ * forwardZ)
    if (length < 0.1f) {
        forwardX = cameraYAxis[0]
        forwardZ = cameraYAxis[2]
        length = sqrt(forwardX * forwardX + forwardZ * forwardZ)
    }
    if (length < 1e-4f) {
        // Degenerate (no usable horizontal direction at all): drop along world −Z.
        forwardX = 0f
        forwardZ = -1f
        length = 1f
    }
    forwardX /= length
    forwardZ /= length
    // Rotating +Z by θ about +Y gives (sin θ, 0, cos θ); the model must face -forward.
    val theta = atan2(-forwardX, -forwardZ)
    return DropPose(
        x = cameraX + forwardX * distance,
        y = cameraY - below,
        z = cameraZ + forwardZ * distance,
        qx = 0f,
        qy = sin(theta / 2f),
        qz = 0f,
        qw = cos(theta / 2f),
    )
}

/**
 * Named visual states of the screen, so QA can capture each one on `emulator-5554`, which
 * cannot run ARCore (#2754), with `--ez qa_mode true --es qa_state <name>`.
 */
enum class GeospatialScenario {
    /** Camera live, the phone is not tracking its own motion yet. Drop disabled. */
    Starting,

    /** Tracking, Earth has no fix yet. Drop disabled. */
    Finding,

    /** Earth has a fix, accuracy still low, no Street View coverage. */
    Improving,

    /** Localized, Street View coverage, nothing dropped yet. */
    Locked,

    /** Still not localized after the timeout. */
    TakingLong,

    /** A drop in flight. */
    Resolving,

    /** A drop resolved a few metres ahead. */
    Anchored,

    /** A drop resolved at street level, well below an upper-floor user. */
    AnchoredBelow,

    /** A drop failed: no terrain data. */
    DropFailed,

    /** Rooftop mode, localized, anchored on a rooftop above the user. */
    Rooftop,
}

/** `"anchored-below"`, `"anchored_below"` and `"AnchoredBelow"` all name the same state. */
fun geospatialScenarioOf(name: String?): GeospatialScenario? {
    val wanted = name?.replace("-", "")?.replace("_", "")?.lowercase() ?: return null
    return GeospatialScenario.entries.firstOrNull { it.name.lowercase() == wanted }
}

/** The frame [this] stands for. Pure, so a capture cannot drift from its name. */
fun GeospatialScenario.frame(): GeospatialFrame {
    val live = GeospatialFrame(cameraReady = true, scrimNarrating = false, cameraTracking = true)
    val locked = live.copy(
        localization = GeospatialLocalization.Localized,
        hasFix = true,
        horizontalAccuracyM = 2.4,
        yawAccuracyDeg = 6.0,
        vps = VpsCoverage.Available,
    )
    val terrain = GeospatialAnchorMode.Terrain
    return when (this) {
        GeospatialScenario.Starting -> live.copy(cameraTracking = false)
        GeospatialScenario.Finding -> live
        GeospatialScenario.Improving -> live.copy(
            localization = GeospatialLocalization.Localizing,
            hasFix = true,
            horizontalAccuracyM = 18.0,
            yawAccuracyDeg = 24.0,
            vps = VpsCoverage.Unavailable,
        )
        GeospatialScenario.Locked -> locked
        GeospatialScenario.TakingLong -> live.copy(
            localization = GeospatialLocalization.TakingLong,
            hasFix = true,
            horizontalAccuracyM = 42.0,
            yawAccuracyDeg = 38.0,
            vps = VpsCoverage.Unavailable,
        )
        GeospatialScenario.Resolving -> locked.copy(
            lastDrop = DropFeedback(terrain, DropOutcome.Resolving),
        )
        GeospatialScenario.Anchored -> locked.copy(
            lastDrop = DropFeedback(terrain, DropOutcome.Anchored, 4.0, -1.2),
        )
        GeospatialScenario.AnchoredBelow -> locked.copy(
            lastDrop = DropFeedback(terrain, DropOutcome.Anchored, 4.0, -9.4),
        )
        GeospatialScenario.DropFailed -> locked.copy(
            lastDrop = DropFeedback(terrain, DropOutcome.NoDataHere),
        )
        GeospatialScenario.Rooftop -> locked.copy(
            mode = GeospatialAnchorMode.Rooftop,
            lastDrop = DropFeedback(GeospatialAnchorMode.Rooftop, DropOutcome.Anchored, 12.0, 14.0),
        )
    }
}
