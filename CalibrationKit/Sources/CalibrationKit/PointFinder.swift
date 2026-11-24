import Foundation

// Point finder: TVs label their multipoint (10/20-point) controls on their own scale, which often
// doesn't match the video's grey levels (an LG C5 in HDR: the 30% control moves the 40% grey).
// The finder measures a ladder of finely spaced greys, the user nudges four controls by +10, it
// measures again, and the bumps in the difference show where each control really acts.

/// Where one TV control acts, as a grey level of the video (its own % scale).
public struct FinderPoint: Codable, Equatable, Sendable {
    /// The TV control's own label, e.g. 40 for the "40%" control.
    public var point: Int
    /// "R", "B", or "?" for a point the user set by hand.
    public var channel: String
    public var level: Double
    /// Size of the bump, %.
    public var size: Double

    public init(point: Int, channel: String, level: Double, size: Double) {
        self.point = point
        self.channel = channel
        self.level = level
        self.size = size
    }
}

public enum PointFinder {
    public static let nudge = 10
    /// Far apart, alternating channels.
    public static let nudges: [(channel: String, point: Int)] = [("R", 20), ("B", 40), ("R", 60), ("B", 80)]

    /// The menu's step: 5 for 20-point menus, 10 for 10/11-point menus.
    public static func grid(points: Int) -> Int { points == 20 || points == 0 ? 5 : 10 }

    struct QRow {
        let level: Double
        let r: Double
        let b: Double
        let Y: Double
    }

    static func q(_ meas: Measurements, hdr: Bool) -> [QRow] {
        Patterns.finderSequence(hdr: hdr).patches.compactMap { p in
            guard p.kind == .grey, let XYZ = meas[p.id], XYZ.y > 0, let level = p.level else { return nil }
            let rgb = ColourMath.xyzToRGB709(XYZ)
            guard rgb.y > 0 else { return nil }
            return QRow(level: level * 100, r: rgb.x / rgb.y, b: rgb.z / rgb.y, Y: XYZ.y)
        }
    }

    /// Where each nudged control acts.
    ///
    /// The two passes are minutes apart and some TVs drift in that time, which shifts every grey the
    /// same way: each channel's change is measured against its own typical (median) change, and only
    /// bumps that stand out from the noise count. A higher control always acts on a brighter grey,
    /// which settles which bump belongs to which control.
    public static func map(base: Measurements, nudged: Measurements, hdr: Bool) -> [FinderPoint] {
        let a = q(base, hdr: hdr), b = q(nudged, hdr: hdr)
        var bl: [Double: QRow] = [:]
        for r in a { bl[pyRound(r.level, 2)] = r }
        struct Row { let level, dR, dB, Y, baseY: Double }
        var rows: [Row] = b.compactMap { r in
            guard let o = bl[pyRound(r.level, 2)] else { return nil }
            return Row(level: r.level, dR: r.r / o.r - 1, dB: r.b / o.b - 1, Y: r.Y, baseY: o.Y)
        }
        // the TV can't get brighter than its peak: greys above it all look the same, so drop them
        let top = rows.map { $0.baseY }.max() ?? 0
        rows = rows.filter { $0.baseY < 0.97 * top }
        guard rows.count >= 6 else { return [] }
        let lv = rows.map { $0.level }
        // each channel against its own typical change (drift), then the two added: a nudge shows up as a
        // bump at its grey whichever channel moved
        var dd: [[Double]] = []
        for ch in 0..<2 {
            let d = rows.map { ch == 0 ? $0.dR : $0.dB }
            let med = d.sorted()[d.count / 2]
            dd.append(d.map { $0 - med })
        }
        let d = zip(dd[0], dd[1]).map { max(0, $0) + max(0, $1) }
        // typical noise: the smaller of the two channel changes at each grey, x2 for the two channels added
        var mad = 2 * 1.48 * zip(dd[0], dd[1]).map { min(abs($0), abs($1)) }.sorted()[d.count / 2]
        if mad == 0 { mad = 1e-4 }
        let need = rows.map { max(0.012, 4 * mad * (1 + 4.0 / max($0.baseY, 0.5)).squareRoot()) }   // dark greys: noisier

        struct Peak { let level, size, score: Double; let ch: String; let lean: Double }
        var peaks: [Peak] = []
        if d.count >= 3 {
            for i in 1..<(d.count - 1) where d[i] >= need[i] && d[i] >= d[i - 1] && d[i] >= d[i + 1] {
                // a bump on the first or last measured grey may sit beyond it: can't place it, so skipped
                var L = lv[i]
                let den = d[i - 1] - 2 * d[i] + d[i + 1]
                if den < 0 {
                    L += clamp(0.5 * (d[i - 1] - d[i + 1]) / den, -0.5, 0.5) * (lv[i + 1] - lv[i])
                }
                let r = max(0, dd[0][i]), bb = max(0, dd[1][i])
                let sum = r + bb
                peaks.append(Peak(level: pyRound(L, 1), size: pyRound(d[i] * 100, 1), score: min(d[i] / need[i], 4.0),
                                  ch: r >= bb ? "R" : "B", lean: (r - bb) / (sum != 0 ? sum : 1)))   // +1 red only, -1 blue only
            }
        }
        // bumps go to controls in order (a brighter control acts on a brighter grey); among the
        // orderings, prefer more bumps explained, then the smaller shift from the labels
        let ctrls = nudges.sorted { $0.point < $1.point }
        var bestScore = -1e9
        var bestPairs: [FinderPoint] = []
        var acc: [FinderPoint] = []
        func walk(_ k: Int, _ pi: Int, _ score: Double) {
            if k == ctrls.count {
                if score > bestScore {
                    bestScore = score
                    bestPairs = acc
                }
                return
            }
            let C = ctrls[k].channel, P = ctrls[k].point
            walk(k + 1, pi, score)                                     // no bump for this control
            if pi < peaks.count {
                for qi in pi..<peaks.count {
                    let pk = peaks[qi]
                    acc.append(FinderPoint(point: P, channel: pk.ch, level: pk.level, size: pk.size))
                    // which channel moved: a clear red-only bump belongs to a red nudge
                    let match = pk.lean * (C == "R" ? 1 : -1)
                    walk(k + 1, qi + 1, score + 10 + pk.score - abs(pk.level - Double(P)) / 10 + 3 * match)
                    acc.removeLast()
                }
            }
        }
        walk(0, 0, 0)
        return bestPairs
    }

    /// Which TV control to use for a grey at level L (the video's %), from the measured points.
    public static func pointForLevel(_ pairs: [FinderPoint], level L: Double, grid: Int) -> Int? {
        let pts = pairs.map { (l: $0.level, p: Double($0.point)) }.sorted { ($0.l, $0.p) < ($1.l, $1.p) }
        guard let first = pts.first else { return nil }
        var P: Double
        if pts.count == 1 {
            P = first.p + (L - first.l)
        } else {
            P = first.p
            for j in 0..<(pts.count - 1) {
                let (l0, p0) = pts[j], (l1, p1) = pts[j + 1]
                if L <= l1 || j + 1 == pts.count - 1 {
                    P = p0 + (p1 - p0) * (L - l0) / ((l1 - l0) != 0 ? (l1 - l0) : 1)
                    break
                }
            }
            if L < first.l {
                let (l0, p0) = pts[0], (l1, p1) = pts[1]
                P = p0 + (p1 - p0) * (L - l0) / ((l1 - l0) != 0 ? (l1 - l0) : 1)
            }
        }
        let g = Double(grid)
        guard P.isFinite else { return nil }
        return Int(clamp(g * pyRound(P / g), g, 100))
    }

    /// For each grey of the pattern pack: the TV control to use. Controls the user found by hand
    /// count as measured points too, and override that grey outright.
    public static func suggestions(map: [FinderPoint], overrides: [String: Int], hdr: Bool, grid: Int) -> [String: Int] {
        let levels = Dictionary(Patterns.sequence(hdr: hdr).patches
            .filter { $0.kind == .grey || $0.kind == .white }
            .map { ($0.id, $0.percent) }, uniquingKeysWith: { x, _ in x })
        if map.isEmpty { return overrides }
        let mine: [FinderPoint] = overrides.keys.sorted().compactMap { pid in
            guard let L = levels[pid], let P = overrides[pid] else { return nil }
            return FinderPoint(point: P, channel: "?", level: L, size: 0)
        }
        // the user's findings win: drop measured points that disagree with them
        func agrees(_ q: FinderPoint) -> Bool {
            mine.allSatisfy { m in
                q.point != m.point && abs(q.level - m.level) > 4 && Double(q.point - m.point) * (q.level - m.level) > 0
            }
        }
        let pairs = (map.filter(agrees) + mine).sorted { $0.level < $1.level }
        var out: [String: Int] = [:]
        for (pid, L) in levels {
            out[pid] = pointForLevel(pairs, level: L, grid: grid)
        }
        for (pid, P) in overrides where levels[pid] != nil {
            out[pid] = P
        }
        return out
    }
}
