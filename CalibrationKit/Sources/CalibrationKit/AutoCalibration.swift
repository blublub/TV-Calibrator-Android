import Foundation

// Automatic rounds: measure the whole video, then prescribe exact menu changes.
//
// Green is the anchor: only Red and Blue move, measured RELATIVE to green (R/G and B/G). That keeps
// working even if the TV quietly rescales overall brightness (power limiting, peak boost).
//
// Learning takes two short rounds:
//   after round 1:  Red and Blue GAIN  -10   (down, so the TV can't be at a ceiling)
//   after round 2:  gains back, Red and Blue OFFSET +10
// From round 3 on: corrections, capped per control per round, and the model rescales itself every
// round from what actually happened.
//
// Change keys are strings shared with the Mac app's history files:
//   "gain:R", "offset:B", "p20:R:40" (multipoint), "cms:YELLOW:B" (R/G/B sliders), "hsl:RED:hue".

// MARK: - Types

/// Which colour menu the TV has.
public enum ColourMenu: String, Codable, CaseIterable, Sendable {
    /// Red, Green and Blue sliders for each colour (Samsung's Color Space > Custom).
    case rgbSliders = "samsung"
    /// Hue, Saturation and Brightness per colour (LG, Sony, TCL...).
    case hsl
    case off
}

public enum RoundKind: String, Codable, Sendable {
    case probeGain = "probe_gain"
    case probeOffset = "probe_offset"
    case correct
    case undo
}

public enum Stage: String, Codable, Sendable {
    case twoPoint = "2pt"
    case multiPoint = "20pt"
}

public enum Banner: String, Codable, Sendable {
    case learn, undo, correct
}

/// What was used to measure: rounds are only comparable with the same source, meter and phone.
public struct RoundSetup: Codable, Equatable, Sendable {
    public var source: String
    public var meter: String
    public var phone: String?
    public init(source: String = "video", meter: String = "iphone", phone: String?) {
        self.source = source
        self.meter = meter
        self.phone = phone
    }
}

public struct AutoRound: Codable, Sendable {
    public var time: String
    public var meas: Measurements
    /// The changes the user confirmed they made after this round (nil: not recorded).
    public var applied: [String: Int]?
    public var kind: RoundKind?
    public var setup: RoundSetup?
    public var stage: Stage?

    public init(time: String, meas: Measurements, setup: RoundSetup?) {
        self.time = time
        self.meas = meas
        self.setup = setup
    }
}

/// What the engine has learned about how this TV's controls respond.
public struct Sensitivity: Codable, Sendable {
    /// % change of R/G (or B/G) per Gain click, per grey id.
    public var relG: [String: [String: Double]]?
    /// Same for Offset.
    public var relO: [String: [String: Double]]?
    public var goScale: [String: Double]?
    /// Multipoint: % change per click on its own grey.
    public var rel20: [String: Double]?
    public var kScale: Double?
    /// R/G/B colour sliders: change in a colour's primary amount per click.
    public var k: Double?
    public var learned = false
    /// "R:40" -> [total clicks, total response]: spots multipoint controls that act elsewhere.
    public var p20Track: [String: [Double]] = [:]
    public var p20Dead: [String] = []
    public var p20Learned = false
    /// Hue/sat/lum colour menus: degrees or % per click (sign learned too).
    public var hslK: [String: Double]?
    public var hslLearned: [String] = []

    enum CodingKeys: String, CodingKey {
        case relG = "rel_g", relO = "rel_o", goScale = "go_scale", rel20, kScale = "k_scale", k, learned
        case p20Track = "p20_track", p20Dead = "p20_dead", p20Learned = "p20_learned", hslK = "hsl_k"
        case hslLearned = "hsl_learned"
    }

    public init() {}

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        relG = try c.decodeIfPresent([String: [String: Double]].self, forKey: .relG)
        relO = try c.decodeIfPresent([String: [String: Double]].self, forKey: .relO)
        goScale = try c.decodeIfPresent([String: Double].self, forKey: .goScale)
        rel20 = try c.decodeIfPresent([String: Double].self, forKey: .rel20)
        kScale = try c.decodeIfPresent(Double.self, forKey: .kScale)
        k = try c.decodeIfPresent(Double.self, forKey: .k)
        learned = try c.decodeIfPresent(Bool.self, forKey: .learned) ?? false
        p20Track = try c.decodeIfPresent([String: [Double]].self, forKey: .p20Track) ?? [:]
        p20Dead = try c.decodeIfPresent([String].self, forKey: .p20Dead) ?? []
        p20Learned = try c.decodeIfPresent(Bool.self, forKey: .p20Learned) ?? false
        hslK = try c.decodeIfPresent([String: Double].self, forKey: .hslK)
        hslLearned = try c.decodeIfPresent([String].self, forKey: .hslLearned) ?? []
    }
}

/// The changes waiting for the user to make (and confirm) after a round.
public struct PendingPlan: Codable, Sendable {
    public var changes: [String: Int]
    public var kind: RoundKind?
    public var title: String
    public var done: Bool
    public var message: String
    public var banner: Banner
    public var notes: [String]
}

/// One TV's automatic-round history for SDR or HDR.
public struct AutoState: Codable, Sendable {
    public var version = AutoCalibration.stateVersion
    public var rounds: [AutoRound] = []
    public var sens = Sensitivity()
    public var notes: [String] = []
    public var pending: PendingPlan?

    public init() {}

    enum CodingKeys: String, CodingKey { case version, rounds, sens, notes, pending }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        version = try c.decodeIfPresent(Int.self, forKey: .version) ?? 1
        rounds = try c.decodeIfPresent([AutoRound].self, forKey: .rounds) ?? []
        sens = try c.decodeIfPresent(Sensitivity.self, forKey: .sens) ?? Sensitivity()
        notes = try c.decodeIfPresent([String].self, forKey: .notes) ?? []
        pending = try c.decodeIfPresent(PendingPlan.self, forKey: .pending)
    }
}

/// The result of planning after a round.
public struct Plan: Sendable {
    public var report: Report
    public var changes: [String: Int] = [:]
    public var kind: RoundKind?
    public var title = ""
    public var done = false
    public var message = ""
    public var banner: Banner = .correct
    public var notes: [String] = []
    /// The round wasn't kept (something other than the calibration controls changed).
    public var discard = false
    public var stage: Stage?

    public var pending: PendingPlan {
        PendingPlan(changes: changes, kind: kind, title: title, done: discard ? false : done, message: message,
                    banner: banner, notes: notes)
    }
}

// MARK: - Engine

public enum AutoCalibration {
    public static let stateVersion = 3
    static let channels = ["R", "B"]          // green stays put
    static let twoMax = 8                     // max clicks per 2-point control per round
    static let p20Max = 4                     // max clicks per multipoint control per round (x2 once learned)
    static let p20Guess = 0.006               // first guess: one multipoint click ~ 0.6% on that step
    static let relMin = 0.002, relMax = 0.04  // sane range for "% change per click" (gain / offset)
    static let hslGuess: [String: Double] = ["hue": 1.0, "sat": 1.0, "lum": 1.0]

    /// Click counts from a model value: Python's int(round(x)), but never crashing on NaN/infinity.
    static func clicks(_ x: Double) -> Int {
        guard x.isFinite else { return 0 }
        return Int(clamp(pyRound(x), -1_000_000, 1_000_000))
    }

    public static func gainProbe(_ cms: ColourMenu) -> [String: Int] {
        var ch = ["gain:R": -10, "gain:B": -10]
        if cms == .rgbSliders { ch["cms:YELLOW:B"] = 10 }
        return ch
    }

    public static func offsetProbe() -> [String: Int] {
        ["gain:R": 10, "gain:B": 10, "offset:R": 10, "offset:B": 10]
    }

    static func roundKind(_ r: AutoRound) -> RoundKind? {
        (r.applied?.isEmpty == false) ? r.kind : nil
    }

    // MARK: greyscale ratios

    struct QRow {
        let id: String
        let level: Double
        let r: Double
        let b: Double
        func v(_ C: String) -> Double { C == "R" ? r : b }
    }

    /// Per usable grey: R/G and B/G, in grey order.
    static func qvals(_ rep: Report) -> [QRow] {
        rep.greys.compactMap { g in
            guard !g.clipped, g.bal.y > 0 else { return nil }
            return QRow(id: g.id, level: g.level, r: g.bal.x / g.bal.y, b: g.bal.z / g.bal.y)
        }
    }

    /// How much R/G and B/G changed from one report to the next (as a fraction), per grey.
    static func qchange(_ a: Report, _ b: Report) -> [QRow] {
        let qb = Dictionary(qvals(b).map { ($0.id, $0) }, uniquingKeysWith: { x, _ in x })
        return qvals(a).compactMap { qa in
            guard let q = qb[qa.id] else { return nil }
            return QRow(id: qa.id, level: qa.level, r: q.r / qa.r - 1, b: q.b / qa.b - 1)
        }
    }

    /// Median of each value and its neighbours (by level), clamped.
    static func smooth(_ d: [(id: String, level: Double, value: Double)], lo: Double, hi: Double) -> [String: Double] {
        let sorted = d.enumerated().sorted { ($0.element.level, $0.offset) < ($1.element.level, $1.offset) }.map { $0.element }
        let vals = sorted.map { $0.value }
        var out: [String: Double] = [:]
        for (i, e) in sorted.enumerated() {
            let win = Array(vals[max(0, i - 1)..<min(vals.count, i + 2)]).sorted()
            out[e.id] = clamp(win[win.count / 2], lo, hi)
        }
        return out
    }

    // MARK: colours

    /// The measured red, green and blue as matrix columns; a primary that couldn't be measured is
    /// replaced by its ideal target (so one missing reading doesn't switch off all colour corrections).
    public static func colourBasis(_ meas: Measurements, hdr: Bool) -> Mat3? {
        guard let W = meas["W0"] else { return nil }
        let tg = Patterns.colourTargets(hdr: hdr)
        let lvl = Analysis.colourLevel(hdr: hdr)
        let P: [Vec3] = ["RED", "GREEN", "BLUE"].map { c in
            meas[c] ?? ColourMath.xyYToXYZ(tg[c]!.xy, W.y * tg[c]!.Yrel * lvl)
        }
        return (0..<3).map { r in (0..<3).map { c in P[c][r] } }
    }

    /// A colour as amounts of the measured red, green and blue.
    static func colourCoefs(_ meas: Measurements, _ name: String, hdr: Bool) -> Vec3? {
        guard let M = colourBasis(meas, hdr: hdr), let C = meas[name] else { return nil }
        return ColourMath.solve3(M, C)
    }

    // MARK: learning

    static func learn(_ st: inout AutoState, hdr: Bool) {
        let rounds = st.rounds
        guard rounds.count >= 2 else { return }
        let prev = rounds[rounds.count - 2], cur = rounds[rounds.count - 1]
        guard let kind = roundKind(prev), let applied = prev.applied else { return }
        let A = Analysis.analyse(prev.meas, hdr: hdr), B = Analysis.analyse(cur.meas, hdr: hdr)
        let dq = qchange(A, B)
        var sens = st.sens
        defer { st.sens = sens }

        switch kind {
        case .probeGain:
            var relG: [String: [String: Double]] = [:]
            for C in channels {
                let g = Double(applied["gain:\(C)"] ?? 0)
                let d = dq.map { (id: $0.id, level: $0.level, value: $0.v(C) / g) }
                let top = d.filter { $0.level >= 0.7 }.map { $0.value }
                if !top.isEmpty && top.reduce(0, +) / Double(top.count) < relMin {
                    st.notes.append("\(C)-Gain barely changed the picture during learning - using a safe default.")
                }
                relG[C] = smooth(d, lo: relMin * 0.5, hi: relMax)
            }
            sens.relG = relG
            if let yb = applied["cms:YELLOW:B"], let ca = colourCoefs(prev.meas, "YELLOW", hdr: hdr),
               let cb = colourCoefs(cur.meas, "YELLOW", hdr: hdr) {
                sens.k = clamp((cb.z - ca.z) / Double(yb), 0.002, 0.03)
            }
            return
        case .probeOffset:
            let base = rounds.count >= 3 ? rounds[rounds.count - 3] : prev
            let dq0 = qchange(Analysis.analyse(base.meas, hdr: hdr), B)
            var relO: [String: [String: Double]] = [:]
            for C in channels {
                relO[C] = smooth(dq0.map { (id: $0.id, level: $0.level, value: $0.v(C) / 10) }, lo: 0, hi: relMax)
            }
            sens.relO = relO
            sens.goScale = ["R": 1.0, "B": 1.0]
            sens.rel20 = ["R": p20Guess, "B": p20Guess]
            sens.kScale = 1.0
            sens.learned = true
            return
        case .correct, .undo:
            break
        }

        guard sens.learned, let relG = sens.relG, let relO = sens.relO,
              var goScale = sens.goScale, var rel20 = sens.rel20 else { return }
        for C in channels {
            var num = 0.0, den = 0.0, n20 = 0.0, d20 = 0.0
            let g = Double(applied["gain:\(C)"] ?? 0), o = Double(applied["offset:\(C)"] ?? 0)
            for row in dq {
                let lvl = clicks(row.level * 100)
                let pred = (g * (relG[C]?[row.id] ?? relMin) + o * (relO[C]?[row.id] ?? 0)) * (goScale[C] ?? 1)
                let c20 = Double(applied["p20:\(C):\(lvl)"] ?? 0)
                if c20 == 0 && abs(pred) > 0.004 {
                    num += pred * row.v(C)
                    den += pred * pred
                }
                if c20 != 0 {
                    n20 += c20 * (row.v(C) - pred)
                    d20 += c20 * c20
                    var tr = sens.p20Track["\(C):\(lvl)"] ?? [0, 0]
                    tr[0] += c20
                    tr[1] += row.v(C) - pred
                    sens.p20Track["\(C):\(lvl)"] = tr
                }
            }
            if den > 0 {
                goScale[C] = clamp((goScale[C] ?? 1) * num / den, 0.3, 3.0)
            }
            if d20 >= 4 {
                let est = n20 / d20
                if est > 0.0005 {
                    rel20[C] = clamp(0.5 * (rel20[C] ?? p20Guess) + 0.5 * est, 0.002, 0.03)
                    sens.p20Learned = true
                }
            }
        }
        sens.goScale = goScale
        sens.rel20 = rel20
        // A multipoint control that's had 10+ clicks in total but barely moved its own step is acting
        // somewhere else (the TV's points don't sit where the app thinks), so stop using it.
        let dead = sens.p20Track.filter { entry in
            let total = entry.value.count > 0 ? entry.value[0] : 0
            let resp = entry.value.count > 1 ? entry.value[1] : 0
            return abs(total) >= 10 && abs(resp) < 0.25 * abs(total) * (rel20[String(entry.key.prefix(1))] ?? p20Guess)
        }.map { $0.key }
        if !dead.isEmpty {
            sens.p20Dead = Array(Set(sens.p20Dead + dead)).sorted()
        }
        // colour menus with hue / saturation / brightness sliders: learn what one click does
        hslLearn(&sens, prev: prev, cur: cur, applied: applied, hdr: hdr)
        if let k = sens.k {
            var n = 0.0, d = 0.0
            let kScale = sens.kScale ?? 1
            for (name, _) in Patterns.colours {
                guard let ca = colourCoefs(prev.meas, name, hdr: hdr),
                      let cb = colourCoefs(cur.meas, name, hdr: hdr) else { continue }
                for (j, C) in ["R", "G", "B"].enumerated() {
                    let cl = Double(applied["cms:\(name):\(C)"] ?? 0)
                    if cl != 0 && !["RED", "GREEN", "BLUE"].contains(name) {
                        let pred = cl * k * kScale
                        n += pred * (cb[j] - ca[j])
                        d += pred * pred
                    }
                }
            }
            if d > 0 {
                sens.kScale = clamp(kScale * n / d, 0.3, 3.0)
            }
        }
    }

    /// For each axis, compare how far the colours moved with the clicks made. The sign is learned
    /// too, so it doesn't matter which way the TV's sliders run.
    static func hslLearn(_ sens: inout Sensitivity, prev: AutoRound, cur: AutoRound, applied: [String: Int], hdr: Bool) {
        guard applied.keys.contains(where: { $0.hasPrefix("hsl:") }) else { return }
        let a = Analysis.analyse(prev.meas, hdr: hdr).colours
        let b = Dictionary(Analysis.analyse(cur.meas, hdr: hdr).colours.map { ($0.name, $0) },
                           uniquingKeysWith: { x, _ in x })
        var k = sens.hslK ?? hslGuess
        for axis in HSLAxis.allCases {
            var n = 0.0, d = 0.0
            for ca in a {
                let cl = Double(applied["hsl:\(ca.name):\(axis.rawValue)"] ?? 0)
                if cl != 0, let cb = b[ca.name] {
                    let moved = cb.value(axis) - ca.value(axis)
                    n += cl * moved
                    d += cl * cl
                }
            }
            if d >= 4 {
                let est = n / d
                if abs(est) > 0.1 {
                    let old = k[axis.rawValue] ?? 1
                    let blended = old * est > 0 ? 0.5 * old + 0.5 * est : est   // sign flip: trust the data
                    k[axis.rawValue] = clamp(blended, -6.0, 6.0)
                }
                if !sens.hslLearned.contains(axis.rawValue) { sens.hslLearned.append(axis.rawValue) }
            }
        }
        sens.hslK = k
    }

    /// Clicks on hue / saturation / brightness per colour, damped and capped.
    static func hslPrescribe(_ sens: inout Sensitivity, _ rep: Report) -> [String: Int] {
        let k = sens.hslK ?? hslGuess
        sens.hslK = k
        let learned = Set(sens.hslLearned)
        let limits: [HSLAxis: Double] = [.hue: 2.0, .sat: 3.0, .lum: 5.0]
        var out: [String: Int] = [:]
        for c in rep.colours {
            if c.de < 2.0 { continue }
            for axis in HSLAxis.allCases {
                let err = c.value(axis)
                if abs(err) < limits[axis]! { continue }
                let cap = learned.contains(axis.rawValue) ? 6 : 3
                let raw = -0.7 * err / (k[axis.rawValue] ?? 1)
                var cl = clicks(raw)
                if cl == 0 { cl = raw > 0 ? 1 : -1 }
                out["hsl:\(c.name):\(axis.rawValue)"] = clamp(cl, -cap, cap)
            }
        }
        return out
    }

    /// rows: (a, b, y, w) -> minimise sum w (a x + b z + y)^2 (+ a small preference for small moves).
    static func lstsq2(_ rows: [(Double, Double, Double, Double)]) -> (Double, Double) {
        var saa = 0.0, sab = 0.0, sbb = 0.0, say = 0.0, sby = 0.0
        for (a, b, y, w) in rows {
            saa += w * a * a
            sab += w * a * b
            sbb += w * b * b
            say += w * a * y
            sby += w * b * y
        }
        let lam = 0.02 * (saa + sbb) / 2 + 1e-12
        let det = (saa + lam) * (sbb + lam) - sab * sab
        let x = (-say * (sbb + lam) + sby * sab) / det
        let z = (-sby * (saa + lam) + say * sab) / det
        return (x, z)
    }

    // MARK: prescribing

    static func prescribe(_ st: inout AutoState, _ rep: Report, hdr: Bool, points: Int, cms: ColourMenu,
                          prevRep: Report?) -> (Stage, [String: Int], [String]) {
        var sens = st.sens
        defer { st.sens = sens }
        var changes: [String: Int] = [:]
        let q = qvals(rep)
        let minLv = hdr ? 0.30 : 0.10
        let usable = q.filter { $0.level >= minLv - 1e-9 }
        let relG = sens.relG ?? [:], relO = sens.relO ?? [:]
        let go = sens.goScale ?? ["R": 1, "B": 1]
        let rel20 = sens.rel20 ?? ["R": p20Guess, "B": p20Guess]

        // 2-point on Red and Blue, relative to green
        var twopt: [String: (Double, Double)] = [:]
        for C in channels {
            var rows: [(Double, Double, Double, Double)] = []
            for row in usable {
                let rg = (relG[C]?[row.id] ?? relMin) * (go[C] ?? 1)
                let ro = (relO[C]?[row.id] ?? 0) * (go[C] ?? 1)
                let w = row.level < 0.2 ? 0.5 : (row.level <= (hdr ? 0.45 : 0.40) ? 1.5 : 1.0)
                let v = row.v(C)
                rows.append((v * rg, v * ro, v - 1, w))
            }
            let (gx, ox) = rows.isEmpty ? (0.0, 0.0) : lstsq2(rows)
            let lim = Double(twoMax)
            twopt[C] = (max(-lim, min(lim, 0.8 * gx)), max(-lim, min(lim, 0.8 * ox)))
        }
        let max2 = channels.flatMap { [abs(twopt[$0]!.0), abs(twopt[$0]!.1)] }.max() ?? 0

        func rms(_ after: Bool) -> Double {
            var tot = 0.0
            var n = 0
            for row in usable {
                for C in channels {
                    var v = row.v(C)
                    if after {
                        let (gx, ox) = twopt[C]!
                        v *= 1 + (gx * (relG[C]?[row.id] ?? relMin) + ox * (relO[C]?[row.id] ?? 0)) * (go[C] ?? 1)
                    }
                    tot += (v - 1) * (v - 1)
                    n += 1
                }
            }
            return n > 0 ? sqrt(tot / Double(n)) : 0
        }
        let helps = rms(true) < 0.75 * rms(false)
        var stage: Stage = ((max2 >= 1.5 && helps) || points == 0) ? .twoPoint : .multiPoint
        let prevStage = st.rounds.count >= 2 ? st.rounds[st.rounds.count - 2].stage : nil
        if points != 0, stage == .twoPoint, prevStage == .twoPoint, let prevRep,
           Analysis.greyAverage(rep, hdr: hdr) > 0.85 * Analysis.greyAverage(prevRep, hdr: hdr) {
            stage = .multiPoint
        }
        if stage == .twoPoint {
            for C in channels {
                let (gx, ox) = twopt[C]!
                if clicks(gx) != 0 { changes["gain:\(C)"] = clicks(gx) }
                if clicks(ox) != 0 { changes["offset:\(C)"] = clicks(ox) }
            }
        } else if points != 0 && sens.p20Dead.count >= 2 {
            stage = .twoPoint          // multipoint isn't landing where expected on this TV: don't keep guessing
        } else if points != 0 {
            let cap = p20Max * (sens.p20Learned ? 2 : 1)
            let dead = Set(sens.p20Dead)
            for row in q {
                let lvl = clicks(row.level * 100)
                if lvl < (hdr ? 25 : 10) { continue }
                if points == 11 && lvl % 10 != 0 { continue }
                for C in channels {
                    if dead.contains("\(C):\(lvl)") { continue }
                    let err = row.v(C) - 1
                    if abs(err) > 0.02 {
                        let raw = -err / ((rel20[C] ?? p20Guess) * row.v(C)) * 0.8
                        var cl = clicks(raw)
                        if cl == 0 { cl = raw > 0 ? 1 : -1 }
                        changes["p20:\(C):\(lvl)"] = clamp(cl, -cap, cap)
                    }
                }
            }
        }

        // colours
        var notes: [String] = []
        switch cms {
        case .rgbSliders:
            if sens.k == nil {
                sens.k = 0.004      // never learned (e.g. a primary wasn't measured): start cautious
                if sens.kScale == nil { sens.kScale = 1.0 }
                notes.append("Colour corrections are starting from an estimate for this TV; they'll sharpen with each round.")
            }
            let k = (sens.k ?? 0.004) * (sens.kScale ?? 1)
            let meas = st.rounds[st.rounds.count - 1].meas
            let missing = ["RED", "GREEN", "BLUE"].filter { meas[$0] == nil }.map { $0.capitalized }
            if !missing.isEmpty {
                notes.append("\(missing.joined(separator: ", ")) couldn't be measured this round, so its own slider isn't "
                             + "adjusted and the other colours use its ideal value instead.")
            }
            if let M = colourBasis(meas, hdr: hdr) {
                for col in rep.colours {
                    if col.de < 2.0 { continue }
                    let cur = ColourMath.solve3(M, col.xyz), tgt = ColourMath.solve3(M, col.target)
                    for (j, C) in ["R", "G", "B"].enumerated() {
                        let cl = clicks(0.6 * (tgt[j] - cur[j]) / k)
                        if cl != 0 { changes["cms:\(col.name):\(C)"] = clamp(cl, -8, 8) }
                    }
                }
            }
        case .hsl:
            let hsl = hslPrescribe(&sens, rep)
            changes.merge(hsl) { _, new in new }
            if !hsl.isEmpty && sens.hslLearned.isEmpty {
                notes.append("Colour changes start small: the app learns from the next round how much one click of "
                             + "hue, saturation and brightness moves on this TV, and which way.")
            }
        case .off:
            break
        }
        if sens.p20Dead.count >= 2 {
            notes.append("The 20-point controls on this TV don't move the steps the app expects (for example, many "
                         + "clicks at one point barely changed that step). The app sticks to 2-point here; fine-tune "
                         + "by hand with the Live meter if you need to.")
        }
        return (stage, changes, notes)
    }

    // MARK: rounds

    /// What should happen after the latest round.
    static func nextStep(_ st: AutoState) -> RoundKind {
        let done = st.rounds.dropLast().map { roundKind($0) }
        if !done.contains(.probeGain) { return .probeGain }
        if !done.contains(.probeOffset) { return .probeOffset }
        return .correct
    }

    static func describeSetup(_ s: RoundSetup) -> String {
        let src = ["video": "the pattern video", "iphone": "the iPhone showing the patches"][s.source] ?? "?"
        var met = ["hl": "the Display Plus HL", "iphone": "the iPhone camera"][s.meter] ?? "?"
        if s.meter == "iphone", let phone = s.phone, !phone.isEmpty { met += " (\(phone))" }
        return "\(src) and \(met)"
    }

    /// After a measurement has been appended to `st.rounds`: learn, analyse and decide what to ask for next.
    public static func planRound(_ st: inout AutoState, hdr: Bool, points: Int, cms: ColourMenu) -> Plan {
        let cur = st.rounds[st.rounds.count - 1]
        let prev: AutoRound? = st.rounds.count >= 2 ? st.rounds[st.rounds.count - 2] : nil
        // Rounds are only comparable if they used the same pattern source, meter and phone.
        if let prev, prev.applied?.isEmpty == false, let ps = prev.setup, let cs = cur.setup, ps != cs {
            var plan = Plan(report: Analysis.analyse(cur.meas, hdr: hdr))
            plan.banner = .undo
            plan.discard = true
            plan.message = "This round was measured with \(describeSetup(cs)), but the last one used "
                + "\(describeSetup(ps)). Run it again with \(describeSetup(ps)) so the app can compare like with like. "
                + "(This round wasn't kept.)"
            return plan
        }
        // A big jump in white means something other than the calibration controls changed.
        if let prev, prev.applied?.isEmpty == false, let w = cur.meas["W0"], let pw = prev.meas["W0"] {
            let ratio = w.y / max(pw.y, 1e-6)
            if ratio > 1.25 || ratio < 0.8 {
                var plan = Plan(report: Analysis.analyse(cur.meas, hdr: hdr))
                plan.banner = .undo
                plan.discard = true
                plan.message = "White is now \(String(format: "%.0f", w.y)) cd/m², but it was \(String(format: "%.0f", pw.y)) "
                    + "cd/m² last round. The white balance and colour controls can't do that, so another setting changed: "
                    + "picture mode, brightness, peak brightness, energy saving or the light sensor. Put it back and run the "
                    + "round again, or start over if you meant to change it. (This round wasn't kept.)"
                return plan
            }
        }
        learn(&st, hdr: hdr)
        let rep = Analysis.analyse(st.rounds[st.rounds.count - 1].meas, hdr: hdr)
        var plan = Plan(report: rep)
        plan.notes = st.notes
        if rep.greys.isEmpty {
            plan.message = "No usable greyscale readings this round."
            return plan
        }
        switch nextStep(st) {
        case .probeGain:
            plan.kind = .probeGain
            plan.changes = gainProbe(cms)
            plan.banner = .learn
            plan.title = "Learning round 1 of 2 - make exactly these changes, then run again"
            plan.message = "Test nudges: the app measures how Red and Blue GAIN respond on this TV (green stays put)."
            return plan
        case .probeOffset:
            plan.kind = .probeOffset
            plan.changes = offsetProbe()
            plan.banner = .learn
            plan.title = "Learning round 2 of 2 - make exactly these changes, then run again"
            plan.message = "This puts the gains back where they were and nudges the OFFSETS instead."
            return plan
        case .correct, .undo:
            break
        }
        let prevRep = st.rounds.count >= 2 ? Analysis.analyse(st.rounds[st.rounds.count - 2].meas, hdr: hdr) : nil
        if let prevRep, let prev, roundKind(prev) == .correct, let applied = prev.applied,
           Analysis.greyAverage(rep, hdr: hdr) > max(2.0, 1.25 * Analysis.greyAverage(prevRep, hdr: hdr) + 0.3)
            || Analysis.colourAverage(rep) > max(2.5, 1.25 * Analysis.colourAverage(prevRep) + 0.5) {
            plan.kind = .undo
            plan.changes = applied.mapValues { -$0 }
            plan.banner = .undo
            plan.title = "Last round made things worse - undo it with these, then run again"
            return plan
        }
        let prescribed = prescribe(&st, rep, hdr: hdr, points: points, cms: cms, prevRep: prevRep)
        let stage = prescribed.0
        var changes = prescribed.1
        plan.notes += prescribed.2
        st.rounds[st.rounds.count - 1].stage = stage
        // Two multipoint rounds in a row that didn't improve the greyscale: we're chasing noise.
        let earlier = st.rounds.dropLast()
        let fine = earlier.indices.filter { earlier[$0].stage == .multiPoint && roundKind(earlier[$0]) == .correct }
        if stage == .multiPoint && fine.count >= 2 {
            let before = Analysis.analyse(st.rounds[fine[fine.count - 2]].meas, hdr: hdr)
            if Analysis.greyAverage(rep, hdr: hdr) > 0.9 * Analysis.greyAverage(before, hdr: hdr) {
                changes = changes.filter { !$0.key.hasPrefix("p20:") }
                plan.notes.append("The last 20-point rounds didn't improve the greyscale (dE "
                                  + "\(String(format: "%.1f", Analysis.greyAverage(before, hdr: hdr))) -> "
                                  + "\(String(format: "%.1f", Analysis.greyAverage(rep, hdr: hdr)))), so the app has stopped "
                                  + "adjusting it: further changes would be chasing measurement noise or the TV's own processing.")
            }
        }
        // Same for colours: two rounds of colour changes that didn't help -> stop changing colours.
        func isColourKey(_ k: String) -> Bool { k.hasPrefix("cms:") || k.hasPrefix("hsl:") }
        let colRounds = earlier.indices.filter {
            roundKind(earlier[$0]) == .correct && (earlier[$0].applied ?? [:]).keys.contains(where: isColourKey)
        }
        if colRounds.count >= 2 && changes.keys.contains(where: isColourKey) {
            let before = Analysis.analyse(st.rounds[colRounds[colRounds.count - 2]].meas, hdr: hdr)
            if Analysis.colourAverage(rep) > 0.9 * Analysis.colourAverage(before) {
                changes = changes.filter { !isColourKey($0.key) }
                plan.notes.append("The last colour changes didn't improve the colours (dE "
                                  + "\(String(format: "%.1f", Analysis.colourAverage(before))) -> "
                                  + "\(String(format: "%.1f", Analysis.colourAverage(rep)))), so the app has stopped adjusting them.")
            }
        }
        let avgG = Analysis.greyAverage(rep, hdr: hdr), maxG = Analysis.greyMax(rep, hdr: hdr)
        let avgC = Analysis.colourAverage(rep)
        let maxC = rep.colours.map { $0.de }.max() ?? 0
        let good = avgG < 1.2 && maxG < 2.5 && avgC < 2.0 && maxC < 3.0
        if good || changes.isEmpty {
            plan.done = true
            plan.message = good ? "Done - greyscale and colours are on target."
                : "That's as close as this TV's controls get (grey dE \(String(format: "%.1f", avgG)), colour dE \(String(format: "%.1f", avgC)))."
            return plan
        }
        var title = stage == .twoPoint ? "Corrections - 2-point white balance"
            : "Corrections - fine-tune with the 20-point controls"
        if changes.keys.contains(where: { $0.hasPrefix("cms:") }) { title += " + colours" }
        plan.kind = .correct
        plan.changes = changes
        plan.title = title
        plan.stage = stage
        return plan
    }

    /// The user made the changes (ok) or skipped them.
    public static func recordRound(_ st: inout AutoState, plan: PendingPlan, ok: Bool) {
        guard ok, !plan.changes.isEmpty, !st.rounds.isEmpty else { return }
        st.rounds[st.rounds.count - 1].applied = plan.changes
        st.rounds[st.rounds.count - 1].kind = plan.kind
        if plan.kind == .undo, var rel20 = st.sens.rel20, var go = st.sens.goScale {
            for C in channels {
                rel20[C] = min((rel20[C] ?? p20Guess) * 2, 0.03)
                go[C] = min((go[C] ?? 1) * 2, 3.0)
            }
            st.sens.rel20 = rel20
            st.sens.goScale = go
        }
    }
}

// MARK: - Showing changes as menu tables

/// One row of a menu table: a label and clicks per column.
public struct ChangeRow: Identifiable, Sendable {
    public var id: String
    public var label: String
    public var values: [Int]
}

public struct ChangeMenu: Identifiable, Sendable {
    public var id: String
    public var title: String
    public var subtitle: String
    public var columns: [String]
    public var rows: [ChangeRow]
}

public enum ChangeTables {
    /// Groups change keys into the TV's menus, in the order the TV shows them.
    public static func menus(_ changes: [String: Int], points: Int) -> [ChangeMenu] {
        var out: [ChangeMenu] = []
        let rgb = ["Red", "Green", "Blue"]
        func v(_ k: String) -> Int { changes[k] ?? 0 }
        // 2-point
        var two: [ChangeRow] = []
        for (kind, label) in [("offset", "Offset"), ("gain", "Gain")] where changes.keys.contains(where: { $0.hasPrefix(kind + ":") }) {
            two.append(ChangeRow(id: kind, label: label, values: ["R", "G", "B"].map { v("\(kind):\($0)") }))
        }
        if !two.isEmpty {
            out.append(ChangeMenu(id: "2pt", title: "White Balance", subtitle: "2 Point", columns: rgb, rows: two))
        }
        // multipoint
        let levels = Set(changes.keys.filter { $0.hasPrefix("p20:") }.compactMap { key -> Int? in
            let parts = key.split(separator: ":")
            return parts.count == 3 ? Int(parts[2]) : nil
        }).sorted()
        if !levels.isEmpty {
            let rows = levels.map { lv in
                ChangeRow(id: "p\(lv)", label: points == 20 ? "\(lv / 5)  (\(lv)%)" : "\(lv)%",
                          values: ["R", "G", "B"].map { v("p20:\($0):\(lv)") })
            }
            out.append(ChangeMenu(id: "20pt", title: "White Balance", subtitle: points == 20 ? "20 Point" : "Multipoint",
                                  columns: rgb, rows: rows))
        }
        // colour space with R/G/B sliders
        let cms = Patterns.menuOrder.filter { c in changes.keys.contains(where: { $0.hasPrefix("cms:\(c):") }) }
        if !cms.isEmpty {
            out.append(ChangeMenu(id: "cms", title: "Color Space", subtitle: "Custom", columns: rgb,
                                  rows: cms.map { c in ChangeRow(id: c, label: c.capitalized,
                                                                 values: ["R", "G", "B"].map { v("cms:\(c):\($0)") }) }))
        }
        // hue / saturation / brightness
        let hsl = Patterns.menuOrder.filter { c in changes.keys.contains(where: { $0.hasPrefix("hsl:\(c):") }) }
        if !hsl.isEmpty {
            out.append(ChangeMenu(id: "hsl", title: "Color Management", subtitle: "per colour",
                                  columns: ["Hue", "Saturation", "Brightness"],
                                  rows: hsl.map { c in ChangeRow(id: c, label: c.capitalized,
                                                                 values: ["hue", "sat", "lum"].map { v("hsl:\(c):\($0)") }) }))
        }
        return out
    }
}
