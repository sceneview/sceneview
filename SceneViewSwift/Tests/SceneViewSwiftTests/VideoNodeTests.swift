#if os(iOS) || os(macOS) || os(visionOS)
import XCTest
import RealityKit
import AVFoundation
@testable import SceneViewSwift

// Test classes run on the main actor: their RealityKit node factories
// (`LightNode.directional`, `node.entity`, …) are `@MainActor`. (#1054)
@MainActor
final class VideoNodeTests: XCTestCase {

    // MARK: - Resource resolution

    func testExtensionlessNameTriesEveryCommonVideoExtension() {
        // "sample" used to be parsed as an empty base name with the extension
        // "sample", so it matched nothing and silently fell back to a
        // nonexistent file path.
        do {
            _ = try VideoNode.resolveVideoURL(named: "sample_video_that_does_not_exist")
            XCTFail("Expected resolveVideoURL to throw for a missing resource")
        } catch let VideoNodeError.resourceNotFound(name, triedExtensions) {
            XCTAssertEqual(name, "sample_video_that_does_not_exist")
            XCTAssertEqual(triedExtensions, ["mp4", "mov", "m4v"])
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
    }

    func testNameWithExtensionOnlyTriesThatExtension() {
        do {
            _ = try VideoNode.resolveVideoURL(named: "missing_clip.mov")
            XCTFail("Expected resolveVideoURL to throw for a missing resource")
        } catch let VideoNodeError.resourceNotFound(_, triedExtensions) {
            XCTAssertEqual(triedExtensions, ["mov"])
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
    }

    func testResolvesAnExistingFileOnDiskWithoutItsExtension() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(
            at: directory, withIntermediateDirectories: true
        )
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = directory.appendingPathComponent("clip.m4v")
        try Data([0x00]).write(to: file)

        let resolved = try VideoNode.resolveVideoURL(
            named: directory.appendingPathComponent("clip").path
        )
        XCTAssertEqual(resolved.lastPathComponent, "clip.m4v")
    }

    func testResolvesAnExistingFileOnDiskThatHasNoExtension() throws {
        // A path on disk is a documented input and a file can genuinely have no
        // extension: the name as given must be checked before the mp4/mov/m4v
        // variants are tried.
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(
            at: directory, withIntermediateDirectories: true
        )
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = directory.appendingPathComponent("clip")
        try Data([0x00]).write(to: file)

        let resolved = try VideoNode.resolveVideoURL(named: file.path)
        XCTAssertEqual(resolved.lastPathComponent, "clip")
    }

    func testLoadOfMissingResourceReturnsAnEmptyPlayerRatherThanABogusURL() {
        let node = VideoNode.load("sample_video_that_does_not_exist")
        // No item at all — never an AVPlayer pointed at a path that does not
        // exist, which used to report success while nothing played.
        XCTAssertNil(node.player.currentItem)
    }

    func testCreateWithPlayer() {
        let player = AVPlayer()
        let node = VideoNode.create(player: player, width: 1.6, height: 0.9)
        XCTAssertEqual(node.entity.name, "VideoNode")
        XCTAssertEqual(node.scale.x, 1.6, accuracy: 0.001)
        XCTAssertEqual(node.scale.y, 0.9, accuracy: 0.001)
    }

    func testPosition() {
        let player = AVPlayer()
        let node = VideoNode.create(player: player)
            .position(.init(x: 0, y: 1.5, z: -3))
        XCTAssertEqual(node.position.y, 1.5, accuracy: 0.001)
        XCTAssertEqual(node.position.z, -3.0, accuracy: 0.001)
    }

    func testSize() {
        let player = AVPlayer()
        let node = VideoNode.create(player: player)
            .size(width: 2.0, height: 1.0)
        XCTAssertEqual(node.scale.x, 2.0, accuracy: 0.001)
        XCTAssertEqual(node.scale.y, 1.0, accuracy: 0.001)
    }

    func testRotation() {
        let player = AVPlayer()
        let quat = simd_quatf(angle: .pi / 4, axis: SIMD3<Float>(0, 1, 0))
        let node = VideoNode.create(player: player)
            .rotation(quat)
        XCTAssertEqual(node.rotation.angle, quat.angle, accuracy: 0.001)
    }

    func testScale() {
        let player = AVPlayer()
        let node = VideoNode.create(player: player)
            .scale(2.0)
        XCTAssertEqual(node.scale.x, 2.0, accuracy: 0.001)
    }

    func testPlaybackControls() {
        let player = AVPlayer()
        let node = VideoNode.create(player: player)
        // Without a valid item, these should not crash
        node.play()
        node.pause()
        node.stop()
        node.seek(to: 5.0)
        XCTAssertFalse(node.isPlaying)
    }

    func testVolume() {
        let player = AVPlayer()
        let node = VideoNode.create(player: player)
        node.volume(0.5)
        XCTAssertEqual(node.player.volume, 0.5, accuracy: 0.001)
    }

    func testMuted() {
        let player = AVPlayer()
        let node = VideoNode.create(player: player)
        node.muted(true)
        XCTAssertTrue(node.player.isMuted)
        node.muted(false)
        XCTAssertFalse(node.player.isMuted)
    }

    func testChaining() {
        let player = AVPlayer()
        let node = VideoNode.create(player: player)
            .position(.init(x: 0, y: 2, z: -5))
            .size(width: 3.2, height: 1.8)
        XCTAssertEqual(node.position.y, 2.0, accuracy: 0.001)
        XCTAssertEqual(node.scale.x, 3.2, accuracy: 0.001)
    }

    // MARK: - Property get/set

    func testPositionPropertyGetSet() {
        let player = AVPlayer()
        var node = VideoNode.create(player: player)
        node.position = SIMD3<Float>(5, 6, 7)
        XCTAssertEqual(node.position.x, 5.0, accuracy: 0.001)
        XCTAssertEqual(node.position.y, 6.0, accuracy: 0.001)
        XCTAssertEqual(node.position.z, 7.0, accuracy: 0.001)
    }

    func testRotationPropertyGetSet() {
        let player = AVPlayer()
        var node = VideoNode.create(player: player)
        let quat = simd_quatf(angle: .pi / 3, axis: SIMD3<Float>(0, 1, 0))
        node.rotation = quat
        XCTAssertEqual(node.rotation.angle, quat.angle, accuracy: 0.001)
    }

    func testScalePropertyGetSet() {
        let player = AVPlayer()
        var node = VideoNode.create(player: player)
        node.scale = SIMD3<Float>(2, 3, 4)
        XCTAssertEqual(node.scale.x, 2.0, accuracy: 0.001)
        XCTAssertEqual(node.scale.y, 3.0, accuracy: 0.001)
        XCTAssertEqual(node.scale.z, 4.0, accuracy: 0.001)
    }

    // MARK: - Default dimensions

    func testDefaultDimensions() {
        let player = AVPlayer()
        let node = VideoNode.create(player: player)
        // Default width=1.6, height=0.9
        XCTAssertEqual(node.scale.x, 1.6, accuracy: 0.001)
        XCTAssertEqual(node.scale.y, 0.9, accuracy: 0.001)
        XCTAssertEqual(node.scale.z, 1.0, accuracy: 0.001)
    }

    // MARK: - Seek edge cases

    func testSeekToZero() {
        let player = AVPlayer()
        let node = VideoNode.create(player: player)
        node.seek(to: 0.0)
        // Should not crash
        XCTAssertNotNil(node.entity)
    }

    func testSeekToNegative() {
        let player = AVPlayer()
        let node = VideoNode.create(player: player)
        node.seek(to: -5.0)
        // Should not crash
        XCTAssertNotNil(node.entity)
    }

    // MARK: - Video player component

    func testEntityHasVideoPlayerComponent() {
        if #available(macOS 15.0, iOS 18.0, visionOS 1.0, *) {
            let player = AVPlayer()
            let node = VideoNode.create(player: player)
            let component = node.entity.components[VideoPlayerComponent.self]
            XCTAssertNotNil(component)
        }
    }
}
#endif
