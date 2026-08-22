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
    /// `true` when the slug on screen declares `fallbackRole == .placeholder`
    /// — its bundled stand-in is a different subject than the label (#2960).
    /// Only changes the ``AssetSourceState/bundled`` wording: a streamed model
    /// is never a placeholder, whatever its fallback would have been.
    var isPlaceholder: Bool = false

    /// The pill copy. Static so the tests can pin it without SwiftUI.
    static func label(state: AssetSourceState, isPlaceholder: Bool) -> String {
        switch state {
        case .streamed: return "Streamed (cached)"
        case .streaming: return "Streaming…"
        case .bundled: return isPlaceholder ? "Offline placeholder" : "Offline model"
        }
    }

    private var label: String { Self.label(state: state, isPlaceholder: isPlaceholder) }

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
    ///
    /// The overlay is applied unconditionally and the *pill* is what the `nil`
    /// case drops. Branching on `if let state { overlay(…) } else { self }`
    /// instead handed SwiftUI two structurally different views, so the first
    /// time a demo went from no-pill to pill — `AnimationDemo` moving off its
    /// bundled slot 0 — the whole modified subtree was discarded and rebuilt,
    /// taking the scene's `RealityView` with it. That is the same teardown
    /// `.contentID(_:)` exists to avoid, and it was measured re-creating the
    /// scene on exactly the first subject change and no other (#3008).
    func assetSourcePill(_ state: AssetSourceState?, placeholder: Bool = false) -> some View {
        overlay(alignment: .topTrailing) {
            if let state {
                AssetSourcePill(state: state, isPlaceholder: placeholder)
                    .padding(.top, 12)
                    .padding(.trailing, 16)
            }
        }
    }
}

#Preview {
    VStack(spacing: 24) {
        AssetSourcePill(state: .streamed)
        AssetSourcePill(state: .streaming)
        AssetSourcePill(state: .bundled)
        AssetSourcePill(state: .bundled, isPlaceholder: true)
    }
    .padding()
    .background(Color.black)
}
