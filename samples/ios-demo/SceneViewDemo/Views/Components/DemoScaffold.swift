import SwiftUI
import SceneViewSwift

/// The one screen every demo is built on — iOS twin of Android's
/// `DemoScaffold` (`DESIGN.md` "Demo Scaffold (iOS)").
///
/// A demo hands over a full-bleed 3D `stage` and, optionally, one `accessory`
/// (an option strip, a hint) and the `controls` of its settings sheet. The
/// scaffold owns everything else, **once**:
///
/// - the glass back button and identity pill, under the status bar / Dynamic Island;
/// - the floating dock, 8 pt above the home-indicator safe area (16 pt from the
///   edge on a Home-button iPhone) — a demo never pads its own bottom;
/// - one 16 pt horizontal margin for every block;
/// - the scrim bands that keep white chrome legible over any scene — except in
///   ``DemoChromeMode/ar``, where the stage is a camera feed and each control
///   carries its own `ar-scrim` ground instead;
/// - the settings sheet (detents, themed surface, keyboard, Reset / feedback / QA);
/// - Dynamic Type (chrome capped at XXL, the sheet scales freely), VoiceOver
///   order (back → title → scene → accessory → dock) and Reduce Motion.
///
/// ```swift
/// struct TextDemo: View {
///     @State private var depth = Depth.deep
///
///     var body: some View {
///         DemoScaffold("3D Text") {
///             SceneView { root in /* … the SDK code the demo is about … */ }
///                 .cameraControls(.orbit)
///         } accessory: {
///             DemoOptionStrip(Depth.allCases, selection: $depth) { $0.rawValue }
///         }
///     }
/// }
/// ```
///
/// Motion (all `DESIGN.md` tokens, all ≤ 350 ms): the stage fades in on
/// `motion-fade`, the top row drops and the bottom cluster rises on
/// `motion-spring`, an option change slides the selection on `motion-spring`.
/// Under Reduce Motion nothing translates or scales — the fades stay.
/// What the scaffold's stage actually is, and therefore how the chrome grounds
/// itself.
public enum DemoChromeMode {
    /// A 3D scene the app renders. The scrim bands apply: the scene can be any
    /// brightness, and white chrome has to read over all of them.
    case stage
    /// A live camera feed. No bands — darkening 160 pt of sky and 220 pt of
    /// floor dims the one thing the user pointed the phone at, and it does so
    /// permanently, on every AR screen. Controls get an `ar-scrim` ground the
    /// size of themselves instead.
    case ar
}

public struct DemoScaffold<Stage: View, Accessory: View, Status: View, Controls: View>: View {
    private let title: String?
    private let dock: [DockItem]
    private let accent: DockItem?
    private let onReset: (() -> Void)?
    private let hasControls: Bool
    private let chromeMode: DemoChromeMode
    private let stage: Stage
    private let accessory: Accessory
    /// Trailing end of the identity row — a small state pill (asset source,
    /// tracking state). It sits in the row so it clears the status bar and the
    /// Dynamic Island exactly as the title does, instead of each demo overlaying
    /// it at the top edge of the stage.
    private let status: Status
    private let controls: Controls

    @State private var controlsPresented = false
    @State private var entered = false
    @State private var accentTaps = 0
    /// Shapes of the bottom cluster (accessory + dock) morph within this
    /// namespace on iOS 26+ instead of cross-fading.
    @Namespace private var glassSpace
    @Environment(\.dismiss) private var dismiss
    @Environment(\.demoTitle) private var presenterTitle
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// Read here, outside the chrome's pinned dark scheme, so the AR ground
    /// resolves against the user's real appearance as `DESIGN.md` specifies.
    @Environment(\.colorScheme) private var colorScheme
    @AppStorage(DeepLinkRouter.qaModeDefaultsKey) private var qaMode: Bool = false

    public init(
        _ title: String? = nil,
        dock: [DockItem] = [],
        accent: DockItem? = nil,
        onReset: (() -> Void)? = nil,
        chromeMode: DemoChromeMode = .stage,
        @ViewBuilder stage: () -> Stage,
        @ViewBuilder accessory: () -> Accessory = { EmptyView() },
        @ViewBuilder status: () -> Status = { EmptyView() },
        @ViewBuilder controls: () -> Controls = { EmptyView() }
    ) {
        self.title = title
        self.dock = dock
        self.accent = accent
        self.onReset = onReset
        self.hasControls = Controls.self != EmptyView.self
        self.chromeMode = chromeMode
        self.stage = stage()
        self.accessory = accessory()
        self.status = status()
        self.controls = controls()
    }

    private typealias Metrics = SceneViewTokens.Chrome
    private var resolvedTitle: String? { title ?? presenterTitle }

    public var body: some View {
        GeometryReader { proxy in
            ZStack {
                stage
                    .ignoresSafeArea()
                    .opacity(entered ? 1 : 0)
                    .accessibilitySortPriority(2)

                if chromeMode == .stage {
                    scrims
                }

                chrome(bottomInset: Metrics.dockBottom(safeArea: proxy.safeAreaInsets.bottom)
                       - proxy.safeAreaInsets.bottom)
            }
            .frame(width: proxy.size.width, height: proxy.size.height)
            .sheet(isPresented: $controlsPresented) {
                DemoControlsSheet(title: resolvedTitle, hasControls: hasControls,
                                  maxPeek: proxy.size.height / 2,
                                  foldInset: proxy.safeAreaInsets.bottom + SceneViewTokens.Space.sm,
                                  onReset: onReset) { controls }
            }
        }
        // The chrome never rides the keyboard: a text field lives in the
        // sheet, and the sheet does its own avoidance.
        .ignoresSafeArea(.keyboard)
        .background(SceneViewTokens.Stage.background.ignoresSafeArea())
        .hideNavigationBar()
        .onAppear {
            withAnimation(SceneViewTokens.Spring.fade) { entered = true }
        }
    }

    // MARK: Scrims

    /// `chrome-scrim` — the ground the white chrome stands on, whatever the
    /// scene renders. Flat next to the screen edge, then fading out.
    private var scrims: some View {
        VStack(spacing: 0) {
            scrimBand(edge: .top).frame(height: Metrics.scrimTop)
            Spacer(minLength: 0)
            scrimBand(edge: .bottom).frame(height: Metrics.scrimBottomMin)
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .opacity(entered ? 1 : 0)
    }

    private func scrimBand(edge: VerticalEdge) -> some View {
        LinearGradient(
            stops: [
                .init(color: Metrics.scrim, location: 0),
                .init(color: Metrics.scrim, location: Metrics.scrimFlat),
                .init(color: .clear, location: 1),
            ],
            startPoint: edge == .top ? .top : .bottom,
            endPoint: edge == .top ? .bottom : .top
        )
    }

    // MARK: Chrome

    private func chrome(bottomInset: CGFloat) -> some View {
        let travels = !entered && !reduceMotion
        return VStack(spacing: 0) {
            identityRow
                .offset(y: travels ? -Metrics.enterTop : 0)
                .accessibilitySortPriority(3)

            Spacer(minLength: 0)

            // One glass group: the accessory and the dock sample the same
            // backdrop and morph into each other when the accessory changes.
            VStack(spacing: Metrics.clusterGap) {
                accessory
                    .padding(.horizontal, Metrics.margin)
                dockView
                    .padding(.horizontal, Metrics.margin)
            }
            .glassEffectGroup()
            .environment(\.chromeGlassNamespace, glassSpace)
            .offset(y: travels ? Metrics.enterBottom : 0)
            .accessibilitySortPriority(1)
        }
        .padding(.top, Metrics.topGap - Self.touchSlop)
        .padding(.bottom, bottomInset)
        .opacity(entered ? 1 : 0)
        .animation(reduceMotion ? SceneViewTokens.Spring.fade : SceneViewTokens.Spring.animation, value: entered)
        // Chrome over media is theme-independent: pin the material and every
        // asset colour to their dark variant so light mode cannot wash it out.
        .environment(\.colorScheme, .dark)
        .environment(\.arChromeGround,
                     chromeMode == .ar ? SceneViewTokens.ARChrome.scrim(colorScheme) : nil)
        .dynamicTypeSize(...DynamicTypeSize.xxLarge)
    }

    /// A glass button is 44 pt inside a 48 pt touch target; the 2 pt of slop is
    /// taken back from the row padding so the *visible* edge lands on the margin.
    private static var touchSlop: CGFloat {
        (SceneViewTokens.Layout.touchTarget - SceneViewTokens.Glass.iconButtonSize) / 2
    }

    // MARK: Top row

    private var identityRow: some View {
        HStack(spacing: SceneViewTokens.Space.sm - Self.touchSlop) {
            GlassIconButton(icon: "chevron.left", label: "Close demo") {
                dismiss()
            }
            .accessibilityIdentifier("demo-close")

            if let resolvedTitle {
                GlassPill {
                    Text(resolvedTitle)
                        .font(SceneViewTokens.TypeScale.chromeLabel)
                        .foregroundStyle(SceneViewTokens.Glass.onGlass)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .accessibilityAddTraits(.isHeader)
                    // The chip is a human's escape hatch out of QA mode. A
                    // scripted pass has no human and its frames ship to the
                    // App Store, so it must not be baked in (#3384).
                    if qaMode && !DeepLinkRouter.isScriptedCapture {
                        Text("QA ×")
                            .font(SceneViewTokens.TypeScale.chromeCaption)
                            .foregroundStyle(SceneViewTokens.Glass.onGlassMuted)
                            .onTapGesture { qaMode = false }
                            .accessibilityLabel("Disable QA mode")
                    }
                }
            }

            Spacer(minLength: 0)

            status
                .padding(.trailing, Self.touchSlop)
        }
        .padding(.horizontal, Metrics.margin - Self.touchSlop)
    }

    // MARK: Dock

    private var dockView: some View {
        let items = Array(dock.prefix(SceneViewTokens.Layout.dockMaxItems))
        // Captions are the rule (`DESIGN.md`: every dock item is labelled). A
        // full dock at XXL on a 320 pt screen is the one case they cannot fit;
        // it falls back to icons and keeps the full accessibility labels.
        return ViewThatFits(in: .horizontal) {
            dockRow(items, captions: true)
            dockRow(items, captions: false)
        }
        .frame(minHeight: SceneViewTokens.Layout.dockHeight)
        .glassBackground(in: Capsule(), interactive: true, id: "dock")
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("demo-dock")
    }

    private func dockRow(_ items: [DockItem], captions: Bool) -> some View {
        HStack(spacing: SceneViewTokens.Space.xs) {
            ForEach(items) { item in
                DockButton(item: item, showsCaption: captions)
            }
            // Always there: even a demo with no controls of its own needs the
            // sheet for Send feedback and QA mode — one settings surface.
            DockButton(
                item: DockItem(icon: "slider.horizontal.3", label: "Demo settings", caption: "Settings",
                               selected: controlsPresented) {
                    controlsPresented = true
                },
                showsCaption: captions
            )
            .accessibilityIdentifier("demo-settings-fab")

            if let accent {
                AccentButton(item: accent) {
                    accentTaps += 1
                    accent.action()
                }
                // The dock's one primary action: a firmer tap than a selection.
                .sensoryFeedback(.impact(weight: .medium), trigger: accentTaps)
                .accessibilityLabel(accent.label)
                .accessibilityIdentifier("demo-dock-accent")
            }
        }
        .padding(.horizontal, SceneViewTokens.Space.sm)
    }
}

// MARK: - Dock item

/// One action in the floating dock: an icon over a one-word caption.
///
/// `label` is the accessible name and stays a full phrase ("Demo settings");
/// `caption` is the visible word under the icon ("Settings") and defaults to
/// `label`, so a one-word label needs nothing more.
public struct DockItem: Identifiable {
    public let id = UUID()
    public let icon: String
    public let label: String
    public let caption: String
    public var enabled: Bool
    public var selected: Bool
    public let action: () -> Void

    public init(icon: String, label: String, caption: String? = nil, enabled: Bool = true,
                selected: Bool = false, action: @escaping () -> Void) {
        self.icon = icon
        self.label = label
        self.caption = caption ?? label
        self.enabled = enabled
        self.selected = selected
        self.action = action
    }
}

/// The dock's primary action — `dock-accent`, a primary-tinted disc.
///
/// iOS 26+: the system's prominent glass (`.glassProminent`) tinted from the
/// `primary` token, so it reads as the same material as the dock it sits in.
/// Below 26, and when disabled on any version: the filled disc it has always
/// been — a disabled `.glassProminent` drops its tint and turned into a dark
/// grey disc under a dark icon that the dock swallowed.
private struct AccentButton: View {
    let item: DockItem
    let action: () -> Void

    var body: some View {
        if #available(iOS 26, macOS 26, visionOS 26, *), item.enabled {
            Button(action: action) {
                // The style pads its label on every side; this label size
                // lands the disc on the same 48 pt as the fallback below.
                icon
                    .frame(width: Self.glassLabelSize, height: Self.glassLabelSize)
            }
            .buttonStyle(.glassProminent)
            .buttonBorderShape(.circle)
            .tint(SceneViewTokens.HomeColor.primary)
            .disabled(!item.enabled)
            .frame(width: SceneViewTokens.Layout.touchTarget,
                   height: SceneViewTokens.Layout.touchTarget)
        } else {
            Button(action: action) {
                icon
                    .frame(width: SceneViewTokens.Layout.touchTarget,
                           height: SceneViewTokens.Layout.touchTarget)
                    .background(SceneViewTokens.HomeColor.primary.opacity(item.enabled ? 1 : 0.38),
                                in: Circle())
            }
            .buttonStyle(PressScaleButtonStyle(scale: SceneViewTokens.Spring.chromePressScale))
            .disabled(!item.enabled)
        }
    }

    private static let glassLabelSize: CGFloat = 32

    private var icon: some View {
        Image(systemName: item.icon)
            .font(.system(size: SceneViewTokens.Layout.dockIconSize, weight: .medium))
            .foregroundStyle(SceneViewTokens.Stage.background)
    }
}

private struct DockButton: View {
    let item: DockItem
    let showsCaption: Bool

    @State private var taps = 0

    var body: some View {
        Button {
            taps += 1
            item.action()
        } label: {
            VStack(spacing: 2) {
                Image(systemName: item.icon)
                    .font(.system(size: SceneViewTokens.Layout.dockIconSize, weight: .medium))
                    .frame(height: SceneViewTokens.Layout.dockIconSize + 2)
                if showsCaption {
                    Text(item.caption)
                        .font(SceneViewTokens.TypeScale.chromeDockCaption)
                        .lineLimit(1)
                        .fixedSize()
                }
            }
            // Icon and caption share one colour, so a selected toggle reads as a unit.
            .foregroundStyle(
                !item.enabled ? SceneViewTokens.Glass.onGlassDisabled
                    : item.selected ? SceneViewTokens.HomeColor.primary
                    : SceneViewTokens.Glass.onGlass
            )
            .padding(.horizontal, SceneViewTokens.Space.xs)
            .frame(minWidth: SceneViewTokens.Layout.touchTarget,
                   minHeight: SceneViewTokens.Layout.touchTarget)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressScaleButtonStyle(scale: SceneViewTokens.Spring.chromePressScale))
        // Every dock item — a toggle (Animate, Lighting) or a destination
        // (Models, Settings) — is picking one thing among the dock's few.
        .sensoryFeedback(.selection, trigger: taps)
        .disabled(!item.enabled)
        .accessibilityLabel(item.label)
        .accessibilityAddTraits(item.selected ? .isSelected : [])
    }
}

// MARK: - Accessories

/// A row of mutually exclusive options floating above the dock — the
/// scaffold's `accessory` for "pick one of a few" (a shape, a curve, a style).
///
/// The white selection capsule slides between options on `motion-spring`;
/// under Reduce Motion it just moves.
public struct DemoOptionStrip<Option: Hashable>: View {
    private let options: [Option]
    @Binding private var selection: Option
    private let label: (Option) -> String

    @Namespace private var selectionSpace
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    public init(_ options: [Option], selection: Binding<Option>, label: @escaping (Option) -> String) {
        self.options = options
        self._selection = selection
        self.label = label
    }

    public var body: some View {
        // Hugs its options when they fit; scrolls when they do not (XXL, SE).
        ViewThatFits(in: .horizontal) {
            row
            ScrollView(.horizontal, showsIndicators: false) { row }
        }
        .glassBackground(in: Capsule(), interactive: true, id: "options")
        .clipShape(Capsule())
        .sensoryFeedback(.selection, trigger: selection)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("demo-options")
    }

    private var row: some View {
        HStack(spacing: 0) {
            ForEach(options, id: \.self) { option in
                let selected = option == selection
                Button {
                    withAnimation(reduceMotion ? nil : SceneViewTokens.Spring.animation) {
                        selection = option
                    }
                } label: {
                    Text(label(option))
                        .font(SceneViewTokens.TypeScale.chromeLabel)
                        .lineLimit(1)
                        .fixedSize()
                        .foregroundStyle(selected ? SceneViewTokens.Stage.background
                                                  : SceneViewTokens.Glass.onGlass)
                        .padding(.horizontal, SceneViewTokens.Glass.pillPaddingHorizontal)
                        .frame(minHeight: SceneViewTokens.Glass.pillHeight)
                        .background {
                            if selected {
                                Capsule()
                                    .fill(SceneViewTokens.Glass.onGlass)
                                    .matchedGeometryEffect(id: "selection", in: selectionSpace)
                            }
                        }
                        .frame(minHeight: SceneViewTokens.Layout.touchTarget)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
        .padding(.horizontal, (SceneViewTokens.Layout.touchTarget - SceneViewTokens.Glass.pillHeight) / 2)
    }
}

/// One line of guidance floating above the dock ("Drag to orbit").
public struct DemoHint: View {
    private let text: String

    public init(_ text: String) { self.text = text }

    public var body: some View {
        Text(text)
            .font(SceneViewTokens.TypeScale.chromeCaption)
            .foregroundStyle(SceneViewTokens.Glass.onGlass)
            .multilineTextAlignment(.center)
            .padding(.horizontal, SceneViewTokens.Glass.pillPaddingHorizontal)
            .padding(.vertical, SceneViewTokens.Space.sm)
            .frame(minHeight: SceneViewTokens.Glass.pillHeight)
            .glassBackground(in: RoundedRectangle(cornerRadius: SceneViewTokens.Glass.pillHeight / 2,
                                                  style: .continuous),
                             id: "hint")
    }
}

// MARK: - Controls sheet

/// The one settings surface: the demo's own controls, then — behind a hairline
/// — Reset, Send feedback and QA mode (`DESIGN.md`: there is no overflow menu).
///
/// Below iOS 26 it is themed, not glass: a sheet is a surface, so it takes
/// `surface-container` and the app's light/dark colours rather than a bare
/// material, which over the dark stage resolved to nearly the black behind it.
/// On iOS 26+ the partial detents are the system's Liquid Glass sheet — a real
/// glass, not that bare material — so the scene stays visible under the
/// controls (`partialSheetBackground`).
struct DemoControlsSheet<Controls: View>: View {
    let title: String?
    let hasControls: Bool
    /// Tallest the resting detent may be — half the stage, so the scene the
    /// controls act on stays in view.
    let maxPeek: CGFloat
    /// What the system adds under a `.height` detent — the bottom safe area.
    /// The rows behind the hairline start past it, so the sheet rests on the
    /// demo's controls and not on an orphan line.
    let foldInset: CGFloat
    let onReset: (() -> Void)?
    @ViewBuilder let controls: () -> Controls

    @Environment(\.openURL) private var openURL
    @AppStorage(DeepLinkRouter.qaModeDefaultsKey) private var qaMode: Bool = false
    @State private var peek: CGFloat = 0
    @State private var detent: PresentationDetent = Self.fallback
    @State private var resets = 0

    private typealias Palette = SceneViewTokens.HomeColor
    private static var fallback: PresentationDetent { .fraction(0.25) }
    private var inset: CGFloat { SceneViewTokens.Space.lg }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: inset) {
                // What the sheet rests on: the demo's own controls when it has
                // any, the shared rows otherwise. Measured, so the fold never
                // cuts through a control — at any Dynamic Type size.
                VStack(alignment: .leading, spacing: SceneViewTokens.Space.md) {
                    Text(title ?? "Settings")
                        .font(.headline)
                        .foregroundStyle(Palette.onSurface)
                        .accessibilityAddTraits(.isHeader)
                    if hasControls { controls() } else { sharedRows }
                }
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { rest(on: $0) }

                if hasControls {
                    VStack(alignment: .leading, spacing: inset) {
                        Rectangle()
                            .fill(Palette.outlineSubtle)
                            .frame(height: 1)
                            .accessibilityHidden(true)
                        sharedRows
                    }
                    .padding(.top, foldInset)
                }
            }
            .padding(.horizontal, SceneViewTokens.Chrome.margin)
            .padding(.vertical, inset)
        }
        .scrollBounceBehavior(.basedOnSize)
        .scrollDismissesKeyboard(.interactively)
        .presentationDetents(peek > 0 ? [.height(peek), .large] : [Self.fallback, .large], selection: $detent)
        .presentationDragIndicator(.visible)
        #if os(iOS)
        .presentationBackgroundInteraction(.enabled)
        .presentationContentInteraction(.scrolls)
        .partialSheetBackground(Palette.surfaceContainer)
        .presentationCornerRadius(SceneViewTokens.Radius.xl)
        #endif
    }

    /// The resting detent hugs the measured block plus the sheet's own insets.
    private func rest(on blockHeight: CGFloat) {
        let height = min(blockHeight + 2 * inset, maxPeek).rounded(.up)
        guard height > 0, height != peek else { return }
        peek = height
        if detent != .large { detent = .height(height) }
    }

    private var sharedRows: some View {
        VStack(spacing: 0) {
            if let onReset {
                row(icon: "arrow.counterclockwise", title: "Reset") {
                    resets += 1
                    onReset()
                }
                // A confirmation, not a selection: the scene is back to where it started.
                .sensoryFeedback(.success, trigger: resets)
                .accessibilityIdentifier("demo-reset")
            }
            row(icon: "exclamationmark.bubble", title: "Send feedback") {
                if let url = URL(string: "https://github.com/SceneView/sceneview/issues/new/choose") {
                    openURL(url)
                }
            }
            Toggle(isOn: $qaMode) {
                Label("QA mode", systemImage: "flask")
                    .labelStyle(RowLabelStyle())
                    .font(.body)
                    .foregroundStyle(Palette.onSurface)
            }
            .tint(Palette.primary)
            .frame(minHeight: SceneViewTokens.Layout.touchTarget)
            .accessibilityIdentifier("demo-qa-mode")
        }
    }

    private func row(icon: String, title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(title, systemImage: icon)
                .labelStyle(RowLabelStyle())
                .font(.body)
                .foregroundStyle(Palette.onSurface)
                .frame(maxWidth: .infinity, minHeight: SceneViewTokens.Layout.touchTarget, alignment: .leading)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// One icon column for every row: SF Symbols have their own widths, and the
/// titles would otherwise start at a different x on each line.
private struct RowLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: SceneViewTokens.Space.md) {
            configuration.icon.frame(width: SceneViewTokens.Space.lg)
            configuration.title
        }
    }
}
