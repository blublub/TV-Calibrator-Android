// Port of CalibrationKit/Sources/CalibrationKit/ColourMath.swift (TV Calibrator by Slidebean,
// https://github.com/slidebean/tv-calibrator, CC BY-NC 4.0). Changed: translated to Kotlin.
package io.github.blublub.tvcal.engine

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** X, Y, Z (or R, G, B): three numbers. Encodes as a JSON array [a, b, c]. */
@Serializable(with = Vec3Serializer::class)
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun get(i: Int): Double = when (i) {
        0 -> x
        1 -> y
        2 -> z
        else -> throw IndexOutOfBoundsException("Vec3 index $i")
    }
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    operator fun div(s: Double) = Vec3(x / s, y / s, z / s)
    fun toList() = listOf(x, y, z)

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        fun of(f: (Int) -> Double) = Vec3(f(0), f(1), f(2))
        fun of(a: DoubleArray) = Vec3(a[0], a[1], a[2])
    }
}

object Vec3Serializer : KSerializer<Vec3> {
    private val delegate = ListSerializer(Double.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: Vec3) = delegate.serialize(encoder, value.toList())
    override fun deserialize(decoder: Decoder): Vec3 {
        val l = delegate.deserialize(decoder)
        require(l.size == 3) { "Vec3 needs 3 numbers, got ${l.size}" }
        return Vec3(l[0], l[1], l[2])
    }
}

/** A 3x3 matrix, row-major: m[row][column]. */
typealias Mat3 = List<List<Double>>

/** A point on a 2D chart: CIE xy or u'v'. */
@Serializable
data class Point2(val x: Double, val y: Double)

/** Python's round(): halves go to the even neighbour (2.5 -> 2), as the engine's click counts need. */
fun pyRound(x: Double): Double = Math.rint(x)

/** Python's round(x, digits): rounds the exact binary value (round(0.15, 1) == 0.1). */
fun pyRound(x: Double, digits: Int): Double {
    if (!x.isFinite()) return x
    return BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).toDouble()
}

fun clamp(x: Double, lo: Double, hi: Double): Double = minOf(maxOf(x, lo), hi)

/** C's "%.Nf": the exact binary value rounded half-even, always with a '.' (never the locale's comma). */
fun fixed(x: Double, digits: Int): String {
    if (x.isNaN()) return "nan"
    if (x.isInfinite()) return if (x > 0) "inf" else "-inf"
    val s = BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).toPlainString()
    // C prints "-0" for a negative number that rounds to zero
    return if (x < 0 && !s.startsWith("-")) "-$s" else s
}

/** C's "%+.Nf". */
fun signedFixed(x: Double, digits: Int): String {
    val s = fixed(x, digits)
    return if (s.startsWith("-")) s else "+$s"
}

/** C's "%g" (6 significant digits, trailing zeros removed) for ordinary magnitudes. */
fun gFormat(x: Double): String {
    if (x == 0.0) return "0"
    if (!x.isFinite()) return fixed(x, 0)
    val bd = BigDecimal(x).round(MathContext(6, RoundingMode.HALF_EVEN))
    val exp = bd.precision() - bd.scale() - 1
    if (exp < -4 || exp >= 6) {
        val m = bd.movePointLeft(exp).stripTrailingZeros().toPlainString()
        val e = if (exp < 0) "-" else "+"
        return "${m}e$e${abs(exp).toString().padStart(2, '0')}"
    }
    return bd.stripTrailingZeros().toPlainString()
}

object ColourMath {
    val d65 = Point2(0.3127, 0.3290)

    // basic conversions

    fun xyzToXY(v: Vec3): Point2 {
        val s = v.x + v.y + v.z
        return if (s > 0) Point2(v.x / s, v.y / s) else Point2(0.0, 0.0)
    }

    fun xyYToXYZ(xy: Point2, Y: Double): Vec3 =
        Vec3(xy.x * Y / xy.y, Y, (1 - xy.x - xy.y) * Y / xy.y)

    /** Solves M v' = v for a 3x3 matrix (Cramer's rule, like the Python engine). */
    fun solve3(m: Mat3, v: Vec3): Vec3 {
        val a = m[0][0]; val b = m[0][1]; val c = m[0][2]
        val d = m[1][0]; val e = m[1][1]; val f = m[1][2]
        val g = m[2][0]; val h = m[2][1]; val i = m[2][2]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        val inv = listOf(
            listOf(e * i - f * h, c * h - b * i, b * f - c * e),
            listOf(f * g - d * i, a * i - c * g, c * d - a * f),
            listOf(d * h - e * g, b * g - a * h, a * e - b * d),
        )
        return Vec3.of { r -> (inv[r][0] * v[0] + inv[r][1] * v[1] + inv[r][2] * v[2]) / det }
    }

    fun matvec(m: Mat3, v: Vec3): Vec3 = Vec3.of { r -> m[r][0] * v[0] + m[r][1] * v[1] + m[r][2] * v[2] }

    /** Columns = the XYZ of each primary, scaled so R+G+B = the white point at Y = 1. */
    fun rgbToXYZMatrix(prim: List<Point2>, white: Point2 = d65): Mat3 {
        val cols = prim.map { Vec3(it.x / it.y, 1.0, (1 - it.x - it.y) / it.y) }
        val p: Mat3 = List(3) { r -> List(3) { c -> cols[c][r] } }
        val s = solve3(p, Vec3(white.x / white.y, 1.0, (1 - white.x - white.y) / white.y))
        return List(3) { r -> List(3) { c -> p[r][c] * s[c] } }
    }

    fun inverse(m: Mat3): Mat3 {
        val cols = listOf(Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0)).map { solve3(m, it) }
        return List(3) { r -> List(3) { c -> cols[c][r] } }
    }

    @Serializable
    enum class Gamut {
        rec709, p3, rec2020;

        val primaries: List<Point2>
            get() = when (this) {
                rec709 -> listOf(Point2(0.640, 0.330), Point2(0.300, 0.600), Point2(0.150, 0.060))
                p3 -> listOf(Point2(0.680, 0.320), Point2(0.265, 0.690), Point2(0.150, 0.060))
                rec2020 -> listOf(Point2(0.708, 0.292), Point2(0.170, 0.797), Point2(0.131, 0.046))
            }
    }

    val m709: Mat3 = rgbToXYZMatrix(Gamut.rec709.primaries)
    val m709Inverse: Mat3 = inverse(m709)

    fun xyzToRGB709(v: Vec3): Vec3 = matvec(m709Inverse, v)

    // perceptual colour difference

    fun xyzToLab(v: Vec3, white: Vec3): Vec3 {
        fun f(t: Double): Double =
            if (t > (6.0 / 29).pow(3)) t.pow(1.0 / 3) else t / (3 * (6.0 / 29).pow(2)) + 4.0 / 29
        val fx = f(v.x / white.x); val fy = f(v.y / white.y); val fz = f(v.z / white.z)
        return Vec3(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }

    private const val RAD_TO_DEG = 180.0 / PI
    private const val DEG_TO_RAD = PI / 180.0
    private fun deg(r: Double) = r * RAD_TO_DEG
    private fun rad(d: Double) = d * DEG_TO_RAD

    /** Python's % for floats: the result has the sign of the divisor. */
    fun pmod(a: Double, n: Double): Double {
        val r = a % n
        return if (r < 0) r + n else r
    }

    /** CIEDE2000. */
    fun de2000(lab1: Vec3, lab2: Vec3): Double {
        val L1 = lab1.x; val a1 = lab1.y; val b1 = lab1.z
        val L2 = lab2.x; val a2 = lab2.y; val b2 = lab2.z
        val C1 = hypot(a1, b1); val C2 = hypot(a2, b2)
        val Cb = (C1 + C2) / 2
        val G = 0.5 * (1 - sqrt(Cb.pow(7) / (Cb.pow(7) + 25.0.pow(7))))
        val a1p = (1 + G) * a1; val a2p = (1 + G) * a2
        val C1p = hypot(a1p, b1); val C2p = hypot(a2p, b2)
        val h1p = pmod(deg(atan2(b1, a1p)), 360.0)
        val h2p = pmod(deg(atan2(b2, a2p)), 360.0)
        val dLp = L2 - L1; val dCp = C2p - C1p
        val dhp = when {
            C1p * C2p == 0.0 -> 0.0
            abs(h2p - h1p) <= 180 -> h2p - h1p
            h2p - h1p > 180 -> h2p - h1p - 360
            else -> h2p - h1p + 360
        }
        val dHp = 2 * sqrt(C1p * C2p) * sin(rad(dhp / 2))
        val Lbp = (L1 + L2) / 2; val Cbp = (C1p + C2p) / 2
        val hbp = when {
            C1p * C2p == 0.0 -> h1p + h2p
            abs(h1p - h2p) <= 180 -> (h1p + h2p) / 2
            h1p + h2p < 360 -> (h1p + h2p + 360) / 2
            else -> (h1p + h2p - 360) / 2
        }
        val T = 1 - 0.17 * cos(rad(hbp - 30)) + 0.24 * cos(rad(2 * hbp)) +
            0.32 * cos(rad(3 * hbp + 6)) - 0.20 * cos(rad(4 * hbp - 63))
        val dth = 30 * exp(-((hbp - 275) / 25).pow(2))
        val Rc = 2 * sqrt(Cbp.pow(7) / (Cbp.pow(7) + 25.0.pow(7)))
        val Sl = 1 + 0.015 * (Lbp - 50).pow(2) / sqrt(20 + (Lbp - 50).pow(2))
        val Sc = 1 + 0.045 * Cbp; val Sh = 1 + 0.015 * Cbp * T
        val Rt = -sin(rad(2 * dth)) * Rc
        val tl = dLp / Sl; val tc = dCp / Sc; val th = dHp / Sh
        return sqrt(tl * tl + tc * tc + th * th + Rt * tc * th)
    }

    /** White-point error only (brightness normalised away). */
    fun whiteDE(v: Vec3): Double {
        val Y = v.y
        if (Y <= 0) return Double.NaN
        val n = Vec3(v.x * 100 / Y, v.y * 100 / Y, v.z * 100 / Y)
        val W = xyYToXYZ(d65, 100.0)
        return de2000(xyzToLab(n, W), Vec3(100.0, 0.0, 0.0))
    }

    // chromaticity helpers

    fun upvp(xy: Point2): Point2 {
        val d = -2 * xy.x + 12 * xy.y + 3
        return Point2(4 * xy.x / d, 9 * xy.y / d)
    }

    data class Polar(val r: Double, val angle: Double)

    /** Distance and angle (degrees) from D65 in u'v'. */
    fun polar(xy: Point2): Polar {
        val p = upvp(xy); val w = upvp(d65)
        return Polar(hypot(p.x - w.x, p.y - w.y), deg(atan2(p.y - w.y, p.x - w.x)))
    }

    /** Angle into -180..<180. */
    fun wrap(a: Double): Double = pmod(a + 180, 360.0) - 180

    // HDR (SMPTE ST 2084, "PQ")

    private const val M1 = 2610.0 / 16384
    private const val M2 = 2523.0 / 4096 * 128
    private const val C1 = 3424.0 / 4096
    private const val C2 = 2413.0 / 4096 * 32
    private const val C3 = 2392.0 / 4096 * 32

    fun pqEncode(nits: Double): Double {
        val y = clamp(nits / 10000, 0.0, 1.0).pow(M1)
        return ((C1 + C2 * y) / (1 + C3 * y)).pow(M2)
    }

    fun pqDecode(e: Double): Double {
        val p = e.pow(1 / M2)
        return 10000 * (maxOf(p - C1, 0.0) / (C2 - C3 * p)).pow(1 / M1)
    }
}
