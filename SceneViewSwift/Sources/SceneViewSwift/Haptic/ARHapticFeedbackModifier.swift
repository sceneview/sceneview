#if os(iOS)
import Combine
import Foundation
import SwiftUI

public extension View {
    /// Opt in to SceneView's semantic AR haptics for an automatic placement.
    ///
    /// Plays ``ARHapticEvent/placed`` when the object lands, ``ARHapticEvent/selected`` on a
    /// tap, ``ARHapticEvent/scaleSnapped`` when a pinch lands on 100 %,
    /// ``ARHapticEvent/limitReached`` at 25 % / 400 %, ``ARHapticEvent/invalidMove`` when a drag
    /// leaves the surface, ``ARHapticEvent/trackingLost`` (only after tracking was established),
    /// ``ARHapticEvent/recovered`` and ``ARHapticEvent/helpNeeded`` when a help card opens.
    /// Each event plays at most once per 400 ms, and the generator is prepared ahead of the
    /// events the current phase makes likely. Same events as Android's `ARHapticFeedback(state)`.
    ///
    /// ```swift
    /// AutoPlacementScene(controller: controller)
    ///     .arHapticFeedback(controller)
    /// ```
    ///
    /// iOS mutes haptics while the app captures audio (`providesAudioData`, microphone).
    ///
    /// - Parameters:
    ///   - controller: The placement to follow.
    ///   - haptic: The facade that plays the events.
    ///   - isEnabled: `false` keeps following the state without playing anything.
    func arHapticFeedback(_ controller: ARPlacementController,
                          haptic: SceneViewHaptic = .shared,
                          isEnabled: Bool = true) -> some View {
        modifier(ARHapticFeedbackModifier(controller: controller, haptic: haptic, isEnabled: isEnabled))
    }
}

@MainActor
private final class ARHapticFeedbackDriver {
    var transitions = ARHapticTransitions()
    var preparedPhase: ARPlacementPhase?
}

@MainActor
private struct ARHapticFeedbackModifier: ViewModifier {
    let controller: ARPlacementController
    let haptic: SceneViewHaptic
    let isEnabled: Bool
    @State private var driver = ARHapticFeedbackDriver()

    func body(content: Content) -> some View {
        content
            .onAppear { observe() }
            // `objectWillChange` fires before the value changes: hop once so the snapshot reads
            // every property a single controller update touched (commit + selection + phase).
            .onReceive(controller.objectWillChange.receive(on: DispatchQueue.main)) { _ in observe() }
            .onReceive(controller.hapticEvents) { event in
                guard driver.transitions.accept(event, now: ProcessInfo.processInfo.systemUptime) else { return }
                if isEnabled { haptic.play(event) }
            }
    }

    private func observe() {
        let snapshot = ARHapticTransitions.Snapshot(phase: controller.phase,
                                                    placements: controller.placementsCreated,
                                                    selected: controller.selection,
                                                    invalidMovement: controller.invalidMovement)
        let events = driver.transitions.next(snapshot, now: ProcessInfo.processInfo.systemUptime)
        guard isEnabled else { return }
        for event in events { haptic.play(event) }
        if driver.preparedPhase != snapshot.phase {
            driver.preparedPhase = snapshot.phase
            for event in ARHapticTransitions.expected(in: snapshot.phase) { haptic.prepare(for: event) }
        }
    }
}
#endif
