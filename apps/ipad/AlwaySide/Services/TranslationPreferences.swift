import Foundation
import Security

/// Non-secret settings share the app's Keychain group; API keys remain separate items.
@MainActor
enum TranslationPreferences {
    private static let service = "AlwaySide.translation.preferences"
    private static let account = "openAISettings.v1"
    private static let maximumBytes = 256 * 1024

    private static func query() throws -> [String: Any] {
        guard let group = Bundle.main.object(forInfoDictionaryKey: "AlwaySideSharedKeychainAccessGroup") as? String,
              !group.isEmpty, !group.contains("$(") else { throw ClientError.keychain }
        return [kSecClass as String: kSecClassGenericPassword,
                kSecAttrService as String: service,
                kSecAttrAccount as String: account,
                kSecAttrAccessGroup as String: group,
                kSecAttrSynchronizable as String: false]
    }

    static func save(settings: OpenAISettings) throws {
        let data = try JSONEncoder().encode(settings)
        guard data.count <= maximumBytes else { throw ClientError.invalidSettings }
        let lookup = try query()
        let status = SecItemUpdate(lookup as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var item = lookup
            item[kSecValueData as String] = data
            item[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            guard SecItemAdd(item as CFDictionary, nil) == errSecSuccess else { throw ClientError.keychain }
        } else if status != errSecSuccess {
            throw ClientError.keychain
        }
    }

    /// Missing settings require opening the main app; do not silently choose different settings.
    static func load() throws -> OpenAISettings {
        var lookup = try query()
        lookup[kSecReturnData as String] = true
        lookup[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(lookup as CFDictionary, &result)
        if status == errSecItemNotFound { throw ClientError.invalidSettings }
        guard status == errSecSuccess, let data = result as? Data else { throw ClientError.keychain }
        guard data.count <= maximumBytes,
              let settings = try? JSONDecoder().decode(OpenAISettings.self, from: data),
              settings.validationMessage == nil else { throw ClientError.invalidSettings }
        return settings
    }
}
