// Port of CalibrationKit/Tests/CalibrationKitTests/EngineTests.swift (TV Calibrator by Slidebean,
// CC BY-NC 4.0). Changed: translated to Kotlin; the AutoCalibration tests (plan.json, the simulated
// calibration rounds) are left out until AutoCalibration is ported.
package io.github.blublub.tvcal.engine

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.fail

class AnalysisTests {
    @Test
    fun analyseMatchesPython() {
        for (item in list(Fixture.load("analyse"))) {
            val c = dict(item)
            val label = str(c["label"]) ?: "?"
            val hdr = bool(c["hdr"]) ?: false
            val meas = Measurements.fromJson(c["meas"]!!)
            val rep = Analysis.analyse(meas, hdr)
            val want = dict(c["report"])
            val greys = list(want["greys"])
            assertEquals(greys.size, rep.greys.size, "$label greys")
            for ((g, wi) in rep.greys.zip(greys)) {
                val w = dict(wi)
                assertEquals(str(w["id"]), g.id, label)
                assertClose(g.Y, num(w["Y"]), 1e-9, "$label ${g.id} Y")
                assertClose(g.targetY, num(w["targetY"]), 1e-9, "$label ${g.id} target")
                assertClose(g.bal, vec3(w["bal"]), 1e-9, "$label ${g.id} balance")
                assertClose(g.de, num(w["de"]), 1e-9, "$label ${g.id} dE")
                assertEquals(bool(w["clipped"]), g.clipped, "$label ${g.id} clipped")
            }
            val cols = list(want["colours"])
            assertEquals(cols.size, rep.colours.size, "$label colours")
            for ((col, wi) in rep.colours.zip(cols)) {
                val w = dict(wi)
                assertEquals(str(w["name"]), col.name, label)
                assertClose(col.sat, num(w["sat"]), 1e-9, "$label ${col.name} sat")
                assertClose(col.hue, num(w["hue"]), 1e-9, "$label ${col.name} hue")
                assertClose(col.lum, num(w["lum"]), 1e-9, "$label ${col.name} lum")
                assertClose(col.de, num(w["de"]), 1e-9, "$label ${col.name} dE")
                assertClose(col.target, vec3(w["target"]), 1e-9, "$label ${col.name} target")
            }
            assertEquals(list(want["warnings"]).size, rep.warnings.size, "$label warnings: ${rep.warnings}")
            assertClose(rep.drift, num(want["drift"]), 1e-9, "$label drift")
            assertClose(rep.peak, num(want["peak"]), 1e-9, "$label peak")
            assertClose(Analysis.greyAverage(rep, hdr), num(want["grey_avg"]), 1e-9, "$label grey avg")
            assertClose(Analysis.greyMax(rep, hdr), num(want["grey_max"]), 1e-9, "$label grey max")
            assertClose(Analysis.colourAverage(rep), num(want["colour_avg"]), 1e-9, "$label colour avg")
        }
    }
}

class SweepTests {
    @Test
    fun syncIsFoundAndEveryPatchMeasured() = runBlocking {
        for (hdr in listOf(false, true)) {
            val tv = SimTV(hdr, seed = 3)
            val seq = Patterns.quickSequence(hdr)
            val meas = Sweep.sweep(SimMeter(tv, seq, playAt = 7.0), seq)
            for (p in seq.patches) {
                val m = meas[p.id]
                assertNotNull(m, "HDR $hdr: ${p.id} missing")
                assertClose(m.y, tv.render(p).y, 0.03, "HDR $hdr: ${p.id} brightness")
            }
            assertFalse(Sweep.outOfOrder(meas, hdr))
        }
    }

    @Test
    fun fullSweepMeasuresEveryPatch() = runBlocking {
        for (hdr in listOf(false, true)) {
            val tv = SimTV(hdr, seed = 11)
            val seq = Patterns.sequence(hdr)
            val meas = Sweep.sweep(SimMeter(tv, seq, seed = 5), seq)
            val missing = seq.patches.filter { meas[it.id] == null && it.id !in meas.missed }
            assertEquals(emptyList(), missing.map { it.id }, "HDR $hdr")
            val rep = Analysis.analyse(meas, hdr)
            assertFalse(Analysis.greyAverage(rep, hdr).isNaN(), "HDR $hdr: grey average")
        }
    }

    @Test
    fun noSyncWithoutAVideo() = runBlocking {
        val tv = SimTV(false)
        val meter = SimMeter(tv, PatternSequence(false, 0.3, 1.0, emptyList()), playAt = 1e9)
        try {
            Sweep.findSync(meter, timeout = 60.0)
            fail("found a sync in black")
        } catch (e: MeasureError) {
            assertSame(MeasureError.NoSync, e)
        }
    }
}

class GuidedTests {
    @Test
    fun settledAverageMatchesPython() {
        val f = dict(Fixture.load("guided"))
        for ((i, item) in list(f["settled"]).withIndex()) {
            val c = dict(item)
            val rs = list(c["readings"]).map { vec3(it) }
            val out = Guided.settledAverage(rs)
            assertClose(out.xyz, vec3(c["xyz"]), 1e-9, "settled $i XYZ")
            assertEquals(num(c["n"])!!.toInt(), out.count, "settled $i count")
            assertClose(out.wobble, num(c["wobble"]), 1e-9, "settled $i wobble")
        }
    }

    @Test
    fun liveReadingMatchesPython() {
        val f = dict(Fixture.load("guided"))
        for ((i, item) in list(f["live"]).withIndex()) {
            val c = dict(item)
            val hdr = bool(c["hdr"]) ?: false
            val p = Patterns.patch(hdr, str(c["patch_id"])!!)!!
            val basis = if (c["basis"] == null || c["basis"] is JsonNull) null else list(c["basis"]).map { vec3(it) }
            val r = Guided.liveReading(p, vec3(c["XYZ"]), hdr, num(c["white_Y"]), basis)
            val w = dict(c["out"])
            val label = "live $i ${p.id}"
            assertClose(r.uv, point(w["uv"]), 1e-9, label)
            assertEquals(bool(w["ok"]), r.ok, "$label ok")
            assertClose(r.de, num(w["de"]), 1e-9, "$label dE")
            (w["q"] as? JsonObject)?.let { q ->
                assertClose(r.qR, num(q["R"]), 1e-9, "$label qR")
                assertClose(r.qB, num(q["B"]), 1e-9, "$label qB")
            }
            if (p.kind == PatchKind.colour) {
                assertClose(r.hue, num(w["hue"]), 1e-9, "$label hue")
                assertClose(r.sat, num(w["sat"]), 1e-9, "$label sat")
                assertClose(r.lum, num(w["lum"]), 1e-9, "$label lum")
                assertEquals(str(w["toward"]), r.toward, "$label toward")
                assertEquals(bool(w["rgb_approx"]) ?: false, r.rgbApprox, "$label approx")
                val h = w["rgb_hint"]
                if (h != null && h !is JsonNull) {
                    assertClose(r.rgbHint ?: Vec3(Double.NaN, Double.NaN, Double.NaN), vec3(h), 1e-9, "$label hint")
                } else {
                    assertNull(r.rgbHint, "$label hint")
                }
                val o = w["out_of_reach"] as? JsonObject
                if (o != null) {
                    assertEquals(list(o["channels"]).map { it.jsonPrimitive.content }, r.outOfReach?.channels, "$label out of reach")
                    assertClose(r.outOfReach?.bestDE, num(o["best_de"]), 1e-9, "$label best dE")
                } else {
                    assertNull(r.outOfReach, "$label out of reach")
                }
            }
        }
    }

    @Test
    fun mismatchMatchesPython() {
        val f = dict(Fixture.load("guided"))
        for ((i, item) in list(f["mismatch"]).withIndex()) {
            val c = dict(item)
            val hdr = bool(c["hdr"]) ?: false
            val p = Patterns.patch(hdr, str(c["patch_id"])!!)!!
            val got = Guided.mismatch(p, vec3(c["XYZ"]), hdr, num(c["white_Y"]))
            val want = str(c["out"])
            assertEquals(want == null, got == null, "mismatch $i ${p.id}: $got vs $want")
        }
    }

    @Test
    fun liveAnalysisMatchesPython() {
        val f = dict(Fixture.load("guided"))
        for (item in list(f["live_analysis"])) {
            val c = dict(item)
            val r = LiveAnalysis.of(vec3(c["XYZ"]), bool(c["hdr"]) ?: false)
            val w = dict(c["out"])
            assertEquals(str(w["kind"]), if (r.kind == LiveAnalysis.Kind.white) "white" else "colour")
            if (r.kind == LiveAnalysis.Kind.white) {
                assertEquals(str(w["advice"]), r.advice)
                assertClose(r.de, num(w["de"]), 1e-9)
            } else {
                assertEquals(str(w["name"]), r.name)
                assertClose(r.hue, num(w["hue"]), 1e-9)
                assertClose(r.sat, num(w["sat"]), 1e-9)
            }
        }
    }
}

class PointFinderTests {
    @Test
    fun finderMapMatchesPython() {
        val f = dict(Fixture.load("finder"))
        for ((i, item) in list(f["cases"]).withIndex()) {
            val c = dict(item)
            val hdr = bool(c["hdr"]) ?: false
            val grid = num(c["grid"])!!.toInt()
            val base = Measurements.fromJson(c["base"]!!)
            val nudged = Measurements.fromJson(c["nudged"]!!)
            val map = PointFinder.map(base, nudged, hdr)
            val want = list(c["map"]).map { dict(it) }
            assertEquals(want.size, map.size, "finder $i points")
            for ((m, w) in map.zip(want)) {
                assertEquals(num(w["point"])!!.toInt(), m.point, "finder $i")
                assertEquals(str(w["channel"]), m.channel, "finder $i channel")
                assertClose(m.level, num(w["level"]), 1e-9, "finder $i level")
                assertClose(m.size, num(w["size"]), 1e-9, "finder $i size")
            }
            val sugg = PointFinder.suggestions(map, emptyMap(), hdr, grid)
            for ((pid, v) in dict(c["suggest"])) {
                assertEquals(num(v)?.toInt(), sugg[pid], "finder $i suggestion $pid")
            }
        }
    }

    @Test
    fun pointForLevelMatchesPython() {
        val f = dict(Fixture.load("finder"))
        for (item in list(f["point_for_level"])) {
            val c = dict(item)
            val pairs = list(c["pairs"]).map {
                val d = dict(it)
                FinderPoint(num(d["point"])!!.toInt(), "R", num(d["level"])!!, 1.0)
            }
            val got = PointFinder.pointForLevel(pairs, num(c["L"])!!, num(c["grid"])!!.toInt())
            assertEquals(num(c["out"])?.toInt(), got, "L ${num(c["L"])} grid ${num(c["grid"])}")
        }
    }

    @Test
    fun overridesWin() {
        val map = listOf(FinderPoint(20, "R", 30.0, 6.0), FinderPoint(40, "B", 50.0, 4.0))
        val s = PointFinder.suggestions(map, mapOf("G50" to 45), false, 5)
        assertEquals(45, s["G50"])
    }
}
