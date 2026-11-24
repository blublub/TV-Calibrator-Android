import Foundation

/// The signal the TV is calibrated for. Each one has its own folder of pattern videos.
public enum SignalMode: String, Codable, CaseIterable, Identifiable, Sendable {
    case sdr, hdr10, hdr10p, dv

    public var id: String { rawValue }
    public var isHDR: Bool { self != .sdr }
    public var label: String {
        switch self {
        case .sdr: return "SDR"
        case .hdr10: return "HDR10"
        case .hdr10p: return "HDR10+"
        case .dv: return "Dolby Vision"
        }
    }
    /// What the TV shows as its badge when the right signal arrives.
    public var badge: String {
        switch self {
        case .sdr: return "SDR"
        case .hdr10: return "HDR"
        case .hdr10p: return "HDR10+"
        case .dv: return "Dolby Vision"
        }
    }
    /// File-name prefix and folder of the pattern videos, e.g. "DV 03 - Grey 50 (middle).mp4" in "Dolby Vision".
    public var packPrefix: String {
        switch self {
        case .sdr: return "SDR"
        case .hdr10: return "HDR10"
        case .hdr10p: return "HDR10+"
        case .dv: return "DV"
        }
    }
    public var packFolder: String {
        switch self {
        case .sdr: return "SDR"
        case .hdr10: return "HDR10"
        case .hdr10p: return "HDR10+"
        case .dv: return "Dolby Vision"
        }
    }
}

public enum PatchKind: String, Codable, Sendable {
    case white, grey, colour
}

/// One measured pattern: a centred window (10% of the screen) of a grey, white or colour.
public struct Patch: Codable, Equatable, Identifiable, Sendable {
    public var id: String
    public var kind: PatchKind
    /// SDR: signal level 0-1. HDR greys/whites: PQ code value 0-1. HDR colours: nil.
    public var level: Double?
    public var name: String
    /// SDR: R'G'B' signal 0-1.
    public var signal: Vec3?
    /// HDR: linear RGB in nits, in the colour's own gamut (P3).
    public var nits: Vec3?
    /// Colours: which primaries are on (1/0).
    public var rgb: Vec3?
    /// Seconds on screen in the videos.
    public var hold: Double

    public init(id: String, kind: PatchKind, level: Double?, name: String, signal: Vec3? = nil, nits: Vec3? = nil,
                rgb: Vec3? = nil, hold: Double = Patterns.hold) {
        self.id = id
        self.kind = kind
        self.level = level
        self.name = name
        self.signal = signal
        self.nits = nits
        self.rgb = rgb
        self.hold = hold
    }

    /// Grey/white level as the video's own percentage (SDR signal % or PQ %), e.g. 50 for "G50".
    public var percent: Double { (level ?? 0) * 100 }
}

/// A pattern video's timeline: black, three sync flashes (grey, white, grey), then the patches.
public struct PatternSequence: Sendable {
    public var hdr: Bool
    /// The sync flashes: SDR signal levels, or HDR nits.
    public var syncLow: Double
    public var syncHigh: Double
    public var patches: [Patch]

    /// Start time of each patch, in seconds after the sync.
    public var offsets: [Double] {
        var out: [Double] = []
        var t = 0.0
        for p in patches {
            out.append(t)
            t += p.hold
        }
        return out
    }

    /// Same order and timing rules, only some patches (the quick-check videos).
    public func keeping(_ ids: [String]) -> PatternSequence {
        var s = self
        s.patches = patches.filter { ids.contains($0.id) }
        return s
    }
}

public enum Patterns {
    /// Seconds of black before the sync.
    public static let lead = 10.0
    /// Low / high / low sync flashes, seconds each.
    public static let syncHold = 6.0
    /// Seconds per measured patch.
    public static let hold = 7.0
    /// HDR colours: very saturated light can make a meter integrate for several seconds.
    public static let hdrColourHold = 12.0
    /// Point-finder greys.
    public static let finderHold = 5.0

    public static let colours: [(name: String, rgb: Vec3)] = [
        ("RED", Vec3(1, 0, 0)), ("GREEN", Vec3(0, 1, 0)), ("BLUE", Vec3(0, 0, 1)),
        ("CYAN", Vec3(0, 1, 1)), ("MAGENTA", Vec3(1, 0, 1)), ("YELLOW", Vec3(1, 1, 0)),
    ]
    public static let colourIDs = ["RED", "GREEN", "BLUE", "CYAN", "MAGENTA", "YELLOW"]
    public static let hueOrder = ["RED", "YELLOW", "GREEN", "CYAN", "BLUE", "MAGENTA"]
    /// The order colour menus list them in.
    public static let menuOrder = ["RED", "GREEN", "BLUE", "YELLOW", "CYAN", "MAGENTA"]

    static func f0(_ x: Double) -> String { String(format: "%.0f", x) }

    /// The full automatic-round sequence (the long tvcal_auto video).
    public static func sequence(hdr: Bool) -> PatternSequence {
        var pats: [Patch] = []
        if !hdr {
            pats.append(Patch(id: "W0", kind: .white, level: 1.0, name: "100% WHITE", signal: Vec3(1, 1, 1)))
            for p in stride(from: 5, through: 100, by: 5) {
                let l = Double(p) / 100
                pats.append(Patch(id: "G\(p)", kind: .grey, level: l, name: "\(p)% GREY", signal: Vec3(l, l, l)))
            }
            for (n, c) in colours {
                pats.append(Patch(id: n, kind: .colour, level: 0.75, name: "\(n) 75%", signal: c * 0.75, rgb: c))
            }
            pats.append(Patch(id: "W1", kind: .white, level: 1.0, name: "100% WHITE (drift check)", signal: Vec3(1, 1, 1)))
            return PatternSequence(hdr: false, syncLow: 0.30, syncHigh: 1.0, patches: pats)
        }
        pats.append(Patch(id: "W0", kind: .white, level: ColourMath.pqEncode(100), name: "100 nit WHITE",
                          nits: Vec3(100, 100, 100)))
        for p in stride(from: 25, through: 75, by: 5) {
            let n = ColourMath.pqDecode(Double(p) / 100)
            pats.append(Patch(id: "G\(p)", kind: .grey, level: Double(p) / 100, name: "PQ \(p)% GREY (\(f0(n)) nits)",
                              nits: Vec3(n, n, n)))
        }
        for (n, c) in colours {
            pats.append(Patch(id: n, kind: .colour, level: nil, name: "P3 \(n)", nits: c * 100, rgb: c,
                              hold: hdrColourHold))
        }
        pats.append(Patch(id: "W1", kind: .white, level: ColourMath.pqEncode(100), name: "100 nit WHITE (drift check)",
                          nits: Vec3(100, 100, 100)))
        return PatternSequence(hdr: true, syncLow: 25, syncHigh: 200, patches: pats)
    }

    public static func patch(hdr: Bool, id: String) -> Patch? {
        sequence(hdr: hdr).patches.first { $0.id == id }
    }

    /// Length of the automatic-round video in seconds.
    public static func videoLength(hdr: Bool) -> Int {
        Int(lead + 3 * syncHold) + Int(sequence(hdr: hdr).patches.reduce(0) { $0 + $1.hold })
    }

    /// The point-finder video: the same sync, then finely spaced greys (5 s each).
    public static func finderSequence(hdr: Bool) -> PatternSequence {
        let base = sequence(hdr: hdr)
        var pats: [Patch] = [
            hdr ? Patch(id: "FW", kind: .white, level: ColourMath.pqEncode(100), name: "WHITE", nits: Vec3(100, 100, 100),
                        hold: finderHold)
                : Patch(id: "FW", kind: .white, level: 1.0, name: "WHITE", signal: Vec3(1, 1, 1), hold: finderHold),
        ]
        if !hdr {
            for p in stride(from: 10, through: 100, by: 5) {
                let l = Double(p) / 100
                pats.append(Patch(id: "F\(p)", kind: .grey, level: l, name: "\(p)% GREY", signal: Vec3(l, l, l),
                                  hold: finderHold))
            }
        } else {
            for i in 0..<25 {
                let pq = 20 + 2.5 * Double(i)
                let n = ColourMath.pqDecode(pq / 100)
                pats.append(Patch(id: "F\(Int(pq * 10))", kind: .grey, level: pq / 100,
                                  name: "PQ \(String(format: "%g", pq))% GREY (\(f0(n)) nits)", nits: Vec3(n, n, n),
                                  hold: finderHold))
            }
        }
        var s = base
        s.patches = pats
        return s
    }

    /// The patches in the one-to-two-minute quick-check videos.
    public static func quickIDs(hdr: Bool) -> [String] {
        hdr ? ["W0", "G30", "G40", "G50", "G60", "G70", "RED", "GREEN", "BLUE", "CYAN", "MAGENTA", "YELLOW"]
            : ["W0", "G20", "G40", "G60", "G80", "G100", "RED", "GREEN", "BLUE", "CYAN", "MAGENTA", "YELLOW"]
    }

    public static func quickSequence(hdr: Bool, ids: [String]? = nil) -> PatternSequence {
        sequence(hdr: hdr).keeping(ids ?? quickIDs(hdr: hdr))
    }

    /// The pattern pack on the USB stick: (patch id or "QUICK", file label), in file order.
    public static func packFiles(hdr: Bool) -> [(key: String, label: String)] {
        if !hdr {
            return [("QUICK", "00 - Quick check (play from the start)"), ("W0", "01 - White (bright end)"),
                    ("G30", "02 - Grey 30 (dark end)"), ("G50", "03 - Grey 50 (middle)"), ("G20", "04 - Grey 20"),
                    ("G40", "05 - Grey 40"), ("G60", "06 - Grey 60"), ("G80", "07 - Grey 80"), ("RED", "08 - Red"),
                    ("GREEN", "09 - Green"), ("BLUE", "10 - Blue"), ("CYAN", "11 - Cyan"), ("MAGENTA", "12 - Magenta"),
                    ("YELLOW", "13 - Yellow")]
        }
        return [("QUICK", "00 - Quick check (play from the start)"), ("G65", "01 - Grey 65 (bright end)"),
                ("G35", "02 - Grey 35 (dark end)"), ("G50", "03 - Grey 50 (middle)"), ("G40", "04 - Grey 40"),
                ("G60", "05 - Grey 60"), ("G70", "06 - Grey 70"), ("RED", "07 - Red"), ("GREEN", "08 - Green"),
                ("BLUE", "09 - Blue"), ("CYAN", "10 - Cyan"), ("MAGENTA", "11 - Magenta"), ("YELLOW", "12 - Yellow")]
    }

    /// File name (without .mp4) of the pattern video for a patch, e.g. "SDR 02 - Grey 30 (dark end)".
    public static func packFile(mode: SignalMode, id: String) -> String? {
        packFiles(hdr: mode.isHDR).first { $0.key == id }.map { "\(mode.packPrefix) \($0.label)" }
    }

    /// The point-finder video for a mode, e.g. "SDR 00b - Point finder (play from the start)".
    public static func finderFile(mode: SignalMode) -> String {
        "\(mode.packPrefix) 00b - Point finder (play from the start)"
    }

    /// The long automatic-round video.
    public static func autoFile(hdr: Bool) -> String { hdr ? "tvcal_auto_HDR" : "tvcal_auto_SDR" }

    /// Colour targets: chromaticity and brightness relative to white (Rec.709 for SDR, P3 for HDR).
    public static func colourTargets(hdr: Bool) -> [String: (xy: Point2, Yrel: Double)] {
        let M = ColourMath.rgbToXYZMatrix((hdr ? ColourMath.Gamut.p3 : .rec709).primaries)
        var out: [String: (xy: Point2, Yrel: Double)] = [:]
        for (n, c) in colours {
            let v = ColourMath.matvec(M, c)
            out[n] = (ColourMath.xyzToXY(v), v.y)
        }
        return out
    }
}
