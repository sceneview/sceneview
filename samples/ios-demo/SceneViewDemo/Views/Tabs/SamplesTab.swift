import SwiftUI
import SceneViewSwift

/// Samples tab — curated preset scenes grouped by category, presented as
/// Liquid Glass cards in a scrollable list.
///
/// Tapping an available sample opens it in a `.fullScreenCover`: every demo
/// mounts a full-screen `SceneView` (RealityView) viewport that needs the whole
/// screen — a partial sheet detent renders the 3D surface as a black, half-height
/// panel that obscures the list (#1392). Only the lightweight `ComingSoonScreen`
/// (a plain scrollable info screen, no 3D surface) uses the `.sheet`.
///
/// A `3D / AR` filter chip at the top filters the visible categories.
struct SamplesTab: View {
    private let scenes: [DemoItem] = Self.allScenes()

    @State private var selectedScene: DemoItem?
    @State private var fullScreenScene: DemoItem?
    @State private var filter: ScopeFilter = .all

    private enum ScopeFilter: String, CaseIterable, Identifiable {
        case all = "All"
        case threeD = "3D"
        case ar = "AR"

        var id: String { rawValue }
        var icon: String {
            switch self {
            case .all: return "square.grid.2x2.fill"
            case .threeD: return "cube.fill"
            case .ar: return "arkit"
            }
        }
    }

    private static func shouldOpenFullScreen(_ scene: DemoItem) -> Bool {
        // Every available demo mounts a full-screen `SceneView` (RealityView)
        // viewport. A partial `.medium` sheet detent renders that 3D surface as
        // a black, half-height panel that covers the Samples list (#1392), so
        // all available demos — 3D and AR alike — take the whole screen.
        // Coming-soon entries route to the lightweight `ComingSoonScreen` and
        // stay in the `.sheet`.
        scene.status.isAvailable
    }

    private var filteredScenes: [DemoItem] {
        switch filter {
        case .all: return scenes
        case .threeD: return scenes.filter { $0.category != .ar }
        case .ar: return scenes.filter { $0.category == .ar }
        }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 24) {
                    filterBar

                    let grouped = Dictionary(grouping: filteredScenes) { $0.category }
                    let sortedCategories = grouped.keys.sorted()
                    ForEach(sortedCategories, id: \.self) { category in
                        categorySection(category: category, items: grouped[category]!)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 24)
            }
            .navigationTitle("Samples")
            .sheet(item: $selectedScene) { scene in
                sheetDestination(for: scene)
                    #if os(iOS)
                    .presentationDetents([.medium, .large])
                    .presentationBackground(.regularMaterial)
                    .presentationCornerRadius(28)
                    .presentationDragIndicator(.visible)
                    #endif
            }
            #if os(iOS)
            .fullScreenCover(item: $fullScreenScene) { scene in
                NavigationStack {
                    sheetDestination(for: scene)
                        // A `.fullScreenCover` has no drag-to-dismiss handle
                        // (unlike the `.sheet` path), so it needs an explicit
                        // Close affordance or the user gets stuck inside the
                        // demo with no way back to the Samples list — see
                        // #1580. Matches the Close button the deep-link
                        // placeholder already uses (DemoDeepLinkRegistry).
                        .toolbar {
                            ToolbarItem(placement: .topBarLeading) {
                                Button {
                                    HapticManager.lightTap()
                                    fullScreenScene = nil
                                } label: {
                                    Label("Close", systemImage: "xmark")
                                }
                                .accessibilityLabel("Close demo")
                                .accessibilityIdentifier("demo-close")
                            }
                        }
                }
            }
            #endif
        }
    }

    // MARK: - Filter bar

    private var filterBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(ScopeFilter.allCases) { option in
                    Button {
                        filter = option
                        #if os(iOS)
                        HapticManager.selectionChanged()
                        #endif
                    } label: {
                        HStack(spacing: 6) {
                            Image(systemName: option.icon)
                                .font(.caption.weight(.semibold))
                            Text(option.rawValue)
                                .font(.subheadline.weight(.medium))
                        }
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(
                            filter == option
                                ? AnyShapeStyle(.tint)
                                : AnyShapeStyle(.regularMaterial),
                            in: Capsule()
                        )
                        .foregroundStyle(filter == option ? Color.white : Color.primary)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("\(option.rawValue) filter")
                    .accessibilityAddTraits(filter == option ? .isSelected : [])
                }
            }
        }
        .scrollClipDisabled()
    }

    // MARK: - Category section

    private func categorySection(category: DemoCategory, items: [DemoItem]) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(category.rawValue)
                .font(.title3.weight(.bold))
                .padding(.horizontal, 4)

            VStack(spacing: 8) {
                ForEach(items) { scene in
                    Button {
                        handleTap(scene)
                    } label: {
                        SceneRow(scene: scene)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(accessibilityLabel(for: scene))
                }
            }
        }
    }

    private func handleTap(_ scene: DemoItem) {
        #if os(iOS)
        HapticManager.lightTap()
        if Self.shouldOpenFullScreen(scene) {
            fullScreenScene = scene
        } else {
            selectedScene = scene
        }
        #else
        selectedScene = scene
        #endif
    }

    @ViewBuilder
    private func sheetDestination(for scene: DemoItem) -> some View {
        switch scene.status {
        case .available:
            scene.destination
                .navigationTitle(scene.title)
                #if os(iOS)
                .navigationBarTitleDisplayMode(.inline)
                #endif
        case .comingSoon:
            ComingSoonScreen(
                title: scene.title,
                subtitle: scene.subtitle,
                icon: scene.icon
            )
        }
    }

    private func accessibilityLabel(for scene: DemoItem) -> String {
        switch scene.status {
        case .available:
            return "\(scene.title): \(scene.subtitle)"
        case .comingSoon:
            return "\(scene.title): \(scene.subtitle). Coming soon."
        }
    }

    // MARK: - Scene catalog

    private static func allScenes() -> [DemoItem] {
        // Category + per-demo category assignment mirror Android's
        // `DemoRegistry.ALL_DEMOS` (`samples/android-demo/.../DemoRegistry.kt`)
        // verbatim — see #1377.
        var items: [DemoItem] = [
            // MARK: 3D Basics

            DemoItem(title: "Model Viewer", icon: "cube.transparent.fill", subtitle: "Load and display 3D models", category: .basics3D) {
                ModelViewerDemo()
            },
            DemoItem(title: "Geometry Primitives", icon: "cube.fill", subtitle: "Cube, sphere, cylinder, cone, plane", category: .basics3D) {
                GeometryDemo()
            },
            DemoItem(title: "Animation", icon: "figure.run", subtitle: "Play, pause, and control animations", category: .basics3D) {
                AnimationDemo()
            },
            DemoItem(title: "Multi-Model Scene", icon: "tree.fill", subtitle: "Multiple models in one scene", category: .basics3D) {
                MultiModelDemo()
            },
            DemoItem(title: "Scene Gallery", icon: "square.grid.3x3.fill", subtitle: "Themed Sketchfab bundles streamed on demand", category: .basics3D) {
                SceneGalleryDemo()
            },

            // MARK: Lighting & Environment

            DemoItem(title: "Light Types", icon: "lightbulb.fill", subtitle: "Directional, point, and spot lights", category: .lighting) {
                LightingDemo()
            },
            DemoItem(title: "Movable Light", icon: "sun.dust.fill", subtitle: "Drag to orbit the light around the model", category: .lighting) {
                MovableLightDemo()
            },
            DemoItem(title: "Dynamic Sky", icon: "sun.horizon.fill", subtitle: "Time-of-day sun simulation", category: .lighting) {
                DynamicSkyDemo()
            },
            DemoItem(title: "Fog", icon: "cloud.fog.fill", subtitle: "Linear and exponential fog", category: .lighting) {
                FogDemo()
            },

            // MARK: Content

            DemoItem(title: "3D Text", icon: "textformat", subtitle: "Extruded text with styles and sizes", category: .content) {
                TextDemo()
            },
            DemoItem(title: "Lines & Paths", icon: "point.topleft.down.to.point.bottomright.curvepath", subtitle: "Polylines, helix, grids, and circles", category: .content) {
                LinesPathsDemo()
            },
            DemoItem(title: "Image Planes", icon: "photo.fill", subtitle: "Image planes in 3D space", category: .content) {
                ImageDemo()
            },
            DemoItem(title: "Billboard", icon: "person.fill.viewfinder", subtitle: "Labels that face the camera", category: .content) {
                BillboardDemo()
            },
            DemoItem(
                comingSoonTitle: "Video Texture",
                icon: "video.fill",
                subtitle: "Video playback on a 3D surface",
                category: .content
            ),

            // MARK: Interaction
            // CameraControlsDemo ships here with the full 3-way mode picker
            // (orbit / pan / firstPerson) since v4.3.0 (#1034). Gesture-editing
            // demos below are still pending iOS port.

            DemoItem(title: "Camera Controls", icon: "camera.fill", subtitle: "Orbit, pan, and free camera modes", category: .interaction) {
                CameraControlsDemo()
            },
            DemoItem(
                comingSoonTitle: "Gesture Editing",
                icon: "hand.pinch.fill",
                subtitle: "Move, scale, and rotate with gestures",
                category: .interaction
            ),
            DemoItem(
                comingSoonTitle: "Collision & Hit Test",
                icon: "capsule.fill",
                subtitle: "Hit testing and collision detection",
                category: .interaction
            ),
            DemoItem(
                comingSoonTitle: "ViewNode",
                icon: "rectangle.stack.fill",
                subtitle: "Native UI embedded in 3D space",
                category: .interaction
            ),

            // MARK: Advanced

            DemoItem(title: "PBR Materials", icon: "paintpalette.fill", subtitle: "PBR metallic and roughness spectrum", category: .advanced) {
                MaterialsDemo()
            },
            DemoItem(title: "Physics", icon: "figure.walk", subtitle: "Gravity, collisions, and rigid bodies", category: .advanced) {
                PhysicsDemo()
            },
            DemoItem(title: "Double Pendulum", icon: "waveform.path", subtitle: "Chaotic two-link physics, shared KMP simulation", category: .advanced) {
                DoublePendulumDemo()
            },
            DemoItem(title: "Custom Mesh", icon: "diamond.fill", subtitle: "Custom vertex and index buffers", category: .advanced) {
                CustomMeshDemo()
            },
            DemoItem(
                comingSoonTitle: "Post Processing",
                icon: "sparkles",
                subtitle: "SSAO, anti-aliasing, and tone mapping",
                category: .advanced
            ),
            DemoItem(
                comingSoonTitle: "Shape Extrude",
                icon: "scribble.variable",
                subtitle: "Extrude 2D polygons into 3D meshes",
                category: .advanced
            ),
            DemoItem(
                comingSoonTitle: "Reflection Probes",
                icon: "circle.lefthalf.filled",
                subtitle: "Local cubemap reflections",
                category: .advanced
            ),
            DemoItem(
                comingSoonTitle: "Secondary Camera (PiP)",
                icon: "pip.fill",
                subtitle: "Picture-in-picture camera view",
                category: .advanced
            ),
        ]

        // MARK: AR -- iOS only platform-wise; most features still ported from Android
        #if os(iOS)
        items.append(contentsOf: [
            DemoItem(title: "Rerun Debug", icon: "antenna.radiowaves.left.and.right", subtitle: "Stream camera pose and planes to the Rerun viewer", category: .ar) {
                RerunDebugDemo()
            },
            DemoItem(title: "Orbital AR", icon: "circle.dotted", subtitle: "Models orbit around you in a personal solar system", category: .ar) {
                OrbitalARDemo()
            },
            DemoItem(title: "AR Recording", icon: "record.circle", subtitle: "Capture the AR session as a screen video (record-only on iOS)", category: .ar) {
                ARRecorderDemo()
            },
            DemoItem(title: "AR Lighting", icon: "lightbulb.max.fill", subtitle: "Compare .mainLight / .fillLight modifier presets", category: .ar) {
                ARLightingDemo()
            },
            DemoItem(title: "Tap to Place", icon: "arkit", subtitle: "Tap a detected plane to place a model", category: .ar) {
                ARPlacementDemo()
            },
            DemoItem(
                comingSoonTitle: "Image Tracking",
                icon: "viewfinder.circle.fill",
                subtitle: "Detect and track reference images",
                category: .ar
            ),
            DemoItem(
                comingSoonTitle: "Augmented Faces",
                icon: "face.smiling.inverse",
                subtitle: "Face mesh tracking and overlays",
                category: .ar
            ),
            DemoItem(
                comingSoonTitle: "Cloud Anchors",
                icon: "icloud.fill",
                subtitle: "Persistent multi-user anchors",
                category: .ar
            ),
            DemoItem(
                comingSoonTitle: "Streetscape Geometry",
                icon: "map.fill",
                subtitle: "Geospatial building and terrain meshes",
                category: .ar
            ),
            DemoItem(
                comingSoonTitle: "Pose Placement",
                icon: "move.3d",
                subtitle: "Free pose positioning",
                category: .ar
            ),
            // "AR Record & Playback" coming-soon entry dropped in #1378 — it
            // duplicated the shipped "AR Recording" demo above. ARKit does not
            // expose ARCore-style session replay, so the iOS demo ships
            // record-only and does not promise a playback feature.
            DemoItem(
                comingSoonTitle: "Depth Occlusion",
                icon: "square.3.layers.3d.down.right",
                subtitle: "Real-world depth masks virtual objects",
                category: .ar
            ),
            DemoItem(title: "Instant Placement", icon: "bolt.fill", subtitle: "Place models before plane detection converges", category: .ar) {
                ARInstantPlacementDemo()
            },
            DemoItem(
                comingSoonTitle: "Terrain Anchors",
                icon: "mountain.2.fill",
                subtitle: "Anchor models on geospatial terrain",
                category: .ar
            ),
            DemoItem(
                comingSoonTitle: "Rooftop Anchors",
                icon: "house.fill",
                subtitle: "Anchor models on geospatial rooftops",
                category: .ar
            ),
            DemoItem(
                comingSoonTitle: "Image Stabilization (EIS)",
                icon: "camera.metering.matrix",
                subtitle: "EIS for smoother AR camera feed",
                category: .ar
            ),
        ])
        #endif

        return items
    }
}

// MARK: - Scene row view (Liquid Glass card)

private struct SceneRow: View {
    let scene: DemoItem

    var body: some View {
        HStack(spacing: 14) {
            ZStack {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(
                        LinearGradient(
                            colors: scene.status.isAvailable
                                ? [Color.blue.opacity(0.25), Color.purple.opacity(0.15)]
                                : [Color.secondary.opacity(0.15), Color.secondary.opacity(0.08)],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                Image(systemName: scene.icon)
                    .font(.title3)
                    .foregroundStyle(scene.status.isAvailable ? Color.blue : Color.secondary)
            }
            .frame(width: 44, height: 44)
            .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 8) {
                    Text(scene.title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(scene.status.isAvailable ? Color.primary : Color.secondary)
                        .lineLimit(1)

                    if scene.status.isComingSoon {
                        Text("Soon")
                            .font(.caption2.weight(.semibold))
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(.orange.opacity(0.18), in: Capsule())
                            .foregroundStyle(.orange)
                    }
                }

                Text(scene.subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                    .opacity(scene.status.isAvailable ? 1.0 : 0.7)
            }

            Spacer(minLength: 4)

            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.tertiary)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .strokeBorder(Color.primary.opacity(0.06), lineWidth: 0.5)
        )
    }
}
