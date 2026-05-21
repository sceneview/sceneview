import SwiftUI
import RealityKit
import SceneViewSwift

// MARK: - Shared physics (Swift port of sceneview-core's DoublePendulum.kt)

/// One rigid link of a ``DoublePendulum``.
///
/// Mirrors `DoublePendulumLink` in
/// `sceneview-core/src/commonMain/.../physics/DoublePendulum.kt` field-for-field.
struct DoublePendulumLink {
    /// Rod length in meters.
    var length: Float = 0.5
    /// Point mass (kg) concentrated at the rod tip.
    var mass: Float = 1
    /// Current angle in radians, measured from the downward vertical
    /// (`0` = hanging straight down, positive = counter-clockwise).
    var angle: Float = .pi / 2
    /// Current angular velocity in radians per second.
    var angularVelocity: Float = 0
}

/// Immutable state of a double-pendulum simulation.
///
/// **iOS-math-duplication note.** This is a hand-port of the Kotlin
/// `DoublePendulumState` / `DoublePendulum` from `sceneview-core`. iOS cannot
/// consume the KMP module directly yet — the `sceneview-core` XCFramework is
/// tracked separately as issue #1033 — so the integrator math lives here in
/// Swift, kept *numerically identical* to the Kotlin source (same Lagrangian
/// equations of motion, same symplectic-Euler step, same `1/240 s` sub-step).
/// When #1033 lands, this struct should be deleted and replaced by the shared
/// Kotlin type. Adapted from [@radcli14](https://github.com/radcli14)'s
/// MIT-licensed [`twolinks`](https://github.com/radcli14/twolinks).
struct DoublePendulumState {
    var link1 = DoublePendulumLink()
    var link2 = DoublePendulumLink()
    /// Fixed world-space hinge position of `link1`.
    var pivot = SIMD3<Float>(0, 0, 0)
    /// Gravitational acceleration magnitude in m/s² (pulls toward -Y).
    var gravity: Float = 9.8
    /// Per-second velocity decay in `[0, 1]`. `0` conserves energy.
    var damping: Float = 0

    /// World-space joint position between `link1` and `link2`.
    var joint: SIMD3<Float> {
        SIMD3(
            pivot.x + link1.length * sin(link1.angle),
            pivot.y - link1.length * cos(link1.angle),
            pivot.z
        )
    }

    /// World-space free tip of `link2`.
    var tip: SIMD3<Float> {
        let j = joint
        return SIMD3(
            j.x + link2.length * sin(link2.angle),
            j.y - link2.length * cos(link2.angle),
            j.z
        )
    }
}

/// Stateless double-pendulum integrator — the Swift twin of Kotlin's
/// `DoublePendulum` object.
enum DoublePendulum {

    /// Largest sub-step `dt`. A chaotic double pendulum needs a fine step for
    /// symplectic Euler to keep its energy bounded; ``step(_:deltaSeconds:)``
    /// sub-steps every frame down to this resolution. Identical to the Kotlin
    /// `MAX_TIME_STEP`.
    static let maxTimeStep: Float = 1.0 / 240.0

    /// Advance the simulation by `deltaSeconds`, sub-stepping for stability.
    static func step(_ state: DoublePendulumState, deltaSeconds: Float) -> DoublePendulumState {
        guard deltaSeconds > 0 else { return state }
        var remaining = min(deltaSeconds, 1) // never simulate >1s in one call
        var current = state
        while remaining > 0 {
            let dt = min(remaining, maxTimeStep)
            current = integrate(current, dt: dt)
            remaining -= dt
        }
        return current
    }

    /// One symplectic-Euler sub-step. Byte-for-byte equivalent to the Kotlin
    /// `integrate` private function.
    private static func integrate(_ state: DoublePendulumState, dt: Float) -> DoublePendulumState {
        let l1 = state.link1.length, l2 = state.link2.length
        let m1 = state.link1.mass, m2 = state.link2.mass
        let a1 = state.link1.angle, a2 = state.link2.angle
        let w1 = state.link1.angularVelocity, w2 = state.link2.angularVelocity
        let g = state.gravity

        let delta = a1 - a2
        let cosD = cos(delta)
        let sinD = sin(delta)

        let den1 = l1 * (2 * m1 + m2 - m2 * cos(2 * delta))
        let den2 = l2 * (2 * m1 + m2 - m2 * cos(2 * delta))
        guard den1 != 0, den2 != 0 else { return state }

        let acc1 = (
            -g * (2 * m1 + m2) * sin(a1)
            - m2 * g * sin(a1 - 2 * a2)
            - 2 * sinD * m2 * (w2 * w2 * l2 + w1 * w1 * l1 * cosD)
        ) / den1

        let acc2 = (
            2 * sinD * (
                w1 * w1 * l1 * (m1 + m2)
                + g * (m1 + m2) * cos(a1)
                + w2 * w2 * l2 * m2 * cosD
            )
        ) / den2

        let dampFactor = min(max(1 - state.damping * dt, 0), 1)
        let newW1 = (w1 + acc1 * dt) * dampFactor
        let newW2 = (w2 + acc2 * dt) * dampFactor
        let newA1 = a1 + newW1 * dt
        let newA2 = a2 + newW2 * dt

        var next = state
        next.link1.angle = newA1
        next.link1.angularVelocity = newW1
        next.link2.angle = newA2
        next.link2.angularVelocity = newW2
        return next
    }
}

// MARK: - Demo view

/// Real-time **double-pendulum** physics demo — a chaotic two-link mechanism.
///
/// The iOS face of the cross-platform demo from
/// [#1221](https://github.com/sceneview/sceneview/issues/1221), a port of
/// [@radcli14](https://github.com/radcli14)'s MIT-licensed
/// [`twolinks`](https://github.com/radcli14/twolinks).
///
/// Mirrors the Android `DoublePendulumDemo`
/// (`samples/android-demo/.../DoublePendulumDemo.kt`): same sliders (upper /
/// lower link length, gravity), same Reset, same metallic links swinging in
/// the XY plane.
///
/// ### iOS-math-duplication note
///
/// Android drives the render from the shared Kotlin `DoublePendulum` in
/// `sceneview-core`. iOS cannot consume that KMP module yet (no XCFramework —
/// issue #1033), so the integrator is hand-ported into the Swift
/// ``DoublePendulum`` enum above, kept numerically identical to the Kotlin
/// source. When #1033 lands, that port collapses back into the shared type.
struct DoublePendulumDemo: View {
    @State private var length1: Float = 0.45
    @State private var length2: Float = 0.40
    @State private var gravity: Float = 9.8
    @State private var generation = 0

    /// Two link entities (thin boxes) updated every tick, plus the timer.
    @State private var coordinator = PendulumCoordinator()

    var body: some View {
        sceneContent
            .demoSettingsSheet { controlsSheet }
            .onDisappear { coordinator.stop() }
    }

    @ViewBuilder
    private var sceneContent: some View {
        ZStack {
            SceneView { root in
                setupScene(root: root)
            }
            .environment(.studio)
            .cameraControls(.orbit)
            .id(generation)
            .ignoresSafeArea()

            VStack {
                Spacer()
                HStack {
                    Text("Chaotic two-link pendulum — shared physics")
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.6))
                    Spacer()
                }
                .padding()
            }
        }
        .background(Color.black)
    }

    /// Builds the scene: two metallic links + a brass pivot marker, and starts
    /// the 60 Hz physics tick. Re-runs whenever `generation` changes (Reset).
    private func setupScene(root: Entity) {
        let pivot = SIMD3<Float>(0, 0.45, 0)
        var state = DoublePendulumState()
        state.link1 = DoublePendulumLink(length: length1, mass: 1, angle: .pi / 2)
        state.link2 = DoublePendulumLink(length: length2, mass: 1, angle: .pi / 2 * 0.6)
        state.pivot = pivot
        state.gravity = gravity
        state.damping = 0.04

        // Each link is a unit-tall thin box; the tick mutates its transform.
        let link1 = GeometryNode.cube(
            size: 1.0,
            material: .pbr(color: .init(white: 0.62, alpha: 1), metallic: 0.85, roughness: 0.25),
            cornerRadius: 0.01
        )
        link1.entity.scale = .init(x: 0.045, y: 1, z: 0.045)

        let link2 = GeometryNode.cube(
            size: 1.0,
            material: .pbr(color: .init(white: 0.62, alpha: 1), metallic: 0.85, roughness: 0.25),
            cornerRadius: 0.01
        )
        link2.entity.scale = .init(x: 0.04, y: 1, z: 0.04)

        // Brass pivot marker.
        let pivotMarker = GeometryNode.sphere(
            radius: 0.045,
            material: .pbr(color: .systemOrange, metallic: 0.9, roughness: 0.2)
        )
        pivotMarker.entity.position = pivot

        root.addChild(link1.entity)
        root.addChild(link2.entity)
        root.addChild(pivotMarker.entity)

        coordinator.start(state: state, link1: link1.entity, link2: link2.entity)
    }

    @ViewBuilder
    private var controlsSheet: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 4) {
                Text(String(format: "Upper link: %.2f m", length1))
                    .font(.subheadline.weight(.semibold))
                Slider(value: $length1, in: 0.2...0.6)
                    .tint(.blue)
                    .onChange(of: length1) { _, _ in restart() }
            }
            VStack(alignment: .leading, spacing: 4) {
                Text(String(format: "Lower link: %.2f m", length2))
                    .font(.subheadline.weight(.semibold))
                Slider(value: $length2, in: 0.2...0.6)
                    .tint(.blue)
                    .onChange(of: length2) { _, _ in restart() }
            }
            VStack(alignment: .leading, spacing: 4) {
                Text(String(format: "Gravity: %.1f m/s²", gravity))
                    .font(.subheadline.weight(.semibold))
                Slider(value: $gravity, in: 1.6...20)
                    .tint(.blue)
                    .onChange(of: gravity) { _, _ in restart() }
            }

            Button {
                restart()
                #if os(iOS)
                HapticManager.mediumTap()
                #endif
            } label: {
                Label("Reset & drop", systemImage: "arrow.counterclockwise")
                    .font(.caption.weight(.semibold))
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(.blue, in: Capsule())
                    .foregroundStyle(.white)
            }
            .buttonStyle(.plain)

            Text("A chaotic two-link pendulum. The integrator is a Swift port of "
                 + "sceneview-core's shared `DoublePendulum.kt` — identical math to "
                 + "the Android demo (XCFramework unification tracked in #1033). "
                 + "Adapted from @radcli14's twolinks.")
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
    }

    private func restart() {
        coordinator.stop()
        generation += 1
    }
}

/// Owns the 60 Hz physics tick and keeps it off the SwiftUI view struct so a
/// `Timer` reference survives view-body recomputation.
@MainActor
private final class PendulumCoordinator {
    private var timer: Timer?
    private var state = DoublePendulumState()
    private weak var link1: Entity?
    private weak var link2: Entity?
    /// Wall-clock timestamp of the previous tick, relative to the timer's
    /// start `Date`. Kept as a main-actor-isolated property (rather than a
    /// captured `var`) so the `@Sendable` timer closure mutates it safely.
    private var lastTick: TimeInterval = 0

    func start(state: DoublePendulumState, link1: Entity, link2: Entity) {
        stop()
        self.state = state
        self.link1 = link1
        self.link2 = link2
        self.lastTick = 0
        // Place the links correctly on frame 0 so there's no initial pop.
        apply()
        let start = Date()
        // The Timer fires on the main run loop, but its closure is `@Sendable`,
        // so the body must explicitly hop onto the main actor before touching
        // `self.state` / `self.lastTick` and calling `apply()`.
        // `MainActor.assumeIsolated` is correct here — the closure already runs
        // on the main thread (the timer is added to `RunLoop.main`) — and it
        // keeps the physics step synchronous, with no scheduling latency.
        let t = Timer.scheduledTimer(withTimeInterval: 1.0 / 60.0, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self else { return }
                let now = Date().timeIntervalSince(start)
                let dt = Float(now - self.lastTick)
                self.lastTick = now
                self.state = DoublePendulum.step(self.state, deltaSeconds: dt)
                self.apply()
            }
        }
        RunLoop.main.add(t, forMode: .common)
        timer = t
    }

    func stop() {
        timer?.invalidate()
        timer = nil
    }

    /// Push the current simulation state onto the two link entities.
    private func apply() {
        if let l1 = link1 {
            Self.layout(entity: l1, from: state.pivot, to: state.joint, thickness: 0.045)
        }
        if let l2 = link2 {
            Self.layout(entity: l2, from: state.joint, to: state.tip, thickness: 0.04)
        }
    }

    /// Position, orient and stretch a unit-tall box so its ends sit at
    /// `from` and `to`. The box mesh is 1 m along local +Y; we scale Y by the
    /// segment length and rotate about Z (the pendulum swings in XY).
    private static func layout(
        entity: Entity,
        from: SIMD3<Float>,
        to: SIMD3<Float>,
        thickness: Float
    ) {
        let dx = to.x - from.x
        let dy = to.y - from.y
        let length = max(sqrt(dx * dx + dy * dy), 0.001)
        // Angle from the +Y axis. atan2(dx, dy) is 0 when the link points up.
        let angle = atan2(dx, dy)
        entity.position = SIMD3(
            (from.x + to.x) * 0.5,
            (from.y + to.y) * 0.5,
            from.z
        )
        // Rotate about -Z so a link leaning right tilts the expected way.
        entity.orientation = simd_quatf(angle: -angle, axis: SIMD3(0, 0, 1))
        entity.scale = SIMD3(thickness, length, thickness)
    }
}
