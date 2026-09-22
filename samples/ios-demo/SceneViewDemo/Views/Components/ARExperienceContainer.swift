// ARExperienceContainer.swift
//
// The one way into any AR screen of the app: AR tab CTA, featured cards,
// Samples catalog, the file viewer's "View in AR" and every `sceneview://demo/`
// deep link go through it. It owns what happens BEFORE the camera view exists
// (capability check, camera permission), what is shown WHILE it starts, and
// what is shown when it cannot — with the exact copies of the AR UX plan,
// §2.2, and the AR overlay tokens of DESIGN.md.
//
// The AR screen inside keeps its own `ARSceneView` closures untouched; the
// container learns about the session through the SDK's
// `.arSessionObserver(_:)` environment hook (first frame → live, failure →
// error). Nothing here is simulated: on the iOS Simulator, where ARKit world
// tracking does not exist, this container shows the Unsupported state and
// mounts no camera view at all.

import SwiftUI
import AVFoundation
#if os(iOS)
import ARKit
import RealityKit
import SceneViewSwift
#endif

// MARK: - Requirement

/// What an AR screen needs from the device, beyond a camera. One line of the
/// Unsupported state per case (`requirementCopy`), and the SDK check behind it.
enum ARExperienceRequirement: Equatable {
    /// ARKit world tracking — every rear-camera AR screen.
    case worldTracking
    /// TrueDepth front camera (`ARFaceTrackingConfiguration`).
    case faceTracking
    /// LiDAR scanner (scene reconstruction).
    case lidar
    /// `ARBodyTrackingConfiguration` (A12 and later).
    case bodyTracking
    /// People occlusion frame semantics (A12 and later).
    case peopleOcclusion

    /// The second line of the Unsupported card — plan §2.2: "Requires LiDAR."
    var requirementCopy: String {
        switch self {
        case .worldTracking: return "Requires an iPhone with ARKit."
        case .faceTracking: return "Requires a TrueDepth camera."
        case .lidar: return "Requires LiDAR."
        case .bodyTracking: return "Requires an A12 chip or later."
        case .peopleOcclusion: return "Requires an A12 chip or later."
        }
    }

    /// Whether the screen drives an `ARSceneView` that reports its lifecycle.
    /// The body tracker runs a raw `ARView` of its own, so the container
    /// cannot know when its first frame lands — it shows the screen directly
    /// rather than a "Starting camera…" it could never clear.
    var reportsSession: Bool { self != .bodyTracking }

    /// The requirement of a catalog scene, by id. Everything not listed is a
    /// rear-camera world-tracking screen.
    static func forScene(id: String) -> ARExperienceRequirement {
        switch id {
        case "ar-face": return .faceTracking
        case "ar-body-tracker": return .bodyTracking
        case "ar-depth-collider", "ar-depth-occlusion", "ar-scene-mesh": return .lidar
        case "ar-people-occlusion": return .peopleOcclusion
        default: return .worldTracking
        }
    }

    #if os(iOS)
    /// `true` when this device can run the screen. Reads ARKit once; injected
    /// into the model so the Simulator (nothing supported) and a forced
    /// preview can both be exercised.
    var isSupported: Bool {
        #if targetEnvironment(simulator)
        return false
        #else
        let capabilities = ARSessionConfiguration.Capabilities.current
        switch self {
        case .worldTracking:
            return ARSessionConfiguration().unmetRequirement(capabilities: capabilities) == nil
        case .faceTracking:
            return ARSessionConfiguration(mode: .faceTracking)
                .unmetRequirement(capabilities: capabilities) == nil
        case .lidar:
            return ARSessionConfiguration(sceneReconstruction: .mesh)
                .unmetRequirement(capabilities: capabilities) == nil
        case .peopleOcclusion:
            return ARSessionConfiguration(frameSemantics: [.personSegmentationWithDepth])
                .unmetRequirement(capabilities: capabilities) == nil
        case .bodyTracking:
            return ARBodyTrackingConfiguration.isSupported
        }
        #endif
    }
    #endif
}

// MARK: - Phase

/// The visible states of the container — plan §2.2, one card per case.
enum ARExperiencePhase: Equatable {
    /// Capability or permission not yet resolved (one synchronous pass).
    case checking
    /// Camera permission never asked: "Allow camera access to use AR."
    case permissionPrompt
    /// Camera permission denied or restricted: "Camera access is off."
    case denied
    /// Device cannot run the screen: "This feature isn't available on this device."
    case unsupported(ARExperienceRequirement)
    /// Camera view mounted, no frame yet: "Starting camera…"
    case starting
    /// First camera frame arrived. The AR screen is on its own from here.
    case live
    /// The session could not start or stopped: "Camera couldn't start."
    case error(String)
}

// MARK: - Model

/// Resolves and holds the phase. Also the `ARSceneSessionObserver` the SDK
/// reports to, so the AR screen needs no wiring of its own.
@MainActor
final class ARExperienceModel: ObservableObject {
    @Published private(set) var phase: ARExperiencePhase = .checking
    /// Bumped by "Try again": rekeys the camera view so a fresh session runs,
    /// and stamps events so a late message from the torn-down view is ignored.
    @Published private(set) var generation = 0

    let requirement: ARExperienceRequirement
    private let isSupported: Bool
    private let authorizationStatus: () -> AVAuthorizationStatus
    private let requestAccess: (@escaping (Bool) -> Void) -> Void
    private let forcedPhase: ARExperiencePhase?

    init(
        requirement: ARExperienceRequirement,
        isSupported: Bool,
        authorizationStatus: @escaping () -> AVAuthorizationStatus = {
            AVCaptureDevice.authorizationStatus(for: .video)
        },
        requestAccess: @escaping (@escaping (Bool) -> Void) -> Void = { completion in
            AVCaptureDevice.requestAccess(for: .video, completionHandler: completion)
        },
        forcedPhase: ARExperiencePhase? = nil
    ) {
        self.requirement = requirement
        self.isSupported = isSupported
        self.authorizationStatus = authorizationStatus
        self.requestAccess = requestAccess
        self.forcedPhase = forcedPhase
    }

    /// The routing every entry goes through, in this order: capability, then
    /// permission, then the camera. Denied, restricted and unsupported never
    /// reach `starting`, so they never show a scanning state.
    func resolve() {
        if let forcedPhase {
            phase = forcedPhase
            return
        }
        guard isSupported else {
            phase = .unsupported(requirement)
            return
        }
        switch authorizationStatus() {
        case .authorized:
            enterCamera()
        case .notDetermined:
            phase = .permissionPrompt
        case .denied, .restricted:
            phase = .denied
        @unknown default:
            phase = .denied
        }
    }

    /// Re-check after the app returns from Settings: a user who flipped the
    /// camera switch comes back to the camera, not to "Camera access is off."
    func sceneBecameActive() {
        guard forcedPhase == nil else { return }
        if case .denied = phase, authorizationStatus() == .authorized {
            enterCamera()
        }
    }

    /// "Continue" on the permission card — the system prompt is raised from a
    /// user action, Apple's recommended trigger.
    func requestPermission() {
        requestAccess { [weak self] granted in
            Task { @MainActor in
                guard let self else { return }
                if granted { self.enterCamera() } else { self.phase = .denied }
            }
        }
    }

    /// "Try again" on the error card.
    func retry() {
        generation += 1
        enterCamera()
    }

    private func enterCamera() {
        phase = requirement.reportsSession ? .starting : .live
    }
}

#if os(iOS)
extension ARExperienceModel: ARSceneSessionObserver {
    func arSession(didEmit event: ARSessionEvent, in arView: ARView) {
        switch event {
        case .firstFrame:
            // The camera is on screen. Model loading is the screen's own
            // business and is reported by the screen ("Loading model…"), never
            // folded into this signal.
            if phase == .starting { phase = .live }
        case .failed(let error):
            phase = .error(Self.errorCopy(for: error))
        case .started, .trackingStateChanged, .interrupted, .interruptionEnded:
            // `.interruptionEnded` only says ARKit resumed the session; the
            // next `.firstFrame` says the camera is back on screen. A screen
            // still starting stays on "Starting camera…" until then.
            break
        }
    }

    /// Plan §2.2: "Camera couldn't start." — the SDK's `unsupported` errors
    /// are routed to the Unsupported card instead, they are not transient.
    static func errorCopy(for error: Error) -> String {
        if let sdk = error as? ARSceneViewError {
            switch sdk {
            case .faceTrackingUnsupported, .unsupported:
                return "This feature isn't available on this device."
            }
        }
        return "Camera couldn't start."
    }
}
#endif

// MARK: - Container

/// Wraps an AR screen with the permission / capability / starting / error
/// presentation. See the file header for the routes that use it.
///
/// - `requirement`: what the screen needs (`ARExperienceRequirement.forScene(id:)`
///   for a catalog scene).
/// - `onViewIn3D`: the "View in 3D" secondary action. `nil` when the screen
///   has no 3D counterpart: the secondary action is then "Back".
/// - `onBack`: explicit back for a host that is not a presented cover. `nil`
///   uses `dismiss`.
struct ARExperienceContainer<Content: View>: View {
    private let requirement: ARExperienceRequirement
    private let onViewIn3D: (() -> Void)?
    private let onBack: (() -> Void)?
    private let content: () -> Content

    @StateObject private var model: ARExperienceModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase

    init(
        requirement: ARExperienceRequirement = .worldTracking,
        onViewIn3D: (() -> Void)? = nil,
        onBack: (() -> Void)? = nil,
        @ViewBuilder content: @escaping () -> Content
    ) {
        self.requirement = requirement
        self.onViewIn3D = onViewIn3D
        self.onBack = onBack
        self.content = content
        #if os(iOS)
        let supported = requirement.isSupported
        #else
        let supported = false
        #endif
        _model = StateObject(wrappedValue: ARExperienceModel(
            requirement: requirement,
            isSupported: supported,
            forcedPhase: ARExperiencePreview.forcedPhase
        ))
    }

    var body: some View {
        ZStack {
            // `stage-background` before the camera starts — the ground of
            // every card below, and what the camera view replaces full-bleed.
            SceneViewTokens.Stage.background.ignoresSafeArea()

            switch model.phase {
            case .checking:
                EmptyView()
            case .permissionPrompt:
                ARStateCard(
                    identifier: "ar-state-permission",
                    severity: .guidance,
                    title: "Allow camera access to use AR.",
                    primary: .init(title: "Continue", action: model.requestPermission),
                    secondary: secondaryAction
                )
            case .denied:
                ARStateCard(
                    identifier: "ar-state-denied",
                    severity: .blocked,
                    title: "Camera access is off.",
                    primary: .init(title: "Open Settings", action: openSettings),
                    secondary: secondaryAction
                )
            case .unsupported(let requirement):
                ARStateCard(
                    identifier: "ar-state-unsupported",
                    severity: .blocked,
                    title: "This feature isn’t available on this device.",
                    body: requirement.requirementCopy,
                    primary: nil,
                    secondary: secondaryAction
                )
            case .error(let message):
                ARStateCard(
                    identifier: "ar-state-error",
                    severity: .blocked,
                    title: message,
                    primary: .init(title: "Try again", action: model.retry),
                    secondary: secondaryAction
                )
            case .starting, .live:
                cameraStage
            }
        }
        // The stage is dark in both themes (the ground is a camera frame, or
        // `stage-background` before it starts), so a host navigation bar is
        // painted `stage-background` and reads white over it whatever the app
        // theme is. SwiftUI applies the bar's colour scheme only to a visible
        // bar background, hence the three modifiers together. A screen that
        // hides the bar (`.demoChrome`) is unaffected.
        .toolbarBackground(SceneViewTokens.Stage.background, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .onAppear(perform: model.resolve)
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { model.sceneBecameActive() }
        }
        .animation(SceneViewTokens.Motion.expressive(SceneViewTokens.Motion.medium), value: model.phase)
    }

    /// The AR screen, mounted only from `starting` on, with the starting pill
    /// over it until the first frame. Rekeyed by `generation` so "Try again"
    /// runs a fresh session.
    private var cameraStage: some View {
        GeometryReader { proxy in
            ZStack(alignment: .bottom) {
                #if os(iOS)
                content()
                    .arSessionObserver(model)
                    .id(model.generation)
                #else
                content().id(model.generation)
                #endif
                if model.phase == .starting {
                    ARStatusPill(
                        identifier: "ar-state-starting",
                        severity: .progress,
                        text: "Starting camera…",
                        bottomInset: ARStatusPill.bottomInset(safeArea: proxy.safeAreaInsets.bottom)
                    )
                    .transition(ARStateMotion.pillTransition)
                }
            }
        }
    }

    /// "View in 3D" when the screen has a 3D counterpart, "Back" otherwise.
    private var secondaryAction: ARStateAction {
        if let onViewIn3D {
            return .init(title: "View in 3D", action: onViewIn3D)
        }
        return .init(title: "Back", action: { (onBack ?? { dismiss() })() })
    }

    private func openSettings() {
        #if os(iOS)
        if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        }
        #endif
    }
}

// MARK: - Preview override (DEBUG)

/// Forces a phase from the `-ar-state-preview <phase>` launch argument so the
/// four cards can be rendered — and captured — on the Simulator, where the
/// real routing always lands on Unsupported. A capture made this way is a
/// render of the state, never a proof that a camera started; the PR that
/// adds it says so. Compiled out of release builds.
enum ARExperiencePreview {
    static var forcedPhase: ARExperiencePhase? {
        #if DEBUG
        let args = CommandLine.arguments
        guard let idx = args.firstIndex(of: "-ar-state-preview"), idx + 1 < args.count else { return nil }
        switch args[idx + 1] {
        case "permission": return .permissionPrompt
        case "denied": return .denied
        case "unsupported": return .unsupported(.lidar)
        case "starting": return .starting
        case "error": return .error("Camera couldn’t start.")
        default: return nil
        }
        #else
        return nil
        #endif
    }
}

// MARK: - AR overlay tokens (DESIGN.md "AR Coaching Overlay" / "AR Overlay Card")

/// Theme-independent by design: the ground under these surfaces is a camera
/// frame (or `stage-background` before it starts), not an app surface. Only
/// the scrim opacity follows the theme. The ground tokens come from
/// `SceneViewTokens.ARChrome`; the accents and the action fills are the
/// DESIGN.md "AR Coaching Overlay" values that have no token yet.
private enum ARStateTokens {
    /// `ar-scrim`, `ar-scrim-border`, `on-ar-scrim`, `on-ar-scrim-dim` —
    /// the shared AR chrome tokens of `SceneViewTokens.ARChrome`.
    static func scrim(_ scheme: ColorScheme) -> Color { SceneViewTokens.ARChrome.scrim(scheme) }
    static func border(_ scheme: ColorScheme) -> Color { SceneViewTokens.ARChrome.border(scheme) }
    static let borderWidth = SceneViewTokens.ARChrome.borderWidth
    static let onScrim = SceneViewTokens.ARChrome.onScrim
    static let onScrimDim = SceneViewTokens.ARChrome.onScrimDim
    /// Accents read on `ar-scrim`, so they take the dark-scheme value in both
    /// themes: `primary` #A4C1FF, `warning` #F59E0B, error #FFB4AB.
    static let progress = Color(red: 0xA4 / 255, green: 0xC1 / 255, blue: 0xFF / 255)
    static let guidance = Color(red: 0xF5 / 255, green: 0x9E / 255, blue: 0x0B / 255)
    static let blocked = Color(red: 0xFF / 255, green: 0xB4 / 255, blue: 0xAB / 255)
    /// Primary action over the camera: filled `primary` (light value #005BC1)
    /// with an `on-ar-scrim` label.
    static let primaryFill = Color(red: 0x00 / 255, green: 0x5B / 255, blue: 0xC1 / 255)
    /// Secondary action: the "Button glass" white-at-8% fill.
    static let glassFill = Color.white.opacity(0.08)
    static let maxWidth: CGFloat = 480
    static let indicatorSize: CGFloat = 20
    /// `shadow-lg` dark — 0 12px 40px rgba(0,0,0,0.5).
    static let shadow = Color.black.opacity(0.5)
}

private enum ARStateMotion {
    /// Enters with fade + 8px rise (`duration-medium`, `ease-expressive`),
    /// leaves with fade + fall (`duration-short`). Under Reduce Motion the
    /// view keeps the fades only — the caller checks and swaps.
    static var pillTransition: AnyTransition {
        if UIAccessibility.isReduceMotionEnabled {
            return .opacity
        }
        return .asymmetric(
            insertion: .opacity.combined(with: .offset(y: SceneViewTokens.Space.sm))
                .animation(SceneViewTokens.Motion.expressive(SceneViewTokens.Motion.medium)),
            removal: .opacity.combined(with: .offset(y: SceneViewTokens.Space.sm))
                .animation(.easeOut(duration: SceneViewTokens.Motion.short))
        )
    }
}

enum ARStateSeverity {
    case progress, guidance, blocked
}

struct ARStateAction {
    let title: String
    let action: () -> Void
}

// MARK: - Card

/// DESIGN.md "AR Overlay Card": `ar-scrim` ground, `ar-scrim-border` hairline,
/// `radius-lg`, `space-md` padding, `space-sm` between children, 480 max
/// width, `shadow-lg`. Title `type-card` on `on-ar-scrim`; body `type-body`
/// on `on-ar-scrim-dim`. Centred on the stage: there is no camera under it
/// to coach, the card IS the screen.
private struct ARStateCard: View {
    let identifier: String
    let severity: ARStateSeverity
    let title: String
    var body_: String? = nil
    let primary: ARStateAction?
    let secondary: ARStateAction

    @Environment(\.colorScheme) private var scheme

    init(
        identifier: String,
        severity: ARStateSeverity,
        title: String,
        body: String? = nil,
        primary: ARStateAction?,
        secondary: ARStateAction
    ) {
        self.identifier = identifier
        self.severity = severity
        self.title = title
        self.body_ = body
        self.primary = primary
        self.secondary = secondary
    }

    var body: some View {
        VStack(alignment: .leading, spacing: SceneViewTokens.Space.sm) {
            HStack(alignment: .top, spacing: SceneViewTokens.Space.sm) {
                ARStateIndicator(severity: severity)
                    .padding(.top, 1)
                Text(title)
                    .font(SceneViewTokens.TypeScale.card)
                    .foregroundStyle(ARStateTokens.onScrim)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if let body_ {
                Text(body_)
                    .font(SceneViewTokens.TypeScale.body)
                    .foregroundStyle(ARStateTokens.onScrimDim)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.leading, ARStateTokens.indicatorSize + SceneViewTokens.Space.sm)
            }
            VStack(spacing: SceneViewTokens.Space.sm) {
                if let primary {
                    ARStateButton(title: primary.title, style: .primary, action: primary.action)
                        .accessibilityIdentifier("\(identifier)-primary")
                }
                ARStateButton(title: secondary.title, style: .secondary, action: secondary.action)
                    .accessibilityIdentifier("\(identifier)-secondary")
            }
            .padding(.top, SceneViewTokens.Space.xs)
        }
        .padding(SceneViewTokens.Space.md)
        .frame(maxWidth: ARStateTokens.maxWidth)
        .background(ARStateTokens.scrim(scheme))
        .overlay(
            RoundedRectangle(cornerRadius: SceneViewTokens.Radius.lg, style: .continuous)
                .strokeBorder(ARStateTokens.border(scheme), lineWidth: ARStateTokens.borderWidth)
        )
        .clipShape(RoundedRectangle(cornerRadius: SceneViewTokens.Radius.lg, style: .continuous))
        .shadow(color: ARStateTokens.shadow, radius: 20, x: 0, y: 12)
        .padding(.horizontal, SceneViewTokens.Space.md)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(identifier)
    }
}

// MARK: - Pill

/// DESIGN.md "AR Coaching Overlay": the one instruction surface over a live
/// camera feed. It shares the bottom band with the demo's floating dock and
/// stacks directly above it (`dock-bottom` + `dock-height` + `chrome-cluster-gap`
/// from the screen edge), so the two never overlap.
private struct ARStatusPill: View {
    let identifier: String
    let severity: ARStateSeverity
    let text: String
    /// Distance from the **screen** bottom edge to the pill's bottom edge.
    let bottomInset: CGFloat

    @Environment(\.colorScheme) private var scheme

    static func bottomInset(safeArea: CGFloat) -> CGFloat {
        SceneViewTokens.Chrome.dockBottom(safeArea: safeArea)
            + SceneViewTokens.Layout.dockHeight
            + SceneViewTokens.Chrome.clusterGap
    }

    var body: some View {
        VStack {
            Spacer()
            HStack(spacing: SceneViewTokens.Space.sm + SceneViewTokens.Space.xs) {
                ARStateIndicator(severity: severity)
                Text(text)
                    .font(SceneViewTokens.TypeScale.bodyMedium)
                    .foregroundStyle(ARStateTokens.onScrim)
                    .lineLimit(3)
            }
            .padding(.horizontal, SceneViewTokens.Space.md)
            .padding(.vertical, SceneViewTokens.Space.sm + SceneViewTokens.Space.xs)
            .background(ARStateTokens.scrim(scheme))
            .overlay(
                RoundedRectangle(cornerRadius: SceneViewTokens.Radius.lg, style: .continuous)
                    .strokeBorder(ARStateTokens.border(scheme), lineWidth: ARStateTokens.borderWidth)
            )
            .clipShape(RoundedRectangle(cornerRadius: SceneViewTokens.Radius.lg, style: .continuous))
            .shadow(color: ARStateTokens.shadow, radius: 20, x: 0, y: 12)
            // The pill hugs its text; the frame only caps it at 480 and centres it.
            .frame(maxWidth: ARStateTokens.maxWidth)
            .padding(.horizontal, SceneViewTokens.Space.md)
            .padding(.bottom, bottomInset)
            .frame(maxWidth: .infinity)
        }
        .ignoresSafeArea(edges: .bottom)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier(identifier)
    }
}

// MARK: - Pieces

/// 20px leading indicator, one per severity: spinner in `primary` (dark
/// value), guidance icon in `warning`, error icon in the dark-scheme error.
private struct ARStateIndicator: View {
    let severity: ARStateSeverity

    var body: some View {
        Group {
            switch severity {
            case .progress:
                ProgressView()
                    .controlSize(.small)
                    .tint(ARStateTokens.progress)
            case .guidance:
                Image(systemName: "camera.viewfinder")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(ARStateTokens.guidance)
            case .blocked:
                Image(systemName: "exclamationmark.circle.fill")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(ARStateTokens.blocked)
            }
        }
        .frame(width: ARStateTokens.indicatorSize, height: ARStateTokens.indicatorSize)
        .accessibilityHidden(true)
    }
}

/// Primary: filled `primary`, `on-ar-scrim` label, `radius-md`, semibold,
/// `dock-item` (48pt) minimum target. Secondary: same geometry on the
/// button-glass fill.
private struct ARStateButton: View {
    enum Style { case primary, secondary }

    let title: String
    let style: Style
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(SceneViewTokens.TypeScale.bodySemibold)
                .foregroundStyle(ARStateTokens.onScrim)
                .frame(maxWidth: .infinity, minHeight: SceneViewTokens.Layout.touchTarget)
                .background(style == .primary ? ARStateTokens.primaryFill : ARStateTokens.glassFill)
                .clipShape(RoundedRectangle(cornerRadius: SceneViewTokens.Radius.md, style: .continuous))
                .contentShape(RoundedRectangle(cornerRadius: SceneViewTokens.Radius.md, style: .continuous))
        }
        .buttonStyle(.plain)
    }
}
