import SwiftUI

/// Placeholder shown when the user taps a demo that has no destination on iOS.
///
/// Two distinct honest treatments (#2804 Job C — never conflate the two):
/// - **Coming soon** (`androidOnlyReason == nil`, the common case): not yet
///   ported, but expected to land eventually. Friendly message, links to
///   track progress or try the equivalent on the Android demo app.
/// - **Android-only** (`androidOnlyReason` set): a capability with no
///   ARKit/RealityKit equivalent — e.g. ARCore Geospatial/VPS. Never implies
///   a future port; the pill, nav title, and footer all say "Android-only"
///   and state the one-line reason instead of "will be ported to iOS soon".
struct ComingSoonScreen: View {
    let title: String
    let subtitle: String
    let icon: String

    /// One-line, user-defensible reason this is **permanently** Android-only
    /// — set only for a platform-locked capability, never for the ordinary
    /// "not ported yet" case. See ``DemoItem/androidOnlyReason``.
    var androidOnlyReason: String? = nil

    @Environment(\.dismiss) private var dismiss

    private var isAndroidOnly: Bool { androidOnlyReason != nil }
    private var pillText: String { isAndroidOnly ? "Android-only" : "Coming soon" }
    private var footerText: String {
        androidOnlyReason ??
            "This sample is already available in the Android demo app and will be ported to iOS soon. SceneView aims for full Android↔iOS parity."
    }

    private var androidPlayStoreURL: URL { URL(string: "https://play.google.com/store/apps/details?id=io.github.sceneview.demo")! }
    private var githubIssuesURL: URL { URL(string: "https://github.com/sceneview/sceneview/issues")! }

    var body: some View {
        ScrollView {
            VStack(spacing: 28) {
                Spacer(minLength: 24)

                ZStack {
                    Circle()
                        .fill(.orange.gradient)
                        .frame(width: 96, height: 96)
                        .opacity(0.15)
                    Image(systemName: icon)
                        .font(.system(size: 44, weight: .semibold))
                        .foregroundStyle(.orange)
                }
                .accessibilityHidden(true)

                VStack(spacing: 10) {
                    Text(title)
                        .font(.title2.weight(.bold))
                        .multilineTextAlignment(.center)

                    Text(subtitle)
                        .font(.body)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 24)

                    Text(pillText)
                        .font(.caption.weight(.semibold))
                        .padding(.horizontal, 12)
                        .padding(.vertical, 5)
                        .background(.tint.opacity(0.15), in: Capsule())
                        .padding(.top, 4)
                }

                VStack(spacing: 12) {
                    Link(destination: githubIssuesURL) {
                        Label("Follow progress on GitHub", systemImage: "star.circle.fill")
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 14)
                            .background(.tint.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
                    }

                    Link(destination: androidPlayStoreURL) {
                        Label("Try it on Android demo", systemImage: "arrow.up.right.square.fill")
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 14)
                            .background(Color.green.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
                            .foregroundStyle(.green)
                    }
                }
                .padding(.horizontal, 20)

                Text(footerText)
                    .font(.footnote)
                    .foregroundStyle(.tertiary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)
                    .padding(.top, 8)

                Spacer(minLength: 24)
            }
            .frame(maxWidth: .infinity)
        }
        .navigationTitle(pillText)
        .navigationBarTitleInline()
    }
}

#Preview {
    NavigationStack {
        ComingSoonScreen(
            title: "Gesture Editing",
            subtitle: "Move, scale, and rotate models with one-finger drag, pinch, and rotate gestures.",
            icon: "hand.pinch.fill"
        )
    }
}

#Preview("Android-only") {
    NavigationStack {
        ComingSoonScreen(
            title: "Streetscape Geometry",
            subtitle: "Geospatial building and terrain meshes",
            icon: "map.fill",
            androidOnlyReason: "ARCore Streetscape Geometry (Geospatial/VPS) is a Google-backend service with no ARKit equivalent — not planned for iOS."
        )
    }
}
