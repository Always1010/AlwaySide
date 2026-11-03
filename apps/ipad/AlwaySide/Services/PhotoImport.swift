import Foundation
import ImageIO
import UIKit

enum PhotoImport {
    /// Downsample before decoding a full-resolution photo; retain only a bounded JPEG in memory.
    static func snapshot(from data: Data) throws -> EvidenceSnapshot {
        guard data.count <= 20 * 1024 * 1024 else { throw ClientError.tooLarge }
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int,
              width > 0, height > 0, Double(width) * Double(height) <= 100_000_000 else {
            throw ClientError.invalidImage
        }
        let options: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true,
                                      kCGImageSourceCreateThumbnailWithTransform: true,
                                      kCGImageSourceThumbnailMaxPixelSize: 1280]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary),
              let jpeg = UIImage(cgImage: image).jpegData(compressionQuality: 0.75),
              jpeg.count <= 3 * 1024 * 1024 else { throw ClientError.invalidImage }
        let frame = CapturedFrame(id: UUID(), timestamp: Date(),
                                  monotonicSeconds: ProcessInfo.processInfo.systemUptime,
                                  jpegData: jpeg,
                                  width: image.width, height: image.height)
        return EvidenceSnapshot(frame: frame, origin: "手动导入（时间为导入时间）", isImported: true)
    }
}
