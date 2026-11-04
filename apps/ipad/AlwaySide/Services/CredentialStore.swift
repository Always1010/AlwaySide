import Foundation
import Security

/// Credentials stay in this device's Keychain. Never add a key to settings defaults or diagnostics.
@MainActor
final class CredentialStore {
    private let service = "AlwaySide.provider.credentials"

    private func query(_ provider: ModelProvider) -> [String: Any] {
        var item: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: provider.rawValue,
         kSecAttrSynchronizable as String: false]
        if let group = Bundle.main.object(forInfoDictionaryKey: "AlwaySideSharedKeychainAccessGroup") as? String,
           !group.isEmpty, !group.contains("$(") {
            item[kSecAttrAccessGroup as String] = group
        }
        return item
    }

    func read(_ provider: ModelProvider) throws -> String? {
        var lookup = query(provider)
        lookup[kSecReturnData as String] = true
        lookup[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(lookup as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data,
              let key = String(data: data, encoding: .utf8) else { throw ClientError.keychain }
        return key
    }

    func save(_ key: String, for provider: ModelProvider) throws {
        let clean = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty, clean.count <= 1024,
              !clean.contains(where: { $0.isWhitespace || $0.isNewline }) else {
            throw ClientError.missingKey
        }
        let data = Data(clean.utf8)
        let status = SecItemUpdate(query(provider) as CFDictionary,
                                   [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var item = query(provider)
            item[kSecValueData as String] = data
            item[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            guard SecItemAdd(item as CFDictionary, nil) == errSecSuccess else {
                throw ClientError.keychain
            }
        } else if status != errSecSuccess {
            throw ClientError.keychain
        }
    }

    func delete(_ provider: ModelProvider) throws {
        let status = SecItemDelete(query(provider) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw ClientError.keychain
        }
    }
}
