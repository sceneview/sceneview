#if os(iOS) || os(macOS) || os(visionOS)

/// Everything the gate is allowed to look at when deciding whether the scene is busy.
///
/// One value per tick, assembled by `SceneViewRepresentation` from state it already holds.
/// Keeping it a plain struct is the point: the decision below is then a pure function of
/// this, unit-testable without a simulator, a `RealityView` or a clock — the
/// ``AppliedCameraState`` (#2412) / ``EntityDragState`` (#2313) precedent.
///
/// These are the **pull** sources of Android's push/pull split: states that last many frames
/// and send no event of their own, so they are asked once per tick. The **push** sources —
/// an explicit ``SceneRenderInvalidator/requestRender()`` — arrive through
/// ``FrameRateGate/requestRender()`` instead.
struct FrameRateActivity: Equatable {

    /// A finger is down: an orbit drag, a pan, or a pinch in flight.
    var isGestureInFlight: Bool = false

    /// `CameraControls.advance(dt:)` reported motion on the last tick — the coast a released
    /// drag leaves, or an active ``SceneView/autoRotate(speed:)`` turntable.
    var isCameraMoving: Bool = false

    /// The auto-centre / fit-to-bounds pass has not latched yet, and has not timed out.
    ///
    /// Same rule as Android's `isFramingPending` (blockers B2/B3 of PR #3718): the flag
    /// reports the condition of the *work*, never `!didLatch`, because a pass that can never
    /// run — an empty scene, a scene whose nodes are all invisible — would otherwise re-arm
    /// the budget forever with nothing wrong on screen.
    var isFramingPending: Bool = false

    /// Any of the above.
    var isActive: Bool {
        isGestureInFlight || isCameraMoving || isFramingPending
    }
}

/// What the driver should do for the tick just evaluated.
struct FrameRateDecision: Equatable {

    /// Whether the driver keeps running. `false` means park: invalidate the display link,
    /// schedule nothing, request no cadence.
    var isRunning: Bool

    /// The cadence ceiling to request while running, or `nil` for "no request" — either
    /// because the driver is parking, or because the policy carries no ``FrameRatePolicy/maxFps``
    /// and the platform maximum is what we want.
    var requestedMaxFps: Int?
}

/// Decides, per tick, whether ``SceneView``'s frame driver keeps running and what display
/// cadence it asks for.
///
/// Pure state machine, deliberately free of `CADisplayLink`, `RealityView`, timers and dates
/// so it can be tested exhaustively off-device. `FrameRateDriver` owns the platform half;
/// this owns the decision.
///
/// The shape mirrors Android's `FrameRateGate`: a dirty flag set by push sources, a settle
/// budget re-armed by any activity, and a park once the budget runs out.
struct FrameRateGate {

    /// Ticks the gate still owes after the last activity before it parks.
    ///
    /// The number matches Android's and Web's `SETTLE_FRAMES`, but **the reason is different
    /// and the parity is cosmetic**. On Android the budget exists because Filament finalises
    /// texture uploads, IBL and shadow work across several frames, so stopping one early
    /// leaves an untextured model on screen. RealityKit does none of its uploading through
    /// us and keeps presenting whether we tick or not, so nothing here can leave a frozen
    /// image.
    ///
    /// On iOS the budget buys hysteresis on the **cadence request**: without it, a tap-drag-tap
    /// would invalidate and recreate the display link — and drop and re-raise the frame-rate
    /// request — several times a second, which is exactly the thrash a variable-refresh-rate
    /// panel handles worst. Half a second of tail costs nothing and removes it.
    static let settleFrames: Int = 30

    /// The policy this gate was built for. Changing policy builds a new gate, so this is `let`.
    let policy: FrameRatePolicy

    /// Ticks still owed. Starts full so a freshly-created gate always runs its opening tail —
    /// a scene has framing, lights and an environment to settle even if the user never touches it.
    private(set) var settleDebt: Int

    /// Set by ``requestRender()``, consumed by the next ``tick(_:)``.
    ///
    /// A flag rather than a counter: two requests between two ticks mean the same thing as one
    /// — re-arm the budget.
    private(set) var hasPendingRequest: Bool = false

    init(policy: FrameRatePolicy) {
        self.policy = policy
        self.settleDebt = Self.settleFrames
    }

    /// Push source: wake the gate and re-arm the full settle budget on the next tick.
    ///
    /// Idempotent between ticks. Safe to call from any thread the caller keeps to the main
    /// actor; ``SceneRenderInvalidator`` is the public route.
    mutating func requestRender() {
        hasPendingRequest = true
    }

    /// Evaluate one tick.
    ///
    /// Any activity — pulled from `activity`, or pushed since the last call — re-arms the
    /// full budget. Otherwise the budget spends one tick. The driver parks when the budget is
    /// exhausted **and** the policy does not tick when idle.
    ///
    /// - Returns: what the driver should do for this tick.
    mutating func tick(_ activity: FrameRateActivity) -> FrameRateDecision {
        if hasPendingRequest || activity.isActive {
            hasPendingRequest = false
            settleDebt = Self.settleFrames
        } else if settleDebt > 0 {
            settleDebt -= 1
        }

        let isRunning = policy.ticksWhenIdle || settleDebt > 0
        return FrameRateDecision(
            isRunning: isRunning,
            requestedMaxFps: isRunning ? policy.resolvedMaxFps : nil
        )
    }
}

#endif
