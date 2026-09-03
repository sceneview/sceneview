package io.github.sceneview.demo.demos.internal

/**
 * Named visual states of the Cloud Anchor screen, so QA can capture each one without a
 * cloud round-trip — or, on `emulator-5554`, without ARCore at all (#2754, #3421).
 *
 * Every other AR demo can be screenshotted because its states are reachable by pointing
 * the phone somewhere. This one's are not: "hosting", "hosted", "code not found" all
 * require a live Cloud Anchor service, a mapped room and a second device. Without a
 * scenario switch the emulator smoke suite could only ever capture the empty first frame
 * — which is exactly the frame that looked fine in review while #3421 was open.
 *
 * The mapping below is a pure function of the enum, so `CloudAnchorScenarioTest` pins
 * that every scenario really does produce the state it is named after: a scenario that
 * has drifted from its name is a screenshot silently capturing the wrong thing.
 */
enum class CloudAnchorScenario {
    /** Host step, tracking, nothing placed — the honest first frame. */
    Placing,

    /** Host step, anchor placed, feature map still too thin to host. */
    Mapping,

    /** Host step, anchor placed, room mapped — Host is live. */
    ReadyToHost,

    /** Host step, upload in flight. */
    Hosting,

    /** Host step, hosted — the code, Copy and Share. */
    Hosted,

    /** Host step, the upload came back with too little room detail. */
    HostFailed,

    /** Resolve step, empty field. */
    ResolveEmpty,

    /** Resolve step, a code pasted and Resolve live. */
    ResolveReady,

    /** Resolve step, lookup in flight. */
    Resolving,

    /** Resolve step, resolved. */
    Resolved,

    /** Resolve step, the code was wrong or has expired. */
    ResolveNotFound,

    /** No ARCore Cloud API key in this build — the explanation card. */
    ApiKeyMissing,

    /** The key was refused — the explanation card. */
    ApiKeyRejected,

    /** Network gone — the shared transient banner, flow still visible. */
    NoNetwork,
}

/**
 * A Cloud Anchor id shaped like ARCore's own, used by every scenario that needs one.
 *
 * Deliberately fixed rather than random: a golden screenshot must be byte-identical
 * between runs, and a code is the one thing on this screen that is otherwise unstable.
 */
const val SAMPLE_CLOUD_ANCHOR_CODE: String = "ua-9f2c41ab77de4b0e8c1d5a3e6f9b2704"

/** The flow state [this] stands for. Pure, so the QA switch cannot drift from its label. */
fun CloudAnchorScenario.state(): CloudAnchorFlowState {
    val hosting = CloudAnchorFlowState(step = CloudAnchorStep.Host, tracking = true)
    val resolving = CloudAnchorFlowState(step = CloudAnchorStep.Resolve, tracking = true)
    return when (this) {
        CloudAnchorScenario.Placing -> hosting
        CloudAnchorScenario.Mapping -> hosting.copy(
            anchorPlaced = true,
            roomQuality = RoomQuality.Insufficient,
        )
        CloudAnchorScenario.ReadyToHost -> hosting.copy(
            anchorPlaced = true,
            roomQuality = RoomQuality.Good,
        )
        CloudAnchorScenario.Hosting -> hosting.copy(
            anchorPlaced = true,
            roomQuality = RoomQuality.Good,
            host = CloudAnchorTask.Running,
        )
        CloudAnchorScenario.Hosted -> hosting.copy(
            anchorPlaced = true,
            roomQuality = RoomQuality.Good,
            host = CloudAnchorTask.Succeeded(SAMPLE_CLOUD_ANCHOR_CODE),
        )
        CloudAnchorScenario.HostFailed -> hosting.copy(
            anchorPlaced = true,
            roomQuality = RoomQuality.Sufficient,
            host = CloudAnchorTask.Failed(CloudAnchorFailure.RoomNotMapped),
        )
        CloudAnchorScenario.ResolveEmpty -> resolving
        CloudAnchorScenario.ResolveReady -> resolving.copy(codeInput = SAMPLE_CLOUD_ANCHOR_CODE)
        CloudAnchorScenario.Resolving -> resolving.copy(
            codeInput = SAMPLE_CLOUD_ANCHOR_CODE,
            resolve = CloudAnchorTask.Running,
        )
        CloudAnchorScenario.Resolved -> resolving.copy(
            codeInput = SAMPLE_CLOUD_ANCHOR_CODE,
            resolve = CloudAnchorTask.Succeeded(SAMPLE_CLOUD_ANCHOR_CODE),
        )
        CloudAnchorScenario.ResolveNotFound -> resolving.copy(
            codeInput = SAMPLE_CLOUD_ANCHOR_CODE,
            resolve = CloudAnchorTask.Failed(CloudAnchorFailure.CodeNotFound),
        )
        CloudAnchorScenario.ApiKeyMissing -> hosting.copy(blocker = CloudAnchorBlocker.ApiKeyMissing)
        CloudAnchorScenario.ApiKeyRejected -> hosting.copy(blocker = CloudAnchorBlocker.ApiKeyRejected)
        CloudAnchorScenario.NoNetwork -> hosting.copy(
            anchorPlaced = true,
            roomQuality = RoomQuality.Good,
            blocker = CloudAnchorBlocker.NoNetwork,
        )
    }
}

/**
 * Resolves a `--es qa_state <name>` value to a scenario, case- and separator-insensitive
 * (`resolve_not_found`, `resolve-not-found` and `ResolveNotFound` all land on the same
 * one), or `null` for an unknown name so a typo leaves the demo in its real state rather
 * than silently capturing the wrong screen.
 */
fun cloudAnchorScenarioOf(name: String?): CloudAnchorScenario? {
    val normalised = name?.replace("_", "")?.replace("-", "")?.lowercase() ?: return null
    return CloudAnchorScenario.entries.firstOrNull { it.name.lowercase() == normalised }
}
