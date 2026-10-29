#if os(iOS)
import SwiftUI

/// 3D Scan asks first what is scanned (#4325), as Android's `ScanScopePage`: a heading, one radio
/// card per scope — its icon in a well, what it is for and how the scan goes, the choice ticked —
/// and one primary action that names it, with the privacy line under it. Laid out as Polycam's and
/// Reality Composer's capture pickers, on the themed stage (light and dark).
struct RerunScanScopePage: View {
    @Binding var selected: RerunScanScope
    let topInset: CGFloat
    let bottomInset: CGFloat
    let onStart: () -> Void

    private typealias Space = SceneViewTokens.Space
    private typealias Stage = SceneViewTokens.ThemedStage

    var body: some View {
        ZStack {
            Stage.ground.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: Space.md) {
                    VStack(alignment: .leading, spacing: Space.xs) {
                        Text(RerunScanScopeCopy.chooserTitle)
                            .font(SceneViewTokens.TypeScale.display)
                            .tracking(SceneViewTokens.TypeScale.displayTracking)
                            .foregroundStyle(Stage.onGlass)
                            .accessibilityAddTraits(.isHeader)
                        Text(RerunScanScopeCopy.chooserBody)
                            .font(SceneViewTokens.TypeScale.body)
                            .foregroundStyle(Stage.onGlassMuted)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    VStack(spacing: Space.sm) {
                        ForEach(RerunScanScope.offered, id: \.self) { scope in
                            ScopeCard(scope: scope, selected: scope == selected) {
                                withAnimation(SceneViewTokens.Spring.fade) { selected = scope }
                            }
                        }
                    }
                    startCard
                }
                .frame(maxWidth: ScopeTokens.maxWidth, alignment: .leading)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, SceneViewTokens.Chrome.margin)
                .padding(.top, topInset)
                .padding(.bottom, bottomInset)
            }
            .scrollIndicators(.hidden)
        }
        .accessibilityIdentifier("rerun-scan-scope")
    }

    private var startCard: some View {
        Button(action: onStart) {
            VStack(spacing: 2) {
                Text(RerunScanScopeCopy.start(selected))
                    .font(SceneViewTokens.TypeScale.title)
                    .tracking(SceneViewTokens.TypeScale.titleTracking)
                Text(RerunScanScopeCopy.privacy)
                    .font(SceneViewTokens.TypeScale.captionRegular)
                    .opacity(0.72)
                    .multilineTextAlignment(.center)
            }
            .foregroundStyle(Stage.onAccent)
            .padding(.horizontal, Space.md)
            .padding(.vertical, Space.sm)
            .frame(maxWidth: .infinity, minHeight: SceneViewTokens.Layout.touchTarget + Space.md)
            .background(Stage.accent, in: RoundedRectangle(cornerRadius: SceneViewTokens.Radius.lg, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: SceneViewTokens.Radius.lg, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("rerun-scan-scope-start")
    }
}

/// One kind of scan, as a radio card: the icon in its well, what it is for, how it goes.
private struct ScopeCard: View {
    let scope: RerunScanScope
    let selected: Bool
    let onTap: () -> Void

    private typealias Space = SceneViewTokens.Space
    private typealias Stage = SceneViewTokens.ThemedStage

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: SceneViewTokens.Radius.lg, style: .continuous)
        Button(action: onTap) {
            HStack(spacing: Space.md) {
                Image(systemName: Self.icon(scope))
                    .font(SceneViewTokens.TypeScale.card)
                    .foregroundStyle(selected ? Stage.onAccent : Stage.onGlass)
                    .frame(width: SceneViewTokens.Layout.touchTarget, height: SceneViewTokens.Layout.touchTarget)
                    .background(selected ? Stage.accent : Stage.onGlass.opacity(Stage.wellAlpha), in: Circle())
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: Space.xs) {
                    Text(RerunScanScopeCopy.title(scope))
                        .font(SceneViewTokens.TypeScale.card)
                        .foregroundStyle(Stage.onGlass)
                    Text(RerunScanScopeCopy.detail(scope))
                        .font(SceneViewTokens.TypeScale.body)
                        .foregroundStyle(Stage.onGlass)
                    Text(RerunScanScopeCopy.how(scope))
                        .font(SceneViewTokens.TypeScale.captionRegular)
                        .foregroundStyle(Stage.onGlassMuted)
                }
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                    .font(SceneViewTokens.TypeScale.title)
                    .foregroundStyle(selected ? Stage.accent : Stage.onGlassMuted)
                    .accessibilityHidden(true)
            }
            .padding(Space.md)
            .background(Stage.glass, in: shape)
            .overlay(
                shape.strokeBorder(selected ? Stage.accent : Stage.onGlass.opacity(Stage.edgeAlpha),
                                   lineWidth: selected ? Stage.selectedBorder : Stage.unselectedBorder)
            )
            .contentShape(shape)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
        .accessibilityIdentifier("rerun-scan-scope-\(scope.rawValue)")
    }

    /// Android's Chair, MeetingRoom, Home and Yard, in SF Symbols.
    static func icon(_ scope: RerunScanScope) -> String {
        switch scope {
        case .object: "chair.fill"
        case .room: "door.left.hand.open"
        case .home: "house.fill"
        case .area: "tree.fill"
        }
    }
}

private enum ScopeTokens {
    /// The page column never grows wider than a phone's, as the landing's (560).
    static let maxWidth: CGFloat = 560
}

extension View {
    /// The status bar of a themed page: dark text on the light ground, light text on the dark one.
    ///
    /// The demo cover leaves the status bar to the screen under it, which keeps it light for the
    /// camera and stage screens. A page drawn on ``SceneViewTokens/ThemedStage`` needs it to follow
    /// the appearance, so while `active` the presentation is pinned to the *system* appearance,
    /// read from the window scene (`colorScheme` cannot be used: under `preferredColorScheme` it
    /// reports the pinned value, so a theme change made on the page would never reach it). Once
    /// inactive, the pin is dropped and the cover hands the status bar back to the screen under it.
    func themedPageStatusBar(_ active: Bool) -> some View {
        modifier(ThemedPageStatusBar(active: active))
    }
}

private struct ThemedPageStatusBar: ViewModifier {
    let active: Bool
    @State private var system: ColorScheme?

    func body(content: Content) -> some View {
        content
            .background(StatusBarHost(active: active, scheme: $system).frame(width: 0, height: 0))
            .preferredColorScheme(active ? system : nil)
    }
}

/// Reads the window scene's own appearance, which no presentation override changes, and gives
/// the status bar back to the screen under the cover when the page goes.
private struct StatusBarHost: UIViewRepresentable {
    let active: Bool
    @Binding var scheme: ColorScheme?

    func makeUIView(context: Context) -> HostView {
        let view = HostView()
        view.onChange = { style in
            let next: ColorScheme = style == .dark ? .dark : .light
            if scheme != next { scheme = next }
        }
        return view
    }

    func updateUIView(_ uiView: HostView, context: Context) {
        uiView.setActive(active)
    }

    final class HostView: UIView {
        var onChange: ((UIUserInterfaceStyle) -> Void)?
        private var registration: (any UITraitChangeRegistration)?
        private var active = false

        func setActive(_ value: Bool) {
            guard value != active else { return }
            active = value
            guard !value else { return }
            // After the pin, the cover keeps the status bar as its own; hand it back.
            DispatchQueue.main.async { [weak self] in
                guard let cover = self?.cover else { return }
                cover.modalPresentationCapturesStatusBarAppearance = false
                cover.presentingViewController?.setNeedsStatusBarAppearanceUpdate()
                cover.setNeedsStatusBarAppearanceUpdate()
            }
        }

        /// The presented controller this view is shown in.
        private var cover: UIViewController? {
            var responder: UIResponder? = self
            while let next = responder?.next {
                if let controller = next as? UIViewController, controller.presentingViewController != nil {
                    var top = controller
                    while let parent = top.parent { top = parent }
                    return top
                }
                responder = next
            }
            return nil
        }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            if let registration, let scene = window?.windowScene { scene.unregisterForTraitChanges(registration) }
            registration = nil
            guard let scene = window?.windowScene else { return }
            let report = onChange
            DispatchQueue.main.async { report?(scene.traitCollection.userInterfaceStyle) }
            registration = scene.registerForTraitChanges([UITraitUserInterfaceStyle.self]) {
                (scene: UIWindowScene, _: UITraitCollection) in
                report?(scene.traitCollection.userInterfaceStyle)
            }
        }
    }
}
#endif
