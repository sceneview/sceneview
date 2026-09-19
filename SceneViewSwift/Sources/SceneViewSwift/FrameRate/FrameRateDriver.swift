#if os(iOS) || os(macOS) || os(visionOS)
import QuartzCore

/// Turns a ``FrameRatePolicy`` into an actual stream of ticks for ``SceneView``'s
/// camera-motion loop, and carries the cadence request that goes with it.
///
/// Two implementations behind one API, because the platforms genuinely differ:
///
/// - **iOS / visionOS** — a `CADisplayLink`. Ticks land on vsync, and
///   `preferredFrameRateRange` is the system-level cadence request, the counterpart of
///   Android's `Surface.setFrameRate` vote.
/// - **macOS** — a plain `Task.sleep` timer. `CADisplayLink.init(target:selector:)` is
///   `@available(macOS, unavailable)`: a display link there has to be vended by an `NSView`,
///   `NSWindow` or `NSScreen`, and a SwiftUI `RealityView` hands us none of the three. So on
///   macOS the policy still parks the driver and still caps its rate, but **there is no
///   cadence request at all**. Stated in ``FrameRatePolicy``'s divergence table too.
///
/// ## Why this replaces a `Task.sleep(16_666_667)`
///
/// The loop this drives used to sleep a hard-coded 16.67 ms. That is 60 Hz written into the
/// source, on a platform that ships 120 Hz phones — and `Task.sleep` guarantees a *minimum*
/// delay, never a phase, so the camera pose was recomputed at roughly 60 Hz with arbitrary
/// jitter while RealityKit presented on vsync. The visible result is orbit judder at **any**
/// refresh rate, worse at 120. A display link fixes the phase, and is also the only object
/// on iOS that can carry a cadence request, which is why the policy and the fix arrive together.
@MainActor
final class FrameRateDriver {

    /// Ceiling requested when a policy carries no ``FrameRatePolicy/maxFps``.
    ///
    /// A display-link request has to name a number; there is no "whatever the panel does"
    /// value, and the obvious sources for the real maximum are not usable here —
    /// `UIScreen.main` is deprecated on iOS 26 and `UIScreen` does not exist on visionOS at
    /// all. CoreAnimation clamps a request to what the display can actually do, so asking for
    /// 120 on a 60 Hz panel yields 60 rather than an error.
    ///
    /// ⚠️ **Unverified above 60 Hz.** No ProMotion device was available when this shipped, and
    /// the Simulator cannot exceed its host's refresh rate. Two things remain to be confirmed
    /// on a real 120 Hz iPhone: that the request is granted at all, and that it is granted for
    /// a `RealityView` — a 2024 developer-forums thread reports
    /// `CADisableMinimumFrameDurationOnPhone` having no effect on a `RealityView` (iPhone 15
    /// Pro, iOS 18 beta) with no official answer. See `docs/docs/performance.md`.
    nonisolated static let uncappedCeiling: Int = 120

    /// Lowest rate the request will tolerate, so a variable-refresh-rate panel keeps room to
    /// drop when our content is cheap. Apple's guidance is to leave the range wide rather
    /// than pin a single value; a range whose minimum equals its maximum forbids exactly the
    /// power saving the policy exists to enable.
    nonisolated static let requestFloor: Int = 30

    /// The cadence request for a ceiling, or `nil` for "make no request".
    ///
    /// Pure and `nonisolated` — it touches no instance state, so it does not need the main
    /// actor and a test does not need to hop onto it. That is what makes the clamping rules
    /// assertable directly: `nil` in means
    /// ``uncappedCeiling``; a ceiling below ``requestFloor`` collapses the range onto itself
    /// (you cannot ask for "at most 10 fps, at least 30"); non-positive ceilings never reach
    /// here because ``FrameRatePolicy/resolvedMaxFps`` clamps them to 1 first.
    nonisolated static func frameRateRange(maxFps: Int?) -> CAFrameRateRange {
        let ceiling = Swift.max(1, maxFps ?? uncappedCeiling)
        let floor = Swift.min(requestFloor, ceiling)
        return CAFrameRateRange(
            minimum: Float(floor),
            maximum: Float(ceiling),
            preferred: Float(ceiling)
        )
    }

    /// Seconds between ticks for the timer fallback, given a ceiling.
    ///
    /// Pure for the same reason as ``frameRateRange(maxFps:)``.
    nonisolated static func fallbackInterval(maxFps: Int?) -> Double {
        1.0 / Double(Swift.max(1, maxFps ?? 60))
    }

    #if os(iOS) || os(visionOS)
    private var link: CADisplayLink?
    private var proxy: DisplayLinkProxy?
    private var continuation: AsyncStream<Double>.Continuation?
    private var lastTimestamp: CFTimeInterval?

    /// `CADisplayLink` keeps an **unowned** reference to its target and retains nothing, but
    /// the run loop retains the link, so a target that is the driver itself would keep the
    /// driver alive through the run loop for as long as the link is scheduled. The proxy is
    /// the usual answer: a tiny `NSObject` whose only job is to forward the selector.
    private final class DisplayLinkProxy: NSObject {
        var onTick: ((CFTimeInterval) -> Void)?

        @objc func tick(_ link: CADisplayLink) {
            onTick?(link.targetTimestamp)
        }
    }
    #endif

    /// Whether a tick source is currently scheduled. Read by tests and by the teardown path.
    private(set) var isRunning: Bool = false

    /// The ceiling currently requested, purely so ``updateRequestedMaxFps(_:)`` can skip a
    /// no-op write — reassigning `preferredFrameRateRange` is not free and the gate hands us
    /// the same value on most ticks.
    private var appliedMaxFps: Int??

    // No `deinit` cleanup on purpose. `deinit` is nonisolated and can run on any thread,
    // and `CADisplayLink.invalidate()` is not safe to call off the thread that scheduled the
    // link — `MainActor.assumeIsolated` there would turn a background release into a crash.
    // It is not needed either: the stream's `onTermination` calls `stop()`, and an
    // `AsyncStream` terminates both when the consumer's task is cancelled and when the
    // consumer breaks out of its `for await`. Those are the only two ways the view's `.task`
    // ever ends.

    /// Start ticking and return the stream of frame deltas, in seconds.
    ///
    /// The stream finishes when ``stop()`` is called or the driver is released, which is what
    /// lets the caller write `for await dt in driver.ticks(...)` inside a SwiftUI `.task` and
    /// get cancellation for free.
    ///
    /// - Parameter maxFps: initial ceiling, from ``FrameRatePolicy/resolvedMaxFps``.
    func ticks(maxFps: Int?) -> AsyncStream<Double> {
        #if os(iOS) || os(visionOS)
        return AsyncStream { continuation in
            let proxy = DisplayLinkProxy()
            let link = CADisplayLink(target: proxy, selector: #selector(DisplayLinkProxy.tick(_:)))
            proxy.onTick = { [weak self] timestamp in
                guard let self else { return }
                let delta: Double
                if let last = self.lastTimestamp {
                    delta = timestamp - last
                } else {
                    // First tick has no predecessor. Report one frame at the requested
                    // ceiling rather than 0 — a zero `dt` makes the camera integrate nothing
                    // and shows up as a one-frame stall at the start of every coast.
                    delta = Self.fallbackInterval(maxFps: maxFps)
                }
                self.lastTimestamp = timestamp
                continuation.yield(delta)
            }
            link.preferredFrameRateRange = Self.frameRateRange(maxFps: maxFps)
            link.add(to: .main, forMode: .common)

            self.proxy = proxy
            self.link = link
            self.continuation = continuation
            self.appliedMaxFps = .some(maxFps)
            self.isRunning = true

            continuation.onTermination = { [weak self] _ in
                Task { @MainActor in self?.stop() }
            }
        }
        #else
        // macOS: no display link available to a SwiftUI `RealityView`, so no vsync alignment
        // and no cadence request — only the cap and the parking survive.
        return AsyncStream { continuation in
            self.isRunning = true
            self.appliedMaxFps = .some(maxFps)
            let task = Task { @MainActor in
                var last = CFAbsoluteTimeGetCurrent()
                while !Task.isCancelled && self.isRunning {
                    let interval = Self.fallbackInterval(maxFps: self.appliedMaxFps ?? maxFps)
                    try? await Task.sleep(nanoseconds: UInt64(interval * 1_000_000_000))
                    let now = CFAbsoluteTimeGetCurrent()
                    continuation.yield(now - last)
                    last = now
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
        #endif
    }

    /// Re-point the cadence request without restarting the stream.
    ///
    /// Called each tick with the gate's decision. A repeat of the value already applied is
    /// dropped.
    func updateRequestedMaxFps(_ maxFps: Int?) {
        if let applied = appliedMaxFps, applied == maxFps { return }
        appliedMaxFps = .some(maxFps)
        #if os(iOS) || os(visionOS)
        link?.preferredFrameRateRange = Self.frameRateRange(maxFps: maxFps)
        #endif
    }

    /// Park: tear the tick source down so nothing is scheduled and no cadence is requested.
    ///
    /// This is the half of ``FrameRatePolicy/onDemand(maxFps:)`` that makes "parked means
    /// parked" true on iOS — invalidating the link removes both the periodic wake-up and the
    /// `preferredFrameRateRange` request from CoreAnimation's arbitration.
    func stop() {
        guard isRunning else { return }
        isRunning = false
        #if os(iOS) || os(visionOS)
        link?.invalidate()
        link = nil
        proxy = nil
        lastTimestamp = nil
        continuation?.finish()
        continuation = nil
        #endif
        appliedMaxFps = nil
    }
}

#endif
