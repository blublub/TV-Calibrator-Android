import CalibrationKit
import Combine
import CryptoKit
import Foundation

enum Config {
    /// The server: the repo's server/ folder, published by the Cloudflare worker in push-worker/ (the
    /// same worker that sends the video pushes; docs/HOSTING.md). It holds index.json (which iPhone
    /// models have a camera profile), profiles/*.json and patterns.json (where the pattern videos are).
    /// The app ships with the profiles known when it was built and only downloads newer ones, so new
    /// profiles and links reach phones without an app update.
    ///
    /// The address isn't in the repo: it's read from ServerConfig.plist in the app's Resources folder,
    /// which Git ignores (copy Config/ServerConfig.example.plist there). Without it the app runs on its
    /// built-in profiles and links, with no downloads and no video notifications.
    static let server: URL? = {
        guard let file = Bundle.main.url(forResource: "ServerConfig", withExtension: "plist"),
              let dict = NSDictionary(contentsOf: file),
              let text = dict["Server"] as? String,
              let url = URL(string: text.hasSuffix("/") ? text : text + "/"),
              url.scheme == "https", let host = url.host, !host.contains("REPLACE-WITH") else { return nil }
        return url
    }()

    /// The catalog format this app reads.
    static let catalogFormat = 1

    static var serverConfigured: Bool { server != nil }
}

/// The hardware model of this iPhone, e.g. "iPhone18,1". Profiles are per model.
enum DeviceModel {
    static let identifier: String = {
        if let sim = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] { return sim }
        var info = utsname()
        uname(&info)
        return withUnsafeBytes(of: &info.machine) { raw in
            String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self)
        }
    }()
}

/// Picks this iPhone's camera profile, in this order:
/// 1. one imported by hand (Lab build only, to test a model before publishing it);
/// 2. this model's own profile: built into the app, or downloaded from the profile server when the server
///    has a newer version than the one built in;
/// 3. the standard profile (built in), when this model has no profile of its own yet.
/// The server is only needed for models and versions released after this build of the app.
@MainActor
final class ProfileService: ObservableObject {
    enum Status: Equatable {
        case checking
        case ready
        /// No profile for this model, and no standard profile to fall back on.
        case unsupported
        /// Couldn't reach the server and there's no profile to use.
        case offline(String)
        case notConfigured
    }

    enum Source: Equatable {
        /// This model's profile, shipped inside the app.
        case builtIn
        /// This model's profile, downloaded from the server (newer than the built-in one, or a new model).
        case downloaded
        /// Imported from a file (Lab build).
        case imported
        /// Another model's profile, used because this model has none yet.
        case standard
    }

    @Published private(set) var profile: CameraProfile?
    @Published private(set) var source: Source?
    @Published private(set) var status: Status = .checking
    @Published private(set) var catalog: ProfileCatalog?
    @Published private(set) var lastChecked: Date?

    /// The profile came from a file, not the app or the server.
    var isImported: Bool { source == .imported }
    /// Using the standard profile because this model has none of its own: readings are approximate.
    var isStandard: Bool { source == .standard }

    let model = DeviceModel.identifier
    let builtIn: BuiltInProfiles
    private let dir: URL

    init() {
        dir = (try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil,
                                            create: true))?.appendingPathComponent("Profiles", isDirectory: true)
            ?? FileManager.default.temporaryDirectory
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        builtIn = Bundle.main.url(forResource: "builtin-profiles", withExtension: "json")
            .flatMap { try? Data(contentsOf: $0) }
            .flatMap { try? JSONDecoder().decode(BuiltInProfiles.self, from: $0) } ?? BuiltInProfiles()
        catalog = load(ProfileCatalog.self, "catalog.json")
        choose()
    }

    private func load<T: Decodable>(_ type: T.Type, _ name: String) -> T? {
        guard let data = try? Data(contentsOf: dir.appendingPathComponent(name)) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }

    private func save<T: Encodable>(_ value: T, _ name: String) {
        if let data = try? JSONEncoder().encode(value) {
            try? data.write(to: dir.appendingPathComponent(name), options: .atomic)
        }
    }

    /// This model's own profile, built in or downloaded, whichever is newer.
    private var ownProfile: (CameraProfile, Source)? {
        let shipped = builtIn.profile(for: model)
        let downloaded = load(CameraProfile.self, "current.json").flatMap { $0.model == model && $0.isValid ? $0 : nil }
        switch (shipped, downloaded) {
        case let (s?, d?): return d.version > s.version ? (d, .downloaded) : (s, .builtIn)
        case let (s?, nil): return (s, .builtIn)
        case let (nil, d?): return (d, .downloaded)
        case (nil, nil): return nil
        }
    }

    /// Picks the profile to use from what's on the phone. No network.
    private func choose() {
        if let imported = load(CameraProfile.self, "imported.json"), imported.isValid {
            use(imported, .imported)
        } else if let own = ownProfile {
            use(own.0, own.1)
        } else if let standard = builtIn.standardProfile {
            use(standard, .standard)
        } else {
            profile = nil
            source = nil
        }
        status = profile == nil ? .checking : .ready
    }

    private func use(_ p: CameraProfile, _ from: Source) {
        profile = p
        source = from
    }

    /// Asks the server whether there's a profile for this model that the phone doesn't have yet (a model
    /// newer than this app, or a retrained version). Whatever happens, the phone keeps a usable profile.
    func refresh() async {
        if isImported { return }
        guard let server = Config.server else {
            status = profile == nil ? .notConfigured : .ready
            return
        }
        if profile == nil { status = .checking }
        do {
            let indexURL = server.appendingPathComponent("index.json")
            let (data, response) = try await URLSession.shared.data(from: indexURL)
            guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw URLError(.badServerResponse) }
            let cat = try JSONDecoder().decode(ProfileCatalog.self, from: data)
            if let minFormat = cat.minAppFormat, minFormat > Config.catalogFormat {
                if profile == nil { status = .offline("The profile server needs a newer version of the app. Update TV Calibrator.") }
                return
            }
            catalog = cat
            save(cat, "catalog.json")
            lastChecked = Date()
            guard let entry = cat.entry(for: model) else {
                status = profile == nil ? .unsupported : .ready
                return
            }
            if let own = ownProfile, own.0.version >= entry.version {
                status = .ready
                return
            }
            guard let fileURL = URL(string: entry.file, relativeTo: server)?.absoluteURL else {
                throw URLError(.badURL)
            }
            let (pdata, presp) = try await URLSession.shared.data(from: fileURL)
            guard (presp as? HTTPURLResponse)?.statusCode == 200 else { throw URLError(.badServerResponse) }
            let digest = SHA256.hash(data: pdata).map { String(format: "%02x", $0) }.joined()
            guard digest == entry.sha256.lowercased() else { throw URLError(.cannotDecodeContentData) }
            let p = try JSONDecoder().decode(CameraProfile.self, from: pdata)
            guard p.model == model, p.isValid else { throw URLError(.cannotDecodeContentData) }
            save(p, "current.json")
            choose()
        } catch {
            // Offline or a bad download: keep using what the phone has.
            status = profile == nil ? .offline("Couldn't reach the profile server: \(error.localizedDescription)") : .ready
        }
    }

    /// Imports a profile file: one from the profile server, or a "camera profile.json" exported by the
    /// Mac app (Camera profiles > Export). Used to try a newly trained model before publishing it.
    func importProfile(from url: URL) throws {
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        let data = try Data(contentsOf: url)
        let p = try ProfileImport.parse(data)
        guard p.model == model else {
            throw ProfileImport.Problem("That profile is for \(p.name.isEmpty ? p.model : p.name), not this iPhone (\(model)).")
        }
        save(p, "imported.json")
        choose()
    }

    /// Stops using the imported profile.
    func clearImported() async {
        try? FileManager.default.removeItem(at: dir.appendingPathComponent("imported.json"))
        choose()
        await refresh()
    }

    /// e.g. "iPhone 18 Pro v1 (built in)".
    var profileLabel: String {
        guard let p = profile else { return "None" }
        let from: String
        switch source {
        case .builtIn: from = "built in"
        case .downloaded: from = "downloaded"
        case .imported: from = "imported"
        case .standard: from = "standard profile"
        case nil: from = ""
        }
        return "\(p.name.isEmpty ? p.model : p.name) v\(p.version)" + (from.isEmpty ? "" : " (\(from))")
    }

    /// How exact this iPhone's readings are, for the results: an iPhone isn't a professional meter, and its
    /// profile says how close it got to one in training. Nil when there's nothing to say.
    /// The iPhone's margin of error on this kind of screen, in dE, for the band around a score's marker:
    /// about twice the profile's average error, and 3 when the profile is only approximate here.
    func marginDE(panel: PanelType) -> Double? {
        guard let p = profile else { return nil }
        guard !isStandard, panel != .unknown, let de = p.panel(panel.profileKey)?.deAvg else { return 3 }
        return max(1.0, (de * 4).rounded() / 2)
    }

    func marginNote(panel: PanelType) -> String? {
        guard let p = profile else { return nil }
        if isStandard {
            return "This iPhone has no camera profile of its own yet and uses the standard one, so its readings are "
                + "approximate: differences under about 3 dE may not be real."
        }
        guard panel != .unknown else {
            return "The app doesn't know what kind of screen this is, so the readings are approximate. "
                + "Choose its screen type in Screen settings for accurate readings."
        }
        guard let m = p.panel(panel.profileKey), let de = m.deAvg else {
            return "This iPhone's camera profile hasn't been matched on a \(panel.label) screen yet, so the readings "
                + "are approximate: differences under about 3 dE may not be real."
        }
        // Typical readings land within about twice the average error; round to half a dE, at least 1.
        let margin = max(1.0, (de * 4).rounded() / 2)
        return "An iPhone isn't a professional meter. On \(panel.label) screens this one's readings typically land "
            + "within about " + String(format: margin.truncatingRemainder(dividingBy: 1) == 0 ? "%.0f" : "%.1f", margin)
            + " dE of one, so smaller differences may not be real."
    }

    /// Short text for the settings and camera screens.
    var summary: String {
        switch status {
        case .checking: return "Checking for this iPhone's camera profile…"
        case .ready:
            guard let p = profile else { return "Ready." }
            let name = p.name.isEmpty ? p.model : p.name
            if isStandard {
                return "This iPhone (\(model)) doesn't have its own camera profile yet, so it uses the standard one (\(name)). "
                    + "Readings are approximate. When a profile for this model is ready, it downloads automatically."
            }
            return "\(name), profile v\(p.version)\(isImported ? " (imported)" : "") - accuracy: \(p.accuracyWord)"
        case .unsupported:
            return "This iPhone (\(model)) isn't supported yet: its camera hasn't been matched to the calibrator. You can still try the demo."
        case .offline(let why): return why
        case .notConfigured: return "This build has no camera profiles and no profile server (ServerConfig.plist)."
        }
    }
}

/// Reads profile files in either format.
enum ProfileImport {
    struct Problem: LocalizedError {
        let text: String
        init(_ t: String) { text = t }
        var errorDescription: String? { text }
    }

    static func parse(_ data: Data) throws -> CameraProfile {
        if let p = try? JSONDecoder().decode(CameraProfile.self, from: data), p.isValid {
            return p
        }
        // The Mac app's export: {"tvcal_camera_profile": 1, "model", "name", "M", "camera", "stats", "sessions"}
        guard let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              obj["tvcal_camera_profile"] != nil, let model = obj["model"] as? String else {
            throw Problem("That isn't a TV Calibrator camera profile.")
        }
        guard let M = obj["M"] as? [[Double]], M.count == 3, M.allSatisfy({ $0.count == 3 }) else {
            throw Problem("That profile has no trained matrix yet. Train it on at least one TV first.")
        }
        let cam = obj["camera"] as? [String: Any] ?? [:]
        let stats = obj["stats"] as? [String: Any] ?? [:]
        let tvs = (stats["tvs"] as? [String: Any]).map { Array($0.keys).sorted() } ?? []
        let settings = CameraSettings(iso: (cam["iso"] as? NSNumber)?.doubleValue, format: cam["format"] as? String,
                                      lens: cam["lens"] as? String, aperture: (cam["aperture"] as? NSNumber)?.doubleValue)
        let p = CameraProfile(model: model, name: obj["name"] as? String ?? "", version: 0, matrix: M, camera: settings,
                              stats: ProfileStats(deAvg: (stats["de_avg"] as? NSNumber)?.doubleValue,
                                                  unseen: (stats["unseen"] as? NSNumber)?.doubleValue, tvs: tvs))
        guard p.isValid else { throw Problem("That profile's matrix isn't usable.") }
        return p
    }
}
