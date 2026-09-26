import SwiftUI

/// About tab — per the SceneView design system (`DESIGN.md` "Demo App About").
///
/// The identity block (launcher icon, name, version, tagline) flat on the page,
/// the support card — the screen's one emphasised surface — then a series of
/// `.regularMaterial` row cards (Open Source, Docs, GitHub, 3D Playground,
/// Credits), and a footer with attribution.
struct AboutTab: View {
    private static let version: String = {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
    }()

    // #1152 Stage 3 — Credits sheet (CC-BY attribution for every streamed
    // Sketchfab model in `SampleAssets`); #3214 — bundled assets too, from
    // the generated `BundledCredits.json`.
    @State private var showCreditsSheet = false

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(spacing: 20) {
                    identity
                    supportCard
                    aboutCards
                    footer
                }
                // One gutter and one bottom inset for the three tabs: the same
                // tokens the Showcase grid uses, so a tab switch moves nothing.
                .padding(.horizontal, SceneViewTokens.Home.contentPadding)
                .padding(.top, SceneViewTokens.Home.heroTopGap)
                .padding(.bottom, SceneViewTokens.Home.gridBottomInset)
            }
            // The page never set a ground, so dark fell back to the system
            // black (#000) while Showcase and Explore sit on `surface` (#0D1117).
            .background(SceneViewTokens.HomeColor.surface)
            .navigationTitle("About")
            .sheet(isPresented: $showCreditsSheet) {
                CreditsSheet()
            }
        }
    }

    // MARK: - Identity

    /// The mark, the name, the installed version, one sentence — the iOS twin
    /// of Android's `AboutIdentity` (#3564, #3808).
    ///
    /// Deliberately **not** a card (`DESIGN.md` "Demo App About"): a slab here
    /// is a second emphasised surface competing with the support card right
    /// under it. On the page's `surface` the block reads as a masthead instead.
    private var identity: some View {
        VStack(spacing: SceneViewTokens.Space.sm) {
            // `about-mark`: the launcher icon itself, not an SF Symbol on a
            // gradient tile. Theme-independent — the same picture in light and
            // dark, with its own contrast built in.
            Image("about_mark")
                .resizable()
                .interpolation(.high)
                .frame(width: SceneViewTokens.About.markSize,
                       height: SceneViewTokens.About.markSize)
                .clipShape(RoundedRectangle(cornerRadius: SceneViewTokens.Radius.xl,
                                            style: .continuous))
                .accessibilityHidden(true)

            Text("SceneView")
                .font(SceneViewTokens.TypeScale.title)
                .tracking(SceneViewTokens.TypeScale.titleTracking)
                .foregroundStyle(SceneViewTokens.HomeColor.onSurface)
                .accessibilityAddTraits(.isHeader)

            // Version as text, not a material pill: a pill is one more surface
            // in a block that must sit flat on the page.
            Text("Version \(Self.version)")
                .font(.footnote)
                .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)

            Text("3D & AR for Jetpack Compose, SwiftUI, and the Web.\nDeclarative, AI-friendly, open source.")
                .font(.callout)
                .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, SceneViewTokens.Space.sm)
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
                subtitle: "Authors & licenses for every bundled and streamed 3D asset",
                trailing: .chevron,
                action: { showCreditsSheet = true }
            )
        }
    }

    // MARK: - Support

    /// The one emphasised surface of the screen (`DESIGN.md` "Demo App About"):
    /// `secondary-container` at `radius-lg`, above the fold, Open Collective as
    /// the primary action and GitHub Sponsors as the secondary one — never a
    /// third link, no amounts, no tiers. The iOS twin of Android's
    /// `AboutSupportCard` (#3676). It replaces the primary-filled "Star on
    /// GitHub" capsule, which was a second emphasised surface and duplicated
    /// the GitHub row below.
    private var supportCard: some View {
        VStack(alignment: .leading, spacing: SceneViewTokens.Space.sm) {
            HStack(spacing: SceneViewTokens.Space.sm) {
                Image(systemName: "heart")
                    .font(.body.weight(.semibold))
                    .accessibilityHidden(true)
                Text("Support SceneView")
                    .font(.headline)
                    .accessibilityAddTraits(.isHeader)
            }
            Text("An independent open-source project. Your support pays for the time that keeps it maintained.")
                .font(.subheadline)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: SceneViewTokens.Space.sm) {
                Link(destination: URL(string: "https://opencollective.com/sceneview")!) {
                    Text("Donate on Open Collective")
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.85)
                        .foregroundStyle(SceneViewTokens.HomeColor.onPrimary)
                        .padding(.horizontal, SceneViewTokens.Space.sm)
                        .frame(maxWidth: .infinity, minHeight: SceneViewTokens.Layout.touchTarget)
                        .background(SceneViewTokens.HomeColor.primary,
                                    in: RoundedRectangle(cornerRadius: SceneViewTokens.Radius.md,
                                                         style: .continuous))
                }
                .accessibilityLabel("Donate on Open Collective. Opens opencollective.com")
                Link(destination: URL(string: "https://github.com/sponsors/sceneview")!) {
                    Text("GitHub Sponsors")
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                        .foregroundStyle(SceneViewTokens.HomeColor.onSecondaryContainer)
                        .padding(.horizontal, SceneViewTokens.Space.sm)
                        .frame(minHeight: SceneViewTokens.Layout.touchTarget)
                }
                .accessibilityLabel("GitHub Sponsors. Opens github.com")
            }
        }
        .foregroundStyle(SceneViewTokens.HomeColor.onSecondaryContainer)
        .padding(SceneViewTokens.Space.md)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(SceneViewTokens.HomeColor.secondaryContainer,
                    in: RoundedRectangle(cornerRadius: SceneViewTokens.Radius.lg, style: .continuous))
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
        .materialGlassBackground(in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .strokeBorder(Color.primary.opacity(0.06), lineWidth: 0.5)
        )
    }
}
