// swift-tools-version:5.9
// KeystoneCore: the calibration maths and picture detection, shared with the web page's keystone.js.
// Builds on Linux too, so it can be unit-tested without a Mac: `swift test` in this directory.
import PackageDescription

let package = Package(
    name: "KeystoneCore",
    products: [.library(name: "KeystoneCore", targets: ["KeystoneCore"])],
    targets: [
        .target(name: "KeystoneCore"),
        .testTarget(name: "KeystoneCoreTests", dependencies: ["KeystoneCore"], resources: [.copy("Fixtures")]),
    ]
)
