import SwiftUI
import RealityKit
import SceneViewSwift
#if os(iOS)
import ARKit
import UIKit
#endif

/// The screen a 3D file lands on when it is opened from Files, Mail, Messages or any
/// share sheet.
///
/// This is the point of the format work: on iOS today, an STL or a 3MF received by
/// AirDrop or downloaded from a marketplace has **nothing** to open it — Quick Look reads
/// USDZ and Reality only. This view is the SceneView demo app's answer, and it answers
/// the question the file cannot: *how big is this, really?*
///
/// - The **size readout** is in the file's own unit and in centimetres, because a print
///   the user is about to make is a physical object, not a picture.
/// - The **unit picker** appears only for the formats that carry no unit — STL, OBJ, PLY
///   (``ModelFormat/carriesUnit``). Guessing silently is what puts a 21 cm print in the
///   room at 210 m; 3MF and USD state their own unit and get no picker.
/// - **View in AR** places the model at that real size on a detected plane. Not scaled to
///   fit a nice preview — at size, standing on the floor, which is the only way to answer
///   "will this fit?".
struct OpenedFileViewer: View {

    /// The file as the system handed it over.
    let url: URL

    /// Presentation dismissal — this screen is always presented, never pushed.
    @Environment(\.dismiss) private var dismiss

    @State private var asset: MeshAsset?
    @State private var loadedNode: ModelNode?
    @State private var loadError: String?
    /// The unit the user chose, once they have chosen one. `nil` = whatever the format
    /// or the file says.
    @State private var chosenUnit: ModelUnit?
    @State private var format: ModelFormat?
    @State private var showAR = false
    @State private var recenterGeneration = 0
    @State private var loadCount = 0

    /// A working copy of the file, owned by this view.
    ///
    /// A security-scoped URL from the document picker is only valid between
    /// `startAccessingSecurityScopedResource()` and its stop, and a parse (plus the AR
    /// session that may follow) outlives that window. Copying once into `tmp` is both
    /// simpler and safer than holding the scope open across an async boundary.
    @State private var workingCopy: URL?

    private var arSupported: Bool {
        #if os(iOS)
        return ARWorldTrackingConfiguration.isSupported
        #else
        return false
        #endif
    }

    /// Units offered in the picker — the ones a mesh file is plausibly authored in.
    private static let offeredUnits: [ModelUnit] = [
        .millimeters, .centimeters, .inches, .meters
    ]

    var body: some View {
        ZStack {
            SceneViewTokens.Stage.background.ignoresSafeArea()
            stage
            VStack {
                Spacer()
                if let loadError {
                    banner(loadError)
                } else if let asset {
                    measurements(of: asset)
                }
            }
            // Clears the floating dock band with room to spare: the dock is centred and
            // rounded, so a readout that merely *reaches* its top edge still collides
            // with its background.
            .padding(.bottom, SceneViewTokens.Layout.dockHeight + SceneViewTokens.Space.lg * 2)
        }
        .demoChrome(
            title: url.lastPathComponent,
            dock: dock,
            accent: DockItem(
                icon: "arkit",
                label: "View in AR",
                enabled: arSupported && loadedNode != nil
            ) { showAR = true },
            onReset: { recenterGeneration += 1 }
        )
        #if os(iOS)
        .fullScreenCover(isPresented: $showAR) {
            NavigationStack {
                ARPlacementDemo(
                    initialModelURL: workingCopy,
                    initialModelUnit: chosenUnit
                )
                .navigationTitle("Tap to Place")
                .navigationBarTitleInline()
            }
            .environment(\.demoTitle, "Tap to Place")
        }
        #endif
        .task { await load() }
        .onChange(of: chosenUnit) { _, _ in
            Task { await load() }
        }
        .onDisappear(perform: discardWorkingCopy)
    }

    // MARK: - Stage

    @ViewBuilder
    private var stage: some View {
        ZStack {
            SceneView { root in
                guard let loadedNode else { return }
                root.addChild(loadedNode.entity)
            }
            .cameraControls(.orbit)
            // A three-quarter view from slightly above. A print or a scan is a solid,
            // and a head-on camera flattens a pyramid or a plate into a silhouette that
            // says nothing about its depth.
            .cameraOrbit(azimuth: .pi / 5, elevation: .pi / 9)
            // Studio IBL for the lighting, skybox off: the subject is a part someone is
            // measuring, and a photographic backdrop behind it competes with the
            // silhouette instead of describing it. `Stage.background` shows through.
            .environment(.custom(name: "Studio", hdrFile: "studio.hdr", showSkybox: false))
            .framingMargin(1.12)
            .contentID(loadedNode == nil ? nil : "\(loadCount)")
            .recenterCamera(recenterGeneration)
            .ignoresSafeArea()

            if loadedNode == nil && loadError == nil {
                ProgressView().tint(.white)
            }
        }
    }

    /// The size readout, and the unit picker for a format that has no unit of its own.
    @ViewBuilder
    private func measurements(of asset: MeshAsset) -> some View {
        VStack(spacing: SceneViewTokens.Space.sm) {
            GlassPill {
                Text(sizeSummary(of: asset))
                    .font(SceneViewTokens.TypeScale.caption)
                    .foregroundStyle(SceneViewTokens.Glass.onGlass)
                    .lineLimit(1)
                    // Shrinks rather than truncates: the centimetre pair sits at the
                    // end of the string, and it is the half the user came for. A
                    // `127.6 × 127.6 × 127.6 mm (12.8 × 12.8 × 12.8 c…` readout drops
                    // exactly the number that answers "how big is this, really?".
                    .minimumScaleFactor(0.7)
                Text("· \(asset.triangleCount) triangles")
                    .font(SceneViewTokens.TypeScale.captionRegular)
                    .foregroundStyle(SceneViewTokens.Glass.onGlassMuted)
                    .lineLimit(1)
                    // Yields its width first: the triangle count is context, the size
                    // is the answer.
                    .layoutPriority(-1)
                    .minimumScaleFactor(0.7)
            }

            if let format, !format.carriesUnit {
                unitPicker(current: asset.unit, format: format)
            }
        }
        .padding(.horizontal, SceneViewTokens.Space.md)
    }

    /// `210 × 100 × 50 mm  (21.0 × 10.0 × 5.0 cm)` — the file's own numbers first,
    /// because that is what the user will recognise from their slicer, then the
    /// real-world size they may not have realised the first pair implied.
    private func sizeSummary(of asset: MeshAsset) -> String {
        guard let bounds = asset.bounds, let metric = asset.boundsInMeters else {
            return "No measurable geometry"
        }
        let source = bounds.extents
        let centimeters = metric.extents * 100
        let native = String(
            format: "%.4g × %.4g × %.4g %@",
            source.x, source.y, source.z, asset.unit.symbol
        )
        guard asset.unit != .centimeters else { return native }
        let real = String(
            format: "%.1f × %.1f × %.1f cm",
            centimeters.x, centimeters.y, centimeters.z
        )
        return "\(native)  (\(real))"
    }

    /// Only shown for STL, OBJ and PLY. The honest interaction: those formats store bare
    /// numbers, so the app asks rather than guessing and being wrong by 1000×.
    ///
    /// A row of glass chips rather than a `Picker(.segmented)` — the segmented control
    /// draws its own opaque track, which fights the glass it would sit on and reads as a
    /// misaligned box over a live viewport.
    private func unitPicker(current: ModelUnit, format: ModelFormat) -> some View {
        HStack(spacing: SceneViewTokens.Space.xs) {
            Text("Units")
                .font(SceneViewTokens.TypeScale.captionRegular)
                .foregroundStyle(SceneViewTokens.Glass.onGlassMuted)
                .padding(.trailing, SceneViewTokens.Space.xs)
            ForEach(Self.offeredUnits, id: \.self) { unit in
                unitChip(unit, selected: (chosenUnit ?? current) == unit)
            }
        }
        .padding(.horizontal, SceneViewTokens.Glass.pillPaddingHorizontal)
        .frame(height: SceneViewTokens.Glass.pillHeight + SceneViewTokens.Space.sm)
        .background(glassBackground(in: Capsule()))
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Model units")
    }

    private func unitChip(_ unit: ModelUnit, selected: Bool) -> some View {
        Button {
            guard chosenUnit != unit else { return }
            chosenUnit = unit
        } label: {
            Text(unit.symbol)
                .font(SceneViewTokens.TypeScale.captionSemibold)
                // `heroPillText` (#1A1A2E), not `chipSelectedText`: the selected chip is
                // a fixed white capsule floating over a live viewport, so its label must
                // be dark in both themes — the theme-flipping chip token would turn it
                // white-on-white in dark mode.
                .foregroundStyle(
                    selected
                        ? SceneViewTokens.HomeColor.heroPillText
                        : SceneViewTokens.Glass.onGlass
                )
                .frame(minWidth: 34)
                .frame(height: SceneViewTokens.Glass.pillHeight - SceneViewTokens.Space.sm)
                .padding(.horizontal, SceneViewTokens.Space.sm)
                .background(
                    Capsule().fill(selected ? Color.white : Color.white.opacity(0.10))
                )
        }
        .buttonStyle(.plain)
        .accessibilityLabel(unit.rawValue)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }

    private func banner(_ message: String) -> some View {
        Text(message)
            .font(SceneViewTokens.TypeScale.captionRegular)
            .foregroundStyle(SceneViewTokens.Glass.onGlass)
            .multilineTextAlignment(.center)
            .padding(.horizontal, SceneViewTokens.Space.md)
            .padding(.vertical, SceneViewTokens.Space.sm)
            .background(glassBackground(in: RoundedRectangle(cornerRadius: SceneViewTokens.Radius.md)))
            .padding(.horizontal, SceneViewTokens.Space.lg)
    }

    private var dock: [DockItem] {
        [DockItem(icon: "scope", label: "Recenter") { recenterGeneration += 1 }]
    }

    // MARK: - Loading

    @MainActor
    private func load() async {
        loadError = nil
        do {
            let local = try workingCopy ?? makeWorkingCopy()
            workingCopy = local
            let sniffed = try ModelFormat.sniff(contentsOf: local)
            format = sniffed

            if sniffed.loader == .realityKit {
                // USD and Reality are already metric and have no MeshAsset form.
                asset = nil
                install(try await ModelNode.load(contentsOf: local))
                return
            }
            let parsed = try MeshAsset.load(contentsOf: local, format: sniffed, unit: chosenUnit)
            asset = parsed
            install(try await ModelNode(parsed))
        } catch {
            loadedNode = nil
            asset = nil
            loadError = message(for: error)
        }
    }

    @MainActor
    private func install(_ node: ModelNode) {
        // Framed, not resized: the entity keeps its real-world metres so the AR handoff
        // and the size readout describe the same object. `.framingMargin` moves the
        // camera instead of the model.
        _ = node.centerOrigin()
        loadedNode = node
        loadCount += 1
    }

    /// A human-facing message. ``ModelLoadingError`` already names the format the user
    /// tried to open, which is the one thing a "could not open this file" alert usually
    /// fails to say.
    private func message(for error: any Error) -> String {
        if let loading = error as? ModelLoadingError {
            return loading.errorDescription ?? "\(loading)"
        }
        return "Could not open \(url.lastPathComponent): \(error.localizedDescription)"
    }

    // MARK: - Working copy

    /// Copies the incoming file into `tmp`, opening the security scope for exactly as
    /// long as the copy takes.
    private func makeWorkingCopy() throws -> URL {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }

        let destination = FileManager.default.temporaryDirectory
            .appendingPathComponent("opened-\(UUID().uuidString)")
            .appendingPathExtension(url.pathExtension)
        try FileManager.default.copyItem(at: url, to: destination)
        return destination
    }

    private func discardWorkingCopy() {
        guard let workingCopy else { return }
        try? FileManager.default.removeItem(at: workingCopy)
        self.workingCopy = nil
    }
}
