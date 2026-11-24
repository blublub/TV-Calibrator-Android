import Foundation

// MARK: - Measurements

/// A patch that got no reading (too dark for the meter, or the meter too slow).
public struct MissedPatch: Codable, Equatable, Sendable {
    public var name: String
    /// How long each reading attempt took, in seconds.
    public var readings: [Double]
    /// Seconds the patch was on screen.
    public var hold: Double

    enum CodingKeys: String, CodingKey {
        case name
        case readings = "readings_s"
        case hold = "hold_s"
    }
}

/// One round's readings: XYZ per patch id. Encodes like the Mac app's history files:
/// {"W0": [X, Y, Z], "G5": null, ..., "_missed": {...}}.
public struct Measurements: Codable, Equatable, Sendable {
    public var values: [String: Vec3]
    public var missed: [String: MissedPatch]

    public init(values: [String: Vec3] = [:], missed: [String: MissedPatch] = [:]) {
        self.values = values
        self.missed = missed
    }

    public subscript(id: String) -> Vec3? {
        get { values[id] }
        set { values[id] = newValue }
    }

    struct Key: CodingKey {
        var stringValue: String
        var intValue: Int? { nil }
        init(_ s: String) { stringValue = s }
        init?(stringValue: String) { self.stringValue = stringValue }
        init?(intValue: Int) { nil }
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: Key.self)
        var values: [String: Vec3] = [:]
        var missed: [String: MissedPatch] = [:]
        for k in c.allKeys {
            if k.stringValue == "_missed" {
                missed = try c.decode([String: MissedPatch].self, forKey: k)
            } else if let v = try c.decodeIfPresent([Double].self, forKey: k), v.count == 3 {
                values[k.stringValue] = Vec3(v[0], v[1], v[2])
            }
        }
        self.values = values
        self.missed = missed
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: Key.self)
        for (k, v) in values {
            try c.encode([v.x, v.y, v.z], forKey: Key(k))
        }
        for k in missed.keys where values[k] == nil {
            try c.encodeNil(forKey: Key(k))
        }
        if !missed.isEmpty {
            try c.encode(missed, forKey: Key("_missed"))
        }
    }
}

// MARK: - Meters

/// One reading: when it started and ended (on the meter's own clock, seconds) and what it saw.
public struct MeterReading: Sendable {
    public var start: Double
    public var end: Double
    public var xyz: Vec3?
    public init(start: Double, end: Double, xyz: Vec3?) {
        self.start = start
        self.end = end
        self.xyz = xyz
    }
}

/// Anything that measures the TV: the iPhone camera (with its profile), or a simulation.
public protocol Meter: AnyObject {
    /// Seconds on the meter's clock (monotonic). Simulations use a virtual clock.
    func now() -> Double
    func sleep(_ seconds: Double) async throws
    func read() async throws -> MeterReading
    /// How long before a reading's end the light it reports was captured (the sync uses this).
    var integration: Double { get }
}

/// Real-time clock helpers for meters that measure real light.
public enum MeterClock {
    public static func now() -> Double { ProcessInfo.processInfo.systemUptime }
    public static func sleep(_ seconds: Double) async throws {
        if seconds > 0 {
            try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
        }
    }
}

public enum MeasureError: Error, LocalizedError, Equatable {
    case noSync
    case outOfOrder
    case message(String)

    public var errorDescription: String? {
        switch self {
        case .noSync:
            return "No sync flashes seen. Check the camera is pointed at the centre of the screen and the video is playing from the start."
        case .outOfOrder:
            return "The greys came in out of order, so the camera and the video got out of step. Start the measurement, then play the video from the very beginning."
        case .message(let s):
            return s
        }
    }
}

/// Progress while a video is being measured.
public enum SweepEvent: Sendable {
    case waiting
    case reading(y: Double?)
    case synced
    case patchStart(index: Int, id: String, name: String)
    case patch(index: Int, id: String, name: String, xyz: Vec3?)
}

// MARK: - Following a pattern video

public enum Sweep {
    static func median(_ v: [Double]) -> Double {
        let s = v.sorted()
        return s[s.count / 2]
    }

    static func neutral(_ v: Vec3, limit: Double = 0.05) -> Bool {
        v.y > 0 && ColourMath.polar(ColourMath.xyzToXY(v)).r < limit
    }

    struct Run {
        var ys: [Double]
        var ts: [Double]
        var xyz: [Vec3]
    }

    /// Waits for the sync and returns the time (meter clock) the patch sequence starts.
    ///
    /// The video goes: black, grey, WHITE, grey, then the first (bright) patch. This looks for
    /// bright -> grey (~6 s) -> bright, all three neutral (not a colour). Readings that straddle a
    /// change are skipped, and anything shown before PLAY is ignored.
    public static func findSync(_ meter: Meter, timeout: Double = 240,
                                onEvent: ((SweepEvent) -> Void)? = nil) async throws -> Double {
        onEvent?(.waiting)
        let tStart = meter.now()
        var runs: [Run] = []
        while meter.now() - tStart < timeout {
            try Task.checkCancellation()
            let r = try await meter.read()
            guard let XYZ = r.xyz else {
                onEvent?(.reading(y: nil))
                continue
            }
            let t = r.end - meter.integration
            let y = max(XYZ.y, 1e-4)
            onEvent?(.reading(y: XYZ.y))
            if let last = runs.last, 1 / 1.6 < y / median(last.ys), y / median(last.ys) < 1.6 {
                runs[runs.count - 1].ys.append(y)
                runs[runs.count - 1].ts.append(t)
                runs[runs.count - 1].xyz.append(XYZ)
            } else {
                runs.append(Run(ys: [y], ts: [t], xyz: [XYZ]))
            }
            if runs.count > 10 { runs.removeFirst(runs.count - 10) }
            // drop single-reading runs that sit between their neighbours (a reading caught mid-change)
            var clean: [Run] = []
            for (i, run) in runs.enumerated() {
                if i > 0, i < runs.count - 1, run.ys.count == 1 {
                    let a = median(runs[i - 1].ys), b = run.ys[0], c = median(runs[i + 1].ys)
                    if min(a, c) < b, b < max(a, c) { continue }
                }
                clean.append(run)
            }
            if clean.count < 3 { continue }
            let H1 = clean[clean.count - 3], L = clean[clean.count - 2], H2 = clean[clean.count - 1]
            let h1 = median(H1.ys), l = median(L.ys), h2 = median(H2.ys)
            // the sync's grey is a mid grey, not black: bright/grey ratio ~4-20, never ~1000
            guard 2.0 <= h1 / l, h1 / l <= 40, 1.6 <= h2 / l, h2 / l <= 40, 0.3 < h1 / h2, h1 / h2 < 3.5 else { continue }
            // all three must be greys/whites, not colour patches
            guard [H1, L, H2].allSatisfy({ neutral($0.xyz[$0.xyz.count / 2]) }) else { continue }
            // The grey started between the last bright reading and the first grey one, and ended
            // between the last grey reading and the first bright one: work with ranges.
            let fallLo = H1.ts[H1.ts.count - 1], fallHi = L.ts[0]
            let riseLo = L.ts[L.ts.count - 1], riseHi = H2.ts[0]
            let tol = 0.6
            guard riseLo - fallHi - tol <= Patterns.syncHold, Patterns.syncHold <= riseHi - fallLo + tol else { continue }
            let lo = max(riseLo, fallLo + Patterns.syncHold)
            let hi = min(riseHi, fallHi + Patterns.syncHold)
            let est = lo <= hi ? (lo + hi) / 2 : (riseLo + riseHi) / 2
            onEvent?(.synced)
            return est
        }
        throw MeasureError.noSync
    }

    /// Measures every patch of a pattern video, following its timeline from the sync.
    public static func sweep(_ meter: Meter, sequence seq: PatternSequence,
                             onEvent: ((SweepEvent) -> Void)? = nil) async throws -> Measurements {
        let tSeq = try await findSync(meter, onEvent: onEvent)
        let offsets = seq.offsets
        var meas = Measurements()
        for (i, p) in seq.patches.enumerated() {
            let start = tSeq + offsets[i]
            let end = start + p.hold
            onEvent?(.patchStart(index: i, id: p.id, name: p.name))
            while meter.now() < start + 1.2 {
                try Task.checkCancellation()
                try await meter.sleep(min(0.25, start + 1.2 - meter.now()))
            }
            var vals: [Vec3] = []
            var tries: [Double] = []
            while meter.now() < end - 1.0 {
                try Task.checkCancellation()
                let r = try await meter.read()
                tries.append(pyRound(r.end - r.start, 1))
                onEvent?(.reading(y: r.xyz?.y))
                guard let XYZ = r.xyz, r.start >= start + 0.5 else { continue }
                if r.end - meter.integration <= end - 0.15 {
                    vals.append(XYZ)
                }
                // bright patches: one more reading if there's clearly time
                if r.end + (r.end - r.start) > end - 0.6 { break }
            }
            if vals.isEmpty {
                meas.missed[p.id] = MissedPatch(name: p.name, readings: tries, hold: p.hold)
            } else {
                meas[p.id] = vals.reduce(Vec3(0, 0, 0), +) / Double(vals.count)
            }
            onEvent?(.patch(index: i, id: p.id, name: p.name, xyz: meas[p.id]))
        }
        return meas
    }

    /// Readings in the wrong order mean the video and the meter got out of step.
    public static func outOfOrder(_ meas: Measurements, hdr: Bool) -> Bool {
        let ys = Patterns.sequence(hdr: hdr).patches.filter { $0.kind == .grey }.compactMap { meas[$0.id]?.y }
        return zip(ys, ys.dropFirst()).contains { $1 < $0 * 0.8 }
    }
}
