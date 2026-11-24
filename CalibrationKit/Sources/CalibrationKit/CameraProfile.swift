import Foundation

// Camera profiles: what turns an iPhone model's raw sensor numbers into real colour (XYZ). Each one is
// trained on the Mac against the Display Plus HL ("synced to the pro calibrator"), on several TVs, and
// published on the profile server. A profile only holds for the camera settings it was trained with.

/// The locked camera settings a profile was trained with.
public struct CameraSettings: Codable, Equatable, Sendable {
    public var iso: Double?
    /// RAW format, e.g. "4032x3024 grb4".
    public var format: String?
    /// e.g. "Back Camera".
    public var lens: String?
    /// f-number. Only on iPhones with a variable aperture (iPhone 18 Pro); nil on fixed-aperture lenses.
    public var aperture: Double?

    public init(iso: Double? = nil, format: String? = nil, lens: String? = nil, aperture: Double? = nil) {
        self.iso = iso
        self.format = format
        self.lens = lens
        self.aperture = aperture
    }

    /// Which settings differ from what the profile expects. Empty if they match.
    public func mismatches(_ got: CameraSettings) -> [String] {
        var diffs: [String] = []
        if let a = iso, let b = got.iso, abs(a - b) > 0.5 { diffs.append("ISO: profile \(fmt(a)), now \(fmt(b))") }
        if let a = format, let b = got.format, a != b { diffs.append("RAW format: profile \(a), now \(b)") }
        if let a = lens, let b = got.lens, a != b { diffs.append("lens: profile \(a), now \(b)") }
        if let a = aperture, let b = got.aperture, abs(a - b) > 0.05 {
            diffs.append("aperture: profile f/\(fmt(a, 2)), now f/\(fmt(b, 2))")
        }
        return diffs
    }

    func fmt(_ v: Double, _ d: Int = 0) -> String { String(format: "%.\(d)f", v) }
}

/// How well a profile matched the Display Plus HL in training.
public struct ProfileStats: Codable, Equatable, Sendable {
    /// Average dE against the HL on the training TVs.
    public var deAvg: Double?
    /// Estimated average dE on a TV it has never seen (leave-one-TV-out).
    public var unseen: Double?
    /// TVs it was trained on.
    public var tvs: [String]

    public init(deAvg: Double? = nil, unseen: Double? = nil, tvs: [String] = []) {
        self.deAvg = deAvg
        self.unseen = unseen
        self.tvs = tvs
    }
}

/// The conversion for one type of screen (panel technology, e.g. "woled", "qdlcd", "rgbled"). Screens make
/// white from different mixes of light, and a camera isn't a perfect colorimeter, so one conversion can't
/// serve them all: trained on a quantum-dot mini-LED, an iPhone read a micro RGB TV about 11 dE off.
public struct PanelMatrix: Codable, Equatable, Sendable {
    /// Camera RGB -> XYZ for this type of screen.
    public var matrix: Mat3
    /// Camera RGB -> XYZ for greys and near-greys, when they need their own: on a WOLED TV the white
    /// subpixel joins in for greys, so one matrix can't fit them and the colours (greys were 2.9 dE off,
    /// 0.5 with this). Blended with `matrix` between 0.02 and 0.04 from D65 in x, y.
    public var neutral: Mat3?
    /// Average dE against the HL on this type's training TVs.
    public var deAvg: Double?
    /// True when worked out from another iPhone model trained on this type, not measured with this one.
    public var derived: Bool?

    public init(matrix: Mat3, neutral: Mat3? = nil, deAvg: Double? = nil, derived: Bool? = nil) {
        self.matrix = matrix
        self.neutral = neutral
        self.deAvg = deAvg
        self.derived = derived
    }
}

/// One iPhone model's profile, as published on the profile server.
public struct CameraProfile: Codable, Equatable, Sendable {
    public var format = 1
    /// Hardware model identifier, e.g. "iPhone18,1".
    public var model: String
    /// Marketing name, e.g. "iPhone 18 Pro".
    public var name: String
    /// Increases each time the profile is retrained.
    public var version: Int
    /// Camera RGB (dark-corrected sensor signal per second) -> XYZ.
    public var matrix: Mat3
    public var camera: CameraSettings
    public var stats: ProfileStats
    public var trained: String?
    /// One conversion per type of screen this model knows; `matrix` is the general one for any other.
    public var panels: [String: PanelMatrix]?

    public init(model: String, name: String, version: Int, matrix: Mat3, camera: CameraSettings,
                stats: ProfileStats, trained: String? = nil, panels: [String: PanelMatrix]? = nil) {
        self.model = model
        self.name = name
        self.version = version
        self.matrix = matrix
        self.camera = camera
        self.stats = stats
        self.trained = trained
        self.panels = panels
    }

    /// The conversion for a type of screen, when this profile has one.
    public func panel(_ key: String?) -> PanelMatrix? {
        guard let key, let p = panels?[key], p.matrix.count == 3, p.matrix.allSatisfy({ $0.count == 3 }) else {
            return nil
        }
        return p
    }

    /// Camera RGB -> XYZ on a given type of screen (the general conversion when it isn't known).
    public func xyz(cameraRGB rgb: Vec3, panel key: String?) -> Vec3 {
        guard let p = panel(key) else { return ColourMath.matvec(matrix, rgb) }
        let colour = ColourMath.matvec(p.matrix, rgb)
        guard let n = p.neutral, n.count == 3, n.allSatisfy({ $0.count == 3 }) else { return colour }
        let s = colour.x + colour.y + colour.z
        guard colour.y > 0, s > 0 else { return colour }
        let dx: Double = colour.x / s - 0.3127
        let dy: Double = colour.y / s - 0.3290
        let d = (dx * dx + dy * dy).squareRoot()
        let w = min(1, max(0, (d - 0.02) / 0.02))          // 0 near grey (neutral), 1 for colours
        let neutral = ColourMath.matvec(n, rgb)
        return neutral * (1 - w) + colour * w
    }

    public var isValid: Bool {
        matrix.count == 3 && matrix.allSatisfy { $0.count == 3 && $0.allSatisfy { $0.isFinite } }
    }

    /// Camera RGB -> XYZ.
    public func xyz(cameraRGB rgb: Vec3) -> Vec3 { ColourMath.matvec(matrix, rgb) }

    /// "Very accurate" ... for the unseen-TV estimate (or the training average).
    public var accuracyWord: String {
        guard let de = stats.unseen ?? stats.deAvg else { return "Not rated" }
        return de < 1 ? "Excellent" : de < 2 ? "Very good" : de < 3 ? "Good" : "Approximate"
    }
}

/// The profile server's index: which iPhone models are supported.
public struct ProfileCatalog: Codable, Equatable, Sendable {
    public struct Entry: Codable, Equatable, Sendable {
        public var model: String
        public var name: String
        public var version: Int
        /// Path of the profile file, relative to the catalog.
        public var file: String
        /// SHA-256 of the profile file, hex.
        public var sha256: String
        public var deAvg: Double?
        public var unseen: Double?
        public var tvs: Int?
    }

    public var format: Int
    public var updated: String
    /// The oldest app catalog format that can read this one.
    public var minAppFormat: Int?
    public var profiles: [Entry]

    public func entry(for model: String) -> Entry? { profiles.first { $0.model == model } }
}

/// The profiles built into the app (Resources/builtin-profiles.json, written by tools/build_catalog.py).
/// `standard` names the profile used on iPhones that have no profile of their own yet.
public struct BuiltInProfiles: Codable, Equatable, Sendable {
    public var format: Int
    /// Model identifier of the standard (fallback) profile, e.g. "iPhone18,1".
    public var standard: String?
    public var profiles: [CameraProfile]

    public init(format: Int = 1, standard: String? = nil, profiles: [CameraProfile] = []) {
        self.format = format
        self.standard = standard
        self.profiles = profiles
    }

    /// This model's own profile, if one is built in.
    public func profile(for model: String) -> CameraProfile? {
        profiles.filter { $0.model == model && $0.isValid }.max { $0.version < $1.version }
    }

    /// The profile to use when a model has none of its own.
    public var standardProfile: CameraProfile? {
        standard.flatMap { profile(for: $0) }
    }
}
