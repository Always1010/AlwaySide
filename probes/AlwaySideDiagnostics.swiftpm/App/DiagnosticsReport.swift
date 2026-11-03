import Foundation
import UIKit

struct DiagnosticField: Identifiable {
    let label: String
    let value: String

    var id: String { label }
}

struct DiagnosticsReport {
    let systemFields: [DiagnosticField]
    let bundleFields: [DiagnosticField]
    let moduleFields: [DiagnosticField]

    static let explanation = "canImport 仅表示当前编译环境能否找到模块，不能证明 API 在 iPad 可用，也不能证明支持后台采集、广播扩展或全功能采集。Bundle SDK 字段是构建元数据，不等于设备系统版本；字段缺失显示 unknown。"

    static var current: DiagnosticsReport {
        let device = UIDevice.current
        let bundle = Bundle.main

        #if canImport(ScreenCaptureKit)
        let screenCaptureKit = "true"
        #else
        let screenCaptureKit = "false"
        #endif

        #if canImport(ReplayKit)
        let replayKit = "true"
        #else
        let replayKit = "false"
        #endif

        let bundleKeys = [
            "CFBundleShortVersionString",
            "CFBundleVersion",
            "DTPlatformName",
            "DTPlatformVersion",
            "DTSDKName",
            "DTSDKBuild",
            "DTXcode",
            "DTXcodeBuild",
            "MinimumOSVersion"
        ]

        return DiagnosticsReport(
            systemFields: [
                DiagnosticField(label: "系统名称", value: valueOrUnknown(device.systemName)),
                DiagnosticField(label: "系统版本", value: valueOrUnknown(device.systemVersion)),
                DiagnosticField(label: "系统版本描述", value: valueOrUnknown(ProcessInfo.processInfo.operatingSystemVersionString)),
                DiagnosticField(label: "设备类别", value: valueOrUnknown(device.model))
            ],
            bundleFields: bundleKeys.map { key in
                DiagnosticField(label: key, value: bundleValue(key, in: bundle))
            },
            moduleFields: [
                DiagnosticField(label: "canImport(ScreenCaptureKit)", value: screenCaptureKit),
                DiagnosticField(label: "canImport(ReplayKit)", value: replayKit)
            ]
        )
    }

    var text: String {
        let sections: [(String, [DiagnosticField])] = [
            ("设备系统", systemFields),
            ("Bundle / SDK 构建信息", bundleFields),
            ("编译期模块探测", moduleFields)
        ]
        var lines = ["AlwaySide iPad 运行诊断 v1", "此文本由诊断 App 运行后生成。"]
        for (title, fields) in sections {
            lines.append("")
            lines.append("[\(title)]")
            lines.append(contentsOf: fields.map { "\($0.label): \($0.value)" })
        }
        lines.append("")
        lines.append(Self.explanation)
        lines.append("本探针不执行屏幕或音频采集，不读取设备唯一标识，不请求采集或后台权限，不访问网络。")
        return lines.joined(separator: "\n")
    }

    private static func bundleValue(_ key: String, in bundle: Bundle) -> String {
        guard let value = bundle.object(forInfoDictionaryKey: key) else {
            return "unknown"
        }
        if let string = value as? String {
            return valueOrUnknown(string)
        }
        if let number = value as? NSNumber {
            return number.stringValue
        }
        return "unknown"
    }

    private static func valueOrUnknown(_ value: String) -> String {
        value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "unknown" : value
    }
}
