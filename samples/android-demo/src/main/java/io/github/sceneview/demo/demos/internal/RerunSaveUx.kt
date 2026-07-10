package io.github.sceneview.demo.demos.internal

/**
 * Headless-testable UX derivation for the Rerun Debug demo's "Save & Share
 * recording" action (issue
 * [#2658](https://github.com/sceneview/sceneview/issues/2658)).
 *
 * The demo streams ARCore data to a desktop Rerun sidecar over
 * `adb reverse tcp:9876 tcp:9876`. On a wireless-debugging device — or for any
 * user without the sidecar running — the bridge never connects, so a save can
 * only ever fail. Two invariants this file locks (both pure, so a JVM unit
 * test pins them without an emulator):
 *
 * 1. The primary CTA is **disabled until the bridge is actually connected** —
 *    the demo's headline button must never lead straight to a failure dialog.
 * 2. If a save does fail, the message is **human-readable and actionable** —
 *    the bridge's internal reason strings (`"bridge not connected — call
 *    connect() first"`, `"control write failed: …"`) must never reach the demo
 *    UI. AI-first repo rule: demos are the reference UX an AI reproduces, so a
 *    raw internal message leaking into the UI teaches the wrong pattern.
 */

/** Derived on-screen state of the "Save & Share recording" button. */
data class RerunSaveActionUx(
    val label: String,
    val enabled: Boolean,
)

/**
 * Derives the Save & Share button's label + enabled state from the two signals
 * that matter, in priority order:
 *
 *  - a save is already in flight ([sharing]) ⇒ disabled, "saving…" label;
 *  - the bridge is not connected ([isConnected] == false) ⇒ disabled, with the
 *    reason inline in the label so the greyed-out CTA is never a mystery;
 *  - otherwise the sidecar is live ⇒ enabled, normal label.
 */
fun rerunSaveActionUx(sharing: Boolean, isConnected: Boolean): RerunSaveActionUx = when {
    sharing -> RerunSaveActionUx(label = "Saving on dev machine…", enabled = false)
    !isConnected -> RerunSaveActionUx(label = "Save & Share (sidecar offline)", enabled = false)
    else -> RerunSaveActionUx(label = "Save & Share recording", enabled = true)
}

/**
 * Maps a [io.github.sceneview.ar.rerun.RerunBridge.ShareResult.reason] to a
 * human-readable, actionable sentence for the "Couldn't save" dialog.
 *
 * The bridge's own reason strings are implementation detail — some are outright
 * internal jargon (`"call connect() first"`, `"control write failed: …"`) that
 * must never surface to an end user who has no `connect()` to call (the demo
 * auto-connects by design). This collapses every failure mode to honest setup
 * copy, and only special-cases the one genuinely user-facing case (the sidecar
 * is reachable but was started in live mode, i.e. without `--save`).
 *
 * @return A user-facing sentence — never a bare bridge reason string.
 */
fun rerunSaveFailureMessage(reason: String?): String {
    val r = reason.orEmpty()
    return when {
        // Sidecar is reachable but running in live mode (not started with --save),
        // so it can't flush a .rrd. This branch is the only reachable failure once
        // the CTA is gated on connection state.
        r.contains("live mode", ignoreCase = true) || r.contains("--save", ignoreCase = true) ->
            "The Rerun sidecar is running in live mode, so it can't write a recording. " +
                "Restart it with --save, then try again."

        // Everything else — not connected, socket write failed, unknown — collapses to
        // the setup explanation. Never surfaces the bridge's internal reason string.
        else ->
            "Saving needs the desktop Rerun sidecar reachable over " +
                "adb reverse tcp:9876 tcp:9876. Wireless debugging can't reach " +
                "127.0.0.1:9876 — connect the device over USB with the sidecar running " +
                "(or bind the sidecar to your LAN IP), then try again."
    }
}
