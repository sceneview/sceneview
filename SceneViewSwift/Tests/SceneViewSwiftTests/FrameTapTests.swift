#if os(iOS)
import CoreVideo
import Metal
import XCTest
@testable import SceneViewSwift

/// The GPU half of video capture: a rendered texture scaled into an encoder pixel buffer.
final class FrameTapTests: XCTestCase {
    func testCopiesTextureIntoPixelBuffer() throws {
        try assertCopy(format: .bgra8Unorm, bytesPerPixel: 4, red: [0, 0, 255, 255])
    }

    func testCopiesSRGBTextureIntoPixelBuffer() throws {
        try assertCopy(format: .bgra8Unorm_srgb, bytesPerPixel: 4, red: [0, 0, 255, 255])
    }

    func testCopiesHalfFloatTextureIntoPixelBuffer() throws {
        // RGBA, 1.0 as a little-endian half float is 0x3C00.
        try assertCopy(format: .rgba16Float, bytesPerPixel: 8, red: [0x00, 0x3C, 0, 0, 0, 0, 0x00, 0x3C])
    }

    private func assertCopy(format: MTLPixelFormat, bytesPerPixel: Int, red: [UInt8]) throws {
        guard let device = MTLCreateSystemDefaultDevice(),
              let queue = device.makeCommandQueue() else { throw XCTSkip("No Metal device") }

        // A 64x128 opaque red texture, filled through a blit so it can stay GPU-private.
        let (w, h) = (64, 128)
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: format, width: w, height: h, mipmapped: false)
        descriptor.usage = [.shaderRead, .renderTarget]
        descriptor.storageMode = .private
        let texture = try XCTUnwrap(device.makeTexture(descriptor: descriptor))
        let bytes = [UInt8]((0..<(w * h)).flatMap { _ in red })
        let staging = try XCTUnwrap(device.makeBuffer(bytes: bytes, length: bytes.count))
        let fill = try XCTUnwrap(queue.makeCommandBuffer())
        let blit = try XCTUnwrap(fill.makeBlitCommandEncoder())
        blit.copy(from: staging, sourceOffset: 0, sourceBytesPerRow: w * bytesPerPixel,
                  sourceBytesPerImage: bytes.count, sourceSize: MTLSize(width: w, height: h, depth: 1),
                  to: texture, destinationSlice: 0, destinationLevel: 0,
                  destinationOrigin: MTLOrigin(x: 0, y: 0, z: 0))
        blit.endEncoding()
        fill.commit()

        let sink = BufferSink(width: 32, height: 64)
        let tap = FrameTap()
        tap.sink = sink
        let commandBuffer = try XCTUnwrap(queue.makeCommandBuffer())
        tap.capture(texture, device: device, commandBuffer: commandBuffer)
        commandBuffer.commit()
        commandBuffer.waitUntilCompleted()

        // The append runs in the command buffer's completed handler, possibly just after the wait.
        let deadline = Date().addingTimeInterval(5)
        while sink.appended == nil, Date() < deadline { Thread.sleep(forTimeInterval: 0.01) }

        let buffer = try XCTUnwrap(sink.appended)
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        let base = try XCTUnwrap(CVPixelBufferGetBaseAddress(buffer)).assumingMemoryBound(to: UInt8.self)
        let row = CVPixelBufferGetBytesPerRow(buffer)
        let center = base + (32 * row) + (16 * 4)
        // BGRA: blue, green, red, alpha.
        XCTAssertLessThan(center[0], 16, "blue")
        XCTAssertLessThan(center[1], 16, "green")
        XCTAssertGreaterThan(center[2], 240, "red")
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
