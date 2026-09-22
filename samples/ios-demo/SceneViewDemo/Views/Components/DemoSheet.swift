import SwiftUI
import SceneViewSwift

/// `.demoChrome` — the modifier form of ``DemoScaffold``.
///
/// The scaffold is the single implementation of the demo chrome; this wraps
/// the modified view as its `stage`. It exists so a screen that is already a
/// finished scene can adopt the chrome in one line:
///
/// ```swift
/// SceneView { ... }
///     .demoChrome(
///         title: "Model Viewer",
///         dock: [DockItem(icon: "scope", label: "Recenter") { recenter() }],
///         accent: DockItem(icon: "arkit", label: "View in AR") { openAR() }
///     ) {
///         // any SwiftUI controls — sliders, pickers, toggles…
///     }
/// ```
///
/// The `accessory` (option strip, hint, legend) and `status` (asset-source
/// pill) slots are the scaffold's, so a finished scene gets the same bottom
/// cluster and identity row as a scene built on ``DemoScaffold`` directly.
public struct DemoChromeModifier<Accessory: View, Status: View, Controls: View>: ViewModifier {
    let title: String?
    let dock: [DockItem]
    let accent: DockItem?
    let onReset: (() -> Void)?
    let chromeMode: DemoChromeMode
    let accessory: () -> Accessory
    let status: () -> Status
    let controls: () -> Controls

    public func body(content: Content) -> some View {
        DemoScaffold(title, dock: dock, accent: accent, onReset: onReset,
                     chromeMode: chromeMode, stage: { content }, accessory: accessory,
                     status: status, controls: controls)
    }
}

/// The demo title the presenter knows (`DemoItem.title`), read by
/// ``DemoScaffold`` when the call site passes no explicit title.
private struct DemoTitleKey: EnvironmentKey {
    static let defaultValue: String? = nil
}

extension EnvironmentValues {
    var demoTitle: String? {
        get { self[DemoTitleKey.self] }
        set { self[DemoTitleKey.self] = newValue }
    }
}

// MARK: - AR stage without a camera

/// What an AR demo shows when there is no camera to draw on: the simulator.
///
/// Theme-independent, exactly like the AR chrome that floats over it. Before
/// this, each demo hand-rolled the same stack with `.secondary` text on
/// `systemGroupedBackground` — light grey on near-white, which the audit
/// captures caught as unreadable in light mode, and which put an ordinary app
/// surface under chrome designed for a camera frame. The ground is the same
/// deep gradient the AR tab already used.
struct ARUnavailableStage: View {
    /// SF Symbol naming the capability the demo would have shown.
    let icon: String
    /// One sentence: what a real device would do here.
    let message: String

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [
                    Color(red: 0.10, green: 0.10, blue: 0.18),
                    Color(red: 0.18, green: 0.18, blue: 0.28),
                ],
                startPoint: .top, endPoint: .bottom
            )
            VStack(spacing: SceneViewTokens.Space.md) {
                Image(systemName: icon)
                    .font(.system(size: 60))
                    .foregroundStyle(SceneViewTokens.ARChrome.onScrimDim)
                    .accessibilityHidden(true)
                Text("AR requires a physical device")
                    .font(.headline)
                    .foregroundStyle(SceneViewTokens.ARChrome.onScrim)
                Text(message)
                    .font(.caption)
                    .foregroundStyle(SceneViewTokens.ARChrome.onScrimDim)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, SceneViewTokens.Space.xl)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// MARK: - Glass primitives

// The glass itself is `View.glassBackground(in:)` (Theme.swift) — the one
// implementation. A free function of the same name used to live here; inside a
// `View` the member wins overload resolution, so `.background(glassBackground(in:))`
// resolved to `self.glassBackground(in:)`, drew the view as its own background
// and overflowed the stack on the first frame of every `.demoChrome` screen.

/// 44 pt glass circle carrying its content.
struct GlassCircle<Content: View>: View {
    @ViewBuilder let content: () -> Content

    var body: some View {
        content()
            .frame(width: SceneViewTokens.Glass.iconButtonSize, height: SceneViewTokens.Glass.iconButtonSize)
            .glassBackground(in: Circle())
            .frame(width: SceneViewTokens.Layout.touchTarget, height: SceneViewTokens.Layout.touchTarget)
            .contentShape(Circle())
    }
}

/// 44 pt glass circle with a white icon inside a 48 pt touch target.
struct GlassIconButton: View {
    let icon: String
    let label: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            GlassCircle {
                Image(systemName: icon)
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(SceneViewTokens.Glass.onGlass)
            }
        }
        .buttonStyle(PressScaleButtonStyle(scale: SceneViewTokens.Spring.chromePressScale))
        .accessibilityLabel(label)
    }
}

/// Glass pill, 36 pt tall at the default text size, 14 pt horizontal padding — the identity pill and
/// any other short, read-only label floating over the scene.
struct GlassPill<Content: View>: View {
    @ViewBuilder let content: () -> Content

    var body: some View {
        HStack(spacing: SceneViewTokens.Space.xs) { content() }
            .padding(.horizontal, SceneViewTokens.Glass.pillPaddingHorizontal)
            .frame(minHeight: SceneViewTokens.Glass.pillHeight)
            .glassBackground(in: Capsule())
    }
}

public extension View {
    /// Wraps the scene in ``DemoScaffold``: back button, identity pill and the
    /// floating dock whose Settings item opens `controls` in a detent sheet.
    func demoChrome<Accessory: View, Status: View, Controls: View>(
        title: String? = nil,
        dock: [DockItem] = [],
        accent: DockItem? = nil,
        onReset: (() -> Void)? = nil,
        chromeMode: DemoChromeMode = .stage,
        @ViewBuilder accessory: @escaping () -> Accessory = { EmptyView() },
        @ViewBuilder status: @escaping () -> Status = { EmptyView() },
        @ViewBuilder controls: @escaping () -> Controls
    ) -> some View {
        modifier(DemoChromeModifier(title: title, dock: dock, accent: accent, onReset: onReset,
                                    chromeMode: chromeMode, accessory: accessory, status: status,
                                    controls: controls))
    }

    /// ``DemoScaffold`` with no controls of the demo's own — the sheet still
    /// carries Reset, Send feedback and QA mode.
    ///
    /// Disfavoured so that `.demoChrome(accessory: { … }) { controls }` keeps
    /// binding its trailing closure to `controls` above: with both overloads
    /// viable, the ranking otherwise preferred this one (no default used) and
    /// the sheet content landed in the identity row's `status` slot.
    @_disfavoredOverload
    func demoChrome<Accessory: View, Status: View>(
        title: String? = nil,
        dock: [DockItem] = [],
        accent: DockItem? = nil,
        onReset: (() -> Void)? = nil,
        chromeMode: DemoChromeMode = .stage,
        @ViewBuilder accessory: @escaping () -> Accessory = { EmptyView() },
        @ViewBuilder status: @escaping () -> Status = { EmptyView() }
    ) -> some View {
        modifier(DemoChromeModifier(title: title, dock: dock, accent: accent, onReset: onReset,
                                    chromeMode: chromeMode, accessory: accessory, status: status,
                                    controls: { EmptyView() }))
    }
}
