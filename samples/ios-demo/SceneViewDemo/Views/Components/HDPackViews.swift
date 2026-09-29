import SwiftUI

// Copy shared with Android (#4146) word for word; only the size differs, since
// each platform ships its own file (USDZ here, GLB there).

// MARK: - Status pill

/// "HD · downloading 34 %" — shown in a demo's status slot while its bundled
/// stand-in is on stage and the HD asset is not there yet.
///
/// Same family as ``AssetSourcePill`` (dot, `caption2` medium, glass capsule).
/// Nothing is shown once the HD asset is on stage: the swap is the
/// confirmation. "Download 52 MB" and "download failed" are tappable: they
/// open the same size-first dialog as the About row.
struct HDPackPill: View {
    let state: HDAssetState
    /// The HD file is on disk and being loaded onto the stage.
    var loading = false
    var bytes: Int64 = 0
    var onDownload: (() -> Void)?

    /// The pill copy. Static so the tests can pin it without SwiftUI.
    static func label(for state: HDAssetState, loading: Bool = false, bytes: Int64) -> String? {
        if loading { return "HD · loading" }
        switch state {
        case .ready: return nil
        case .waitingForWiFi: return "HD · waiting for Wi-Fi"
        case .waitingForNetwork: return "HD · waiting for a network"
        case .downloading(let fraction): return "HD · downloading \(Int((fraction * 100).rounded(.down))) %"
        case .failed: return "HD · download failed"
        case .missing: return "HD · download \(HDPackFormat.size(bytes))"
        }
    }

    private var isTappable: Bool {
        guard !loading, onDownload != nil else { return false }
        return state == .missing || state == .failed
    }

    private var tint: Color {
        if loading { return .accentColor }
        switch state {
        case .downloading: return .accentColor
        case .failed: return SceneViewTokens.HomeColor.danger
        default: return .secondary
        }
    }

    var body: some View {
        if let label = Self.label(for: state, loading: loading, bytes: bytes) {
            let pill = HStack(spacing: SceneViewTokens.Space.xs) {
                Circle()
                    .fill(tint)
                    .frame(width: SceneViewTokens.Space.sm, height: SceneViewTokens.Space.sm)
                Text(label)
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(.primary)
                    .monospacedDigit()
                if isTappable {
                    Image(systemName: state == .failed ? "arrow.clockwise" : "arrow.down.circle")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(.tint)
                }
            }
            .padding(.horizontal, SceneViewTokens.Space.sm)
            .padding(.vertical, SceneViewTokens.Space.xs)
            .glassBackground(in: Capsule())

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

// MARK: - Download dialog

extension View {
    /// "Download HD scenes?" — states the size before anything moves (App
    /// Review 4.2.3(ii)). Mentions mobile data only on an expensive network;
    /// Low Data Mode alone is not a data plan.
    func hdPackDownloadDialog(isPresented: Binding<Bool>, store: HDPackStore = .shared) -> some View {
        modifier(HDPackDownloadDialog(isPresented: isPresented, store: store))
    }
}

struct HDPackDownloadDialog: ViewModifier {
    @Binding var isPresented: Bool
    @ObservedObject var store: HDPackStore

    static func message(bytes: Int64, expensive: Bool) -> String {
        let base = "Full-resolution models, \(HDPackFormat.size(bytes)). They stay on this device until you remove them in About."
        return expensive ? base + " This uses mobile data." : base
    }

    func body(content: Content) -> some View {
        content.confirmationDialog("Download HD scenes?", isPresented: $isPresented, titleVisibility: .visible) {
            Button("Download") { store.downloadNow() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(Self.message(bytes: store.totalBytes, expensive: store.isExpensive))
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
                Text("HD scenes · \(HDPackFormat.size(store.totalBytes))")
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
