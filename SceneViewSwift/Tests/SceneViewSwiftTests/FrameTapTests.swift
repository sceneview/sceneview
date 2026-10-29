#if os(iOS)
import CoreGraphics
import CoreVideo
import Metal
import XCTest
@testable import SceneViewSwift

/// The GPU half of video capture: a rendered texture scaled into an encoder pixel buffer.
///
/// Each texture holds sRGB mid-grey (0.5, about 0.216 in linear light) over black. Reading a
/// format with the wrong transfer function turns the grey into about 55 or 188 instead of
/// 128, and a flipped copy puts the black on top, so both mistakes fail.
final class FrameTapTests: XCTestCase {
    /// sRGB-encoded 0.5 as an 8-bit value.
    private static let grey: UInt8 = 128

    func testCopiesTextureIntoPixelBuffer() throws {
        let g = Self.grey
        try assertTwoTone(format: .bgra8Unorm, bytesPerPixel: 4, grey: [g, g, g, 255], black: [0, 0, 0, 255])
    }

    func testCopiesSRGBTextureIntoPixelBuffer() throws {
        // Same bytes: the sampler decodes them to linear 0.216, the copy encodes them back.
        let g = Self.grey
        try assertTwoTone(format: .bgra8Unorm_srgb, bytesPerPixel: 4, grey: [g, g, g, 255], black: [0, 0, 0, 255])
    }

    func testCopiesHalfFloatTextureIntoPixelBuffer() throws {
        // RGBA little-endian half floats: linear 0.2158 is 0x32E8, 1.0 is 0x3C00.
        let grey: [UInt8] = [0xE8, 0x32, 0xE8, 0x32, 0xE8, 0x32, 0x00, 0x3C]
        let black: [UInt8] = [0, 0, 0, 0, 0, 0, 0x00, 0x3C]
        try assertTwoTone(format: .rgba16Float, bytesPerPixel: 8, grey: grey, black: black)
    }

    func testAFrameOfAnotherAspectRatioIsLetterboxedNotStretched() throws {
        // A landscape frame (the view turned mid-recording) into a portrait video: one scale
        // for both edges, centred, black above and below.
        let g = Self.grey
        let buffer = try copy(format: .bgra8Unorm, bytesPerPixel: 4, width: 128, height: 64,
                              sinkWidth: 32, sinkHeight: 64) { _, _ in [g, g, g, 255] }
        // 128x64 scaled by 0.25 is 32x16, rows 24 to 40.
        assertGrey(pixel(buffer, x: 16, y: 32), "centre")
        assertGrey(pixel(buffer, x: 1, y: 30), "left edge, no side bars")
        assertGrey(pixel(buffer, x: 30, y: 34), "right edge, no side bars")
        assertBlack(pixel(buffer, x: 16, y: 8), "bar above")
        assertBlack(pixel(buffer, x: 16, y: 56), "bar below")
    }

    func testAspectFitCentresWithOneScale() {
        let transform = FrameTap.aspectFit(CGSize(width: 200, height: 100), in: CGSize(width: 100, height: 200))
        let rect = CGRect(x: 0, y: 0, width: 200, height: 100).applying(transform)
        XCTAssertEqual(rect.width, 100, accuracy: 0.001)
        XCTAssertEqual(rect.height, 50, accuracy: 0.001)
        XCTAssertEqual(rect.minX, 0, accuracy: 0.001)
        XCTAssertEqual(rect.minY, 75, accuracy: 0.001)
    }

    // MARK: - Helpers

    /// A 64x128 texture, grey on the top half and black on the bottom, copied at half size.
    private func assertTwoTone(format: MTLPixelFormat, bytesPerPixel: Int, grey: [UInt8], black: [UInt8],
                               file: StaticString = #filePath, line: UInt = #line) throws {
        let buffer = try copy(format: format, bytesPerPixel: bytesPerPixel, width: 64, height: 128,
                              sinkWidth: 32, sinkHeight: 64) { _, y in y < 64 ? grey : black }
        assertGrey(pixel(buffer, x: 16, y: 16), "top half (\(format.rawValue))", file: file, line: line)
        assertBlack(pixel(buffer, x: 16, y: 48), "bottom half (\(format.rawValue))", file: file, line: line)
    }

    private func assertGrey(_ pixel: [UInt8], _ message: String, file: StaticString = #filePath, line: UInt = #line) {
        for (channel, value) in zip(["blue", "green", "red"], pixel.prefix(3)) {
            XCTAssertEqual(Int(value), Int(Self.grey), accuracy: 3, "\(message) \(channel)", file: file, line: line)
        }
    }

    private func assertBlack(_ pixel: [UInt8], _ message: String, file: StaticString = #filePath, line: UInt = #line) {
        for (channel, value) in zip(["blue", "green", "red"], pixel.prefix(3)) {
            XCTAssertLessThan(value, 4, "\(message) \(channel)", file: file, line: line)
        }
    }

    /// BGRA of the pixel at (`x`, `y`), row 0 at the top.
    private func pixel(_ buffer: CVPixelBuffer, x: Int, y: Int) -> [UInt8] {
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(buffer)?.assumingMemoryBound(to: UInt8.self) else { return [] }
        let start = base + y * CVPixelBufferGetBytesPerRow(buffer) + x * 4
        return Array(UnsafeBufferPointer(start: start, count: 4))
    }

    /// Fills a GPU-private texture through a blit (row 0 at the top), runs it through the
    /// tap and returns the pixel buffer handed to the sink.
    private func copy(format: MTLPixelFormat, bytesPerPixel: Int, width w: Int, height h: Int,
                      sinkWidth: Int, sinkHeight: Int, texel: (Int, Int) -> [UInt8]) throws -> CVPixelBuffer {
        guard let device = MTLCreateSystemDefaultDevice(),
              let queue = device.makeCommandQueue() else { throw XCTSkip("No Metal device") }

        let descriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: format, width: w, height: h, mipmapped: false)
        descriptor.usage = [.shaderRead, .renderTarget]
        descriptor.storageMode = .private
        let texture = try XCTUnwrap(device.makeTexture(descriptor: descriptor))
        var bytes: [UInt8] = []
        bytes.reserveCapacity(w * h * bytesPerPixel)
        for y in 0..<h { for x in 0..<w { bytes += texel(x, y) } }
        let staging = try XCTUnwrap(device.makeBuffer(bytes: bytes, length: bytes.count))
        let fill = try XCTUnwrap(queue.makeCommandBuffer())
        let blit = try XCTUnwrap(fill.makeBlitCommandEncoder())
        blit.copy(from: staging, sourceOffset: 0, sourceBytesPerRow: w * bytesPerPixel,
                  sourceBytesPerImage: bytes.count, sourceSize: MTLSize(width: w, height: h, depth: 1),
                  to: texture, destinationSlice: 0, destinationLevel: 0,
                  destinationOrigin: MTLOrigin(x: 0, y: 0, z: 0))
        blit.endEncoding()
        fill.commit()

        let sink = BufferSink(width: sinkWidth, height: sinkHeight)
        let tap = FrameTap()
        tap.sink = sink
        let commandBuffer = try XCTUnwrap(queue.makeCommandBuffer())
        tap.capture(texture, device: device, commandBuffer: commandBuffer)
        commandBuffer.commit()
        commandBuffer.waitUntilCompleted()

        // The append runs in the command buffer's completed handler, possibly just after the wait.
        let deadline = Date().addingTimeInterval(5)
        while sink.appended == nil, Date() < deadline { Thread.sleep(forTimeInterval: 0.01) }
        return try XCTUnwrap(sink.appended)
    }
}

private final class BufferSink: VideoFrameSink, @unchecked Sendable {
    private let pool: CVPixelBufferPool
    private let lock = NSLock()
    private var buffer: CVPixelBuffer?

    init(width: Int, height: Int) {
        var pool: CVPixelBufferPool?
        CVPixelBufferPoolCreate(nil, nil, [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: width,
            kCVPixelBufferHeightKey as String: height,
            kCVPixelBufferMetalCompatibilityKey as String: true,
            kCVPixelBufferIOSurfacePropertiesKey as String: [String: Any](),
        ] as CFDictionary, &pool)
        self.pool = pool!
    }

    var appended: CVPixelBuffer? { lock.lock(); defer { lock.unlock() }; return buffer }

    func reserveFrame(at hostTime: CFTimeInterval) -> CVPixelBuffer? {
        var buffer: CVPixelBuffer?
        CVPixelBufferPoolCreatePixelBuffer(nil, pool, &buffer)
        return buffer
    }

    func append(_ buffer: CVPixelBuffer, at hostTime: CFTimeInterval) {
        lock.lock(); self.buffer = buffer; lock.unlock()
    }

    func discardFrame() {}
}
#endif
