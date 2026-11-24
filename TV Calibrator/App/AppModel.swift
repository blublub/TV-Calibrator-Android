import CalibrationKit
import Foundation
import SwiftUI

@MainActor
final class AppModel: ObservableObject {
    @Published var tvs: [TVRecord] = []

    let store = TVStore()
    let camera = CameraMeter()
    let profiles = ProfileService()
    let links = LinksService()
    #if LAB_MODE
    lazy var lab = LabLink(camera: camera)
    static let isLabBuild = true
    #else
    static let isLabBuild = false
    #endif
    private var sims: [String: SimTV] = [:]

    init() {
        tvs = store.loadTVs()
    }

    // MARK: TVs

    func add(_ tv: TVRecord) {
        store.save(tv)
        tvs.append(tv)
    }

    func update(_ tv: TVRecord) {
        store.save(tv)
        if let i = tvs.firstIndex(where: { $0.id == tv.id }) { tvs[i] = tv }
    }

    func delete(_ tv: TVRecord) {
        Notifications.shared.cancelRecheck(tvID: tv.id.uuidString)
        store.delete(tv)
        tvs.removeAll { $0.id == tv.id }
    }

    func addDemoTV() -> TVRecord {
        let n = tvs.filter { $0.isDemo }.count
        let tv = TVRecord(name: n == 0 ? "Demo Screen" : "Demo Screen \(n + 1)", brand: .samsung, multipoint: .twenty,
                          colourMenu: .rgbSliders, isDemo: true)
        add(tv)
        return tv
    }

    // MARK: demo TVs

    func simTV(_ tv: TVRecord, hdr: Bool) -> SimTV {
        let key = "\(tv.id.uuidString)-\(hdr)"
        if let s = sims[key] { return s }
        let u = tv.id.uuid
        let seed = (UInt64(u.0) << 16) | (UInt64(u.1) << 8) | UInt64(u.2)
        let saved = store.simState(tv, hdr: hdr)
        let s = SimTV(hdr: hdr, state: saved, seed: seed)
        if saved == nil {
            s.s.p20Shift = 0.10          // demo: the multipoint controls act 10% above their label
            store.saveSim(s.s, tv, hdr: hdr)
        }
        sims[key] = s
        return s
    }

    /// Demo: "press the remote".
    func demoApply(_ tv: TVRecord, hdr: Bool, _ changes: [String: Int]) {
        let s = simTV(tv, hdr: hdr)
        s.apply(changes)
        store.saveSim(s.s, tv, hdr: hdr)
    }

    // MARK: meters

    /// Why the camera can't measure yet, or nil when it's ready.
    var cameraBlocker: String? {
        guard profiles.profile != nil else { return profiles.summary }
        if !camera.running { return "Start the camera (Camera setup)." }
        if camera.dark == nil { return "Measure the camera's darkness once (Camera setup)." }
        return nil
    }

    func startCamera() async {
        // Readings must be taken the way the profile was trained: at the aperture it records, or with the
        // aperture left alone if it records none (the first iPhone 18 Pro profile was trained that way).
        // The Lab build always locks one (the profile's, or the default f-stop) on variable-aperture iPhones:
        // an unlocked aperture can change how much light the camera collects between training sessions.
        // Fixed-aperture iPhones ignore it.
        let recorded = profiles.profile?.camera.aperture
        if let recorded {
            await camera.start(aperture: recorded)
        } else if AppModel.isLabBuild || profiles.profile == nil {
            await camera.start(aperture: ApertureLock.defaultFNumber)
        } else {
            await camera.start(aperture: nil)
        }
    }

    /// A meter that follows a pattern video.
    func videoMeter(_ tv: TVRecord, hdr: Bool, sequence: PatternSequence) -> Meter? {
        if tv.isDemo {
            return SimMeter(tv: simTV(tv, hdr: hdr), sequence: sequence, playAt: 2.0, realTime: 0.1,
                            seed: UInt64(Date().timeIntervalSince1970) % 100_000)
        }
        guard cameraBlocker == nil, let p = profiles.profile else { return nil }
        return CameraXYZMeter(camera: camera, profile: p, checkSettings: !profiles.isStandard, panel: tv.panel.profileKey)
    }

    /// A meter for the live guided steps. In the demo it reads whatever pattern is being asked for.
    func liveMeter(_ tv: TVRecord, hdr: Bool, shown: ShownPatch) -> Meter? {
        if tv.isDemo { return SimLiveMeter(tv: simTV(tv, hdr: hdr), shown: shown) }
        guard cameraBlocker == nil, let p = profiles.profile else { return nil }
        return CameraXYZMeter(camera: camera, profile: p, checkSettings: !profiles.isStandard, panel: tv.panel.profileKey)
    }
}
