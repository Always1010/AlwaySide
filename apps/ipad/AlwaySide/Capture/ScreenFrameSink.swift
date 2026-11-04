import CoreImage
import CoreMedia
import Foundation
import ImageIO
import ScreenCaptureKit
import UniformTypeIdentifiers

/// The stream calls this object only on its dedicated serial sample queue.
/// CMSampleBuffer and CVPixelBuffer never leave that callback/queue.
final class ScreenFrameSink: NSObject, SCStreamOutput {
    static let maximumDimension = 1600
    static let maximumJPEGBytes = 2 * 1024 * 1024

    private let context = CIContext(options: [.cacheIntermediates: false])
    private let continuation: AsyncStream<CapturedFrame>.Continuation
    private var lastReceiptSeconds: TimeInterval = -.infinity

    init(continuation: AsyncStream<CapturedFrame>.Continuation) {
        self.continuation = continuation
        super.init()
    }

    // AsyncStream's continuation is thread-safe; no queue-owned mutable state is touched.
    func finish() { continuation.finish() }

    func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer,
                of type: SCStreamOutputType) {
        guard type == .screen, sampleBuffer.isValid,
              let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer,
                  createIfNecessary: false) as? [[SCStreamFrameInfo: Any]],
              let rawStatus = attachments.first?[.status] as? Int,
              SCFrameStatus(rawValue: rawStatus) == .complete,
              let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }

        let receiptSeconds = ProcessInfo.processInfo.systemUptime
        guard receiptSeconds - lastReceiptSeconds >= 0.95 else { return }
        lastReceiptSeconds = receiptSeconds
        let receivedAt = Date()

        autoreleasepool {
            let source = CIImage(cvPixelBuffer: pixelBuffer)
            let extent = source.extent
            guard extent.width.isFinite, extent.height.isFinite,
                  extent.width > 0, extent.height > 0 else { return }
            let scale = min(1, CGFloat(Self.maximumDimension) / max(extent.width, extent.height))
            let image = source.transformed(by: CGAffineTransform(translationX: -extent.minX,
                                                                 y: -extent.minY))
                .transformed(by: CGAffineTransform(scaleX: scale, y: scale))
            let width = max(1, Int(floor(extent.width * scale)))
            let height = max(1, Int(floor(extent.height * scale)))
            let bounds = CGRect(x: 0, y: 0, width: CGFloat(width), height: CGFloat(height))
            guard let colorSpace = CGColorSpace(name: CGColorSpace.sRGB),
                  let bitmap = context.createCGImage(image, from: bounds, format: .RGBA8,
                                                    colorSpace: colorSpace) else { return }
            let data = NSMutableData()
            guard let destination = CGImageDestinationCreateWithData(data as CFMutableData,
                UTType.jpeg.identifier as CFString, 1, nil) else { return }
            CGImageDestinationAddImage(destination, bitmap,
                [kCGImageDestinationLossyCompressionQuality: 0.72] as CFDictionary)
            guard CGImageDestinationFinalize(destination), data.length <= Self.maximumJPEGBytes else { return }
            let frame = CapturedFrame(id: UUID(), timestamp: receivedAt,
                                      monotonicSeconds: receiptSeconds, jpegData: data as Data,
                                      width: width, height: height)
            // The controller's channel buffers only the newest frame if the UI is busy.
            continuation.yield(frame)
        }
    }
}
