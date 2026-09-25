import Foundation

/// Semantic AR haptic events, shared with Android (`io.github.sceneview.haptic.ARHapticEvent`).
///
/// Haptics are **opt-in**: nothing vibrates until the app adds
/// `.arHapticFeedback(controller)` to its ``AutoPlacementScene`` (or calls
/// ``SceneViewHaptic/play(_:)`` itself). Each event maps to one UIKit feedback generator:
///
/// | Event | iOS feedback |
/// |---|---|
/// | ``placed`` | `UIImpactFeedbackGenerator(.soft)` at 0.8 |
/// | ``selected`` | `UISelectionFeedbackGenerator` |
/// | ``scaleSnapped`` | `UIImpactFeedbackGenerator(.rigid)` at 0.7 |
/// | ``limitReached`` | `UIImpactFeedbackGenerator(.rigid)` at 0.5 |
/// | ``invalidMove`` | `UIImpactFeedbackGenerator(.rigid)` at 0.5 |
/// | ``trackingLost`` | `UINotificationFeedbackGenerator` `.warning` |
/// | ``recovered`` | `UINotificationFeedbackGenerator` `.success` |
/// | ``helpNeeded`` | `UINotificationFeedbackGenerator` `.warning` |
public enum ARHapticEvent: String, CaseIterable, Sendable {
    /// A surface was found and the object stands on it (placement is immediate).
    case placed
    /// A tap selected the standing object.
    case selected
    /// A pinch landed on exactly 100 % (the real-world size). Plays with an elastic rebound.
    case scaleSnapped
    /// A pinch reached 25 % or 400 %. Plays once on entering the bound.
    case limitReached
    /// A drag left the usable surface.
    case invalidMove
    /// World tracking was lost after it had been established.
    case trackingLost
    /// A lost placement is back on its surface.
    case recovered
    /// A help card opened: no surface found, or the placement could not be recovered.
    case helpNeeded
}

/// The pinch-scale detent of automatic placement: one source of truth for the 25–400 % range,
/// the 100 % snap window and the elastic rebound. Same numbers as Android's `ScaleSnap`.
enum ARScaleSnap {
    static let min: Float = 0.25
    static let max: Float = 4
    /// ±4 % around the real-world size snaps to exactly 100 %.
    static let window: Float = 0.04
    static let reboundAmplitude: Float = 0.05
    static let reboundDecayMs: Float = 80
    static let reboundPeriodMs: Float = 160
    /// After this, the rebound ends on exactly 1.
    static let reboundMs: Float = 280
    private static let epsilon: Float = 0.001

    /// One pinch update. `displayed` is exactly 1 inside the window; `enteredSnap` and
    /// `enteredLimit` are true only on the update that entered the detent or the bound.
    struct Step: Equatable {
        let displayed: Float
        let snapped: Bool
        let enteredSnap: Bool
        let enteredLimit: Bool
    }

    static func step(previousDisplayed: Float, raw: Float) -> Step {
        let clamped = Swift.max(min, Swift.min(max, raw))
        let snapped = abs(clamped - 1) < window
        let displayed: Float = snapped ? 1 : clamped
        let wasSnapped = abs(previousDisplayed - 1) < epsilon
        return Step(displayed: displayed,
                    snapped: snapped,
                    enteredSnap: snapped && !wasSnapped,
                    enteredLimit: isAtLimit(displayed) && !isAtLimit(previousDisplayed))
    }

    static func isAtLimit(_ scale: Float) -> Bool { scale <= min + epsilon || scale >= max - epsilon }

    /// The visual scale `elapsedMs` after entering the detent: a damped sine around 1 that first
    /// continues the pinch's direction (below 1 when shrinking into it), then settles on 1.
    static func rebound(elapsedMs: Float, fromAbove: Bool) -> Float {
        guard elapsedMs > 0, elapsedMs < reboundMs else { return 1 }
        let sign: Float = fromAbove ? -1 : 1
        let decay = exp(-elapsedMs / reboundDecayMs)
        return 1 + sign * reboundAmplitude * decay * sin(2 * Float.pi * elapsedMs / reboundPeriodMs)
    }
}

#if os(iOS)
/// Turns ``ARPlacementController`` snapshots into ``ARHapticEvent``s. Pure, so the table is
/// pinned by unit tests. Mirrors Android's `ARHapticTransitions`.
struct ARHapticTransitions {
    struct Snapshot: Equatable {
        var phase: ARPlacementPhase
        var placements: Int
        var selected: Bool
        var invalidMovement: Bool
    }

    static let throttle: TimeInterval = 0.4
    private static let standing: Set<ARPlacementPhase> = [.placed, .adjusting]
    private static let lost: Set<ARPlacementPhase> = [.trackingLost, .recovering, .recoveryFailed]
    private static let established: Set<ARPlacementPhase> = [
        .scanning, .noSurface, .placed, .adjusting, .recovering, .recoveryFailed,
    ]

    private var previous: Snapshot?
    private var trackingEstablished = false
    private var lastPlayed: [ARHapticEvent: TimeInterval] = [:]

    /// Events for the transition into `snapshot`. The first snapshot only records.
    mutating func next(_ snapshot: Snapshot, now: TimeInterval) -> [ARHapticEvent] {
        let before = previous
        previous = snapshot
        let events = before.map { transition(from: $0, to: snapshot) } ?? []
        if Self.established.contains(snapshot.phase) { trackingEstablished = true }
        return events.filter { accept($0, now: now) }
    }

    private func transition(from before: Snapshot, to now: Snapshot) -> [ARHapticEvent] {
        if now.placements > before.placements { return [.placed] }
        if now.phase != before.phase {
            if now.phase == .trackingLost { return trackingEstablished ? [.trackingLost] : [] }
            if Self.standing.contains(now.phase) && Self.lost.contains(before.phase) { return [.recovered] }
            if now.phase == .noSurface || now.phase == .recoveryFailed { return [.helpNeeded] }
        }
        if now.selected && !before.selected { return [.selected] }
        if now.invalidMovement && !before.invalidMovement { return [.invalidMove] }
        return []
    }

    /// Throttle: `false` when `event` already played less than 400 ms ago.
    mutating func accept(_ event: ARHapticEvent, now: TimeInterval) -> Bool {
        if let last = lastPlayed[event], now - last < Self.throttle { return false }
        lastPlayed[event] = now
        return true
    }

    /// The events worth a `prepare()` in `phase`, so the Taptic Engine is warm when they fire.
    static func expected(in phase: ARPlacementPhase) -> [ARHapticEvent] {
        switch phase {
        case .initializing, .scanning, .noSurface: return [.placed]
        case .placed: return [.selected, .scaleSnapped]
        case .adjusting: return [.scaleSnapped, .limitReached, .invalidMove]
        case .trackingLost, .recovering, .recoveryFailed: return [.recovered]
        case .cameraError: return []
        }
    }
}
#endif
