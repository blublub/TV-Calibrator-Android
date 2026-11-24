import Foundation

// A made-up TV and meter, used by the tests and by the app's demo mode (so the app can be tried, and
// reviewed by App Review, without a TV). Ported from the Python engine's SimTV / SimMeter.

/// Small, seedable random numbers (SplitMix64), so a demo TV is the same every time.
public struct SeededRandom: Sendable {
    private var state: UInt64
    public init(seed: UInt64) { state = seed &+ 0x9E37_79B9_7F4A_7C15 }

    public mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }

    /// 0 ..< 1
    public mutating func unit() -> Double { Double(next() >> 11) / Double(1 << 53) }

    public mutating func uniform(_ a: Double, _ b: Double) -> Double { a + (b - a) * unit() }

    public mutating func gauss(_ mu: Double, _ sigma: Double) -> Double {
        let u1 = max(unit(), 1e-12), u2 = unit()
        return mu + sigma * (-2 * log(u1)).squareRoot() * cos(2 * .pi * u2)
    }
}

/// A TV with white-balance, gamma and colour errors that responds to menu changes roughly the way a
/// real one does (not exactly like the engine's model).
public final class SimTV {
    public struct State: Codable, Sendable {
        public var peak: Double
        public var gainErr: [Double]
        public var offErr: [Double]
        public var gamma: [Double]
        public var colErr: [String: [Double]]
        public var ctrl: [String: Double]
        /// Each multipoint control acts this far (0-1) above its label.
        public var p20Shift: Double
    }

    public var s: State
    public let hdr: Bool
    let kTrue = 0.0075
    let gainStep = [0.0045, 0.0040, 0.0055]
    let offStep = [0.0030, 0.0035, 0.0110]        // blue offset ~3x stronger, like the Micro RGB
    let p20Step = [0.0070, 0.0050, 0.0040]
    /// Hue/sat/lum menus in the demo: degrees, % and % per click.
    let hslStep = ["hue": 1.2, "sat": 0.9, "lum": 1.1]

    public init(hdr: Bool, state: State? = nil, seed: UInt64 = 7) {
        self.hdr = hdr
        if let state {
            s = state
        } else {
            var rnd = SeededRandom(seed: seed)
            var col: [String: [Double]] = [:]
            for (n, _) in Patterns.colours {
                col[n] = [rnd.uniform(-0.06, 0.06), rnd.uniform(-0.06, 0.06), rnd.uniform(-0.06, 0.06)]
            }
            s = State(peak: hdr ? 700 : 160,
                      gainErr: [rnd.uniform(-0.06, 0), rnd.uniform(-0.02, 0), rnd.uniform(-0.06, 0)],
                      offErr: [rnd.uniform(-0.03, 0.03), rnd.uniform(-0.03, 0.03), rnd.uniform(-0.03, 0.03)],
                      gamma: [2.4 + rnd.uniform(-0.12, 0.12), 2.4 + rnd.uniform(-0.12, 0.12), 2.4 + rnd.uniform(-0.12, 0.12)],
                      colErr: col, ctrl: [:], p20Shift: 0)
        }
    }

    /// Menu changes, in clicks. Menus stop at +/-50.
    public func apply(_ changes: [String: Int]) {
        for (k, v) in changes {
            s.ctrl[k] = clamp((s.ctrl[k] ?? 0) + Double(v), -50, 50)
        }
    }

    /// Power limiter: if the brightest channel at full white would exceed the panel's max, everything
    /// is scaled down (so raising all gains together does nothing).
    func limit() -> Double {
        let m = (0..<3).map { chanRaw($0, 1.0, nil) }.max() ?? 1
        return m > 1 ? 1 / m : 1
    }

    func chan(_ c: Int, _ level: Double, _ keyLevel: Double? = nil) -> Double {
        chanRaw(c, level, keyLevel) * limit()
    }

    func chanRaw(_ c: Int, _ level: Double, _ keyLevel: Double?) -> Double {
        let C = ["R", "G", "B"][c]
        let kl = keyLevel ?? level
        let g = s.ctrl["gain:\(C)"] ?? 0
        let o = s.ctrl["offset:\(C)"] ?? 0
        // multipoint: tent interpolation between 5% points, spilling into neighbours
        var p = 0.0
        for (key, v) in s.ctrl where key.hasPrefix("p20:\(C):") {
            let parts = key.split(separator: ":")
            guard parts.count == 3, let lvInt = Double(parts[2]) else { continue }
            let lv = lvInt / 100
            p += v * max(0.0, 1 - abs(kl - (lv + s.p20Shift)) / 0.09)
        }
        let base = pow(level, s.gamma[c])
        var val = base * (1 + s.gainErr[c] + g * gainStep[c]) * (1 + p * p20Step[c])
        val += (s.offErr[c] + o * offStep[c]) * 0.02 * pow(1 - level, 2) * (1 - exp(-30 * level))
        return max(val, 0)
    }

    /// What a meter on the centre of the screen sees for a patch.
    public func render(_ patch: Patch) -> Vec3 {
        let peak = s.peak
        if patch.kind == .white || patch.kind == .grey {
            var rgb = Vec3(0, 0, 0)
            if hdr, let nits = patch.nits?.x {
                let lvl = nits < peak ? pow(nits / peak, 1 / 2.4) : 1.0
                for c in 0..<3 { rgb[c] = min(chan(c, min(lvl, 1.0), patch.level), 1.02) * peak }
            } else {
                for c in 0..<3 { rgb[c] = chan(c, patch.level ?? 0) * peak }
            }
            return ColourMath.matvec(ColourMath.m709, rgb)
        }
        // colour: native primaries a bit wider than the target, plus per-colour errors + menu sliders
        let name = patch.id
        let target = patch.rgb ?? Vec3(0, 0, 0)
        let lvl = hdr ? pow(100 / peak, 1 / 2.4) : 0.75
        var mix = Vec3(0, 0, 0)
        for (j, C) in ["R", "G", "B"].enumerated() {
            let cms = (s.ctrl["cms:\(name):\(C)"] ?? 0) * kTrue
            mix[j] = max(0, target[j] + (s.colErr[name]?[j] ?? 0) + cms) * chan(j, lvl) * peak
        }
        let native: [Point2] = hdr ? ColourMath.Gamut.p3.primaries
            : [Point2(0.655, 0.325), Point2(0.290, 0.615), Point2(0.150, 0.058)]
        var XYZ = ColourMath.matvec(ColourMath.rgbToXYZMatrix(native), mix)
        // demo extra: hue / saturation / brightness sliders move the colour around D65
        let h = s.ctrl["hsl:\(name):hue"] ?? 0, sa = s.ctrl["hsl:\(name):sat"] ?? 0, lu = s.ctrl["hsl:\(name):lum"] ?? 0
        if h != 0 || sa != 0 || lu != 0 {
            let xy = ColourMath.xyzToXY(XYZ), w = ColourMath.d65
            let ang = h * (hslStep["hue"] ?? 1) * .pi / 180
            let scale = 1 + sa * (hslStep["sat"] ?? 1) / 100
            let dx = (xy.x - w.x) * scale, dy = (xy.y - w.y) * scale
            let nx = w.x + dx * cos(ang) - dy * sin(ang), ny = w.y + dx * sin(ang) + dy * cos(ang)
            XYZ = ColourMath.xyYToXYZ(Point2(nx, ny), XYZ.y * (1 + lu * (hslStep["lum"] ?? 1) / 100))
        }
        return XYZ
    }

    /// A sync flash at a drive level (SDR signal, or HDR nits converted to this TV's drive).
    func renderSync(_ value: Double) -> Vec3 {
        let lvl = hdr ? pow(value / s.peak, 1 / 2.4) : value
        let rgb = Vec3(chan(0, lvl) * s.peak, chan(1, lvl) * s.peak, chan(2, lvl) * s.peak)
        return ColourMath.matvec(ColourMath.m709, rgb)
    }
}

/// Virtual clock + a pattern video playing + a SimTV. Runs much faster than real time unless
/// `realTime` is set (the demo uses 0.1: ten times faster than real, so progress is watchable).
public final class SimMeter: Meter {
    let tv: SimTV
    let seq: PatternSequence
    var t = 0.0
    let playAt: Double
    let realTime: Double
    var rnd: SeededRandom

    public init(tv: SimTV, sequence: PatternSequence, playAt: Double = 3.0, realTime: Double = 0, seed: UInt64 = 1) {
        self.tv = tv
        self.seq = sequence
        self.playAt = playAt
        self.realTime = realTime
        self.rnd = SeededRandom(seed: seed)
    }

    public var integration: Double { 0.25 }
    public func now() -> Double { t }

    public func sleep(_ seconds: Double) async throws {
        if seconds > 0 {
            t += seconds
            if realTime > 0 { try await MeterClock.sleep(seconds * realTime) } else { await Task.yield() }
        }
    }

    enum Showing {
        case nothing
        case sync(Double)
        case patch(Patch)
    }

    func showing(_ time: Double) -> Showing {
        var v = time - playAt
        if v < Patterns.lead { return .nothing }
        v -= Patterns.lead
        if v < 3 * Patterns.syncHold {
            let which = Int(v / Patterns.syncHold)
            return .sync(which == 1 ? seq.syncHigh : seq.syncLow)
        }
        v -= 3 * Patterns.syncHold
        for (p, off) in zip(seq.patches, seq.offsets) where off <= v && v < off + p.hold {
            return .patch(p)
        }
        return .nothing
    }

    func render(_ s: Showing) -> Vec3 {
        switch s {
        case .nothing: return Vec3(0.03, 0.03, 0.035)
        case .sync(let lvl): return tv.renderSync(lvl)
        case .patch(let p): return tv.render(p)
        }
    }

    public func read() async throws -> MeterReading {
        let t0 = t
        let Y = render(showing(t0 + 0.3)).y
        let dur = 0.9 + 0.25 * rnd.unit() + (Y < 5 ? 1.6 / pow(max(Y, 0.05), 0.5) : 0)
        t += dur
        if realTime > 0 { try await MeterClock.sleep(dur * realTime) } else { await Task.yield() }
        // a real meter integrates over a short window, so readings across a change are mixed
        let win = 0.5
        var sum = Vec3(0, 0, 0)
        for k in 0..<5 {
            sum += render(showing(t - win + win * (Double(k) + 0.5) / 5))
        }
        let common = 1 + rnd.gauss(0, 0.003)                             // brightness noise
        let XYZ = sum / 5 * common * (1 + rnd.gauss(0, 0.0006))         // small colour noise
        return MeterReading(start: t0, end: t, xyz: XYZ)
    }
}

/// The pattern the app is currently asking for, readable from any thread (the demo meter uses it).
public final class ShownPatch: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Patch?
    public init() {}
    public var patch: Patch? {
        get { lock.lock(); defer { lock.unlock() }; return value }
        set { lock.lock(); value = newValue; lock.unlock() }
    }
}

/// Demo mode for the guided steps: reads whichever patch the app is asking for (as if the right file
/// were playing), in real time.
public final class SimLiveMeter: Meter {
    let tv: SimTV
    let shown: ShownPatch
    var rnd = SeededRandom(seed: 99)

    public init(tv: SimTV, shown: ShownPatch) {
        self.tv = tv
        self.shown = shown
    }

    func showing() -> Patch? { shown.patch }

    public var integration: Double { 0.1 }
    public func now() -> Double { MeterClock.now() }
    public func sleep(_ seconds: Double) async throws { try await MeterClock.sleep(seconds) }

    public func read() async throws -> MeterReading {
        let t0 = now()
        let p = showing()
        var XYZ = p.map { tv.render($0) } ?? Vec3(0.03, 0.03, 0.035)
        XYZ *= 1 + rnd.gauss(0, 0.002)
        XYZ.x *= 1 + rnd.gauss(0, 0.0015)
        XYZ.z *= 1 + rnd.gauss(0, 0.0015)
        try await MeterClock.sleep(XYZ.y > 5 ? 0.35 : 0.8)
        return MeterReading(start: t0, end: now(), xyz: XYZ)
    }
}
