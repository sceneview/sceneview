import SwiftUI
import RealityKit
import SceneViewSwift

/// Demonstrates VideoNode — a video stream rendered on a flat 3D quad.
///
/// Mirrors SceneView Android's `VideoDemo`. A looping clip from Big Buck Bunny
/// (Blender Foundation, CC-BY 3.0) plays on a 16:9 plane in the scene.
/// Users can pause/resume and mute from the settings sheet.
struct VideoTextureDemo: View {

    // MARK: - State

    @State private var videoNode: VideoNode?
    @State private var isPlaying = true
    @State private var isMuted = false
    @State private var isLooping = true

    // MARK: - Body

    var body: some View {
        ZStack {
            sceneView
            playPauseButton
        }
        .background(Color.black)
        .demoSettingsSheet { settingsSheet }
        .task { buildVideoNode() }
    }

    // MARK: - Scene

    private var sceneView: some View {
        SceneView { root in
            // Floor plane for depth reference
            let floor = GeometryNode.plane(width: 6, depth: 4, color: .init(white: 0.08, alpha: 1))
            floor.entity.position = SIMD3(0, -1.1, -3)
            root.addChild(floor.entity)

            // Back wall plane (depth acts as height once rotated 90° around X)
            let wall = GeometryNode.plane(width: 6, depth: 3, color: .init(white: 0.05, alpha: 1))
            wall.entity.position = SIMD3(0, 0.4, -4.01)
            wall.entity.orientation = simd_quatf(angle: .pi / 2, axis: [1, 0, 0])
            root.addChild(wall.entity)

            if let videoNode {
                root.addChild(videoNode.entity)
            }
        }
        .cameraControls(.orbit)
        .id("video-\(videoNode != nil)")
        .ignoresSafeArea()
    }

    // MARK: - Play/Pause overlay button

    private var playPauseButton: some View {
        VStack {
            Spacer()
            HStack {
                Spacer()
                Button {
                    togglePlayback()
                } label: {
                    Image(systemName: isPlaying ? "pause.fill" : "play.fill")
                        .font(.title2)
                        .foregroundStyle(.white)
                        .frame(width: 48, height: 48)
                        .background(.ultraThinMaterial)
                        .clipShape(Circle())
                }
                .padding(.bottom, 24)
                .padding(.trailing, 24)
            }
        }
    }

    // MARK: - Settings sheet

    @ViewBuilder
    private var settingsSheet: some View {
        Toggle(isOn: Binding(
            get: { isPlaying },
            set: { _ in togglePlayback() }
        )) {
            Label("Playing", systemImage: "play.fill")
        }
        .tint(.blue)

        Toggle(isOn: Binding(
            get: { isMuted },
            set: { newVal in
                isMuted = newVal
                videoNode?.muted(newVal)
            }
        )) {
            Label("Muted", systemImage: "speaker.slash.fill")
        }
        .tint(.orange)

        Toggle(isOn: Binding(
            get: { isLooping },
            set: { _ in
                // Rebuild node with new loop setting
                isLooping.toggle()
                buildVideoNode()
            }
        )) {
            Label("Loop", systemImage: "repeat")
        }
        .tint(.green)
    }

    // MARK: - Helpers

    private func buildVideoNode() {
        let node = VideoNode.load("sample", width: 2.4, height: 1.35, loop: isLooping)
        node.entity.position = SIMD3(0, 0.3, -3)
        node.muted(isMuted)
        if isPlaying { node.play() }
        videoNode = node
    }

    private func togglePlayback() {
        guard let videoNode else { return }
        if isPlaying {
            videoNode.pause()
        } else {
            videoNode.play()
        }
        isPlaying.toggle()
    }
}
