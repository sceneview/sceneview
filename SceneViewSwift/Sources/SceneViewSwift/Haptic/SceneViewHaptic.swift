#if os(iOS)
import Foundation
import UIKit
import CoreHaptics

/// Lightweight, semantic haptic feedback for SceneView demos and apps.
///
/// Wraps `UIImpactFeedbackGenerator` / `UISelectionFeedbackGenerator` /
/// `UINotificationFeedbackGenerator` behind a small set of **semantic**
/// presets (``light()``, ``medium()``, ``heavy()``, ``success()``,
/// ``warning()``, ``error()``, ``selection()``) plus a Core Haptics-backed
/// ``continuous(intensity:duration:)`` and ``pattern(_:)`` for richer
/// feedback. The same API surface ships on Android
/// (`io.github.sceneview.haptic.SceneViewHaptic`) and on Web
/// (`navigator.vibrate(...)` fallback) so cross-platform code paths stay
/// symmetric.
///
/// ### Threading
///
/// All `UIFeedbackGenerator` APIs are main-thread only — this class is
/// `@MainActor`-isolated to enforce that at compile time.
///
/// ### Usage
///
/// ```swift
/// struct PlaceAnchorButton: View {
///     @StateObject private var haptic = SceneViewHaptic()
///
///     var body: some View {
///         Button("Place") {
///             haptic.medium()  // confirm the placement
///             // …
///         }
///     }
/// }
/// ```
///
/// ### Core Haptics
///
/// ``continuous(intensity:duration:)`` and ``pattern(_:)`` use Core
/// Haptics under the hood. The first call lazily creates a
/// `CHHapticEngine`. If Core Haptics is not available on the device
/// (e.g. older iPad models), those two APIs gracefully fall back to the
/// preset generators — they never throw.
@MainActor
public final class SceneViewHaptic: ObservableObject {

    /// Process-wide shared instance for callsites that don't want to thread a
    /// `@StateObject`/`@EnvironmentObject` reference through every view.
    /// Equivalent to constructing a `SceneViewHaptic()` once and reusing it —
    /// the generators are pre-prepared either way.
    public static let shared = SceneViewHaptic()

    private let impactLight = UIImpactFeedbackGenerator(style: .light)
    private let impactMedium = UIImpactFeedbackGenerator(style: .medium)
    private let impactHeavy = UIImpactFeedbackGenerator(style: .heavy)
    private let selectionGenerator = UISelectionFeedbackGenerator()
    private let notificationGenerator = UINotificationFeedbackGenerator()

    private var hapticEngine: CHHapticEngine?

    /// Create a haptic facade. Generators are pre-instantiated so the
    /// first preset call is as low-latency as the second.
    public init() {
        impactLight.prepare()
        impactMedium.prepare()
        impactHeavy.prepare()
        selectionGenerator.prepare()
        notificationGenerator.prepare()
    }

    // MARK: - Semantic presets

    /// Light tap — taps, button presses, selections.
    public func light() {
        impactLight.impactOccurred()
    }

    /// Medium tap — placing an anchor, mode change confirmation.
    public func medium() {
        impactMedium.impactOccurred()
    }

    /// Heavy tap — boundary hit, drag-lock engagement.
    public func heavy() {
        impactHeavy.impactOccurred()
    }

    /// Success notification — anchor stable, action confirmed.
    public func success() {
        notificationGenerator.notificationOccurred(.success)
    }

    /// Warning notification — tracking degraded, soft failure.
    public func warning() {
        notificationGenerator.notificationOccurred(.warning)
    }

    /// Error notification — action rejected, hard failure.
    public func error() {
        notificationGenerator.notificationOccurred(.error)
    }

    /// Selection tick — drag tick, picker scroll.
    public func selection() {
        selectionGenerator.selectionChanged()
    }

    // MARK: - Low-level Core Haptics escape hatches

    /// Continuous vibration for `duration` seconds at `intensity` (0.0..1.0).
    ///
    /// Falls back to ``medium()`` on devices without Core Haptics.
    public func continuous(intensity: Float, duration: TimeInterval) {
        guard duration > 0 else { return }
        guard ensureEngine() else {
            medium()
            return
        }
        let intensityParam = CHHapticEventParameter(
            parameterID: .hapticIntensity,
            value: max(0, min(1, intensity))
        )
        let event = CHHapticEvent(
            eventType: .hapticContinuous,
            parameters: [intensityParam],
            relativeTime: 0,
            duration: duration
        )
        play(events: [event])
    }

    /// Play a sequence of ``HapticEvent``s via Core Haptics.
    ///
    /// Falls back to ``selection()`` on devices without Core Haptics.
    public func pattern(_ events: [HapticEvent]) {
        guard !events.isEmpty else { return }
        guard ensureEngine() else {
            selection()
            return
        }
        var cumulativeTime: TimeInterval = 0
        let coreEvents: [CHHapticEvent] = events.map { event in
            cumulativeTime += TimeInterval(event.delayMs) / 1000.0
            let durationSec = TimeInterval(event.durationMs) / 1000.0
            let intensityParam = CHHapticEventParameter(
                parameterID: .hapticIntensity,
                value: max(0, min(1, event.intensity))
            )
            let sharpnessParam = CHHapticEventParameter(
                parameterID: .hapticSharpness,
                value: max(0, min(1, event.sharpness))
            )
            let coreEvent = CHHapticEvent(
                eventType: durationSec > 0 ? .hapticContinuous : .hapticTransient,
                parameters: [intensityParam, sharpnessParam],
                relativeTime: cumulativeTime,
                duration: max(0, durationSec)
            )
            cumulativeTime += durationSec
            return coreEvent
        }
        play(events: coreEvents)
    }

    // MARK: - Private

    @discardableResult
    private func ensureEngine() -> Bool {
        guard CHHapticEngine.capabilitiesForHardware().supportsHaptics else {
            return false
        }
        if hapticEngine != nil { return true }
        do {
            let engine = try CHHapticEngine()
            try engine.start()
            engine.resetHandler = { [weak self] in
                guard let self = self else { return }
                try? self.hapticEngine?.start()
            }
            engine.stoppedHandler = { _ in
                // The engine can stop on app-background; the resetHandler
                // restarts it on next use, so nothing to do here.
            }
            hapticEngine = engine
            return true
        } catch {
            hapticEngine = nil
            return false
        }
    }

    private func play(events: [CHHapticEvent]) {
        guard let engine = hapticEngine else { return }
        do {
            let pattern = try CHHapticPattern(events: events, parameters: [])
            let player = try engine.makePlayer(with: pattern)
            try player.start(atTime: CHHapticTimeImmediate)
        } catch {
            // Surface to the developer console once; haptic failures are
            // never user-visible enough to throw on.
            #if DEBUG
            print("SceneViewHaptic: failed to play pattern — \(error)")
            #endif
        }
    }
}

/// A single timed haptic event used as a building block for
/// ``SceneViewHaptic/pattern(_:)``.
///
/// Mirrors the Android `HapticEvent` data class. `intensity` and
/// `sharpness` are honoured on iOS via Core Haptics; on Web they are
/// ignored (the Web Vibration API takes durations only).
public struct HapticEvent: Sendable {
    /// Strength of the haptic, `0.0` (off) .. `1.0` (max). Clamped if out
    /// of range.
    public let intensity: Float
    /// Wave-shape hint, `0.0` (round/dull) .. `1.0` (sharp/clicky).
    public let sharpness: Float
    /// How long this event vibrates, in milliseconds.
    public let durationMs: Int
    /// Delay before this event starts, relative to the end of the previous
    /// event (or to `pattern(_:)` invocation for the first event).
    public let delayMs: Int

    public init(intensity: Float, sharpness: Float, durationMs: Int, delayMs: Int = 0) {
        self.intensity = intensity
        self.sharpness = sharpness
        self.durationMs = durationMs
        self.delayMs = delayMs
    }
}

/// Semantic haptic preset shared across Android, iOS and Web.
///
/// Presets map to the canonical platform feedback under the hood — see
/// the Android `HapticPreset` Kdoc for the cross-platform table.
public enum HapticPreset: Sendable {
    case light
    case medium
    case heavy
    case success
    case warning
    case error
    case selection
}

public extension SceneViewHaptic {
    /// Trigger a haptic by preset value (useful for table-driven event
    /// handlers).
    func trigger(_ preset: HapticPreset) {
        switch preset {
        case .light: light()
        case .medium: medium()
        case .heavy: heavy()
        case .success: success()
        case .warning: warning()
        case .error: error()
        case .selection: selection()
        }
    }
}
#endif
