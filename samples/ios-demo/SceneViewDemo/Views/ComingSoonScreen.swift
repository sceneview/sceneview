import SwiftUI

/// Placeholder shown when the user taps a demo that is not yet ported to iOS.
///
/// Mirrors an Android-only feature with a friendly "Coming soon" message and
/// links to track progress or try the equivalent on the Android demo app.
struct ComingSoonScreen: View {
    let title: String
    let subtitle: String
    let icon: String

    @Environment(\.dismiss) private var dismiss

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

                    Text("Coming soon")
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

                Text("This sample is already available in the Android demo app and will be ported to iOS soon. SceneView aims for full Android↔iOS parity.")
                    .font(.footnote)
                    .foregroundStyle(.tertiary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)
                    .padding(.top, 8)

                Spacer(minLength: 24)
            }
            .frame(maxWidth: .infinity)
        }
        .navigationTitle("Coming soon")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
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
