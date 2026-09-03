package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.common.DemoStatusTone

/**
 * The headless core of the Cloud Anchor demo's two-step flow
 * ([#3421](https://github.com/sceneview/sceneview/issues/3421)).
 *
 * The screen this replaces kept its state in nine loose `remember`d variables and
 * derived what to show from a `when` chain that could not see them all at once. Three
 * consequences shipped:
 *
 *  - **Progress was invisible.** `onHost` set `statusMessage = "Hosting anchor…"`, but
 *    the banner's `when` reached `!isAnchorReady` first and unconditionally answered
 *    "Anchor placed — tap Host to share it to the cloud". Tapping Host changed nothing
 *    on screen until the request came back, seconds later. Resolve had the mirror bug.
 *  - **Severity was decided by substring.** `statusMessage.contains("failed")` chose
 *    between the Blocked and Progress tone, so the tone of a state depended on the
 *    English wording of an unrelated sentence.
 *  - **Actions were offered that could not work.** Host stayed enabled with no anchor
 *    placed, stayed enabled after a successful *resolve* (offering to host an anchor
 *    that is already hosted), and was enabled with an unmapped room — the single most
 *    common real-world cause of `ERROR_HOSTING_DATASET_PROCESSING_FAILED`.
 *
 * Everything the screen shows is now a pure function of one [CloudAnchorFlowState]:
 * the status sentence and its tone ([status]), which card occupies the bottom band
 * ([card]), and which buttons exist and whether each is tappable ([actionBar]). No
 * ARCore or Android type appears in this file, so `CloudAnchorFlowTest` pins all of it
 * on the JVM — which matters more here than for most demos, because `emulator-5554`
 * cannot run ARCore at all (#2754) and the real state machine is therefore only ever
 * exercised on a physical device.
 */

/** How long [hostCloudAnchor] keeps a hosted anchor alive. Mirrors the demo's `host(ttlDays = …)`. */
const val CLOUD_ANCHOR_TTL_DAYS: Int = 1

/**
 * Which half of the two-step flow the screen is on.
 *
 * The old screen had no mode: Host and Resolve were peer buttons over one shared
 * anchor slot, so "host an anchor" and "resolve someone else's" competed for the same
 * pixels and the same `localAnchor`. They are sequential tasks with different inputs,
 * different failures and different outputs, so they are now two steps the user picks
 * between explicitly.
 */
enum class CloudAnchorStep {
    /** Place an anchor, map the room, upload it, share the code. */
    Host,

    /** Paste a code someone shared, resolve it, look at the anchor. */
    Resolve,
}

/**
 * Room-mapping quality for hosting, mirroring `Session.FeatureMapQuality`.
 *
 * Duplicated rather than imported so this file stays free of ARCore types; the demo
 * maps the real enum across in one `when`. The old screen ignored feature-map quality
 * entirely and let the user host from a standing start, which is why hosting "just
 * failed" so often.
 */
enum class RoomQuality {
    /** Not enough visual detail around the anchor. Hosting from here usually fails. */
    Insufficient,

    /** Enough to host, though the resolve match will be less reliable. */
    Sufficient,

    /** Well mapped from several angles. */
    Good,
}

/**
 * Why no Cloud Anchor call on this build can succeed, independent of what the user does
 * on screen.
 *
 * Mirrors the shared `CloudServiceStatus` (#3262) minus the Geospatial-only member, so
 * the flow can reason about it without a Compose or ARCore dependency.
 */
enum class CloudAnchorBlocker {
    /** No `com.google.android.ar.API_KEY` in the manifest — this build was never wired up. */
    ApiKeyMissing,

    /** A key is present and Google Cloud refused it, almost always an SHA-1 restriction (#3185). */
    ApiKeyRejected,

    /** The Cloud project's ARCore API quota is spent. */
    QuotaExhausted,

    /** No usable network. Unlike the three above, this one clears itself. */
    NoNetwork,

    /** ARCore cannot start a session here at all — the SDK draws its own card (#3374). */
    ArUnavailable,
}

/**
 * `true` when the blocker is a build or project misconfiguration: nothing the user does
 * on this screen will clear it, so the screen owes them an explanation card rather than
 * a coaching pill over controls that cannot work (the #3374 rule).
 *
 * [CloudAnchorBlocker.NoNetwork] is deliberately excluded — it resolves the moment Wi-Fi
 * comes back, so it stays a transient banner and the flow stays visible behind it.
 * [CloudAnchorBlocker.ArUnavailable] is excluded too: `ARSceneView` already draws
 * `ARCoreAvailabilityOverlay` over the viewport, and a second card would cover the SDK's
 * own, better-informed one.
 */
val CloudAnchorBlocker.needsExplanationCard: Boolean
    get() = this == CloudAnchorBlocker.ApiKeyMissing ||
        this == CloudAnchorBlocker.ApiKeyRejected ||
        this == CloudAnchorBlocker.QuotaExhausted

/**
 * The error half of `Anchor.CloudAnchorState`, collapsed to the cases that deserve
 * different words.
 *
 * The old screen printed the raw ARCore constant — "Hosting failed:
 * ERROR_HOSTING_DATASET_PROCESSING_FAILED" — straight into the coaching pill. That is a
 * log line, not copy, and it told the user nothing about what to do next.
 */
enum class CloudAnchorFailure {
    /** `ERROR_NOT_AUTHORIZED` — the key is present and refused. */
    NotAuthorized,

    /** `ERROR_RESOURCE_EXHAUSTED` — project quota spent. */
    QuotaExhausted,

    /** `ERROR_CLOUD_ID_NOT_FOUND` — wrong code, or the anchor's TTL has run out. */
    CodeNotFound,

    /** `ERROR_HOSTING_DATASET_PROCESSING_FAILED` — the room was not mapped well enough. */
    RoomNotMapped,

    /** `ERROR_RESOLVING_LOCALIZATION_NO_MATCH` — right code, wrong place. */
    NoMatchHere,

    /** Either `ERROR_*_SERVICE_UNAVAILABLE` — the service could not be reached. */
    ServiceUnavailable,

    /** `ERROR_RESOLVING_SDK_VERSION_TOO_OLD` / `_TOO_NEW` — an ARCore version mismatch. */
    VersionMismatch,

    /** `ERROR_INTERNAL` and anything ARCore adds after this was written. */
    Internal,
}

/**
 * Maps an `Anchor.CloudAnchorState` constant *by name* to the failure it represents.
 *
 * Taking the name rather than the enum is what keeps this testable: `Anchor` is an
 * ARCore type, and a JVM unit test that touches it loads native-backed classes. The
 * demo calls `cloudAnchorFailureOf(state.name)` at the one place the callback lands.
 *
 * Every non-error constant (`NONE`, `TASK_IN_PROGRESS`, `SUCCESS`) and every future
 * constant maps to [CloudAnchorFailure.Internal] — an honest "it failed, try again"
 * beats leaking a symbol nobody outside ARCore can read.
 */
fun cloudAnchorFailureOf(arCoreStateName: String): CloudAnchorFailure = when (arCoreStateName) {
    "ERROR_NOT_AUTHORIZED" -> CloudAnchorFailure.NotAuthorized
    "ERROR_RESOURCE_EXHAUSTED" -> CloudAnchorFailure.QuotaExhausted
    "ERROR_CLOUD_ID_NOT_FOUND" -> CloudAnchorFailure.CodeNotFound
    "ERROR_HOSTING_DATASET_PROCESSING_FAILED" -> CloudAnchorFailure.RoomNotMapped
    "ERROR_RESOLVING_LOCALIZATION_NO_MATCH" -> CloudAnchorFailure.NoMatchHere
    "ERROR_SERVICE_UNAVAILABLE" -> CloudAnchorFailure.ServiceUnavailable
    "ERROR_HOSTING_SERVICE_UNAVAILABLE" -> CloudAnchorFailure.ServiceUnavailable
    "ERROR_RESOLVING_SDK_VERSION_TOO_OLD" -> CloudAnchorFailure.VersionMismatch
    "ERROR_RESOLVING_SDK_VERSION_TOO_NEW" -> CloudAnchorFailure.VersionMismatch
    else -> CloudAnchorFailure.Internal
}

/**
 * One Cloud Anchor request's lifecycle — ARCore's own four outcomes, made explicit.
 *
 * The old screen had no such type: an in-flight host was inferred from a String
 * containing "Hosting anchor…", which nothing on screen ever read.
 */
sealed interface CloudAnchorTask {
    /** Never started, or reset. */
    data object Idle : CloudAnchorTask

    /** `TASK_IN_PROGRESS` — the request is with the service. */
    data object Running : CloudAnchorTask

    /** `SUCCESS`, carrying the Cloud Anchor id the request produced or consumed. */
    data class Succeeded(val code: String) : CloudAnchorTask

    /** One of the `ERROR_*` states. */
    data class Failed(val failure: CloudAnchorFailure) : CloudAnchorTask
}

/**
 * Everything the Cloud Anchor screen knows, in one value.
 *
 * @property step which half of the flow is showing.
 * @property blocker why no Cloud call can succeed at all, or `null`.
 * @property tracking ARCore's camera is tracking.
 * @property anchorPlaced the user has tapped a plane in the Host step.
 * @property roomQuality ARCore's feature-map estimate around the placed anchor.
 * @property host the host request's lifecycle.
 * @property resolve the resolve request's lifecycle.
 * @property codeInput the Resolve step's text field, verbatim (untrimmed).
 * @property trackingHint the concrete reason ARCore gives for not tracking ("It's too
 *   dark", "Move the device more slowly"), when it has one. Replaces the generic
 *   not-tracking sentence — a specific instruction always beats "move slowly". Passed in
 *   already resolved rather than as an enum so this file stays free of ARCore types;
 *   `common/TrackingFailureMessages.kt` owns that wording for all 28 AR demos.
 */
data class CloudAnchorFlowState(
    val step: CloudAnchorStep = CloudAnchorStep.Host,
    val blocker: CloudAnchorBlocker? = null,
    val tracking: Boolean = false,
    val anchorPlaced: Boolean = false,
    val roomQuality: RoomQuality = RoomQuality.Insufficient,
    val host: CloudAnchorTask = CloudAnchorTask.Idle,
    val resolve: CloudAnchorTask = CloudAnchorTask.Idle,
    val codeInput: String = "",
    val trackingHint: String? = null,
)

/**
 * The not-tracking sentence: ARCore's concrete reason when it has one, the generic
 * instruction otherwise.
 *
 * The tone moves with it. A named reason ("It's too dark") is [DemoStatusTone.Guidance] —
 * the user has something specific to do. A plain cold start is [DemoStatusTone.Progress],
 * because waiting is the correct behaviour there and a warning icon would overstate it.
 */
private fun CloudAnchorFlowState.notTrackingStatus(): CloudAnchorStatus =
    if (trackingHint != null) {
        CloudAnchorStatus(trackingHint, DemoStatusTone.Guidance)
    } else {
        CloudAnchorStatus("Move the phone slowly to start tracking.", DemoStatusTone.Progress)
    }

/** Every action the screen can offer. [allows] decides which of them are live right now. */
enum class CloudAnchorAction {
    /** Tap a detected plane to drop the anchor to be hosted. */
    PlaceAnchor,

    /** Upload the placed anchor and get a code back. */
    Host,

    /** Put the hosted code on the clipboard. */
    CopyCode,

    /** Hand the hosted code to the system share sheet. */
    ShareCode,

    /** Fill the Resolve field from the clipboard. */
    PasteCode,

    /** Look up the code in the field and place its anchor. */
    Resolve,

    /** Drop the anchor and both requests, back to an empty Host step. */
    Restart,

    /** Flip between the Host and Resolve steps. */
    SwitchStep,
}

/** The code in [codeInput] with surrounding whitespace removed — what a paste usually needs. */
val CloudAnchorFlowState.trimmedCode: String get() = codeInput.trim()

/**
 * Whether [action] can do anything right now.
 *
 * This is the answer to the issue's "buttons enabled when they cannot work". Two rules
 * carry most of it:
 *
 *  - **A configuration blocker disables everything that touches the cloud, and placing
 *    too.** The old screen kept letting the user place anchors while the banner said the
 *    API key was missing, so they built up a scene that could never be hosted.
 *  - **Host needs a mapped room.** `RoomQuality.Insufficient` is ARCore telling us the
 *    upload will most likely be rejected; the button waits instead, and the status line
 *    says why.
 */
fun CloudAnchorFlowState.allows(action: CloudAnchorAction): Boolean {
    // A blocker that needs a card takes the whole screen: nothing behind it is live.
    // NoNetwork and ArUnavailable fall through to the per-action rules below, because
    // both can clear on their own and neither should tear the flow down.
    if (blocker?.needsExplanationCard == true) return false
    val cloudReachable = blocker == null
    return when (action) {
        CloudAnchorAction.PlaceAnchor ->
            cloudReachable &&
                step == CloudAnchorStep.Host &&
                tracking &&
                !anchorPlaced &&
                host == CloudAnchorTask.Idle

        CloudAnchorAction.Host ->
            cloudReachable &&
                step == CloudAnchorStep.Host &&
                anchorPlaced &&
                roomQuality != RoomQuality.Insufficient &&
                host !is CloudAnchorTask.Succeeded &&
                host != CloudAnchorTask.Running

        // Both only exist once there is a code to hand over. The old sheet told the user
        // to "copy it" and offered no way to.
        CloudAnchorAction.CopyCode, CloudAnchorAction.ShareCode ->
            host is CloudAnchorTask.Succeeded

        CloudAnchorAction.PasteCode ->
            step == CloudAnchorStep.Resolve &&
                resolve != CloudAnchorTask.Running &&
                resolve !is CloudAnchorTask.Succeeded

        CloudAnchorAction.Resolve ->
            cloudReachable &&
                step == CloudAnchorStep.Resolve &&
                tracking &&
                trimmedCode.isNotEmpty() &&
                resolve != CloudAnchorTask.Running &&
                resolve !is CloudAnchorTask.Succeeded

        // Restart is a local reset — it stays available under a transient blocker, and is
        // the way out of a failed request. It is pointless on a screen nothing has touched.
        CloudAnchorAction.Restart ->
            anchorPlaced ||
                host != CloudAnchorTask.Idle ||
                resolve != CloudAnchorTask.Idle ||
                codeInput.isNotEmpty()

        // Switching mid-request would abandon a call that is already billing.
        CloudAnchorAction.SwitchStep ->
            host != CloudAnchorTask.Running && resolve != CloudAnchorTask.Running
    }
}

/**
 * Which leading indicator the coaching pill shows.
 *
 * [DemoStatusTone] alone cannot say this. A completed host and "walk around the anchor"
 * are both [DemoStatusTone.Guidance] — the user has something to do next in each case —
 * but the tone's default glyph is a *move-the-device* icon, so "Hosted. Share the code…"
 * shipped under a rotate-your-phone symbol. An enum rather than an `ImageVector` keeps
 * this file free of Compose types, so the whole flow stays JVM-testable.
 */
enum class CloudAnchorStatusIcon {
    /** Whatever the tone's own indicator is. */
    Default,

    /** A step that just completed — a check, never a coaching glyph. */
    Success,
}

/** The one sentence the coaching pill shows, how loud it is, and what leads it. */
data class CloudAnchorStatus(
    val text: String,
    val tone: DemoStatusTone,
    val icon: CloudAnchorStatusIcon = CloudAnchorStatusIcon.Default,
)

/**
 * The complete sentence for a failed request. Short, in the app's voice, and it always
 * names the next move — never the ARCore constant.
 */
fun CloudAnchorFailure.message(): String = when (this) {
    CloudAnchorFailure.NotAuthorized ->
        "Google Cloud rejected this build's ARCore key."
    CloudAnchorFailure.QuotaExhausted ->
        "This project's ARCore Cloud quota is used up."
    CloudAnchorFailure.CodeNotFound ->
        "No anchor for that code. Codes expire after $CLOUD_ANCHOR_TTL_DAYS day."
    CloudAnchorFailure.RoomNotMapped ->
        "Not enough room detail to host. Restart and map more of the room."
    CloudAnchorFailure.NoMatchHere ->
        "That anchor is somewhere else. Stand where it was hosted."
    CloudAnchorFailure.ServiceUnavailable ->
        "Cloud Anchor service unreachable. Try again."
    CloudAnchorFailure.VersionMismatch ->
        "Update Google Play Services for AR, then try again."
    CloudAnchorFailure.Internal ->
        "Cloud Anchor request failed. Try again."
}

/** The one-line form of a blocker, for the coaching pill. [CloudAnchorBlockedCopy] is the card form. */
fun CloudAnchorBlocker.message(): String = when (this) {
    CloudAnchorBlocker.ApiKeyMissing -> "This build has no ARCore Cloud API key."
    CloudAnchorBlocker.ApiKeyRejected -> "Google Cloud rejected this build's ARCore key."
    CloudAnchorBlocker.QuotaExhausted -> "This project's ARCore Cloud quota is used up."
    CloudAnchorBlocker.NoNetwork -> "No network. Cloud Anchors need an internet connection."
    CloudAnchorBlocker.ArUnavailable -> "AR is unavailable on this device."
}

/**
 * The status sentence for [this], in strict priority order.
 *
 * A blocker outranks everything. Within a step, a live request outranks the coaching
 * that led to it — the fix for the invisible-progress bug: "Hosting the anchor…" is now
 * derived from `host == Running`, not from a String nothing read.
 */
fun CloudAnchorFlowState.status(): CloudAnchorStatus {
    blocker?.let { return CloudAnchorStatus(it.message(), DemoStatusTone.Blocked) }
    return when (step) {
        CloudAnchorStep.Host -> hostStatus()
        CloudAnchorStep.Resolve -> resolveStatus()
    }
}

private fun CloudAnchorFlowState.hostStatus(): CloudAnchorStatus = when {
    host == CloudAnchorTask.Running ->
        CloudAnchorStatus("Hosting the anchor…", DemoStatusTone.Progress)
    host is CloudAnchorTask.Failed ->
        CloudAnchorStatus(host.failure.message(), DemoStatusTone.Blocked)
    host is CloudAnchorTask.Succeeded -> CloudAnchorStatus(
        "Hosted. Share the code to open it elsewhere.",
        DemoStatusTone.Guidance,
        CloudAnchorStatusIcon.Success,
    )
    !tracking -> notTrackingStatus()
    !anchorPlaced ->
        CloudAnchorStatus("Tap a surface to place the anchor.", DemoStatusTone.Guidance)
    roomQuality == RoomQuality.Insufficient ->
        CloudAnchorStatus("Walk around the anchor to map the room.", DemoStatusTone.Guidance)
    roomQuality == RoomQuality.Sufficient ->
        CloudAnchorStatus("Good enough to host. More mapping resolves better.", DemoStatusTone.Guidance)
    else ->
        CloudAnchorStatus("Room mapped. Tap Host.", DemoStatusTone.Guidance)
}

private fun CloudAnchorFlowState.resolveStatus(): CloudAnchorStatus = when {
    resolve == CloudAnchorTask.Running ->
        CloudAnchorStatus("Resolving the code…", DemoStatusTone.Progress)
    resolve is CloudAnchorTask.Failed ->
        CloudAnchorStatus(resolve.failure.message(), DemoStatusTone.Blocked)
    resolve is CloudAnchorTask.Succeeded -> CloudAnchorStatus(
        "Resolved. Look around to find the anchor.",
        DemoStatusTone.Guidance,
        CloudAnchorStatusIcon.Success,
    )
    !tracking -> notTrackingStatus()
    trimmedCode.isEmpty() ->
        CloudAnchorStatus("Paste a code shared from another device.", DemoStatusTone.Guidance)
    else ->
        CloudAnchorStatus("Stand where it was hosted, then tap Resolve.", DemoStatusTone.Guidance)
}

/** Title and body of the explanation card for a configuration blocker. */
data class CloudAnchorBlockedCopy(val title: String, val body: String)

/**
 * The card copy for a blocker that will not clear on its own, or `null` for one that will.
 *
 * Same shape as the SDK's `ARCoreAvailabilityOverlay` (#3374): a title, one explanatory
 * sentence, and **no action** — every one of these is fixed off the device, so a button
 * here would do nothing.
 */
fun CloudAnchorBlocker.blockedCopy(): CloudAnchorBlockedCopy? = when (this) {
    CloudAnchorBlocker.ApiKeyMissing -> CloudAnchorBlockedCopy(
        title = "Cloud Anchors not configured",
        body = "This build has no ARCore Cloud API key, so anchors cannot be hosted or " +
            "resolved. Set ARCORE_API_KEY in local.properties and rebuild.",
    )
    CloudAnchorBlocker.ApiKeyRejected -> CloudAnchorBlockedCopy(
        title = "ARCore key rejected",
        body = "Google Cloud refused this build's ARCore API key. Check that the key's " +
            "SHA-1 restriction matches the certificate that signed this app.",
    )
    CloudAnchorBlocker.QuotaExhausted -> CloudAnchorBlockedCopy(
        title = "Cloud quota used up",
        body = "This project's ARCore Cloud quota is spent. Check the ARCore API quotas " +
            "in the Google Cloud Console.",
    )
    CloudAnchorBlocker.NoNetwork -> null
    CloudAnchorBlocker.ArUnavailable -> null
}

/** What occupies the bottom band above the status pill. Exactly one of these, ever. */
sealed interface CloudAnchorCard {
    /** Nothing to show — the status pill is carrying the whole message. */
    data object None : CloudAnchorCard

    /** A configuration blocker's explanation, in the #3374 card shape. */
    data class Blocked(val copy: CloudAnchorBlockedCopy) : CloudAnchorCard

    /** Live feature-map feedback while the user maps the room around a placed anchor. */
    data class RoomMapping(val quality: RoomQuality) : CloudAnchorCard

    /** The hosted code, ready to copy or share, with the days it stays alive. */
    data class HostedCode(val code: String, val ttlDays: Int = CLOUD_ANCHOR_TTL_DAYS) : CloudAnchorCard

    /** The Resolve step's editable code field. */
    data class ResolveInput(val code: String) : CloudAnchorCard

    /** The code that was successfully resolved, read-only. */
    data class ResolvedCode(val code: String) : CloudAnchorCard
}

/**
 * Which card the bottom band shows.
 *
 * One card at a time, chosen here rather than by three independent `if`s in the
 * composable — that is what let the old screen show its "Cloud Anchor ID to resolve"
 * field while the user was placing an anchor to *host*, and hide it the moment a
 * resolve succeeded, i.e. exactly backwards.
 */
fun CloudAnchorFlowState.card(): CloudAnchorCard {
    blocker?.blockedCopy()?.let { return CloudAnchorCard.Blocked(it) }
    return when (step) {
        CloudAnchorStep.Host -> when {
            host is CloudAnchorTask.Succeeded -> CloudAnchorCard.HostedCode(host.code)
            anchorPlaced -> CloudAnchorCard.RoomMapping(roomQuality)
            else -> CloudAnchorCard.None
        }
        CloudAnchorStep.Resolve -> when {
            resolve is CloudAnchorTask.Succeeded -> CloudAnchorCard.ResolvedCode(resolve.code)
            else -> CloudAnchorCard.ResolveInput(codeInput)
        }
    }
}

/** One button of the on-screen action bar. */
data class CloudAnchorButton(
    val action: CloudAnchorAction,
    val label: String,
    val enabled: Boolean,
)

/**
 * The ordered on-screen buttons for [this]. The first is the step's dominant action —
 * `SceneActionBar` renders it filled and the rest tonal.
 *
 * Wording is one word per button wherever the flow allows it, and the same word always
 * means the same thing. The old screen had five different sentences for two states and
 * two different casings of "Cloud Anchor ID".
 */
fun CloudAnchorFlowState.actionBar(): List<CloudAnchorButton> {
    // A card-worthy blocker owns the band; the buttons would all be dead anyway.
    if (blocker?.needsExplanationCard == true) return emptyList()

    val restart = CloudAnchorButton(
        action = CloudAnchorAction.Restart,
        label = "Restart",
        enabled = allows(CloudAnchorAction.Restart),
    )
    return when (step) {
        CloudAnchorStep.Host -> if (host is CloudAnchorTask.Succeeded) {
            listOf(
                CloudAnchorButton(CloudAnchorAction.ShareCode, "Share", allows(CloudAnchorAction.ShareCode)),
                CloudAnchorButton(CloudAnchorAction.CopyCode, "Copy", allows(CloudAnchorAction.CopyCode)),
                restart,
            )
        } else {
            listOf(
                CloudAnchorButton(CloudAnchorAction.Host, "Host", allows(CloudAnchorAction.Host)),
            ) + if (restart.enabled) listOf(restart) else emptyList()
        }

        CloudAnchorStep.Resolve -> if (resolve is CloudAnchorTask.Succeeded) {
            listOf(restart.copy(label = "Resolve another"))
        } else {
            val resolveButton =
                CloudAnchorButton(CloudAnchorAction.Resolve, "Resolve", allows(CloudAnchorAction.Resolve))
            val pasteButton =
                CloudAnchorButton(CloudAnchorAction.PasteCode, "Paste", allows(CloudAnchorAction.PasteCode))
            // `SceneActionBar` renders the first button filled and the rest tonal, so the
            // first slot has to be the action that can actually be taken. With an empty
            // field that is Paste — leading with a dead filled Resolve would put the
            // screen's loudest element on the one thing the user cannot do yet.
            val ordered = if (trimmedCode.isEmpty()) {
                listOf(pasteButton, resolveButton)
            } else {
                listOf(resolveButton, pasteButton)
            }
            ordered + if (restart.enabled) listOf(restart) else emptyList()
        }
    }
}

/**
 * A Cloud Anchor id shortened for display: first six characters, an ellipsis, last four.
 *
 * ARCore ids are long opaque strings; the old sheet rendered one whole into 13 sp
 * `bodySmall` where it wrapped across three lines and read as noise. The user never
 * needs to *read* the id — they need to recognise the one they just shared and hand the
 * full value over. So the screen shows this, and Copy / Share always carry the full
 * [code]. Anything short enough to read is left alone.
 */
fun shortCloudAnchorCode(code: String): String =
    if (code.length <= 14) code else code.take(6) + "…" + code.takeLast(4)

/**
 * What the system share sheet sends for [code] — the full id plus the one sentence the
 * recipient needs to act on it.
 */
fun cloudAnchorShareText(code: String): String =
    "Open the SceneView demo's Cloud Anchors screen, switch to Resolve and paste this " +
        "code within $CLOUD_ANCHOR_TTL_DAYS day:\n$code"

/** The caption under the room-mapping meter — one word, matching the status sentence's promise. */
fun RoomQuality.label(): String = when (this) {
    RoomQuality.Insufficient -> "Mapping"
    RoomQuality.Sufficient -> "Good enough"
    RoomQuality.Good -> "Well mapped"
}

/** How many of the meter's three segments are lit. */
fun RoomQuality.filledSegments(): Int = when (this) {
    RoomQuality.Insufficient -> 1
    RoomQuality.Sufficient -> 2
    RoomQuality.Good -> 3
}

/** Total segments in the room-mapping meter. */
const val ROOM_QUALITY_SEGMENTS: Int = 3
