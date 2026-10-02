import SwiftUI

/// "Help improve SceneView Demo" — the usage-statistics consent, asked once in the EEA,
/// the UK and Switzerland (`ConsentRegion`), over Home on first launch (`TelemetryConsent`).
/// Same copy and buttons as the Android bottom sheet.
///
/// Built on `PushPrePromptSheet` (60 pt circle, content-height detent, `surfaceContainer`),
/// with one difference: refusing is as easy as accepting, so "Share" and "Don't share" are
/// two capsules of the same style, side by side. Swiping the sheet away is a refusal
/// (`TelemetryConsent.sheetDismissed`).
struct ConsentSheet: View {
    let onShare: () -> Void
    let onDecline: () -> Void

    static let privacyPolicyURL = URL(string: "https://sceneview.github.io/privacy.html")!

    @State private var contentHeight: CGFloat = 380

    var body: some View {
        VStack(spacing: SceneViewTokens.Space.md) {
            ZStack {
                Circle()
                    .fill(SceneViewTokens.HomeColor.primary.opacity(0.16))
                Image(systemName: "chart.bar.fill")
                    .font(.system(size: 24, weight: .semibold))
                    .foregroundStyle(SceneViewTokens.HomeColor.primary)
            }
            .frame(width: 60, height: 60)
            .accessibilityHidden(true)

            VStack(spacing: SceneViewTokens.Space.sm) {
                Text("Help improve SceneView Demo")
                    .font(SceneViewTokens.TypeScale.title)
                    .tracking(SceneViewTokens.TypeScale.titleTracking)
                    .foregroundStyle(SceneViewTokens.HomeColor.onSurface)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityAddTraits(.isHeader)
                Text("Share usage statistics and crash reports? They show which samples get opened and what breaks, so we can fix it. No ads, no advertising ID, never sold. You can change this anytime in About › Privacy & notifications.")
                    .font(SceneViewTokens.TypeScale.body)
                    .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                Link(destination: Self.privacyPolicyURL) {
                    Text("Privacy policy")
                        .font(SceneViewTokens.TypeScale.bodySemibold)
                        .foregroundStyle(SceneViewTokens.HomeColor.primary)
                        .underline()
                        .frame(minHeight: SceneViewTokens.Layout.touchTarget)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("consent-privacy-policy")
            }

            HStack(spacing: SceneViewTokens.Space.sm) {
                choice("Don't share", identifier: "consent-decline", action: onDecline)
                choice("Share", identifier: "consent-share", action: onShare)
            }
        }
        .padding(.horizontal, SceneViewTokens.Space.lg)
        .padding(.top, SceneViewTokens.Space.lg)
        .padding(.bottom, SceneViewTokens.Space.sm)
        #if os(iOS)
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { contentHeight = $0 }
        .frame(maxHeight: .infinity, alignment: .top)
        .presentationDetents([.height(contentHeight)])
        .presentationDragIndicator(.visible)
        #else
        .padding(.bottom, SceneViewTokens.Space.md)
        .frame(width: 440)
        .background(SceneViewTokens.HomeColor.surfaceContainer)
        #endif
        .presentationBackground(SceneViewTokens.HomeColor.surfaceContainer)
        // Keep the buttons' own identifiers reachable under the container one.
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("consent-sheet")
    }

    /// One answer: an outlined capsule, the same for both so neither is pushed. Text and
    /// stroke are `primary` (6.4:1 light, 8.1:1 dark on `surfaceContainer`).
    private func choice(_ title: String, identifier: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(SceneViewTokens.TypeScale.bodySemibold)
                .foregroundStyle(SceneViewTokens.HomeColor.primary)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .frame(maxWidth: .infinity, minHeight: 50)
                .overlay(Capsule().strokeBorder(SceneViewTokens.HomeColor.primary, lineWidth: 1.5))
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(identifier)
    }
}
