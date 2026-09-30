import SwiftUI
import RealityKit
import SceneViewSwift

// MARK: - Ball kinds

/// The three ball materials of the tray. Same radius, restitution, rolling resistance, mass,
/// colour and surface as the Android `BallKind` in
/// `samples/android-demo/.../demos/RollingBallsDemo.kt`.
enum RollingBallKind: String, CaseIterable, Identifiable {
    case rubber, steel, foam

    var id: String { rawValue }

    var label: String {
        switch self {
        case .rubber: return "Rubber"
        case .steel: return "Steel"
        case .foam: return "Foam"
        }
    }

    /// Sphere radius in metres.
    var radius: Float {
        switch self {
        case .rubber: return 0.075
        case .steel: return 0.06
        case .foam: return 0.085
        }
    }

    /// Fraction of the normal speed kept after a bounce.
    var restitution: Float {
        switch self {
        case .rubber: return 0.82
        case .steel: return 0.35
        case .foam: return 0.2
        }
    }

    /// Rolling-resistance coefficient: the deceleration of a rolling ball is
    /// `mu * |g.y|` plus a speed-proportional drag (see
    /// ``RollingBallsSimulation/rolling(velocity:gravity:resistance:dt:)``).
    var rollingResistance: Float {
        switch self {
        case .rubber: return 0.03
        case .steel: return 0.008
        case .foam: return 0.08
        }
    }

    /// Mass in kilograms — only the ratio matters for the pair impulse.
    var mass: Float {
        switch self {
        case .rubber: return 1
        case .steel: return 4
        case .foam: return 0.3
        }
    }

    var color: UIColor {
        let ramp = SceneViewTokens.Stage.shapeRamp
        switch self {
        case .rubber: return ramp[0]
        case .steel: return ramp[2]
        case .foam: return ramp[3]
        }
    }

    /// SwiftUI swatch of ``color`` for the material chips.
    var swatch: Color {
        #if os(macOS)
        Color(nsColor: color)
        #else
        Color(uiColor: color)
        #endif
    }

    var metallic: Float { self == .steel ? 1 : 0 }

    var roughness: Float {
        switch self {
        case .rubber: return 0.6
        case .steel: return 0.22
        case .foam: return 0.95
        }
    }
}

// MARK: - Simulation (Swift port of the Android tray)

/// One ball of the tray: a sphere integrated by ``RollingBallsSimulation``.
struct RollingBall {
    let id: Int
    let kind: RollingBallKind
    var position: SIMD3<Float>
    var velocity: SIMD3<Float>
}

/// Deterministic rigid-sphere tray, stepped at a fixed 120 Hz.
///
/// **iOS-math-duplication note.** Android drives this from `PhysicsBody.step` (gravity and the
/// floor bounce, `sceneview/.../node/PhysicsNode.kt`) plus the tray loop in the Android
/// `RollingBallsDemo.kt` (rails, rolling resistance, mass-weighted sphere impulses). iOS cannot
/// consume either yet (#1033), so both are hand-ported here, kept numerically identical: same
/// constants, same id-ordered loop, same opening shot. Reset therefore replays the same
/// sequence of impacts on both platforms.
struct RollingBallsSimulation {
    static let gravity: Float = -9.8
    static let floor: Float = -0.5
    static let traySize: Float = 1.6
    static let railThickness: Float = 0.03
    static let railHeight: Float = 0.16
    static let stepSeconds = Float(8_333_333.0 / 1_000_000_000.0)
    static let dropHeight: Float = 0.2
    static let dropLayer: Float = 0.2
    static let maxBodies = 30
    static let impactSpeed: Float = 0.2

    /// Balls in ascending id order — the order every loop visits them in.
    private(set) var balls: [RollingBall] = []
    private(set) var collisions = 0
    private var nextId = 0
    private var dropCount = 0
    /// Gravity in the tray's frame (see ``trayLocalGravity(pitchDegrees:rollDegrees:)``).
    var gravity = SIMD3<Float>(0, RollingBallsSimulation.gravity, 0)

    /// Clears the tray and lays out the opening shot: a steel cue rolling into a six-ball
    /// rubber pyramid.
    mutating func reset() {
        balls.removeAll()
        collisions = 0
        dropCount = 0
        let r: Float = 0.075
        let rowHeight = r * Float(3).squareRoot()
        let base: Float = 0.05
        let y0 = Self.floor + r
        let pyramid: [SIMD3<Float>] = [
            SIMD3(base, y0, 0), SIMD3(base + 2 * r, y0, 0), SIMD3(base + 4 * r, y0, 0),
            SIMD3(base + r, y0 + rowHeight, 0), SIMD3(base + 3 * r, y0 + rowHeight, 0),
            SIMD3(base + 2 * r, y0 + 2 * rowHeight, 0),
        ]
        add(.steel, at: SIMD3(-0.65, Self.floor + 0.18, 0), velocity: SIMD3(1.9, 0.4, 0))
        for p in pyramid { add(.rubber, at: p, velocity: .zero) }
    }

    /// Drops `count` balls of `kind` on a golden-angle spiral, recycling the oldest past
    /// ``maxBodies``.
    mutating func drop(_ kind: RollingBallKind, count: Int) {
        for k in 0..<count {
            let n = Float(dropCount)
            dropCount += 1
            let angle = n * 2.3999632
            let fraction = (n * 0.618034).truncatingRemainder(dividingBy: 1)
            let spread = 0.1 + 0.4 * fraction
            let y = Self.dropHeight + Float(k / 5) * Self.dropLayer
            if balls.count >= Self.maxBodies { balls.removeFirst() }
            add(kind, at: SIMD3(spread * cos(angle), y, spread * sin(angle)), velocity: .zero)
        }
    }

    mutating func add(_ kind: RollingBallKind, at p: SIMD3<Float>, velocity v: SIMD3<Float>) {
        balls.append(RollingBall(id: nextId, kind: kind, position: p, velocity: v))
        nextId += 1
    }

    /// One fixed 120 Hz step.
    mutating func step() {
        let dt = min(max(Self.stepSeconds, 0), 0.05)
        let bound = Self.traySize / 2 - Self.railThickness / 2
        for i in balls.indices {
            var b = balls[i]
            let r = b.kind.radius
            let before = b.velocity
            // PhysicsBody.step: semi-implicit Euler, then the floor bounce.
            var v = b.velocity + gravity * dt
            var p = b.position + v * dt
            if p.y < Self.floor + r {
                p.y = Self.floor + r
                v.y = -v.y * b.kind.restitution
            }
            if before.y < -Self.impactSpeed && v.y > 0 { collisions += 1 }
            let limit = bound - r
            var vx = v.x
            var vz = v.z
            if abs(p.x) > limit && p.x * vx > 0 {
                if abs(vx) >= Self.impactSpeed { collisions += 1 }
                vx = -vx * b.kind.restitution
            }
            if abs(p.z) > limit && p.z * vz > 0 {
                if abs(vz) >= Self.impactSpeed { collisions += 1 }
                vz = -vz * b.kind.restitution
            }
            if p.y <= Self.floor + r + 0.001 && abs(v.y) < 0.2 {
                let rolled = Self.rolling(velocity: SIMD3(vx, v.y, vz), gravity: gravity,
                                          resistance: b.kind.rollingResistance, dt: dt)
                vx = rolled.x
                vz = rolled.z
            }
            if abs(p.x) > limit || abs(p.z) > limit {
                p.x = min(max(p.x, -limit), limit)
                p.z = min(max(p.z, -limit), limit)
            }
            b.position = p
            b.velocity = SIMD3(vx, v.y, vz)
            balls[i] = b
        }
        for a in balls.indices {
            for b in balls.indices where b > a {
                if resolvePair(a, b) { collisions += 1 }
            }
        }
    }

    /// Rolling contact of a solid sphere on the tray floor, applied after the step has already
    /// integrated the full gravity.
    ///
    /// 1. A solid sphere that rolls without slipping accelerates at `5/7 · g·sinθ`, not
    ///    `g·sinθ`: the step already added the whole horizontal gravity, so 2/7 of it comes off.
    /// 2. Rolling resistance: a constant `mu · |g.y|` plus a `0.3 · speed` drag, which stops the
    ///    ball outright rather than reversing it. A slope whose 5/7 pull is below `mu · |g.y|`
    ///    never starts a resting ball, so a nearly level tray stays still.
    ///
    /// Pure, so the numbers are pinned by `RollingBallsSimulationTests`; Android's tray loop uses
    /// the same two steps with the same coefficients.
    static func rolling(velocity v: SIMD3<Float>, gravity g: SIMD3<Float>,
                        resistance mu: Float, dt: Float) -> SIMD3<Float> {
        var vx = v.x - (2.0 / 7.0) * g.x * dt
        var vz = v.z - (2.0 / 7.0) * g.z * dt
        let speed = (vx * vx + vz * vz).squareRoot()
        let drop = (mu * abs(g.y) + 0.3 * speed) * dt
        if speed <= drop {
            vx = 0
            vz = 0
        } else {
            let keep = (speed - drop) / speed
            vx *= keep
            vz *= keep
        }
        return SIMD3(vx, v.y, vz)
    }

    /// Separates two overlapping spheres by inverse mass and exchanges the impulse along the
    /// contact normal. Returns `true` when they met hard enough to count as an impact.
    private mutating func resolvePair(_ ia: Int, _ ib: Int) -> Bool {
        var a = balls[ia]
        var b = balls[ib]
        let d = b.position - a.position
        let distanceSquared = simd_dot(d, d)
        let diameter = a.kind.radius + b.kind.radius
        if distanceSquared >= diameter * diameter { return false }
        let distance = distanceSquared.squareRoot()
        let n = distance > 0.00001 ? d / distance : SIMD3<Float>(1, 0, 0)
        let invA = 1 / a.kind.mass
        let invB = 1 / b.kind.mass
        let invSum = invA + invB
        let overlap = diameter - distance
        a.position -= n * (overlap * invA / invSum)
        a.position.y = max(a.position.y, Self.floor + a.kind.radius)
        b.position += n * (overlap * invB / invSum)
        b.position.y = max(b.position.y, Self.floor + b.kind.radius)
        defer { balls[ia] = a; balls[ib] = b }
        let approach = simd_dot(b.velocity - a.velocity, n)
        if approach >= 0 { return false }
        let e = min(a.kind.restitution, b.kind.restitution)
        let impulse = -(1 + e) * approach / invSum
        a.velocity -= n * (impulse * invA)
        b.velocity += n * (impulse * invB)
        return approach < -Self.impactSpeed
    }

    /// World gravity expressed in the tilted tray's frame. The tray pivot is rotated by
    /// `Rz(roll) * Rx(pitch)`; a world-constant vector seen from inside the tray is the inverse
    /// rotation applied to it. Exactly `(0, -9.8, 0)` at zero tilt.
    static func trayLocalGravity(pitchDegrees: Float, rollDegrees: Float) -> SIMD3<Float> {
        let world = SIMD3<Float>(0, gravity, 0)
        if pitchDegrees == 0 && rollDegrees == 0 { return world }
        return trayRotation(pitchDegrees: pitchDegrees, rollDegrees: rollDegrees).inverse.act(world)
    }

    /// Positive pitch lowers the near (+Z) edge; positive roll raises the right (+X) edge.
    static func trayRotation(pitchDegrees: Float, rollDegrees: Float) -> simd_quatf {
        let toRadians = Float.pi / 180
        return simd_quatf(angle: rollDegrees * toRadians, axis: SIMD3(0, 0, 1))
            * simd_quatf(angle: pitchDegrees * toRadians, axis: SIMD3(1, 0, 0))
    }
}

// MARK: - Demo view

/// **Rolling Balls** — drop rubber, steel and foam balls on a railed tray, tilt it, knock the
/// opening pyramid over.
///
/// The iOS face of the Android `RollingBallsDemo` (#4083): same 1.6 m tray, same ball
/// parameters, same opening shot and the same deterministic 120 Hz simulation (ported in
/// ``RollingBallsSimulation``). Tilt is on from the start: a drag tips the tray within ±35° and
/// gravity is rotated into the tray's frame, so the balls roll downhill while the simulation
/// stays flat. Tilt off hands the drag back to the camera orbit.
struct RollingBallsDemo: View {
    @State private var coordinator = RollingBallsCoordinator()
    @State private var selectedKind: RollingBallKind = .rubber
    @State private var tiltEnabled = true
    @State private var cameraPose: SceneCameraPose?

    private static let maxTilt = RollingBallsCoordinator.maxTilt
    private static let minElevation: Float = 12 * .pi / 180
    private static let maxElevation: Float = 75 * .pi / 180

    var body: some View {
        GeometryReader { proxy in
            stage
                .onAppear { cameraPose = Self.framingPose(for: proxy.size) }
                .onChange(of: proxy.size) { _, size in cameraPose = Self.framingPose(for: size) }
        }
        .ignoresSafeArea()
        .demoChrome(
            dock: [
                DockItem(icon: "move.3d", label: "Tilt", selected: tiltEnabled) {
                    tiltEnabled.toggle()
                    coordinator.endDrag()
                },
                DockItem(icon: "arrow.counterclockwise", label: "Reset") { reset() },
            ],
            accent: DockItem(icon: "arrow.down.circle.fill", label: "Drop") {
                drop(count: 1)
            },
            onReset: { reset() },
            accessory: {
                VStack(spacing: SceneViewTokens.Space.sm) {
                    DemoHint(tiltEnabled ? "\(countsText) · \(Self.tiltHint)" : countsText)
                    materialStrip
                }
            },
            controls: { controlsSheet }
        )
        // The content closure runs once per RealityView, so a return to this screen restarts
        // the tick here rather than through `install(in:)`.
        .onAppear {
            coordinator.setDarkStage(colorScheme == .dark)
            coordinator.start()
        }
        .onChange(of: colorScheme) { _, scheme in coordinator.setDarkStage(scheme == .dark) }
        .onDisappear { coordinator.stop() }
    }

    private var countsText: String {
        "Bodies: \(coordinator.bodies) · impacts: \(coordinator.impacts)"
    }

    @Environment(\.colorScheme) private var colorScheme

    private static let tiltHint =
        "Tilt is on · drag the scene to tip the tray and the balls roll downhill. "
        + "Turn it off to orbit the camera again."

    // MARK: Stage

    private var stage: some View {
        ZStack {
            // Themed stage sky behind a skybox-less studio IBL — never a black void.
            LinearGradient(colors: [SceneViewTokens.Stage.skyHorizon, SceneViewTokens.Stage.skyGround],
                           startPoint: .top, endPoint: .bottom)
            SceneView { root in
                coordinator.install(in: root)
            }
            .environment(Self.environment)
            .mainLight(.custom(coordinator.keyLight))
            .cameraControls(.orbit)
            .autoCenterContent(false)
            .cameraPose(cameraPose)
            .onCameraChanged { pose in
                Task { @MainActor in
                    coordinator.cameraAzimuth = pose.azimuth
                    clampElevation(pose)
                }
            }
            .cameraGesturesEnabled(!tiltEnabled)
            if tiltEnabled {
                Color.clear
                    .contentShape(Rectangle())
                    .gesture(tiltDrag)
                    .accessibilityHidden(true)
            }
        }
    }

    private static var environment: SceneEnvironment {
        var studio = SceneEnvironment.studio
        studio.showSkybox = false
        return studio
    }

    /// The drag only moves the tilt *target*; the coordinator's display-link tick eases the
    /// rendered tray onto it. The last translation lives in the coordinator (not in `@State`), so
    /// a drag event never re-renders this view.
    private var tiltDrag: some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in coordinator.drag(to: value.translation) }
            .onEnded { value in
                coordinator.drag(to: value.translation)
                coordinator.endDrag()
            }
    }

    /// Keeps the orbit between 12° and 75° above the tray, like Android's polar range.
    private func clampElevation(_ pose: SceneCameraPose) {
        let clamped = min(max(pose.elevation, Self.minElevation), Self.maxElevation)
        guard clamped != pose.elevation else { return }
        var fixed = pose
        fixed.elevation = clamped
        cameraPose = fixed
    }

    // MARK: Framing

    /// The camera pose that fits the tray between the chrome's top and bottom scrims, seen from
    /// 32° above the near edge: the level tray plus the balls' bounce room at 92 % of the band,
    /// and the tray tipped to ±35° on both axes (it hangs 0.5 m under its pivot, so it swings)
    /// within the full band.
    static func framingPose(for size: CGSize) -> SceneCameraPose {
        let elevation: Float = 32 * .pi / 180
        let extent = SIMD3<Float>(1.7, 0.75, 1.7)
        let target = SIMD3<Float>(0, -0.15, 0)
        var points: [(point: SIMD3<Float>, fill: Float)] = []
        for sx in [-1, 1] as [Float] {
            for sy in [-1, 1] as [Float] {
                for sz in [-1, 1] as [Float] {
                    points.append((target + extent / 2 * SIMD3(sx, sy, sz), 0.92))
                }
            }
        }
        let half = RollingBallsSimulation.traySize / 2
        let railTop = RollingBallsSimulation.floor + RollingBallsSimulation.railHeight
        let tilts: [Float] = [-maxTilt, 0, maxTilt]
        for pitch in tilts {
            for roll in tilts {
                let rotation = RollingBallsSimulation.trayRotation(pitchDegrees: pitch,
                                                                   rollDegrees: roll)
                for sx in [-1, 1] as [Float] {
                    for sz in [-1, 1] as [Float] {
                        for y in [RollingBallsSimulation.floor, railTop] {
                            points.append((rotation.act(SIMD3(sx * half, y, sz * half)), 1))
                        }
                    }
                }
            }
        }
        let width = Float(max(size.width, 1))
        let height = Float(max(size.height, 1))
        let top = Float(SceneViewTokens.Chrome.scrimTop)
        let bottom = Float(SceneViewTokens.Chrome.scrimBottomMin)
        let band = max(height - top - bottom, height * 0.35)
        let tanV = tan(Float.pi / 6)
        let tanH = tanV * width / height
        let tanBand = tanV * band / height
        let up = SIMD3<Float>(0, cos(elevation), -sin(elevation))
        let back = SIMD3<Float>(0, sin(elevation), cos(elevation))
        var distance: Float = 1
        for (point, fill) in points {
            let offset = point - target
            let depth = simd_dot(offset, back)
            distance = max(distance, depth + abs(offset.x) / (tanH * fill))
            distance = max(distance, depth + abs(simd_dot(offset, up)) / (tanBand * fill))
        }
        // Centre the tray on the band, not on the screen: shift the look-at point along the
        // camera's up axis by the band's offset from the screen centre.
        let bandCentreOffset = (top + band / 2) - height / 2
        let shift = bandCentreOffset / (height / 2) * distance * tanV
        return SceneCameraPose(azimuth: 0, elevation: elevation, distance: distance,
                               target: target + up * shift)
    }

    // MARK: Accessory

    private var materialStrip: some View {
        HStack(spacing: 0) {
            ForEach(RollingBallKind.allCases) { kind in
                let selected = kind == selectedKind
                Button {
                    selectedKind = kind
                    drop(count: 1)
                } label: {
                    HStack(spacing: SceneViewTokens.Space.xs) {
                        Circle()
                            .fill(kind.swatch)
                            .frame(width: SceneViewTokens.Space.md, height: SceneViewTokens.Space.md)
                            .overlay(Circle().strokeBorder(SceneViewTokens.Glass.onGlass.opacity(0.5),
                                                           lineWidth: 1))
                        Text(kind.label)
                            .font(SceneViewTokens.TypeScale.chromeLabel)
                            .lineLimit(1)
                            .fixedSize()
                    }
                    .foregroundStyle(selected ? SceneViewTokens.Stage.background
                                              : SceneViewTokens.Glass.onGlass)
                    .padding(.horizontal, SceneViewTokens.Glass.pillPaddingHorizontal)
                    .frame(minHeight: SceneViewTokens.Glass.pillHeight)
                    .background {
                        if selected { Capsule().fill(SceneViewTokens.Glass.onGlass) }
                    }
                    .frame(minHeight: SceneViewTokens.Layout.touchTarget)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Drop \(kind.label.lowercased()) ball")
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
        .padding(.horizontal, (SceneViewTokens.Layout.touchTarget - SceneViewTokens.Glass.pillHeight) / 2)
        .glassBackground(in: Capsule(), interactive: true, id: "options")
        .clipShape(Capsule())
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("demo-options")
    }

    // MARK: Settings sheet

    @ViewBuilder
    private var controlsSheet: some View {
        VStack(alignment: .leading, spacing: SceneViewTokens.Space.md) {
            Text(countsText)
                .font(.subheadline.weight(.semibold).monospacedDigit())

            HStack(spacing: SceneViewTokens.Space.sm) {
                Button {
                    drop(count: 10)
                } label: {
                    Label("Drop 10", systemImage: "square.stack.3d.down.forward")
                }
                Button {
                    coordinator.level()
                } label: {
                    Label("Level", systemImage: "level")
                }
                .disabled(coordinator.pitchTarget == 0 && coordinator.rollTarget == 0)
            }
            .buttonStyle(.bordered)
            .tint(SceneViewTokens.HomeColor.primary)

            Text("PhysicsBody.step supplies gravity and the floor bounce; this sample adds "
                 + "mass-weighted sphere impulses at a fixed 120 Hz step. Rubber bounces, steel "
                 + "is heavy and barely bounces, foam is light and soaks up the hit. Reset "
                 + "replays the same opening shot.")
                .font(.caption)
                .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)

            Text("Tilt the tray")
                .font(.subheadline.weight(.semibold))
            LabeledSlider(label: "Pitch · toward you", value: userTilt(\.pitchTarget),
                          range: -Self.maxTilt...Self.maxTilt, decimals: 0, unit: "°")
            LabeledSlider(label: "Roll · left and right", value: userTilt(\.rollTarget),
                          range: -Self.maxTilt...Self.maxTilt, decimals: 0, unit: "°")
            Text("The tray hangs off one pivot node and the gravity vector is rotated into its "
                 + "frame, so the floor plane and rails stay flat in the simulation while the "
                 + "balls accelerate down the slope.")
                .font(.caption)
                .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
        }
    }

    /// A slider binding on one tilt axis's target. A user write cancels a running Level, so
    /// the easing never fights the finger.
    private func userTilt(_ axis: KeyPath<RollingBallsCoordinator, Float>) -> Binding<Float> {
        Binding(
            get: { coordinator[keyPath: axis] },
            set: { value in coordinator.setTarget(axis, value) }
        )
    }

    // MARK: Actions

    private func drop(count: Int) {
        coordinator.drop(selectedKind, count: count)
        #if os(iOS)
        SceneViewHaptic.shared.light()
        #endif
    }

    private func reset() {
        coordinator.reset()
        #if os(iOS)
        SceneViewHaptic.shared.medium()
        #endif
    }
}

// MARK: - Coordinator

/// Owns the tray entities, the simulation and the frame tick, so they survive view-body
/// recomputation.
///
/// **Target vs rendered tilt.** The drag and the sliders only write a *target* inclination,
/// accumulated synchronously from every gesture delta and clamped to ±35°. Once per display
/// frame, ``tick(at:)`` eases the *rendered* inclination onto it (exponential smoothing,
/// `tau` 50 ms while dragging, 120 ms for Level), then derives the pivot orientation and the
/// simulation's gravity from that same rendered value before the fixed 120 Hz steps — so the
/// tray on screen and the slope the balls feel never disagree, and the tray moves at the
/// display's rate rather than at the touch rate. Same numbers as Android's tray loop.
@MainActor
@Observable
final class RollingBallsCoordinator {
    static let maxTilt: Float = 35
    /// Degrees of tilt per point of drag — Android's 0.06°/px at ~2.6 px/pt.
    static let tiltPerPoint: Float = 0.15
    /// Smoothing time constant while the finger or a slider drives the target.
    static let dragTau: Double = 0.05
    /// Smoothing time constant of Level (target back to 0).
    static let levelTau: Double = 0.12

    /// Target pitch in degrees; positive lowers the near edge.
    private(set) var pitchTarget: Float = 0
    /// Target roll in degrees; positive raises the right edge.
    private(set) var rollTarget: Float = 0
    private(set) var bodies = 0
    private(set) var impacts = 0

    /// Rendered tilt, eased onto the target once per frame. Not observed: it changes every frame
    /// and nothing in SwiftUI draws it.
    @ObservationIgnored private(set) var pitch: Float = 0
    @ObservationIgnored private(set) var roll: Float = 0
    /// Camera azimuth in radians, so a drag tips the tray relative to the screen after an orbit.
    @ObservationIgnored var cameraAzimuth: Float = 0

    /// Scene key light: 5 000 lux from above-front-right, with shadows. Kept here so
    /// `.mainLight(.custom(_:))` sees the same entity on every body pass.
    let keyLight: LightNode = {
        let light = LightNode.directional(color: .white, intensity: 5_000, castsShadow: true)
        light.position = SIMD3(0.3, 1, 0.5)
        light.lookAt(.zero)
        return light
    }()

    @ObservationIgnored private var simulation = RollingBallsSimulation()
    private let pivot = Entity()
    @ObservationIgnored private var ballEntities: [Int: Entity] = [:]
    /// Ball positions before the last fixed step, to draw each frame between two steps.
    @ObservationIgnored private var previousPositions: [Int: SIMD3<Float>] = [:]
    @ObservationIgnored private var frameLink: CosmosFrameLink?
    @ObservationIgnored private var lastTick: CFTimeInterval?
    @ObservationIgnored private var lastPublish: CFTimeInterval = 0
    @ObservationIgnored private var accumulator: TimeInterval = 0
    @ObservationIgnored private var built = false
    @ObservationIgnored private var floorEntity: Entity?
    @ObservationIgnored private var darkStage = false
    /// `true` while Level eases the tray back to 0 with ``levelTau``.
    @ObservationIgnored private var leveling = false
    /// Translation of the running drag at its previous event, `nil` between drags.
    @ObservationIgnored private var lastTranslation: CGSize?

    private static let step: TimeInterval = 8_333_333 / 1_000_000_000
    /// How often the counters reach SwiftUI; the simulation itself is not throttled.
    private static let publishInterval: CFTimeInterval = 0.1

    /// Attaches the tray to `root`, building it and the opening shot on first use, and starts
    /// the tick.
    func install(in root: Entity) {
        if !built {
            buildTray()
            simulation.reset()
            syncEntities()
            built = true
        }
        pivot.removeFromParent()
        root.addChild(pivot)
        start()
    }

    func drop(_ kind: RollingBallKind, count: Int) {
        simulation.drop(kind, count: count)
        syncEntities()
    }

    /// Replays the opening shot and snaps the tray level — target and rendered tilt at once.
    func reset() {
        leveling = false
        setTargets(pitch: 0, roll: 0)
        pitch = 0
        roll = 0
        applyTilt()
        simulation.reset()
        previousPositions.removeAll()
        syncEntities()
        publishCounts()
    }

    /// Eases the tray back to level (``levelTau``).
    func level() {
        guard pitchTarget != 0 || rollTarget != 0 || pitch != 0 || roll != 0 else { return }
        leveling = true
        setTargets(pitch: 0, roll: 0)
    }

    /// Accumulates one drag event into the tilt target. Every event's delta since the previous
    /// one is applied, so no motion is lost however fast the finger moves.
    func drag(to translation: CGSize) {
        let previous = lastTranslation ?? .zero
        lastTranslation = translation
        let dx = Float(translation.width - previous.width)
        let dy = Float(translation.height - previous.height)
        guard dx != 0 || dy != 0 else { return }
        leveling = false
        let delta = Self.tiltDelta(dx: dx, dy: dy, azimuth: cameraAzimuth)
        setTargets(pitch: pitchTarget + delta.pitch, roll: rollTarget + delta.roll)
    }

    func endDrag() { lastTranslation = nil }

    /// A slider write on one axis's target.
    func setTarget(_ axis: KeyPath<RollingBallsCoordinator, Float>, _ value: Float) {
        leveling = false
        if axis == \RollingBallsCoordinator.pitchTarget {
            setTargets(pitch: value, roll: rollTarget)
        } else {
            setTargets(pitch: pitchTarget, roll: value)
        }
    }

    /// Recolours the tray floor for the colour scheme (Android's `StageSky.floor`).
    func setDarkStage(_ dark: Bool) {
        guard dark != darkStage else { return }
        darkStage = dark
        if built { buildFloor() }
    }

    func stop() {
        frameLink?.invalidate()
        frameLink = nil
        lastTick = nil
    }

    /// Starts the display-link tick; a no-op while it is already running.
    func start() {
        guard frameLink == nil else { return }
        frameLink = CosmosFrameLink { [weak self] timestamp in self?.tick(at: timestamp) }
    }

    // MARK: Pure tilt maths

    /// Pitch and roll deltas, in degrees, for a drag of (`dx`, `dy`) points seen from a camera
    /// at `azimuth` radians. The screen delta is turned into the ground-plane direction the
    /// finger points at (screen right is `(cos a, 0, -sin a)`, screen down is
    /// `(sin a, 0, cos a)`), and that side of the tray goes down: the balls roll the way the
    /// finger moves wherever the camera is.
    static func tiltDelta(dx: Float, dy: Float, azimuth: Float) -> (pitch: Float, roll: Float) {
        let c = cos(azimuth)
        let s = sin(azimuth)
        let worldX = dx * c + dy * s
        let worldZ = -dx * s + dy * c
        return (worldZ * tiltPerPoint, -worldX * tiltPerPoint)
    }

    /// One step of exponential smoothing: `current` moves `1 - exp(-dt / tau)` of the way to
    /// `target`, which is frame-rate independent.
    static func smoothed(_ current: Float, toward target: Float, dt: Double, tau: Double) -> Float {
        let alpha = Float(1 - exp(-dt / tau))
        let next = current + (target - current) * alpha
        return abs(target - next) < 0.001 ? target : next
    }

    // MARK: Private

    private func setTargets(pitch newPitch: Float, roll newRoll: Float) {
        let p = min(max(newPitch, -Self.maxTilt), Self.maxTilt)
        let r = min(max(newRoll, -Self.maxTilt), Self.maxTilt)
        if pitchTarget != p { pitchTarget = p }
        if rollTarget != r { rollTarget = r }
    }

    /// One display frame: ease the rendered tilt, apply it to the pivot and the gravity, run
    /// the fixed steps due, then draw the balls between the last two steps.
    private func tick(at timestamp: CFTimeInterval) {
        let elapsed = min(max(timestamp - (lastTick ?? timestamp), 0), 0.1)
        lastTick = timestamp

        if pitch != pitchTarget || roll != rollTarget {
            let tau = leveling ? Self.levelTau : Self.dragTau
            pitch = Self.smoothed(pitch, toward: pitchTarget, dt: elapsed, tau: tau)
            roll = Self.smoothed(roll, toward: rollTarget, dt: elapsed, tau: tau)
            applyTilt()
        } else if leveling {
            leveling = false
        }

        accumulator += elapsed
        while accumulator >= Self.step {
            for ball in simulation.balls { previousPositions[ball.id] = ball.position }
            simulation.step()
            accumulator -= Self.step
        }
        let alpha = Float(accumulator / Self.step)
        for ball in simulation.balls {
            let before = previousPositions[ball.id] ?? ball.position
            ballEntities[ball.id]?.position = before + (ball.position - before) * alpha
        }
        if timestamp - lastPublish >= Self.publishInterval {
            lastPublish = timestamp
            publishCounts()
        }
    }

    /// Pivot orientation and tray-frame gravity, both from the rendered tilt.
    private func applyTilt() {
        pivot.orientation = RollingBallsSimulation.trayRotation(pitchDegrees: pitch, rollDegrees: roll)
        simulation.gravity = RollingBallsSimulation.trayLocalGravity(pitchDegrees: pitch,
                                                                     rollDegrees: roll)
    }

    private func publishCounts() {
        let count = simulation.balls.count
        if bodies != count { bodies = count }
        if impacts != simulation.collisions { impacts = simulation.collisions }
    }

    /// Floor slab (top face at the simulation floor) and four rails, all under the pivot.
    private func buildTray() {
        let size = RollingBallsSimulation.traySize
        let floorY = RollingBallsSimulation.floor
        let railHeight = RollingBallsSimulation.railHeight
        let railThickness = RollingBallsSimulation.railThickness

        buildFloor()

        for side in [-1, 1] as [Float] {
            let railY = floorY + railHeight / 2
            let alongZ = GeometryNode.cube(
                size: 1,
                material: .pbr(color: SceneViewTokens.Stage.trayRail, metallic: 0, roughness: 0.5)
            )
            alongZ.entity.scale = SIMD3(railThickness, railHeight, size)
            alongZ.entity.position = SIMD3(side * size / 2, railY, 0)
            pivot.addChild(alongZ.entity)

            let alongX = GeometryNode.cube(
                size: 1,
                material: .pbr(color: SceneViewTokens.Stage.trayRail, metallic: 0, roughness: 0.5)
            )
            alongX.entity.scale = SIMD3(size, railHeight, railThickness)
            alongX.entity.position = SIMD3(0, railY, side * size / 2)
            pivot.addChild(alongX.entity)
        }
    }

    /// The floor slab, its top face at the simulation floor, in the current scheme's colour.
    private func buildFloor() {
        let size = RollingBallsSimulation.traySize
        let slabThickness: Float = 0.02
        floorEntity?.removeFromParent()
        let floor = GeometryNode.cube(
            size: 1,
            material: .pbr(color: SceneViewTokens.Stage.trayFloor(dark: darkStage),
                           metallic: 0, roughness: 0.8)
        )
        floor.entity.scale = SIMD3(size, slabThickness, size)
        floor.entity.position = SIMD3(0, RollingBallsSimulation.floor - slabThickness / 2, 0)
        pivot.addChild(floor.entity)
        floorEntity = floor.entity
    }

    /// Adds entities for new balls and removes those of recycled or reset ones.
    private func syncEntities() {
        let live = Set(simulation.balls.map(\.id))
        for (id, entity) in ballEntities where !live.contains(id) {
            entity.removeFromParent()
            ballEntities[id] = nil
            previousPositions[id] = nil
        }
        for ball in simulation.balls {
            // Balls already on the tray keep the interpolated position the tick drew.
            if ballEntities[ball.id] != nil { continue }
            let node = GeometryNode.sphere(
                radius: ball.kind.radius,
                material: .pbr(color: ball.kind.color, metallic: ball.kind.metallic,
                               roughness: ball.kind.roughness)
            )
            node.entity.position = ball.position
            pivot.addChild(node.entity)
            ballEntities[ball.id] = node.entity
        }
        publishCounts()
    }
}
