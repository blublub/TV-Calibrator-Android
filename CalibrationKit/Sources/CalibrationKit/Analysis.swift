import Foundation

/// One grey step of a round.
public struct GreyResult: Codable, Equatable, Sendable, Identifiable {
    public var id: String
    public var level: Double
    public var name: String
    public var xyz: Vec3
    public var Y: Double
    public var targetY: Double
    /// R, G, B relative to brightness: (1, 1, 1) is exactly on D65.
    public var bal: Vec3
    public var rgb: Vec3
    /// White-point error (dE2000, brightness ignored).
    public var de: Double
    /// HDR: above what this TV can reach, so left out of corrections.
    public var clipped: Bool

    /// Red and blue relative to green, minus 1 (0 = on target). What the white-balance bars show.
    public var redVsGreen: Double { bal.y > 0 ? bal.x / bal.y - 1 : 0 }
    public var blueVsGreen: Double { bal.y > 0 ? bal.z / bal.y - 1 : 0 }
}

/// One colour of a round.
public struct ColourResult: Codable, Equatable, Sendable, Identifiable {
    public var name: String
    public var xyz: Vec3
    public var target: Vec3
    public var xy: Point2
    public var txy: Point2
    /// Saturation error, %.
    public var sat: Double
    /// Hue error, degrees (u'v' angle around D65).
    public var hue: Double
    /// Brightness error, %.
    public var lum: Double
    public var de: Double
    public var id: String { name }

    public func value(_ axis: HSLAxis) -> Double {
        switch axis {
        case .hue: return hue
        case .sat: return sat
        case .lum: return lum
        }
    }
}

public enum HSLAxis: String, Codable, CaseIterable, Sendable {
    case hue, sat, lum
}

/// The analysis of one set of measurements.
public struct Report: Codable, Equatable, Sendable {
    public var greys: [GreyResult] = []
    public var colours: [ColourResult] = []
    public var white: Vec3?
    public var warnings: [String] = []
    /// Brightness change of white from the start of the run to the end, %.
    public var drift: Double?
    /// HDR: the TV's highest measured brightness, when some greys are above it.
    public var peak: Double?

    public init() {}
}

public enum Analysis {
    /// The SDR colour patches are 75% signal; their target brightness is relative to white.
    static func colourLevel(hdr: Bool) -> Double { hdr ? 1.0 : pow(0.75, 2.4) }

    public static func analyse(_ meas: Measurements, hdr: Bool) -> Report {
        let seq = Patterns.sequence(hdr: hdr)
        var rep = Report()
        guard let W = meas["W0"] else {
            rep.warnings.append("No 100% white reading - can't analyse this round.")
            return rep
        }
        rep.white = W
        let Yw = W.y
        if let W1 = meas["W1"] {
            let drift = (W1.y / Yw - 1) * 100
            rep.drift = drift
            if abs(drift) > 3 {
                rep.warnings.append("Brightness drifted \(String(format: "%+.1f", drift))% during the run (TV not warmed up, "
                                    + "or auto-brightness/energy saving still on).")
            }
        }
        // missed patches, in the video's order
        for p in seq.patches {
            guard let m = meas.missed[p.id] else { continue }
            let took = m.readings.max() ?? 0
            rep.warnings.append("\(m.name): no reading. " + (took != 0
                ? "The camera's readings took up to \(String(format: "%.1f", took))s on it, against \(String(format: "%g", m.hold))s on screen."
                : "The camera didn't return anything while it was on screen."))
        }
        var prevY = 0.0
        for p in seq.patches where p.kind == .grey {
            guard let XYZ = meas[p.id], let level = p.level else { continue }
            let rgb = ColourMath.xyzToRGB709(XYZ)
            let Y = XYZ.y
            if Y < prevY * 0.95 && !hdr {
                rep.warnings.append("\(p.name) is darker than the step before it - timing may be off, "
                                    + "or the TV is clipping/tone-mapping.")
            }
            prevY = Y
            let tgt = hdr ? ColourMath.pqDecode(level) : Yw * pow(level, 2.4)
            let clipped = hdr && tgt > 200 && Y < 0.85 * tgt
            rep.greys.append(GreyResult(id: p.id, level: level, name: p.name, xyz: XYZ, Y: Y, targetY: tgt,
                                        bal: rgb / Y, rgb: rgb, de: ColourMath.whiteDE(XYZ), clipped: clipped))
        }
        let clip = rep.greys.filter { $0.clipped }
        if !clip.isEmpty {
            let peak = rep.greys.map { $0.Y }.max() ?? 0
            rep.peak = peak
            let names = clip.map { $0.name.components(separatedBy: " (")[0] }.joined(separator: ", ")
            rep.warnings.append("The TV tops out around \(String(format: "%.0f", peak)) cd/m² - steps above that "
                                + "(\(names)) are left out of the corrections.")
        }
        let tg = Patterns.colourTargets(hdr: hdr)
        // HDR: judge colours against the TV's own 100-nit white, so an overall HDR brightness
        // boost (reported separately as PQ tracking) doesn't swamp the colour scores.
        let whiteY = hdr ? (Yw > 0 ? Yw : 100.0) : Yw
        let lvl = colourLevel(hdr: hdr)
        let Wn = ColourMath.xyYToXYZ(ColourMath.d65, whiteY)
        for (n, _) in Patterns.colours {
            guard let XYZ = meas[n], let t = tg[n] else { continue }
            let expY = whiteY * t.Yrel * lvl
            let T = ColourMath.xyYToXYZ(t.xy, expY)
            let xy = ColourMath.xyzToXY(XYZ)
            let m = ColourMath.polar(xy), tp = ColourMath.polar(t.xy)
            rep.colours.append(ColourResult(
                name: n, xyz: XYZ, target: T, xy: xy, txy: t.xy,
                sat: (m.r / tp.r - 1) * 100, hue: ColourMath.wrap(m.angle - tp.angle), lum: (XYZ.y / expY - 1) * 100,
                de: ColourMath.de2000(ColourMath.xyzToLab(XYZ, white: Wn), ColourMath.xyzToLab(T, white: Wn))))
        }
        return rep
    }

    // MARK: summaries

    static func usableGreys(_ rep: Report, hdr: Bool) -> [GreyResult] {
        rep.greys.filter { (hdr || $0.level >= 0.099) && !$0.clipped }
    }

    /// Average greyscale dE (SDR: 10% and up). NaN if nothing to average.
    public static func greyAverage(_ rep: Report, hdr: Bool) -> Double {
        let g = usableGreys(rep, hdr: hdr).map { $0.de }
        return g.isEmpty ? .nan : g.reduce(0, +) / Double(g.count)
    }

    public static func greyMax(_ rep: Report, hdr: Bool) -> Double {
        usableGreys(rep, hdr: hdr).map { $0.de }.max() ?? .nan
    }

    public static func colourAverage(_ rep: Report) -> Double {
        let c = rep.colours.map { $0.de }
        return c.isEmpty ? 0 : c.reduce(0, +) / Double(c.count)
    }

    /// SDR: average gamma between 10% and 90%.
    public static func gamma(_ rep: Report) -> Double? {
        guard let W = rep.white else { return nil }
        let gs = rep.greys.filter { $0.level >= 0.1 && $0.level <= 0.9 && $0.Y > 0 }
            .map { log($0.Y / W.y) / log($0.level) }
        return gs.isEmpty ? nil : gs.reduce(0, +) / Double(gs.count)
    }

    /// HDR: how far brightness is from the PQ target below 300 nits, % on average.
    public static func pqTracking(_ rep: Report) -> Double? {
        let trk = rep.greys.filter { $0.targetY < 300 && $0.targetY > 0 }.map { $0.Y / $0.targetY - 1 }
        return trk.isEmpty ? nil : 100 * trk.reduce(0, +) / Double(trk.count)
    }

    /// "Excellent", "Very good"... for a dE.
    public static func scoreWord(_ de: Double?) -> String {
        guard let de, !de.isNaN else { return "" }
        return de < 1 ? "Excellent" : de < 2 ? "Very good" : de < 3 ? "Good" : de < 5 ? "Noticeably off" : "Clearly off"
    }

    /// Which neighbouring colour a hue error leans toward.
    public static func hueToward(_ name: String, hue: Double, hdr: Bool) -> String? {
        guard abs(hue) >= 1, let i = Patterns.hueOrder.firstIndex(of: name) else { return nil }
        let tg = Patterns.colourTargets(hdr: hdr)
        guard let me = tg[name] else { return nil }
        let at = ColourMath.polar(me.xy).angle
        var toward: String?
        for cand in [Patterns.hueOrder[(i + 1) % 6], Patterns.hueOrder[(i + 5) % 6]] {
            guard let c = tg[cand] else { continue }
            if (ColourMath.wrap(ColourMath.polar(c.xy).angle - at) > 0) == (hue > 0) {
                toward = cand
            }
        }
        return toward
    }
}
