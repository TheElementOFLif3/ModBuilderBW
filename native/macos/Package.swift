// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "ModBuilderBWMac",
    platforms: [
        .macOS(.v14)
    ],
    products: [
        .executable(name: "ModBuilderBWMac", targets: ["ModBuilderBWMac"])
    ],
    targets: [
        .executableTarget(
            name: "ModBuilderBWMac",
            path: "Sources/ModBuilderBWMac"
        )
    ]
)
