import AVFoundation
import CalibrationKit
import SwiftUI
import UIKit

/// The camera as a Meter for the engine: each reading is the RAW camera signal turned into XYZ with
/// this iPhone model's profile.
final class CameraXYZMeter: Meter {
    let camera: CameraMeter
    let profile: CameraProfile
    /// Refuse to measure if the camera's locked settings differ from the profile's. Off for the standard
    /// profile, which was trained on a different iPhone model, so its settings never match exactly.
    let checkSettings: Bool
    /// The kind of screen being measured (a key in the profile's `panels`); nil uses the general conversion.
    let panel: String?

    init(camera: CameraMeter, profile: CameraProfile, checkSettings: Bool = true, panel: String? = nil) {
        self.camera = camera
        self.profile = profile
        self.checkSettings = checkSettings
        self.panel = panel
    }

    /// The light in a reading is captured a little before the reading comes back (RAW processing).
    var integration: Double { 0.25 }
    func now() -> Double { MeterClock.now() }
    func sleep(_ seconds: Double) async throws { try await MeterClock.sleep(seconds) }

    func read() async throws -> MeterReading {
        let t0 = now()
        let (reading, problem) = await camera.measure()
        let t1 = now()
        guard let reading else {
            if let problem, problem.hasPrefix("Too bright") { throw MeasureError.message(problem) }
            // No photo (the camera was interrupted, or isn't ready): report a missed reading, after a short
            // pause so callers that keep trying don't spin.
            try await MeterClock.sleep(0.3)
            return MeterReading(start: t0, end: now(), xyz: nil)
        }
        let diffs = checkSettings ? profile.camera.mismatches(reading.settings) : []
        if !diffs.isEmpty {
            throw MeasureError.message("The camera is running with different settings than its profile was trained with ("
                                       + diffs.joined(separator: "; ") + "). This can follow an iOS update: "
                                       + "check for a newer profile in Settings.")
        }
        let rgb = Vec3(reading.rgb[0], reading.rgb[1], reading.rgb[2])
        return MeterReading(start: t0, end: t1, xyz: profile.xyz(cameraRGB: rgb, panel: panel))
    }
}

/// The live camera picture, with the 10% measuring box in the middle.
struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.previewLayer.session = session
        // Aspect-fit, never cropped: the 3:4 frame matches the sensor, so the yellow box marks exactly
        // the middle 10% of the picture that is measured.
        view.previewLayer.videoGravity = .resizeAspect
        view.backgroundColor = UIColor(Theme.previewFill)
        return view
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {}
}

/// The camera preview (design: "Camera preview 3 by 4"): 3:4, 14 pt corners, #30383A when the camera is
/// off, and a yellow 1.5 pt box exactly 10% of the width and height in the centre: what is measured.
struct CameraViewfinder: View {
    @ObservedObject var camera: CameraMeter

    var body: some View {
        ZStack {
            Theme.previewFill
            if camera.running {
                CameraPreview(session: camera.engine.session)
            }
            GeometryReader { geo in
                Rectangle()
                    .strokeBorder(Theme.accent, lineWidth: 1.5)
                    .frame(width: geo.size.width * 0.1, height: geo.size.height * 0.1)
                    .position(x: geo.size.width / 2, y: geo.size.height / 2)
            }
        }
        .aspectRatio(3.0 / 4.0, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 14))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(camera.running ? "Camera view with the measuring box in the middle"
                                           : "Camera off. The measuring box is in the middle")
    }
}
