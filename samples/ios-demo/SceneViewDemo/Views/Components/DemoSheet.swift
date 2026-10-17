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
///         dock: [DockItem(icon: "arrow.counterclockwise.circle", label: "Recenter") { recenter() }],
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

// MARK: - Controls sheet cover

/// How much of the stage's bottom the controls sheet covers, in points — `0`
/// while it is closed. ``DemoScaffold`` publishes it to the stage, which hands
/// it to `SceneView.contentInsets(_:)` so the subject stays in what is left.
private struct DemoControlsCoverKey: EnvironmentKey {
    static let defaultValue: CGFloat = 0
}

extension EnvironmentValues {
    var demoControlsCover: CGFloat {
        get { self[DemoControlsCoverKey.self] }
        set { self[DemoControlsCoverKey.self] = newValue }
    }
}

/// Hands its content the height the controls sheet covers.
///
/// A view of its own because the value is set by ``DemoScaffold`` on the
/// stage: the demo that *applies* `demoChrome` sits outside it and cannot read
/// it from its own environment.
struct DemoControlsCover<Content: View>: View {
    /// The spring a system sheet rises on, near enough that the subject and
    /// the sheet read as one motion. Not a `SceneViewTokens` value on purpose:
    /// it shadows UIKit's sheet transition, which the design system does not
    /// set — `Spring.animation` (0.35 s) lands the subject before the sheet.
    static var animation: Animation { .spring(duration: 0.5, bounce: 0) }

    @Environment(\.demoControlsCover) private var cover
    @ViewBuilder let content: (CGFloat) -> Content

    var body: some View {
        content(cover)
    }
}

/// Where the system puts a sheet, which decides whether it covers the stage's
/// bottom at all.
enum DemoSheetPlacement {
    /// Whether a sheet presented in this size class is attached to the bottom
    /// edge, so that its height is what it hides of the stage.
    ///
    /// True at compact width — iPhone, a narrow iPad window. At regular width
    /// it is true from iPadOS 27, where a sheet with detents was captured
    /// resting on the bottom edge; earlier systems were not observed and may
    /// centre it as a form sheet, so nothing is inset there. macOS shows a
    /// sheet as a window-modal panel that leaves no rectangle above it.
    static func coversBottom(_ horizontalSizeClass: UserInterfaceSizeClass?) -> Bool {
        #if os(iOS)
        if horizontalSizeClass == .compact { return true }
        if #available(iOS 27.0, *) { return true }
        return false
        #else
        return false
        #endif
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
            .glassBackground(in: Circle(), interactive: true)
            .frame(width: SceneViewTokens.Layout.touchTarget, height: SceneViewTokens.Layout.touchTarget)
            .contentShape(Circle())
    }
}

/// 44 pt glass circle with a white icon inside a 48 pt touch target.
struct GlassIconButton: View {
    let icon: String
    let label: String
    let action: () -> Void

    @Environment(\.themedDemoChrome) private var themed
    @State private var taps = 0

    var body: some View {
        Button {
            taps += 1
            action()
        } label: {
            GlassCircle {
                Image(systemName: icon)
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(themed ? SceneViewTokens.RoomScan.text : SceneViewTokens.Glass.onGlass)
            }
        }
        .buttonStyle(PressScaleButtonStyle(scale: SceneViewTokens.Spring.chromePressScale))
        .sensoryFeedback(.impact(weight: .light), trigger: taps)
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
