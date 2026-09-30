import SwiftUI

// Copy shared with Android (#4146) word for word; only the size differs, since
// each platform ships its own file (USDZ here, GLB there).

// MARK: - Status pill

/// "Flight Helmet · downloading 34 %" — shown in a demo's status slot while
/// its bundled stand-in is on stage and the HD asset is not there yet.
///
/// Same family as ``AssetSourcePill`` (dot, `caption2` medium, glass capsule).
/// Nothing is shown once the HD asset is on stage: the swap is the
/// confirmation. "download 52 MB" and "download failed" are tappable: they
/// open the size-first dialog for this one model.
///
/// The pill lays out at the width of its widest copy, whatever the state, so
/// the demo header picks one row or two once and does not jump while the
/// percentage counts up.
struct HDPackPill: View {
    /// What the download gets you: the manifest entry's `title`. The stand-in
    /// on stage is a different model, so the pill names the one replacing it.
    let title: String
    let state: HDAssetState
    /// The HD file is on disk and being loaded onto the stage.
    var loading = false
    var bytes: Int64 = 0
    /// Nothing else can go on stage for this model (a Museum scan). "waiting
    /// for Wi-Fi" is then tappable too: on cellular the user may choose to
    /// spend mobile data rather than look at a thumbnail.
    var hdOnly = false
    var onDownload: (() -> Void)?

    /// The status half of the pill ("download 52 MB"), without the title.
    /// Static so the tests can pin it without SwiftUI.
    static func status(for state: HDAssetState, loading: Bool = false, bytes: Int64) -> String? {
        if loading { return "loading" }
        switch state {
        case .ready: return nil
        case .waitingForWiFi: return "waiting for Wi-Fi"
        case .waitingForNetwork: return "waiting for a network"
        case .downloading(let fraction): return "downloading \(Int((fraction * 100).rounded(.down))) %"
        case .failed: return "download failed"
        case .missing: return "download \(HDPackFormat.size(bytes))"
        }
    }

    /// The whole pill copy, e.g. "Flight Helmet · download 52 MB".
    static func label(title: String, state: HDAssetState, loading: Bool = false, bytes: Int64) -> String? {
        status(for: state, loading: loading, bytes: bytes).map { "\(title) · \($0)" }
    }

    /// Whether a tap on the pill in `state` opens the download dialog.
    /// Static so the tests can pin it without SwiftUI.
    static func isTappable(_ state: HDAssetState, loading: Bool = false, hdOnly: Bool) -> Bool {
        guard !loading else { return false }
        switch state {
        case .missing, .failed: return true
        case .waitingForWiFi: return hdOnly
        default: return false
        }
    }

    private var isTappable: Bool {
        onDownload != nil && Self.isTappable(state, loading: loading, hdOnly: hdOnly)
    }

    private static func tint(for state: HDAssetState, loading: Bool) -> Color {
        if loading { return .accentColor }
        switch state {
        case .downloading, .missing: return .accentColor
        case .failed: return SceneViewTokens.HomeColor.danger
        default: return .secondary
        }
    }

    /// Every copy this pill can show, with its glyph: the widest one sets
    /// the layout width.
    private var allCopies: [(status: String, tappable: Bool)] {
        let states: [HDAssetState] = [.missing, .waitingForWiFi, .waitingForNetwork, .downloading(1), .failed]
        let canTap = onDownload != nil
        return states.compactMap { s in
            Self.status(for: s, bytes: bytes).map { ($0, canTap && Self.isTappable(s, hdOnly: hdOnly)) }
        } + [("loading", false)]
    }

    /// Glyph and copy, without the capsule.
    private func content(status: String, tappable: Bool, tint: Color, state: HDAssetState) -> some View {
        HStack(spacing: SceneViewTokens.Space.xs) {
            // One glyph per state: the dot while something is under way,
            // the action icon (trailing) when a tap is expected. Keeps the
            // longest copy ("… · download 52 MB") beside the demo title.
            if !tappable {
                Circle()
                    .fill(tint)
                    .frame(width: SceneViewTokens.Space.sm, height: SceneViewTokens.Space.sm)
            }
            // Large Dynamic Type: the title truncates, the status never does.
            HStack(spacing: 0) {
                Text(title)
                    .lineLimit(1)
                    .truncationMode(.tail)
                Text(" · \(status)")
                    .monospacedDigit()
                    .lineLimit(1)
                    .fixedSize()
                    .layoutPriority(1)
            }
            .font(.caption2.weight(.medium))
            .foregroundStyle(.primary)
            if tappable {
                Image(systemName: state == .failed ? "arrow.clockwise" : "arrow.down.circle")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(tint)
            }
        }
        .padding(.horizontal, SceneViewTokens.Space.sm)
        .padding(.vertical, SceneViewTokens.Space.xs)
    }

    var body: some View {
        if let status = Self.status(for: state, loading: loading, bytes: bytes) {
            let label = "\(title) · \(status)"
            let pill = content(status: status, tappable: isTappable,
                               tint: Self.tint(for: state, loading: loading), state: state)
                .glassBackground(in: Capsule())

            ZStack(alignment: .trailing) {
                // Width reservation only: never drawn, never read aloud.
                ForEach(allCopies, id: \.status) { copy in
                    content(status: copy.status, tappable: copy.tappable, tint: .clear, state: .missing)
                        .hidden()
                }
                .accessibilityHidden(true)

                if isTappable, let onDownload {
                    Button(action: onDownload) { pill }
                        .buttonStyle(PressScaleButtonStyle())
                        .accessibilityLabel(label)
                        .accessibilityHint("Shows the download size first")
                        .accessibilityIdentifier("hdPackPill")
                } else {
                    pill
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel(label)
                        .accessibilityIdentifier("hdPackPill")
                }
            }
        }
    }
}

// MARK: - Download dialog

extension View {
    /// States the size before anything moves (App Review 4.2.3(ii)).
    /// Mentions mobile data only on an expensive network; Low Data Mode alone
    /// is not a data plan.
    ///
    /// - `assetID` set (a demo's pill): "Download Apollo 11 Interior?", that
    ///   one file's size, and only that file is fetched.
    /// - `assetID` nil (the About row): "Download HD scenes?", the size of
    ///   what ``HDPackStore/packAssets`` still misses, and that is fetched.
    func hdPackDownloadDialog(isPresented: Binding<Bool>, assetID: String? = nil,
                              store: HDPackStore = .shared) -> some View {
        modifier(HDPackDownloadDialog(isPresented: isPresented, assetID: assetID, store: store))
    }
}

struct HDPackDownloadDialog: ViewModifier {
    @Binding var isPresented: Bool
    var assetID: String?
    @ObservedObject var store: HDPackStore

    /// The whole pack, from the About row.
    static func message(bytes: Int64, expensive: Bool) -> String {
        let base = "Full-resolution models, \(HDPackFormat.size(bytes)). They stay on this device until you remove them in About."
        return expensive ? base + " This uses mobile data." : base
    }

    /// One model, from its pill.
    static func title(asset: HDPackAsset) -> String { "Download \(asset.title)?" }

    static func message(asset: HDPackAsset, expensive: Bool) -> String {
        let base = "Full-resolution model, \(HDPackFormat.size(asset.bytes)). It stays on this device until you remove HD scenes in About."
        return expensive ? base + " This uses mobile data." : base
    }

    private var asset: HDPackAsset? { assetID.flatMap { store.manifest.asset(id: $0) } }

    func body(content: Content) -> some View {
        let asset = self.asset
        return content.confirmationDialog(asset.map(Self.title(asset:)) ?? "Download HD scenes?",
                                          isPresented: $isPresented, titleVisibility: .visible) {
            Button("Download") {
                if let asset { store.download(id: asset.id) } else { store.downloadNow() }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            if let asset {
                Text(Self.message(asset: asset, expensive: store.isExpensive))
            } else {
                Text(Self.message(bytes: store.missingBytes, expensive: store.isExpensive))
            }
        }
    }
}

// MARK: - About row

/// "HD scenes · 52 MB" with its status line and one action: Download now or
/// Remove. Lives on the About tab next to the other row cards.
struct HDPackSettingsRow: View {
    @ObservedObject var store: HDPackStore = .shared
    @State private var confirmDownload = false

    /// Status line under the title; `nil` when there is nothing to say
    /// (not downloaded: the button says it). Static so the tests can pin it.
    static func status(for state: HDAssetState, constrained: Bool) -> String? {
        switch state {
        case .ready: return "Downloaded"
        case .downloading(let f): return "Downloading \(Int((f * 100).rounded(.down))) %"
        case .waitingForWiFi: return constrained ? "Paused · Low Data Mode" : "Waiting for Wi-Fi"
        case .waitingForNetwork: return "Waiting for a network"
        case .failed: return "Download failed"
        case .missing: return nil
        }
    }

    private var state: HDAssetState { store.packState }

    private var statusLine: String? {
        store.removalNotice ?? Self.status(for: state, constrained: store.isConstrained)
    }

    var body: some View {
        HStack(spacing: SceneViewTokens.Space.sm + SceneViewTokens.Space.xs) {
            ZStack {
                RoundedRectangle(cornerRadius: SceneViewTokens.Radius.sm, style: .continuous)
                    .fill(SceneViewTokens.HomeColor.primary.opacity(0.18))
                Image(systemName: "sparkles.tv")
                    .font(.title3)
                    .foregroundStyle(SceneViewTokens.HomeColor.primary)
            }
            .frame(width: SceneViewTokens.Glass.iconButtonSize, height: SceneViewTokens.Glass.iconButtonSize)
            .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 2) {
                Text("HD scenes · \(HDPackFormat.size(store.packBytes))")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(SceneViewTokens.HomeColor.onSurface)
                    .lineLimit(1)
                    .minimumScaleFactor(0.85)
                if let statusLine {
                    Text(statusLine)
                        .font(.caption)
                        .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
                        .monospacedDigit()
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("hdPackStatus")
                }
            }
            .layoutPriority(1)
            .accessibilityElement(children: .combine)
            .animation(.easeInOut(duration: 0.2), value: statusLine)

            Spacer(minLength: SceneViewTokens.Space.xs)

            trailing
        }
        .padding(.horizontal, SceneViewTokens.Space.md)
        .padding(.vertical, SceneViewTokens.Space.sm + SceneViewTokens.Space.xs)
        .materialGlassBackground(in: RoundedRectangle(cornerRadius: SceneViewTokens.Radius.md, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: SceneViewTokens.Radius.md, style: .continuous)
                .strokeBorder(SceneViewTokens.HomeColor.outlineSubtle, lineWidth: 0.5)
        )
        .hdPackDownloadDialog(isPresented: $confirmDownload, store: store)
        .accessibilityIdentifier("hdPackRow")
    }

    @ViewBuilder
    private var trailing: some View {
        switch state {
        case .ready:
            Button("Remove", role: .destructive) { store.remove() }
                .font(SceneViewTokens.TypeScale.captionSemibold)
                .foregroundStyle(SceneViewTokens.HomeColor.danger)
                .frame(minHeight: SceneViewTokens.Layout.touchTarget)
                .accessibilityIdentifier("hdPackRemove")
        case .downloading(let f):
            ProgressView(value: f)
                .progressViewStyle(.circular)
                .tint(SceneViewTokens.HomeColor.primary)
                .frame(minHeight: SceneViewTokens.Layout.touchTarget)
                .accessibilityLabel("Downloading")
        case .waitingForNetwork:
            ProgressView()
                .tint(SceneViewTokens.HomeColor.primary)
                .frame(minHeight: SceneViewTokens.Layout.touchTarget)
                .accessibilityLabel("Waiting for a network")
        case .waitingForWiFi, .missing, .failed:
            Button { confirmDownload = true } label: {
                Text("Download now")
                    .font(SceneViewTokens.TypeScale.captionSemibold)
                    .lineLimit(1)
                    .fixedSize()
                    .foregroundStyle(SceneViewTokens.HomeColor.onPrimary)
                    .padding(.horizontal, SceneViewTokens.Space.sm + SceneViewTokens.Space.xs)
                    .frame(minHeight: SceneViewTokens.Glass.pillHeight)
                    .background(SceneViewTokens.HomeColor.primary, in: Capsule())
            }
            .buttonStyle(PressScaleButtonStyle())
            .frame(minHeight: SceneViewTokens.Layout.touchTarget)
            .accessibilityHint("Shows the download size first")
            .accessibilityIdentifier("hdPackDownload")
        }
    }
}
