import SwiftUI
import RealityKit
import SceneViewSwift

/// The home screen's single focal point (`DESIGN.md` "Demo App Home", Hero):
/// a full-span card for the Model Viewer. `radius-xl` clip, a vertical scrim
/// from transparent at 50 % to `stage-scrim-end`, and bottom-left copy —
/// `type-display` title, `type-body` subtitle at 80 % white, one 44 pt "Open"
/// pill. The whole card is one button. Light: soft shadow; dark: 1 pt
/// `outline` over `surface-container`. The iOS twin of Android's `HomeHero.kt`.
///
/// **The hero is alive.** `preview_hero_model_viewer` is the poster frame; once
/// the bundled helmet has loaded, a real `SceneView` (RealityKit) crossfades
/// over it and turntables slowly. It is the same subject the Model Viewer opens
/// on, so the card shows what the tap gives — the picture is the demo, running.
/// The still image stays underneath, which is what the card falls back to
/// while the model loads, when `live` is false, and under
/// `accessibilityReduceMotion`-independent teardown.
///
/// **One 3D render at a time.** The stage is mounted only while `live` — the
/// Showcase tab is the visible tab, nothing is presented over it, and the app
/// is in the foreground. Opening a demo tears it down (after the zoom
/// transition has landed, so the card the user tapped does not go blank
/// mid-morph) and releases the loaded entity, so the demo owns the GPU alone.
///
/// The hero is dark in both themes by design — it is the one accent on a
/// white page in light mode, which is why its text colours are fixed tokens
/// (`SceneViewTokens.HomeColor`) rather than system roles.
struct HomeHero: View {
    let height: CGFloat
    /// Whether the live 3D stage may run. False tears it down — see the type doc.
    var live: Bool = false
    let onTap: () -> Void

    /// The bundled subject on the hero stage: the Model Viewer's own first-run
    /// model, and a helmet like the poster frame it fades out of. Guarded by
    /// `ViewerAssetTests.testHomeHeroModelShipsItsUSDZ`.
    static let heroAssetName = "khronos_damaged_helmet"

    /// Neutral studio IBL with no backdrop: the model floats on the
    /// `stage-background` field the poster frame already uses, so the crossfade
    /// changes the subject's motion and nothing else.
    private static let heroEnvironment = SceneEnvironment.custom(
        name: "Studio",
        hdrFile: "studio.hdr",
        intensity: 1,
        showSkybox: false
    )

    /// Bounding sphere plus 10 % of air — above the 0.95 floor an auto-rotating
    /// scene needs, tight enough that the subject reads as the card's subject
    /// and not as a trinket dropped on a dark field. The room it needs is taken
    /// in layout, not in the framing: see ``stageInset``.
    private static let heroFramingMargin: Float = 1.1

    /// Share of the card height given back to the copy block. The stage is
    /// inset from the leading and bottom edges rather than the subject being
    /// pushed around inside a full-bleed viewport, so the helmet is framed in
    /// the free upper-right corner and never turns into the title.
    private static let stageInset: CGFloat = 0.26

    /// How long the stage outlives a `live` drop. The iOS 18 zoom transition
    /// morphs this very card into the demo; disposing the RealityKit view on
    /// the same frame would blank the thing being morphed.
    private static let teardownGrace: Duration = .milliseconds(500)

    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// The loaded subject, `nil` whenever the stage is torn down.
    @State private var model: ModelNode?
    /// Mount gate for the `SceneView` itself, kept separate from `live` so the
    /// teardown can lag behind by ``teardownGrace``.
    @State private var stageMounted = false

    private var stageVisible: Bool { stageMounted && model != nil }

    var body: some View {
        Button(action: onTap) {
            ZStack(alignment: .bottomLeading) {
                // Dark shares the cards' elevated ground; the light stage stays intact.
                (colorScheme == .dark ? SceneViewTokens.HomeColor.surfaceContainer
                                      : SceneViewTokens.HomeColor.heroField)
                liveStage
                // Hosted in an overlay so the fill-scaled image never reports
                // its own ideal width to the ZStack (it would widen the whole
                // home scroll content past the screen).
                //
                // Drawn *over* the stage and faded out once the subject is on
                // it: the poster frame is what the card shows while the model
                // loads and whenever the stage is down, so the handover is one
                // crossfade instead of a dark hole opening under it.
                Color.clear
                    .frame(height: height)
                    .overlay {
                        Image("preview_hero_model_viewer")
                            .resizable()
                            .scaledToFill()
                    }
                    .clipped()
                    .opacity(stageVisible ? 0 : 1)
                    .animation(SceneViewTokens.Motion.expressive(SceneViewTokens.Motion.medium),
                               value: stageVisible)
                LinearGradient(
                    stops: [
                        .init(color: SceneViewTokens.SpatialGalleryColor.stageScrimStart,
                              location: SceneViewTokens.Home.heroScrimStart),
                        // Dark ends on the card ground, seating the text and image
                        // on the same elevation as the grid, without a shadow.
                        .init(color: colorScheme == .dark ? SceneViewTokens.HomeColor.surfaceContainer
                                                        : SceneViewTokens.SpatialGalleryColor.stageScrimEnd,
                              location: 1),
                    ],
                    startPoint: .top,
                    endPoint: .bottom
                )
                VStack(alignment: .leading, spacing: SceneViewTokens.Space.sm) {
                    Text("Model Viewer")
                        .font(SceneViewTokens.TypeScale.display)
                        .tracking(SceneViewTokens.TypeScale.displayTracking)
                        .foregroundStyle(SceneViewTokens.HomeColor.heroTitle)
                    Text("Any glTF, HDR lighting, one tap to AR")
                        .font(SceneViewTokens.TypeScale.body)
                        .foregroundStyle(SceneViewTokens.HomeColor.heroSubtitle)
                        .lineLimit(2)
                        .frame(maxWidth: SceneViewTokens.Home.heroSubtitleMaxWidth, alignment: .leading)
                    Text("Open")
                        .font(SceneViewTokens.TypeScale.bodySemibold)
                        .foregroundStyle(SceneViewTokens.HomeColor.heroPillText)
                        .padding(.horizontal, SceneViewTokens.Home.heroPillPaddingHorizontal)
                        .frame(height: SceneViewTokens.Home.heroPillHeight)
                        .background(SceneViewTokens.HomeColor.heroPillBackground, in: Capsule())
                        .padding(.top, SceneViewTokens.Space.sm)
                }
                .padding(SceneViewTokens.Home.heroPadding)
            }
            .frame(maxWidth: .infinity)
            .frame(height: height)
            .clipShape(RoundedRectangle(cornerRadius: SceneViewTokens.Radius.xl, style: .continuous))
            .overlay {
                if colorScheme == .dark {
                    RoundedRectangle(cornerRadius: SceneViewTokens.Radius.xl, style: .continuous)
                        // The inner spec hairline also contains the bright artwork edges.
                        .strokeBorder(SceneViewTokens.HomeColor.outline,
                                      lineWidth: SceneViewTokens.Home.cardOutlineWidth)
                }
            }
            .shadow(color: .black.opacity(colorScheme == .dark ? 0 : 0.12), radius: 12, y: 4)
        }
        .buttonStyle(PressScaleButtonStyle())
        .accessibilityLabel("Model Viewer. Any glTF, HDR lighting, one tap to AR. Open")
        .accessibilityIdentifier("home-hero")
        .task(id: live) { await syncStage() }
        .onDisappear {
            stageMounted = false
            model = nil
        }
    }

    /// The RealityKit stage, on the same dark field as the poster frame that
    /// covers it until the subject lands. Never mounted with a changing
    /// `.id(_:)` — that rebuild leaves the scene permanently blank on the iOS
    /// 26 Simulator (#3008 / #2935).
    ///
    /// The view is mounted *before* the model exists, and the subject arrives
    /// through the `contentID` swap once it has loaded. That order is not a
    /// style choice: RealityKit's `Entity(named:)` never resumes while no
    /// RealityKit view is in the hierarchy, so loading first and mounting after
    /// leaves the card stuck on its poster frame — measured on the iPhone 17
    /// Pro Max Simulator, iOS 26.3.
    @ViewBuilder
    private var liveStage: some View {
        ZStack {
            if stageMounted {
                SceneViewTokens.HomeColor.heroField
                SceneView { root in
                    guard let model else { return }
                    root.addChild(model.entity)
                }
                .cameraOrbit(azimuth: .pi / 5, elevation: .pi / 12)
                .framingMargin(Self.heroFramingMargin)
                // Reduce Motion stops the turntable and leaves a lit, still
                // subject — the render stays, only the movement goes.
                .autoRotate(speed: reduceMotion ? Float(0) : SceneViewTokens.Motion.heroOrbitSpeed)
                .environment(Self.heroEnvironment)
                .contentID(model == nil ? nil : Self.heroAssetName)
                // A poster does not take gestures: every touch belongs to the
                // button underneath, which is the whole card. Last in the
                // chain — the SceneView modifiers above return a `SceneView`,
                // this one returns a plain `View`.
                .allowsHitTesting(false)
                .padding(.leading, height * Self.stageInset)
                .padding(.bottom, height * Self.stageInset)
            }
        }
    }

    /// Loads the subject when the hero goes live, releases it when it stops —
    /// after ``teardownGrace``, so a demo opening through the zoom transition
    /// does not morph a card that has just gone blank.
    @MainActor
    private func syncStage() async {
        guard live else {
            try? await Task.sleep(for: Self.teardownGrace)
            guard !Task.isCancelled else { return }
            stageMounted = false
            model = nil
            return
        }
        // Mount first, load second — see ``liveStage``.
        stageMounted = true
        guard model == nil, let loaded = try? await ModelNode.load(Self.heroAssetName) else { return }
        guard !Task.isCancelled else { return }
        _ = loaded.scaleToUnits(0.6)
        _ = loaded.centerOrigin()
        model = loaded
    }
}

/// Press feedback on the one app spring: scale to 0.98 while pressed, no
/// highlight. Shared by the hero, the media cards and the chrome.
///
/// Under `accessibilityReduceMotion` the scale is dropped and the press reads
/// as a dim instead — the spec's "disable scale, keep opacity".
struct PressScaleButtonStyle: ButtonStyle {
    var scale: CGFloat = SceneViewTokens.Spring.pressScale

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed && !reduceMotion ? scale : 1)
            .opacity(configuration.isPressed && reduceMotion ? 0.8 : 1)
            .animation(SceneViewTokens.Spring.animation, value: configuration.isPressed)
    }
}

// MARK: - Catalogue entrance

/// `DESIGN.md` Motion, "Scroll reveal" + the catalogue's staggered entry: the
/// item fades in and rises `revealOffset` on `ease-expressive`, `position`
/// places it in the cascade — each slot starts `staggerStep` after the one
/// before it, capped at `staggerMaxDelay`.
///
/// **The cascade is driven by one flag the catalogue owns**, never by per-item
/// `@State` set from `onAppear`. A lazily built grid row that is rebuilt —
/// which is exactly what the home grid does while the hero's RealityKit stage
/// renders — would reset that state and replay its fade, and the measured
/// result was a catalogue pulsing between transparent and opaque forever. A
/// flag read from the parent cannot regress: a rebuilt card reads `true` and is
/// simply there.
///
/// Under `accessibilityReduceMotion` the rise and the cascade both go; the
/// opacity fade stays.
struct StaggeredReveal: ViewModifier {
    let position: Int
    /// The catalogue's entrance flag — flipped once, one frame after it appears.
    let revealed: Bool

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content
            .opacity(revealed ? 1 : 0)
            .offset(y: revealed || reduceMotion ? 0 : SceneViewTokens.Motion.revealOffset)
            .animation(animation, value: revealed)
    }

    private var animation: Animation {
        guard !reduceMotion else {
            return SceneViewTokens.Motion.expressive(SceneViewTokens.Motion.medium)
        }
        return SceneViewTokens.Motion.reveal.delay(
            min(Double(position) * SceneViewTokens.Motion.staggerStep,
                SceneViewTokens.Motion.staggerMaxDelay)
        )
    }
}

extension View {
    /// Reveals this view as item `position` of a catalogue whose entrance flag
    /// is `revealed` — see ``StaggeredReveal``.
    func staggeredReveal(position: Int, revealed: Bool) -> some View {
        modifier(StaggeredReveal(position: position, revealed: revealed))
    }
}
