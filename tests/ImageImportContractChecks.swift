import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

/// Runs the production ImageIO import path on macOS without UIKit, networking or device access.
@main
private struct ImageImportContractChecks {
    private static var checks = 0
    private enum Failure { case invalidImage, tooLarge }

    private static func expect(_ value: @autoclosure () -> Bool, _ label: String) {
        guard value() else { fatalError("FAIL: \(label)") }
        checks += 1
    }

    private static func expectFailure(_ expected: Failure, _ label: String, _ operation: () throws -> Void) {
        do { try operation() }
        catch {
            switch (expected, error) {
            case (.invalidImage, ClientError.invalidImage),
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
        let jpeg = encoded(width: 640, height: 480)
        let decoded = try PhotoImport.snapshot(from: jpeg)
        expect(decoded.frame.width == 640 && decoded.frame.height == 480, "JPEG dimensions are decoded")
        expectFailure(.invalidImage, "truncated JPEG is rejected") {
            _ = try PhotoImport.snapshot(from: Data(jpeg.prefix(jpeg.count / 2)))
        }
        expectFailure(.invalidImage, "JPEG without its end marker is rejected despite decoder recovery") {
            _ = try PhotoImport.snapshot(from: Data(jpeg.dropLast(2)))
        }
        let png = try PhotoImport.snapshot(from: encoded(width: 2, height: 2, type: .png))
        expect(png.frame.width == 2 && png.frame.height == 2, "PNG imports remain supported")
        var padded = jpeg

        let file = root.appendingPathComponent("photo.png")
        try encoded(width: 2560, height: 640, type: .png).write(to: file)
        let imported = try PhotoImport.snapshot(from: file)
        expect(imported.frame.width == 1280 && imported.frame.height == 320, "file import downsamples before retaining pixels")
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
