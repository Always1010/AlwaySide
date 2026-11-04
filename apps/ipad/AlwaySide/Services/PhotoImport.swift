import Foundation
import ImageIO
import UniformTypeIdentifiers

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
            try Task.checkCancellation()
            data.append(chunk)
            guard data.count <= maximumBytes else { throw ClientError.tooLarge }
        }
        return try snapshot(from: data)
    }

    /// Downsample before decoding a full-resolution photo; retain only a bounded JPEG in memory.
    static func snapshot(from data: Data) throws -> EvidenceSnapshot {
        guard data.count <= 20 * 1024 * 1024 else { throw ClientError.tooLarge }
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              CGImageSourceGetType(source) as String? != UTType.jpeg.identifier || hasCompleteJPEGStream(data),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int,
              width > 0, height > 0, Double(width) * Double(height) <= 100_000_000 else {
            throw ClientError.invalidImage
        }
        let options: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true,
                                      kCGImageSourceCreateThumbnailWithTransform: true,
                                      kCGImageSourceThumbnailMaxPixelSize: 1280]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
            throw ClientError.invalidImage
        }
        let encoded = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(encoded as CFMutableData,
            UTType.jpeg.identifier as CFString, 1, nil) else {
            throw ClientError.invalidImage
        }
        CGImageDestinationAddImage(destination, image,
                                  [kCGImageDestinationLossyCompressionQuality: 0.75] as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { throw ClientError.invalidImage }
        let jpeg = encoded as Data
        guard jpeg.count <= 3 * 1024 * 1024 else { throw ClientError.invalidImage }
        let frame = CapturedFrame(id: UUID(), timestamp: Date(),
                                  monotonicSeconds: ProcessInfo.processInfo.systemUptime,
                                  jpegData: jpeg,
                                  width: image.width, height: image.height)
        return EvidenceSnapshot(frame: frame, origin: "手动导入（时间为导入时间）", isImported: true)
    }

    /// ImageIO may recover a truncated JPEG and still report complete. Require an
    /// actual EOI marker outside metadata segments, while permitting trailing padding.
    private static func hasCompleteJPEGStream(_ data: Data) -> Bool {
        data.withUnsafeBytes { raw in
            let bytes = raw.bindMemory(to: UInt8.self)
            guard bytes.count >= 4, bytes[0] == 0xff, bytes[1] == 0xd8 else { return false }
            var offset = 2
            var inScan = false
            var sawScan = false
            while offset < bytes.count {
                if inScan {
                    while offset < bytes.count, bytes[offset] != 0xff { offset += 1 }
                }
                guard offset < bytes.count, bytes[offset] == 0xff else { return false }
                while offset < bytes.count, bytes[offset] == 0xff { offset += 1 }
                guard offset < bytes.count else { return false }
                let marker = bytes[offset]
                offset += 1
                if inScan && (marker == 0x00 || (0xd0...0xd7).contains(marker)) { continue }
                if marker == 0xd9 { return sawScan }
                guard marker != 0x00, marker != 0xd8, !(0xd0...0xd7).contains(marker) else { return false }
                if marker == 0x01 { continue }
                inScan = false
                guard offset + 2 <= bytes.count else { return false }
                let length = Int(bytes[offset]) * 256 + Int(bytes[offset + 1])
                guard length >= 2, length <= bytes.count - offset else { return false }
                offset += length
                if marker == 0xda { inScan = true; sawScan = true }
            }
            return false
        }
    }
}
