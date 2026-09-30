import XCTest
import simd
@testable import SceneViewDemo

/// Pins the iOS reader of the bundled Real-World Scan (`raccoon_family.spz`) to the numbers the
/// Android `SpzParser` reads from the same file, and the pure helpers the screen prints.
final class SplatScanTests: XCTestCase {

    private func bundledScan() throws -> (SplatScan, Int) {
        let url = try XCTUnwrap(Bundle.main.url(forResource: "raccoon_family", withExtension: "spz"),
                                "raccoon_family.spz is not in the app bundle")
        let data = try Data(contentsOf: url)
        return (try SPZReader.read(data), data.count)
    }

    func testBundledScanDecodesEveryPoint() throws {
        let (scan, bytes) = try bundledScan()
        XCTAssertEqual(scan.count, 233_808)
        XCTAssertEqual(scan.positions.count, scan.count)
        XCTAssertEqual(scan.colors.count, scan.count)
        XCTAssertEqual(scan.sizes.count, scan.count)
        XCTAssertEqual(bytes, 3_306_117)
        // The capture is cropped to the stump: no point further than 0.8 m from the origin.
        XCTAssertLessThan(scan.positions.map { simd_length($0) }.max() ?? 0, 0.8)
    }

    func testFramingTargetsTheStump() throws {
        let (scan, _) = try bundledScan()
        let framing = SplatFraming(scan)
        XCTAssertEqual(framing.centroid.x, 0.019, accuracy: 0.01)
        XCTAssertEqual(framing.centroid.y, 0.085, accuracy: 0.01)
        XCTAssertEqual(framing.centroid.z, 0.020, accuracy: 0.01)
        XCTAssertEqual(framing.subjectRadius, 0.294, accuracy: 0.01)
    }

    func testPointCloudChunksCoverTheScan() throws {
        let (scan, _) = try bundledScan()
        XCTAssertEqual(SplatPointCloud.chunkCount(scan), 15)
        let mesh = SplatPointCloud.mesh(scan, range: 0..<100, centroid: .zero, scale: 1)
        XCTAssertEqual(mesh.positions.count, 400)
        XCTAssertEqual(mesh.uvs.count, 400)
        XCTAssertEqual(mesh.indices.count, 1200)
        XCTAssertEqual(SplatPointCloud.atlasPixels(scan, range: 0..<100).count,
                       RerunPointAtlas.size * RerunPointAtlas.size * 4)
    }

    func testRejectsWhatIsNotAScan() {
        XCTAssertThrowsError(try SPZReader.read(Data("not a scan at all".utf8))) { error in
            XCTAssertEqual(error as? SplatScanError, .notGzip)
        }
        var header = Data(count: 16)
        header[0] = 0x4E
        XCTAssertThrowsError(try SPZReader.decode(header)) { error in
            XCTAssertEqual(error as? SplatScanError, .badMagic)
        }
    }

    func testDecodesAHandWrittenPoint() throws {
        // One point, version 2, degree 0, 12 fractional bits.
        var bytes: [UInt8] = [0x4E, 0x47, 0x53, 0x50, 2, 0, 0, 0, 1, 0, 0, 0, 0, 12, 0, 0]
        bytes += [0x00, 0x10, 0x00, 0x00, 0xF0, 0xFF, 0x00, 0x08, 0x00] // x 1, y -1, z 0.5
        bytes += [255]                                                    // alpha
        bytes += [128, 255, 0]                                            // colour
        bytes += [160, 160, 160]                                          // scale exp(0)
        bytes += [0, 0, 0]                                                // rotation
        let scan = try SPZReader.decode(Data(bytes))
        XCTAssertEqual(scan.positions[0], SIMD3(1, -1, 0.5))
        XCTAssertEqual(scan.sizes[0], 1, accuracy: 1e-5)
        XCTAssertEqual(scan.colors[0] >> 24, 255)
        XCTAssertEqual((scan.colors[0] >> 16) & 0xFF, 128)
        XCTAssertEqual((scan.colors[0] >> 8) & 0xFF, 255)
        XCTAssertEqual(scan.colors[0] & 0xFF, 0)
    }

    func testPrintsCountsLikeAndroid() {
        XCTAssertEqual(SplatPreviewDemo.formatPoints(233_808), "233 808")
        XCTAssertEqual(SplatPreviewDemo.formatPoints(1_000), "1 000")
        XCTAssertEqual(SplatPreviewDemo.formatPoints(999), "999")
        XCTAssertEqual(SplatPreviewDemo.formatMegabytes(3_306_117), "3.2 MB")
    }
}
