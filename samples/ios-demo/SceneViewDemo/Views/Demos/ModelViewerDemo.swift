import SwiftUI
import RealityKit
import SceneViewSwift
#if os(iOS)
import ARKit
#endif

/// Full-screen 3D model viewer — the iOS twin of Android's `ModelViewerDemo.kt`
/// after the showcase redesign.
///
/// **Stage.** A `#0B0F16` stage (`SceneViewTokens.Stage.background`), no
/// auto-rotate: the model sits still on its fitted framing
/// (`framingMargin(0.91)`, sized to Android's) until the user orbits it. The
/// camera opens in front of the model, azimuth 0 and 12° above it, as on
/// Android; a model that reads better three-quarter is turned by its own
/// `frontYaw`, not by the camera.
///
/// **Dock.** Models · Lighting · Animate (only when the loaded entity has
/// animation clips) · Recenter — Android's order — then the scaffold's
/// Settings and the accent "View in AR", enabled when ARKit world
/// tracking is available, which presents the existing `ARPlacementDemo` armed
/// with the selected bundled model.
///
/// **Sheets.** Models — the bundled USDZ grid (transparent 5:4 `model_thumb_*`
/// renders, Android's own where the model is shared) plus the "Surprise me" Sketchfab row (hidden without an API
/// key) and "Browse online models"; Environment — the bundled HDRs with their
/// `env_thumb_*` tiles, an IBL intensity slider and a skybox toggle.
///
/// Honours the umbrella's hard rules: no Sketchfab WebView, local file URLs
/// only, something useful renders offline (the bundled hero).
struct ModelViewerDemo: View {
    /// Bundled models offered in the Models sheet. The Khronos set mirrors
    /// Android's grid; the hovercar is the iOS store hero, selected under
    /// `qa_mode` by ``storeHeroAssetName`` rather than by being first here.
    ///
    /// `internal`, not `private` — `ViewerAssetTests` iterates this exact array
    /// (rather than a hand-copied duplicate) via `@testable import` so a model
    /// added here without its `model_thumb_<asset>` tile fails the suite
    /// instead of silently regressing to the blank-cube placeholder (#3584).
    static let bundledModels: [BundledViewerModel] = [
        BundledViewerModel(assetName: "khronos_damaged_helmet", displayName: "Damaged Helmet",
                           description: "Scuffed metal and glass"),
        // HD pack: 52 MB, full 2K textures, downloaded once. The bundled
        // Damaged Helmet stands in until the file is on disk — same choice
        // as Android.
        BundledViewerModel(assetName: "khronos_flight_helmet", displayName: "Flight Helmet",
                           description: "Leather, brass and glass · HD",
                           hdPackID: "flight-helmet", standInAssetName: "khronos_damaged_helmet"),
        // Fox, Hovercar and Butterfly are iOS-only tiles: Android has no
        // description to copy, so theirs are written in the same register.
        BundledViewerModel(assetName: "khronos_fox", displayName: "Fox",
                           description: "Survey, walk and run clips"),
        BundledViewerModel(assetName: "khronos_lantern", displayName: "Lantern",
                           description: "Wooden post, metal lantern"),
        BundledViewerModel(assetName: "khronos_toy_car", displayName: "Toy Car",
                           description: "Clearcoat car on velvet"),
        // iOS-only and the App Store hero: head-on it is a grille, so it
        // turns three-quarter by itself, like the museum models.
        BundledViewerModel(assetName: "cyberpunk_hovercar", displayName: "Cyberpunk Hovercar",
                           description: "Dark gloss bodywork", frontYaw: -30),
        BundledViewerModel(assetName: "animated_butterfly", displayName: "Butterfly",
                           description: "Monarch, wings in flight"),
    ]

    /// "Museum & Space": HD pack models with no bundled copy, in Android's
    /// order. Until the file is on disk the stage shows the model's thumbnail
    /// with the HD pill; then the HD model at its real-world size.
    ///
    /// `hdPackID` is the shared id in `assets/hd-pack/{ios,android}.json`;
    /// the pill reads its title there, the stage its real-world `scale`.
    /// `assetName` only keys the `model_thumb_<assetName>` tile. None is
    /// offered in AR: the HD file is not placed in AR until a real-device run
    /// proves it (see #4147).
    static let museumModels: [BundledViewerModel] = [
        BundledViewerModel(assetName: "hd_apollo11_exterior", displayName: "Apollo 11 Command Module",
                           description: "Columbia, as flown in 1969 · HD",
                           hdPackID: "apollo11-exterior"),
        BundledViewerModel(assetName: "hd_apollo11_interior", displayName: "Apollo 11 Interior",
                           description: "Inside the capsule, cut away · HD",
                           hdPackID: "apollo11-interior"),
        // Head-on, the tusks hide the skeleton: it opens three-quarter, as its card shows it.
        BundledViewerModel(assetName: "hd_woolly_mammoth", displayName: "Woolly Mammoth",
                           description: "Full skeleton, 3.4 m tall · HD",
                           hdPackID: "woolly-mammoth", frontYaw: -30),
        // Shown static: the rover's rigging clips stay under Animate, paused.
        // Three-quarter like the mammoth: head-on it is a wall of wheels.
        BundledViewerModel(assetName: "hd_perseverance", displayName: "Perseverance Rover",
                           description: "Mars 2020 rover, real size · HD",
                           hdPackID: "perseverance", frontYaw: -30, autoplaysAnimations: false),
    ]

    /// The lighting a model opens under while the stage is still on the
    /// default — applied through ``ViewerLighting``.
    ///
    /// A museum scan is a record of an object's real colours, so it opens
    /// under **Studio** (`studio_warm`, the grey softbox studio — mean
    /// RGB 139/140/145, no cast), as on Android. Chinese Garden, the viewer's
    /// first-run sky, is green-dominant and tinted the Apollo interior green.
    /// Everything else opens under ``defaultEnvironment``.
    static func openingEnvironment(for model: BundledViewerModel) -> ViewerEnvironment {
        guard museumModels.contains(model),
              let studio = environments.first(where: { $0.assetName == museumEnvironmentName })
        else { return defaultEnvironment }
        return studio
    }

    static let museumEnvironmentName = "studio_warm"

    #if DEBUG
    /// `-viewer_model <assetName>` (DEBUG, QA captures): opens the viewer on
    /// that model — bundled or Museum & Space — instead of the default.
    static var launchArgModel: BundledViewerModel? {
        let args = CommandLine.arguments
        guard let i = args.firstIndex(of: "-viewer_model"), i + 1 < args.count else { return nil }
        return (bundledModels + museumModels).first { $0.assetName == args[i + 1] }
    }
    #endif

    /// Bundled HDRs offered in the Environment sheet, in Android's order.
    ///
    /// `internal`, not `private` — see ``bundledModels``: `ViewerAssetTests`
    /// walks this array directly.
    ///
    /// Every name says what the HDR shows, as on Android since #4052 (#4103):
    /// `sunset.hdr` is a bright cloudy sky over a calm sea, so it reads
    /// "Seascape"; `studio.hdr` is a sunlit living room, "Interior"; and
    /// `studio_warm.hdr` is the softbox studio, "Studio". Android's real sunset
    /// (`sky_on_fire`) is not bundled on iOS, so there is no "Sunset" tile here:
    /// "Seascape", an iOS-only tile, takes its second place. The rest is
    /// Android's order — Chinese Garden first, then Studio before Interior.
    static let environments: [ViewerEnvironment] = [
        ViewerEnvironment(assetName: "chinese_garden", displayName: "Chinese Garden", authoredAsPlace: true),
        ViewerEnvironment(assetName: "sunset", displayName: "Seascape", authoredAsPlace: true),
        ViewerEnvironment(assetName: "studio_warm", displayName: "Studio", authoredAsPlace: false),
        ViewerEnvironment(assetName: "studio", displayName: "Interior", authoredAsPlace: false),
        ViewerEnvironment(assetName: "outdoor_cloudy", displayName: "Outdoor Cloudy", authoredAsPlace: true),
        ViewerEnvironment(assetName: "night_sky", displayName: "Night Sky", authoredAsPlace: true),
        ViewerEnvironment(assetName: "rooftop_night", displayName: "Rooftop Night", authoredAsPlace: true),
    ]

    /// The environment a first run lands on.
    ///
    /// #3583 asked for the environment backdrop to be visible by default "sans le
    /// forcer". A studio rig has no backdrop worth drawing — it is four softbox
    /// panels in a void — so shipping `studio` first meant the smart default below
    /// always resolved to "hidden" and nobody ever saw an environment. Landing on a
    /// place instead makes the default self-explanatory: the garden you can see is
    /// the sky lighting the model. Same first-run environment as Android, whose
    /// viewer opens on the first tile, Chinese Garden (#4103); the user can pick a
    /// studio (or switch the backdrop off) at any time.
    static let defaultEnvironment: ViewerEnvironment =
        environments.first { $0.assetName == "chinese_garden" } ?? environments[0]

    /// Remembered answer to "should the backdrop be drawn?", persisted across launches.
    ///
    /// `auto` is the smart default (#3583): the backdrop follows the picked
    /// environment — drawn for one authored as a place, hidden for a studio rig, so
    /// choosing Seascape actually shows you the sea instead of only its reflection.
    /// The moment the user touches the "Show environment" switch the answer stops
    /// being inferred and their choice sticks for every environment and every launch,
    /// which is the "sans le forcer" half of the ask. "Reset" returns to `auto`.
    private enum SkyboxPreference: Int {
        case auto = 0, alwaysOn = 1, alwaysOff = 2
    }

    /// The subject App Store slot 1 is meant to show. The interactive default
    /// is `bundledModels[0]` (Damaged Helmet) — the Khronos reference model a
    /// first-run user should land on — but `dynamic-sky`, which fills slot 2,
    /// loads that *same* helmet since #3003. Left alone the listing showed one
    /// subject twice: the redesign (#3308) rewrote this view and kept the
    /// "hovercar is the iOS store hero" comment while defaulting to index 0,
    /// so the store hero silently stopped being captured (#3006). Under
    /// `qa_mode` only — the interactive first-run subject is unchanged.
    private static let storeHeroAssetName = "cyberpunk_hovercar"

    /// The stage App Store slot 1 is meant to stand on, drawn as a skybox.
    ///
    /// The interactive default is ``defaultEnvironment`` (`chinese_garden`) with its
    /// backdrop drawn (#3583). That is still the wrong stage for a store frame:
    /// the listing wants a controlled warm studio behind the hero rather than a
    /// sky, so slot 1 pins `studio_warm` and forces the skybox on. With no
    /// backdrop drawn at all, the hovercar's dark bodywork reads as a grey silhouette
    /// on near-black. That exact frame was already rejected once — #2896 was
    /// filed about a "dim, dark-on-black" capture, and the fix recorded at the
    /// time was to stage the hero in `studio_warm`, an actual photo studio
    /// (seamless cyclorama, softboxes) whose bright backdrop separates the
    /// silhouette at every orbit angle. The redesign (#3308) rewrote this view
    /// and dropped that constant, so the store frames silently went back to
    /// dark-on-black — the same class of regression #3382 fixed for the hero
    /// model. Applied under `qa_mode` only.
    private static let storeHeroEnvironmentName = "studio_warm"

    /// The camera's opening pose, Android's (`DemoMath.viewerFraming`): in
    /// front of the model (+Z, azimuth 0) and `VIEWER_PITCH_DEGREES` = 12°
    /// above it. A model that reads better three-quarter is turned by its own
    /// ``BundledViewerModel/frontYaw``, as on Android, not by the camera.
    /// Recenter returns to this same pose (`SceneView.recenterCamera`
    /// restores the authored orbit angles).
    ///
    /// `ViewerAssetTests` reads the Android constant and `frontYaw` values.
    static let openingAzimuth: Float = 0
    static let openingElevation: Float = 12 * .pi / 180

    /// Fitted framing, sized to Android's. Android fits the front view of the
    /// box to 65 % of the band between the chrome and 86 % of the width
    /// (`DemoMath.VIEWER_FILL` / `VIEWER_HORIZONTAL_FILL`). The SceneView fit
    /// sizes a sphere round the box instead, and at 1.12 drew every model
    /// ~1.23x smaller than Android (Helmet 1.25, Toy Car 1.19, Mammoth 1.35,
    /// Perseverance 1.17, Apollo 1.19, measured on the silhouettes of
    /// side-by-side captures). 1.12 / 1.23 ≈ 0.91.
    ///
    /// Below 1 the sphere no longer fits the frame, so a long model can run
    /// past the screen edges once orbited side-on. Android clips the same
    /// way: it fits the front view only.
    private static let framingMargin: Float = 0.91
    /// Under `qa_mode` the pose is frozen, so the store capture fills the frame.
    ///
    /// Tighter than `DynamicSkyDemo`'s 0.75 because the subjects differ in
    /// aspect, not in preference: the auto-fit pass inscribes the *space
    /// diagonal* of the union bounds in a sphere and fits that sphere to the
    /// narrower of the two FOV axes — width, in a portrait store frame. A
    /// near-isotropic subject (the helmet) fills that sphere; the hovercar is
    /// wide and short, so the same margin leaves it visibly smaller.
    ///
    /// 0.62 is the floor, not a preference: swept on the 6.9" simulator with
    /// the `-camera_distance` override (#2785), 0.75 leaves the car at roughly
    /// 45 % of the frame width and 0.5 clips its tail against the right edge.
    /// It cannot go lower while the car renders off-centre — the union bounds
    /// this pass fits are visibly wider than the car's silhouette, so the car
    /// sits right of the frame centre and runs out of room on that side long
    /// before the empty left third is used. Closing that gap is a
    /// `CameraControls.fitRadius` change (fit the projected AABB rather than
    /// the space-diagonal sphere), not a constant, and is out of scope here.
    private static let captureFramingMargin: Float = 0.62

    private enum ViewerSheet: Identifiable {
        case models, environment
        var id: Self { self }
    }

    @State private var selectedModel: BundledViewerModel = ModelViewerDemo.bundledModels[0]
    @State private var loadedNode: ModelNode?
    @State private var loadError: String?
    @State private var loadCount = 0
    @State private var recenterGeneration = 0
    @State private var sheet: ViewerSheet?
    @State private var showExplore = false
    @State private var showAR = false

    /// Which HDR lights the stage, and whether the app or the user chose it —
    /// see ``ViewerLighting``. Changed only through ``relight(_:)``.
    @State private var lighting = ViewerLighting()
    private var environment: ViewerEnvironment { lighting.environment }
    @State private var iblIntensity: Float = 1
    @State private var showSkybox = ModelViewerDemo.defaultEnvironment.authoredAsPlace

    /// Raw storage for ``SkyboxPreference`` — `@AppStorage` cannot hold the enum directly.
    @AppStorage("viewer_skybox_preference") private var skyboxPreferenceRaw: Int = SkyboxPreference.auto.rawValue

    @State private var animationNames: [String] = []
    @State private var animationBarOpen = false
    @State private var selectedAnimation = 0
    @State private var animationPlaying = true
    @State private var animationProgress: Float = 0
    @State private var playback: AnimationPlaybackController?

    @State private var surpriseInFlight = false
    @State private var surpriseError: String?
    @State private var streamedDisplayName: String?
    /// Sketchfab uid of the streamed model on stage, so a roll never repeats it.
    @State private var streamedUid: String?

    private let hasSketchfabKey: Bool = SketchfabConfig.apiKey != nil

    /// HD pack: the stage shows a bundled stand-in while the selected model's
    /// HD file is not on disk; ``pendingHDID`` names that file, and the stage
    /// swaps to it the moment the store reports it ready.
    @ObservedObject private var hdPack = HDPackStore.shared
    @State private var pendingHDID: String?
    /// The HD file is on disk and being loaded onto the stage
    /// ("Flight Helmet · loading"), until its first frame is drawn.
    @State private var hdLoading = false
    @State private var hdFrameWatch = HDFirstFrameWatch()
    /// Which load may still put its model on stage: see ``StageRequests``.
    @State private var stageRequests = StageRequests()
    /// The pill's "download 52 MB" / "download failed" tap: size-first dialog.
    @State private var confirmHD = false
    /// An HD-only model (Museum & Space) picked before its file is on disk:
    /// the stage shows its thumbnail instead of a 3D stand-in.
    @State private var posterModel: BundledViewerModel?

    /// `-qa_mode 1` / `?qa_mode=1` — keeps the authored pose for captures.
    @AppStorage(DeepLinkRouter.qaModeDefaultsKey) private var qaMode: Bool = false

    private var arSupported: Bool {
        #if os(iOS) && !targetEnvironment(simulator)
        return ARWorldTrackingConfiguration.isSupported
        #else
        return false
        #endif
    }

    /// The backdrop state a given environment should land on, honouring a
    /// remembered explicit choice over the per-environment default.
    private func defaultSkybox(for environment: ViewerEnvironment) -> Bool {
        switch SkyboxPreference(rawValue: skyboxPreferenceRaw) ?? .auto {
        case .alwaysOn: return true
        case .alwaysOff: return false
        case .auto: return environment.authoredAsPlace
        }
    }

    /// The sheet's "Show environment" switch. Reading is plain state; *writing* is
    /// the user speaking, so it also promotes the preference out of `auto`.
    private var skyboxBinding: Binding<Bool> {
        Binding(
            get: { showSkybox },
            set: { newValue in
                showSkybox = newValue
                skyboxPreferenceRaw = (newValue ? SkyboxPreference.alwaysOn
                                                : SkyboxPreference.alwaysOff).rawValue
            }
        )
    }

    private var sceneEnvironment: SceneEnvironment {
        SceneEnvironment.custom(
            name: environment.displayName,
            hdrFile: "\(environment.assetName).hdr",
            intensity: iblIntensity,
            showSkybox: showSkybox
        )
    }

    /// Android's order (#3402): *what* am I looking at (Models), *how* is it
    /// lit (Lighting), play it (Animate, only with clips), then put the
    /// camera back (Recenter). The scaffold appends Settings and the AR
    /// accent. The dock is this demo's own array — no other demo shares it.
    private var dock: [DockItem] {
        var items = [
            DockItem(icon: "cube.transparent", label: "Models") { sheet = .models },
            DockItem(icon: "sun.max", label: "Environment", caption: "Lighting") { sheet = .environment },
        ]
        if !animationNames.isEmpty {
            items.append(DockItem(icon: "play.circle", label: "Animate", selected: animationBarOpen) {
                withAnimation(SceneViewTokens.Spring.animation) { animationBarOpen.toggle() }
            })
        }
        items.append(DockItem(icon: "scope", label: "Recenter") { recenterGeneration += 1 })
        return items
    }

    /// Applies one ``ViewerLighting`` step; a new environment brings its own
    /// backdrop default (or the remembered explicit choice).
    private func relight(_ change: (inout ViewerLighting) -> Void) {
        let before = lighting.environment
        change(&lighting)
        if lighting.environment != before { showSkybox = defaultSkybox(for: lighting.environment) }
    }

    /// Raw `-camera_distance <float>` launch-arg override, written by
    /// `SceneViewDemoApp` into `UserDefaults` (#2785). `0` is the "unset"
    /// sentinel — see `DeepLinkRouter.cameraDistanceDefaultsKey`.
    @AppStorage(DeepLinkRouter.cameraDistanceDefaultsKey) private var cameraDistanceRaw: Double = 0

    /// Validated `-camera_distance` override, or `nil` when absent — mirrors
    /// Android's nullable `DemoSettings.cameraDistance`. Threaded into
    /// ``sceneView``'s `.framingMargin(_:)` in place of the demo's own
    /// interactive / `qa_mode` defaults, the same "explicit override wins
    /// over auto-fit" precedence Android's `rememberHeroOrbitCameraManipulator`
    /// applies to `radius` (`DemoHelpers.kt`, #1571). Note `.framingMargin(_:)`
    /// itself clamps to `0.2...10` (`SceneView.swift`) — narrower than
    /// `DeepLinkRouter`'s accepted `0.05...100`, since it is a fit-margin
    /// multiplier, not the absolute-metre distance Android's `radius` is;
    /// callers targeting a store-tight frame want small values (< 1) anyway.
    private var cameraDistanceOverride: Float? {
        cameraDistanceRaw > 0 ? Float(cameraDistanceRaw) : nil
    }

    /// Pills that float above the dock: the error banner, the streamed
    /// model's name, Surprise me and the animation bar.
    ///
    /// They ride the scaffold's `accessory` slot, part of the chrome. Drawn
    /// as part of the stage they sat under the bottom scrim that keeps the
    /// chrome legible, which greyed the white "Surprise me" label to about
    /// 47 % and made the idle pill look disabled (#4013). In the chrome they
    /// stand on the same dark glass as the dock, above the scrim.
    @ViewBuilder
    private var floatingBand: some View {
        VStack(spacing: 0) {
            if let surpriseError {
                errorBanner(surpriseError)
                    .padding(.bottom, SceneViewTokens.Space.sm)
            }
            if let name = streamedDisplayName {
                GlassPill {
                    Text("Streamed: \(name)")
                        .font(SceneViewTokens.TypeScale.caption)
                        .foregroundStyle(SceneViewTokens.Glass.onGlass)
                        .lineLimit(1)
                }
                .padding(.bottom, SceneViewTokens.Space.sm)
            }
            if hasSketchfabKey {
                // Re-roll without opening the sheet — the "switcher sans se prendre
                // la tête" half of #3585. Animate already occupies dock item four
                // and AR owns the accent, so this rides the floating band instead.
                // Shown in both themes: the viewer chrome is glass over live 3D and
                // is theme-independent by design.
                Button {
                    Task { @MainActor in
                        guard !surpriseInFlight else { return }
                        await rollSurpriseModel()
                    }
                } label: {
                    GlassPill {
                        Group {
                            SurpriseShuffleIcon(loading: surpriseInFlight)
                            Text("Surprise me")
                                .font(SceneViewTokens.TypeScale.captionSemibold)
                                .lineLimit(1)
                        }
                        // Dimming is reserved for the in-flight roll.
                        .foregroundStyle(surpriseInFlight ? SceneViewTokens.Glass.onGlassMuted
                                                          : SceneViewTokens.Glass.onGlass)
                    }
                    .tint(SceneViewTokens.Glass.onGlass)
                    .frame(minHeight: SceneViewTokens.Layout.touchTarget)
                    .contentShape(Capsule())
                }
                .buttonStyle(PressScaleButtonStyle(scale: SceneViewTokens.Spring.chromePressScale))
                .disabled(surpriseInFlight)
                .accessibilityLabel("Surprise me")
                .accessibilityValue(surpriseInFlight ? "Loading" : "Ready")
                .accessibilityHint("Loads another random CC-BY model without opening Models")
                .accessibilityIdentifier("viewer-surprise")
                .padding(.bottom, SceneViewTokens.Space.sm)
            }
            if animationBarOpen && !animationNames.isEmpty {
                AnimationBar(
                    clipNames: animationNames,
                    selectedClip: $selectedAnimation,
                    playing: $animationPlaying,
                    progress: $animationProgress,
                    onScrub: scrub
                )
                // The accessory slot already insets the band to the chrome margin.
                .transition(.opacity)
            }
        }
    }

    var body: some View {
        ZStack {
            SceneViewTokens.Stage.background.ignoresSafeArea()
            sceneView
        }
        .demoChrome(
            title: "Model Viewer",
            dock: dock,
            accent: DockItem(icon: "arkit", label: "View in AR",
                             enabled: arSupported && selectedModel.arResourceName != nil) { showAR = true },
            onReset: resetAll,
            accessory: { floatingBand },
            status: {
                if let pendingHDID, let hdAsset = hdPack.manifest.asset(id: pendingHDID) {
                    HDPackPill(
                        title: hdAsset.title,
                        state: hdPack.state(for: pendingHDID),
                        loading: hdLoading,
                        bytes: hdAsset.bytes,
                        hdOnly: selectedModel.isHDOnly,
                        onDownload: { confirmHD = true }
                    )
                }
            }
        )
        .hdPackDownloadDialog(isPresented: $confirmHD, assetID: pendingHDID)
        .sheet(item: $sheet) { which in
            Group {
                switch which {
                case .models:
                    ModelPickerSheet(
                        models: Self.bundledModels,
                        museum: Self.museumModels,
                        selected: selectedModel,
                        surpriseAvailable: hasSketchfabKey,
                        surpriseLoading: surpriseInFlight,
                        onSelect: { model in
                            sheet = nil
                            selectedModel = model
                            relight { $0.select(model) }
                            Task { await loadBundled(model) }
                        },
                        onSurprise: {
                            sheet = nil
                            Task { await rollSurpriseModel() }
                        },
                        onBrowse: {
                            sheet = nil
                            showExplore = true
                        }
                    )
                case .environment:
                    EnvironmentSheet(
                        environments: Self.environments,
                        selected: environment,
                        intensity: $iblIntensity,
                        showSkybox: skyboxBinding,
                        onSelect: { picked in
                            lighting.pick(picked)
                            showSkybox = defaultSkybox(for: picked)
                        },
                        onReset: {
                            // Reset also forgets the remembered choice, back to auto,
                            // and returns to the lighting the model on stage opens
                            // under — a Surprise model (streamed) gets the garden.
                            skyboxPreferenceRaw = SkyboxPreference.auto.rawValue
                            lighting.reset(for: streamedUid == nil ? selectedModel : nil)
                            iblIntensity = 1
                            showSkybox = environment.authoredAsPlace
                        }
                    )
                }
            }
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
            #if os(iOS)
            .presentationBackgroundInteraction(.enabled(upThrough: .medium))
            .presentationBackground(.regularMaterial)
            .presentationCornerRadius(SceneViewTokens.Radius.xl)
            #endif
        }
        .sheet(isPresented: $showExplore) {
            ExploreTab()
        }
        #if os(iOS)
        .fullScreenCover(isPresented: $showAR) {
            NavigationStack {
                ARExperienceContainer(onViewIn3D: { showAR = false }) {
                    ARPlacementDemo(initialModel: selectedModel.arResourceName)
                }
                    .navigationTitle("AR Placement")
                    .navigationBarTitleInline()
            }
            .environment(\.demoTitle, "AR Placement")
        }
        #endif
        .task {
            // Under `qa_mode` the store hero and its stage replace the
            // first-run defaults, so slot 1 captures the hovercar instead of
            // repeating slot 2's helmet (#3006), lit against a drawn backdrop
            // instead of the clear colour (#2896). Assigned before the load so
            // a single pass runs.
            if qaMode {
                if let hero = Self.bundledModels.first(where: { $0.assetName == Self.storeHeroAssetName }) {
                    selectedModel = hero
                }
                if let stage = Self.environments.first(where: { $0.assetName == Self.storeHeroEnvironmentName }) {
                    // Set as the user would pick it, so switching models
                    // (museum or not) never swaps the store stage out.
                    lighting.pick(stage)
                    showSkybox = true
                }
            } else {
                // Honour a remembered explicit choice from a previous launch;
                // otherwise fall back to the picked environment's own default.
                showSkybox = defaultSkybox(for: environment)
            }
            #if DEBUG
            if let picked = Self.launchArgModel { selectedModel = picked }
            #endif
            // A museum model opened directly (deep link, QA launch arg) gets its
            // Studio lighting too; the first-run model keeps the store/garden stage.
            relight { $0.select(selectedModel) }
            await loadBundled(selectedModel)
        }
        .onChange(of: hdPack.states) { _, _ in
            // The HD file of the model on stage just landed: swap the stand-in out.
            guard let id = pendingHDID, hdPack.state(for: id) == .ready, !hdLoading,
                  selectedModel.hdPackID == id else { return }
            Task { await loadBundled(selectedModel) }
        }
        .onChange(of: selectedAnimation) { _, index in
            play(clip: index)
        }
        .onChange(of: animationPlaying) { _, playing in
            if playing { playback?.resume() } else { playback?.pause() }
        }
    }

    @ViewBuilder
    private var sceneView: some View {
        ZStack {
            // Mounted once and never re-keyed with `.id(_:)` — see #3008.
            // `.contentID(_:)` swaps the model inside the live scene and re-arms
            // the fit-to-bounds pass. "Recenter" goes through
            // `.recenterCamera(_:)` instead of being folded into this id: a
            // contentID change rebuilds the model, which restarted the playing
            // animation and made the button look like it did something random
            // rather than recentring (#3595).
            SceneView { root in
                guard let loadedNode else { return }
                root.addChild(loadedNode.entity)
                hdFrameWatch.joined(loadedNode.entity)
            }
            .cameraControls(.orbit)
            .cameraOrbit(azimuth: Self.openingAzimuth, elevation: Self.openingElevation)
            .environment(sceneEnvironment)
            // `cameraDistanceOverride` — the `-camera_distance <float>` launch
            // arg (#2785) — wins over both when present, same as Android's
            // `DemoSettings.cameraDistance` beating its own `radius` default.
            .framingMargin(cameraDistanceOverride ?? (qaMode ? Self.captureFramingMargin : Self.framingMargin))
            .contentID(loadedNode == nil ? nil : "\(loadCount)")
            .recenterCamera(recenterGeneration)
            .ignoresSafeArea()

            if loadedNode == nil, let posterModel, let thumb = posterModel.thumbnailName {
                // A render of the HD model itself, so the stage already shows
                // what the download brings; the pill in the header says how far
                // it is. The picker card's transparent render, stage-wide at
                // 5:4 like Android's poster, capped on iPad (`posterMaxWidth`).
                Image(thumb)
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: SceneViewTokens.Stage.posterMaxWidth)
                    .aspectRatio(SceneViewTokens.Layout.mediaAspect, contentMode: .fit)
                    .padding(.horizontal, SceneViewTokens.Space.lg)
                    .accessibilityLabel("\(posterModel.displayName), preview")
                    .accessibilityIdentifier("viewer-hd-poster")
                    .transition(.opacity)
            } else if loadedNode == nil {
                VStack(spacing: SceneViewTokens.Space.sm + 4) {
                    ProgressView().tint(.white)
                    if let loadError {
                        Text(loadError)
                            .font(SceneViewTokens.TypeScale.captionRegular)
                            .foregroundStyle(SceneViewTokens.Glass.onGlassMuted)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, SceneViewTokens.Space.xl)
                    }
                }
            }
        }
    }

    private func errorBanner(_ message: String) -> some View {
        Text(message)
            .font(SceneViewTokens.TypeScale.captionRegular)
            .foregroundStyle(SceneViewTokens.Glass.onGlass)
            .lineLimit(2)
            .multilineTextAlignment(.center)
            .padding(.horizontal, SceneViewTokens.Space.md)
            .padding(.vertical, SceneViewTokens.Space.sm)
            .glassBackground(in: Capsule())
            .padding(.horizontal, SceneViewTokens.Space.lg)
    }

    // MARK: - Loading

    @MainActor
    private func loadBundled(_ model: BundledViewerModel) async {
        loadError = nil
        streamedDisplayName = nil
        streamedUid = nil
        #if DEBUG
        let started = Date()
        #endif
        hdFrameWatch.cancel()
        let ticket = stageRequests.begin()
        // A 38 MB scan can take seconds to load: if the user picked another
        // model meanwhile, this one must not land over it.
        func isCurrent() -> Bool { stageRequests.isCurrent(ticket) && selectedModel == model }
        do {
            if let id = model.hdPackID, let url = hdPack.localURL(for: id) {
                pendingHDID = id
                hdLoading = true
                let node: ModelNode
                do {
                    node = try await ModelNode.load(contentsOf: url)
                } catch {
                    if isCurrent() { hdLoading = false }
                    throw error
                }
                guard isCurrent() else { return }
                // The pill stays on "loading" until the HD entity is drawn,
                // not when the load call returns (see HDFirstFrameWatch).
                let entity = node.entity
                hdFrameWatch.expect(entity) {
                    hdLoading = false
                    pendingHDID = nil
                    #if DEBUG
                    print(String(format: "[ModelViewer] first HD frame of %@ after %.0f ms, footprint %.0f MB",
                                 model.assetName, Date().timeIntervalSince(started) * 1000,
                                 Double(MemoryFootprint.current()) / 1_048_576))
                    #endif
                }
                install(node, as: model, hd: true)
                Task { @MainActor in
                    try? await Task.sleep(for: .seconds(15))
                    if hdFrameWatch.isWaiting(for: entity) { hdFrameWatch.finish() }
                }
            } else if let resource = model.bundledResourceName {
                hdLoading = false
                let node = try await ModelNode.load(resource)
                guard isCurrent() else { return }
                install(node, as: model, hd: false)
                pendingHDID = model.hdPackID
            } else {
                // HD-only and not on disk yet: its thumbnail holds the stage
                // with the pill until the file lands (`onChange(of: states)`).
                hdLoading = false
                clearStage(poster: model)
                pendingHDID = model.hdPackID
            }
            #if DEBUG
            // Guardrail for the HD pack: load time and footprint per model.
            print(String(format: "[ModelViewer] loaded %@%@ in %.0f ms, footprint %.0f MB",
                         model.assetName, model.hdPackID != nil && !hdLoading ? " (stand-in)" : "",
                         Date().timeIntervalSince(started) * 1000,
                         Double(MemoryFootprint.current()) / 1_048_576))
            #endif
        } catch {
            guard isCurrent() else { return }
            // The poster would hide the message; the spinner overlay shows it.
            posterModel = nil
            loadError = "Could not load \(model.displayName): \(error.localizedDescription)"
        }
    }

    /// Puts a freshly loaded node on stage: normalised to 0.6 units and
    /// centred (auto-fit framing then adapts the orbit radius), animation
    /// state rebuilt from the entity's clips.
    ///
    /// An HD file whose manifest entry carries a real-world `scale` keeps its
    /// true size instead: the capsule is 3.9 m across, the mammoth 5 m long,
    /// and the framing fits the camera to that.
    @MainActor
    private func install(_ node: ModelNode, as model: BundledViewerModel? = nil, hd: Bool = false) {
        if hd, let id = model?.hdPackID, let scale = HDPackStore.shared.manifest.asset(id: id)?.scale {
            node.entity.scale = SIMD3(repeating: scale)
        } else {
            _ = node.scaleToUnits(0.6)
        }
        // Turned before centring: `centerOrigin` reads the world bounds, so
        // the turned box is what lands on the origin and what the fit frames,
        // as Android frames the turned AABB. A streamed file keeps its pose.
        // Composed with the file's own root rotation, never written over it:
        // a USDZ whose root prim carries a turn keeps it.
        if let yaw = model?.frontYaw, yaw != 0 {
            node.entity.orientation = simd_quatf(angle: yaw * .pi / 180, axis: [0, 1, 0])
                * node.entity.orientation
        }
        _ = node.centerOrigin()
        playback = nil
        posterModel = nil
        loadedNode = node
        loadCount += 1
        animationNames = node.entity.availableAnimations.enumerated().map { index, clip in
            clip.name ?? "Clip \(index + 1)"
        }
        selectedAnimation = 0
        animationProgress = 0
        animationPlaying = !qaMode && (model?.autoplaysAnimations ?? true)
        if !animationNames.isEmpty {
            play(clip: 0)
        } else {
            animationBarOpen = false
        }
    }

    /// Empties the stage for an HD-only model whose file is not on disk:
    /// ``posterModel``'s thumbnail stands in until the HD model is installed.
    @MainActor
    private func clearStage(poster model: BundledViewerModel) {
        loadedNode?.entity.stopAllAnimations()
        playback = nil
        loadedNode = nil
        posterModel = model
        animationNames = []
        animationBarOpen = false
    }

    private func resetAll() {
        recenterGeneration += 1
        skyboxPreferenceRaw = SkyboxPreference.auto.rawValue
        lighting.resetAll()
        iblIntensity = 1
        showSkybox = Self.defaultEnvironment.authoredAsPlace
        selectedModel = Self.bundledModels[0]
        Task { await loadBundled(selectedModel) }
    }

    // MARK: - Animation

    @MainActor
    private func play(clip index: Int) {
        guard let entity = loadedNode?.entity, entity.availableAnimations.indices.contains(index) else { return }
        entity.stopAllAnimations()
        let controller = entity.playAnimation(entity.availableAnimations[index].repeat(), transitionDuration: 0.2)
        if !animationPlaying { controller.pause() }
        playback = controller
        animationProgress = 0
    }

    @MainActor
    private func scrub(_ value: Float) {
        animationProgress = value
        guard let playback else { return }
        playback.time = Double(value) * playback.duration
    }

    // MARK: - Surprise me

    /// Loads a random CC-BY model that renders as one coherent object.
    ///
    /// Candidates come from Sketchfab's curated feeds (Staff Picks and the
    /// most-liked downloadable models) before the free-text search. Sketchfab
    /// converts every upload to USDZ itself, and some conversions come out
    /// broken: "PBR Firefighter Helmet" landed as a tiny helmet in a cloud of
    /// scattered red shards (#4012). Each download is checked with
    /// ``SurpriseModelCheck`` before it goes on stage; a candidate that fails
    /// to download, load or pass the check is skipped silently and the next
    /// one is tried, up to ``surpriseAttempts``.
    @MainActor
    private func rollSurpriseModel() async {
        surpriseInFlight = true
        surpriseError = nil
        defer { surpriseInFlight = false }

        let candidates: [SketchfabModel]
        do {
            candidates = try await surpriseCandidates()
        } catch {
            surfaceTransientError("Couldn't roll a model: \(error.localizedDescription)")
            return
        }
        for pick in candidates.prefix(Self.surpriseAttempts) {
            guard
                let downloaded = try? await SketchfabService.shared.downloadModel(uid: pick.uid),
                let node = try? await ModelNode.load(contentsOf: downloaded),
                SurpriseModelCheck.isCoherent(node.entity)
            else { continue }
            // The surprise wins over a bundled load still in flight.
            _ = stageRequests.begin()
            hdFrameWatch.cancel()
            hdLoading = false
            install(node)
            pendingHDID = nil
            streamedUid = pick.uid
            streamedDisplayName = pick.name
            return
        }
        surfaceTransientError("No surprise model available right now — try again.")
    }

    /// How many candidates one roll may download before giving up.
    private static let surpriseAttempts = 4

    /// Shuffled viable candidates: curated feeds first, the search as fallback.
    /// The model on stage is never re-rolled.
    @MainActor
    private func surpriseCandidates() async throws -> [SketchfabModel] {
        func viable(_ models: [SketchfabModel]) -> [SketchfabModel] {
            models.filter {
                $0.downloadable && (1..<200_000).contains($0.faceCount) && $0.uid != streamedUid
            }
        }
        let service = SketchfabService.shared
        var curated: [SketchfabModel] = []
        if let picks = try? await service.staffPicks(limit: 24) { curated += picks }
        if let liked = try? await service.featured(limit: 24) { curated += liked }
        var seen = Set<String>()
        let pool = viable(curated).filter { seen.insert($0.uid).inserted }
        if !pool.isEmpty { return pool.shuffled() }
        for query in ["pbr", "modern", "scan"] {
            let hits = viable(try await service.search(query: query, downloadable: true, limit: 24))
            if !hits.isEmpty { return hits.shuffled() }
        }
        return []
    }

    @MainActor
    private func surfaceTransientError(_ message: String) {
        surpriseError = message
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            if surpriseError == message {
                surpriseError = nil
            }
        }
    }
}

/// Tells a coherent model from a broken USDZ conversion before it goes on
/// stage (#4012).
///
/// The broken shape seen in the wild is a small subject inside a cloud of
/// scattered fragments: many parts, none of them large, most of them touching
/// nothing. A model is accepted when its largest part spans at least 30 % of
/// the whole, or when most of its parts touch another part (a building made of
/// planks, a figure made of pieces). A lone mesh always passes.
enum SurpriseModelCheck {

    /// Largest-part share of the union diagonal that is enough on its own.
    static let dominantPartShare: Float = 0.3
    /// Share of isolated parts above which a model reads as scattered.
    static let maxIsolatedShare: Float = 0.5
    /// Parts compared pairwise at most, to bound the O(n²) contact pass.
    static let maxPartsCompared = 400

    @MainActor
    static func isCoherent(_ root: Entity) -> Bool {
        isCoherent(parts: partBounds(of: root))
    }

    /// World-space (root-relative) bounds of every mesh part under `root`.
    @MainActor
    static func partBounds(of root: Entity) -> [BoundingBox] {
        var boxes: [BoundingBox] = []
        var stack: [Entity] = [root]
        while let entity = stack.popLast() {
            stack.append(contentsOf: entity.children)
            guard let model = entity.components[ModelComponent.self] else { continue }
            let local = model.mesh.bounds
            let transform = entity.transformMatrix(relativeTo: root)
            var box = BoundingBox()
            var first = true
            for x in [local.min.x, local.max.x] {
                for y in [local.min.y, local.max.y] {
                    for z in [local.min.z, local.max.z] {
                        let p = transform * SIMD4<Float>(x, y, z, 1)
                        let point = SIMD3<Float>(p.x, p.y, p.z)
                        if first {
                            box = BoundingBox(min: point, max: point)
                            first = false
                        } else {
                            box = box.union(point)
                        }
                    }
                }
            }
            boxes.append(box)
        }
        return boxes
    }

    static func isCoherent(parts: [BoundingBox]) -> Bool {
        guard let first = parts.first else { return false }
        let union = parts.dropFirst().reduce(first) { $0.union($1) }
        let diagonal = simd_length(union.extents)
        guard diagonal.isFinite, diagonal > 0 else { return false }
        let largest = parts.map { simd_length($0.extents) }.max() ?? 0
        if largest >= dominantPartShare * diagonal { return true }

        let compared = Array(parts.prefix(maxPartsCompared))
        let slack = SIMD3<Float>(repeating: diagonal * 0.02)
        let grown = compared.map { BoundingBox(min: $0.min - slack, max: $0.max + slack) }
        var isolated = 0
        for i in grown.indices {
            let touches = grown.indices.contains { j in
                j != i && overlaps(grown[i], grown[j])
            }
            if !touches { isolated += 1 }
        }
        return Float(isolated) / Float(grown.count) <= maxIsolatedShare
    }

    private static func overlaps(_ a: BoundingBox, _ b: BoundingBox) -> Bool {
        all(a.min .<= b.max) && all(b.min .<= a.max)
    }
}

// MARK: - HD first frame

/// Holds the HD pill on "loading" until RealityKit has actually drawn the HD
/// entity. `ModelNode.load` returns before the renderer has uploaded the
/// textures; on the simulator the stand-in frame stayed on screen 3–5 s after
/// the pill had already cleared, which read as "nothing is happening".
///
/// The first scene update after the entity joins the scene precedes the frame
/// that uploads its resources; the second one comes after that frame, so it
/// marks the swap as visible. A timeout in the caller covers a scene that
/// stops ticking.
/// Last request wins on stage. Every load that ends in an install takes a
/// ticket first; after its `await` it installs only if no newer load took
/// one since. Picking the 38 MB Apollo interior and then the mammoth used to
/// let the interior land over the mammoth when its load finished last.
struct StageRequests: Equatable {
    private(set) var latest = 0

    /// A fresh ticket; every ticket issued before it is now stale.
    mutating func begin() -> Int {
        latest += 1
        return latest
    }

    func isCurrent(_ ticket: Int) -> Bool { ticket == latest }
}

@MainActor
final class HDFirstFrameWatch {
    private weak var expected: Entity?
    private var onDrawn: (@MainActor () -> Void)?
    private var stopUpdates: (() -> Void)?
    private var updates = 0

    /// Waits for `entity`; nothing happens until it joins the scene.
    func expect(_ entity: Entity, onDrawn: @escaping @MainActor () -> Void) {
        cancel()
        expected = entity
        self.onDrawn = onDrawn
    }

    /// Called from the scene content closure with every entity it adds.
    func joined(_ entity: Entity) {
        guard entity === expected, stopUpdates == nil else { return }
        guard let scene = entity.scene else { finish(); return }
        updates = 0
        let subscription = scene.subscribe(to: SceneEvents.Update.self) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        }
        stopUpdates = { subscription.cancel() }
    }

    /// Whether the watch still waits for `entity` (used by the timeout).
    func isWaiting(for entity: Entity) -> Bool { expected === entity }

    func finish() {
        let done = onDrawn
        cancel()
        done?()
    }

    func cancel() {
        stopUpdates?()
        stopUpdates = nil
        expected = nil
        onDrawn = nil
    }

    private func tick() {
        updates += 1
        if updates >= 2 { finish() }
    }
}

/// The Model Viewer's lighting as a value, so its sequences are unit-tested
/// (`ViewerLightingTests`) rather than living in view `@State`.
///
/// Same rule as Android's `LaunchedEffect(isMuseumModel)` (#4166): a Museum &
/// Space model swaps the lighting to **Studio** only while it is still the
/// default Chinese Garden, and leaving the shelf gives the garden back only if
/// Studio was put there by the app. A lighting the user picks — or the
/// `qa_mode` store stage — is never overridden.
@MainActor
struct ViewerLighting {
    private(set) var environment: ViewerEnvironment
    /// `true` while Studio is on stage because the app put it there.
    private(set) var museumApplied = false

    init(environment: ViewerEnvironment = ModelViewerDemo.defaultEnvironment) {
        self.environment = environment
    }

    /// A model goes on stage. `nil` is a streamed (Surprise me) model, which is
    /// never a museum scan.
    mutating func select(_ model: BundledViewerModel?) {
        let isMuseum = model.map { ModelViewerDemo.museumModels.contains($0) } ?? false
        if isMuseum, let model, environment == ModelViewerDemo.defaultEnvironment {
            environment = ModelViewerDemo.openingEnvironment(for: model)
            museumApplied = true
        } else if !isMuseum, museumApplied {
            environment = ModelViewerDemo.defaultEnvironment
            museumApplied = false
        }
    }

    /// The user picks a lighting in the sheet: it sticks across models.
    mutating func pick(_ picked: ViewerEnvironment) {
        environment = picked
        museumApplied = false
    }

    /// "Reset lighting" in the sheet: back to the lighting `model` opens under
    /// (Studio for a museum scan, the garden otherwise; `nil`, a streamed
    /// model, gets the garden).
    ///
    /// Reset lands on the same state as reopening the model, so a museum scan
    /// never stays under a lighting the app does not open it with. Android's
    /// `ViewerLighting.reset` applies the same rule.
    mutating func reset(for model: BundledViewerModel?) {
        environment = ModelViewerDemo.defaultEnvironment
        museumApplied = false
        select(model)
    }

    /// The demo's global reset: first-run lighting.
    mutating func resetAll() {
        self = ViewerLighting()
    }
}
