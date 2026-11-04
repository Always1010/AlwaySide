// swift-tools-version: 5.5

import PackageDescription
import AppleProductTypes

let package = Package(
    name: "AlwaySideDiagnostics",
    platforms: [
        .iOS("15.2")
    ],
    products: [
        .iOSApplication(
            name: "AlwaySideDiagnostics",
            targets: ["App"],
            displayVersion: "1.0",
            bundleVersion: "1",
            iconAssetName: "AppIcon",
            supportedDeviceFamilies: [.pad],
            supportedInterfaceOrientations: [
                .portrait,
                .landscapeRight,
                .landscapeLeft,
                .portraitUpsideDown(.when(deviceFamilies: [.pad]))
            ],
            capabilities: []
        )
    ],
    targets: [
        .executableTarget(
            name: "App",
            path: "App"
        )
    ]
)
