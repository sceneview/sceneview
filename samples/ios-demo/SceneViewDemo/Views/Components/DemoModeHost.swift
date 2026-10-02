import SwiftUI
import SceneViewSwift

/// One mode of an umbrella card: a screen that used to be a card of its own and
/// is now reached from the card that absorbed it (samples audit, step 0).
///
/// `id` is the mode's name in a `?tab=` deep link — the same lower-case token
/// Android's `DeepLinkRouter` uses (`wall`, `free-pose`, `pendulum`). `aliases`
/// lists every other token that should land here: the retired card id
/// (`wall-placement`) and, only where the order matches Android's segmented
/// modes, the 0-based index (`"1"`), so `sceneview://demo/ar-placement?tab=1`
/// opens the same mode on both platforms.
struct DemoMode: Identifiable {
    let id: String
    let title: String
    var aliases: Set<String> = []
    let content: () -> AnyView

    init(_ id: String, title: String, aliases: Set<String> = [],
         @ViewBuilder content: @escaping () -> some View) {
        self.id = id
        self.title = title
        self.aliases = aliases
        self.content = { AnyView(content()) }
    }

    func matches(_ token: String) -> Bool {
        token == id || aliases.contains(token)
    }
}

/// An umbrella card: the screens it absorbed, one at a time, switched by the
/// mode pill (`DESIGN.md` `mode-pill-*`) that ``DemoScaffold`` draws above the
/// accessory.
///
/// Step 0 of the samples consolidation is a regroup, not a redesign: each mode
/// is the screen that already shipped, untouched, with its own stage and dock.
/// The host only chooses which one is on screen, and hands the pill to that
/// screen's scaffold through the environment so no child has to know it is a
/// mode.
///
/// The first mode opens unless the link asked for another (`?tab=`, or a
/// retired id re-keyed by ``DemoDeepLinkRegistry/routeTab(for:)``).
struct DemoModeHost: View {
    let demoId: String
    let modes: [DemoMode]

    @State private var selection: String

    init(demoId: String, modes: [DemoMode]) {
        precondition(!modes.isEmpty, "An umbrella card needs at least one mode")
        self.demoId = demoId
        self.modes = modes
        let requested = DeepLinkRouter.consumeTab(for: demoId)
        let initial = requested.flatMap { token in modes.first { $0.matches(token) } } ?? modes[0]
        _selection = State(initialValue: initial.id)
    }

    private var current: DemoMode {
        modes.first { $0.id == selection } ?? modes[0]
    }

    var body: some View {
        current.content()
            // A mode is a whole screen: a fresh stage and fresh state, never the
            // previous mode's camera or placement carried over.
            .id(current.id)
            .environment(\.demoModePicker, DemoModePickerModel(
                label: "Mode",
                options: modes.map { ($0.id, $0.title) },
                selection: $selection
            ))
            // The tab is normally taken in `init`, so the first frame is
            // already the asked-for mode. A link that lands after this host
            // was built (a cover re-presented) is taken here instead.
            .onAppear {
                if let token = DeepLinkRouter.consumeTab(for: demoId),
                   let mode = modes.first(where: { $0.matches(token) }) {
                    selection = mode.id
                }
            }
    }
}

/// What ``DemoScaffold`` needs to draw an umbrella card's mode pill.
struct DemoModePickerModel {
    let label: String
    let options: [(id: String, title: String)]
    let selection: Binding<String>
}

private struct DemoModePickerKey: EnvironmentKey {
    static let defaultValue: DemoModePickerModel? = nil
}

extension EnvironmentValues {
    /// The mode pill of the umbrella card around this screen, if any. Read by
    /// ``DemoScaffold``, which clears it for everything it presents so a sheet
    /// or a nested scene never draws a second pill.
    var demoModePicker: DemoModePickerModel? {
        get { self[DemoModePickerKey.self] }
        set { self[DemoModePickerKey.self] = newValue }
    }
}

/// The umbrella card's mode pill: one segment per mode, above the dock. Same
/// tokens and geometry as Cosmos's Starlight / Spacetime pill
/// (`SpacetimeModePicker`), generalised to any number of modes.
struct DemoModePicker: View {
    let model: DemoModePickerModel
    @Environment(\.analyticsSampleId) private var analyticsSampleId

    private typealias Pill = SceneViewTokens.ModePill

    var body: some View {
        HStack(spacing: 0) {
            ForEach(model.options, id: \.id) { option in
                segment(option.title, id: option.id, selected: option.id == model.selection.wrappedValue)
            }
        }
        .padding(.horizontal, SceneViewTokens.Space.xs)
        .background(Capsule().fill(Pill.container))
        .overlay(Capsule().strokeBorder(Pill.outline, lineWidth: Pill.outlineWidth))
        .fixedSize()
        .accessibilityElement(children: .contain)
        .accessibilityLabel(model.label)
        .accessibilityIdentifier("demo-mode-picker")
    }

    private func segment(_ title: String, id: String, selected: Bool) -> some View {
        Button {
            guard !selected else { return }
            // `sample_interaction(control = "mode_<x>")`, the event Android logs
            // for an umbrella card's mode change.
            if let analyticsSampleId { DemoAnalytics.shared.interaction(analyticsSampleId, "mode_\(id)") }
            SceneViewHaptic.shared.selection()
            model.selection.wrappedValue = id
        } label: {
            Text(title)
                .font(SceneViewTokens.TypeScale.chromeCaption.weight(.semibold))
                .foregroundStyle(selected ? Pill.onSelected : Pill.onContainer)
                .lineLimit(1)
                .padding(.horizontal, SceneViewTokens.Space.md)
                .frame(height: Pill.segmentHeight)
                .background(Capsule().fill(selected ? Pill.selectedContainer : .clear))
                .frame(minHeight: SceneViewTokens.Layout.touchTarget)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier("demo-mode-\(id)")
    }
}
