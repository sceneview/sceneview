import SwiftUI
import RealityKit
import SceneViewSwift

/// Dynamic sky -- slider to control time of day from midnight to midnight.
struct DynamicSkyDemo: View {
    @State private var timeOfDay: Float = 12

    private var timeLabel: String {
        let hour = Int(timeOfDay) % 24
        let minute = Int((timeOfDay - Float(Int(timeOfDay))) * 60)
        return String(format: "%02d:%02d", hour, minute)
    }

    private var periodLabel: String {
        if timeOfDay < 5 || timeOfDay > 20 { return "Night" }
        if timeOfDay < 7 { return "Dawn" }
        if timeOfDay < 10 { return "Morning" }
        if timeOfDay < 14 { return "Noon" }
        if timeOfDay < 17 { return "Afternoon" }
        if timeOfDay < 19 { return "Sunset" }
        return "Dusk"
    }

    /// Pick an HDR environment that visually matches the current time of day so the
    /// background isn't a flat black box when the sun is up. Mirrors the Android fix
    /// in commit `15bcaf8c`. Three buckets is coarse — the tint jumps rather than
    /// fading smoothly — but it covers the obvious user expectations. The deep-night
    /// bucket uses the dramatic `night_sky` Milky Way HDR (v4.4.0, #1219) so the
    /// midnight sky actually wraps the scene instead of fading to neutral dark.
    private var skyEnvironment: SceneEnvironment {
        switch timeOfDay {
        case ..<6, 19...: return .nightSky
        case ..<9, 17...: return .sunset
        default: return .outdoor
        }
    }

    var body: some View {
        sceneContent
            .demoSettingsSheet {
                controlsSheet
            }
    }

    @ViewBuilder
    private var sceneContent: some View {
        ZStack {
            SceneView { root in
                // Ground plane. Sized to the skyline's own footprint rather
                // than a 5 m slab: the auto-framing pass fits the *union*
                // bounding sphere, so an oversized plane pushed the camera far
                // enough back to shrink every building to a few pixels (#2896).
                // Base colour barely drives how this plane reads: at roughness
                // 0.5 its dielectric specular lobe reflects the sky, so it
                // tracks the time of day (measured: dropping the tint from 0.15
                // to 0.05 moved the rendered plane only 166 → 152 / 255). That
                // sky-coupling is the point here — leave the tint alone.
                let ground = GeometryNode.plane(width: 2.6, depth: 2.6, color: .init(white: 0.15, alpha: 1))
                ground.entity.position = .init(x: 0, y: -0.3, z: -2)
                root.addChild(ground.entity)

                // Buildings (cubes of varying heights). Taller than wide so the
                // silhouette reads as a skyline against the sky instead of a
                // scatter of blocks on a floor.
                let buildings: [(Float, Float, Float, UIColor)] = [
                    (-0.6, 0.65, -2.5, .systemGray),
                    (-0.2, 0.95, -2.8, .systemGray2),
                    (0.2, 0.48, -2.3, .systemGray3),
                    (0.5, 0.80, -2.6, .systemGray),
                    (0.8, 0.56, -2.4, .systemGray2),
                ]
                for (x, h, z, color) in buildings {
                    let building = GeometryNode.cube(size: 0.2, color: color, cornerRadius: 0.01)
                    building.entity.scale = .init(x: 1, y: h / 0.2, z: 1)
                    building.entity.position = .init(x: x, y: h / 2 - 0.3, z: z)
                    root.addChild(building.entity)
                }

                // Dynamic sky light
                let sky = DynamicSkyNode(timeOfDay: timeOfDay, turbidity: 3, sunIntensity: 1500)
                root.addChild(sky.entity)
            }
            .environment(skyEnvironment)
            .cameraControls(.orbit)
            // The orbit camera's 30° default pitch puts the horizon exactly on
            // the top edge of a 60°-FOV frame, so a time-of-day demo showed
            // everything *except* its sky. Drop to 12° for a low skyline angle
            // where the sky fills most of the frame (#2896).
            .cameraOrbit(elevation: .pi / 15)
            .id("sky-\(Int(timeOfDay * 10))-\(skyEnvironment.name)")
            .ignoresSafeArea()
        }
        .background(Color.black)
    }

    @ViewBuilder
    private var controlsSheet: some View {
        VStack(spacing: 10) {
            HStack {
                Image(systemName: timeIcon)
                    .foregroundStyle(.yellow)
                Text(timeLabel)
                    .font(.title2).bold()
                    .monospacedDigit()
                Text(periodLabel)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(.gray.opacity(0.15))
                    .clipShape(Capsule())
            }

            Slider(value: $timeOfDay, in: 0...24, step: 0.25)
                .tint(.orange)
                .accessibilityLabel("Time of day slider")
                .accessibilityValue("\(timeLabel), \(periodLabel)")

            HStack {
                Text("00:00").font(.caption2).foregroundStyle(.secondary)
                Spacer()
                Text("12:00").font(.caption2).foregroundStyle(.secondary)
                Spacer()
                Text("24:00").font(.caption2).foregroundStyle(.secondary)
            }
        }
    }

    private var timeIcon: String {
        if timeOfDay < 5 || timeOfDay > 20 { return "moon.stars.fill" }
        if timeOfDay < 7 || timeOfDay > 18 { return "sun.horizon.fill" }
        return "sun.max.fill"
    }
}
