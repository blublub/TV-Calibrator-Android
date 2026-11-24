import XCTest
@testable import CalibrationKit

final class MathsTests: XCTestCase {
    func testConversionsMatchPython() throws {
        let f = dict(try Fixture.load("maths"))
        let W = ColourMath.xyYToXYZ(ColourMath.d65, 100)
        for (i, item) in list(f["xyz"]).enumerated() {
            let c = dict(item)
            let XYZ = vec3(c["XYZ"])
            let xy = ColourMath.xyzToXY(XYZ)
            assertClose(xy, point(c["xy"]), 1e-9, "xy \(i)")
            assertClose(ColourMath.xyzToLab(XYZ, white: W), vec3(c["lab"]), 1e-9, "lab \(i)")
            assertClose(ColourMath.whiteDE(XYZ), num(c["white_de"]), 1e-9, "white dE \(i)")
            assertClose(ColourMath.upvp(xy), point(c["upvp"]), 1e-9, "u'v' \(i)")
            let pol = ColourMath.polar(xy), want = list(c["polar"])
            assertClose(pol.r, num(want[0]), 1e-9, "polar r \(i)")
            assertClose(pol.angle, num(want[1]), 1e-9, "polar angle \(i)")
            assertClose(ColourMath.xyzToRGB709(XYZ), vec3(c["rgb709"]), 1e-9, "rgb709 \(i)")
        }
    }

    func testDE2000MatchesPython() throws {
        let f = dict(try Fixture.load("maths"))
        for (i, item) in list(f["de2000"]).enumerated() {
            let c = dict(item)
            assertClose(ColourMath.de2000(vec3(c["a"]), vec3(c["b"])), num(c["de"]), 1e-9, "dE2000 \(i)")
        }
    }

    func testPQAndMatrices() throws {
        let f = dict(try Fixture.load("maths"))
        for item in list(f["pq"]) {
            let c = dict(item)
            if let n = num(c["nits"]) { assertClose(ColourMath.pqEncode(n), num(c["enc"]), 1e-9, "PQ encode \(n)") }
            if let e = num(c["enc_in"]) { assertClose(ColourMath.pqDecode(e), num(c["nits_out"]), 1e-9, "PQ decode \(e)") }
        }
        for item in list(f["wrap"]) {
            let c = list(item)
            assertClose(ColourMath.wrap(num(c[0])!), num(c[1]), 1e-12, "wrap")
        }
        let m709 = list(f["m709"]).map { vec3($0) }
        let inv = list(f["m709inv"]).map { vec3($0) }
        let p3 = list(f["p3"]).map { vec3($0) }
        let myP3 = ColourMath.rgbToXYZMatrix(ColourMath.Gamut.p3.primaries)
        for r in 0..<3 {
            assertClose(Vec3(ColourMath.m709[r][0], ColourMath.m709[r][1], ColourMath.m709[r][2]), m709[r], 1e-12, "M709")
            assertClose(Vec3(ColourMath.m709Inverse[r][0], ColourMath.m709Inverse[r][1], ColourMath.m709Inverse[r][2]), inv[r],
                        1e-12, "M709 inverse")
            assertClose(Vec3(myP3[r][0], myP3[r][1], myP3[r][2]), p3[r], 1e-12, "P3")
        }
    }

    func testPythonRounding() {
        XCTAssertEqual(pyRound(2.5), 2)
        XCTAssertEqual(pyRound(3.5), 4)
        XCTAssertEqual(pyRound(-2.5), -2)
        XCTAssertEqual(AutoCalibration.clicks(.nan), 0)
    }
}

final class PatternTests: XCTestCase {
    func comparePatches(_ mine: [Patch], _ want: [Any], _ label: String) {
        XCTAssertEqual(mine.count, want.count, "\(label) count")
        for (p, item) in zip(mine, want) {
            let w = dict(item)
            XCTAssertEqual(p.id, w["id"] as? String, label)
            XCTAssertEqual(p.name, w["name"] as? String, "\(label) \(p.id) name")
            XCTAssertEqual(p.kind.rawValue, w["kind"] as? String, "\(label) \(p.id) kind")
            assertClose(p.level, num(w["level"]), 1e-12, "\(label) \(p.id) level")
            assertClose(p.hold, num(w["hold"]) ?? Patterns.hold, 1e-12, "\(label) \(p.id) hold")
            if let s = w["sig"] { assertClose(p.signal ?? Vec3(-1, -1, -1), vec3(s), 1e-12, "\(label) \(p.id) signal") }
            if let n = w["nits"] { assertClose(p.nits ?? Vec3(-1, -1, -1), vec3(n), 1e-9, "\(label) \(p.id) nits") }
        }
    }

    func testSequencesMatchTheVideos() throws {
        let f = dict(try Fixture.load("sequences"))
        for hdr in [false, true] {
            let w = dict(f[hdr ? "hdr" : "sdr"])
            let seq = Patterns.sequence(hdr: hdr)
            comparePatches(seq.patches, list(w["patches"]), hdr ? "HDR" : "SDR")
            XCTAssertEqual(seq.offsets, list(w["offsets"]).compactMap { num($0) })
            XCTAssertEqual(Patterns.videoLength(hdr: hdr), Int(num(w["video_length"])!))
            let finder = Patterns.finderSequence(hdr: hdr)
            comparePatches(finder.patches, list(w["finder"]), hdr ? "HDR finder" : "SDR finder")
            XCTAssertEqual(finder.offsets, list(w["finder_offsets"]).compactMap { num($0) })
            XCTAssertEqual(Patterns.quickIDs(hdr: hdr), list(w["quick_ids"]) as? [String])
            XCTAssertEqual(Patterns.quickSequence(hdr: hdr).offsets, list(w["quick_offsets"]).compactMap { num($0) })
            let sync = dict(w["sync"])
            let low = vec3(dict(sync["low"])[hdr ? "nits" : "sig"]), high = vec3(dict(sync["high"])[hdr ? "nits" : "sig"])
            XCTAssertEqual(seq.syncLow, low.x)
            XCTAssertEqual(seq.syncHigh, high.x)
            let targets = Patterns.colourTargets(hdr: hdr)
            for (name, item) in dict(w["targets"]) {
                let t = dict(item)
                assertClose(targets[name]!.xy, point(t["xy"]), 1e-12, "target \(name)")
                assertClose(targets[name]!.Yrel, num(t["Yrel"]), 1e-12, "target \(name) Y")
            }
        }
        for (mode, files) in dict(f["pack_files"]) {
            let m = SignalMode(rawValue: mode)!
            for (pid, name) in dict(files) {
                XCTAssertEqual(Patterns.packFile(mode: m, id: pid), name as? String)
            }
        }
    }

    func testMeasurementsRoundTrip() throws {
        var m = Measurements()
        m["W0"] = Vec3(100, 105, 110)
        m.missed["G5"] = MissedPatch(name: "5% GREY", readings: [5.9, 6.1], hold: 7)
        let data = try JSONEncoder().encode(m)
        let back = try JSONDecoder().decode(Measurements.self, from: data)
        XCTAssertEqual(back, m)
        let obj = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        XCTAssertTrue(obj["G5"] is NSNull)
        XCTAssertNotNil(obj["_missed"])
    }
}

final class ProfileFormatTests: XCTestCase {
    /// The files ProfileServer/build_catalog.py writes must decode in the app.
    func testServerFilesDecode() throws {
        let index = try Fixture.decode(ProfileCatalog.self, try Fixture.load("server_index"))
        XCTAssertEqual(index.format, 1)
        XCTAssertEqual(index.entry(for: "iPhone18,1")?.version, 2)
        let p = try Fixture.decode(CameraProfile.self, try Fixture.load("server_profile"))
        XCTAssertTrue(p.isValid)
        XCTAssertEqual(p.camera.aperture, 2.8)
        XCTAssertEqual(p.stats.tvs.count, 2)
        let xyz = p.xyz(cameraRGB: Vec3(100, 100, 100))
        XCTAssertGreaterThan(xyz.y, 0)
    }

    func testCameraSettingsMismatch() {
        let trained = CameraSettings(iso: 32, format: "4032x3024 grb4", lens: "Back Camera", aperture: 2.8)
        XCTAssertTrue(trained.mismatches(trained).isEmpty)
        XCTAssertEqual(trained.mismatches(CameraSettings(iso: 32, format: "4032x3024 grb4", lens: "Back Camera", aperture: 1.48)).count, 1)
        XCTAssertEqual(trained.mismatches(CameraSettings(iso: 50, format: "4032x3024 grb4", lens: nil, aperture: nil)).count, 1)
    }
    /// The app's built-in profiles file (build_catalog.py): an own profile per model, and the standard
    /// profile for models that have none.
    func testBuiltInProfiles() throws {
        let p = try Fixture.decode(CameraProfile.self, try Fixture.load("server_profile"))
        var older = p
        older.version = p.version - 1
        let json = try JSONEncoder().encode(BuiltInProfiles(standard: p.model, profiles: [older, p]))
        let b = try JSONDecoder().decode(BuiltInProfiles.self, from: json)
        XCTAssertEqual(b.profile(for: p.model)?.version, p.version)
        XCTAssertNil(b.profile(for: "iPhone99,9"))
        XCTAssertEqual(b.standardProfile?.model, p.model)
        let empty = try JSONDecoder().decode(BuiltInProfiles.self, from: Data(#"{"format": 1, "standard": null, "profiles": []}"#.utf8))
        XCTAssertNil(empty.standardProfile)
    }
}
