import CalibrationKit
import SwiftUI

// A measurement as a picture rather than a page of text: each score on a gauge that shows before and
// after, the white point against D65 and the colours against their targets. Used by the results, the
// starting point, the screen home and the image people share.

// MARK: - The dE gauge

/// One score on a scale from "off" (left) to "perfect" (right): orange, white and green zones, a hollow
/// marker where it was, a solid one where it is now, and a line between them. The soft band around the
/// marker is the iPhone's margin of error.
struct DeGauge: View {
    let value: Double?
    var was: Double? = nil
    var kind: ScoreTile.Kind = .grey
    /// The iPhone's margin of error, in dE.
    var margin: Double? = nil
    /// False for the shared image, which is drawn once.
    var animated = true

    @State private var progress: Double = 0

    private static func finite(_ v: Double?) -> Double? { v.flatMap { $0.isFinite ? $0 : nil } }

    /// Where the scale ends (its left edge), in dE: a little past "Good".
    private var top: Double { ScoreTile.bands(kind)[3] * 1.3 }

    /// 0 (the left edge, far off) to 1 (the right edge, perfect).
    private func fraction(_ de: Double) -> CGFloat { CGFloat(1 - min(max(de / top, 0), 1)) }

    private var zones: LinearGradient {
        let bands = ScoreTile.bands(kind)
        let good = fraction(bands[3])
        let green = fraction(bands[kind == .grey ? 1 : 2])
        return LinearGradient(stops: [
            .init(color: Theme.limit.opacity(0.6), location: 0),
            .init(color: Theme.limit.opacity(0.6), location: good),
            .init(color: Color.white.opacity(0.32), location: good),
            .init(color: Color.white.opacity(0.32), location: green),
            .init(color: Theme.green.opacity(0.75), location: green),
            .init(color: Theme.green.opacity(0.75), location: 1),
        ], startPoint: .leading, endPoint: .trailing)
    }

    var body: some View {
        let now = Self.finite(value)
        let before = Self.finite(was)
        let t = animated ? progress : 1
        // The marker travels from where it was to where it is now.
        let shown: Double? = now.map { n in (before ?? n) + (n - (before ?? n)) * t }
        return GeometryReader { g in
            let r: CGFloat = 9
            let span = max(g.size.width - 2 * r, 1)
            ZStack(alignment: .leading) {
                Capsule()
                    .fill(zones)
                    .frame(height: 6)
                if let shown, let margin, margin > 0 {
                    let half = max(span * CGFloat(margin / top), r)
                    Capsule()
                        .fill(Color.white.opacity(0.13))
                        .frame(width: half * 2, height: 18)
                        .offset(x: r + span * fraction(shown) - half)
                }
                if let before, let shown {
                    let a = r + span * fraction(before)
                    let b = r + span * fraction(shown)
                    Capsule()
                        .fill(Color.white.opacity(0.55))
                        .frame(width: abs(b - a), height: 2)
                        .offset(x: min(a, b))
                    Circle()
                        .fill(Theme.background)
                        .overlay(Circle().strokeBorder(Color.white.opacity(0.75), lineWidth: 1.5))
                        .frame(width: 10, height: 10)
                        .offset(x: a - 5)
                }
                if let shown {
                    // In the green zone, the marker blooms.
                    let landed = shown < ScoreTile.bands(kind)[kind == .grey ? 1 : 2]
                    Circle()
                        .fill(ScoreTile.tint(value, kind: kind))
                        .overlay(Circle().strokeBorder(Theme.background, lineWidth: 2))
                        .frame(width: r * 2, height: r * 2)
                        .shadow(color: Theme.green.opacity(landed ? 0.9 : 0), radius: 7)
                        .offset(x: span * fraction(shown))
                }
            }
            .frame(width: g.size.width, height: 22, alignment: .leading)
        }
        .frame(height: 22)
        .onAppear {
            guard animated, progress == 0 else { return }
            withAnimation(.spring(response: 0.9, dampingFraction: 0.82).delay(0.25)) { progress = 1 }
        }
        .accessibilityHidden(true)
    }
}

/// "Greyscale" and "4.8 → 1.2 dE", the word for it ("Excellent") in its colour, and the gauge.
struct ScoreGaugeRow: View {
    let label: String
    let value: Double?
    var was: Double? = nil
    var kind: ScoreTile.Kind = .grey
    var margin: Double? = nil
    var animated = true

    private var hasWas: Bool { was.map { $0.isFinite } ?? false }

    private var numbers: String {
        hasWas ? "\(fmt(was, 1)) \u{2192} \(fmt(value, 1)) dE" : "\(fmt(value, 1)) dE"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(label)
                    .font(.captionBrand)
                    .secondaryText()
                Spacer(minLength: 8)
                Text(numbers)
                    .font(.small)
                    .monospacedDigit()
                    .secondaryText()
            }
            Text(ScoreTile.word(value, kind: kind))
                .font(.brand(26, .semibold, relativeTo: .title))
                .tracking(-0.5)
                .foregroundStyle(ScoreTile.tint(value, kind: kind))
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            DeGauge(value: value, was: was, kind: kind, margin: margin, animated: animated)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
        .accessibilityValue(ScoreTile.word(value, kind: kind) + ", " + numbers)
    }
}

/// Under the gauges: which end is which, and the before / after markers.
private struct GaugeLegend: View {
    let hasBefore: Bool

    var body: some View {
        HStack(spacing: 6) {
            Text("Off")
            Spacer(minLength: 4)
            if hasBefore {
                Circle()
                    .strokeBorder(Color.white.opacity(0.75), lineWidth: 1.2)
                    .frame(width: 7, height: 7)
                Text("before")
                Circle()
                    .fill(Color.white)
                    .frame(width: 7, height: 7)
                    .padding(.leading, 6)
                Text("after")
                Spacer(minLength: 4)
            }
            Text("Perfect")
        }
        .font(.micro)
        .secondaryText()
        .accessibilityHidden(true)
    }
}

// MARK: - The report

/// A measurement at a glance: the greyscale and colour scores on their gauges, then the white point
/// against D65 and the colours against their targets, with where each started when there's a `before`.
struct ReportHero: View {
    let report: Report
    var before: Report? = nil
    let hdr: Bool
    /// The iPhone's margin of error, in dE (ProfileService.marginDE); nil hides it.
    var margin: Double? = nil
    var animated = true

    private static func brightest(_ r: Report?) -> GreyResult? { r?.greys.max { $0.level < $1.level } }

    private static func uv(_ g: GreyResult) -> Point2 { ColourMath.upvp(ColourMath.xyzToXY(g.xyz)) }

    private var colourWas: Double? {
        guard let before, !before.colours.isEmpty else { return nil }
        return Analysis.colourAverage(before)
    }

    private var coloursCaption: String {
        let ok = report.colours.filter { $0.de < 3 }.count
        return "\(ok) of \(report.colours.count) colours on target"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            ScoreGaugeRow(label: "Greyscale", value: Analysis.greyAverage(report, hdr: hdr),
                          was: before.map { Analysis.greyAverage($0, hdr: hdr) },
                          kind: .grey, margin: margin, animated: animated)
            if !report.colours.isEmpty {
                ScoreGaugeRow(label: "Colours", value: Analysis.colourAverage(report), was: colourWas,
                              kind: .colour, margin: margin, animated: animated)
            }
            GaugeLegend(hasBefore: before != nil)
            charts
            if !hdr, let gamma = Analysis.gamma(report) {
                HStack(spacing: 6) {
                    Text("Gamma")
                        .secondaryText()
                    Text(fmt(gamma, 2))
                        .foregroundStyle(.white)
                        .monospacedDigit()
                    Text("· target 2.40")
                        .secondaryText()
                }
                .font(.small)
                .accessibilityElement(children: .combine)
            }
        }
    }

    @ViewBuilder private var charts: some View {
        let white = Self.brightest(report)
        if white != nil || !report.colours.isEmpty {
            HStack(alignment: .center, spacing: 12) {
                if let w = white {
                    VStack(spacing: 6) {
                        WhiteTarget(uv: Self.uv(w), d65: ColourMath.upvp(ColourMath.d65), ok: w.de < 2,
                                    was: Self.brightest(before).map { Self.uv($0) })
                            .frame(maxWidth: 132)
                        Text("White point")
                            .font(.tiny)
                            .secondaryText()
                    }
                    .frame(maxWidth: .infinity)
                }
                if !report.colours.isEmpty {
                    VStack(spacing: 6) {
                        GamutChart(dots: GamutChart.dots(report), hdr: hdr,
                                   before: before.map { GamutChart.dots($0) } ?? [])
                        Text(coloursCaption)
                            .font(.tiny)
                            .secondaryText()
                    }
                    .frame(maxWidth: .infinity)
                }
            }
        }
    }
}

/// The teal panel a report sits on.
struct ReportPanel<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 14) { content }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.tealGradient, in: RoundedRectangle(cornerRadius: 22))
            .overlay(RoundedRectangle(cornerRadius: 22).stroke(Theme.hairline, lineWidth: 1))
    }
}

/// "What the numbers mean": closed by default, so the explanation is there without being in the way.
struct ScoreInfo: View {
    /// The kind of screen, for the iPhone's margin of error; nil leaves it out (the demo).
    var margin: PanelType? = nil
    @State private var open = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button {
                withAnimation(.easeInOut(duration: 0.2)) { open.toggle() }
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "info.circle")
                    Text("What the numbers mean")
                    Image(systemName: open ? "chevron.up" : "chevron.down")
                        .font(.system(size: 10, weight: .semibold))
                }
                .font(.small)
                .foregroundStyle(Theme.tealBright)
                .frame(minHeight: 36)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            if open {
                Text(ReportSummary.deExplainer)
                    .font(.small)
                    .secondaryText()
                    .fixedSize(horizontal: false, vertical: true)
                if let margin { MarginNote(panel: margin) }
            }
        }
    }
}

// MARK: - Small pictures for the steps

/// A little TV with a play button, in a format's colour: "play this on the TV".
struct TVPlayArt: View {
    var tint: Color = Theme.tealBright

    var body: some View {
        VStack(spacing: 3) {
            RoundedRectangle(cornerRadius: 5)
                .fill(Theme.background)
                .overlay(RoundedRectangle(cornerRadius: 5).strokeBorder(tint, lineWidth: 1.5))
                .overlay(Image(systemName: "play.fill")
                    .font(.system(size: 15))
                    .foregroundStyle(tint))
                .frame(width: 64, height: 38)
            Capsule()
                .fill(tint)
                .frame(width: 20, height: 2)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 12))
        .accessibilityHidden(true)
    }
}

/// What to do, in order, as numbered tiles: "1 Tap Measure → 2 Start the video".
struct OrderStrip: View {
    let steps: [String]

    var body: some View {
        HStack(spacing: 6) {
            ForEach(Array(steps.enumerated()), id: \.offset) { i, text in
                if i > 0 {
                    Image(systemName: "arrow.right")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(Theme.textSecondary)
                        .accessibilityHidden(true)
                }
                HStack(spacing: 8) {
                    Text("\(i + 1)")
                        .font(.badge)
                        .foregroundStyle(Theme.background)
                        .frame(width: 22, height: 22)
                        .background(Circle().fill(Theme.accent))
                    Text(text)
                        .font(.captionStrong)
                        .foregroundStyle(.white)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(10)
                .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
                .background(Theme.card, in: RoundedRectangle(cornerRadius: 14))
                .accessibilityElement(children: .combine)
            }
        }
    }
}
