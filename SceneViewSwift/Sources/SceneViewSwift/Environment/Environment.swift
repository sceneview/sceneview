#if os(iOS) || os(macOS) || os(visionOS)
import RealityKit
import Foundation
import CoreGraphics
import ImageIO

/// Image-based lighting and skybox configuration for a 3D scene.
///
/// Mirrors SceneView Android's `Environment` — provides preset environments
/// and custom HDR loading with caching.
///
/// ```swift
/// SceneView { content in
///     // ...
/// }
/// .environment(.studio)
/// ```
public struct SceneEnvironment: Sendable {
    /// Display name for UI pickers.
    public let name: String

    /// Bundle resource name for the HDR/EXR file.
    public let hdrResource: String?

    /// Light intensity multiplier.
    public var intensity: Float

    /// Whether to render the environment as a skybox background.
    public var showSkybox: Bool

    public init(
        name: String,
        hdrResource: String? = nil,
        intensity: Float = 1.0,
        showSkybox: Bool = true
    ) {
        self.name = name
        self.hdrResource = hdrResource
        self.intensity = intensity
        self.showSkybox = showSkybox
    }

    /// Loads the IBL environment resource into a RealityKit scene.
    ///
    /// Uses a shared cache to avoid redundant I/O for repeated loads.
    ///
    /// - Returns: An `EnvironmentResource` ready to apply via
    ///   `ImageBasedLightComponent`.
    /// - Throws: `SceneEnvironmentError.noResourceSpecified` if no HDR resource
    ///   name was provided, or a RealityKit error if the file cannot be loaded.
    ///
    /// ### Radiance `.hdr` support (#2896)
    ///
    /// `EnvironmentResource(named:)` only resolves the asset kinds Xcode
    /// *compiles* into a RealityKit resource — `.exr`, asset-catalog textures,
    /// Reality Composer Pro scenes. A plain Radiance `.hdr` copied into the
    /// bundle as a resource file is **not** one of them: it fails with
    /// `resourceLoadFailure`, and the caller silently falls back to **no custom
    /// IBL** — the `ImageBasedLightComponent` is never set, so the scene keeps
    /// RealityView's own default environment lighting (#2842/#2868); it is not
    /// unlit — and to **no skybox at all**. That is what left every iOS demo
    /// dim-on-black and made
    /// `dynamic-sky` render no sky at all — all seven bundled presets
    /// (`studio.hdr`, `outdoor_cloudy.hdr`, …) are Radiance files.
    ///
    /// ImageIO reads `public.radiance` natively, so a failed `named:` lookup
    /// falls back to decoding the file into a half-float equirectangular
    /// `CGImage` and building the resource from that. The `named:` path is
    /// still tried first so `.exr` / asset-catalog / RCP resources keep
    /// working unchanged.
    public func load() async throws -> EnvironmentResource {
        guard let hdrResource else {
            throw SceneEnvironmentError.noResourceSpecified
        }
        // Check cache first
        if let cached = EnvironmentCache.shared.get(hdrResource) {
            return cached
        }
        let resource: EnvironmentResource
        do {
            resource = try await EnvironmentResource(named: hdrResource)
        } catch {
            // Re-throw the ORIGINAL RealityKit error when the fallback can't
            // apply — a genuinely missing resource must not be reported as a
            // decode problem.
            guard let image = Self.equirectangularImage(named: hdrResource) else {
                throw error
            }
            resource = try await EnvironmentResource(
                equirectangular: image,
                withName: hdrResource
            )
        }
        EnvironmentCache.shared.set(hdrResource, resource: resource)
        return resource
    }

    /// Decodes a bundled equirectangular image (Radiance `.hdr`, and anything
    /// else ImageIO reads) into a `CGImage` for
    /// `EnvironmentResource(equirectangular:withName:)`.
    ///
    /// Looks the resource up in `Bundle.main` — the same bundle
    /// `EnvironmentResource(named:)` defaults to — both as a full filename and
    /// as a name/extension pair, so `"studio.hdr"` and `"studio"` both resolve.
    ///
    /// Returns `nil` when the file isn't in the bundle or ImageIO can't decode
    /// it, which keeps the original load error as the reported failure.
    private static func equirectangularImage(named resource: String) -> CGImage? {
        let candidates: [URL?] = [
            Bundle.main.url(forResource: resource, withExtension: nil),
            Bundle.main.url(
                forResource: (resource as NSString).deletingPathExtension,
                withExtension: (resource as NSString).pathExtension.isEmpty
                    ? "hdr"
                    : (resource as NSString).pathExtension
            )
        ]
        for case let url? in candidates {
            guard
                let source = CGImageSourceCreateWithURL(url as CFURL, nil),
                let image = CGImageSourceCreateImageAtIndex(source, 0, nil)
            else { continue }
            return image
        }
        return nil
    }

    /// Creates a custom environment from an HDR file in the bundle.
    ///
    /// - Parameters:
    ///   - name: Display name.
    ///   - hdrFile: HDR file name in the bundle (e.g. "custom_env.hdr").
    ///   - intensity: Light intensity multiplier.
    ///   - showSkybox: Whether to show as background.
    public static func custom(
        name: String,
        hdrFile: String,
        intensity: Float = 1.0,
        showSkybox: Bool = true
    ) -> SceneEnvironment {
        SceneEnvironment(
            name: name,
            hdrResource: hdrFile,
            intensity: intensity,
            showSkybox: showSkybox
        )
    }
}

// MARK: - Errors

/// Errors that can occur when loading a scene environment.
public enum SceneEnvironmentError: LocalizedError {
    /// No HDR resource name was provided to the environment.
    case noResourceSpecified

    public var errorDescription: String? {
        switch self {
        case .noResourceSpecified:
            return "No HDR resource name specified for this SceneEnvironment. "
                + "Provide an hdrResource name or use a preset like .studio."
        }
    }
}

// MARK: - Presets (mirrors Android's bundled HDR environments)

extension SceneEnvironment {
    /// Neutral studio lighting — good default for model viewing.
    public static let studio = SceneEnvironment(
        name: "Studio",
        hdrResource: "studio.hdr",
        intensity: 1.0
    )

    /// Warm outdoor daylight.
    public static let outdoor = SceneEnvironment(
        name: "Outdoor",
        hdrResource: "outdoor_cloudy.hdr",
        intensity: 1.2
    )

    /// Golden hour sunset lighting.
    public static let sunset = SceneEnvironment(
        name: "Sunset",
        hdrResource: "sunset.hdr",
        intensity: 0.8
    )

    /// Night rooftop lighting — dark, moody atmosphere.
    public static let night = SceneEnvironment(
        name: "Night",
        hdrResource: "rooftop_night.hdr",
        intensity: 0.4
    )

    /// Warm studio lighting — cozy, slightly orange tone.
    public static let warm = SceneEnvironment(
        name: "Warm",
        hdrResource: "studio_warm.hdr",
        intensity: 1.0
    )

    /// Autumn field lighting — soft, natural outdoor light.
    public static let autumn = SceneEnvironment(
        name: "Autumn",
        hdrResource: "autumn_field.hdr",
        intensity: 0.9
    )

    /// Deep night sky with the Milky Way — a dramatic CC0 starfield HDRI.
    /// Pairs with metallic PBR materials for chrome-mirror reflections.
    public static let nightSky = SceneEnvironment(
        name: "Night Sky",
        hdrResource: "night_sky.hdr",
        intensity: 0.5,          // dim ambient — stars shouldn't blow out skin tones
        showSkybox: true
    )

    /// All available presets for UI pickers.
    public static let allPresets: [SceneEnvironment] = [
        .studio, .outdoor, .sunset, .night, .warm, .autumn, .nightSky
    ]
}

// MARK: - Environment Cache

/// Thread-safe cache for loaded environment resources.
///
/// Avoids redundant HDR file I/O when switching environments.
final class EnvironmentCache: @unchecked Sendable {
    static let shared = EnvironmentCache()

    private var cache: [String: EnvironmentResource] = [:]
    private let lock = NSLock()

    func get(_ key: String) -> EnvironmentResource? {
        lock.lock()
        defer { lock.unlock() }
        return cache[key]
    }

    func set(_ key: String, resource: EnvironmentResource) {
        lock.lock()
        defer { lock.unlock() }
        cache[key] = resource
    }

    func clear() {
        lock.lock()
        defer { lock.unlock() }
        cache.removeAll()
    }
}

#endif // os(iOS) || os(macOS) || os(visionOS)
