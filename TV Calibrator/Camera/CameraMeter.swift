import AVFoundation
import CalibrationKit
import Combine
import CoreVideo
import Foundation
import ImageIO
import UIKit

// The iPhone camera as a light meter.
// It takes RAW photos (straight from the sensor: no tone curve, no white balance, no processing),
// averages the red, green and blue photosites in a box in the centre of the frame, and turns them into
// real colour (XYZ) with this iPhone model's profile, trained against the Display Plus HL.
//
// Everything that affects the numbers is locked: lens (back main camera), focus (closest, so the TV's
// pixel grid blurs out), ISO (lowest), RAW format and, on iPhones with a variable aperture (iPhone 18
// Pro), the aperture. Only the shutter time changes, and readings are divided by it.
//
// The exact same code runs in Lab mode when a new iPhone model is trained on the Mac, so a profile
// always matches the camera pipeline that uses it.

// MARK: - Reading the RAW sensor data

struct RawStats {
    var mean: [Double]      // average R, G, B photosite value in the centre box (raw counts)
    var clipped: Bool       // photosites at the sensor's ceiling
    var uneven: Double      // brightness spread across the box's four quarters (0 = even)
    var maxRaw: Double      // the brightest photosite in the box
    var plateau: Bool       // many photosites pile up at that brightest value: the sensor's real ceiling
}

enum RawAnalysis {
    static let fullScale = 16383.0     // 14-bit sensor data; some iPhones' sensors top out lower (see `white`)

    /// `white`: the level at which this sensor's photosites saturate (from the photo's DNG data, or learned).
    /// Clipping is also caught when many photosites sit at the same top value, whatever the sensor's ceiling:
    /// an iPhone 15 Pro read every bright patch as the same clipped value because its ceiling is below 14 bits.
    /// `floor`: the level a covered lens reads (0 if not known yet). With the lens covered, or on a black
    /// patch, every photosite sits at that same low level, which must not be taken for a ceiling.
    static func measure(_ buffer: CVPixelBuffer, white: Double = fullScale, floor: Double = 0) -> RawStats? {
        // Colour of each photosite in a 2x2 cell, indexed (y % 2) * 2 + (x % 2): 0 red, 1 green, 2 blue.
        let cell: [Int]
        switch CVPixelBufferGetPixelFormatType(buffer) {
        case kCVPixelFormatType_14Bayer_RGGB: cell = [0, 1, 1, 2]
        case kCVPixelFormatType_14Bayer_GRBG: cell = [1, 0, 2, 1]
        case kCVPixelFormatType_14Bayer_GBRG: cell = [1, 2, 0, 1]
        case kCVPixelFormatType_14Bayer_BGGR: cell = [2, 1, 1, 0]
        default: return nil
        }
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(buffer) else { return nil }
        let width = CVPixelBufferGetWidth(buffer)
        let height = CVPixelBufferGetHeight(buffer)
        let rowBytes = CVPixelBufferGetBytesPerRow(buffer)

        // Centre box: 10% of the width and height, aligned to whole 2x2 cells.
        let boxW = (width / 10) & ~1
        let boxH = (height / 10) & ~1
        let x0 = (width / 2 - boxW / 2) & ~1
        let y0 = (height / 2 - boxH / 2) & ~1

        var sum = [Double](repeating: 0, count: 3)
        var count = [Double](repeating: 0, count: 3)
        var quarter = [Double](repeating: 0, count: 4)
        var quarterN = [Double](repeating: 0, count: 4)
        var clipped = false
        let clipLevel = UInt16(min(max(white, 1000), fullScale) * 0.98)
        var maxRaw: UInt16 = 0
        var histogram = [Int](repeating: 0, count: 65536)

        for y in y0..<(y0 + boxH) {
            let row = base.advanced(by: y * rowBytes).assumingMemoryBound(to: UInt16.self)
            let qy = y < y0 + boxH / 2 ? 0 : 2
            let parity = (y & 1) * 2
            for x in x0..<(x0 + boxW) {
                let raw = row[x]
                if raw >= clipLevel { clipped = true }
                if raw > maxRaw { maxRaw = raw }
                histogram[Int(raw)] += 1
                let v = Double(raw)
                let c = cell[parity + (x & 1)]
                sum[c] += v
                count[c] += 1
                let q = qy + (x < x0 + boxW / 2 ? 0 : 1)
                quarter[q] += v
                quarterN[q] += 1
            }
        }
        let mean = (0..<3).map { count[$0] > 0 ? sum[$0] / count[$0] : 0 }
        let qMeans = (0..<4).map { quarterN[$0] > 0 ? quarter[$0] / quarterN[$0] : 0 }
        let qAvg = qMeans.reduce(0, +) / 4
        let uneven = qAvg > 0 ? ((qMeans.max() ?? 0) - (qMeans.min() ?? 0)) / qAvg : 0
        // A real patch's brightest photosites are a thin tail of the noise; at the sensor's ceiling they
        // pile up. More than 1% of the box within a hair of the top value means it's clipped.
        let top = Int(maxRaw)
        let band = max(2, top * 3 / 1000)
        let nearTop = histogram[max(0, top - band)...top].reduce(0, +)
        let total = boxW * boxH
        let plateau = Double(top) > max(2000, floor * 2) && total > 0 && Double(nearTop) / Double(total) > 0.01
        if plateau { clipped = true }
        return RawStats(mean: mean, clipped: clipped, uneven: uneven, maxRaw: Double(maxRaw), plateau: plateau)
    }
}

// MARK: - Camera control (runs on its own queue)

final class CameraEngine: NSObject, AVCapturePhotoCaptureDelegate, @unchecked Sendable {
    let session = AVCaptureSession()
    private let output = AVCapturePhotoOutput()
    private let queue = DispatchQueue(label: "tvcal.camera")
    private var device: AVCaptureDevice?
    private var rawType: OSType = 0
    /// The photo being waited for: its settings id and what to do with its numbers. Only touched on `queue`.
    private var pending: ((RawStats?) -> Void)?
    private var pendingID: Int64 = -1
    private(set) var iso: Float = 0
    private(set) var minDuration = 1.0 / 8000
    private(set) var maxDuration = 0.25
    /// The RAW format's largest size, e.g. "8064x6048 bgg4". It identifies the sensor mode a profile was
    /// trained with; the photos themselves are usually smaller (see capturedSize).
    private(set) var formatDescription = ""
    private(set) var lensDescription = ""
    /// The size of the RAW photos actually delivered, e.g. "4032x3024" (known after the first photo).
    var capturedSize: String {
        infoLock.lock()
        defer { infoLock.unlock() }
        return _capturedSize
    }
    private var _capturedSize = ""
    private let infoLock = NSLock()
    /// The raw level at which this camera's photosites saturate. Read once from a photo's DNG data, or
    /// learned from a clipped photo; 14-bit full scale until then.
    var whiteLevel: Double {
        infoLock.lock()
        defer { infoLock.unlock() }
        return _whiteLevel
    }
    private var _whiteLevel = RawAnalysis.fullScale
    private var whiteChecked = false        // only touched on the photo delegate's thread
    /// The raw level a covered lens reads (the sensor's own baseline), once the darkness has been measured;
    /// 0 until then.
    var floorLevel: Double {
        infoLock.lock()
        defer { infoLock.unlock() }
        return _floorLevel
    }
    private var _floorLevel = 0.0
    /// Whether the photo being waited for may teach the white level. Only touched on `queue`.
    private var pendingLearns = true
    /// The locked f-number on a variable-aperture lens; nil when the lens has a fixed aperture.
    private(set) var lockedAperture: Double?
    /// The aperture the lens reports (fixed lenses too).
    private(set) var reportedAperture: Double = 0
    /// The aperture to lock to (from the profile, so a reading matches its training). Set before start().
    var wantedAperture: Double?
    var logger: ((String) -> Void)?

    private func log(_ line: String) { logger?(line) }

    override init() {
        super.init()
        // If the camera stops with an error (another app took it, the media services restarted), start it
        // again, so measuring picks up where it left off.
        NotificationCenter.default.addObserver(forName: AVCaptureSession.runtimeErrorNotification, object: session,
                                               queue: nil) { [weak self] note in
            guard let self else { return }
            let why = (note.userInfo?[AVCaptureSessionErrorKey] as? NSError)?.localizedDescription ?? "unknown error"
            self.log("The camera stopped (\(why)). Starting it again.")
            self.queue.async {
                if self.device != nil, !self.session.isRunning { self.session.startRunning() }
            }
        }
    }

    /// One capture's "has it been answered yet" flag, shared by the photo, error and time-out paths.
    private final class Ticket {
        var answered = false
    }

    var settings: CameraSettings {
        CameraSettings(iso: Double(iso), format: formatDescription, lens: lensDescription,
                       aperture: lockedAperture)
    }

    /// Sets up the back main camera for RAW capture with focus, white balance, ISO and aperture locked.
    func start(_ done: @escaping (String?) -> Void) {
        queue.async {
            if self.device != nil {
                if !self.session.isRunning { self.session.startRunning() }
                done(nil)
                return
            }
            self.log("Setting up the back main camera")
            guard let dev = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) else {
                done("This iPhone has no back camera.")
                return
            }
            do {
                self.session.beginConfiguration()
                self.session.sessionPreset = .photo
                let input = try AVCaptureDeviceInput(device: dev)
                guard self.session.canAddInput(input), self.session.canAddOutput(self.output) else {
                    self.session.commitConfiguration()
                    done("Couldn't set up the camera.")
                    return
                }
                self.session.addInput(input)
                self.session.addOutput(self.output)
                self.session.commitConfiguration()

                guard let raw = self.output.availableRawPhotoPixelFormatTypes
                        .first(where: { AVCapturePhotoOutput.isBayerRAWPixelFormat($0) }) else {
                    done("This iPhone can't take RAW photos, which the camera meter needs.")
                    return
                }
                self.rawType = raw
                let fourCC = String((0..<4).map { Character(UnicodeScalar(UInt8((raw >> (24 - 8 * $0)) & 0xFF))) })
                let dims = dev.activeFormat.supportedMaxPhotoDimensions.last
                self.formatDescription = "\(dims?.width ?? 0)x\(dims?.height ?? 0) \(fourCC)"
                self.lensDescription = dev.localizedName

                try dev.lockForConfiguration()
                // Closest focus: at TV distance this softens the picture so the pixel grid averages out.
                if dev.isFocusModeSupported(.locked) && dev.isLockingFocusWithCustomLensPositionSupported {
                    dev.setFocusModeLocked(lensPosition: 0.0, completionHandler: nil)
                }
                if dev.isWhiteBalanceModeSupported(.locked) {
                    dev.whiteBalanceMode = .locked        // RAW ignores it, but keep the preview steady
                }
                self.iso = dev.activeFormat.minISO        // lowest noise; only the shutter speed changes
                self.minDuration = max(CMTimeGetSeconds(dev.activeFormat.minExposureDuration), 1.0 / 8000)
                self.maxDuration = min(CMTimeGetSeconds(dev.activeFormat.maxExposureDuration), 0.25)
                self.lockedAperture = ApertureLock.choose(device: dev, wanted: self.wantedAperture)
                self.reportedAperture = Double(dev.lensAperture)
                dev.unlockForConfiguration()
                self.device = dev
                self.log("RAW \(self.formatDescription), ISO \(Int(self.iso)), "
                         + (self.lockedAperture.map { String(format: "aperture locked at f/%.2f", $0) }
                            ?? String(format: "fixed aperture f/%.2f", self.reportedAperture)))
                self.session.startRunning()
                done(nil)
            } catch {
                self.log("Setup failed: \(error.localizedDescription)")
                done("Couldn't set up the camera: \(error.localizedDescription)")
            }
        }
    }

    func stop() {
        queue.async { self.session.stopRunning() }
    }

    /// Takes one RAW photo at the given shutter time (seconds). Reports the stats, the time used and
    /// when the exposure happened (seconds since boot). `done` is called exactly once, even if the camera
    /// never answers (an interrupted session): a missing photo comes back as nil stats.
    /// `learnsWhite`: false for photos of the darkness, which must never teach the sensor's ceiling.
    func capture(duration: Double, learnsWhite: Bool = true, _ done: @escaping (RawStats?, Double, Double) -> Void) {
        queue.async {
            guard let dev = self.device, self.session.isRunning else {
                done(nil, duration, ProcessInfo.processInfo.systemUptime)
                return
            }
            let d = min(max(duration, self.minDuration), self.maxDuration)
            var time = CMTime(seconds: d, preferredTimescale: 1_000_000)
            time = CMTimeMaximum(time, dev.activeFormat.minExposureDuration)
            time = CMTimeMinimum(time, dev.activeFormat.maxExposureDuration)

            let ticket = Ticket()
            let answer: (RawStats?, Double) -> Void = { stats, at in      // always called on `queue`
                if ticket.answered { return }
                ticket.answered = true
                done(stats, d, at)
            }
            self.queue.asyncAfter(deadline: .now() + d + 8) {
                guard !ticket.answered else { return }
                self.pending = nil
                self.pendingID = -1
                self.log("The camera didn't answer in time")
                answer(nil, ProcessInfo.processInfo.systemUptime)
            }
            do {
                try dev.lockForConfiguration()
                let shoot: () -> Void = {
                    self.queue.async {
                        guard !ticket.answered else { return }
                        let started = ProcessInfo.processInfo.systemUptime
                        let settings = AVCapturePhotoSettings(rawPixelFormatType: self.rawType)
                        // No shutter sound: the meter takes a photo every second or two. (Some regions
                        // require the sound, and there the system keeps it.)
                        if self.output.isShutterSoundSuppressionSupported {
                            settings.isShutterSoundSuppressionEnabled = true
                        }
                        self.pendingID = settings.uniqueID
                        self.pendingLearns = learnsWhite
                        self.pending = { stats in answer(stats, started + d / 2) }
                        self.output.capturePhoto(with: settings, delegate: self)
                    }
                }
                if let ap = self.lockedAperture {
                    ApertureLock.setExposure(device: dev, aperture: ap, duration: time, iso: self.iso) { _ in shoot() }
                } else {
                    dev.setExposureModeCustom(duration: time, iso: self.iso) { _ in shoot() }
                }
                dev.unlockForConfiguration()
            } catch {
                self.log("Couldn't lock the camera for exposure: \(error.localizedDescription)")
                answer(nil, ProcessInfo.processInfo.systemUptime)
            }
        }
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        // Read the numbers here, while the photo's buffer is valid; hand them over on our queue.
        var note: String?
        let floor = floorLevel
        if error == nil, !whiteChecked {
            whiteChecked = true                  // once per launch: packing the DNG is slow
            if let w = Self.dngWhiteLevel(photo), w > 1000, w <= RawAnalysis.fullScale, w > floor * 3 {
                setWhite(w)
                note = "Sensor white level \(Int(w)) (from the photo's DNG data)"
            }
        }
        let white = whiteLevel
        let stats = error == nil
            ? photo.pixelBuffer.flatMap { RawAnalysis.measure($0, white: white, floor: floor) } : nil
        let id = photo.resolvedSettings.uniqueID
        let size = photo.pixelBuffer.map { "\(CVPixelBufferGetWidth($0))x\(CVPixelBufferGetHeight($0))" }
        let format = photo.pixelBuffer.map { CVPixelBufferGetPixelFormatType($0) } ?? 0
        let failure = error?.localizedDescription
        queue.async {
            if let note { self.log(note) }
            if let size, size != self.capturedSize {
                self.infoLock.lock()
                self._capturedSize = size
                self.infoLock.unlock()
                self.log("RAW photos are \(size) (only the centre 10% is read)")
            }
            if let failure {
                self.log("Photo failed: \(failure)")
            } else if stats == nil {
                self.log("RAW photo arrived but couldn't be read (pixel format \(format))")
            }
            // A photo that arrives after its capture timed out must not answer the next capture.
            guard id == self.pendingID, let callback = self.pending else { return }
            // A clipped photo shows where the sensor really tops out, if that's lower than we thought.
            if self.pendingLearns, let st = stats, st.plateau, st.maxRaw < white * 0.98 {
                self.setWhite(st.maxRaw)
                self.log("Sensor white level \(Int(st.maxRaw)) (learned from a clipped photo)")
            }
            self.pending = nil
            self.pendingID = -1
            callback(stats)
        }
    }

    private func setWhite(_ w: Double) {
        infoLock.lock()
        _whiteLevel = w
        infoLock.unlock()
    }

    /// Called with the measured darkness. A sensor's ceiling is many times its dark level, so a white level
    /// within three times of it can't be right (read on another scale, or learned from a dark photo): go
    /// back to 14 bits and let a clipped photo show the real one.
    func setFloor(_ level: Double) {
        infoLock.lock()
        _floorLevel = level
        let wrong = level > 0 && _whiteLevel < level * 3
        let was = _whiteLevel
        if wrong { _whiteLevel = RawAnalysis.fullScale }
        infoLock.unlock()
        if wrong {
            log("Sensor white level \(Int(was)) is too close to the dark level \(Int(level)): ignoring it")
        }
    }

    /// The sensor's white level as the photo's DNG data records it (nil if it can't be read).
    private static func dngWhiteLevel(_ photo: AVCapturePhoto) -> Double? {
        guard let data = photo.fileDataRepresentation(),
              let source = CGImageSourceCreateWithData(data as CFData, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [String: Any],
              let dng = props[kCGImagePropertyDNGDictionary as String] as? [String: Any] else { return nil }
        let value = dng["WhiteLevel"]
        if let n = value as? NSNumber { return n.doubleValue }
        if let list = value as? [NSNumber] { return list.map(\.doubleValue).min() }
        return nil
    }

    /// Always called last for a capture. If no photo arrived for it (the capture failed), say so now
    /// rather than waiting for the time-out.
    func photoOutput(_ output: AVCapturePhotoOutput, didFinishCaptureFor resolvedSettings: AVCaptureResolvedPhotoSettings,
                     error: Error?) {
        let id = resolvedSettings.uniqueID
        let failure = error?.localizedDescription
        queue.async {
            guard id == self.pendingID, let callback = self.pending else { return }
            self.pending = nil
            self.pendingID = -1
            self.log("The camera finished without a photo" + (failure.map { ": \($0)" } ?? ""))
            callback(nil)
        }
    }
}

/// Variable aperture (iPhone 18 Pro and later, iOS 27). A profile is only valid at the aperture it was
/// trained at, so the aperture is locked like ISO and recorded with every reading.
///
/// If Xcode reports an error in here, check the iOS 27 SDK's AVCaptureDevice / AVCaptureDevice.Format
/// headers: the names below follow Apple's documentation (minLensAperture, maxLensAperture,
/// setExposureModeCustom(lensAperture:duration:iso:completionHandler:)). It's the only place they're used.
enum ApertureLock {
    /// The f-number to train new profiles at: a middle stop (sharp, little vignetting).
    static let defaultFNumber = 2.8

    /// Returns the f-number to lock, or nil to leave the aperture as the camera sets it (fixed-aperture
    /// lenses, and profiles trained without an aperture lock, like the first iPhone 18 Pro profile).
    /// Call with the device locked.
    static func choose(device: AVCaptureDevice, wanted: Double?) -> Double? {
        guard let wanted else { return nil }
        if #available(iOS 27.0, *) {
            let f = device.activeFormat
            let lo = Double(f.minLensAperture), hi = Double(f.maxLensAperture)
            guard hi - lo > 0.05 else { return nil }
            return clamp(wanted, lo, hi)
        }
        return nil
    }

    static func setExposure(device: AVCaptureDevice, aperture: Double, duration: CMTime, iso: Float,
                            done: @escaping (CMTime) -> Void) {
        if #available(iOS 27.0, *) {
            device.setExposureModeCustom(lensAperture: Float(aperture), duration: duration, iso: iso,
                                         completionHandler: done)
        } else {
            device.setExposureModeCustom(duration: duration, iso: iso, completionHandler: done)
        }
    }
}

// MARK: - The meter, as the app sees it

struct CameraReading {
    var rgb: [Double]        // dark-corrected sensor signal per second of exposure
    var duration: Double
    var uneven: Double
    var peak: Double         // brightest channel as a share of full scale
    var settings: CameraSettings
    /// When the light was captured (seconds since boot).
    var capturedAt: Double
}

/// Sensor baseline (black) measured with the lens covered at a short and a long shutter time.
struct DarkLevel: Codable {
    var short: [Double]      // at 1/30 s
    var long: [Double]       // at 1/4 s
    static let tShort = 1.0 / 30
    static let tLong = 0.25

    /// Baseline for any shutter time (straight line through the two measurements).
    func at(_ t: Double) -> [Double] {
        let f = (t - Self.tShort) / (Self.tLong - Self.tShort)
        return (0..<3).map { short[$0] + (long[$0] - short[$0]) * f }
    }
}

@MainActor
final class CameraMeter: ObservableObject {
    let engine = CameraEngine()
    @Published private(set) var running = false
    @Published private(set) var busy = false
    @Published private(set) var message = "The camera is off."
    @Published private(set) var dark: DarkLevel?
    @Published private(set) var lastReading: CameraReading?
    @Published private(set) var logLines: [String] = []
    private var duration = 1.0 / 250
    /// Extra listener for log lines (Lab mode forwards them to the Mac).
    var onLog: ((String) -> Void)?
    /// Only one measurement uses the camera at a time; others wait their turn. (Two at once would mix up
    /// which photo belongs to which shutter time.)
    private var inUse = false
    private var waiting: [CheckedContinuation<Void, Never>] = []

    private func acquire() async {
        if inUse {
            await withCheckedContinuation { waiting.append($0) }
        } else {
            inUse = true
        }
    }

    private func release() {
        if waiting.isEmpty {
            inUse = false
        } else {
            waiting.removeFirst().resume()      // hands the camera straight to the next in line
        }
    }

    init() {
        engine.logger = { [weak self] line in
            Task { @MainActor in self?.log(line) }
        }
        if let data = UserDefaults.standard.data(forKey: "darkLevel"),
           let d = try? JSONDecoder().decode(DarkLevel.self, from: data) {
            dark = d
            engine.setFloor(d.short.max() ?? 0)
        }
    }

    func log(_ line: String) {
        let stamp = Date().formatted(date: .omitted, time: .standard)
        logLines.append("\(stamp)  \(line)")
        if logLines.count > 200 { logLines.removeFirst(logLines.count - 200) }
        onLog?("camera: \(line)")
    }

    var ready: Bool { running && dark != nil }
    var settings: CameraSettings { engine.settings }

    /// Starts the camera. `aperture`: the f-number to lock (variable-aperture iPhones); nil leaves the
    /// aperture as the camera sets it.
    func start(aperture: Double? = nil) async {
        guard !running else { return }
        guard await AVCaptureDevice.requestAccess(for: .video) else {
            message = "TV Calibrator needs the camera to measure the TV. Allow it in Settings > Privacy & Security > Camera."
            return
        }
        message = "Starting the camera…"
        engine.wantedAperture = aperture
        let error: String? = await withCheckedContinuation { cont in
            engine.start { err in cont.resume(returning: err) }
        }
        if let error {
            message = error
        } else {
            running = true
            UIApplication.shared.isIdleTimerDisabled = true
            message = dark == nil ? "Cover the lens and measure the darkness once." : "Ready to measure."
        }
    }

    func stop() {
        engine.stop()
        running = false
        UIApplication.shared.isIdleTimerDisabled = false
        message = "The camera is off."
    }

    private func shot(_ seconds: Double, dark: Bool = false) async -> (RawStats?, Double, Double) {
        await withCheckedContinuation { cont in
            engine.capture(duration: seconds, learnsWhite: !dark) { stats, used, at in
                cont.resume(returning: (stats, used, at))
            }
        }
    }

    /// With the lens covered: the sensor's baseline at a short and a long shutter time, subtracted from
    /// every reading. Kept between launches; measure again after an iOS update.
    func measureDark() async {
        await acquire()
        defer { release() }
        busy = true
        defer { busy = false }
        message = "Measuring with the lens covered…"
        // Three shutter times: the shortest the camera has, 1/30 s and 1/4 s. In real darkness all three read
        // the same (the sensor's own baseline, whatever its level on this iPhone). Any light makes the longer
        // ones read higher: a little leaking in (through a fingertip, say) shows between 1/30 s and 1/4 s;
        // a lot (the lens not covered) can fill the sensor at both, and shows against the shortest.
        let (c, usedC, _) = await shot(engine.minDuration, dark: true)
        let (a, usedA, _) = await shot(DarkLevel.tShort, dark: true)
        let (b, usedB, _) = await shot(DarkLevel.tLong, dark: true)
        guard let a, let b, let c else {
            message = "Couldn't take a photo. Try again."
            return
        }
        let leak = (0..<3).map { b.mean[$0] - a.mean[$0] }.max() ?? 0
        let flood = (0..<3).map { a.mean[$0] - c.mean[$0] }.max() ?? 0
        if leak > 120 || flood > 120 {
            log(String(format: "Dark rejected: brightest colour reads %.0f at 1/%.0f s, %.0f at 1/%.0f s, %.0f at 1/%.0f s (white level %.0f)",
                       c.mean.max() ?? 0, 1 / usedC, a.mean.max() ?? 0, 1 / usedA, b.mean.max() ?? 0, 1 / usedB,
                       engine.whiteLevel))
            message = max(leak, flood) > 2000
                ? "Still seeing light. Lay the phone screen-up on a table, so the camera on the back is pressed flat against it, and try again."
                : "Some light is still getting in. A fingertip isn't enough: lay the phone screen-up on a table, so the camera on the back is pressed flat against it, and try again."
            return
        }
        let d = DarkLevel(short: a.mean, long: b.mean)
        dark = d
        engine.setFloor(d.short.max() ?? 0)
        if let data = try? JSONEncoder().encode(d) { UserDefaults.standard.set(data, forKey: "darkLevel") }
        log(String(format: "Dark at 1/%.0f s: %.1f %.1f %.1f; at 1/%.0f s: %.1f %.1f %.1f",
                   1 / usedA, a.mean[0], a.mean[1], a.mean[2], 1 / usedB, b.mean[0], b.mean[1], b.mean[2]))
        message = "Ready. Point the box at the centre of the TV."
    }

    /// One reading: finds a shutter time that exposes the patch well, then reports the signal.
    func measure() async -> (CameraReading?, String?) {
        await acquire()
        defer { release() }
        guard ready, let dark else { return (nil, "The camera isn't ready.") }
        busy = true
        defer { busy = false }
        var seconds = duration
        for _ in 0..<7 {
            let (stats, used, at) = await shot(seconds)
            guard let stats else {
                log("Measurement failed: no usable photo")
                return (nil, "The iPhone couldn't take a photo.")
            }
            let base = dark.at(used)
            let net = (0..<3).map { max(stats.mean[$0] - base[$0], 0) }
            let peak = (net.max() ?? 0) / max(engine.whiteLevel - (base.max() ?? 0), 1000)
            if stats.clipped || peak > 0.75 {
                if used <= engine.minDuration * 1.01 {
                    return (nil, "Too bright for the camera. Move the phone a little further from the TV.")
                }
                seconds = used / 4
                continue
            }
            if peak < 0.15 && used < engine.maxDuration * 0.99 {
                seconds = min(used * min(0.4 / max(peak, 0.002), 16), engine.maxDuration)
                continue
            }
            duration = used
            let reading = CameraReading(rgb: net.map { $0 / used }, duration: used, uneven: stats.uneven, peak: peak,
                                        settings: engine.settings, capturedAt: at)
            lastReading = reading
            return (reading, nil)
        }
        return (nil, "Couldn't find a good exposure for this patch.")
    }
}
