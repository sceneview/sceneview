import SwiftUI

/// The home screen's single focal point: a window onto the live dusk flight
/// (`HomeHeroStage`, drawn under the catalogue) with the Model Viewer's copy
/// at its bottom left — `type-display` title, `type-body` subtitle at 80 %
/// white, one 44 pt "Open" pill. No card, no corners, no shadow: the landscape
/// runs edge to edge behind it and the stage paints the legibility scrim. The
/// whole band is one button. The iOS twin of Android's featured window page
/// (`HomeFeatured.kt`, #3948).
///
/// The text colours are fixed tokens (`SceneViewTokens.HomeColor`), not system
/// roles: the stage is a dusk sky in both themes.
struct HomeHero: View {
    let height: CGFloat
    let onTap: () -> Void

    /// The helmet flying beside the camera on the hero stage: the Model
    /// Viewer's own first-run model, so the band shows what the tap opens.
    /// Guarded by `ViewerAssetTests.testHomeHeroModelShipsItsUSDZ`.
    static let heroAssetName = "khronos_damaged_helmet"

    private static let title = "Models"
    private static let subtitle = "Explore a model in 3D or in your room"

    var body: some View {
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: SceneViewTokens.Space.sm) {
                Text(Self.title)
                    .font(SceneViewTokens.TypeScale.display)
                    .tracking(SceneViewTokens.TypeScale.displayTracking)
                    .foregroundStyle(SceneViewTokens.HomeColor.heroTitle)
                Text(Self.subtitle)
                    .font(SceneViewTokens.TypeScale.body)
                    .foregroundStyle(SceneViewTokens.HomeColor.heroSubtitle)
                    .lineLimit(2)
                Text("Open")
                    .font(SceneViewTokens.TypeScale.bodySemibold)
                    .foregroundStyle(SceneViewTokens.HomeColor.heroPillText)
                    .padding(.horizontal, SceneViewTokens.Home.heroPillPaddingHorizontal)
                    .frame(height: SceneViewTokens.Home.heroPillHeight)
                    .background(SceneViewTokens.HomeColor.heroPillBackground, in: Capsule())
                    .padding(.top, SceneViewTokens.Space.sm)
            }
            .padding(SceneViewTokens.Home.heroPadding)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
            .frame(height: height)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(Self.title). \(Self.subtitle). Open")
        .accessibilityIdentifier("home-hero")
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
