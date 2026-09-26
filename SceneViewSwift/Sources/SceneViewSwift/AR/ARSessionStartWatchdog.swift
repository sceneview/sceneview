#if os(iOS)
import Foundation

/// What an ``ARSceneView`` does when a session it ran has delivered no
/// `ARFrame` within its budget (#3912).
///
/// ARKit reports a configuration it cannot run through
/// `session(_:didFailWithError:)`. A session that *starts* and never delivers
/// a frame — the camera still held by a previous client, a capture pipeline
/// that never came up — reports nothing: the host stays on its "starting"
/// state over a black view for as long as the user waits. This is the missing
/// signal. It is pure decision logic: the coordinator arms one timer per run,
/// feeds each expiry to ``budgetExpired()`` and acts on the answer, so the
/// policy is testable without a session and the scheduling stays in one
/// place.
///
/// One start gets one re-run with a tracking reset — enough to recover a
/// camera that was still being released when the first `run` went out — then
/// the start is reported as ``ARSceneViewError/noCameraFrames``.
struct ARSessionStartWatchdog: Equatable, Sendable {
    /// The action for one expired budget.
    enum Decision: Equatable, Sendable {
        /// Run the same configuration again with a tracking reset, and watch
        /// again.
        case retry
        /// Report ``ARSceneViewError/noCameraFrames`` and stop watching.
        case fail
    }

    /// Seconds a run may spend without a frame before the watchdog acts.
    /// ARKit delivers its first frame well under a second on every supported
    /// iPhone; the budget is generous so a slow cold start is never mistaken
    /// for a dead one.
    static let defaultBudget: TimeInterval = 4

    /// Re-runs allowed per start.
    static let defaultRetries = 1

    let budget: TimeInterval
    let retriesAllowed: Int
    private(set) var retriesUsed = 0

    init(budget: TimeInterval = Self.defaultBudget, retriesAllowed: Int = Self.defaultRetries) {
        self.budget = budget
        self.retriesAllowed = retriesAllowed
    }

    /// The decision for one expired budget; consumes a retry while one is left.
    mutating func budgetExpired() -> Decision {
        guard retriesUsed < retriesAllowed else { return .fail }
        retriesUsed += 1
        return .retry
    }

    /// A new start — a host run, an interruption that ended — gets its retry
    /// back.
    mutating func startedFresh() {
        retriesUsed = 0
    }
}
#endif
