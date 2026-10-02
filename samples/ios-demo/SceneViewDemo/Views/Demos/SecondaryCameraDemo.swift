import SwiftUI
import RealityKit
import SceneViewSwift

/// One scene, two cameras: an interactive main view and an independent
/// picture-in-picture inset render the same helmet on the same floor.
///
/// iOS twin of Android's `SecondaryCameraDemo`
/// (`samples/android-demo/.../demos/SecondaryCameraDemo.kt`):
///
/// - The **main view** orbits under the user's finger and is framed on the stage
///   the helmet can walk on, 24° above it.
/// - The **inset** is a second `SceneView` whose camera the user never drags: the
///   Top / Side / Front / Corner / Orbit strip parks it on Android's lines of
///   sight, at ``SecondaryCameraMath/insetZoom`` of their distance so the helmet
///   reads in a 192 × 128 pt inset, and Orbit sweeps it round the stage once
///   every 12 s.
/// - **The edit is shared.** A tap in either view casts a ray with *that* view's
///   camera: through the helmet it turns it a quarter turn, on the floor it walks
///   the helmet there (kept on a 70 cm stage). Both views show the change, each
///   from its own angle.
///
/// ## What differs from Android, and why
///
/// Android renders both views from one Filament scene through two `View`s.
/// RealityKit gives each `RealityView` its own scene, so iOS keeps one *state*
/// (``SecondaryCameraStage``) and two copies of the entities: the helmet is
/// loaded once and the inset gets a `clone(recursive:)` that shares its meshes
/// and textures — the RealityKit counterpart of Android's
/// `createInstancedModel(count = 2)`. An edit writes the state once and moves
/// both copies.
///
/// Android's floor runs out into the stage sky's fog. RealityKit has no fog,
/// and a semi-transparent floor composites too bright over SwiftUI, so the iOS
/// floor is opaque and its texture fades, in colour, into a flat backdrop
/// (``SceneViewTokens/Stage/pipBackdropColor``) drawn behind both views: no
/// angle shows the floor's edge.
struct SecondaryCameraDemo: View {
    @State private var stage = SecondaryCameraStage()
    @State private var preset: SecondaryCameraPreset = .corner
    /// Bumped by Reset: the main view re-frames on it.
    @State private var framingRequest = 0

    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.analyticsSampleId) private var analyticsSampleId

    var body: some View {
        // Read the safe area outside the chrome: inside it the stage ignores the
        // safe area, the inset reads 0 and the PiP slides under the title row.
        GeometryReader { proxy in
            let top = Self.topReserve(safeTop: proxy.safeAreaInsets.top)
            ZStack(alignment: .top) {
                SecondaryCameraMainView(
                    stage: stage,
                    framingRequest: framingRequest,
                    bandTop: top + SecondaryCameraInset.size.height + SceneViewTokens.Space.sm
                        + SceneViewTokens.Glass.pillHeight,
                    bandBottom: SceneViewTokens.Chrome.scrimBottomMin + SceneViewTokens.Space.x2l,
                    reduceMotion: reduceMotion
                )
                .ignoresSafeArea()

                overlay
                    .padding(.horizontal, SceneViewTokens.Chrome.margin)
                    .padding(.top, top)
                    .ignoresSafeArea(edges: .top)
            }
            .background(SceneViewTokens.Stage.background)
            .demoChrome(
                dock: [
                    DockItem(icon: "arrow.counterclockwise", label: "Reset the helmet",
                             caption: "Reset", enabled: stage.lastEdit != nil) {
                        stage.resetHelmet(animated: !reduceMotion)
                    },
                ],
                onReset: { reset() },
                accessory: {
                    VStack(spacing: SceneViewTokens.Space.sm) {
                        DemoHint(statusText)
                        DemoOptionStrip(SecondaryCameraPreset.allCases, selection: $preset) { $0.label }
                    }
                },
                controls: { controls }
            )
        }
        .task {
            await stage.loadIfNeeded(sampleId: analyticsSampleId ?? "secondary-camera")
        }
        .onAppear { stage.setDark(colorScheme == .dark) }
        .onChange(of: colorScheme) { _, scheme in stage.setDark(scheme == .dark) }
    }

    // MARK: Overlay

    /// The inset at the leading edge, the main view's caption right under it on
    /// the same edge — Android's `topOverlay`, same order.
    private var overlay: some View {
        VStack(alignment: .leading, spacing: SceneViewTokens.Space.sm) {
            SecondaryCameraInset(stage: stage, preset: preset, reduceMotion: reduceMotion)
                // The chrome pins a dark scheme; the inset's outline and shadow
                // follow the app's, so the light theme gets its light contour.
                .environment(\.colorScheme, colorScheme)
            SecondaryCameraCaption(text: "Main camera · drag to orbit, tap to edit")
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var statusText: String {
        switch stage.lastEdit {
        case .main: return "Edited in the main view — the inset shows the same change from its own angle."
        case .inset: return "Edited in the inset — the main view shows the same change from its own angle."
        case nil: return "Tap the floor in either view to move the helmet, or tap the helmet to turn it."
        }
    }

    // MARK: Controls sheet

    @ViewBuilder
    private var controls: some View {
        VStack(alignment: .leading, spacing: SceneViewTokens.Space.md) {
            Text("One scene, two cameras: an interactive main view and an independent picture-in-picture view render the same state. An edit made through either camera — a move, a turn — appears in both.")
                .font(SceneViewTokens.TypeScale.body)
                .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
                .fixedSize(horizontal: false, vertical: true)
            Text("PiP Camera Angle")
                .font(SceneViewTokens.TypeScale.bodySemibold)
                .accessibilityAddTraits(.isHeader)
            Picker("PiP Camera Angle", selection: $preset) {
                ForEach(SecondaryCameraPreset.allCases) { option in
                    Text(option.label).tag(option)
                }
            }
            .pickerStyle(.segmented)
            Button("Reset the helmet") { stage.resetHelmet(animated: !reduceMotion) }
                .buttonStyle(.bordered)
                .disabled(stage.lastEdit == nil)
            if stage.loadFailed {
                Label("Could not load the helmet.", systemImage: "exclamationmark.triangle")
                    .font(SceneViewTokens.TypeScale.caption)
                    .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
            }
        }
    }

    /// The sheet's Reset: the helmet home, the inset back on Corner, the main
    /// camera back on its framing.
    private func reset() {
        stage.resetHelmet(animated: false)
        preset = .corner
        framingRequest += 1
    }

    /// The identity row's bottom edge plus a gap: where the inset starts.
    private static func topReserve(safeTop: CGFloat) -> CGFloat {
        let slop = (SceneViewTokens.Layout.touchTarget - SceneViewTokens.Glass.iconButtonSize) / 2
        return safeTop + SceneViewTokens.Chrome.topGap - slop + SceneViewTokens.Layout.touchTarget
            + SceneViewTokens.Space.sm
    }

    /// Studio IBL for the helmet, no backdrop: the themed sky gradient behind the
    /// scene shows through, as Android's stage sky does.
    static let environment: SceneEnvironment = {
        var studio = SceneEnvironment.studio
        studio.showSkybox = false
        return studio
    }()
}

/// The flat backdrop both views draw the stage on, so the inset reads as the
/// same place seen from elsewhere. Android draws its stage sky as a gradient
/// (`themedStageSky()`); here the floor fades into this one colour instead, which
/// a gradient would not match all the way round the rim.
private struct SecondaryCameraSky: View {
    var body: some View {
        SceneViewTokens.Stage.pipBackdropColor
    }
}

// MARK: - Main view

/// The interactive camera. Its own view so that mirroring the live pose into
/// `@State` on every drag tick re-evaluates this view alone, not the inset.
private struct SecondaryCameraMainView: View {
    let stage: SecondaryCameraStage
    let framingRequest: Int
    /// Screen band the stage is framed into — under the inset, above the dock.
    let bandTop: CGFloat
    let bandBottom: CGFloat
    let reduceMotion: Bool

    @State private var pose: SceneCameraPose?
    @State private var size: CGSize = .zero

    var body: some View {
        ZStack {
            SecondaryCameraSky()
            SceneView { root in
                stage.install(in: root, slot: .main)
            }
            .environment(SecondaryCameraDemo.environment)
            .cameraControls(.orbit)
            // The helmet moves; re-centring on the scene's bounds would move the
            // stage with it (Android: `autoCenterContent = false`).
            .autoCenterContent(false)
            .cameraPose(pose)
            .onCameraChanged { live in
                Task { @MainActor in
                    pose = live
                    stage.mainPose = live
                }
            }
            .simultaneousGesture(
                SpatialTapGesture().onEnded { tap in
                    guard let current = pose,
                          let ray = SecondaryCameraMath.ray(through: tap.location, in: size,
                                                            pose: current)
                    else { return }
                    stage.edit(ray: ray, from: .main, animated: !reduceMotion)
                }
            )
            .accessibilityLabel("Main camera · drag to orbit, tap to edit")
        }
        .onGeometryChange(for: CGSize.self) { $0.size } action: { newSize in
            size = newSize
            frame()
        }
        .onChange(of: framingRequest) { _, _ in frame() }
    }

    private func frame() {
        guard size.width > 0, size.height > 0 else { return }
        let framed = SecondaryCameraMath.mainFraming(size: size, bandTop: bandTop,
                                                     bandBottom: bandBottom)
        pose = framed
        stage.mainPose = framed
    }
}

// MARK: - Inset

/// The picture-in-picture camera: 192 × 128 pt like Android's
/// (`Space.x4l * 2` × `Space.x3l * 2`), `Radius.sm` corners, a 2 pt outline.
/// The camera only moves through the preset — never under a finger — but a tap
/// still edits the scene, resolved against this camera.
private struct SecondaryCameraInset: View {
    static let size = CGSize(width: 192, height: 128)

    let stage: SecondaryCameraStage
    let preset: SecondaryCameraPreset
    let reduceMotion: Bool

    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: SceneViewTokens.Radius.sm, style: .continuous)
        ZStack(alignment: .bottomLeading) {
            SecondaryCameraSky()
            SceneView { root in
                stage.install(in: root, slot: .inset)
            }
            .environment(SecondaryCameraDemo.environment)
            .cameraControls(.orbit)
            .cameraGesturesEnabled(false)
            .autoCenterContent(false)
            .cameraPose(preset.pose)
            // Orbit: SceneView's own turntable sweeps the camera round the stage
            // centre — one turn per 12 s, Android's `ORBIT_PERIOD_NANOS`. A fixed
            // preset passes 0, which runs no loop at all.
            .autoRotate(speed: preset == .orbit ? SecondaryCameraPreset.orbitSpeed : 0)
            .onCameraChanged { live in
                Task { @MainActor in stage.insetPose = live }
            }
            .simultaneousGesture(
                SpatialTapGesture().onEnded { tap in
                    guard let ray = SecondaryCameraMath.ray(through: tap.location, in: Self.size,
                                                            pose: stage.insetPose)
                    else { return }
                    stage.edit(ray: ray, from: .inset, animated: !reduceMotion)
                }
            )

            SecondaryCameraCaption(text: "PiP — \(preset.label) view")
                .padding(SceneViewTokens.Space.xs)
                .accessibilityHidden(true)
        }
        .frame(width: Self.size.width, height: Self.size.height)
        .clipShape(shape)
        // Android's PiP outline is the theme's `outline`, #D6DAE0 / #8B95A6.
        .overlay(shape.strokeBorder(SceneViewTokens.HomeColor.controlOutline,
                                    lineWidth: SceneViewTokens.Layout.selectedOutlineWidth))
        // Light: `shadow-md` lifts the inset off the same pale stage it shows.
        // Cast by a plain shape behind it, so the live render is never drawn
        // offscreen for the shadow.
        .background {
            if colorScheme != .dark {
                let layers = SceneViewTokens.Shadow.md
                ForEach(layers.indices, id: \.self) { index in
                    shape.fill(SceneViewTokens.Stage.pipBackdropColor)
                        .shadow(color: .black.opacity(layers[index].opacity),
                                radius: layers[index].radius, x: 0, y: layers[index].y)
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Picture-in-picture camera view: \(preset.label)")
        .onChange(of: preset) { _, newPreset in stage.insetPose = newPreset.pose }
    }
}

/// A label over the scene: white on the chrome scrim (black 60 %), so it reads
/// over the light sky and the dark one alike — Android draws these on `surface`.
private struct SecondaryCameraCaption: View {
    let text: String

    var body: some View {
        Text(text)
            .font(SceneViewTokens.TypeScale.chromeCaption)
            .foregroundStyle(SceneViewTokens.Glass.onGlass)
            .lineLimit(2)
            .padding(.horizontal, SceneViewTokens.Space.sm)
            .padding(.vertical, SceneViewTokens.Space.xs)
            .background(SceneViewTokens.Chrome.scrim,
                        in: RoundedRectangle(cornerRadius: SceneViewTokens.Radius.xs, style: .continuous))
    }
}

// MARK: - Presets

/// The inset's camera angles — Android's `CameraPreset`, same eye positions,
/// all looking at the stage centre.
enum SecondaryCameraPreset: String, CaseIterable, Identifiable {
    case top, side, front, corner, orbit

    var id: String { rawValue }

    var label: String {
        switch self {
        case .top: return "Top"
        case .side: return "Side"
        case .front: return "Front"
        case .corner: return "Corner"
        case .orbit: return "Orbit"
        }
    }

    /// Android's eye positions, metres.
    var androidEye: SIMD3<Float> {
        switch self {
        case .top: return SIMD3(0.01, 1.9, 0)
        case .side: return SIMD3(1.5, 0.35, 0)
        case .front: return SIMD3(0, 0.35, 1.5)
        case .corner: return SIMD3(1.05, 0.85, 1.05)
        // Where the 12 s sweep starts: radius 1.45 m, 0.75 m up, azimuth 0.
        case .orbit: return SIMD3(0, SecondaryCameraMath.orbitHeight, SecondaryCameraMath.orbitRadius)
        }
    }

    /// Where the inset's camera sits: Android's eye, brought in towards the stage
    /// centre by ``SecondaryCameraMath/insetZoom`` along the same line of sight.
    var eye: SIMD3<Float> {
        let centre = SecondaryCameraMath.stageCentre
        return centre + (androidEye - centre) * SecondaryCameraMath.insetZoom
    }

    var pose: SceneCameraPose {
        SecondaryCameraMath.pose(eye: eye, target: SecondaryCameraMath.stageCentre)
    }

    /// One turn per 12 s, in radians per second.
    static let orbitSpeed: Float = 2 * .pi / 12
}

/// Which camera the last edit was made through.
enum SecondaryCameraEditSource {
    case main, inset
}

// MARK: - Stage

/// The one scene state both views draw — where the helmet stands and which way it
/// faces — and the entities that draw it, one set per view.
@MainActor
@Observable
final class SecondaryCameraStage {
    private(set) var helmetX: Float = 0
    private(set) var helmetZ: Float = 0
    private(set) var helmetYaw: Float = 0
    private(set) var lastEdit: SecondaryCameraEditSource?
    private(set) var loadFailed = false

    /// Latest poses each view rendered, for resolving a tap.
    @ObservationIgnored var mainPose: SceneCameraPose?
    @ObservationIgnored var insetPose: SceneCameraPose = SecondaryCameraPreset.corner.pose

    enum Slot { case main, inset }

    private let mainSet = StageEntities()
    private let insetSet = StageEntities()
    @ObservationIgnored private var loadStarted = false
    @ObservationIgnored private var dark = false

    /// Adds this view's floor and helmet pivot to its scene root. The content
    /// closure runs once per `RealityView`; re-adding re-parents.
    func install(in root: Entity, slot: Slot) {
        let set = entities(for: slot)
        root.addChild(set.floor)
        root.addChild(set.pivot)
        set.pivot.transform = helmetTransform
    }

    /// Loads the helmet once and stands one copy in each view.
    func loadIfNeeded(sampleId: String) async {
        guard !loadStarted else { return }
        loadStarted = true
        do {
            let node = try await ModelNode.load("khronos_damaged_helmet")
            _ = node.scaleToUnits(SecondaryCameraMath.helmetSize)
            // Stands on the floor: the bounds' bottom centre onto the origin.
            _ = node.centerOrigin(normalized: SIMD3(0, -1, 0))
            mainSet.pivot.addChild(node.entity)
            // Shares meshes and textures with the main copy.
            insetSet.pivot.addChild(node.entity.clone(recursive: true))
        } catch is CancellationError {
            // The demo closed mid-load: nothing failed, nothing to report.
            loadStarted = false
        } catch {
            loadFailed = true
            DemoAnalytics.shared.log(.modelLoadFailed(sampleId: sampleId,
                                                      reason: DemoAnalytics.modelLoadReason(for: error)))
        }
    }

    /// A tap resolved against the camera it was made through: on the helmet it
    /// turns it a quarter turn, on the floor it walks it there.
    func edit(ray: SecondaryCameraMath.Ray, from source: SecondaryCameraEditSource, animated: Bool) {
        let centre = SIMD3<Float>(helmetX, SecondaryCameraMath.helmetSize / 2, helmetZ)
        if SecondaryCameraMath.passes(ray, within: SecondaryCameraMath.helmetPickRadius, of: centre) {
            helmetYaw += .pi / 2
        } else {
            guard let hit = SecondaryCameraMath.floorHit(ray) else { return }
            let edge = SecondaryCameraMath.stageHalfExtent
            helmetX = min(max(hit.x, -edge), edge)
            helmetZ = min(max(hit.z, -edge), edge)
        }
        lastEdit = source
        applyHelmet(animated: animated)
    }

    func resetHelmet(animated: Bool) {
        helmetX = 0
        helmetZ = 0
        helmetYaw = 0
        lastEdit = nil
        applyHelmet(animated: animated)
    }

    /// Floor and grid take the scheme's `Stage.pipFloor` / `Stage.pipGrid`.
    func setDark(_ isDark: Bool) {
        guard isDark != dark else { return }
        dark = isDark
        for set in [mainSet, insetSet] { set.recolour(dark: isDark) }
    }

    private func entities(for slot: Slot) -> StageEntities {
        slot == .main ? mainSet : insetSet
    }

    private var helmetTransform: Transform {
        Transform(scale: .one,
                  rotation: simd_quatf(angle: helmetYaw, axis: SIMD3(0, 1, 0)),
                  translation: SIMD3(helmetX, 0, helmetZ))
    }

    /// Moves both copies — Android's 450 ms glide, none under Reduce Motion.
    private func applyHelmet(animated: Bool) {
        let target = helmetTransform
        for pivot in [mainSet.pivot, insetSet.pivot] {
            if animated, pivot.scene != nil, let parent = pivot.parent {
                _ = pivot.move(to: target, relativeTo: parent,
                           duration: SecondaryCameraMath.editGlide, timingFunction: .easeInOut)
            } else {
                pivot.stopAllAnimations()
                pivot.transform = target
            }
        }
    }
}

/// One view's floor (its grid drawn in) and helmet pivot.
@MainActor
private final class StageEntities {
    let pivot = Entity()
    let floor: ModelEntity

    /// The contact shadow is see-through and lies a hair above the floor: it
    /// always draws after it, whichever is nearer the camera.
    private static let sortGroup = ModelSortGroup(depthPass: nil)

    init() {
        let size = SecondaryCameraMath.floorSize
        floor = ModelEntity(mesh: .generatePlane(width: size, depth: size), materials: [])
        floor.components.set(ModelSortGroupComponent(group: Self.sortGroup, order: 0))

        // A soft contact shadow that travels and turns with the helmet. The
        // floor is unlit (see `recolour`), so RealityKit's grounding shadow has
        // nothing to land on; this disc stands in for Android's shadow.
        if let shadow = Self.contactShadow() {
            shadow.position.y = 0.0005
            shadow.components.set(ModelSortGroupComponent(group: Self.sortGroup, order: 1))
            pivot.addChild(shadow)
        }
        recolour(dark: false)
    }

    /// Radial black fade, 35 % at the centre to clear at the rim, shared by
    /// both views' discs. `nil` only if the texture cannot be made — the demo
    /// then runs without a shadow.
    private static let shadowTexture: TextureResource? = {
        let side = 128
        guard let context = CGContext(data: nil, width: side, height: side, bitsPerComponent: 8,
                                      bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue),
              let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(),
                                        colors: [UIColor(white: 0, alpha: 0.35).cgColor,
                                                 UIColor(white: 0, alpha: 0).cgColor] as CFArray,
                                        locations: [0, 1]) else { return nil }
        let centre = CGPoint(x: side / 2, y: side / 2)
        context.drawRadialGradient(gradient, startCenter: centre, startRadius: 0,
                                   endCenter: centre, endRadius: CGFloat(side) / 2, options: [])
        guard let image = context.makeImage() else { return nil }
        return try? TextureResource(image: image, withName: nil,
                                    options: .init(semantic: .color, mipmapsMode: .none))
    }()

    private static func contactShadow() -> ModelEntity? {
        guard let texture = shadowTexture else { return nil }
        var material = UnlitMaterial(applyPostProcessToneMap: false)
        material.color = .init(tint: .white, texture: .init(texture))
        material.blending = .transparent(opacity: .init(floatLiteral: 1))
        let size = SecondaryCameraMath.helmetSize * 1.3
        return ModelEntity(mesh: .generatePlane(width: size, depth: size), materials: [material])
    }

    /// One floor texture per scheme, made on first use and shared by both views.
    private static var floorTextures: [Bool: TextureResource] = [:]

    /// The floor and its 25 cm grid, fading at the rim into the backdrop colour
    /// (`Stage.pipBackdrop`) — opaque all the way, so the stage dissolves into
    /// the flat backdrop and no view, the main camera low over it or the inset
    /// from any preset, ever shows an edge. Fully opaque on purpose: a see-through
    /// rim reaches the screen brighter than the backdrop behind it (RealityKit's
    /// surface is composited as premultiplied, its alpha is not), a white halo
    /// in the light theme.
    private static func floorTexture(dark: Bool) -> TextureResource? {
        if let cached = floorTextures[dark] { return cached }
        let side = floorTextureSide
        let rect = CGRect(x: 0, y: 0, width: side, height: side)
        let metres = CGFloat(SecondaryCameraMath.floorSize)
        let perMetre = CGFloat(side) / metres
        let backdrop = SceneViewTokens.Stage.pipBackdrop(dark: dark)
        guard let srgb = CGColorSpace(name: CGColorSpace.sRGB),
              let context = CGContext(data: nil, width: side, height: side, bitsPerComponent: 8,
                                      bytesPerRow: 0, space: srgb,
                                      bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue),
              let fade = CGGradient(colorsSpace: srgb, colors: [
                  backdrop.withAlphaComponent(0).cgColor, backdrop.withAlphaComponent(0).cgColor,
                  backdrop.withAlphaComponent(0.65).cgColor, backdrop.cgColor,
              ] as CFArray, locations: [0, CGFloat(SecondaryCameraMath.floorFadeStart * 2) / metres, 0.8, 1])
        else { return nil }
        context.setFillColor(SceneViewTokens.Stage.pipFloor(dark: dark).cgColor)
        context.fill(rect)
        context.setFillColor(SceneViewTokens.Stage.pipGrid(dark: dark).cgColor)
        let spacing = CGFloat(SecondaryCameraMath.gridSpacing) * perMetre
        let width = CGFloat(SecondaryCameraMath.gridLineWidth) * perMetre
        var offset = rect.midX.truncatingRemainder(dividingBy: spacing)
        while offset < rect.width {
            context.fill(CGRect(x: offset - width / 2, y: 0, width: width, height: rect.height))
            context.fill(CGRect(x: 0, y: offset - width / 2, width: rect.width, height: width))
            offset += spacing
        }
        // Past the disc the square's corners are backdrop too.
        let centre = CGPoint(x: rect.midX, y: rect.midY)
        context.drawRadialGradient(fade, startCenter: centre, startRadius: 0,
                                   endCenter: centre, endRadius: rect.width / 2,
                                   options: [.drawsAfterEndLocation])
        guard let image = context.makeImage(),
              let texture = try? TextureResource(image: image, withName: nil,
                                                 options: .init(semantic: .color,
                                                                mipmapsMode: .allocateAndGenerateAll))
        else { return nil }
        floorTextures[dark] = texture
        return texture
    }

    /// 1024 px for 4 m: 256 px a metre, the 8 mm grid lines two pixels wide.
    private static let floorTextureSide = 1024

    /// Unlit and untone-mapped: under the studio light a lit floor washes out
    /// to white in light mode and to mid-grey in dark, and the grid disappears
    /// into it.
    func recolour(dark: Bool) {
        var material = UnlitMaterial(applyPostProcessToneMap: false)
        if let texture = Self.floorTexture(dark: dark) {
            let sampler = MTLSamplerDescriptor()
            sampler.minFilter = .linear
            sampler.magFilter = .linear
            sampler.mipFilter = .linear
            // The main camera looks along the floor: without anisotropy the grid
            // blurs into the ground a metre out.
            sampler.maxAnisotropy = 8
            material.color = .init(tint: .white,
                                   texture: .init(texture, sampler: .init(sampler)))
        } else {
            material.color = .init(tint: SceneViewTokens.Stage.pipFloor(dark: dark))
        }
        floor.model?.materials = [material]
    }
}

// MARK: - Maths

/// The pure geometry of the demo, unit-tested in `SecondaryCameraMathTests`.
enum SecondaryCameraMath {
    struct Ray: Equatable {
        var origin: SIMD3<Float>
        var direction: SIMD3<Float>
    }

    // Android constants, value for value.
    static let helmetSize: Float = 0.5
    static let stageHalfExtent: Float = 0.35
    static let helmetPickRadius: Float = 0.2
    static let editGlide: TimeInterval = 0.45
    static let stageCentre = SIMD3<Float>(0, 0.2, 0)
    static let mainElevation: Float = 24 * .pi / 180
    static let orbitRadius: Float = 1.45
    static let orbitHeight: Float = 0.75
    /// The floor: 4 m across, its own colour to 0.9 m from the centre, the
    /// backdrop's at 2 m.
    static let floorSize: Float = 4
    static let floorFadeStart: Float = 0.9
    static let gridSpacing: Float = 0.25
    static let gridLineWidth: Float = 0.008
    /// The inset's camera sits at 80 % of Android's distance from the stage
    /// centre: in a 192 × 128 pt window the helmet then fills a third of the
    /// height instead of a quarter, and the 70 cm stage still fits every preset.
    static let insetZoom: Float = 0.8
    /// SceneView's vertical field of view.
    static let verticalFov: Float = 60 * .pi / 180

    /// The orbit pose whose camera sits at `eye` looking at `target`.
    static func pose(eye: SIMD3<Float>, target: SIMD3<Float>) -> SceneCameraPose {
        let offset = eye - target
        let distance = simd_length(offset)
        let horizontal = (offset.x * offset.x + offset.z * offset.z).squareRoot()
        return SceneCameraPose(azimuth: atan2(offset.x, offset.z),
                               elevation: atan2(offset.y, horizontal),
                               distance: distance,
                               target: target)
    }

    /// The world ray through `point` of a `size` viewport rendered from `pose` —
    /// the same construction SceneView uses for its own taps: 60° vertical field
    /// of view, camera looking at the target with +Y up.
    static func ray(through point: CGPoint, in size: CGSize, pose: SceneCameraPose) -> Ray? {
        guard size.width > 0, size.height > 0 else { return nil }
        let eye = pose.cameraPosition()
        let toTarget = pose.target - eye
        guard simd_length(toTarget) > 1e-6 else { return nil }
        let forward = simd_normalize(toTarget)
        var right = simd_cross(forward, SIMD3<Float>(0, 1, 0))
        if simd_length(right) < 1e-6 { right = SIMD3(1, 0, 0) }
        right = simd_normalize(right)
        let up = simd_cross(right, forward)
        let ndcX = Float(2 * point.x / size.width - 1)
        let ndcY = Float(1 - 2 * point.y / size.height)
        let tanV = tan(verticalFov / 2)
        let tanH = tanV * Float(size.width / size.height)
        let direction = simd_normalize(forward + right * (ndcX * tanH) + up * (ndcY * tanV))
        return Ray(origin: eye, direction: direction)
    }

    /// Where `ray` meets the floor (`y = 0`), or `nil` if it points at the sky.
    static func floorHit(_ ray: Ray) -> SIMD3<Float>? {
        guard ray.direction.y < -1e-6 else { return nil }
        let t = -ray.origin.y / ray.direction.y
        guard t > 0 else { return nil }
        return ray.origin + ray.direction * t
    }

    /// Whether `ray` passes within `radius` of `point`, in front of its origin.
    static func passes(_ ray: Ray, within radius: Float, of point: SIMD3<Float>) -> Bool {
        let direction = simd_normalize(ray.direction)
        let along = simd_dot(point - ray.origin, direction)
        guard along > 0 else { return false }
        return simd_length(point - (ray.origin + direction * along)) <= radius
    }

    /// The main camera: 24° above the stage, at the distance that fits the
    /// stage the helmet can walk on (1.2 × 0.5 × 1.2 m, Android's fit extents)
    /// into the screen band between `bandTop` and `bandBottom`, centred on it.
    static func mainFraming(size: CGSize, bandTop: CGFloat, bandBottom: CGFloat) -> SceneCameraPose {
        let elevation = mainElevation
        let halfX = stageHalfExtent + helmetSize / 2
        let target = stageCentre
        var corners: [SIMD3<Float>] = []
        for sx in [-1, 1] as [Float] {
            for y in [0, helmetSize] {
                for sz in [-1, 1] as [Float] {
                    corners.append(SIMD3(sx * halfX, y, sz * halfX))
                }
            }
        }
        let width = Float(max(size.width, 1))
        let height = Float(max(size.height, 1))
        let top = Float(bandTop)
        let band = max(height - top - Float(bandBottom), height * 0.35)
        let fill: Float = 0.92
        let tanV = tan(verticalFov / 2)
        let tanH = tanV * width / height
        let tanBand = tanV * band / height
        let up = SIMD3<Float>(0, cos(elevation), -sin(elevation))
        let back = SIMD3<Float>(0, sin(elevation), cos(elevation))
        var distance: Float = 1
        for corner in corners {
            let offset = corner - target
            let depth = simd_dot(offset, back)
            distance = max(distance, depth + abs(offset.x) / (tanH * fill))
            distance = max(distance, depth + abs(simd_dot(offset, up)) / (tanBand * fill))
        }
        // Centre the stage on the band, not on the screen.
        let bandCentreOffset = (top + band / 2) - height / 2
        let shift = bandCentreOffset / (height / 2) * distance * tanV
        return SceneCameraPose(azimuth: 0, elevation: elevation, distance: distance,
                               target: target + up * shift)
    }
}
