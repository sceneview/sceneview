import SwiftUI
import RealityKit
import SceneViewSwift

// MARK: - Ball kinds

/// The three ball materials of the tray. Same radius, restitution, rolling friction, mass,
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

    /// Horizontal velocity kept per 120 Hz step while rolling on the floor.
    var rollFriction: Float {
        switch self {
        case .rubber: return 0.99
        case .steel: return 0.997
        case .foam: return 0.96
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
/// `RollingBallsDemo.kt` (rails, rolling friction, mass-weighted sphere impulses). iOS cannot
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

    private mutating func add(_ kind: RollingBallKind, at p: SIMD3<Float>, velocity v: SIMD3<Float>) {
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
            if p.y <= Self.floor + r && abs(v.y) < 0.2 {
                vx *= b.kind.rollFriction
                vz *= b.kind.rollFriction
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
/// ``RollingBallsSimulation``). With Tilt on, a drag tips the tray within ±20° and gravity is
/// rotated into the tray's frame, so the balls roll downhill while the simulation stays flat.
struct RollingBallsDemo: View {
    @State private var coordinator = RollingBallsCoordinator()
    @State private var selectedKind: RollingBallKind = .rubber
    @State private var tiltEnabled = false
    @State private var cameraPose: SceneCameraPose?
    @State private var lastDrag: CGSize = .zero

    private static let maxTilt: Float = 20
    /// Degrees of tilt per point of drag — Android's 0.06°/px at ~2.6 px/pt.
    private static let tiltPerPoint: Float = 0.15
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
                    lastDrag = .zero
                },
                DockItem(icon: "arrow.counterclockwise", label: "Reset") { reset() },
            ],
            accent: DockItem(icon: "arrow.down.circle.fill", label: "Drop") {
                drop(count: 1)
            },
            onReset: { reset() },
            accessory: {
                VStack(spacing: 8) {
                    DemoHint(tiltEnabled ? "\(countsText) · \(Self.tiltHint)" : countsText)
                    materialStrip
                }
            },
            controls: { controlsSheet }
        )
        // The content closure runs once per RealityView, so a return to this screen restarts
        // the tick here rather than through `install(in:)`.
        .onAppear { coordinator.start() }
        .onDisappear { coordinator.stop() }
    }

    private var countsText: String {
        "Bodies: \(coordinator.bodies) · impacts: \(coordinator.impacts)"
    }

    private static let tiltHint =
        "Tilt is on · drag the scene to tip the tray and the balls roll downhill. "
        + "Turn it off to orbit the camera again."

    // MARK: Stage

    private var stage: some View {
        ZStack {
            // Neutral studio grey behind a skybox-less studio IBL — never a black void.
            SceneViewTokens.Stage.studioBackdrop
            SceneView { root in
                coordinator.install(in: root)
            }
            .environment(Self.environment)
            .mainLight(.custom(coordinator.keyLight))
            .cameraControls(.orbit)
            .autoCenterContent(false)
            .cameraPose(cameraPose)
            .onCameraChanged { pose in
                Task { @MainActor in clampElevation(pose) }
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

    private var tiltDrag: some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                let dx = Float(value.translation.width - lastDrag.width)
                let dy = Float(value.translation.height - lastDrag.height)
                lastDrag = value.translation
                coordinator.cancelLevel()
                coordinator.pitch = clampTilt(coordinator.pitch + dy * Self.tiltPerPoint)
                coordinator.roll = clampTilt(coordinator.roll - dx * Self.tiltPerPoint)
            }
            .onEnded { _ in lastDrag = .zero }
    }

    private func clampTilt(_ degrees: Float) -> Float {
        min(max(degrees, -Self.maxTilt), Self.maxTilt)
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

    /// The camera pose that fits the tray (plus the balls' bounce room) between the chrome's
    /// top and bottom scrims, seen from 32° above the near edge.
    static func framingPose(for size: CGSize) -> SceneCameraPose {
        let elevation: Float = 32 * .pi / 180
        let fill: Float = 0.92
        let extent = SIMD3<Float>(1.7, 0.75, 1.7)
        let target = SIMD3<Float>(0, -0.15, 0)
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
        for sx in [-1, 1] as [Float] {
            for sy in [-1, 1] as [Float] {
                for sz in [-1, 1] as [Float] {
                    let corner = extent / 2 * SIMD3(sx, sy, sz)
                    let depth = simd_dot(corner, back)
                    distance = max(distance, depth + abs(corner.x) / (tanH * fill))
                    distance = max(distance, depth + abs(simd_dot(corner, up)) / (tanBand * fill))
                }
            }
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
                    HStack(spacing: 6) {
                        Circle()
                            .fill(kind.swatch)
                            .frame(width: 12, height: 12)
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
        VStack(alignment: .leading, spacing: 16) {
            Text(countsText)
                .font(.subheadline.weight(.semibold).monospacedDigit())

            HStack(spacing: 12) {
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
                .disabled(coordinator.pitch == 0 && coordinator.roll == 0)
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
            LabeledSlider(label: "Pitch · toward you", value: userTilt(\.pitch),
                          range: -Self.maxTilt...Self.maxTilt, decimals: 0, unit: "°")
            LabeledSlider(label: "Roll · left and right", value: userTilt(\.roll),
                          range: -Self.maxTilt...Self.maxTilt, decimals: 0, unit: "°")
            Text("The tray hangs off one pivot node and the gravity vector is rotated into its "
                 + "frame, so the floor plane and rails stay flat in the simulation while the "
                 + "balls accelerate down the slope.")
                .font(.caption)
                .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
        }
    }

    /// A slider binding on one tilt axis. A user write cancels a running Level, so the easing
    /// never fights the finger.
    private func userTilt(_ axis: ReferenceWritableKeyPath<RollingBallsCoordinator, Float>) -> Binding<Float> {
        Binding(
            get: { coordinator[keyPath: axis] },
            set: { value in
                coordinator.cancelLevel()
                coordinator[keyPath: axis] = clampTilt(value)
            }
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
/// recomputation. The tick accumulates wall-clock time and runs fixed 120 Hz steps, like
/// Android's frame driver.
@MainActor
@Observable
final class RollingBallsCoordinator {
    /// Tray pitch in degrees; positive lowers the near edge.
    var pitch: Float = 0 { didSet { applyTilt() } }
    /// Tray roll in degrees; positive raises the right edge.
    var roll: Float = 0 { didSet { applyTilt() } }
    private(set) var bodies = 0
    private(set) var impacts = 0

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
    @ObservationIgnored private var timer: Timer?
    @ObservationIgnored private var lastTick: TimeInterval?
    @ObservationIgnored private var accumulator: TimeInterval = 0
    @ObservationIgnored private var built = false
    /// Level easing: start angles and elapsed time, `nil` when idle.
    @ObservationIgnored private var leveling: (pitch: Float, roll: Float, elapsed: TimeInterval)?

    private static let levelDuration: TimeInterval = 0.4
    private static let step: TimeInterval = 8_333_333 / 1_000_000_000

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

    /// Replays the opening shot and snaps the tray level.
    func reset() {
        leveling = nil
        setTilt(pitch: 0, roll: 0)
        simulation.reset()
        syncEntities()
        publishCounts()
    }

    /// Eases the tray back to level over 400 ms.
    func level() {
        guard pitch != 0 || roll != 0 else { return }
        leveling = (pitch, roll, 0)
    }

    func cancelLevel() { leveling = nil }


    func stop() {
        timer?.invalidate()
        timer = nil
        lastTick = nil
    }

    // MARK: Private

    /// Starts the frame tick; a no-op while it is already running.
    func start() {
        guard timer == nil else { return }
        let t = Timer.scheduledTimer(withTimeInterval: 1.0 / 60.0, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        }
        RunLoop.main.add(t, forMode: .common)
        timer = t
    }

    private func tick() {
        let now = ProcessInfo.processInfo.systemUptime
        let elapsed = min(max(now - (lastTick ?? now), 0), 0.1)
        lastTick = now

        if let level = leveling {
            let t = min((level.elapsed + elapsed) / Self.levelDuration, 1)
            let eased = Float(1 - pow(1 - t, 3))
            setTilt(pitch: level.pitch * (1 - eased), roll: level.roll * (1 - eased))
            leveling = t >= 1 ? nil : (level.pitch, level.roll, level.elapsed + elapsed)
        }

        accumulator += elapsed
        var stepped = false
        while accumulator >= Self.step {
            simulation.step()
            accumulator -= Self.step
            stepped = true
        }
        guard stepped else { return }
        for ball in simulation.balls {
            ballEntities[ball.id]?.position = ball.position
        }
        publishCounts()
    }

    private func setTilt(pitch newPitch: Float, roll newRoll: Float) {
        if pitch != newPitch { pitch = newPitch }
        if roll != newRoll { roll = newRoll }
    }

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
        let slabThickness: Float = 0.02

        let floor = GeometryNode.cube(
            size: 1,
            material: .pbr(color: SceneViewTokens.Stage.trayFloor, metallic: 0, roughness: 0.8)
        )
        floor.entity.scale = SIMD3(size, slabThickness, size)
        floor.entity.position = SIMD3(0, floorY - slabThickness / 2, 0)
        pivot.addChild(floor.entity)

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

    /// Adds entities for new balls and removes those of recycled or reset ones.
    private func syncEntities() {
        let live = Set(simulation.balls.map(\.id))
        for (id, entity) in ballEntities where !live.contains(id) {
            entity.removeFromParent()
            ballEntities[id] = nil
        }
        for ball in simulation.balls {
            if let entity = ballEntities[ball.id] {
                entity.position = ball.position
                continue
            }
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
