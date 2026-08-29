import SwiftUI
import SceneViewSwift

/// iOS counterpart of Android's `DemoScaffold` glass chrome (`DESIGN.md`
/// "Glass Chrome over Media" + "Floating Dock").
///
/// The chrome floats over a live RealityKit / ARKit viewport, which is media,
/// not a themed surface — so it is theme-independent: white on an 8 % white
/// fill with a 1 pt 8 % white border. Unlike Android (a `SurfaceView` cannot
/// be sampled) iOS can blur what is underneath, so every glass element also
/// sits on `.ultraThinMaterial`; the geometry is identical on both platforms.
///
/// Usage:
/// ```swift
/// var body: some View {
///     SceneView { ... }
///         .demoChrome(
///             title: "Model Viewer",
///             dock: [DockItem(icon: "scope", label: "Recenter") { recenter() }],
///             accent: DockItem(icon: "arkit", label: "View in AR") { openAR() }
///         ) {
///             // any SwiftUI controls — sliders, pickers, toggles…
///         }
/// }
/// ```
///
/// Anatomy:
/// - **Top row:** glass back button (44 pt circle, `demo-close`) · identity
///   pill (36 pt, `type-caption` semibold) with the demo title — the one passed
///   in, else the presenter's `\.demoTitle` environment value · overflow menu
///   (Reset when `onReset` is given, Feedback, QA mode).
/// - **Bottom dock:** a 64 pt glass capsule with at most four demo `dock`
///   items, the auto-appended Controls item (`demo-settings-fab` — Maestro and
///   the UI tests key on it) that opens the controls sheet, and an optional
///   `accent` rendered as a 48 pt filled primary circle.
/// - **Controls sheet:** the same `.fraction(0.25)` / `.medium` / `.large`
///   detents as before, background interaction enabled so AR keeps tracking.
///
/// The scene itself is edge-to-edge; the presenter's navigation bar is hidden
/// because the chrome carries its own back button.
public struct DockItem: Identifiable {
    public let id = UUID()
    public let icon: String
    public let label: String
    public var enabled: Bool
    public var selected: Bool
    public let action: () -> Void

    public init(icon: String, label: String, enabled: Bool = true, selected: Bool = false,
                action: @escaping () -> Void) {
        self.icon = icon
        self.label = label
        self.enabled = enabled
        self.selected = selected
        self.action = action
    }
}

/// The demo title the presenter knows (`DemoItem.title`), read by
/// `.demoChrome` when the call site passes no explicit title.
private struct DemoTitleKey: EnvironmentKey {
    static let defaultValue: String? = nil
}

extension EnvironmentValues {
    var demoTitle: String? {
        get { self[DemoTitleKey.self] }
        set { self[DemoTitleKey.self] = newValue }
    }
}

public struct DemoChromeModifier<Controls: View>: ViewModifier {
    private let title: String?
    private let dock: [DockItem]
    private let accent: DockItem?
    private let onReset: (() -> Void)?
    private let hasControls: Bool
    private let controls: () -> Controls

    @State private var controlsPresented = false
    @Environment(\.dismiss) private var dismiss
    @Environment(\.demoTitle) private var presenterTitle
    @Environment(\.openURL) private var openURL
    @AppStorage(DeepLinkRouter.qaModeDefaultsKey) private var qaMode: Bool = false

    init(title: String?, dock: [DockItem], accent: DockItem?, onReset: (() -> Void)?,
         hasControls: Bool, @ViewBuilder controls: @escaping () -> Controls) {
        self.title = title
        self.dock = dock
        self.accent = accent
        self.onReset = onReset
        self.hasControls = hasControls
        self.controls = controls
    }

    private var resolvedTitle: String? { title ?? presenterTitle }

    public func body(content: Content) -> some View {
        content
            .ignoresSafeArea()
            .toolbar(.hidden, for: .navigationBar)
            .overlay(alignment: .top) { identityRow }
            .overlay(alignment: .bottom) { dockView }
            .sheet(isPresented: $controlsPresented) {
                DemoSettingsContainer { controls() }
                    .presentationDetents([.fraction(0.25), .medium, .large])
                    .presentationDragIndicator(.visible)
                    #if os(iOS)
                    .presentationBackgroundInteraction(.enabled)
                    .presentationContentInteraction(.scrolls)
                    .presentationBackground(.ultraThinMaterial)
                    .presentationCornerRadius(SceneViewTokens.Radius.xl)
                    #endif
            }
    }

    // MARK: Top row

    private var identityRow: some View {
        HStack(spacing: SceneViewTokens.Space.sm) {
            GlassIconButton(icon: "chevron.left", label: "Close demo") {
                #if os(iOS)
                SceneViewHaptic.shared.light()
                #endif
                dismiss()
            }
            .accessibilityIdentifier("demo-close")

            if let resolvedTitle {
                GlassPill {
                    Text(resolvedTitle)
                        .font(SceneViewTokens.TypeScale.captionSemibold)
                        .foregroundStyle(SceneViewTokens.Glass.onGlass)
                        .lineLimit(1)
                    // The chip is a human's escape hatch out of QA mode. A
                    // scripted pass has no human and its frames ship to the
                    // App Store, so it must not be baked in (#3384).
                    if qaMode && !DeepLinkRouter.isScriptedCapture {
                        Text(" QA ×")
                            .font(SceneViewTokens.TypeScale.caption)
                            .foregroundStyle(SceneViewTokens.Glass.onGlassMuted)
                            .onTapGesture { qaMode = false }
                            .accessibilityLabel("Disable QA mode")
                    }
                }
            }

            Spacer(minLength: 0)

            Menu {
                if let onReset {
                    Button { onReset() } label: { Label("Reset", systemImage: "arrow.counterclockwise") }
                }
                Button {
                    if let url = URL(string: "https://github.com/SceneView/sceneview/issues/new/choose") {
                        openURL(url)
                    }
                } label: { Label("Feedback", systemImage: "exclamationmark.bubble") }
                Toggle(isOn: $qaMode) { Label("QA mode", systemImage: "flask") }
            } label: {
                GlassCircle {
                    Image(systemName: "ellipsis")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(SceneViewTokens.Glass.onGlass)
                }
            }
            .accessibilityLabel("More options")
            .accessibilityIdentifier("demo-overflow")
        }
        .padding(.horizontal, SceneViewTokens.Space.md)
        .padding(.top, SceneViewTokens.Space.sm)
    }

    // MARK: Dock

    private var dockView: some View {
        let items = Array(dock.prefix(SceneViewTokens.Layout.dockMaxItems))
        return Group {
            if !items.isEmpty || accent != nil || hasControls {
                HStack(spacing: SceneViewTokens.Space.xs) {
                    ForEach(items) { item in
                        DockButton(item: item)
                    }
                    if hasControls {
                        DockButton(item: DockItem(icon: "slider.horizontal.3", label: "Demo settings",
                                                  selected: controlsPresented) {
                            #if os(iOS)
                            SceneViewHaptic.shared.selection()
                            #endif
                            controlsPresented = true
                        })
                        .accessibilityIdentifier("demo-settings-fab")
                    }
                    if let accent {
                        Button(action: accent.action) {
                            Image(systemName: accent.icon)
                                .font(.system(size: SceneViewTokens.Layout.dockIconSize, weight: .medium))
                                .foregroundStyle(.white)
                                .frame(width: SceneViewTokens.Layout.touchTarget,
                                       height: SceneViewTokens.Layout.touchTarget)
                                .background(SceneViewTheme.primary.opacity(accent.enabled ? 1 : 0.38),
                                            in: Circle())
                        }
                        .buttonStyle(PressScaleButtonStyle(scale: SceneViewTokens.Spring.chromePressScale))
                        .disabled(!accent.enabled)
                        .accessibilityLabel(accent.label)
                        .accessibilityIdentifier("demo-dock-accent")
                    }
                }
                .padding(.horizontal, SceneViewTokens.Space.sm)
                .frame(height: SceneViewTokens.Layout.dockHeight)
                .background(glassBackground(in: Capsule()))
                .padding(.bottom, SceneViewTokens.Space.md)
                .accessibilityIdentifier("demo-dock")
            }
        }
    }
}

private struct DockButton: View {
    let item: DockItem

    var body: some View {
        Button(action: item.action) {
            Image(systemName: item.icon)
                .font(.system(size: SceneViewTokens.Layout.dockIconSize, weight: .medium))
                .foregroundStyle(
                    !item.enabled ? SceneViewTokens.Glass.onGlassDisabled
                        : item.selected ? SceneViewTheme.primary
                        : SceneViewTokens.Glass.onGlass
                )
                .frame(width: SceneViewTokens.Layout.touchTarget, height: SceneViewTokens.Layout.touchTarget)
                .contentShape(Circle())
        }
        .buttonStyle(PressScaleButtonStyle(scale: SceneViewTokens.Spring.chromePressScale))
        .disabled(!item.enabled)
        .accessibilityLabel(item.label)
    }
}

// MARK: - Glass primitives

/// `glass-surface` + `glass-border` on a blur, in the given shape.
func glassBackground<S: InsettableShape>(in shape: S) -> some View {
    shape
        .fill(.ultraThinMaterial)
        .overlay(shape.fill(SceneViewTokens.Glass.surface))
        .overlay(shape.strokeBorder(SceneViewTokens.Glass.border, lineWidth: SceneViewTokens.Glass.borderWidth))
}

/// 44 pt glass circle carrying its content.
struct GlassCircle<Content: View>: View {
    @ViewBuilder let content: () -> Content

    var body: some View {
        content()
            .frame(width: SceneViewTokens.Glass.iconButtonSize, height: SceneViewTokens.Glass.iconButtonSize)
            .background(glassBackground(in: Circle()))
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

/// 36 pt tall glass pill with 14 pt horizontal padding — the identity pill and
/// any other short, read-only label floating over the scene.
struct GlassPill<Content: View>: View {
    @ViewBuilder let content: () -> Content

    var body: some View {
        HStack(spacing: SceneViewTokens.Space.xs) { content() }
            .padding(.horizontal, SceneViewTokens.Glass.pillPaddingHorizontal)
            .frame(height: SceneViewTokens.Glass.pillHeight)
            .background(glassBackground(in: Capsule()))
    }
}

/// Padded, scrollable container so the sheet content survives long control
/// stacks (mirrors Android `Column { verticalScroll(...) }` inside the sheet).
public struct DemoSettingsContainer<Content: View>: View {
    let content: () -> Content

    public init(@ViewBuilder content: @escaping () -> Content) {
        self.content = content
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                content()
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
            .padding(.top, 8)
        }
    }
}

public extension View {
    /// Wraps the scene in the SceneView demo glass chrome: back button,
    /// identity pill, overflow menu and the floating dock whose Controls item
    /// opens `controls` in a detent sheet.
    func demoChrome<Controls: View>(
        title: String? = nil,
        dock: [DockItem] = [],
        accent: DockItem? = nil,
        onReset: (() -> Void)? = nil,
        @ViewBuilder controls: @escaping () -> Controls
    ) -> some View {
        modifier(DemoChromeModifier(title: title, dock: dock, accent: accent, onReset: onReset,
                                    hasControls: true, controls: controls))
    }

    /// Glass chrome without a controls sheet (no Controls dock item).
    func demoChrome(
        title: String? = nil,
        dock: [DockItem] = [],
        accent: DockItem? = nil,
        onReset: (() -> Void)? = nil
    ) -> some View {
        modifier(DemoChromeModifier(title: title, dock: dock, accent: accent, onReset: onReset,
                                    hasControls: false, controls: { EmptyView() }))
    }
}
