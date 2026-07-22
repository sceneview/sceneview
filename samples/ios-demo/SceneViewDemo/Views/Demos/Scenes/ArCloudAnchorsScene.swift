// @sceneId     ar-cloud-anchor
// @title       Cloud Anchors
// @subtitle    Persistent multi-user anchors
// @category    ar
// @available   true
// @status      knownIssue
// @icon        icloud.fill
// @iosOnly     true
import SwiftUI

/// Cloud Anchor persistence — mirrors Android's `ARCloudAnchorDemo`
/// (`samples/android-demo/.../demos/ARCloudAnchorDemo.kt`, issue #2836).
///
/// ### Single-file structure (deliberate)
///
/// `SceneViewDemo.xcodeproj` registers Swift sources as hand-written
/// `PBXFileReference`/`PBXBuildFile` entries rather than a file-system
/// synchronized group (`grep -c PBXFileSystemSynchronizedRootGroup` → `0`), so
/// a brand-new file needs a `project.pbxproj` edit before it even compiles.
/// This port is scoped to the Scene file + `parity-manifest.yml` + a changelog
/// fragment, and `ArCloudAnchorsScene.swift` already has its own pbxproj entry
/// — so the demo body lives here rather than in a `Views/Demos/*Demo.swift`
/// file the build would silently ignore. Same call as `ArPoseScene` (#2837).
///
/// The file name stays plural for historical reasons; the canonical
/// `@sceneId` is the singular `ar-cloud-anchor` (#2799), with
/// `ar-cloud-anchors` kept as a legacy deep-link alias.
enum ArCloudAnchorsScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(ARCloudAnchorDemo())
        #else
        return AnyView(EmptyView())
        #endif
    }
}

#if os(iOS)
import ARKit
import RealityKit
import SceneViewSwift

// MARK: - App-side ARCore Cloud bridge

/// The app half of a Cloud Anchor round-trip.
///
/// `SceneViewSwift` deliberately does **not** vendor Google's `arcore-ios-sdk`
/// (`GARSession`) — see the "Dependency-free by design" section of
/// ``CloudAnchorNode``. The library owns only the cancellation lifecycle
/// (`CloudAnchorFuture`); the app supplies the actual billed network call
/// through the `operation` closure. This struct is that app-supplied half,
/// expressed as plain closures so no `arcore-ios-sdk` symbol is referenced
/// anywhere in this file.
///
/// `resolve` reports a world transform alongside the `CloudAnchorResult`
/// because `CloudAnchorResult` carries only `(cloudAnchorId, state)` — it has
/// no pose payload, so the resolved `GARAnchor`'s transform has to come back
/// out-of-band for the app to place content on it. Android's
/// `CloudAnchorNode.resolve(engine, session, id) { state, node -> }` hands
/// back the node (and therefore the anchor) directly; that asymmetry is real
/// and is called out here rather than papered over.
private struct ARCoreCloudBridge {
    /// Starts `GARSession.hostCloudAnchor(_:TTLDays:completionHandler:)`.
    /// Returns the cancel hook `CloudAnchorFuture.cancel()` invokes, or `nil`.
    let host: (
        _ anchor: ARAnchor,
        _ ttlDays: Int,
        _ report: @escaping (CloudAnchorResult) -> Void
    ) -> (() -> Void)?

    /// Starts `GARSession.resolveCloudAnchor(_:completionHandler:)`.
    /// Same cancel-hook contract as ``host``.
    let resolve: (
        _ cloudAnchorId: String,
        _ report: @escaping (CloudAnchorResult, simd_float4x4?) -> Void
    ) -> (() -> Void)?
}

/// Where the demo looks for its ARCore Cloud bridge.
///
/// **`nil` in this build, on purpose.** The SceneView demo app does not link
/// `arcore-ios-sdk`, and no ARCore Cloud API key is configured for it — the
/// iOS counterpart of Android's `com.google.android.ar.API_KEY` manifest
/// meta-data check in `ARCloudAnchorDemo.kt`. Rather than fake a hosted id or
/// silently do nothing, the demo surfaces an explicit unavailable banner and
/// disables Host/Resolve, exactly as Android does when its key is missing.
///
/// To make hosting real: add Google's `arcore-ios-sdk` Swift Package to the
/// demo target, create a `GARSession` alongside the `ARSession`, and return an
/// `ARCoreCloudBridge` here whose closures forward to
/// `GARSession.hostCloudAnchor` / `.resolveCloudAnchor`. Nothing else in this
/// file changes — the `CloudAnchorNode.host` / `.resolve` wiring below is the
/// real library call path already.
@MainActor
private enum ARCoreCloud {
    static let bridge: ARCoreCloudBridge? = nil

    static var isAvailable: Bool { bridge != nil }

    /// Shown verbatim in the status banner while ``bridge`` is `nil`. Mirrors
    /// the shape of Android's "ARCore Cloud API key not configured — Host/
    /// Resolve will return ERROR_NOT_AUTHORIZED" message: name the blocker,
    /// name the consequence, point at the fix.
    static let unavailableMessage =
        "Cloud Anchors unavailable: this build doesn't link Google's "
        + "arcore-ios-sdk (GARSession), so Host and Resolve can't reach the "
        + "ARCore Cloud service. Plane detection and anchor placement below "
        + "are live."
}

// MARK: - Demo

/// ### What this mirrors
///
/// Android's `ARCloudAnchorDemo` is the full four-step Cloud Anchor loop, and
/// so is this port:
///
/// 1. **Place** — tap a detected plane to drop a single anchor (Android bails
///    out of its tap handler once `localAnchor != null`; same here) with the
///    bundled `khronos_lantern` model on it at `scaleToUnits(0.3)`.
/// 2. **Host** — `CloudAnchorNode.host(ttlDays: 1)`, the library's cancellable
///    Future, driven by the app-supplied ``ARCoreCloudBridge``.
/// 3. **Copy** — the hosted id is shown in the settings sheet with a Copy
///    button (`UIPasteboard`), so it can leave the device. Android shows the
///    same "Hosted ID: …" readout in its bottom sheet.
/// 4. **Resolve** — paste an id into the on-screen field (#2486 moved that
///    field on-screen on Android for exactly this reason) and tap Resolve;
///    a successful resolve re-anchors the lantern at the recovered pose.
///
/// Both futures are cancelled in `onDisappear`, the direct analogue of
/// Android's `DisposableEffect(node) { onDispose { future.cancel() } }`
/// hygiene (#1768) — a screen the user left must not keep a billed ARCore
/// Cloud request running.
///
/// ### Honest platform gaps
///
/// - **No Cloud Anchor backend in this build.** See ``ARCoreCloud``. Host and
///   Resolve are disabled and the banner says why — mirroring Android's
///   `enabled = hasArcoreApiKey && …`. The hosting/resolving path has
///   therefore never been exercised end-to-end on this host: it compiles
///   (including the device-only `#if !targetEnvironment(simulator)` half) and
///   is wired to the real library API, but no anchor has ever round-tripped
///   through Google's service from here, and no id is ever fabricated to
///   pretend otherwise.
/// - **Simulator**: ARKit world tracking doesn't run on the iOS Simulator, so
///   the AR half is replaced by the same "requires a physical device"
///   placeholder every other AR demo in this catalog uses.
/// - **Camera warm-up scrim**: Android covers ARCore's 1–3 s warm-up with a
///   bespoke `ARCameraInitScrim` (#2484). iOS uses ARKit's own
///   `showCoachingOverlay`, as every other AR demo here does, instead of
///   reimplementing that overlay.
/// - **Forced tracking-failure debug menu**: Android's `ForceTrackingFailureMenu`
///   (#1881) injects synthetic `TrackingFailureReason`s for QA. ARKit exposes
///   no equivalent injection point and the iOS demo app has no such menu, so
///   it is not mirrored.
private struct ARCloudAnchorDemo: View {

    // MARK: - AR state

    /// `true` once ARKit reports `.normal` tracking — the closest iOS analogue
    /// of Android's `frame.camera.trackingState == TrackingState.TRACKING`.
    @State private var isTracking = false

    /// The ARKit session anchor backing the placed content. A real `ARAnchor`
    /// (not just a RealityKit `AnchorEntity`) because that is exactly what
    /// `GARSession.hostCloudAnchor` takes — Android hosts its `Anchor` the
    /// same way. `nil` = nothing placed yet.
    @State private var placedAnchor: ARAnchor?

    /// The RealityKit entity bound to ``placedAnchor``, kept so a successful
    /// resolve can tear the old one down before re-anchoring.
    @State private var anchorEntity: AnchorEntity?

    /// Bundled lantern, loaded once and moved between anchors — at most one
    /// anchor is live at a time, mirroring Android's single `localAnchor`.
    @State private var lanternModel: ModelNode?

    /// Weak handle on the live `ARView` so Resolve (which has no tap to ride
    /// on) can add its anchor. Same `ARViewBox` pattern as `ARPlacementDemo`.
    @State private var arViewRef = ARViewBox()

    // MARK: - Cloud Anchor state (mirrors ARCloudAnchorDemo.kt)

    /// Id returned by a successful host. Never set from anything but a real
    /// `.success` result carrying a real id.
    @State private var hostedId: String?

    /// Id currently bound to the placed anchor — the hosted id, or the id a
    /// successful resolve echoed back. Android's `cloudAnchorId`.
    @State private var cloudAnchorId: String?

    /// Contents of the on-screen "Cloud Anchor ID to resolve" field.
    @State private var resolveId = ""

    /// Transform reported alongside a successful resolve. See
    /// ``ARCoreCloudBridge`` for why it arrives out-of-band.
    @State private var resolvedTransform: simd_float4x4?

    /// Free-form status line. Only consulted by ``statusText`` for the states
    /// Android also falls back to it for.
    @State private var statusMessage = "Tap a surface to place an anchor"

    /// Set when a host/resolve call came back with an error state, so the
    /// banner tints red. Android infers this from a `"failed"` substring
    /// search; an explicit flag says the same thing without the string match.
    @State private var operationFailed = false

    /// In-flight handles. Cancelled on `onDisappear` (#1768).
    @State private var hostFuture: CloudAnchorFuture?
    @State private var resolveFuture: CloudAnchorFuture?

    /// Transient "Copied" confirmation on the hosted-id Copy button.
    @State private var didCopyId = false

    /// Reference box for the non-`Sendable` `ARView` so it can live in
    /// `@State` without triggering view-identity churn.
    private final class ARViewBox {
        weak var value: ARView?
    }

    /// Android hides the resolve field once an anchor is hosted or resolved.
    private var isAnchorReady: Bool { hostedId != nil || cloudAnchorId != nil }

    var body: some View {
        ZStack {
            #if !targetEnvironment(simulator)
            arScene
                .ignoresSafeArea()
            #else
            simulatorPlaceholder
            #endif

            VStack(spacing: 10) {
                statusBanner
                if !isAnchorReady {
                    resolveIdField
                }
                Spacer()
                actionBar
            }
            .padding()
        }
        .background(Color.black)
        .demoSettingsSheet { controlsSheet }
        .task { await loadLantern() }
        .onDisappear {
            // Android: DisposableEffect(node) { onDispose { future.cancel() } }.
            // A billed ARCore Cloud request must not outlive the screen (#1768).
            hostFuture?.cancel()
            hostFuture = nil
            resolveFuture?.cancel()
            resolveFuture = nil
        }
    }

    // MARK: - AR scene

    #if !targetEnvironment(simulator)
    private var arScene: some View {
        ARSceneView(
            // Android leaves `planeFindingMode` at its default,
            // `HORIZONTAL_AND_VERTICAL` — `.both` is the ARKit equivalent.
            planeDetection: .both,
            showPlaneOverlay: true,
            showCoachingOverlay: true,
            onTapOnPlane: { worldPosition, arView in
                placeAnchor(at: worldPosition, in: arView)
            }
        )
        .onFrame { frame, arView in
            let tracking = frame.camera.trackingState == .normal
            if tracking != isTracking { isTracking = tracking }
            if arViewRef.value == nil { arViewRef.value = arView }
        }
    }
    #endif

    // MARK: - Placement

    /// Places the one-and-only local anchor. Mirrors Android's
    /// `onSingleTapConfirmed`, which returns early once `localAnchor != null`.
    @MainActor
    private func placeAnchor(at worldPosition: SIMD3<Float>, in arView: ARView) {
        guard placedAnchor == nil else { return }
        var transform = matrix_identity_float4x4
        transform.columns.3 = SIMD4<Float>(worldPosition, 1)
        arViewRef.value = arView
        attach(transform: transform, in: arView, name: "cloud-anchor")
        statusMessage = "Anchor placed — tap Host to share"
        operationFailed = false
    }

    /// Adds an `ARAnchor` at `transform` to the session, binds a RealityKit
    /// anchor to it, and moves the lantern onto it — replacing any previous
    /// anchor (the resolve path re-anchors the same content elsewhere, which
    /// is Android's `localAnchor = node.anchor`).
    @MainActor
    private func attach(transform: simd_float4x4, in arView: ARView, name: String) {
        if let entity = anchorEntity {
            arView.scene.removeAnchor(entity)
        }
        if let previous = placedAnchor {
            arView.session.remove(anchor: previous)
        }
        let anchor = ARAnchor(name: name, transform: transform)
        arView.session.add(anchor: anchor)
        let entity = AnchorEntity(anchor: anchor)
        arView.scene.addAnchor(entity)
        placedAnchor = anchor
        anchorEntity = entity
        attachLantern(to: entity)
    }

    @MainActor
    private func loadLantern() async {
        lanternModel = try? await ModelNode.load("khronos_lantern")
        // The model may finish loading after the user already tapped.
        if let entity = anchorEntity { attachLantern(to: entity) }
    }

    /// Moves the single lantern instance onto `anchor`. A no-op until the
    /// model finishes loading — `loadLantern` retries once it has.
    @MainActor
    private func attachLantern(to anchor: AnchorEntity) {
        guard let model = lanternModel else { return }
        _ = model.scaleToUnits(0.3)
        model.entity.removeFromParent()
        anchor.addChild(model.entity)
    }

    // MARK: - Host / Resolve

    /// Mirrors Android's `onHost`.
    @MainActor
    private func host() {
        guard let anchor = placedAnchor else {
            // Android: "Tap a surface to place an anchor first, then Host".
            statusMessage = "Tap a surface to place an anchor first, then Host"
            return
        }
        guard let bridge = ARCoreCloud.bridge else {
            statusMessage = ARCoreCloud.unavailableMessage
            operationFailed = true
            return
        }
        statusMessage = "Hosting anchor…"
        operationFailed = false
        hostFuture?.cancel()
        do {
            hostFuture = try CloudAnchorNode.host(ttlDays: 1) { result in
                hostFuture = nil
                if result.state == .success, let id = result.cloudAnchorId {
                    hostedId = id
                    cloudAnchorId = id
                    statusMessage = "Hosted! ID: \(id)"
                } else {
                    operationFailed = true
                    statusMessage = failureMessage("Hosting failed", result.state)
                }
            } operation: { report in
                bridge.host(anchor, 1, report)
            }
        } catch {
            // `ttlDays` out of range — unreachable with the literal 1 above,
            // but the throw is part of the API contract, not swallowed.
            operationFailed = true
            statusMessage = "Hosting failed: \(error)"
        }
    }

    /// Mirrors Android's `onResolve`.
    @MainActor
    private func resolve() {
        let id = resolveId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty else {
            statusMessage = "Enter a Cloud Anchor ID above, then tap Resolve"
            return
        }
        guard let bridge = ARCoreCloud.bridge else {
            statusMessage = ARCoreCloud.unavailableMessage
            operationFailed = true
            return
        }
        guard let arView = arViewRef.value else {
            statusMessage = "AR session not ready"
            return
        }
        statusMessage = "Resolving \(id)…"
        operationFailed = false
        resolvedTransform = nil
        resolveFuture?.cancel()
        resolveFuture = CloudAnchorNode.resolve(cloudAnchorId: id) { result in
            resolveFuture = nil
            if result.state == .success {
                cloudAnchorId = result.cloudAnchorId ?? id
                if let transform = resolvedTransform {
                    attach(transform: transform, in: arView, name: "cloud-anchor-resolved")
                }
                statusMessage = "Resolved \(id)"
            } else {
                operationFailed = true
                statusMessage = failureMessage("Resolve failed", result.state)
            }
        } operation: { report in
            bridge.resolve(id) { result, transform in
                resolvedTransform = transform
                report(result)
            }
        }
    }

    /// Actionable failure text. Android singles out `ERROR_NOT_AUTHORIZED`
    /// because it is by far the most common real-world failure (a key that
    /// doesn't cover this build); the same call-out applies on iOS, pointing
    /// at the iOS-side cause instead of Android's SHA-1 runbook.
    private func failureMessage(_ prefix: String, _ state: CloudAnchorState) -> String {
        switch state {
        case .errorNotAuthorized:
            return "\(prefix): ERROR_NOT_AUTHORIZED. The ARCore Cloud API key "
                + "is rejecting this app — check that the key is enabled for "
                + "the ARCore API and allows this bundle id."
        case .errorResolvingCloudIdNotFound:
            return "\(prefix): that Cloud Anchor ID doesn't exist, or its "
                + "time-to-live has expired."
        default:
            return "\(prefix): \(state)"
        }
    }

    // MARK: - Overlays

    /// One contextual line, mirroring Android's `statusText` when-chain.
    private var statusText: String {
        if !ARCoreCloud.isAvailable { return ARCoreCloud.unavailableMessage }
        if !isTracking { return "Initializing camera — move slowly to find a surface" }
        if placedAnchor == nil { return "Point at a surface and tap to place an anchor" }
        if hostedId == nil && cloudAnchorId == nil {
            return "Anchor placed — tap Host to share it to the cloud"
        }
        return statusMessage
    }

    private var isError: Bool { !ARCoreCloud.isAvailable || operationFailed }

    private var statusBanner: some View {
        Text(statusText)
            .font(.footnote)
            .multilineTextAlignment(.center)
            .foregroundStyle(.white)
            .padding(.horizontal, 18)
            .padding(.vertical, 10)
            .frame(maxWidth: 340)
            .background(isError ? Color.red.opacity(0.85) : Color.accentColor.opacity(0.85))
            .clipShape(RoundedRectangle(cornerRadius: 20))
    }

    /// On-screen resolve field — Android moved this out of its settings sheet
    /// in #2486 precisely so the host → resolve loop is discoverable without
    /// ever opening the sheet.
    private var resolveIdField: some View {
        TextField("Cloud Anchor ID to resolve", text: $resolveId)
            .textFieldStyle(.roundedBorder)
            .autocorrectionDisabled()
            .textInputAutocapitalization(.never)
            .font(.footnote.monospaced())
            .frame(maxWidth: 340)
            .disabled(!ARCoreCloud.isAvailable)
    }

    /// Host / Resolve are the demo's primary actions, so they live on-screen
    /// rather than in the settings sheet (Android #1964 / #1614). Disabled
    /// only for the genuinely-unavailable cases — no Cloud backend, or Host
    /// after a successful host — matching Android's `enabled =` conditions.
    private var actionBar: some View {
        HStack(spacing: 12) {
            actionButton("Host", systemImage: "icloud.and.arrow.up",
                         enabled: ARCoreCloud.isAvailable && hostedId == nil,
                         action: host)
            actionButton("Resolve", systemImage: "icloud.and.arrow.down",
                         enabled: ARCoreCloud.isAvailable,
                         action: resolve)
        }
    }

    private func actionButton(
        _ label: String,
        systemImage: String,
        enabled: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Label(label, systemImage: systemImage)
                .font(.body.weight(.semibold))
                .foregroundStyle(.white)
                .padding(.horizontal, 20)
                .padding(.vertical, 12)
                .background(enabled ? Color.accentColor : Color.white.opacity(0.15))
                .clipShape(Capsule())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }

    private var simulatorPlaceholder: some View {
        VStack(spacing: 16) {
            Image(systemName: "icloud.fill")
                .font(.system(size: 56))
                .foregroundStyle(.white.opacity(0.5))
            Text("Cloud Anchors")
                .font(.headline)
                .foregroundStyle(.white)
            Text("Hosting and resolving Cloud Anchors needs ARKit world tracking on a physical device.")
                .font(.subheadline)
                .multilineTextAlignment(.center)
                .foregroundStyle(.white.opacity(0.7))
                .padding(.horizontal, 40)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: - Settings sheet

    /// Mirrors Android's `controls` sheet: the one-paragraph explainer plus
    /// the hosted-id readout. Everything actionable stays on-screen.
    @ViewBuilder
    private var controlsSheet: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(
                "Place an anchor and tap Host to share it; paste a shared id in "
                + "the on-screen field and tap Resolve. The hosted id appears "
                + "below once Host succeeds — copy it to resolve on another device."
            )
            .font(.subheadline)

            if let hostedId {
                Text("Hosted ID")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                Text(hostedId)
                    .font(.caption.monospaced())
                    .textSelection(.enabled)
                Button {
                    UIPasteboard.general.string = hostedId
                    didCopyId = true
                } label: {
                    Label(didCopyId ? "Copied" : "Copy ID",
                          systemImage: didCopyId ? "checkmark" : "doc.on.doc")
                        .font(.caption.weight(.medium))
                }
                .buttonStyle(.bordered)
            }

            if !ARCoreCloud.isAvailable {
                Text(
                    "This build has no ARCore Cloud backend: the demo app doesn't "
                    + "link Google's arcore-ios-sdk, so Host and Resolve are "
                    + "disabled. Placement, plane detection and the anchor "
                    + "lifecycle below them are real."
                )
                .font(.caption)
                .foregroundStyle(.secondary)
            }
        }
    }
}
#endif
