import SwiftUI

/// The home screen's single focal point (`DESIGN.md` "Demo App Home", Hero):
/// a full-span image card for the Model Viewer. `radius-xl` clip,
/// `preview_hero_model_viewer` cropped to fill, a vertical scrim from
/// transparent at 50 % to `stage-scrim-end`, and bottom-left copy —
/// `type-display` title, `type-body` subtitle at 80 % white, one 44 pt "Open"
/// pill. The whole card is one button. Light: soft shadow; dark: 1 pt
/// `outline-subtle`. The iOS twin of Android's `HomeHero.kt`.
///
/// The hero is dark in both themes by design — it is the one accent on a
/// white page in light mode, which is why its text colours are fixed tokens
/// (`SceneViewTokens.HomeColor`) rather than system roles.
struct HomeHero: View {
    let height: CGFloat
    let onTap: () -> Void

    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        Button(action: onTap) {
            ZStack(alignment: .bottomLeading) {
                SceneViewTokens.HomeColor.heroField
                // Hosted in an overlay so the fill-scaled image never reports
                // its own ideal width to the ZStack (it would widen the whole
                // home scroll content past the screen).
                Color.clear
                    .frame(height: height)
                    .overlay {
                        Image("preview_hero_model_viewer")
                            .resizable()
                            .scaledToFill()
                    }
                    .clipped()
                LinearGradient(
                    stops: [
                        .init(color: SceneViewTokens.SpatialGalleryColor.stageScrimStart,
                              location: SceneViewTokens.Home.heroScrimStart),
                        .init(color: SceneViewTokens.SpatialGalleryColor.stageScrimEnd, location: 1),
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
                        .strokeBorder(SceneViewTokens.HomeColor.outlineSubtle,
                                      lineWidth: SceneViewTokens.Home.cardOutlineWidth)
                }
            }
            .shadow(color: .black.opacity(colorScheme == .dark ? 0 : 0.12), radius: 12, y: 4)
        }
        .buttonStyle(PressScaleButtonStyle())
        .accessibilityLabel("Model Viewer. Any glTF, HDR lighting, one tap to AR. Open")
        .accessibilityIdentifier("home-hero")
    }
}

/// Press feedback on the one app spring: scale to 0.98 while pressed, no
/// highlight. Shared by the hero, the media cards and the chrome.
struct PressScaleButtonStyle: ButtonStyle {
    var scale: CGFloat = SceneViewTokens.Spring.pressScale

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? scale : 1)
            .animation(SceneViewTokens.Spring.animation, value: configuration.isPressed)
    }
}
