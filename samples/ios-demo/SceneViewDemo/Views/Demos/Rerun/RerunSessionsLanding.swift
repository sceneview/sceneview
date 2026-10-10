#if os(iOS)
import SwiftUI
import UIKit

/// Room Scan's first screen: record your own room (the primary action), watch the sample
/// session, open a scan or `.rrd` file, and every session kept on this iPhone.
///
/// Its stage and cards follow the system theme, like the replay it leads to.
struct RerunSessionsLanding: View {
    let sessions: [RerunStoredSession]
    let store: RerunSessionStore
    /// A file is being read in.
    let importing: Bool
    /// One line explaining the last thing that failed, `nil` for none.
    let notice: String?
    let topInset: CGFloat
    let bottomInset: CGFloat
    let onRecord: () -> Void
    let onSample: () -> Void
    let onOpenFile: () -> Void
    let onOpen: (RerunStoredSession) -> Void
    let onDelete: (RerunStoredSession) -> Void

    @State private var pendingDelete: RerunStoredSession?
    @State private var shared: SharedScan?

    private typealias Space = SceneViewTokens.Space

    var body: some View {
        ZStack {
            SceneViewTokens.RoomScan.background.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: Space.lg) {
                    header
                    actions
                    sessionList
                }
                .frame(maxWidth: LandingTokens.maxWidth, alignment: .leading)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, SceneViewTokens.Chrome.margin)
                .padding(.top, topInset)
                .padding(.bottom, bottomInset)
            }
            .scrollIndicators(.hidden)
        }
        .confirmationDialog(
            pendingDelete.map { "Delete \u{201C}\($0.title)\u{201D}?" } ?? "",
            isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } }),
            titleVisibility: .visible,
            presenting: pendingDelete
        ) { session in
            Button("Delete", role: .destructive) { onDelete(session) }
        } message: { _ in
            Text("It is removed from this iPhone. Files you already shared are not affected.")
        }
        .sheet(item: $shared) { scan in
            RerunActivitySheet(items: [scan.url])
                .presentationDetents([.medium, .large])
        }
    }

    // MARK: Header and actions

    private var header: some View {
        VStack(alignment: .leading, spacing: Space.sm) {
            Text("Scan a room in 3D")
                .font(SceneViewTokens.TypeScale.display)
                .tracking(SceneViewTokens.TypeScale.displayTracking)
                .foregroundStyle(SceneViewTokens.RoomScan.text)
                .accessibilityAddTraits(.isHeader)
            Text("Walk around with your iPhone. SceneView keeps the camera's path, its photos, the "
                 + "surfaces and the points, then replays the room in 3D.")
                .font(SceneViewTokens.TypeScale.body)
                .foregroundStyle(SceneViewTokens.RoomScan.secondaryText)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var actions: some View {
        VStack(spacing: Space.sm) {
            Button(action: onRecord) {
                HStack(spacing: Space.md) {
                    Image(systemName: "camera.viewfinder")
                        .font(LandingTokens.recordIcon)
                        .frame(width: LandingTokens.recordIconWell, height: LandingTokens.recordIconWell)
                        .background(SceneViewTokens.HomeColor.onPrimary.opacity(0.12), in: Circle())
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Record your room")
                            .font(SceneViewTokens.TypeScale.title)
                            .tracking(SceneViewTokens.TypeScale.titleTracking)
                        Text("Everything stays on your iPhone.")
                            .font(SceneViewTokens.TypeScale.captionRegular)
                            .opacity(0.8)
                    }
                    Spacer(minLength: 0)
                    Image(systemName: "chevron.right")
                        .font(SceneViewTokens.TypeScale.bodySemibold)
                        .accessibilityHidden(true)
                }
                .foregroundStyle(SceneViewTokens.HomeColor.onPrimary)
                .padding(.horizontal, Space.md)
                .frame(maxWidth: .infinity, minHeight: LandingTokens.recordHeight, alignment: .leading)
                .background(SceneViewTokens.HomeColor.primary,
                            in: RoundedRectangle(cornerRadius: SceneViewTokens.Radius.lg, style: .continuous))
                .contentShape(RoundedRectangle(cornerRadius: SceneViewTokens.Radius.lg, style: .continuous))
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("rerun-record")

            HStack(spacing: Space.sm) {
                Button(action: onSample) {
                    Label("Watch a sample session", systemImage: "play.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(LandingGlassButtonStyle())
                .accessibilityIdentifier("rerun-sample")
                Button(action: onOpenFile) {
                    Label("Open file", systemImage: "doc")
                }
                .buttonStyle(LandingGlassButtonStyle())
                .accessibilityHint("Opens a SceneView scan or a Rerun recording")
                .accessibilityIdentifier("rerun-open-file")
            }
        }
    }

    // MARK: Sessions

    private var sessionList: some View {
        VStack(alignment: .leading, spacing: Space.sm) {
            HStack(alignment: .firstTextBaseline) {
                Text("Your sessions")
                    .font(SceneViewTokens.TypeScale.card)
                    .foregroundStyle(SceneViewTokens.RoomScan.text)
                    .accessibilityAddTraits(.isHeader)
                Spacer()
                if !sessions.isEmpty {
                    Text("On this iPhone")
                        .font(SceneViewTokens.TypeScale.captionRegular)
                        .foregroundStyle(SceneViewTokens.RoomScan.secondaryText)
                }
            }
            if let notice {
                Label(notice, systemImage: "exclamationmark.triangle.fill")
                    .font(SceneViewTokens.TypeScale.captionRegular)
                    .foregroundStyle(SceneViewTokens.RoomScan.text)
                    .padding(Space.sm + Space.xs)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(SceneViewTokens.HomeColor.danger.opacity(0.24),
                                in: RoundedRectangle(cornerRadius: SceneViewTokens.Radius.md, style: .continuous))
                    .accessibilityIdentifier("rerun-notice")
            }
            if importing {
                HStack(spacing: Space.sm) {
                    ProgressView()
                    Text("Opening file…")
                        .font(SceneViewTokens.TypeScale.bodyMedium)
                        .foregroundStyle(SceneViewTokens.RoomScan.secondaryText)
                }
                .padding(Space.md)
                .frame(maxWidth: .infinity, alignment: .leading)
                .modifier(LandingCard())
            }
            if sessions.isEmpty && !importing {
                empty
            }
            ForEach(Array(sessions.enumerated()), id: \.element.id) { index, session in
                RerunSessionCard(session: session, thumbnailURL: store.thumbnailURL(for: session.id),
                                 onOpen: { onOpen(session) },
                                 onShare: { share(session) },
                                 onDelete: { pendingDelete = session })
                    .accessibilityIdentifier("rerun-session-\(index)")
            }
        }
        .animation(SceneViewTokens.Spring.fade, value: sessions.map(\.id))
    }

    private var empty: some View {
        VStack(alignment: .leading, spacing: Space.xs) {
            Label("No sessions yet", systemImage: "square.stack.3d.up")
                .font(SceneViewTokens.TypeScale.bodySemibold)
                .foregroundStyle(SceneViewTokens.RoomScan.text)
            Text("Rooms you record are kept here, on this iPhone, until you delete them. "
                 + "You can also open a .svscan or .rrd file.")
                .font(SceneViewTokens.TypeScale.captionRegular)
                .foregroundStyle(SceneViewTokens.RoomScan.secondaryText)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(Space.md)
        .frame(maxWidth: .infinity, alignment: .leading)
        .overlay(
            RoundedRectangle(cornerRadius: SceneViewTokens.Radius.md, style: .continuous)
                .strokeBorder(SceneViewTokens.RoomScan.border,
                              style: StrokeStyle(lineWidth: SceneViewTokens.Glass.borderWidth, dash: [6, 4]))
        )
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("rerun-sessions-empty")
    }

    private func share(_ session: RerunStoredSession) {
        let store = store
        Task {
            let url = await Task.detached(priority: .userInitiated) { try? store.scanFile(for: session) }.value
            if let url { shared = SharedScan(url: url) }
        }
    }

    private struct SharedScan: Identifiable {
        let url: URL
        var id: URL { url }
    }
}

// MARK: - Session card

/// One kept session: its first photo, its title, when and how it was made, and its figures.
/// Tap opens the replay; the menu shares the scan file or deletes the session.
struct RerunSessionCard: View {
    let session: RerunStoredSession
    let thumbnailURL: URL?
    let onOpen: () -> Void
    let onShare: () -> Void
    let onDelete: () -> Void

    @State private var thumbnail: UIImage?

    private typealias Space = SceneViewTokens.Space

    var body: some View {
        HStack(spacing: Space.sm + Space.xs) {
            Button(action: onOpen) {
                HStack(spacing: Space.sm + Space.xs) {
                    thumb
                    VStack(alignment: .leading, spacing: 2) {
                        Text(session.title)
                            .font(SceneViewTokens.TypeScale.card)
                            .foregroundStyle(SceneViewTokens.RoomScan.text)
                            .lineLimit(1)
                        Text(Self.origin(session))
                            .font(SceneViewTokens.TypeScale.captionRegular)
                            .foregroundStyle(SceneViewTokens.RoomScan.secondaryText)
                            .lineLimit(1)
                        Text(Self.figures(session))
                            .font(SceneViewTokens.TypeScale.caption)
                            .monospacedDigit()
                            .foregroundStyle(SceneViewTokens.RoomScan.secondaryText)
                            .lineLimit(1)
                            .minimumScaleFactor(0.85)
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint("Opens the replay")

            Menu {
                menuItems
            } label: {
                Image(systemName: "ellipsis")
                    .font(SceneViewTokens.TypeScale.bodySemibold)
                    .foregroundStyle(SceneViewTokens.RoomScan.text)
                    .frame(width: SceneViewTokens.Layout.touchTarget, height: SceneViewTokens.Layout.touchTarget)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel("More for \(session.title)")
        }
        .padding(.leading, Space.sm)
        .padding(.vertical, Space.sm)
        .modifier(LandingCard())
        .contextMenu { menuItems }
        .task(id: thumbnailURL) {
            guard let thumbnailURL else { return }
            thumbnail = await Task.detached(priority: .utility) {
                UIImage(contentsOfFile: thumbnailURL.path)?.preparingThumbnail(of: LandingTokens.thumbnailPixels)
            }.value
        }
    }

    @ViewBuilder
    private var menuItems: some View {
        Button(action: onShare) {
            Label("Share scan file", systemImage: "square.and.arrow.up")
        }
        Button(role: .destructive, action: onDelete) {
            Label("Delete", systemImage: "trash")
        }
    }

    private var thumb: some View {
        ZStack {
            SceneViewTokens.RoomScan.card
            if let thumbnail {
                Image(uiImage: thumbnail)
                    .resizable()
                    .scaledToFill()
            } else {
                Image(systemName: "cube.transparent")
                    .font(SceneViewTokens.TypeScale.title)
                    .foregroundStyle(SceneViewTokens.RoomScan.secondaryText)
            }
        }
        .frame(width: LandingTokens.thumbnailSize, height: LandingTokens.thumbnailSize)
        .clipShape(RoundedRectangle(cornerRadius: SceneViewTokens.Radius.sm, style: .continuous))
        .accessibilityHidden(true)
    }

    /// "Sep 28, 2:32 PM · Recorded"
    static func origin(_ session: RerunStoredSession) -> String {
        let when = session.createdAt.formatted(.dateTime.day().month(.abbreviated).hour().minute())
        let how = switch session.source {
        case .recorded: "Recorded"
        case .scan: "Scan file"
        case .rrd: ".rrd file"
        }
        return "\(when) · \(how)"
    }

    /// "4.2 m · 3.8k points · 42 photos · 0:18"
    static func figures(_ session: RerunStoredSession) -> String {
        var parts = [RerunFormat.distance(session.pathMetres),
                     "\(RerunFormat.compactCount(session.points)) points"]
        if session.photos > 0 { parts.append("\(RerunFormat.count(session.photos)) photos") }
        if session.duration > 0 { parts.append(RerunFormat.clock(session.duration)) }
        return parts.joined(separator: " · ")
    }
}

// MARK: - Pieces

private enum LandingTokens {
    /// The landing column never grows wider than a phone's, even on iPad.
    static let maxWidth: CGFloat = 560
    static let recordHeight: CGFloat = 96
    static let recordIconWell: CGFloat = 56
    static let recordIcon = Font.system(size: 26, weight: .semibold)
    static let thumbnailSize: CGFloat = 64
    static let thumbnailPixels = CGSize(width: 192, height: 192)
}

/// Token container and border for a landing card on the themed stage.
private struct LandingCard: ViewModifier {
    func body(content: Content) -> some View {
        content
            .background(SceneViewTokens.RoomScan.card,
                        in: RoundedRectangle(cornerRadius: SceneViewTokens.Radius.md, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: SceneViewTokens.Radius.md, style: .continuous)
                    .strokeBorder(SceneViewTokens.RoomScan.border, lineWidth: SceneViewTokens.Glass.borderWidth)
            )
    }
}

/// The secondary actions: a glass capsule, a full touch target high.
private struct LandingGlassButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(SceneViewTokens.TypeScale.bodySemibold)
            .foregroundStyle(SceneViewTokens.RoomScan.text)
            .lineLimit(1)
            .minimumScaleFactor(0.85)
            .padding(.horizontal, SceneViewTokens.Space.md)
            .frame(minHeight: SceneViewTokens.Layout.touchTarget)
            .background(SceneViewTokens.RoomScan.card, in: Capsule())
            .overlay(Capsule().strokeBorder(SceneViewTokens.RoomScan.border, lineWidth: SceneViewTokens.Glass.borderWidth))
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}

/// The system share sheet for files made on demand, where a `ShareLink` would need them ready.
struct RerunActivitySheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
#endif
