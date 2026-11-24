import CalibrationKit
import Charts
import SwiftUI

// The measurement visuals, from the Figma file (pages 02 Components and 04 Measurement visuals):
// score tiles, centred balance bars, the white-point target, the colour chart and the greyscale chart.

// MARK: - Score tile

/// A result tile. dE scores (`digits` 1) show a word ("Very good", green "Invisible") with the dE beside it
/// and "was 4.8" at the top right; other numbers (gamma, HDR brightness) show the number big with
/// "target 2.40 · was 2.18" beside it.
struct ScoreTile: View {
    let label: String
    let value: Double?
    var was: Double?
    var digits = 1
    /// dE tiles: replaces the score word. Number tiles: the line beside the number ("target 2.40").
    var caption: String?
    /// Number tiles: appended to the number and to "was", e.g. "%".
    var unit = ""

    enum Kind { case grey, colour }
    /// Greys and colours. Colours get wider bands: a good TV rarely matches every colour exactly, and
    /// an iPhone isn't a professional meter.
    var kind: Kind = .grey

    /// Upper limits for Perfect, Excellent, Very good and Good; above the last is "Noticeably off".
    static func bands(_ kind: Kind) -> [Double] { kind == .grey ? [1, 2, 4, 7] : [1, 3, 6, 10] }

    /// The word for a dE.
    static func word(_ de: Double?, kind: Kind = .grey) -> String {
        guard let de, de.isFinite else { return "–" }
        let b = bands(kind)
        return de < b[0] ? "Perfect" : de < b[1] ? "Excellent" : de < b[2] ? "Very good" : de < b[3] ? "Good" : "Noticeably off"
    }

    /// Green up to Excellent for greys and Very good for colours, white for Good, orange beyond.
    static func tint(_ de: Double?, kind: Kind = .grey) -> Color {
        guard let de, de.isFinite else { return Theme.textSecondary }
        let b = bands(kind)
        return de < b[kind == .grey ? 1 : 2] ? Theme.green : de < b[3] ? .white : Theme.limit
    }

    private var hasValue: Bool { value.map { $0.isFinite } ?? false }
    private var hasWas: Bool { was.map { $0.isFinite } ?? false }

    var body: some View {
        Card(spacing: 12) {
            if digits == 1 {
                scoreContent
            } else {
                numberContent
            }
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder private var scoreContent: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text(label)
                .font(.captionBrand)
                .secondaryText()
                .frame(maxWidth: .infinity, alignment: .leading)
            if hasWas {
                Text("was \(fmt(was, digits))")
                    .font(.small)
                    .secondaryText()
            }
        }
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Text(caption ?? ScoreTile.word(value, kind: kind))
                .font(.brand(27, .regular, relativeTo: .title))
                .tracking(-0.7)
                .foregroundStyle(ScoreTile.tint(value, kind: kind))
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            if hasValue {
                Text("\(fmt(value, digits)) dE")
                    .font(.callout14)
                    .secondaryText()
            }
        }
    }

    private var detail: String {
        var parts: [String] = []
        if let caption { parts.append(caption) }
        if hasWas { parts.append("was " + fmt(was, digits) + unit) }
        return parts.joined(separator: " · ")
    }

    @ViewBuilder private var numberContent: some View {
        Text(label)
            .font(.captionBrand)
            .secondaryText()
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text(fmt(value, digits) + (hasValue ? unit : ""))
                .font(.reading(34))
                .foregroundStyle(.white)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            Spacer(minLength: 8)
            if !detail.isEmpty {
                Text(detail)
                    .font(.captionBrand)
                    .secondaryText()
                    .multilineTextAlignment(.trailing)
            }
        }
    }
}

// MARK: - Balance bar

/// One channel: its name and a big reading, over a centred track. Left of the white target line is
/// "too little", right is "too much"; the green zone is the tolerance. Within tolerance the reading
/// turns green with a tick and the bar shrinks to a green pip on the line.
struct BalanceBar: View {
    let label: String
    let value: Double?           // fraction, e.g. 0.03 = 3% high
    let colour: Color
    var fullScale = 0.05         // this much fills half the bar
    var tolerance = 0.012
    var valueText: String?
    /// The reading's size (the design uses 34 and 38).
    var readingSize: CGFloat = 34

    /// The value when it's a real number.
    private var v: Double? { value.flatMap { $0.isFinite ? $0 : nil } }

    private var onTarget: Bool { v.map { abs($0) <= tolerance } ?? false }

    private var reading: String {
        if let valueText { return valueText }
        guard let v else { return "–" }
        return String(format: "%+.1f%%", v * 100).replacingOccurrences(of: "-", with: "\u{2212}")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack(alignment: .center, spacing: 8) {
                Text(label)
                    .font(.headlineBrand)
                    .foregroundStyle(.white)
                    .lineLimit(1)
                Spacer(minLength: 8)
                HStack(spacing: 6) {
                    if onTarget {
                        Image(systemName: "checkmark")
                            .font(.brand(readingSize * 0.62, .semibold, relativeTo: .largeTitle))
                    }
                    Text(reading)
                        .font(.reading(readingSize))
                        .tracking(-1)
                }
                .foregroundStyle(onTarget ? Theme.green : Color.white)
                .lineLimit(1)
                .minimumScaleFactor(0.5)
            }
            track
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(label): \(reading)\(onTarget ? ", on target" : "")")
    }

    private var track: some View {
        GeometryReader { geo in
            let mid = geo.size.width / 2
            let off = CGFloat(clamp((v ?? 0) / fullScale, -1, 1)) * mid
            let tol = min(max(CGFloat(tolerance / fullScale) * mid, 6), mid)
            let len = onTarget ? 8 : max(abs(off), 4)
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: 6)
                    .fill(Color.white.opacity(0.07))
                Rectangle()
                    .fill(Theme.green.opacity(0.16))
                    .frame(width: tol * 2)
                    .offset(x: mid - tol)
                if v != nil {
                    RoundedRectangle(cornerRadius: 4)
                        .fill(onTarget ? Theme.green : colour)
                        .frame(width: len, height: 10)
                        .offset(x: onTarget ? mid - len / 2 : (off < 0 ? mid - len : mid))
                }
                Rectangle()
                    .fill(Color.white)
                    .frame(width: 2)
                    .offset(x: mid - 1)
            }
        }
        .frame(height: 20)
        .clipShape(RoundedRectangle(cornerRadius: 6))
    }
}

/// The labels under a set of balance bars: "Too little · On target · Too much".
struct BalanceScaleLabels: View {
    var body: some View {
        HStack {
            Text("Too little").secondaryText()
            Spacer()
            Text("On target").foregroundStyle(Theme.green)
            Spacer()
            Text("Too much").secondaryText()
        }
        .font(.tiny)
        .accessibilityHidden(true)
    }
}

// MARK: - White-point target

/// The white point against D65, in u'v'. The teal inner ring is the target zone; the dot is the TV's
/// white: yellow while off, green with a tick once inside.
/// The app icon's colours as a wheel around white: each hue lies the way that colour lies from the white
/// point (red toward the red primary, cyan directly opposite it, and so on). It tints the inside of the
/// colour triangle and the ring around the D65 target, so a position reads as a colour at a glance.
enum HueWheel {
    private static let red = Color(red: 1, green: 0.10, blue: 0.10)
    private static let green = Color(red: 0.13, green: 1, blue: 0.24)
    private static let blue = Color(red: 0.23, green: 0.17, blue: 1)
    private static let cyan = Color(red: 0, green: 0.88, blue: 1)
    private static let magenta = Color(red: 1, green: 0.06, blue: 0.48)
    private static let yellow = Color(red: 1, green: 0.93, blue: 0)

    /// `corners`: the red, green and blue primaries on screen; `white`: the white point on screen.
    /// A primary's opposite colour (cyan for red) lies straight across white from it.
    static func shading(corners: [CGPoint], white: CGPoint) -> GraphicsContext.Shading {
        guard corners.count == 3 else { return .color(.clear) }
        let twoPi = 2 * Double.pi
        func angle(_ p: CGPoint) -> Double {
            let a = atan2(Double(p.y - white.y), Double(p.x - white.x))
            return a < 0 ? a + twoPi : a
        }
        func across(_ a: Double) -> Double { a + .pi >= twoPi ? a - .pi : a + .pi }
        let r = angle(corners[0]), g = angle(corners[1]), b = angle(corners[2])
        var hues: [(a: Double, c: Color)] = [(a: r, c: red), (a: across(r), c: cyan), (a: g, c: green),
                                             (a: across(g), c: magenta), (a: b, c: blue), (a: across(b), c: yellow)]
        hues.sort { $0.a < $1.a }
        // Start the wheel at its first colour, and come back round to it.
        let first = hues[0]
        var stops = hues.map { Gradient.Stop(color: $0.c, location: CGFloat(($0.a - first.a) / twoPi)) }
        stops.append(Gradient.Stop(color: first.c, location: 1))
        return .conicGradient(Gradient(stops: stops), center: white, angle: .radians(first.a))
    }
}

struct WhiteTarget: View {
    let uv: Point2
    let d65: Point2
    let ok: Bool
    /// Where the white was before calibrating: a hollow dot, joined to where it is now.
    var was: Point2? = nil

    /// Inner ring radius in u'v' (about dE 2); the outer ring is the design's 2x.
    private static let inner = 0.0045
    private static let outer = inner * 130 / 64.24

    var body: some View {
        Canvas { ctx, size in
            let side = min(size.width, size.height)
            let c = CGPoint(x: size.width / 2, y: size.height / 2)
            let outerR = side / 2 * (130 / 146)
            let sc = outerR / CGFloat(WhiteTarget.outer)
            let innerR = CGFloat(WhiteTarget.inner) * sc

            func circle(_ p: CGPoint, _ r: CGFloat) -> Path {
                Path(ellipseIn: CGRect(x: p.x - r, y: p.y - r, width: r * 2, height: r * 2))
            }

            // Between the rings, a faint tint of the colour that lies each way from white; at the centre,
            // the icon's white hot spot.
            let primaries = ColourMath.Gamut.rec709.primaries.map { ColourMath.upvp($0) }.map {
                CGPoint(x: c.x + CGFloat($0.x - d65.x) * sc, y: c.y - CGFloat($0.y - d65.y) * sc)
            }
            var band = circle(c, outerR - 2)
            band.addPath(circle(c, innerR + 2))
            ctx.drawLayer { l in
                l.opacity = 0.24
                l.addFilter(.blur(radius: max(3, side * 0.04)))
                l.fill(band, with: HueWheel.shading(corners: primaries, white: c), style: FillStyle(eoFill: true))
            }
            ctx.fill(circle(c, innerR * 1.5),
                     with: .radialGradient(Gradient(colors: [.white.opacity(0.6), .white.opacity(0.18), .white.opacity(0)]),
                                           center: c, startRadius: 0, endRadius: innerR * 1.5))

            ctx.stroke(circle(c, outerR), with: .color(.white.opacity(0.2)), lineWidth: 1)
            ctx.stroke(circle(c, innerR), with: .color(Theme.tealBright), lineWidth: 1.5)

            var cross = Path()
            cross.move(to: CGPoint(x: c.x - 10, y: c.y))
            cross.addLine(to: CGPoint(x: c.x + 10, y: c.y))
            cross.move(to: CGPoint(x: c.x, y: c.y - 10))
            cross.addLine(to: CGPoint(x: c.x, y: c.y + 10))
            ctx.stroke(cross, with: .color(.white.opacity(0.6)), lineWidth: 1)

            // a white, kept inside the outer ring so it never leaves the picture
            func place(_ p: Point2) -> CGPoint {
                var dx = CGFloat(p.x - d65.x) * sc
                var dy = -CGFloat(p.y - d65.y) * sc
                let dist = (dx * dx + dy * dy).squareRoot()
                if dist > outerR, dist > 0 {
                    dx *= outerR / dist
                    dy *= outerR / dist
                }
                return CGPoint(x: c.x + dx, y: c.y + dy)
            }
            let m = place(uv)
            let dotR = max(5, side * 5 / 146)
            if let was {
                let b = place(was)
                var trail = Path()
                trail.move(to: b)
                trail.addLine(to: m)
                ctx.stroke(trail, with: .color(.white.opacity(0.45)), lineWidth: 1.2)
                ctx.fill(circle(b, dotR * 0.8), with: .color(Theme.background))
                ctx.stroke(circle(b, dotR * 0.8), with: .color(.white.opacity(0.75)), lineWidth: 1.3)
            }
            if ok {
                // on target: the dot blooms
                ctx.drawLayer { l in
                    l.addFilter(.blur(radius: dotR))
                    l.fill(circle(m, dotR * 1.8), with: .color(Theme.green.opacity(0.85)))
                }
            }
            ctx.fill(circle(m, dotR), with: .color(ok ? Theme.green : Theme.accent))

            if ok {
                var tick = Path()
                let t = CGPoint(x: m.x + dotR + 4, y: m.y - 2)
                tick.move(to: CGPoint(x: t.x, y: t.y))
                tick.addLine(to: CGPoint(x: t.x + 3, y: t.y + 3))
                tick.addLine(to: CGPoint(x: t.x + 9, y: t.y - 4))
                ctx.stroke(tick, with: .color(Theme.green),
                           style: StrokeStyle(lineWidth: 1.6, lineCap: .round, lineJoin: .round))
            }
        }
        .overlay(alignment: .bottom) {
            Text("D65")
                .font(.micro)
                .secondaryText()
                .padding(.bottom, 1)
        }
        .aspectRatio(1, contentMode: .fit)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(ok ? "White is on target" : "White is off target")
    }
}

// MARK: - Colour chart

/// The colours in CIE 1976 u'v', over the real Rec.709 (SDR) or P3 (HDR) gamut triangle: rings are the
/// targets, dots are the TV, a line joins each pair, and a green tick marks a colour that's on target.
/// `highlight` (a colour id, e.g. "RED") brings the current colour forward and dims the rest.
struct GamutChart: View {
    struct Dot: Identifiable {
        let id: String
        let uv: Point2
        let target: Point2
        /// How far off (dE), when known: under 2 gets the on-target tick.
        var de: Double? = nil

        var onTarget: Bool {
            if let de, de.isFinite { return de < 3 }
            let du = uv.x - target.x, dv = uv.y - target.y
            return (du * du + dv * dv).squareRoot() < 0.004
        }
    }

    let dots: [Dot]
    let hdr: Bool
    var highlight: String? = nil
    /// Where each colour was before calibrating: a small hollow dot, joined to where it is now.
    var before: [Dot] = []

    /// The design's colour for each patch.
    static func colour(_ id: String) -> Color {
        switch id.uppercased() {
        case "RED": return Theme.red
        case "GREEN": return Theme.green
        case "BLUE": return Theme.blue
        case "YELLOW": return Theme.accent
        case "MAGENTA": return Theme.dolbyVision
        default: return Theme.swatch(id.uppercased())
        }
    }

    private var active: String? { highlight ?? (dots.count == 1 ? dots.first?.id : nil) }

    var body: some View {
        Canvas { ctx, size in
            let gamut = (hdr ? ColourMath.Gamut.p3 : .rec709).primaries.map { ColourMath.upvp($0) }
            let fit = gamut + dots.map { $0.target }
            let uMin = fit.map { $0.x }.min() ?? 0, uMax = fit.map { $0.x }.max() ?? 1
            let vMin = fit.map { $0.y }.min() ?? 0, vMax = fit.map { $0.y }.max() ?? 1
            let pad: CGFloat = 16
            // one scale for both axes, so the triangle keeps its true shape
            let sc = min((size.width - pad * 2) / CGFloat(max(uMax - uMin, 0.01)),
                         (size.height - pad * 2) / CGFloat(max(vMax - vMin, 0.01)))
            let ox = (size.width - CGFloat(uMax - uMin) * sc) / 2
            let oy = (size.height - CGFloat(vMax - vMin) * sc) / 2

            func P(_ p: Point2) -> CGPoint {
                CGPoint(x: ox + CGFloat(p.x - uMin) * sc, y: size.height - oy - CGFloat(p.y - vMin) * sc)
            }
            func inside(_ p: CGPoint) -> CGPoint {
                CGPoint(x: clamp(p.x, 6, size.width - 6), y: clamp(p.y, 6, size.height - 6))
            }
            func circle(_ p: CGPoint, _ r: CGFloat) -> Path {
                Path(ellipseIn: CGRect(x: p.x - r, y: p.y - r, width: r * 2, height: r * 2))
            }
            /// Where the line from `v` through `w` meets the line through `a` and `b`.
            func hit(_ v: CGPoint, _ w: CGPoint, _ a: CGPoint, _ b: CGPoint) -> CGPoint {
                let d1 = CGPoint(x: w.x - v.x, y: w.y - v.y), d2 = CGPoint(x: b.x - a.x, y: b.y - a.y)
                let den = d1.x * d2.y - d1.y * d2.x
                guard abs(den) > 0.000001 else { return w }
                let t = ((a.x - v.x) * d2.y - (a.y - v.y) * d2.x) / den
                return CGPoint(x: v.x + d1.x * t, y: v.y + d1.y * t)
            }

            // the gamut triangle, and a line from each primary through white to the opposite side
            let corners = gamut.map { P($0) }
            var tri = Path()
            tri.addLines(corners)
            tri.closeSubpath()
            let white = P(ColourMath.upvp(ColourMath.d65))
            // Inside the triangle, the icon's colours: each hue toward its own corner, white in the middle.
            if corners.count == 3 {
                let wheel = HueWheel.shading(corners: corners, white: white)
                ctx.drawLayer { l in
                    l.opacity = active == nil ? 0.3 : 0.2
                    l.clip(to: tri)
                    l.fill(tri, with: wheel)
                    l.fill(tri, with: .radialGradient(Gradient(colors: [.white.opacity(0.9), .white.opacity(0)]),
                                                      center: white, startRadius: 0,
                                                      endRadius: min(size.width, size.height) * 0.3))
                }
            }
            // which triangle this is
            ctx.draw(Text(hdr ? "P3 (HDR)" : "Rec. 709 (SDR)").font(.micro).foregroundStyle(Theme.textSecondary),
                     at: CGPoint(x: size.width - 2, y: size.height - 2), anchor: .bottomTrailing)
            var medians = Path()
            if corners.count == 3 {
                for i in 0..<3 {
                    medians.move(to: corners[i])
                    medians.addLine(to: hit(corners[i], white, corners[(i + 1) % 3], corners[(i + 2) % 3]))
                }
            }
            ctx.stroke(medians, with: .color(.white.opacity(0.13)), lineWidth: 1)
            ctx.stroke(tri, with: .color(.white.opacity(0.45)), lineWidth: 1)

            // the current colour last, on top
            let order = dots.filter { $0.id != active } + dots.filter { $0.id == active }
            for d in order {
                let col = GamutChart.colour(d.id)
                let isActive = d.id == active
                let dim = active != nil && !isActive
                let t = P(d.target), m = inside(P(d.uv))
                let ringR: CGFloat = isActive ? 9 : 8
                if d.onTarget && !dim {
                    // on target: the ring blooms in its colour
                    ctx.drawLayer { l in
                        l.addFilter(.blur(radius: 6))
                        l.fill(circle(t, ringR + 3), with: .color(col.opacity(0.8)))
                    }
                    ctx.fill(circle(t, ringR), with: .color(col.opacity(0.2)))
                }
                ctx.stroke(circle(t, ringR), with: .color(col.opacity(dim ? 0.45 : 0.9)),
                           lineWidth: isActive ? 2 : 1.2)
                var line = Path()
                line.move(to: t)
                line.addLine(to: m)
                ctx.stroke(line, with: .color(col.opacity(dim ? 0.5 : 1)), lineWidth: isActive ? 1.5 : 1)
                if let was = before.first(where: { $0.id == d.id }) {
                    let b = inside(P(was.uv))
                    var trail = Path()
                    trail.move(to: b)
                    trail.addLine(to: m)
                    ctx.stroke(trail, with: .color(col.opacity(0.45)), style: StrokeStyle(lineWidth: 1, dash: [3, 3]))
                    ctx.fill(circle(b, 3), with: .color(Theme.background))
                    ctx.stroke(circle(b, 3), with: .color(col.opacity(0.8)), lineWidth: 1.2)
                }
                ctx.fill(circle(m, isActive ? 5 : 4), with: .color(col.opacity(dim ? 0.65 : 1)))
                if d.onTarget {
                    var tick = Path()
                    let k = CGPoint(x: t.x + ringR + 3, y: t.y - 3)
                    tick.move(to: k)
                    tick.addLine(to: CGPoint(x: k.x + 2.5, y: k.y + 2.5))
                    tick.addLine(to: CGPoint(x: k.x + 7.5, y: k.y - 3))
                    ctx.stroke(tick, with: .color(Theme.green),
                               style: StrokeStyle(lineWidth: 1.4, lineCap: .round, lineJoin: .round))
                }
            }
        }
        .aspectRatio(1.4, contentMode: .fit)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilitySummary)
    }

    private var accessibilitySummary: String {
        guard !dots.isEmpty else { return "Colour chart" }
        let ok = dots.filter { $0.onTarget }.count
        if dots.count == 1, let d = dots.first {
            return "Colour chart: \(d.id.capitalized) is \(d.onTarget ? "on target" : "off target")"
        }
        return "Colour chart: \(ok) of \(dots.count) colours on target"
    }

    static func dots(_ rep: Report) -> [Dot] {
        rep.colours.map { Dot(id: $0.name, uv: ColourMath.upvp($0.xy), target: ColourMath.upvp($0.txy), de: $0.de) }
    }
}

// MARK: - Greyscale chart

/// Red, green and blue in each grey (100 = on target, the white line). With a `before`, its traces sit
/// faded behind the solid after traces.
struct GreyBalanceChart: View {
    let report: Report
    var before: Report?
    let hdr: Bool

    struct Point: Identifiable {
        let id = UUID()
        let level: Double
        let value: Double
        let channel: String      // "R", "G" or "B"
        let series: String
    }

    var points: [Point] {
        func pts(_ rep: Report, _ series: String) -> [Point] {
            rep.greys.filter { hdr || $0.level >= 0.099 }.flatMap { g in
                [Point(level: g.level * 100, value: g.bal.x * 100, channel: "R", series: series + "R"),
                 Point(level: g.level * 100, value: g.bal.y * 100, channel: "G", series: series + "G"),
                 Point(level: g.level * 100, value: g.bal.z * 100, channel: "B", series: series + "B")]
            }
        }
        // before first, so the after traces draw on top
        return (before.map { pts($0, "was") } ?? []) + pts(report, "now")
    }

    var body: some View {
        let pts = points.filter { $0.value.isFinite && $0.level.isFinite }
        let dev = pts.map { abs($0.value - 100) }.max() ?? 0
        let grid = max(5, (dev / 5).rounded(.up) * 5)          // the faint lines: ±grid %
        let span = grid * 1.45
        let lo = pts.map { $0.level }.min() ?? 0
        let hi = max(pts.map { $0.level }.max() ?? 100, lo + 1)
        let ticks = (hdr ? [0.0, 25, 50, 75, 100] : [10.0, 30, 50, 70, 100]).filter { $0 >= lo - 0.5 && $0 <= hi + 0.5 }

        Chart {
            RuleMark(y: .value("Level", 100 + grid))
                .foregroundStyle(Theme.hairline)
                .lineStyle(StrokeStyle(lineWidth: 1))
            RuleMark(y: .value("Level", 100 - grid))
                .foregroundStyle(Theme.hairline)
                .lineStyle(StrokeStyle(lineWidth: 1))
            ForEach(pts) { p in
                LineMark(x: .value(hdr ? "PQ %" : "Grey %", p.level),
                         y: .value("Level", p.value),
                         series: .value("Series", p.series))
                    .foregroundStyle(Theme.channel(p.channel))
                    .opacity(p.series.hasPrefix("was") ? 0.3 : 1)
                    .lineStyle(StrokeStyle(lineWidth: p.series.hasPrefix("was") ? 1.5 : 2, lineCap: .round, lineJoin: .round))
                    .interpolationMethod(.monotone)
            }
            RuleMark(y: .value("Target", 100))
                .foregroundStyle(Theme.textSecondary)
                .lineStyle(StrokeStyle(lineWidth: 1))
        }
        .chartXScale(domain: lo...hi)
        .chartYScale(domain: (100 - span)...(100 + span))
        .chartYAxis(.hidden)
        .chartXAxis {
            AxisMarks(values: ticks) { v in
                AxisValueLabel {
                    if let d = v.as(Double.self) {
                        Text("\(Int(d.rounded()))%")
                            .font(.tiny)
                            .foregroundStyle(Theme.textSecondary)
                    }
                }
            }
        }
        .chartLegend(.hidden)
        .frame(height: 160)
        .accessibilityLabel("Greyscale chart: red, green and blue against the target line")
    }
}

// MARK: - Results

/// The score tiles for one measurement: greyscale, colours, and gamma (SDR) or brightness vs the HDR target.
struct ResultScores: View {
    let report: Report
    var before: Report?
    let hdr: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            ScoreTile(label: "Greyscale", value: Analysis.greyAverage(report, hdr: hdr),
                      was: before.map { Analysis.greyAverage($0, hdr: hdr) })
            if !report.colours.isEmpty {
                ScoreTile(label: "Colours", value: Analysis.colourAverage(report),
                          was: before.flatMap { b -> Double? in b.colours.isEmpty ? nil : Analysis.colourAverage(b) },
                          kind: .colour)
            }
            // HDR brightness isn't scored: no step adjusts it, and TVs differ in how bright they show HDR.
            if !hdr {
                ScoreTile(label: "Gamma", value: Analysis.gamma(report),
                          was: before.flatMap { Analysis.gamma($0) }, digits: 2, caption: "target 2.40")
            }
        }
    }
}

/// The measurement's warnings, in the limit colour.
struct ResultWarnings: View {
    let warnings: [String]

    var body: some View {
        ForEach(Array(warnings.enumerated()), id: \.offset) { _, w in
            Text(w)
                .font(.small)
                .foregroundStyle(Theme.limit)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

/// The greyscale and colour detail cards ("12 Results · charts").
struct ResultCharts: View {
    let report: Report
    var before: Report?
    let hdr: Bool
    /// False where the colour chart is already on screen (in the report panel).
    var showsColours = true

    private var greyTitle: String {
        let lv = report.greys.filter { hdr || $0.level >= 0.099 }.map { $0.level * 100 }
        guard let lo = lv.min(), let hi = lv.max() else { return "Greyscale" }
        return "Greyscale · \(Int(lo.rounded()))\u{2013}\(Int(hi.rounded()))%"
    }

    private var colourTitle: String {
        let total = report.colours.count
        let ok = report.colours.filter { $0.de < 3 }.count
        if ok == total { return total == 6 ? "Colours · all six on target" : "Colours · all on target" }
        return "Colours · \(ok) of \(total) on target"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if !report.greys.isEmpty {
                Card {
                    Text(greyTitle)
                        .font(.bodyStrong)
                        .foregroundStyle(.white)
                    GreyBalanceChart(report: report, before: before, hdr: hdr)
                    Text(before != nil ? "Before faded · after solid · flat target line" : "Flat line is the target")
                        .font(.small)
                        .secondaryText()
                }
            }
            if showsColours && !report.colours.isEmpty {
                Card {
                    Text(colourTitle)
                        .font(.bodyStrong)
                        .foregroundStyle(.white)
                    GamutChart(dots: GamutChart.dots(report), hdr: hdr,
                               before: before.map { GamutChart.dots($0) } ?? [])
                        .frame(maxWidth: 340)
                        .frame(maxWidth: .infinity)
                    Text(before != nil ? "Rings: target · hollow: before · dots: after" : "Rings: target · dots: your TV")
                        .font(.small)
                        .secondaryText()
                }
            }
        }
    }
}

/// Scores, warnings and charts for one measurement.
struct ReportSummary: View {
    let report: Report
    var before: Report?
    let hdr: Bool
    /// The kind of screen, for the iPhone's margin of error; nil hides it (the demo's readings are simulated).
    var margin: PanelType? = nil

    /// The dE explanation under the score tiles.
    static let deExplainer = "How far off (dE): under 1 is perfect. Greys under 2 and colours under 3 are excellent; TVs rarely match every colour exactly."

    @EnvironmentObject var app: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            ReportPanel {
                ReportHero(report: report, before: before, hdr: hdr,
                           margin: margin.flatMap { app.profiles.marginDE(panel: $0) })
            }
            ResultWarnings(warnings: report.warnings)
            ResultCharts(report: report, before: before, hdr: hdr, showsColours: false)
            ScoreInfo(margin: margin)
        }
    }
}

/// The iPhone's margin of error under the scores: how close its camera profile got to a professional meter.
struct MarginNote: View {
    @EnvironmentObject var app: AppModel
    let panel: PanelType

    var body: some View {
        if let text = app.profiles.marginNote(panel: panel) {
            HStack(alignment: .top, spacing: 8) {
                Image(systemName: "info.circle")
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.tealBright)
                    .accessibilityHidden(true)
                Text(text)
                    .font(.small)
                    .secondaryText()
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}
