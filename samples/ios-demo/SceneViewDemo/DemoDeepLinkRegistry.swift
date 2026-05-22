import SwiftUI

/// Maps a stable demo id (`ar-rerun`, `model-viewer`, …) to the
/// corresponding SwiftUI destination, for the deep-link path.
///
/// Why this exists separately from `SamplesTab.allScenes()` and
/// friends: the iOS demo's catalogue uses human titles (`"AR Debug
/// (Rerun)"`) while the deep-link contract requires a stable, slug-style
/// id matched **byte-for-byte** with the Android `DemoRegistry.kt` —
/// otherwise the same `sceneview://demo/<id>` URL would route to
/// different things on Android and iOS, defeating the cross-platform
/// guarantee of the QR codes generated on the website.
///
/// **Coverage policy** — we only need to map the ids that QR codes
/// actually point at today. The set grows as we add QR codes on
/// website / README / docs. Unknown ids fall through to a fallback
/// "Open in app" placeholder (the parent `DeepLinkRouter` already
/// validates ids against `allowedIds` so the placeholder is only ever
/// reached for ids registered here without a destination).
enum DemoDeepLinkRegistry {

    /// Subset of `DemoRegistry.kt` ids that should be reachable via
    /// `sceneview://demo/<id>` on iOS. Add ids here as new QR codes are
    /// published; new ids should always be a *subset* of the canonical
    /// list (see `DeepLinkRouter` Kotlin).
    /// Full demo catalog — mirrors Android's `DemoRegistry.kt` id set so
    /// every `sceneview://demo/<id>` QR code resolves on iOS.  Coming-soon
    /// demos are included and route to a placeholder so the deep-link URL
    /// is never silently ignored (closed-registry + placeholder  > silent
    /// 404). Add new ids here whenever a new demo is published on any platform.
    static let allowedIds: Set<String> = [
        // ── 3D Basics ───────────────────────────────────────────────────
        "model-viewer", "geometry", "animation", "multi-model", "scene-gallery",
        // ── Lighting ────────────────────────────────────────────────────
        "lighting", "movable-light", "fog", "dynamic-sky",
        // ── Content ─────────────────────────────────────────────────────
        "text", "lines-paths", "image", "billboard",
        // ── Interaction ─────────────────────────────────────────────────
        "camera-controls", "collision",
        // ── Advanced ────────────────────────────────────────────────────
        "physics", "double-pendulum", "custom-mesh", "materials", "spatial-audio",
        "post-processing", "reflection-probes", "secondary-camera", "shape",
        // ── AR (iOS-only on device) ──────────────────────────────────────
        "ar-placement", "ar-instant-placement", "ar-orbital", "ar-lighting",
        "ar-recording", "ar-rerun",
        // Coming-soon AR (routed to placeholder)
        "ar-image", "ar-face", "ar-cloud-anchor", "ar-depth-occlusion",
        "ar-eis", "ar-pose-placement", "ar-rooftop", "ar-streetscape",
        "ar-terrain",
        // Coming-soon 3D (routed to placeholder)
        "gesture-editing", "video", "view-node",
    ]

    /// Resolve a demo id to its presented `View`. Returns a fallback
    /// "Coming soon" view for ids that pass `allowedIds` but don't yet
    /// have an iOS destination wired up — this keeps the QR / deep-link
    /// path working as soon as a new id is published, even if the iOS
    /// catalogue lags behind.
    ///
    /// `@MainActor`-isolated because it constructs SwiftUI `View` values,
    /// which are themselves main-actor-isolated; the only call site
    /// (`ContentView`'s `.fullScreenCover` / `.sheet`) already runs on the
    /// main actor.
    @MainActor
    @ViewBuilder
    static func destination(for id: String) -> some View {
        switch id {
        // ── AR Rerun debug ───────────────────────────────────────────
        case "ar-rerun":
            #if os(iOS)
            RerunDebugDemo()
            #else
            DeepLinkPlaceholder(id: id, reason: "AR Rerun is iOS-only on this build.")
            #endif

        // ── 3D Basics ────────────────────────────────────────────────
        case "animation":     AnimationDemo()
        case "geometry":      GeometryDemo()
        case "model-viewer":  ModelViewerDemo()
        case "multi-model":   MultiModelDemo()
        case "scene-gallery": SceneGalleryDemo()

        // ── Lighting ─────────────────────────────────────────────────
        case "lighting":      LightingDemo()
        case "movable-light": MovableLightDemo()
        case "dynamic-sky":   DynamicSkyDemo()
        case "fog":           FogDemo()

        // ── Content ──────────────────────────────────────────────────
        case "billboard":     BillboardDemo()
        case "image":         ImageDemo()
        case "lines-paths":   LinesPathsDemo()
        case "text":          TextDemo()

        // ── Interaction ──────────────────────────────────────────────
        case "camera-controls":  CameraControlsDemo()
        case "collision":        CollisionHitTestDemo()
        case "gesture-editing":  GestureEditingDemo()

        // ── Advanced ─────────────────────────────────────────────────
        case "custom-mesh":     CustomMeshDemo()
        case "double-pendulum": DoublePendulumDemo()
        case "materials":       MaterialsDemo()
        case "physics":         PhysicsDemo()
        case "shape":           GeometryDemo()    // routed to shapes gallery
        case "spatial-audio":   SpatialAudioDemo()

        // ── AR (iOS device-only) ──────────────────────────────────────
        case "ar-instant-placement":
            #if os(iOS)
            ARInstantPlacementDemo()
            #else
            DeepLinkPlaceholder(id: id, reason: "AR demos are iOS-only on this build.")
            #endif
        case "ar-lighting":
            #if os(iOS)
            ARLightingDemo()
            #else
            DeepLinkPlaceholder(id: id, reason: "AR demos are iOS-only on this build.")
            #endif
        case "ar-orbital":
            #if os(iOS)
            OrbitalARDemo()
            #else
            DeepLinkPlaceholder(id: id, reason: "AR demos are iOS-only on this build.")
            #endif
        case "ar-placement":
            #if os(iOS)
            ARPlacementDemo()
            #else
            DeepLinkPlaceholder(id: id, reason: "AR demos are iOS-only on this build.")
            #endif
        case "ar-recording":
            #if os(iOS)
            ARRecorderDemo()
            #else
            DeepLinkPlaceholder(id: id, reason: "AR demos are iOS-only on this build.")
            #endif

        // ── Coming-soon — placeholder keeps URL live ──────────────────
        default:
            DeepLinkPlaceholder(id: id, reason: "This demo isn't available in the iOS app yet — open it on Android, or browse the Samples tab for the full iOS catalog.")
        }
    }
}

/// Tiny placeholder shown when a deep-link id is recognised by the
/// router but doesn't yet have a destination wired in
/// `DemoDeepLinkRegistry`. Communicates the gap clearly and offers a
/// way out (close + browse the Scenes tab).
private struct DeepLinkPlaceholder: View {
    let id: String
    let reason: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 16) {
            Spacer()
            Image(systemName: "qrcode.viewfinder")
                .font(.system(size: 48))
                .foregroundStyle(.secondary)
            Text("Demo: \(id)")
                .font(.headline)
            Text(reason)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
            Spacer()
            Button("Close") { dismiss() }
                .buttonStyle(.bordered)
                .padding(.bottom, 24)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        #if os(iOS)
        .background(Color(UIColor.systemBackground))
        #endif
    }
}
