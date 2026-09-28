import SwiftUI

// MARK: - Status pill

/// "HD · downloading 34 %" — shown in a demo's status slot while its bundled
/// stand-in is on stage and the HD asset is not on disk yet.
///
/// Same metrics as ``AssetSourcePill`` (dot, `caption2` medium, glass capsule),
/// so the two read as one family. Nothing is shown once the HD asset is on
/// stage: the swap is the confirmation.
struct HDPackPill: View {
    let state: HDAssetState

    /// The pill copy. Static so the tests can pin it without SwiftUI; shared
    /// with Android's `hd_pack_pill_*` strings.
    static func label(for state: HDAssetState) -> String? {
        switch state {
        case .ready: return nil
        case .waiting: return "HD · waiting for Wi-Fi"
        case .downloading(let fraction): return "HD · downloading \(Int((fraction * 100).rounded(.down))) %"
        case .failed: return "HD · download failed"
        case .missing: return "HD · not downloaded"
        }
    }

    private var tint: Color {
        switch state {
        case .downloading: return .accentColor
        case .failed: return SceneViewTokens.HomeColor.danger
        default: return .secondary
        }
    }

    var body: some View {
        if let label = Self.label(for: state) {
            HStack(spacing: 6) {
                Circle()
                    .fill(tint)
                    .frame(width: 8, height: 8)
                Text(label)
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(.primary)
                    .monospacedDigit()
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .glassBackground(in: Capsule())
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(label)
            .accessibilityIdentifier("hdPackPill")
        }
    }
}

// MARK: - Settings row

/// "HD scenes · 52 MB · Downloaded" with its one action: Download now or Remove.
///
/// Lives on the About tab next to the other row cards. "Download now" states
/// the size before anything moves (App Review 4.2.3(ii)); on a metered network
/// it is the only way a transfer starts.
struct HDPackSettingsRow: View {
    @ObservedObject var store: HDPackStore = .shared
    @State private var confirmDownload = false

    static func sizeText(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }

    /// Second line of the row. Static so the tests can pin it.
    static func status(for state: HDAssetState, metered: Bool) -> String {
        switch state {
        case .ready: return "Downloaded"
        case .downloading(let f): return "Downloading \(Int((f * 100).rounded(.down))) %"
        case .waiting: return metered ? "Waiting for Wi-Fi" : "Waiting for network"
        case .failed: return "Download failed"
        case .missing: return "Not downloaded"
        }
    }

    private var size: String { Self.sizeText(store.totalBytes) }
    private var state: HDAssetState { store.packState }

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
                Text("HD scenes")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(SceneViewTokens.HomeColor.onSurface)
                Text("\(size) · \(Self.status(for: state, metered: store.isOnMeteredNetwork))")
                    .font(.caption)
                    .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
                    .monospacedDigit()
                    .lineLimit(1)
            }
            .accessibilityElement(children: .combine)

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
        .confirmationDialog("Download HD scenes?", isPresented: $confirmDownload, titleVisibility: .visible) {
            Button("Download \(size)") { store.downloadNow() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(store.isOnMeteredNetwork
                 ? "\(size) of high-detail 3D scenes, kept on this device. This uses your cellular data."
                 : "\(size) of high-detail 3D scenes, kept on this device.")
        }
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
        case .waiting, .missing, .failed:
            Button { confirmDownload = true } label: {
                Text("Download now")
                    .font(SceneViewTokens.TypeScale.captionSemibold)
                    .foregroundStyle(SceneViewTokens.HomeColor.onPrimary)
                    .padding(.horizontal, SceneViewTokens.Space.md)
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
