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
/// New demos should build on ``DemoScaffold`` directly: it also takes the
/// `accessory` slot (option strip, hint) that a modifier cannot express well.
public struct DemoChromeModifier<Controls: View>: ViewModifier {
    let title: String?
    let dock: [DockItem]
    let accent: DockItem?
    let onReset: (() -> Void)?
    let controls: () -> Controls

    public func body(content: Content) -> some View {
        DemoScaffold(title, dock: dock, accent: accent, onReset: onReset,
                     stage: { content }, controls: controls)
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
    func demoChrome<Controls: View>(
        title: String? = nil,
        dock: [DockItem] = [],
        accent: DockItem? = nil,
        onReset: (() -> Void)? = nil,
        @ViewBuilder controls: @escaping () -> Controls
    ) -> some View {
        modifier(DemoChromeModifier(title: title, dock: dock, accent: accent, onReset: onReset,
                                    controls: controls))
    }

    /// ``DemoScaffold`` with no controls of the demo's own — the sheet still
    /// carries Reset, Send feedback and QA mode.
    func demoChrome(
        title: String? = nil,
        dock: [DockItem] = [],
        accent: DockItem? = nil,
        onReset: (() -> Void)? = nil
    ) -> some View {
        modifier(DemoChromeModifier(title: title, dock: dock, accent: accent, onReset: onReset,
                                    controls: { EmptyView() }))
    }
}
