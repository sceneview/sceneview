import SwiftUI
import RealityKit
import SceneViewSwift

/// `GeometryNode` — the seven built-in primitives, Android's `GeometryDemo` on RealityKit.
///
/// Chips show and hide each shape in its own slot; the sheet restyles them all, from the
/// studio finishes (lacquer, chrome, aluminium, ceramic, gold, copper) to one material
/// whose Metallic and Roughness you dial. Wireframe swaps each solid for
/// `MeshNode.wireframe(of:)` — an iOS addition: RealityKit has no wireframe fill mode, so
/// the wires are thin unlit prisms built from the shape's own mesh.
///
/// Coverage: `sceneview://demo/geometry`
struct GeometryDemo: View {
    @State private var visible = Set(GeometryShape.allCases)
    @State private var animating = true
    @State private var wireframe = false
    @State private var finish = GeometryFinish.studio
    @State private var metallic = GeometryFinish.defaultMetallic
    @State private var roughness = GeometryFinish.defaultRoughness
    @State private var viewSize: CGSize = .zero
    @State private var recenters = 0
    @State private var stage = GeometryStage()
    @Environment(\.colorScheme) private var colorScheme
    @AppStorage(DeepLinkRouter.qaModeDefaultsKey) private var qaMode = false

    private var arrangement: GeometryArrangement { GeometryArrangement.best(for: viewSize) }

    var body: some View {
        GeometryReader { proxy in
            ZStack {
                LinearGradient(colors: [SceneViewTokens.Stage.skyHorizon, SceneViewTokens.Stage.skyGround],
                               startPoint: .top, endPoint: .bottom)
                SceneView { root in stage.install(in: root) }
                    .cameraControls(.orbit)
                    // The slots are laid out around the origin on purpose: a fit to the
                    // visible shapes' bounds would slide the block as chips toggle.
                    .autoCenterContent(false)
                    .cameraPose(arrangement.pose(in: viewSize, recenters: recenters))
                    // The metals need an IBL to have anything to reflect (#2114) — lit by
                    // the studio HDR, not drawn in front of it.
                    .environment(.custom(name: "Studio", hdrFile: "studio.hdr", showSkybox: false))
            }
            .onAppear { viewSize = proxy.size }
            .onChange(of: proxy.size) { _, size in viewSize = size }
        }
        .ignoresSafeArea()
        .demoChrome(
            title: "Geometry Primitives",
            dock: [
                DockItem(icon: animating ? "pause.fill" : "play.fill", label: "Animate", control: "animate",
                         selected: animating) { animating.toggle() },
                DockItem(icon: "scope", label: "Recenter", control: "recenter") { recenters += 1 },
                DockItem(icon: "squareshape.split.3x3", label: "Wireframe", control: "wireframe",
                         selected: wireframe) { wireframe.toggle() },
            ],
            onReset: reset,
            accessory: {
                GeometryShapeChips(visible: $visible) { shape in
                    Color(uiColor: shape.swatch(finish))
                }
            }
        ) {
            Picker("Finish", selection: $finish) {
                Text("Studio finishes").tag(GeometryFinish.studio)
                Text("One material").tag(GeometryFinish.uniform)
            }
            .pickerStyle(.segmented)
            // Dialing either value restyles every shape with it: the sliders mean
            // "one material", so moving one switches to it.
            LabeledSlider(label: "Metallic", value: uniform($metallic), range: 0...1, decimals: 2)
            LabeledSlider(label: "Roughness", value: uniform($roughness), range: 0...1, decimals: 2)
        }
        .onAppear {
            stage.configure(visible: visible, look: look, arrangement: arrangement)
            stage.setMotion(animating: animating, frozen: qaMode)
        }
        .onDisappear { stage.stop() }
        .onChange(of: visible) { _, value in stage.configure(visible: value) }
        .onChange(of: look) { _, value in stage.configure(look: value) }
        .onChange(of: arrangement) { _, value in stage.configure(arrangement: value) }
        .onChange(of: animating) { _, value in stage.setMotion(animating: value, frozen: qaMode) }
        .onChange(of: qaMode) { _, value in stage.setMotion(animating: animating, frozen: value) }
    }

    private var look: GeometryLook {
        GeometryLook(finish: finish, metallic: metallic, roughness: roughness,
                     wireframe: wireframe, dark: colorScheme == .dark)
    }

    private func uniform(_ value: Binding<Float>) -> Binding<Float> {
        Binding(get: { value.wrappedValue }, set: { value.wrappedValue = $0; finish = .uniform })
    }

    private func reset() {
        visible = Set(GeometryShape.allCases)
        animating = true
        wireframe = false
        finish = .studio
        metallic = GeometryFinish.defaultMetallic
        roughness = GeometryFinish.defaultRoughness
        recenters += 1
    }
}

// MARK: - Shapes

/// Android's `GeometryShape`, in slot order.
enum GeometryShape: Int, CaseIterable, Identifiable {
    case cube, sphere, cylinder, cone, torus, capsule, plane

    var id: Int { rawValue }

    var label: String {
        switch self {
        case .cube: "Cube"
        case .sphere: "Sphere"
        case .cylinder: "Cylinder"
        case .cone: "Cone"
        case .torus: "Torus"
        case .capsule: "Capsule"
        case .plane: "Plane"
        }
    }

    /// Android's `GeometryLayout` sizes. The capsule's 0.13 m is its straight body; iOS
    /// takes the total height, caps included.
    func makeSolid() -> ModelEntity {
        switch self {
        case .cube: GeometryNode.cube(size: 0.22).entity
        case .sphere: GeometryNode.sphere(radius: 0.14).entity
        case .cylinder: GeometryNode.cylinder(radius: 0.10, height: 0.26).entity
        case .cone: GeometryNode.cone(height: 0.26, radius: 0.13).entity
        case .torus: GeometryNode.torus(majorRadius: 0.105, minorRadius: 0.042,
                                        majorSegments: 48, minorSegments: 24).entity
        case .capsule: GeometryNode.capsule(radius: 0.08, height: 0.13 + 2 * 0.08).entity
        case .plane: GeometryNode.plane(width: 0.24, depth: 0.24).entity
        }
    }

    /// A plane is built flat on XZ: stand it up to face the camera (+Z).
    var baseOrientation: simd_quatf {
        self == .plane ? simd_quatf(angle: .pi / 2, axis: [1, 0, 0]) : simd_quatf(ix: 0, iy: 0, iz: 0, r: 1)
    }

    /// Android's tumble: a fixed tilt, then the spin — the torus tilted further so its
    /// hole shows, the plane spinning in its own plane so it never turns edge-on.
    func orientation(spin: Float) -> simd_quatf {
        let degrees: Float = self == .torus ? 65 : 20
        let tilt = simd_quatf(angle: degrees * .pi / 180, axis: [1, 0, 0])
        let axis: SIMD3<Float> = self == .plane ? [0, 0, 1] : [0, 1, 0]
        return tilt * simd_quatf(angle: spin, axis: axis)
    }

    /// The studio finish Android gives each shape.
    var studioMaterial: StudioMaterial {
        let library = Dictionary(uniqueKeysWithValues: MaterialWall.library.map { ($0.id, $0) })
        switch self {
        case .cube: return Self.lacquer(GeometryFinish.tintLight)
        case .sphere: return library["chrome"]!
        case .cylinder:
            let aluminium = library["aluminium"]!
            return StudioMaterial(id: "aluminium", name: aluminium.name, note: aluminium.note,
                                  color: aluminium.color, metallic: aluminium.metallic, roughness: 0.3)
        case .cone: return library["ceramic"]!
        case .torus: return library["gold"]!
        case .capsule: return library["copper"]!
        case .plane: return Self.lacquer(GeometryFinish.tintSoft)
        }
    }

    func swatch(_ finish: GeometryFinish) -> UIColor {
        let rgb = finish == .studio ? studioMaterial.color : GeometryFinish.uniformColor(self)
        return UIColor(red: CGFloat(rgb.x), green: CGFloat(rgb.y), blue: CGFloat(rgb.z), alpha: 1)
    }

    private static func lacquer(_ color: SIMD3<Float>) -> StudioMaterial {
        StudioMaterial(id: "lacquer", name: "Lacquer", note: "A dielectric under a glossy clear coat.",
                       color: color, metallic: 0, roughness: 0.4,
                       trait: .clearcoat, traitAmount: 1, traitRoughness: 0.03)
    }
}

enum GeometryFinish: Hashable {
    case studio, uniform

    static let defaultMetallic: Float = 0.3
    static let defaultRoughness: Float = 0.5
    /// Theme `TintLight` #A4C1FF and `TintSoft` #D2A8FF.
    static let tintLight = SIMD3<Float>(0xA4, 0xC1, 0xFF) / 255
    static let tintSoft = SIMD3<Float>(0xD2, 0xA8, 0xFF) / 255

    /// One material, two tints alternating by slot so neighbours stay apart.
    static func uniformColor(_ shape: GeometryShape) -> SIMD3<Float> {
        shape.rawValue.isMultiple(of: 2) ? tintLight : tintSoft
    }
}

/// Everything that decides how the shapes are drawn.
struct GeometryLook: Equatable {
    var finish = GeometryFinish.studio
    var metallic = GeometryFinish.defaultMetallic
    var roughness = GeometryFinish.defaultRoughness
    var wireframe = false
    var dark = false
}

// MARK: - Layout

/// Android's `GeometryLayout.Arrangement`: seven slots 0.42 m apart, in whichever block
/// lets the camera come closest for the screen's shape.
enum GeometryArrangement: CaseIterable {
    case honeycomb, twoRows, singleRow

    static let cell: Float = 0.42
    static let rowPitch: Float = cell * sin(.pi / 3)
    /// Twice the largest bounding radius (the cube's).
    static let depth: Float = 2 * 0.22 * sqrt(3) / 2
    private static let horizontalFill: Float = 0.94
    private static let verticalFill: Float = 0.90
    private static let pitch: Float = 8 * .pi / 180
    /// Points under the chips and the dock: the block is framed in the band above them.
    private static let bottomReserve: Float = 250

    var rows: [Int] {
        switch self {
        case .honeycomb: [2, 3, 2]
        case .twoRows: [4, 3]
        case .singleRow: [7]
        }
    }

    var extent: SIMD2<Float> {
        SIMD2(Float((rows.max() ?? 1) - 1) * Self.cell + Self.depth,
              Float(rows.count - 1) * Self.rowPitch + Self.depth)
    }

    func position(of shape: GeometryShape) -> SIMD3<Float> {
        var index = shape.rawValue
        for (row, count) in rows.enumerated() {
            if index < count {
                return [(Float(index) - Float(count - 1) / 2) * Self.cell,
                        (Float(rows.count - 1) / 2 - Float(row)) * Self.rowPitch, 0]
            }
            index -= count
        }
        return .zero
    }

    static func best(for size: CGSize) -> GeometryArrangement {
        allCases.min { $0.fit(in: size).distance < $1.fit(in: size).distance } ?? .honeycomb
    }

    /// The home pose. `recenters` nudges the distance by a micrometre so that re-writing
    /// the same pose after a drag still counts as a new request.
    func pose(in size: CGSize, recenters: Int) -> SceneCameraPose {
        let fit = fit(in: size)
        return SceneCameraPose(azimuth: 0, elevation: Self.pitch,
                               distance: fit.distance + (recenters.isMultiple(of: 2) ? 0 : 1e-6),
                               target: fit.target)
    }

    private func fit(in size: CGSize) -> (distance: Float, target: SIMD3<Float>) {
        let width = Float(max(size.width, 1))
        let height = Float(max(size.height, 1))
        let top = Float(SceneViewTokens.Chrome.scrimTop) * 0.6
        let band = max(height - top - Self.bottomReserve, height * 0.35)
        let tanV = tan(Float.pi / 6)
        let tanH = tanV * width / height
        let tanBand = tanV * band / height
        let distance = max(extent.x / 2 / (tanH * Self.horizontalFill),
                           extent.y / 2 / (tanBand * Self.verticalFill)) + Self.depth / 2
        // Centre the block on the band, not on the screen.
        let bandCentreOffset = (top + band / 2) - height / 2
        let shift = bandCentreOffset / (height / 2) * distance * tanV
        let up = SIMD3<Float>(0, cos(Self.pitch), -sin(Self.pitch))
        return (distance, up * shift)
    }
}

// MARK: - Stage

/// Seven slots, each a spinning holder with the shape's solid and, once asked for, its
/// wireframe. Hidden shapes keep their slot.
@MainActor
final class GeometryStage {
    private static let spinRadiansPerSecond: Float = 36 * .pi / 180
    private static let frozenSpin: Float = 30 * .pi / 180
    private static let wireThickness: Float = 0.0025

    private struct Slot {
        let holder = Entity()
        let solid: ModelEntity
        var wire: ModelEntity?
        var wireDark: Bool?
    }

    private let root = Entity()
    private var slots: [GeometryShape: Slot] = [:]
    private let ticker = FrameTicker()
    private var visible = Set(GeometryShape.allCases)
    private var look = GeometryLook()
    private var arrangement = GeometryArrangement.honeycomb
    private var spin: Float = 0
    private var animating = true
    private var frozen = false

    func install(in scene: Entity) {
        if slots.isEmpty {
            for shape in GeometryShape.allCases {
                let slot = Slot(solid: shape.makeSolid())
                let body = Entity()
                body.orientation = shape.baseOrientation
                body.addChild(slot.solid)
                slot.holder.addChild(body)
                root.addChild(slot.holder)
                slots[shape] = slot
            }
            apply()
        }
        root.removeFromParent()
        scene.addChild(root)
        ticker.start(on: root) { [weak self] delta in self?.tick(delta) }
    }

    func stop() { ticker.stop() }

    func configure(visible: Set<GeometryShape>? = nil, look: GeometryLook? = nil,
                   arrangement: GeometryArrangement? = nil) {
        if let visible { self.visible = visible }
        if let look { self.look = look }
        if let arrangement { self.arrangement = arrangement }
        apply()
    }

    func setMotion(animating: Bool, frozen: Bool) {
        self.animating = animating
        self.frozen = frozen
        if frozen { spin = Self.frozenSpin }
        applySpin()
    }

    private func tick(_ delta: Float) {
        guard animating, !frozen else { return }
        spin = (spin + delta * Self.spinRadiansPerSecond).truncatingRemainder(dividingBy: 2 * .pi)
        applySpin()
    }

    private func applySpin() {
        for (shape, slot) in slots { slot.holder.orientation = shape.orientation(spin: spin) }
    }

    private func apply() {
        for shape in GeometryShape.allCases {
            guard var slot = slots[shape] else { continue }
            slot.holder.position = arrangement.position(of: shape)
            slot.holder.isEnabled = visible.contains(shape)
            slot.solid.model?.materials = [material(for: shape)]
            slot.solid.isEnabled = !look.wireframe
            if look.wireframe, slot.wire == nil || slot.wireDark != look.dark {
                slot.wire?.removeFromParent()
                slot.wire = wire(for: slot.solid)
                slot.wireDark = look.dark
                if let wire = slot.wire { slot.solid.parent?.addChild(wire) }
            }
            slot.wire?.isEnabled = look.wireframe
            slots[shape] = slot
        }
        applySpin()
    }

    private func material(for shape: GeometryShape) -> PhysicallyBasedMaterial {
        var material = look.finish == .studio
            ? MaterialWall.material(shape.studioMaterial)
            : MaterialWall.material(
                StudioMaterial(id: "uniform", name: "One material", note: "",
                               color: GeometryFinish.uniformColor(shape), metallic: look.metallic,
                               roughness: look.roughness))
        // The plane is a single sheet: draw its back face too, as Android does.
        if shape == .plane { material.faceCulling = .none }
        return material
    }

    private func wire(for solid: ModelEntity) -> ModelEntity? {
        guard let mesh = solid.model?.mesh else { return nil }
        // Theme `primary`: #005BC1 light, #A4C1FF dark.
        let color = SceneViewTokens.Stage.shapeRamp[look.dark ? 2 : 0]
        return try? MeshNode.wireframe(of: mesh, thickness: Self.wireThickness,
                                       material: .unlit(color: color)).entity
    }
}

// MARK: - Chips

/// One chip per shape, wrapped onto as many centred lines as the width needs: a chip
/// shows or hides its shape, its swatch is the shape's current colour.
private struct GeometryShapeChips: View {
    @Binding var visible: Set<GeometryShape>
    let swatch: (GeometryShape) -> Color

    var body: some View {
        CentredFlow(spacing: SceneViewTokens.Space.xs) {
            ForEach(GeometryShape.allCases) { shape in
                let on = visible.contains(shape)
                Button {
                    if on { visible.remove(shape) } else { visible.insert(shape) }
                } label: {
                    HStack(spacing: SceneViewTokens.Space.xs) {
                        Circle()
                            .fill(swatch(shape))
                            .overlay(Circle().strokeBorder(SceneViewTokens.Glass.border, lineWidth: 1))
                            .frame(width: 12, height: 12)
                            .opacity(on ? 1 : 0.45)
                        Text(shape.label)
                            .font(SceneViewTokens.TypeScale.chromeLabel)
                            .lineLimit(1)
                            .fixedSize()
                    }
                    .foregroundStyle(on ? SceneViewTokens.Stage.background : SceneViewTokens.Glass.onGlassMuted)
                    .padding(.horizontal, SceneViewTokens.Space.md)
                    .frame(minHeight: SceneViewTokens.Glass.pillHeight)
                    .background {
                        if on {
                            Capsule().fill(SceneViewTokens.Glass.onGlass)
                        } else {
                            Capsule().strokeBorder(SceneViewTokens.Glass.border, lineWidth: 1)
                        }
                    }
                    .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(shape.label)
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
        .padding(SceneViewTokens.Space.xs)
        .glassBackground(in: RoundedRectangle(cornerRadius: SceneViewTokens.Radius.lg, style: .continuous),
                         interactive: true, id: "shape-chips")
        .sensoryFeedback(.selection, trigger: visible)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("geometry-shape-chips")
    }
}

/// Lines of subviews at their ideal size, each line centred, wrapping at the proposed width.
private struct CentredFlow: Layout {
    let spacing: CGFloat

    private func lines(_ subviews: Subviews, width: CGFloat) -> [[(index: Int, size: CGSize)]] {
        var lines: [[(index: Int, size: CGSize)]] = [[]]
        var x: CGFloat = 0
        for (index, subview) in subviews.enumerated() {
            let size = subview.sizeThatFits(.unspecified)
            if x > 0, x + size.width > width {
                lines.append([])
                x = 0
            }
            lines[lines.count - 1].append((index, size))
            x += size.width + spacing
        }
        return lines
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        let lines = lines(subviews, width: width)
        let widths = lines.map { line in line.reduce(0) { $0 + $1.size.width } + spacing * CGFloat(max(line.count - 1, 0)) }
        let heights = lines.map { line in line.map(\.size.height).max() ?? 0 }
        return CGSize(width: min(widths.max() ?? 0, width),
                      height: heights.reduce(0, +) + spacing * CGFloat(max(lines.count - 1, 0)))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for line in lines(subviews, width: bounds.width) {
            let lineWidth = line.reduce(0) { $0 + $1.size.width } + spacing * CGFloat(max(line.count - 1, 0))
            let lineHeight = line.map(\.size.height).max() ?? 0
            var x = bounds.minX + (bounds.width - lineWidth) / 2
            for item in line {
                subviews[item.index].place(at: CGPoint(x: x, y: y + (lineHeight - item.size.height) / 2),
                                           anchor: .topLeading, proposal: ProposedViewSize(item.size))
                x += item.size.width + spacing
            }
            y += lineHeight + spacing
        }
    }
}
