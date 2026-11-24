import Foundation

// Guided calibration: the TV plays one pattern at a time (a 10-minute file from the USB stick), the
// camera reads continuously, and the app says in plain words which control to move and which way.

/// The patches each guided step uses.
public struct GuidePlan: Sendable {
    /// Bright end (Gain) and dark end (Offset) patches.
    public var gain: String
    public var offset: String
    /// Greys for the bright / middle / dark check.
    public var check: [String]
    /// Greys of the before/after quick check (the colours are added to them).
    public var quick: [String]
    /// Greys for multipoint fine-tuning.
    public var fine: [String]

    public static func forHDR(_ hdr: Bool) -> GuidePlan {
        hdr ? GuidePlan(gain: "G65", offset: "G35", check: ["G65", "G50", "G35"],
                        quick: Array(Patterns.quickIDs(hdr: true).prefix(6)), fine: ["G40", "G50", "G60", "G70"])
            : GuidePlan(gain: "W0", offset: "G30", check: ["W0", "G50", "G30"],
                        quick: Array(Patterns.quickIDs(hdr: false).prefix(6)), fine: ["G20", "G40", "G60", "G80"])
    }

    /// The before/after check: the quick greys plus the six colours.
    public var quickWithColours: [String] { quick + Patterns.colourIDs }
}

/// A colour the TV can't reach with its current primaries: how close it can get.
public struct OutOfReach: Sendable, Equatable {
    /// Sliders that would have to go below zero ("R", "G", "B").
    public var channels: [String]
    public var bestDE: Double
}

/// Plain-language state of one pattern: how far off it is, and what to do about it.
public struct LiveReading: Sendable {
    public var id: String
    public var name: String
    public var kind: PatchKind
    public var Y: Double
    public var xy: Point2
    public var uv: Point2
    public var d65uv: Point2

    // greys and whites
    /// Red and blue relative to green, minus 1 (0 = on target).
    public var qR: Double?
    public var qB: Double?
    public var de: Double?
    public var ok = false

    // colours
    public var tuv: Point2?
    public var hue: Double?
    public var sat: Double?
    public var lum: Double?
    public var toward: String?
    /// R/G/B slider menus: which slider moves this colour toward its target (share of the main slider).
    public var rgbHint: Vec3?
    /// The hint uses ideal primaries (the Before check didn't measure all three).
    public var rgbApprox = false
    public var outOfReach: OutOfReach?

    // filled in by the live session
    public var steady = false
    public var averaged = 0
    public var need = 3
    public var jitter = 0.0
    public var latestY = 0.0
    public var mismatch: String?
}

public enum Guided {
    public static func liveReading(patch p: Patch, xyz XYZ: Vec3, hdr: Bool, whiteY: Double?, basis: [Vec3]?) -> LiveReading {
        let xy = ColourMath.xyzToXY(XYZ)
        var out = LiveReading(id: p.id, name: p.name, kind: p.kind, Y: XYZ.y, xy: xy, uv: ColourMath.upvp(xy),
                              d65uv: ColourMath.upvp(ColourMath.d65))
        if p.kind == .white || p.kind == .grey {
            let rgb = ColourMath.xyzToRGB709(XYZ)
            let g = rgb.y > 0 ? rgb.y : 1e-9
            let qR = rgb.x / g - 1, qB = rgb.z / g - 1
            out.qR = qR
            out.qB = qB
            out.de = ColourMath.whiteDE(XYZ)
            out.ok = abs(qR) <= 0.012 && abs(qB) <= 0.012
            return out
        }
        let targets = Patterns.colourTargets(hdr: hdr)
        guard let tg = targets[p.id] else { return out }
        let lvl = Analysis.colourLevel(hdr: hdr)
        let wY: Double? = (whiteY ?? 0) != 0 ? whiteY : (hdr ? 100.0 : nil)
        let m = ColourMath.polar(xy), t = ColourMath.polar(tg.xy)
        let hue = ColourMath.wrap(m.angle - t.angle)
        let sat = (m.r / t.r - 1) * 100
        out.tuv = ColourMath.upvp(tg.xy)
        out.hue = hue
        out.sat = sat
        if let wY {
            let expY = wY * tg.Yrel * lvl
            let Wn = ColourMath.xyYToXYZ(ColourMath.d65, wY)
            out.lum = (XYZ.y / expY - 1) * 100
            out.de = ColourMath.de2000(ColourMath.xyzToLab(XYZ, white: Wn),
                                       ColourMath.xyzToLab(ColourMath.xyYToXYZ(tg.xy, expY), white: Wn))
        }
        if let i = Patterns.hueOrder.firstIndex(of: p.id) {
            for cand in [Patterns.hueOrder[(i + 1) % 6], Patterns.hueOrder[(i + 5) % 6]] {
                if let c = targets[cand], (ColourMath.wrap(ColourMath.polar(c.xy).angle - t.angle) > 0) == (hue > 0) {
                    out.toward = cand
                }
            }
        }
        if let de = out.de {
            out.ok = de <= 2.0
        } else {
            out.ok = abs(hue) < 2 && abs(sat) < 3
        }
        var basis = basis
        if let wY, basis == nil || basis!.isEmpty {
            // no measured primaries: use the standard ones - the direction of each slider hint is
            // still right, only the size is approximate
            basis = ["RED", "GREEN", "BLUE"].map { ColourMath.xyYToXYZ(targets[$0]!.xy, wY * targets[$0]!.Yrel * lvl) }
            out.rgbApprox = true
        }
        if let basis, basis.count == 3, let wY {
            let M: Mat3 = (0..<3).map { r in (0..<3).map { c in basis[c][r] } }
            let targetXYZ = ColourMath.xyYToXYZ(tg.xy, wY * tg.Yrel * lvl)
            let cur = ColourMath.solve3(M, XYZ)
            let tgt = ColourMath.solve3(M, targetXYZ)
            let peak = max(tgt.x, tgt.y, tgt.z)
            // each change as a share of the colour's main slider, so a tiny trace of green on red
            // doesn't outrank red being 25% too bright
            let hint = (tgt - cur) / max(peak, 1e-6)
            if hint.x.isFinite && hint.y.isFinite && hint.z.isFinite {
                out.rgbHint = hint
                // A slider can't go below zero. If the target needs a negative amount of a primary, it
                // lies outside what this TV's red, green and blue can mix: say so, and how close it can get.
                let short = (0..<3).filter { tgt[$0] < -0.02 * peak }
                if !short.isEmpty {
                    let reach = Vec3(max(0, tgt.x), max(0, tgt.y), max(0, tgt.z))
                    let XYZr = ColourMath.matvec(M, reach)
                    let Wn = ColourMath.xyYToXYZ(ColourMath.d65, wY)
                    let best = ColourMath.de2000(ColourMath.xyzToLab(XYZr, white: Wn), ColourMath.xyzToLab(targetXYZ, white: Wn))
                    out.outOfReach = OutOfReach(channels: short.map { ["R", "G", "B"][$0] }, bestDE: pyRound(best, 1))
                }
            }
        }
        return out
    }

    /// Averages the readings since the last real change on the TV.
    ///
    /// Some TVs (Samsung Micro RGB, many mini-LEDs) wobble from reading to reading. Averaging hides the
    /// wobble, but a long average would also hide the effect of a click on the remote. So: judge the
    /// normal wobble from reading-to-reading steps, find the point where the average clearly shifted (a
    /// setting change) and average only the readings after it.
    /// Returns the XYZ, how many readings were averaged, and the wobble as a fraction.
    public static func settledAverage(_ rs: [Vec3]) -> (xyz: Vec3, count: Int, wobble: Double) {
        let n = rs.count
        guard n > 0 else { return (Vec3(0, 0, 0), 0, 0) }
        let cs: [(Double, Double)] = rs.map { ($0.x / max($0.y, 1e-6), $0.z / max($0.y, 1e-6)) }
        func comp(_ c: (Double, Double), _ k: Int) -> Double { k == 0 ? c.0 : c.1 }
        var sig: [Double] = []
        for k in 0..<2 {
            let d = zip(cs, cs.dropFirst()).map { abs(comp($0.0, k) - comp($0.1, k)) }.sorted()
            var scale = cs.map { abs(comp($0, k)) }.max() ?? 0
            if scale == 0 { scale = 1.0 }
            sig.append(max(d.isEmpty ? 0.0 : d[d.count / 2] / 0.954, 0.0015 * scale, 1e-6))
        }
        var start = 0
        var best = 0.0
        for m in stride(from: 2, to: n - 1, by: 1) {          // last m readings vs the ones before them
            let old = cs[0..<(n - m)], new = cs[(n - m)..<n]
            var t = -Double.infinity
            for k in 0..<2 {
                let meanNew = new.map { comp($0, k) }.reduce(0, +) / Double(m)
                let meanOld = old.map { comp($0, k) }.reduce(0, +) / Double(old.count)
                t = max(t, abs(meanNew - meanOld) / (sig[k] * (1 / Double(m) + 1 / Double(old.count)).squareRoot()))
            }
            if t > 4.0 && t > best {
                best = t
                start = n - m
            }
        }
        if n - start == 1 {
            start = n - 1
        } else if n >= 2 && start == 0 {
            // a big jump on the very newest reading: show it straight away
            let jump = (0..<2).map { abs(comp(cs[n - 1], $0) - comp(cs[n - 2], $0)) / (sig[$0] * 1.414) }.max() ?? 0
            if jump > 6 { start = n - 1 }
        }
        let tail = Array(max(start, n - 10)..<n)
        let m = Double(tail.count)
        let cx = tail.map { cs[$0].0 }.reduce(0, +) / m
        let cz = tail.map { cs[$0].1 }.reduce(0, +) / m
        let Y = tail.map { rs[$0].y }.reduce(0, +) / m
        let wobble = (0..<2).map { sig[$0] / max(abs(comp(cs[n - 1], $0)), 0.05) }.max() ?? 0
        return (Vec3(cx * Y, Y, cz * Y), tail.count, pyRound(wobble, 4))
    }

    /// How many averaged readings it takes to call a reading steady, for how jumpy this TV is.
    public static func readingsNeeded(jitter: Double) -> Int {
        jitter < 0.004 ? 3 : jitter < 0.01 ? 5 : 8
    }

    /// Does the reading look like the pattern we asked for? Catches the wrong file playing.
    public static func mismatch(patch p: Patch, xyz XYZ: Vec3, hdr: Bool, whiteY: Double?) -> String? {
        if XYZ.y <= 0 { return "The camera sees black." }
        let r = ColourMath.polar(ColourMath.xyzToXY(XYZ)).r
        let neutral = p.kind == .white || p.kind == .grey
        if neutral && r > 0.05 { return "The camera is seeing a colour, not a grey." }
        if p.kind == .colour && r < 0.03 { return "The camera is seeing a grey, not a colour." }
        if let wY = whiteY, wY != 0, neutral, let level = p.level {
            let exp = hdr ? ColourMath.pqDecode(level) * wY / 100.0 : wY * pow(level, 2.4)
            if exp > 0 && !(0.4 < XYZ.y / exp && XYZ.y / exp < 2.5) {
                return "The brightness doesn't match this pattern."
            }
        }
        return nil
    }

    /// Everything the live view needs from the latest readings of one pattern.
    public static func evaluate(patch: Patch, readings: [Vec3], hdr: Bool, whiteY: Double?, basis: [Vec3]?) -> LiveReading? {
        guard let latest = readings.last else { return nil }
        let avg = settledAverage(readings)
        var out = liveReading(patch: patch, xyz: avg.xyz, hdr: hdr, whiteY: whiteY, basis: basis)
        out.averaged = avg.count
        out.jitter = avg.wobble
        out.need = readingsNeeded(jitter: avg.wobble)
        out.steady = out.ok && avg.count >= out.need
        out.latestY = latest.y
        out.mismatch = mismatch(patch: patch, xyz: avg.xyz, hdr: hdr, whiteY: whiteY)
        if out.mismatch != nil {
            out.ok = false
            out.steady = false
        }
        return out
    }
}

// MARK: - The free-running live meter

public struct LiveAnalysis: Sendable {
    public enum Kind: Sendable { case white, colour }
    public var kind: Kind
    public var Y: Double
    public var xy: Point2
    public var uv: Point2
    public var d65uv: Point2
    // white
    public var bal: Vec3?
    public var de: Double?
    public var advice: String?
    // colour
    public var name: String?
    public var sat: Double?
    public var hue: Double?
    public var tuv: Point2?

    /// Reads whatever is on screen: a white (balance + advice) or the nearest target colour.
    public static func of(_ XYZ: Vec3, hdr: Bool) -> LiveAnalysis {
        let xy = ColourMath.xyzToXY(XYZ)
        let pol = ColourMath.polar(xy)
        let uv = ColourMath.upvp(xy)
        var out = LiveAnalysis(kind: .white, Y: XYZ.y, xy: xy, uv: uv, d65uv: ColourMath.upvp(ColourMath.d65))
        if pol.r < 0.03 {
            let rgb = ColourMath.xyzToRGB709(XYZ)
            let bal = XYZ.y > 0 ? rgb / XYZ.y : Vec3(1, 1, 1)
            out.bal = bal
            out.de = ColourMath.whiteDE(XYZ)
            let vals = [bal.x, bal.y, bal.z]
            let mx = vals.indices.max { vals[$0] < vals[$1] } ?? 0
            let mn = vals.indices.min { vals[$0] < vals[$1] } ?? 0
            let names = ["Red", "Green", "Blue"]
            out.advice = vals[mx] - vals[mn] <= 0.01 ? "White is on target."
                : "\(names[mx]) is high and \(names[mn]) is low: lower \(names[mx]) or raise \(names[mn])."
        } else {
            let tg = Patterns.colourTargets(hdr: hdr)
            let name = Patterns.colourIDs.min { a, b in
                let ua = ColourMath.upvp(tg[a]!.xy), ub = ColourMath.upvp(tg[b]!.xy)
                return hypot(uv.x - ua.x, uv.y - ua.y) < hypot(uv.x - ub.x, uv.y - ub.y)
            } ?? "RED"
            let t = ColourMath.polar(tg[name]!.xy)
            out.kind = .colour
            out.name = name
            out.sat = (pol.r / t.r - 1) * 100
            out.hue = ColourMath.wrap(pol.angle - t.angle)
            out.tuv = ColourMath.upvp(tg[name]!.xy)
        }
        return out
    }
}
