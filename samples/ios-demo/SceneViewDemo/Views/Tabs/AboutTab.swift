import SwiftUI

/// About tab — Liquid Glass card layout (iOS 26+ Stitch spec).
///
/// Hero logo + version pill, then a series of `.regularMaterial` glass cards
/// (Open Source, Docs, GitHub, 3D Playground, Credits), a tinted "Star on GitHub"
/// CTA, and a footer with attribution.
struct AboutTab: View {
    private static let version: String = {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
    }()

    // #1152 Stage 3 — Credits sheet (CC-BY attribution for every streamed
    // Sketchfab model in `SampleAssets`).
    @State private var showCreditsSheet = false

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(spacing: 20) {
                    heroCard
                    aboutCards
                    starCTA
                    footer
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 24)
            }
            .navigationTitle("About")
            .sheet(isPresented: $showCreditsSheet) {
                CreditsSheet()
            }
        }
    }

    // MARK: - Hero

    private var heroCard: some View {
        VStack(spacing: 14) {
            ZStack {
                LinearGradient(
                    colors: [Color.blue.opacity(0.35), Color.purple.opacity(0.25)],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
                Image(systemName: "cube.fill")
                    .font(.system(size: 56, weight: .semibold))
                    .foregroundStyle(.white)
                    .shadow(color: .blue.opacity(0.4), radius: 12)
            }
            .frame(width: 110, height: 110)
            .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
            .accessibilityHidden(true)

            Text("SceneView")
                .font(.largeTitle.weight(.bold))

            HStack(spacing: 6) {
                Image(systemName: "checkmark.seal.fill")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.green)
                Text("v\(Self.version)")
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.primary)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(.regularMaterial, in: Capsule())
            .overlay(Capsule().strokeBorder(Color.primary.opacity(0.08), lineWidth: 0.5))

            Text("3D & AR for Jetpack Compose, SwiftUI, and the Web.\nDeclarative, AI-friendly, open source.")
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 8)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 28)
        .padding(.horizontal, 16)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .strokeBorder(Color.primary.opacity(0.06), lineWidth: 0.5)
        )
    }

    // MARK: - About cards

    private var aboutCards: some View {
        VStack(spacing: 12) {
            AboutCard(
                icon: "heart.circle.fill",
                iconColor: .pink,
                title: "Open Source",
                subtitle: "Apache 2.0 — free for any project, forever",
                trailing: nil,
                action: nil
            )

            AboutCard(
                icon: "book.fill",
                iconColor: .blue,
                title: "Documentation",
                subtitle: "Guides, API reference, recipes",
                trailing: .link,
                url: URL(string: "https://sceneview.github.io")
            )

            AboutCard(
                icon: "chevron.left.forwardslash.chevron.right",
                iconColor: .indigo,
                title: "GitHub",
                subtitle: "Source, issues, releases",
                trailing: .link,
                url: URL(string: "https://github.com/sceneview/sceneview")
            )

            AboutCard(
                icon: "sparkles",
                iconColor: .orange,
                title: "3D Playground",
                subtitle: "Try every feature in the browser",
                trailing: .link,
                url: URL(string: "https://sceneview.github.io/playground.html")
            )

            AboutCard(
                icon: "person.2.fill",
                iconColor: .teal,
                title: "Credits",
                subtitle: "Authors & CC-BY attribution for every streamed Sketchfab model",
                trailing: .chevron,
                action: { showCreditsSheet = true }
            )
        }
    }

    // MARK: - Star CTA

    private var starCTA: some View {
        Link(destination: URL(string: "https://github.com/sceneview/sceneview")!) {
            HStack(spacing: 10) {
                Image(systemName: "star.fill")
                    .font(.title3)
                Text("Star on GitHub")
                    .font(.headline)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(.tint, in: Capsule())
            .foregroundStyle(.white)
        }
        .accessibilityLabel("Star SceneView on GitHub")
    }

    // MARK: - Footer

    private var footer: some View {
        VStack(spacing: 4) {
            HStack(spacing: 4) {
                Text("Made with")
                Image(systemName: "heart.fill")
                    .foregroundStyle(.red)
                Text("by Thomas Gorisse")
            }
            .font(.caption)
            .foregroundStyle(.secondary)

            Text("and the SceneView contributors")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding(.top, 8)
    }
}

// MARK: - Liquid Glass row card

private struct AboutCard: View {
    enum Trailing {
        case link
        case chevron
    }

    let icon: String
    let iconColor: Color
    let title: String
    let subtitle: String
    let trailing: Trailing?
    var url: URL? = nil
    var action: (() -> Void)? = nil

    var body: some View {
        if let url = url {
            Link(destination: url) {
                cardContent
            }
            .buttonStyle(.plain)
            .accessibilityLabel("\(title): \(subtitle). Opens \(url.host ?? "link")")
        } else if let action = action {
            Button(action: action) { cardContent }
                .buttonStyle(.plain)
                .accessibilityLabel("\(title): \(subtitle)")
        } else {
            cardContent
                .accessibilityElement(children: .combine)
                .accessibilityLabel("\(title): \(subtitle)")
        }
    }

    private var cardContent: some View {
        HStack(spacing: 14) {
            ZStack {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(iconColor.opacity(0.18))
                Image(systemName: icon)
                    .font(.title3)
                    .foregroundStyle(iconColor)
            }
            .frame(width: 44, height: 44)
            .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }

            Spacer(minLength: 4)

            if let trailing = trailing {
                Image(systemName: trailing == .link ? "arrow.up.right" : "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
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
