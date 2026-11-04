import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

/// Runs the production ImageIO import path on macOS without UIKit, networking or device access.
@main
private struct ImageImportContractChecks {
    private static var checks = 0
    private enum Failure { case invalidHandoff, expired, invalidImage, tooLarge }

    private static func expect(_ value: @autoclosure () -> Bool, _ label: String) {
        guard value() else { fatalError("FAIL: \(label)") }
        checks += 1
    }

    private static func expectFailure(_ expected: Failure, _ label: String, _ operation: () throws -> Void) {
        do { try operation() }
        catch {
            switch (expected, error) {
            case (.invalidHandoff, ScreenshotHandoffError.invalidImage),
                 (.expired, ScreenshotHandoffError.expired),
                 (.invalidImage, ClientError.invalidImage),
                 (.tooLarge, ClientError.tooLarge):
                checks += 1
                return
            default: fatalError("FAIL: \(label), unexpected error: \(error)")
            }
        }
        fatalError("FAIL: \(label), input was accepted")
    }

    private static func encoded(width: Int, height: Int, type: UTType = .jpeg) -> Data {
        guard let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8,
                                      bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else {
            fatalError("Could not allocate image fixture")
        }
        context.setFillColor(red: 0.2, green: 0.5, blue: 0.8, alpha: 1)
        context.fill(CGRect(x: 0, y: 0, width: CGFloat(width), height: CGFloat(height)))
        guard let image = context.makeImage() else { fatalError("Could not create image fixture") }
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data as CFMutableData,
            type.identifier as CFString, 1, nil) else {
            fatalError("Could not encode image fixture")
        }
        CGImageDestinationAddImage(destination, image, nil)
        guard CGImageDestinationFinalize(destination) else { fatalError("Could not finish image fixture") }
        return data as Data
    }

    static func main() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("alwayside-image-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let receivedAt = Date().addingTimeInterval(-120)
        let jpeg = encoded(width: 640, height: 480)
        let restored = try PhotoImport.restoreScreenshot(jpegData: jpeg, receivedAt: receivedAt)
        expect(restored.frame.jpegData == jpeg, "handoff retains exact JPEG bytes without recompression")
        expect(restored.frame.timestamp == receivedAt, "delayed recovery retains original receipt time")
        expect(restored.frame.width == 640 && restored.frame.height == 480, "dimensions come from decoded JPEG")
        expect(restored.origin == "快捷指令截图（时间为接收时间）" && restored.isImported, "receipt origin remains explicit")
        let maximumSide = try PhotoImport.restoreScreenshot(jpegData: encoded(width: 1280, height: 2), receivedAt: receivedAt)
        expect(maximumSide.frame.width == 1280, "1280 pixel edge is accepted")

        expectFailure(.expired, "expired screenshot is rejected") {
            _ = try PhotoImport.restoreScreenshot(jpegData: jpeg, receivedAt: Date().addingTimeInterval(-601))
        }
        expectFailure(.expired, "future receipt beyond tolerance is rejected") {
            _ = try PhotoImport.restoreScreenshot(jpegData: jpeg, receivedAt: Date().addingTimeInterval(61))
        }
        _ = try PhotoImport.restoreScreenshot(jpegData: jpeg, receivedAt: Date().addingTimeInterval(59))
        checks += 1
        expectFailure(.invalidHandoff, "empty handoff is rejected") {
            _ = try PhotoImport.restoreScreenshot(jpegData: Data(), receivedAt: receivedAt)
        }
        expectFailure(.invalidHandoff, "PNG in JPEG handoff is rejected") {
            _ = try PhotoImport.restoreScreenshot(jpegData: encoded(width: 2, height: 2, type: .png), receivedAt: receivedAt)
        }
        expectFailure(.invalidHandoff, "oversized pixel dimensions are rejected") {
            _ = try PhotoImport.restoreScreenshot(jpegData: encoded(width: 1281, height: 2), receivedAt: receivedAt)
        }
        expectFailure(.invalidHandoff, "truncated JPEG is rejected") {
            _ = try PhotoImport.restoreScreenshot(jpegData: Data(jpeg.prefix(jpeg.count / 2)), receivedAt: receivedAt)
        }
        expectFailure(.invalidHandoff, "JPEG without its end marker is rejected despite decoder recovery") {
            _ = try PhotoImport.restoreScreenshot(jpegData: Data(jpeg.dropLast(2)), receivedAt: receivedAt)
        }
        expectFailure(.invalidImage, "manual import also rejects a truncated JPEG") {
            _ = try PhotoImport.snapshot(from: Data(jpeg.prefix(jpeg.count / 2)))
        }
        // Trailing JPEG padding retains a valid image while exercising byte limits independently of pixels.
        var padded = jpeg
        padded.append(Data(count: 3 * 1024 * 1024 - padded.count))
        let maximumJPEG = try PhotoImport.restoreScreenshot(jpegData: padded, receivedAt: receivedAt)
        expect(maximumJPEG.frame.jpegData == padded, "exact 3 MiB JPEG is accepted unchanged")
        padded.append(0)
        expectFailure(.invalidHandoff, "3 MiB plus one byte is rejected") {
            _ = try PhotoImport.restoreScreenshot(jpegData: padded, receivedAt: receivedAt)
        }

        let file = root.appendingPathComponent("photo.png")
        try encoded(width: 2560, height: 640, type: .png).write(to: file)
        let imported = try PhotoImport.snapshot(from: file)
        expect(imported.frame.width == 1280 && imported.frame.height == 320, "file import downsamples before retaining pixels")
        let importedJPEG = try PhotoImport.restoreScreenshot(jpegData: imported.frame.jpegData, receivedAt: receivedAt)
        expect(importedJPEG.frame.jpegData == imported.frame.jpegData, "new ImageIO encoder produces restorable JPEG")
        expectFailure(.invalidImage, "directory import is rejected") { _ = try PhotoImport.snapshot(from: root) }
        expectFailure(.invalidImage, "non-file URL is rejected") {
            _ = try PhotoImport.snapshot(from: URL(string: "https://example.com/photo.jpg")!)
        }
        try Data().write(to: file)
        expectFailure(.invalidImage, "empty file is rejected") { _ = try PhotoImport.snapshot(from: file) }
        try Data("not an image".utf8).write(to: file)
        expectFailure(.invalidImage, "non-image file is rejected") { _ = try PhotoImport.snapshot(from: file) }
        padded = jpeg
        padded.append(Data(count: 20 * 1024 * 1024 - padded.count))
        try padded.write(to: file)
        let maximumInput = try PhotoImport.snapshot(from: file)
        expect(maximumInput.frame.width == 640, "exact 20 MiB original is accepted")
        padded.append(0)
        try padded.write(to: file)
        expectFailure(.tooLarge, "20 MiB plus one byte is rejected") { _ = try PhotoImport.snapshot(from: file) }
        print("Image import contracts passed: \(checks)")
    }
}
