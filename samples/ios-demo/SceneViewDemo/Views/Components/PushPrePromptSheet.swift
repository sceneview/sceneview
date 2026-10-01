#if os(iOS)
import SwiftUI

/// "Get notified when new samples land" — the pre-prompt shown before the system
/// notification dialog, on the 2nd return to Home after a sample (`PushPrePromptPolicy`).
/// Same copy and buttons as the Android bottom sheet.
struct PushPrePromptSheet: View {
    let onNotify: () -> Void
    let onLater: () -> Void

    @State private var contentHeight: CGFloat = 320

    var body: some View {
        VStack(spacing: SceneViewTokens.Space.md) {
            ZStack {
                Circle()
                    .fill(SceneViewTokens.HomeColor.primary.opacity(0.16))
                Image(systemName: "bell.badge.fill")
                    .font(.system(size: 26, weight: .semibold))
                    .foregroundStyle(SceneViewTokens.HomeColor.primary)
                    .symbolRenderingMode(.hierarchical)
            }
            .frame(width: 60, height: 60)
            .accessibilityHidden(true)

            VStack(spacing: SceneViewTokens.Space.sm) {
                Text("Get notified when new samples land")
                    .font(SceneViewTokens.TypeScale.title)
                    .tracking(SceneViewTokens.TypeScale.titleTracking)
                    .foregroundStyle(SceneViewTokens.HomeColor.onSurface)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityAddTraits(.isHeader)
                Text("A short note when a new sample or a release ships, a few times a month at most. You can turn it off in About at any time.")
                    .font(SceneViewTokens.TypeScale.body)
                    .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }

            VStack(spacing: SceneViewTokens.Space.xs) {
                Button(action: onNotify) {
                    Text("Notify me")
                        .font(SceneViewTokens.TypeScale.bodySemibold)
                        .foregroundStyle(SceneViewTokens.HomeColor.onPrimary)
                        .frame(maxWidth: .infinity, minHeight: 50)
                        .background(SceneViewTokens.HomeColor.primary, in: Capsule())
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("push-preprompt-notify")

                Button(action: onLater) {
                    Text("Not now")
                        .font(SceneViewTokens.TypeScale.bodySemibold)
                        .foregroundStyle(SceneViewTokens.HomeColor.primary)
                        .frame(maxWidth: .infinity, minHeight: SceneViewTokens.Layout.touchTarget)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("push-preprompt-later")
            }
            .padding(.top, SceneViewTokens.Space.xs)
        }
        .padding(.horizontal, SceneViewTokens.Space.lg)
        .padding(.top, SceneViewTokens.Space.lg)
        .padding(.bottom, SceneViewTokens.Space.sm)
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { contentHeight = $0 }
        .frame(maxHeight: .infinity, alignment: .top)
        .presentationDetents([.height(contentHeight)])
        .presentationDragIndicator(.visible)
        .presentationBackground(SceneViewTokens.HomeColor.surfaceContainer)
        // Keep the buttons' own identifiers reachable under the container one.
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("push-preprompt")
    }
}
#endif
