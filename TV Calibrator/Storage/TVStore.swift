import CalibrationKit
import Foundation

/// One TV the user calibrates.
struct TVRecord: Codable, Identifiable, Hashable {
    var id = UUID()
    var name: String
    var brand: Brand = .other
    var multipoint: Multipoint = .twenty
    var colourMenu: ColourMenu = .hsl
    /// False until the user has told us what the TV's menus look like ("Not sure" when adding the TV).
    /// The app then asks when it reaches the step that needs it.
    var multipointKnown = true
    var colourMenuKnown = true
    /// The kind of screen, which picks the camera's conversion.
    var panel: PanelType = .unknown
    /// A simulated TV for trying the app (and for App Review) without a real one.
    var isDemo = false
    var created = Date()
}

extension TVRecord {
    /// Reads TVs saved by any version of the app (missing fields take their defaults).
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(UUID.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        brand = (try? c.decodeIfPresent(Brand.self, forKey: .brand)) ?? .other
        multipoint = (try? c.decodeIfPresent(Multipoint.self, forKey: .multipoint)) ?? .twenty
        colourMenu = (try? c.decodeIfPresent(ColourMenu.self, forKey: .colourMenu)) ?? .hsl
        multipointKnown = try c.decodeIfPresent(Bool.self, forKey: .multipointKnown) ?? true
        colourMenuKnown = try c.decodeIfPresent(Bool.self, forKey: .colourMenuKnown) ?? true
        panel = (try? c.decodeIfPresent(PanelType.self, forKey: .panel)) ?? .unknown
        isDemo = try c.decodeIfPresent(Bool.self, forKey: .isDemo) ?? false
        created = try c.decodeIfPresent(Date.self, forKey: .created) ?? Date()
    }
}

/// The guided steps, in order. "fine" (multipoint fine-tune) is reached from the Check step.
enum GuideStep: String, Codable, CaseIterable, Identifiable {
    case setup, before, bright, dark, check, colours, after, fine
    var id: String { rawValue }
    static let stepper: [GuideStep] = [.setup, .before, .bright, .dark, .check, .colours, .after]
    var label: String {
        switch self {
        case .setup: return "Set up"
        case .before: return "Before"
        case .bright: return "Bright end"
        case .dark: return "Dark end"
        case .check: return "Check"
        case .colours: return "Colours"
        case .after: return "After"
        case .fine: return "Fine-tune"
        }
    }
    var next: GuideStep? {
        guard let i = GuideStep.stepper.firstIndex(of: self), i + 1 < GuideStep.stepper.count else { return nil }
        return GuideStep.stepper[i + 1]
    }
}

struct GuideProgress: Codable {
    var step: GuideStep = .setup
    var done: [GuideStep] = []
    /// Colours finished (or marked "as close as it gets").
    var colours: [String] = []
    /// Steps and colours that hit the TV's limit.
    var limits: [String] = []
    var colour: String?
    var finePid: String?
}

struct GuidedSession: Codable {
    var started = Date()
    var before: Measurements?
    var after: Measurements?
    var finished: Date?
}

struct FinderData: Codable {
    var base: Measurements?
    var nudged: Measurements?
    /// Grey id -> TV control the user found by hand.
    var overrides: [String: Int] = [:]
}

// Saved files must keep loading after an app update adds a field, so each of these reads what's there
// and takes the default for anything missing (the synthesized decoders would reject the whole file).
extension GuideProgress {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        step = (try? c.decodeIfPresent(GuideStep.self, forKey: .step)) ?? .setup
        done = (try? c.decodeIfPresent([GuideStep].self, forKey: .done)) ?? []
        colours = (try? c.decodeIfPresent([String].self, forKey: .colours)) ?? []
        limits = (try? c.decodeIfPresent([String].self, forKey: .limits)) ?? []
        colour = try? c.decodeIfPresent(String.self, forKey: .colour)
        finePid = try? c.decodeIfPresent(String.self, forKey: .finePid)
    }
}

extension GuidedSession {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        started = (try? c.decodeIfPresent(Date.self, forKey: .started)) ?? Date()
        before = try? c.decodeIfPresent(Measurements.self, forKey: .before)
        after = try? c.decodeIfPresent(Measurements.self, forKey: .after)
        finished = try? c.decodeIfPresent(Date.self, forKey: .finished)
    }
}

extension FinderData {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        base = try? c.decodeIfPresent(Measurements.self, forKey: .base)
        nudged = try? c.decodeIfPresent(Measurements.self, forKey: .nudged)
        overrides = (try? c.decodeIfPresent([String: Int].self, forKey: .overrides)) ?? [:]
    }
}

extension GuideState {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        sessions = (try? c.decodeIfPresent([GuidedSession].self, forKey: .sessions)) ?? []
        check = try? c.decodeIfPresent(Measurements.self, forKey: .check)
        finder = (try? c.decodeIfPresent(FinderData.self, forKey: .finder)) ?? FinderData()
        progress = (try? c.decodeIfPresent(GuideProgress.self, forKey: .progress)) ?? GuideProgress()
    }
}

/// One TV's guided calibration for one signal mode.
struct GuideState: Codable {
    var sessions: [GuidedSession] = []
    /// The latest Check step measurement, so its result is still there after leaving the step.
    var check: Measurements?
    var finder = FinderData()
    var progress = GuideProgress()

    /// This TV's own white and primaries, from the latest Before/After check (for colour hints).
    var reference: (whiteY: Double?, basis: [Vec3]?) {
        let checks = sessions.reversed().flatMap { [$0.after, $0.before].compactMap { $0 } }
        let white = checks.lazy.compactMap { $0["W0"]?.y }.first
        let basis = checks.lazy.map { m in ["RED", "GREEN", "BLUE"].compactMap { m[$0] } }.first { $0.count == 3 }
        return (white, basis)
    }
}

/// Everything is kept as JSON files in Application Support/TVs/<id>/.
final class TVStore {
    let root: URL
    private let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.outputFormatting = [.prettyPrinted, .sortedKeys]
        e.dateEncodingStrategy = .iso8601
        return e
    }()
    private let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .iso8601
        return d
    }()

    init() {
        let base = (try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                                 appropriateFor: nil, create: true)) ?? FileManager.default.temporaryDirectory
        root = base.appendingPathComponent("TVs", isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    func folder(_ tv: TVRecord) -> URL {
        let d = root.appendingPathComponent(tv.id.uuidString, isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    private func read<T: Decodable>(_ type: T.Type, _ url: URL) -> T? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? decoder.decode(T.self, from: data)
    }

    private func write<T: Encodable>(_ value: T, _ url: URL) {
        if let data = try? encoder.encode(value) {
            try? data.write(to: url, options: .atomic)
        }
    }

    // MARK: TVs

    func loadTVs() -> [TVRecord] {
        let dirs = (try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? []
        // A folder with no tv.json is left over from a deleted screen: tidy it away.
        for d in dirs where !FileManager.default.fileExists(atPath: d.appendingPathComponent("tv.json").path) {
            try? FileManager.default.removeItem(at: d)
        }
        return dirs.compactMap { read(TVRecord.self, $0.appendingPathComponent("tv.json")) }
            .sorted { $0.created < $1.created }
    }

    func save(_ tv: TVRecord) { write(tv, folder(tv).appendingPathComponent("tv.json")) }

    func delete(_ tv: TVRecord) {
        try? FileManager.default.removeItem(at: root.appendingPathComponent(tv.id.uuidString, isDirectory: true))
    }

    // MARK: guided (per signal mode)

    func guideState(_ tv: TVRecord, mode: SignalMode) -> GuideState {
        read(GuideState.self, folder(tv).appendingPathComponent("guide-\(mode.rawValue).json")) ?? GuideState()
    }

    func save(_ st: GuideState, _ tv: TVRecord, mode: SignalMode) {
        write(st, folder(tv).appendingPathComponent("guide-\(mode.rawValue).json"))
    }

    // MARK: demo TVs

    func simState(_ tv: TVRecord, hdr: Bool) -> SimTV.State? {
        read(SimTV.State.self, folder(tv).appendingPathComponent(hdr ? "sim-hdr.json" : "sim-sdr.json"))
    }

    func saveSim(_ s: SimTV.State, _ tv: TVRecord, hdr: Bool) {
        write(s, folder(tv).appendingPathComponent(hdr ? "sim-hdr.json" : "sim-sdr.json"))
    }

    // MARK: readable log + export

    func appendLog(_ tv: TVRecord, _ text: String) {
        let url = folder(tv).appendingPathComponent("log.md")
        if let h = try? FileHandle(forWritingTo: url) {
            _ = try? h.seekToEnd()
            try? h.write(contentsOf: Data(text.utf8))
            try? h.close()
        } else {
            try? Data(text.utf8).write(to: url)
        }
    }

    /// One file with everything about this TV, to share for troubleshooting.
    func export(_ tv: TVRecord, profile: CameraProfile?) -> URL? {
        struct Export: Encodable {
            var exported: Date
            var app: String
            var tv: TVRecord
            var phone: String
            var profile: CameraProfile?
            var guided: [String: GuideState]
        }
        var guided: [String: GuideState] = [:]
        for mode in SignalMode.allCases {
            let st = guideState(tv, mode: mode)
            if !st.sessions.isEmpty || st.finder.base != nil { guided[mode.rawValue] = st }
        }
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
        let e = Export(exported: Date(), app: version, tv: tv, phone: DeviceModel.identifier, profile: profile,
                       guided: guided)
        let safe = tv.name.replacingOccurrences(of: "/", with: "-")
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(safe) - TV Calibrator export.json")
        guard let data = try? encoder.encode(e) else { return nil }
        try? data.write(to: url, options: .atomic)
        return url
    }
}
