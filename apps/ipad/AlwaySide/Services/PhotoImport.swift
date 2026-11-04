import Foundation
import ImageIO
import UIKit

enum PhotoImport {
    /// Validate a transferred file before reading it, and cap the read even if it grows meanwhile.
    static func snapshot(from url: URL) throws -> EvidenceSnapshot {
        guard url.isFileURL else { throw ClientError.invalidImage }
        let values = try url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
        guard values.isRegularFile == true, let size = values.fileSize, size > 0 else {
            throw ClientError.invalidImage
        }
        let maximumBytes = 20 * 1024 * 1024
        guard size <= maximumBytes else { throw ClientError.tooLarge }
        let file = try FileHandle(forReadingFrom: url)
        defer { try? file.close() }
        var data = Data()
        while let chunk = try file.read(upToCount: min(64 * 1024, maximumBytes + 1 - data.count)),
              !chunk.isEmpty {
            data.append(chunk)
            guard data.count <= maximumBytes else { throw ClientError.tooLarge }
        }
        return try snapshot(from: data)
    }

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
