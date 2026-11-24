import Foundation

/// X, Y, Z (or R, G, B): three numbers. Encodes as a JSON array [a, b, c], like the Mac app's history files.
public typealias Vec3 = SIMD3<Double>

/// A 3x3 matrix, row-major: m[row][column].
public typealias Mat3 = [[Double]]

/// A point on a 2D chart: CIE xy or u'v'.
public struct Point2: Codable, Equatable, Hashable, Sendable {
    public var x: Double
    public var y: Double
    public init(_ x: Double, _ y: Double) {
        self.x = x
        self.y = y
    }
}

/// Python's round(): halves go to the even neighbour. The engine's click counts use it, so the
/// Swift port must too, or a 2.5-click change would come out as 3 here and 2 on the Mac.
@inlinable public func pyRound(_ x: Double) -> Double { x.rounded(.toNearestOrEven) }

/// Python's round(x, digits).
public func pyRound(_ x: Double, _ digits: Int) -> Double {
    guard x.isFinite else { return x }
    // printf rounds the exact binary value, as Python does (round(0.15, 1) == 0.1)
    return Double(String(format: "%.\(digits)f", x)) ?? x
}

@inlinable public func clamp<T: Comparable>(_ x: T, _ lo: T, _ hi: T) -> T { min(max(x, lo), hi) }

public enum ColourMath {
    public static let d65 = Point2(0.3127, 0.3290)

    // MARK: basic conversions

    public static func xyzToXY(_ v: Vec3) -> Point2 {
        let s = v.x + v.y + v.z
        return s > 0 ? Point2(v.x / s, v.y / s) : Point2(0, 0)
    }

    public static func xyYToXYZ(_ xy: Point2, _ Y: Double) -> Vec3 {
        Vec3(xy.x * Y / xy.y, Y, (1 - xy.x - xy.y) * Y / xy.y)
    }

    /// Solves M v' = v for a 3x3 matrix (Cramer's rule, like the Python engine).
    public static func solve3(_ m: Mat3, _ v: Vec3) -> Vec3 {
        let a = m[0][0], b = m[0][1], c = m[0][2]
        let d = m[1][0], e = m[1][1], f = m[1][2]
        let g = m[2][0], h = m[2][1], i = m[2][2]
        let det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        let inv: Mat3 = [[e * i - f * h, c * h - b * i, b * f - c * e],
                         [f * g - d * i, a * i - c * g, c * d - a * f],
                         [d * h - e * g, b * g - a * h, a * e - b * d]]
        var out = Vec3(0, 0, 0)
        for r in 0..<3 {
            out[r] = (inv[r][0] * v[0] + inv[r][1] * v[1] + inv[r][2] * v[2]) / det
        }
        return out
    }

    public static func matvec(_ m: Mat3, _ v: Vec3) -> Vec3 {
        var out = Vec3(0, 0, 0)
        for r in 0..<3 {
            out[r] = m[r][0] * v[0] + m[r][1] * v[1] + m[r][2] * v[2]
        }
        return out
    }

    /// Columns = the XYZ of each primary, scaled so R+G+B = the white point at Y = 1.
    public static func rgbToXYZMatrix(_ prim: [Point2], white: Point2 = d65) -> Mat3 {
        let cols = prim.map { Vec3($0.x / $0.y, 1.0, (1 - $0.x - $0.y) / $0.y) }
        var P: Mat3 = Array(repeating: Array(repeating: 0, count: 3), count: 3)
        for r in 0..<3 { for c in 0..<3 { P[r][c] = cols[c][r] } }
        let S = solve3(P, Vec3(white.x / white.y, 1.0, (1 - white.x - white.y) / white.y))
        var M = P
        for r in 0..<3 { for c in 0..<3 { M[r][c] = P[r][c] * S[c] } }
        return M
    }

    public static func inverse(_ m: Mat3) -> Mat3 {
        let cols = [Vec3(1, 0, 0), Vec3(0, 1, 0), Vec3(0, 0, 1)].map { solve3(m, $0) }
        return (0..<3).map { r in (0..<3).map { c in cols[c][r] } }
    }

    public enum Gamut: String, Codable, Sendable {
        case rec709, p3, rec2020
        public var primaries: [Point2] {
            switch self {
            case .rec709: return [Point2(0.640, 0.330), Point2(0.300, 0.600), Point2(0.150, 0.060)]
            case .p3: return [Point2(0.680, 0.320), Point2(0.265, 0.690), Point2(0.150, 0.060)]
            case .rec2020: return [Point2(0.708, 0.292), Point2(0.170, 0.797), Point2(0.131, 0.046)]
            }
        }
    }

    public static let m709: Mat3 = rgbToXYZMatrix(Gamut.rec709.primaries)
    public static let m709Inverse: Mat3 = inverse(m709)

    public static func xyzToRGB709(_ v: Vec3) -> Vec3 { matvec(m709Inverse, v) }

    // MARK: perceptual colour difference

    public static func xyzToLab(_ v: Vec3, white: Vec3) -> Vec3 {
        func f(_ t: Double) -> Double {
            t > pow(6.0 / 29, 3) ? pow(t, 1.0 / 3) : t / (3 * pow(6.0 / 29, 2)) + 4.0 / 29
        }
        let fx = f(v.x / white.x), fy = f(v.y / white.y), fz = f(v.z / white.z)
        return Vec3(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }

    private static let radToDeg = 180.0 / Double.pi
    private static let degToRad = Double.pi / 180.0
    private static func deg(_ r: Double) -> Double { r * radToDeg }
    private static func rad(_ d: Double) -> Double { d * degToRad }

    /// Python's % for floats: the result has the sign of the divisor.
    static func pmod(_ a: Double, _ n: Double) -> Double {
        let r = fmod(a, n)
        return r < 0 ? r + n : r
    }

    /// CIEDE2000.
    public static func de2000(_ lab1: Vec3, _ lab2: Vec3) -> Double {
        let L1 = lab1.x, a1 = lab1.y, b1 = lab1.z
        let L2 = lab2.x, a2 = lab2.y, b2 = lab2.z
        let C1 = hypot(a1, b1), C2 = hypot(a2, b2)
        let Cb = (C1 + C2) / 2
        let G = 0.5 * (1 - sqrt(pow(Cb, 7) / (pow(Cb, 7) + pow(25.0, 7))))
        let a1p = (1 + G) * a1, a2p = (1 + G) * a2
        let C1p = hypot(a1p, b1), C2p = hypot(a2p, b2)
        let h1p = pmod(deg(atan2(b1, a1p)), 360)
        let h2p = pmod(deg(atan2(b2, a2p)), 360)
        let dLp = L2 - L1, dCp = C2p - C1p
        var dhp: Double
        if C1p * C2p == 0 {
            dhp = 0
        } else if abs(h2p - h1p) <= 180 {
            dhp = h2p - h1p
        } else if h2p - h1p > 180 {
            dhp = h2p - h1p - 360
        } else {
            dhp = h2p - h1p + 360
        }
        let dHp = 2 * sqrt(C1p * C2p) * sin(rad(dhp / 2))
        let Lbp = (L1 + L2) / 2, Cbp = (C1p + C2p) / 2
        var hbp: Double
        if C1p * C2p == 0 {
            hbp = h1p + h2p
        } else if abs(h1p - h2p) <= 180 {
            hbp = (h1p + h2p) / 2
        } else if h1p + h2p < 360 {
            hbp = (h1p + h2p + 360) / 2
        } else {
            hbp = (h1p + h2p - 360) / 2
        }
        let T = 1 - 0.17 * cos(rad(hbp - 30)) + 0.24 * cos(rad(2 * hbp))
            + 0.32 * cos(rad(3 * hbp + 6)) - 0.20 * cos(rad(4 * hbp - 63))
        let dth = 30 * exp(-pow((hbp - 275) / 25, 2))
        let Rc = 2 * sqrt(pow(Cbp, 7) / (pow(Cbp, 7) + pow(25.0, 7)))
        let Sl = 1 + 0.015 * pow(Lbp - 50, 2) / sqrt(20 + pow(Lbp - 50, 2))
        let Sc = 1 + 0.045 * Cbp, Sh = 1 + 0.015 * Cbp * T
        let Rt = -sin(rad(2 * dth)) * Rc
        let tl = dLp / Sl, tc = dCp / Sc, th = dHp / Sh
        return sqrt(tl * tl + tc * tc + th * th + Rt * tc * th)
    }

    /// White-point error only (brightness normalised away).
    public static func whiteDE(_ v: Vec3) -> Double {
        let Y = v.y
        if Y <= 0 { return .nan }
        let n = Vec3(v.x * 100 / Y, v.y * 100 / Y, v.z * 100 / Y)
        let W = xyYToXYZ(d65, 100)
        return de2000(xyzToLab(n, white: W), Vec3(100, 0, 0))
    }

    // MARK: chromaticity helpers

    public static func upvp(_ xy: Point2) -> Point2 {
        let d = -2 * xy.x + 12 * xy.y + 3
        return Point2(4 * xy.x / d, 9 * xy.y / d)
    }

    /// Distance and angle (degrees) from D65 in u'v'.
    public static func polar(_ xy: Point2) -> (r: Double, angle: Double) {
        let p = upvp(xy), w = upvp(d65)
        return (hypot(p.x - w.x, p.y - w.y), deg(atan2(p.y - w.y, p.x - w.x)))
    }

    /// Angle into -180..<180.
    public static func wrap(_ a: Double) -> Double { pmod(a + 180, 360) - 180 }

    // MARK: HDR (SMPTE ST 2084, "PQ")

    private static let m1 = 2610.0 / 16384, m2 = 2523.0 / 4096 * 128
    private static let c1 = 3424.0 / 4096, c2 = 2413.0 / 4096 * 32, c3 = 2392.0 / 4096 * 32

    public static func pqEncode(_ nits: Double) -> Double {
        let y = pow(clamp(nits / 10000, 0.0, 1.0), m1)
        return pow((c1 + c2 * y) / (1 + c3 * y), m2)
    }

    public static func pqDecode(_ e: Double) -> Double {
        let p = pow(e, 1 / m2)
        return 10000 * pow(max(p - c1, 0) / (c2 - c3 * p), 1 / m1)
    }
}
