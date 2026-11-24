// swift-tools-version:5.9
// CalibrationKit: the TV calibration engine (colour maths, pattern timing, measuring, analysis,
// automatic rounds, guided live hints, point finder). A Swift port of tvcal.py / tvcal_app.py from
// the Mac app; the tests check it against fixtures produced by the Python engine.
//
// Run the tests on the Mac:   cd CalibrationKit && swift test

import PackageDescription

let package = Package(
    name: "CalibrationKit",
    platforms: [.iOS(.v18), .macOS(.v14)],
    products: [
        .library(name: "CalibrationKit", targets: ["CalibrationKit"]),
    ],
    targets: [
        .target(name: "CalibrationKit"),
        .testTarget(
            name: "CalibrationKitTests",
            dependencies: ["CalibrationKit"],
            resources: [.copy("Fixtures")]
        ),
    ],
    swiftLanguageVersions: [.v5]
)
