import XCTest
@testable import CalibrationKit

final class AnalysisTests: XCTestCase {
    func testAnalyseMatchesPython() throws {
        for item in list(try Fixture.load("analyse")) {
            let c = dict(item)
            let label = c["label"] as? String ?? "?"
            let hdr = c["hdr"] as? Bool ?? false
            let meas = try Fixture.decode(Measurements.self, c["meas"]!)
            let rep = Analysis.analyse(meas, hdr: hdr)
            let want = dict(c["report"])
            let greys = list(want["greys"])
            XCTAssertEqual(rep.greys.count, greys.count, "\(label) greys")
            for (g, wi) in zip(rep.greys, greys) {
                let w = dict(wi)
                XCTAssertEqual(g.id, w["id"] as? String, label)
                assertClose(g.Y, num(w["Y"]), 1e-9, "\(label) \(g.id) Y")
                assertClose(g.targetY, num(w["targetY"]), 1e-9, "\(label) \(g.id) target")
                assertClose(g.bal, vec3(w["bal"]), 1e-9, "\(label) \(g.id) balance")
                assertClose(g.de, num(w["de"]), 1e-9, "\(label) \(g.id) dE")
                XCTAssertEqual(g.clipped, w["clipped"] as? Bool, "\(label) \(g.id) clipped")
            }
            let cols = list(want["colours"])
            XCTAssertEqual(rep.colours.count, cols.count, "\(label) colours")
            for (col, wi) in zip(rep.colours, cols) {
                let w = dict(wi)
                XCTAssertEqual(col.name, w["name"] as? String, label)
                assertClose(col.sat, num(w["sat"]), 1e-9, "\(label) \(col.name) sat")
                assertClose(col.hue, num(w["hue"]), 1e-9, "\(label) \(col.name) hue")
                assertClose(col.lum, num(w["lum"]), 1e-9, "\(label) \(col.name) lum")
                assertClose(col.de, num(w["de"]), 1e-9, "\(label) \(col.name) dE")
                assertClose(col.target, vec3(w["target"]), 1e-9, "\(label) \(col.name) target")
            }
            XCTAssertEqual(rep.warnings.count, list(want["warnings"]).count, "\(label) warnings: \(rep.warnings)")
            assertClose(rep.drift, num(want["drift"]), 1e-9, "\(label) drift")
            assertClose(rep.peak, num(want["peak"]), 1e-9, "\(label) peak")
            assertClose(Analysis.greyAverage(rep, hdr: hdr), num(want["grey_avg"]), 1e-9, "\(label) grey avg")
            assertClose(Analysis.greyMax(rep, hdr: hdr), num(want["grey_max"]), 1e-9, "\(label) grey max")
            assertClose(Analysis.colourAverage(rep), num(want["colour_avg"]), 1e-9, "\(label) colour avg")
        }
    }
}

final class AutoRoundTests: XCTestCase {
    /// Every simulated round from the Python engine: same state in, same plan and learning out.
    func testPlanRoundMatchesPython() throws {
        for item in list(try Fixture.load("plan")) {
            let c = dict(item)
            let label = c["label"] as? String ?? "?"
            let hdr = c["hdr"] as? Bool ?? false
            let points = Int(num(c["points"]) ?? 20)
            let cms = ColourMenu(rawValue: c["cms"] as? String ?? "off") ?? .off
            var st = try Fixture.decode(AutoState.self, c["state_before"]!)
            let plan = AutoCalibration.planRound(&st, hdr: hdr, points: points, cms: cms)
            let want = dict(c["plan"])

            XCTAssertEqual(plan.kind?.rawValue, want["kind"] as? String, "\(label) kind")
            let wantChanges = (want["changes"] as? [String: Any] ?? [:]).mapValues { Int(num($0) ?? 0) }
            XCTAssertEqual(plan.changes, wantChanges, "\(label) changes")
            XCTAssertEqual(plan.done, want["done"] as? Bool ?? false, "\(label) done")
            XCTAssertEqual(plan.discard, want["discard"] as? Bool ?? false, "\(label) discard")
            XCTAssertEqual(plan.stage?.rawValue, want["stage"] as? String, "\(label) stage")
            XCTAssertEqual(plan.banner.rawValue, want["banner"] as? String ?? "correct", "\(label) banner")
            XCTAssertEqual(plan.title, want["title"] as? String ?? "", "\(label) title")
            XCTAssertEqual(plan.notes.count, list(want["notes"]).count, "\(label) notes \(plan.notes)")

            // what was learned
            let after = try Fixture.decode(AutoState.self, c["state_after"]!)
            let a = st.sens, b = after.sens
            XCTAssertEqual(a.learned, b.learned, "\(label) learned")
            XCTAssertEqual(a.p20Learned, b.p20Learned, "\(label) p20 learned")
            XCTAssertEqual(a.p20Dead, b.p20Dead, "\(label) p20 dead")
            XCTAssertEqual(Set(a.hslLearned), Set(b.hslLearned), "\(label) hsl learned")
            assertClose(a.k, b.k, 1e-9, "\(label) k")
            assertClose(a.kScale, b.kScale, 1e-9, "\(label) k scale")
            for C in ["R", "B"] {
                assertClose(a.goScale?[C], b.goScale?[C], 1e-9, "\(label) go scale \(C)")
                assertClose(a.rel20?[C], b.rel20?[C], 1e-9, "\(label) rel20 \(C)")
                let mineKeys = Set((a.relG?[C] ?? [:]).keys), wantKeys = Set((b.relG?[C] ?? [:]).keys)
                XCTAssertEqual(mineKeys, wantKeys, "\(label) relG keys")
                for (k, v) in b.relG?[C] ?? [:] { assertClose(a.relG?[C]?[k], v, 1e-9, "\(label) relG \(C) \(k)") }
                for (k, v) in b.relO?[C] ?? [:] { assertClose(a.relO?[C]?[k], v, 1e-9, "\(label) relO \(C) \(k)") }
            }
            for (axis, v) in b.hslK ?? [:] { assertClose(a.hslK?[axis], v, 1e-9, "\(label) hsl \(axis)") }
            for (key, v) in b.p20Track {
                assertClose(a.p20Track[key]?.first, v.first, 1e-9, "\(label) track \(key) clicks")
                assertClose(a.p20Track[key]?.last, v.last, 1e-9, "\(label) track \(key) response")
            }
            XCTAssertEqual(st.rounds.last?.stage, after.rounds.last?.stage, "\(label) round stage")
            XCTAssertEqual(st.notes.count, after.notes.count, "\(label) state notes")
        }
    }

    /// The Swift simulator end to end: learning, corrections, and the TV getting close to target.
    func testSimulatedCalibrationConverges() async throws {
        for (hdr, cms) in [(false, ColourMenu.rgbSliders), (false, .hsl), (true, .rgbSliders)] {
            let tv = SimTV(hdr: hdr, seed: 11)
            var st = AutoState()
            var first: Double?
            var last = Double.nan
            for r in 0..<10 {
                let meter = SimMeter(tv: tv, sequence: Patterns.sequence(hdr: hdr), seed: UInt64(r + 1))
                let meas = try await Sweep.sweep(meter, sequence: Patterns.sequence(hdr: hdr))
                st.rounds.append(AutoRound(time: "r\(r)", meas: meas, setup: RoundSetup(phone: "sim")))
                let plan = AutoCalibration.planRound(&st, hdr: hdr, points: 20, cms: cms)
                last = Analysis.greyAverage(plan.report, hdr: hdr)
                if first == nil { first = last }
                if plan.done { break }
                AutoCalibration.recordRound(&st, plan: plan.pending, ok: true)
                tv.apply(plan.changes)
            }
            XCTAssertLessThan(last, 2.0, "HDR \(hdr) \(cms): greyscale dE ended at \(last)")
            XCTAssertLessThan(last, first ?? 0, "HDR \(hdr) \(cms): it should improve")
        }
    }

    func testSyncIsFoundAndEveryPatchMeasured() async throws {
        for hdr in [false, true] {
            let tv = SimTV(hdr: hdr, seed: 3)
            let seq = Patterns.quickSequence(hdr: hdr)
            let meas = try await Sweep.sweep(SimMeter(tv: tv, sequence: seq, playAt: 7), sequence: seq)
            for p in seq.patches {
                XCTAssertNotNil(meas[p.id], "HDR \(hdr): \(p.id) missing")
                if let m = meas[p.id] { assertClose(m.y, tv.render(p).y, 0.03, "HDR \(hdr): \(p.id) brightness") }
            }
            XCTAssertFalse(Sweep.outOfOrder(meas, hdr: hdr))
        }
    }

    func testNoSyncWithoutAVideo() async {
        let tv = SimTV(hdr: false)
        let meter = SimMeter(tv: tv, sequence: PatternSequence(hdr: false, syncLow: 0.3, syncHigh: 1, patches: []),
                             playAt: 1e9)
        do {
            _ = try await Sweep.findSync(meter, timeout: 60)
            XCTFail("found a sync in black")
        } catch {
            XCTAssertEqual(error as? MeasureError, .noSync)
        }
    }
}

final class GuidedTests: XCTestCase {
    func testSettledAverageMatchesPython() throws {
        let f = dict(try Fixture.load("guided"))
        for (i, item) in list(f["settled"]).enumerated() {
            let c = dict(item)
            let rs = list(c["readings"]).map { vec3($0) }
            let out = Guided.settledAverage(rs)
            assertClose(out.xyz, vec3(c["xyz"]), 1e-9, "settled \(i) XYZ")
            XCTAssertEqual(out.count, Int(num(c["n"])!), "settled \(i) count")
            assertClose(out.wobble, num(c["wobble"]), 1e-9, "settled \(i) wobble")
        }
    }

    func testLiveReadingMatchesPython() throws {
        let f = dict(try Fixture.load("guided"))
        for (i, item) in list(f["live"]).enumerated() {
            let c = dict(item)
            let hdr = c["hdr"] as? Bool ?? false
            let p = Patterns.patch(hdr: hdr, id: c["patch_id"] as! String)!
            let basis: [Vec3]? = (c["basis"] is NSNull || c["basis"] == nil) ? nil : list(c["basis"]).map { vec3($0) }
            let r = Guided.liveReading(patch: p, xyz: vec3(c["XYZ"]), hdr: hdr, whiteY: num(c["white_Y"]), basis: basis)
            let w = dict(c["out"])
            let label = "live \(i) \(p.id)"
            assertClose(r.uv, point(w["uv"]), 1e-9, label)
            XCTAssertEqual(r.ok, w["ok"] as? Bool, "\(label) ok")
            assertClose(r.de, num(w["de"]), 1e-9, "\(label) dE")
            if let q = w["q"] as? [String: Any] {
                assertClose(r.qR, num(q["R"]), 1e-9, "\(label) qR")
                assertClose(r.qB, num(q["B"]), 1e-9, "\(label) qB")
            }
            if p.kind == .colour {
                assertClose(r.hue, num(w["hue"]), 1e-9, "\(label) hue")
                assertClose(r.sat, num(w["sat"]), 1e-9, "\(label) sat")
                assertClose(r.lum, num(w["lum"]), 1e-9, "\(label) lum")
                XCTAssertEqual(r.toward, w["toward"] as? String, "\(label) toward")
                XCTAssertEqual(r.rgbApprox, w["rgb_approx"] as? Bool ?? false, "\(label) approx")
                if let h = w["rgb_hint"], !(h is NSNull) {
                    assertClose(r.rgbHint ?? Vec3(.nan, .nan, .nan), vec3(h), 1e-9, "\(label) hint")
                } else {
                    XCTAssertNil(r.rgbHint, "\(label) hint")
                }
                if let o = w["out_of_reach"] as? [String: Any] {
                    XCTAssertEqual(r.outOfReach?.channels, o["channels"] as? [String], "\(label) out of reach")
                    assertClose(r.outOfReach?.bestDE, num(o["best_de"]), 1e-9, "\(label) best dE")
                } else {
                    XCTAssertNil(r.outOfReach, "\(label) out of reach")
                }
            }
        }
    }

    func testMismatchMatchesPython() throws {
        let f = dict(try Fixture.load("guided"))
        for (i, item) in list(f["mismatch"]).enumerated() {
            let c = dict(item)
            let hdr = c["hdr"] as? Bool ?? false
            let p = Patterns.patch(hdr: hdr, id: c["patch_id"] as! String)!
            let got = Guided.mismatch(patch: p, xyz: vec3(c["XYZ"]), hdr: hdr, whiteY: num(c["white_Y"]))
            let want = c["out"] as? String
            XCTAssertEqual(got == nil, want == nil, "mismatch \(i) \(p.id): \(String(describing: got)) vs \(String(describing: want))")
        }
    }

    func testLiveAnalysisMatchesPython() throws {
        let f = dict(try Fixture.load("guided"))
        for item in list(f["live_analysis"]) {
            let c = dict(item)
            let r = LiveAnalysis.of(vec3(c["XYZ"]), hdr: c["hdr"] as? Bool ?? false)
            let w = dict(c["out"])
            XCTAssertEqual(r.kind == .white ? "white" : "colour", w["kind"] as? String)
            if r.kind == .white {
                XCTAssertEqual(r.advice, w["advice"] as? String)
                assertClose(r.de, num(w["de"]), 1e-9)
            } else {
                XCTAssertEqual(r.name, w["name"] as? String)
                assertClose(r.hue, num(w["hue"]), 1e-9)
                assertClose(r.sat, num(w["sat"]), 1e-9)
            }
        }
    }
}

final class PointFinderTests: XCTestCase {
    func testFinderMapMatchesPython() throws {
        let f = dict(try Fixture.load("finder"))
        for (i, item) in list(f["cases"]).enumerated() {
            let c = dict(item)
            let hdr = c["hdr"] as? Bool ?? false
            let grid = Int(num(c["grid"])!)
            let base = try Fixture.decode(Measurements.self, c["base"]!)
            let nudged = try Fixture.decode(Measurements.self, c["nudged"]!)
            let map = PointFinder.map(base: base, nudged: nudged, hdr: hdr)
            let want = list(c["map"]).map { dict($0) }
            XCTAssertEqual(map.count, want.count, "finder \(i) points")
            for (m, w) in zip(map, want) {
                XCTAssertEqual(m.point, Int(num(w["point"])!), "finder \(i)")
                XCTAssertEqual(m.channel, w["channel"] as? String, "finder \(i) channel")
                assertClose(m.level, num(w["level"]), 1e-9, "finder \(i) level")
                assertClose(m.size, num(w["size"]), 1e-9, "finder \(i) size")
            }
            let sugg = PointFinder.suggestions(map: map, overrides: [:], hdr: hdr, grid: grid)
            for (pid, v) in dict(c["suggest"]) {
                XCTAssertEqual(sugg[pid], num(v).map { Int($0) }, "finder \(i) suggestion \(pid)")
            }
        }
    }

    func testPointForLevelMatchesPython() throws {
        let f = dict(try Fixture.load("finder"))
        for item in list(f["point_for_level"]) {
            let c = dict(item)
            let pairs = list(c["pairs"]).map { p -> FinderPoint in
                let d = dict(p)
                return FinderPoint(point: Int(num(d["point"])!), channel: "R", level: num(d["level"])!, size: 1)
            }
            let got = PointFinder.pointForLevel(pairs, level: num(c["L"])!, grid: Int(num(c["grid"])!))
            XCTAssertEqual(got, num(c["out"]).map { Int($0) }, "L \(num(c["L"])!) grid \(num(c["grid"])!)")
        }
    }

    func testOverridesWin() {
        let map = [FinderPoint(point: 20, channel: "R", level: 30, size: 6), FinderPoint(point: 40, channel: "B", level: 50, size: 4)]
        let s = PointFinder.suggestions(map: map, overrides: ["G50": 45], hdr: false, grid: 5)
        XCTAssertEqual(s["G50"], 45)
    }
}
