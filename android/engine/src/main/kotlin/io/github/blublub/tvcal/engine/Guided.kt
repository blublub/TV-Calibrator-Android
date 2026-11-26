// Port of CalibrationKit/Sources/CalibrationKit/Guided.swift (TV Calibrator by Slidebean,
// https://github.com/slidebean/tv-calibrator, CC BY-NC 4.0). Changed: translated to Kotlin.
package io.github.blublub.tvcal.engine

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sqrt

// Guided calibration: the TV plays one pattern at a time (a 10-minute file from the USB stick), the
// camera reads continuously, and the app says in plain words which control to move and which way.

/** The patches each guided step uses. */
data class GuidePlan(
    /** Bright end (Gain) and dark end (Offset) patches. */
    val gain: String,
    val offset: String,
    /** Greys for the bright / middle / dark check. */
    val check: List<String>,
    /** Greys of the before/after quick check (the colours are added to them). */
    val quick: List<String>,
    /** Greys for multipoint fine-tuning. */
    val fine: List<String>,
) {
    /** The before/after check: the quick greys plus the six colours. */
    val quickWithColours: List<String> get() = quick + Patterns.colourIDs

    companion object {
        fun forHDR(hdr: Boolean): GuidePlan =
            if (hdr) GuidePlan("G65", "G35", listOf("G65", "G50", "G35"), Patterns.quickIDs(true).take(6), listOf("G40", "G50", "G60", "G70"))
            else GuidePlan("W0", "G30", listOf("W0", "G50", "G30"), Patterns.quickIDs(false).take(6), listOf("G20", "G40", "G60", "G80"))
    }
}

/** A colour the TV can't reach with its current primaries: how close it can get. */
data class OutOfReach(
    /** Sliders that would have to go below zero ("R", "G", "B"). */
    val channels: List<String>,
    val bestDE: Double,
)

/** Plain-language state of one pattern: how far off it is, and what to do about it. */
data class LiveReading(
    val id: String,
    val name: String,
    val kind: PatchKind,
    val Y: Double,
    val xy: Point2,
    val uv: Point2,
    val d65uv: Point2,
    // greys and whites
    /** Red and blue relative to green, minus 1 (0 = on target). */
    var qR: Double? = null,
    var qB: Double? = null,
    var de: Double? = null,
    var ok: Boolean = false,
    // colours
    var tuv: Point2? = null,
    var hue: Double? = null,
    var sat: Double? = null,
    var lum: Double? = null,
    var toward: String? = null,
    /** R/G/B slider menus: which slider moves this colour toward its target (share of the main slider). */
    var rgbHint: Vec3? = null,
    /** The hint uses ideal primaries (the Before check didn't measure all three). */
    var rgbApprox: Boolean = false,
    var outOfReach: OutOfReach? = null,
    // filled in by the live session
    var steady: Boolean = false,
    var averaged: Int = 0,
    var need: Int = 3,
    var jitter: Double = 0.0,
    var latestY: Double = 0.0,
    var mismatch: String? = null,
)

object Guided {
    fun liveReading(patch: Patch, xyz: Vec3, hdr: Boolean, whiteY: Double?, basis: List<Vec3>?): LiveReading {
        val xy = ColourMath.xyzToXY(xyz)
        val out = LiveReading(patch.id, patch.name, patch.kind, xyz.y, xy, ColourMath.upvp(xy), ColourMath.upvp(ColourMath.d65))
        if (patch.kind == PatchKind.white || patch.kind == PatchKind.grey) {
            val rgb = ColourMath.xyzToRGB709(xyz)
            val g = if (rgb.y > 0) rgb.y else 1e-9
            val qR = rgb.x / g - 1; val qB = rgb.z / g - 1
            out.qR = qR
            out.qB = qB
            out.de = ColourMath.whiteDE(xyz)
            out.ok = abs(qR) <= 0.012 && abs(qB) <= 0.012
            return out
        }
        val targets = Patterns.colourTargets(hdr)
        val tg = targets[patch.id] ?: return out
        val lvl = Analysis.colourLevel(hdr)
        val wY: Double? = if ((whiteY ?: 0.0) != 0.0) whiteY else if (hdr) 100.0 else null
        val m = ColourMath.polar(xy); val t = ColourMath.polar(tg.xy)
        val hue = ColourMath.wrap(m.angle - t.angle)
        val sat = (m.r / t.r - 1) * 100
        out.tuv = ColourMath.upvp(tg.xy)
        out.hue = hue
        out.sat = sat
        if (wY != null) {
            val expY = wY * tg.Yrel * lvl
            val wn = ColourMath.xyYToXYZ(ColourMath.d65, wY)
            out.lum = (xyz.y / expY - 1) * 100
            out.de = ColourMath.de2000(ColourMath.xyzToLab(xyz, wn), ColourMath.xyzToLab(ColourMath.xyYToXYZ(tg.xy, expY), wn))
        }
        val i = Patterns.hueOrder.indexOf(patch.id)
        if (i >= 0) {
            for (cand in listOf(Patterns.hueOrder[(i + 1) % 6], Patterns.hueOrder[(i + 5) % 6])) {
                val c = targets[cand]
                if (c != null && (ColourMath.wrap(ColourMath.polar(c.xy).angle - t.angle) > 0) == (hue > 0)) out.toward = cand
            }
        }
        val de = out.de
        out.ok = if (de != null) de <= 2.0 else abs(hue) < 2 && abs(sat) < 3
        var b = basis
        if (wY != null && (b == null || b.isEmpty())) {
            // no measured primaries: use the standard ones - the direction of each slider hint is
            // still right, only the size is approximate
            b = listOf("RED", "GREEN", "BLUE").map { ColourMath.xyYToXYZ(targets[it]!!.xy, wY * targets[it]!!.Yrel * lvl) }
            out.rgbApprox = true
        }
        if (b != null && b.size == 3 && wY != null) {
            val mat: Mat3 = List(3) { r -> List(3) { c -> b[c][r] } }
            val targetXYZ = ColourMath.xyYToXYZ(tg.xy, wY * tg.Yrel * lvl)
            val cur = ColourMath.solve3(mat, xyz)
            val tgt = ColourMath.solve3(mat, targetXYZ)
            val peak = maxOf(tgt.x, tgt.y, tgt.z)
            // each change as a share of the colour's main slider, so a tiny trace of green on red
            // doesn't outrank red being 25% too bright
            val hint = (tgt - cur) / maxOf(peak, 1e-6)
            if (hint.x.isFinite() && hint.y.isFinite() && hint.z.isFinite()) {
                out.rgbHint = hint
                // A slider can't go below zero. If the target needs a negative amount of a primary, it
                // lies outside what this TV's red, green and blue can mix: say so, and how close it can get.
                val short = (0 until 3).filter { tgt[it] < -0.02 * peak }
                if (short.isNotEmpty()) {
                    val reach = Vec3(maxOf(0.0, tgt.x), maxOf(0.0, tgt.y), maxOf(0.0, tgt.z))
                    val xyzR = ColourMath.matvec(mat, reach)
                    val wn = ColourMath.xyYToXYZ(ColourMath.d65, wY)
                    val best = ColourMath.de2000(ColourMath.xyzToLab(xyzR, wn), ColourMath.xyzToLab(targetXYZ, wn))
                    out.outOfReach = OutOfReach(short.map { listOf("R", "G", "B")[it] }, pyRound(best, 1))
                }
            }
        }
        return out
    }

    data class Settled(val xyz: Vec3, val count: Int, val wobble: Double)

    /**
     * Averages the readings since the last real change on the TV.
     *
     * Some TVs (Samsung Micro RGB, many mini-LEDs) wobble from reading to reading. Averaging hides the
     * wobble, but a long average would also hide the effect of a click on the remote. So: judge the
     * normal wobble from reading-to-reading steps, find the point where the average clearly shifted (a
     * setting change) and average only the readings after it.
     */
    fun settledAverage(rs: List<Vec3>): Settled {
        val n = rs.size
        if (n == 0) return Settled(Vec3.ZERO, 0, 0.0)
        val cs: List<DoubleArray> = rs.map { doubleArrayOf(it.x / maxOf(it.y, 1e-6), it.z / maxOf(it.y, 1e-6)) }
        val sig = DoubleArray(2)
        for (k in 0 until 2) {
            val d = cs.zipWithNext().map { (a, b) -> abs(a[k] - b[k]) }.sorted()
            var scale = cs.maxOfOrNull { abs(it[k]) } ?: 0.0
            if (scale == 0.0) scale = 1.0
            sig[k] = maxOf(if (d.isEmpty()) 0.0 else d[d.size / 2] / 0.954, 0.0015 * scale, 1e-6)
        }
        var start = 0
        var best = 0.0
        for (m in 2 until n - 1) {          // last m readings vs the ones before them
            val old = cs.subList(0, n - m); val new = cs.subList(n - m, n)
            var t = Double.NEGATIVE_INFINITY
            for (k in 0 until 2) {
                val meanNew = new.sumOf { it[k] } / m
                val meanOld = old.sumOf { it[k] } / old.size
                t = maxOf(t, abs(meanNew - meanOld) / (sig[k] * sqrt(1.0 / m + 1.0 / old.size)))
            }
            if (t > 4.0 && t > best) {
                best = t
                start = n - m
            }
        }
        if (n - start == 1) {
            start = n - 1
        } else if (n >= 2 && start == 0) {
            // a big jump on the very newest reading: show it straight away
            val jump = (0 until 2).maxOf { abs(cs[n - 1][it] - cs[n - 2][it]) / (sig[it] * 1.414) }
            if (jump > 6) start = n - 1
        }
        val tail = (maxOf(start, n - 10) until n).toList()
        val m = tail.size.toDouble()
        val cx = tail.sumOf { cs[it][0] } / m
        val cz = tail.sumOf { cs[it][1] } / m
        val y = tail.sumOf { rs[it].y } / m
        val wobble = (0 until 2).maxOf { sig[it] / maxOf(abs(cs[n - 1][it]), 0.05) }
        return Settled(Vec3(cx * y, y, cz * y), tail.size, pyRound(wobble, 4))
    }

    /** How many averaged readings it takes to call a reading steady, for how jumpy this TV is. */
    fun readingsNeeded(jitter: Double): Int = if (jitter < 0.004) 3 else if (jitter < 0.01) 5 else 8

    /** Does the reading look like the pattern we asked for? Catches the wrong file playing. */
    fun mismatch(patch: Patch, xyz: Vec3, hdr: Boolean, whiteY: Double?): String? {
        if (xyz.y <= 0) return "The camera sees black."
        val r = ColourMath.polar(ColourMath.xyzToXY(xyz)).r
        val neutral = patch.kind == PatchKind.white || patch.kind == PatchKind.grey
        if (neutral && r > 0.05) return "The camera is seeing a colour, not a grey."
        if (patch.kind == PatchKind.colour && r < 0.03) return "The camera is seeing a grey, not a colour."
        val level = patch.level
        if (whiteY != null && whiteY != 0.0 && neutral && level != null) {
            val exp = if (hdr) ColourMath.pqDecode(level) * whiteY / 100.0 else whiteY * level.pow(2.4)
            if (exp > 0 && !(0.4 < xyz.y / exp && xyz.y / exp < 2.5)) return "The brightness doesn't match this pattern."
        }
        return null
    }

    /** Everything the live view needs from the latest readings of one pattern. */
    fun evaluate(patch: Patch, readings: List<Vec3>, hdr: Boolean, whiteY: Double?, basis: List<Vec3>?): LiveReading? {
        val latest = readings.lastOrNull() ?: return null
        val avg = settledAverage(readings)
        val out = liveReading(patch, avg.xyz, hdr, whiteY, basis)
        out.averaged = avg.count
        out.jitter = avg.wobble
        out.need = readingsNeeded(avg.wobble)
        out.steady = out.ok && avg.count >= out.need
        out.latestY = latest.y
        out.mismatch = mismatch(patch, avg.xyz, hdr, whiteY)
        if (out.mismatch != null) {
            out.ok = false
            out.steady = false
        }
        return out
    }
}

// The free-running live meter

data class LiveAnalysis(
    var kind: Kind,
    val Y: Double,
    val xy: Point2,
    val uv: Point2,
    val d65uv: Point2,
    // white
    var bal: Vec3? = null,
    var de: Double? = null,
    var advice: String? = null,
    // colour
    var name: String? = null,
    var sat: Double? = null,
    var hue: Double? = null,
    var tuv: Point2? = null,
) {
    enum class Kind { white, colour }

    companion object {
        /** Reads whatever is on screen: a white (balance + advice) or the nearest target colour. */
        fun of(xyz: Vec3, hdr: Boolean): LiveAnalysis {
            val xy = ColourMath.xyzToXY(xyz)
            val pol = ColourMath.polar(xy)
            val uv = ColourMath.upvp(xy)
            val out = LiveAnalysis(Kind.white, xyz.y, xy, uv, ColourMath.upvp(ColourMath.d65))
            if (pol.r < 0.03) {
                val rgb = ColourMath.xyzToRGB709(xyz)
                val bal = if (xyz.y > 0) rgb / xyz.y else Vec3(1.0, 1.0, 1.0)
                out.bal = bal
                out.de = ColourMath.whiteDE(xyz)
                val vals = listOf(bal.x, bal.y, bal.z)
                val mx = vals.indices.maxByOrNull { vals[it] } ?: 0
                val mn = vals.indices.minByOrNull { vals[it] } ?: 0
                val names = listOf("Red", "Green", "Blue")
                out.advice = if (vals[mx] - vals[mn] <= 0.01) "White is on target."
                else "${names[mx]} is high and ${names[mn]} is low: lower ${names[mx]} or raise ${names[mn]}."
            } else {
                val tg = Patterns.colourTargets(hdr)
                val name = Patterns.colourIDs.minByOrNull { id ->
                    val u = ColourMath.upvp(tg[id]!!.xy)
                    hypot(uv.x - u.x, uv.y - u.y)
                } ?: "RED"
                val t = ColourMath.polar(tg[name]!!.xy)
                out.kind = Kind.colour
                out.name = name
                out.sat = (pol.r / t.r - 1) * 100
                out.hue = ColourMath.wrap(pol.angle - t.angle)
                out.tuv = ColourMath.upvp(tg[name]!!.xy)
            }
            return out
        }
    }
}
