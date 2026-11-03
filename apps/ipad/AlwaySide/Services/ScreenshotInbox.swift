import AppIntents
import Foundation
import UniformTypeIdentifiers

/// One bounded, atomic handoff in the main app's sandbox. No App Group entitlement is needed.
actor ScreenshotInbox {
    static let shared = ScreenshotInbox()
    static let didChange = Notification.Name("AlwaySideScreenshotInboxDidChange")
    static let willReceive = Notification.Name("AlwaySideScreenshotInboxWillReceive")
    private static let maximumInputBytes = 20 * 1024 * 1024
    private static let maximumEnvelopeBytes = 5 * 1024 * 1024
    private static let maximumAge: TimeInterval = 10 * 60
    private var latestReceiptID = UUID()
    private var isReceiving = false

    struct Pending: Codable, Sendable {
        let id: UUID
        let receivedAt: Date
        let jpegData: Data?
        let failure: String?
    }

    private var directory: URL {
        get throws {
            let support = try FileManager.default.url(for: .applicationSupportDirectory,
                                                     in: .userDomainMask,
                                                     appropriateFor: nil, create: true)
            let url = support.appendingPathComponent("ScreenshotInbox", isDirectory: true)
            try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            var excludedURL = url
            try excludedURL.setResourceValues(values)
            return url
        }
    }

    private var pendingURL: URL { get throws { try directory.appendingPathComponent("pending.json") } }

    func receive(_ image: IntentFile) async throws {
        let receiptID = UUID()
        let receivedAt = Date()
        latestReceiptID = receiptID
        isReceiving = true
        defer { if latestReceiptID == receiptID { isReceiving = false } }
        await MainActor.run { NotificationCenter.default.post(name: Self.willReceive, object: nil) }
        do {
            guard latestReceiptID == receiptID else { return }
            // Invalidate the old durable image before conversion. If the process is interrupted,
            // the next launch sees an incomplete handoff instead of an older screenshot.
            try write(Pending(id: receiptID, receivedAt: receivedAt, jpegData: nil,
                              failure: "本次截图接收未完成，请重新运行「截屏 → 接收截图」。"))
            // Ask for a concrete representation; do not interpret an arbitrary URL or pasteboard.
            let representation = try await image.file(contentType: .png)
            defer {
                if !representation.openedInPlace {
                    try? FileManager.default.removeItem(at: representation.fileURL)
                }
            }
            guard latestReceiptID == receiptID else { return }
            let scoped = representation.fileURL.startAccessingSecurityScopedResource()
            defer { if scoped { representation.fileURL.stopAccessingSecurityScopedResource() } }
            let values = try representation.fileURL.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
            guard values.isRegularFile == true, let size = values.fileSize,
                  size > 0, size <= Self.maximumInputBytes else { throw ScreenshotHandoffError.invalidImage }
            let data = try Data(contentsOf: representation.fileURL, options: .mappedIfSafe)
            guard !data.isEmpty, data.count <= Self.maximumInputBytes else { throw ScreenshotHandoffError.invalidImage }
            let snapshot = try PhotoImport.snapshot(from: data)
            try write(Pending(id: receiptID, receivedAt: receivedAt, jpegData: snapshot.frame.jpegData, failure: nil))
            isReceiving = false
        } catch {
            guard latestReceiptID == receiptID else { return }
            // An invalid newer input replaces any pending older screenshot with an explicit failure.
            try write(Pending(id: receiptID, receivedAt: receivedAt, jpegData: nil,
                              failure: "截图传入失败，请重新运行「截屏 → 接收截图」。\(error.localizedDescription)"))
            isReceiving = false
            await announceChange()
            throw error
        }
        await announceChange()
    }

    private func write(_ pending: Pending) throws {
        let data = try JSONEncoder().encode(pending)
        guard data.count <= Self.maximumEnvelopeBytes else { throw ScreenshotHandoffError.invalidImage }
        try data.write(to: pendingURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    func pending() throws -> Pending? {
        guard !isReceiving else { return nil }
        let url = try pendingURL
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard size > 0, size <= Self.maximumEnvelopeBytes else {
            try? FileManager.default.removeItem(at: url)
            throw ScreenshotHandoffError.invalidImage
        }
        let data = try Data(contentsOf: url)
        guard data.count <= Self.maximumEnvelopeBytes else { throw ScreenshotHandoffError.invalidImage }
        do { return try JSONDecoder().decode(Pending.self, from: data) }
        catch {
            try? FileManager.default.removeItem(at: url)
            throw ScreenshotHandoffError.invalidImage
        }
    }

    func materialize(_ pending: Pending) throws -> URL {
        let age = Date().timeIntervalSince(pending.receivedAt)
        guard age >= -60, age <= Self.maximumAge else { throw ScreenshotHandoffError.expired }
        guard let jpeg = pending.jpegData, !jpeg.isEmpty, jpeg.count <= 3 * 1024 * 1024 else {
            throw ScreenshotHandoffError.invalidImage
        }
        // Remove remnants of an interrupted prior import before creating the single UI handoff.
        removeMaterializedFiles()
        let url = try directory.appendingPathComponent("\(pending.id.uuidString).jpg")
        try jpeg.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        return url
    }

    func acknowledge(_ id: UUID, materializedURL: URL?) throws {
        if let materializedURL { try? FileManager.default.removeItem(at: materializedURL) }
        removeMaterializedFiles()
        // A newer shortcut may have completed while the UI was importing the previous image.
        if let latest = try pending(), latest.id == id {
            try FileManager.default.removeItem(at: pendingURL)
        }
    }

    private func removeMaterializedFiles() {
        guard let folder = try? directory,
              let files = try? FileManager.default.contentsOfDirectory(at: folder,
                                                                      includingPropertiesForKeys: nil) else { return }
        for file in files where file.pathExtension == "jpg" && UUID(uuidString: file.deletingPathExtension().lastPathComponent) != nil {
            try? FileManager.default.removeItem(at: file)
        }
    }

    private func announceChange() async {
        await MainActor.run { NotificationCenter.default.post(name: Self.didChange, object: nil) }
    }
}

enum ScreenshotHandoffError: LocalizedError {
    case invalidImage, expired
    var errorDescription: String? {
        switch self {
        case .invalidImage: "没有收到有效截图（原图上限 20 MiB）。请将系统「截屏」的输出连接到接收动作。"
        case .expired: "待接收截图已超过 10 分钟，请回到目标页面重新截屏。旧图不会代替本次截图。"
        }
    }
}

/// Serializes delivery to the model; the disk handoff is acknowledged only after import finishes.
@MainActor
enum ScreenshotInboxDelivery {
    private static var consuming = false
    private static var generation = UUID()

    static func begin(using model: CompanionModel) {
        generation = UUID()
        model.beginScreenshotImport()
    }

    static func consume(using model: CompanionModel) async {
        guard !consuming else { return }
        consuming = true
        defer { consuming = false }
        var currentGeneration = generation
        do {
            while true {
                currentGeneration = generation
                guard let pending = try await ScreenshotInbox.shared.pending() else { break }
                var materializedURL: URL?
                if let failure = pending.failure {
                    if currentGeneration == generation { model.receiveScreenshotFailure(message: failure) }
                } else {
                    do {
                        let url = try await ScreenshotInbox.shared.materialize(pending)
                        materializedURL = url
                        if currentGeneration == generation { await model.receiveScreenshot(url: url) }
                    } catch {
                        if currentGeneration == generation { model.receiveScreenshotFailure(message: error.localizedDescription) }
                    }
                }
                try await ScreenshotInbox.shared.acknowledge(pending.id, materializedURL: materializedURL)
            }
        } catch {
            if currentGeneration == generation {
                model.receiveScreenshotFailure(message: "读取本次截图失败：\(error.localizedDescription)")
            }
        }
    }
}
