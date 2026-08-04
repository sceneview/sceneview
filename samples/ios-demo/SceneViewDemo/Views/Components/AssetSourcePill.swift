import SwiftUI

/// Compact pill telling the user where the model on screen came from.
///
/// iOS port of the Android asset-source chip (`DemoScaffold.AssetSourceChip`,
/// #1152 Stage 3 / #2936). Until now iOS had **no** cue at all, which is what
/// made #2960 a user-facing defect rather than a registry wart: on a keyless
/// build — every App Store build, since no Sketchfab key ships — 14 of the 29
/// curated slugs resolve to a bundled USDZ whose subject does not match the
/// label printed next to it, and nothing on screen said a substitution had
/// happened. A visible "Offline model" is what turns a confident wrong scene
/// into an honest stand-in (#2913).
///
/// Copy is kept **identical to Android's** (`strings.xml`
/// `demo_chip_streamed` / `demo_chip_streaming` / `demo_chip_bundled`) so the
/// two demo apps read the same in screenshots and QA transcripts.
struct AssetSourcePill: View {
    let state: AssetSourceState

    private var label: String {
        switch state {
        case .streamed: return "Streamed (cached)"
        case .streaming: return "Streaming…"
        case .bundled: return "Offline model"
        }
    }

    private var tint: Color {
        switch state {
        // Android maps these to tertiary / primary / outline. SwiftUI has no
        // Material colour roles, so this is the nearest fixed equivalent: a
        // positive accent for a real stream, the app accent while in flight,
        // and a muted grey for the stand-in — never red, because a bundled
        // model is a supported state, not an error.
        case .streamed: return .green
        case .streaming: return .accentColor
        case .bundled: return .secondary
        }
    }

    var body: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(tint)
                .frame(width: 8, height: 8)
            Text(label)
                .font(.caption2.weight(.medium))
                .foregroundStyle(.primary)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(.ultraThinMaterial, in: Capsule())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Asset source: \(label)")
        // The QA harness and the screenshot suite locate the pill by this id;
        // keep it stable.
        .accessibilityIdentifier("assetSourcePill")
    }
}

extension View {
    /// Pins an ``AssetSourcePill`` to the top-trailing corner of the scene,
    /// inside the safe area — the same corner Android uses, and the one corner
    /// no demo's own controls occupy (settings sit bottom-trailing, chips sit
    /// bottom-centre).
    ///
    /// Pass `nil` for a demo that never touches `SketchfabAssetResolver`: it
    /// has no origin question to answer and must show no pill.
    @ViewBuilder
    func assetSourcePill(_ state: AssetSourceState?) -> some View {
        if let state {
            overlay(alignment: .topTrailing) {
                AssetSourcePill(state: state)
                    .padding(.top, 12)
                    .padding(.trailing, 16)
            }
        } else {
            self
        }
    }
}

#Preview {
    VStack(spacing: 24) {
        AssetSourcePill(state: .streamed)
        AssetSourcePill(state: .streaming)
        AssetSourcePill(state: .bundled)
    }
    .padding()
    .background(Color.black)
}
