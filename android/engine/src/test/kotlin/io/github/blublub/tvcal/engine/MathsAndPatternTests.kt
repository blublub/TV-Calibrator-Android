// Port of CalibrationKit/Tests/CalibrationKitTests/MathsAndPatternTests.swift (TV Calibrator by Slidebean,
// CC BY-NC 4.0). Changed: translated to Kotlin; the AutoCalibration check is left out (not ported yet).
package io.github.blublub.tvcal.engine

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MathsTests {
    @Test
    fun conversionsMatchPython() {
        val f = dict(Fixture.load("maths"))
        val w = ColourMath.xyYToXYZ(ColourMath.d65, 100.0)
        for ((i, item) in list(f["xyz"]).withIndex()) {
            val c = dict(item)
            val xyz = vec3(c["XYZ"])
            val xy = ColourMath.xyzToXY(xyz)
            assertClose(xy, point(c["xy"]), 1e-9, "xy $i")
            assertClose(ColourMath.xyzToLab(xyz, w), vec3(c["lab"]), 1e-9, "lab $i")
            assertClose(ColourMath.whiteDE(xyz), num(c["white_de"]), 1e-9, "white dE $i")
            assertClose(ColourMath.upvp(xy), point(c["upvp"]), 1e-9, "u'v' $i")
            val pol = ColourMath.polar(xy); val want = list(c["polar"])
            assertClose(pol.r, num(want[0]), 1e-9, "polar r $i")
            assertClose(pol.angle, num(want[1]), 1e-9, "polar angle $i")
            assertClose(ColourMath.xyzToRGB709(xyz), vec3(c["rgb709"]), 1e-9, "rgb709 $i")
        }
    }

    @Test
    fun de2000MatchesPython() {
        val f = dict(Fixture.load("maths"))
        for ((i, item) in list(f["de2000"]).withIndex()) {
            val c = dict(item)
            assertClose(ColourMath.de2000(vec3(c["a"]), vec3(c["b"])), num(c["de"]), 1e-9, "dE2000 $i")
        }
    }

    @Test
    fun pqAndMatrices() {
        val f = dict(Fixture.load("maths"))
        for (item in list(f["pq"])) {
            val c = dict(item)
            num(c["nits"])?.let { assertClose(ColourMath.pqEncode(it), num(c["enc"]), 1e-9, "PQ encode $it") }
            num(c["enc_in"])?.let { assertClose(ColourMath.pqDecode(it), num(c["nits_out"]), 1e-9, "PQ decode $it") }
        }
        for (item in list(f["wrap"])) {
            val c = list(item)
            assertClose(ColourMath.wrap(num(c[0])!!), num(c[1]), 1e-12, "wrap")
        }
        val m709 = list(f["m709"]).map { vec3(it) }
        val inv = list(f["m709inv"]).map { vec3(it) }
        val p3 = list(f["p3"]).map { vec3(it) }
        val myP3 = ColourMath.rgbToXYZMatrix(ColourMath.Gamut.p3.primaries)
        for (r in 0 until 3) {
            assertClose(Vec3.of { ColourMath.m709[r][it] }, m709[r], 1e-12, "M709")
            assertClose(Vec3.of { ColourMath.m709Inverse[r][it] }, inv[r], 1e-12, "M709 inverse")
            assertClose(Vec3.of { myP3[r][it] }, p3[r], 1e-12, "P3")
        }
    }

    @Test
    fun pythonRounding() {
        assertEquals(2.0, pyRound(2.5))
        assertEquals(4.0, pyRound(3.5))
        assertEquals(-2.0, pyRound(-2.5))
        assertEquals(0.1, pyRound(0.15, 1))
        assertEquals(0.2, pyRound(0.25, 1))     // exact tie: Python goes to even
    }

    @Test
    fun cStyleFormatting() {
        assertEquals("2", fixed(2.5, 0))          // C rounds the exact value half-even, unlike Java's "%.0f"
        assertEquals("4", fixed(3.5, 0))
        assertEquals("0.1", fixed(0.15, 1))       // 0.15 is really 0.1499999...
        assertEquals("+3.2", signedFixed(3.2, 1))
        assertEquals("-0.0", fixed(-0.01, 1))
        assertEquals("7", gFormat(7.0))
        assertEquals("22.5", gFormat(22.5))
        assertEquals("12", gFormat(12.0))
    }
}

class PatternTests {
    private fun comparePatches(mine: List<Patch>, want: List<kotlinx.serialization.json.JsonElement>, label: String) {
        assertEquals(want.size, mine.size, "$label count")
        for ((p, item) in mine.zip(want)) {
            val w = dict(item)
            assertEquals(str(w["id"]), p.id, label)
            assertEquals(str(w["name"]), p.name, "$label ${p.id} name")
            assertEquals(str(w["kind"]), p.kind.name, "$label ${p.id} kind")
            assertClose(p.level, num(w["level"]), 1e-12, "$label ${p.id} level")
            assertClose(p.hold, num(w["hold"]) ?: Patterns.HOLD, 1e-12, "$label ${p.id} hold")
            w["sig"]?.let { assertClose(p.signal ?: Vec3(-1.0, -1.0, -1.0), vec3(it), 1e-12, "$label ${p.id} signal") }
            w["nits"]?.let { assertClose(p.nits ?: Vec3(-1.0, -1.0, -1.0), vec3(it), 1e-9, "$label ${p.id} nits") }
        }
    }

    @Test
    fun sequencesMatchTheVideos() {
        val f = dict(Fixture.load("sequences"))
        for (hdr in listOf(false, true)) {
            val w = dict(f[if (hdr) "hdr" else "sdr"])
            val seq = Patterns.sequence(hdr)
            comparePatches(seq.patches, list(w["patches"]), if (hdr) "HDR" else "SDR")
            assertEquals(list(w["offsets"]).mapNotNull { num(it) }, seq.offsets)
            assertEquals(num(w["video_length"])!!.toInt(), Patterns.videoLength(hdr))
            val finder = Patterns.finderSequence(hdr)
            comparePatches(finder.patches, list(w["finder"]), if (hdr) "HDR finder" else "SDR finder")
            assertEquals(list(w["finder_offsets"]).mapNotNull { num(it) }, finder.offsets)
            assertEquals(list(w["quick_ids"]).map { it.jsonPrimitive.content }, Patterns.quickIDs(hdr))
            assertEquals(list(w["quick_offsets"]).mapNotNull { num(it) }, Patterns.quickSequence(hdr).offsets)
            val sync = dict(w["sync"])
            val key = if (hdr) "nits" else "sig"
            assertEquals(vec3(dict(sync["low"])[key]).x, seq.syncLow)
            assertEquals(vec3(dict(sync["high"])[key]).x, seq.syncHigh)
            val targets = Patterns.colourTargets(hdr)
            for ((name, item) in dict(w["targets"])) {
                val t = dict(item)
                assertClose(targets[name]!!.xy, point(t["xy"]), 1e-12, "target $name")
                assertClose(targets[name]!!.Yrel, num(t["Yrel"]), 1e-12, "target $name Y")
            }
        }
        for ((mode, files) in dict(f["pack_files"])) {
            val m = SignalMode.fromRaw(mode)!!
            for ((pid, name) in dict(files)) assertEquals(str(name), Patterns.packFile(m, pid))
        }
    }

    @Test
    fun measurementsRoundTrip() {
        val m = Measurements()
        m["W0"] = Vec3(100.0, 105.0, 110.0)
        m.missed["G5"] = MissedPatch("5% GREY", listOf(5.9, 6.1), 7.0)
        val text = engineJson.encodeToString(Measurements.serializer(), m)
        val back = engineJson.decodeFromString(Measurements.serializer(), text)
        assertEquals(m, back)
        val obj = dict(kotlinx.serialization.json.Json.parseToJsonElement(text))
        assertTrue(obj["G5"] is JsonNull)
        assertNotNull(obj["_missed"])
    }
}

class ProfileFormatTests {
    private fun <T> decode(s: kotlinx.serialization.KSerializer<T>, name: String): T =
        engineJson.decodeFromJsonElement(s, Fixture.load(name))

    /** The files build_catalog.py writes must decode in the app. */
    @Test
    fun serverFilesDecode() {
        val index = decode(ProfileCatalog.serializer(), "server_index")
        assertEquals(1, index.format)
        assertEquals(2, index.entry("iPhone18,1")?.version)
        val p = decode(CameraProfile.serializer(), "server_profile")
        assertTrue(p.isValid)
        assertEquals(2.8, p.camera.aperture)
        assertEquals(2, p.stats.tvs.size)
        assertTrue(p.xyz(Vec3(100.0, 100.0, 100.0)).y > 0)
    }

    @Test
    fun cameraSettingsMismatch() {
        val trained = CameraSettings(32.0, "4032x3024 grb4", "Back Camera", 2.8)
        assertTrue(trained.mismatches(trained).isEmpty())
        assertEquals(1, trained.mismatches(CameraSettings(32.0, "4032x3024 grb4", "Back Camera", 1.48)).size)
        assertEquals(1, trained.mismatches(CameraSettings(50.0, "4032x3024 grb4", null, null)).size)
    }

    /** The app's built-in profiles file: an own profile per model, and the standard profile for models that have none. */
    @Test
    fun builtInProfiles() {
        val p = decode(CameraProfile.serializer(), "server_profile")
        val older = p.copy(version = p.version - 1)
        val json = engineJson.encodeToString(BuiltInProfiles.serializer(), BuiltInProfiles(standard = p.model, profiles = listOf(older, p)))
        val b = engineJson.decodeFromString(BuiltInProfiles.serializer(), json)
        assertEquals(p.version, b.profile(p.model)?.version)
        assertNull(b.profile("iPhone99,9"))
        assertEquals(p.model, b.standardProfile?.model)
        val empty = engineJson.decodeFromString(BuiltInProfiles.serializer(), """{"format": 1, "standard": null, "profiles": []}""")
        assertNull(empty.standardProfile)
    }

    /** The real built-in profiles shipped with the iPhone app decode too. */
    @Test
    fun shippedBuiltInProfilesDecode() {
        val f = java.io.File(System.getProperty("fixtures"), "../../../../TV Calibrator/Resources/builtin-profiles.json")
        val b = engineJson.decodeFromString(BuiltInProfiles.serializer(), f.readText())
        assertTrue(b.profiles.isNotEmpty())
        assertNotNull(b.standardProfile)
        assertTrue(b.profiles.all { it.isValid })
    }
}
