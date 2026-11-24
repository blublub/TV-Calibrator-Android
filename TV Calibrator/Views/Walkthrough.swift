import CalibrationKit
import SwiftUI

// A swipeable, one-idea-per-page walkthrough, and the small illustrations it uses
// (Figma: "Walkthrough page" component, 07 Set up and 03 Add a Screen).

struct WalkPage {
    var symbol: String
    var tint: Color = Theme.tealBright
    var title: String
    var text: String
    var bullets: [String] = []
    /// A picture above the text, drawn on the teal illustration panel (replaces the symbol).
    var art: AnyView?
    /// Controls under the text (buttons, choices).
    var extra: AnyView?
    /// The small teal line above the title. When nil the walkthrough shows "<section> · 2 of 6".
    var eyebrow: String? = nil
    /// A larger white sentence before `text` (19 pt), for the one thing to do on this page.
    var lead: String? = nil
}

/// Pages you swipe (or tap Next) through. The last page's button finishes.
/// The yellow Next / finish button is declared with `pinnedAction`, so the hosting screen
/// (`pinnedActionHost()`) pins it at the bottom; the page dots and Back sit under the page.
struct Walkthrough: View {
    let pages: [WalkPage]
    var finishTitle = "Done"
    /// The eyebrow's prefix: "Set up" gives "Set up · 5 of 6".
    var section: String? = nil
    /// Page titles: 22 pt semibold (`.stepTitle`), the same as a step's title.
    var titleFont: Font = .stepTitle
    /// Height of the illustration panel for pages with `art`.
    var artHeight: CGFloat = 170
    /// Whether the user may leave page i (e.g. a name is filled in).
    var canAdvance: (Int) -> Bool = { _ in true }
    let onFinish: () -> Void

    @State private var index = 0
    @State private var forward = true

    private var current: Int { min(index, max(pages.count - 1, 0)) }
    private var onLastPage: Bool { index >= pages.count - 1 }

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            if !pages.isEmpty {
                ZStack {
                    pageView(pages[current], at: current)
                        .id(current)
                        .transition(.asymmetric(
                            insertion: .move(edge: forward ? .trailing : .leading).combined(with: .opacity),
                            removal: .move(edge: forward ? .leading : .trailing).combined(with: .opacity)))
                }
                .frame(maxWidth: .infinity)
                .clipped()
                .contentShape(Rectangle())
                .simultaneousGesture(
                    DragGesture(minimumDistance: 30).onEnded { v in
                        guard abs(v.translation.width) > abs(v.translation.height) * 1.5 else { return }
                        if v.translation.width < -50 { next() } else if v.translation.width > 50 { back() }
                    })

                footer
            }
        }
        .pinnedAction(onLastPage ? finishTitle : "Next", enabled: canAdvance(current)) {
            // Read the page at tap time: the host keeps the closure while the title stays the same.
            if index >= pages.count - 1 { onFinish() } else { next() }
        }
    }

    /// Back (a teal link) on the left, the page dots in the middle.
    private var footer: some View {
        ZStack {
            if pages.count > 1 {
                PageDots(count: pages.count, index: current)
            }
            HStack {
                if current > 0 {
                    Button("Back") { back() }
                        .buttonStyle(LinkButtonStyle(font: .bodyBrand))
                }
                Spacer(minLength: 0)
            }
        }
        .frame(minHeight: 44)
    }

    func next() {
        guard index < pages.count - 1, canAdvance(index) else { return }
        forward = true
        withAnimation(.easeInOut(duration: 0.28)) { index += 1 }
    }

    func back() {
        guard index > 0 else { return }
        forward = false
        withAnimation(.easeInOut(duration: 0.28)) { index -= 1 }
    }

    private func eyebrow(for p: WalkPage, at i: Int) -> String? {
        if let e = p.eyebrow { return e }
        guard pages.count > 1 else { return section }
        let count = "\(i + 1) of \(pages.count)"
        if let section { return "\(section) · \(count)" }
        return count
    }

    @ViewBuilder func pageView(_ p: WalkPage, at i: Int) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            if let e = eyebrow(for: p, at: i) {
                Eyebrow(text: e)
            }
            Text(p.title)
                .font(titleFont)
                .tracking(-0.4)
                .foregroundStyle(.white)
                .fixedSize(horizontal: false, vertical: true)
            if let art = p.art {
                WalkArtPanel(height: artHeight) { art }
            } else if !p.symbol.isEmpty {
                WalkArtPanel(height: 120) {
                    Image(systemName: p.symbol)
                        .font(.system(size: 44, weight: .light))
                        .foregroundStyle(p.tint)
                }
            }
            if let lead = p.lead {
                Text(lead)
                    .font(.brand(17, relativeTo: .title3))
                    .foregroundStyle(.white)
                    .lineSpacing(4)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if !p.text.isEmpty {
                Text(p.text)
                    .font(.bodyBrand)
                    .secondaryText()
                    .lineSpacing(4)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if !p.bullets.isEmpty {
                Card(spacing: 10) {
                    ForEach(Array(p.bullets.enumerated()), id: \.offset) { _, b in
                        HStack(alignment: .firstTextBaseline, spacing: 10) {
                            Image(systemName: "checkmark.circle")
                                .font(.system(size: 15))
                                .foregroundStyle(p.tint)
                            Text(b)
                                .font(.bodyBrand)
                                .foregroundStyle(.white)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
            }
            if let extra = p.extra { extra }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// The illustration panel: the brand teal surface, 18 pt corners, the picture centred.
struct WalkArtPanel<Content: View>: View {
    var height: CGFloat = 170
    @ViewBuilder var content: Content

    var body: some View {
        content
            .frame(maxWidth: .infinity)
            .frame(height: height)
            .background(Theme.surface, in: RoundedRectangle(cornerRadius: 18))
            .clipShape(RoundedRectangle(cornerRadius: 18))
    }
}

// MARK: - Illustrations
// Flat line drawings: teal outlines on the panel, the screen in the background colour,
// the grey centre patch and the yellow measuring box.

private enum ArtColour {
    static let line = Theme.tealBright
    static let screen = Theme.background
    static let patch = Color(red: 0x77 / 255, green: 0x7D / 255, blue: 0x7E / 255)   // #777D7E
    static let track = Color.white.opacity(0.14)
}

/// The TV with a grey patch in the middle, and the phone on its stand facing it.
struct PlacementArt: View {
    // Drawn on a 325 × 160 canvas (the Figma frame), scaled to fit.
    private static let canvas = CGSize(width: 325, height: 160)

    var body: some View {
        GeometryReader { geo in
            let s = max(0.01, min(geo.size.width / Self.canvas.width, geo.size.height / Self.canvas.height))
            ZStack(alignment: .topLeading) {
                // TV and its stand
                RoundedRectangle(cornerRadius: 7)
                    .fill(ArtColour.screen)
                    .overlay(RoundedRectangle(cornerRadius: 7).stroke(ArtColour.line, lineWidth: 2))
                    .frame(width: 228, height: 86)
                    .offset(x: 28, y: 20)
                RoundedRectangle(cornerRadius: 1)
                    .fill(ArtColour.patch)
                    .frame(width: 76, height: 60)
                    .offset(x: 28 + (228 - 76) / 2, y: 20 + (86 - 60) / 2)
                Rectangle()
                    .fill(ArtColour.line)
                    .frame(width: 40, height: 3)
                    .offset(x: 28 + (228 - 40) / 2, y: 111)
                // Phone with the measuring box
                RoundedRectangle(cornerRadius: 6)
                    .fill(ArtColour.screen)
                    .overlay(RoundedRectangle(cornerRadius: 6).stroke(ArtColour.line, lineWidth: 2))
                    .overlay(RoundedRectangle(cornerRadius: 1).stroke(Theme.accent, lineWidth: 1.5)
                        .frame(width: 10, height: 10))
                    .frame(width: 35, height: 62)
                    .offset(x: 270, y: 60)
                // Its stand: a stem and two legs
                Path { p in
                    let x: CGFloat = 287.5
                    p.move(to: CGPoint(x: x, y: 122))
                    p.addLine(to: CGPoint(x: x, y: 136))
                    p.move(to: CGPoint(x: x - 21, y: 147))
                    p.addLine(to: CGPoint(x: x, y: 136))
                    p.addLine(to: CGPoint(x: x + 21, y: 147))
                }
                .stroke(ArtColour.line, style: StrokeStyle(lineWidth: 1.5, lineCap: .round, lineJoin: .round))
                Text("15–30 cm · facing the centre")
                    .font(.tiny)
                    .secondaryText()
                    .lineLimit(1)
                    .fixedSize()
                    .offset(x: 45, y: 135)
            }
            .frame(width: Self.canvas.width, height: Self.canvas.height, alignment: .topLeading)
            .scaleEffect(s)
            .frame(width: geo.size.width, height: geo.size.height)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("A phone on a stand, 15 to 30 centimetres from the TV, facing the grey patch in the middle")
    }
}

/// Lights off, TV warming up.
struct DarkRoomArt: View {
    var body: some View {
        HStack(alignment: .top, spacing: 30) {
            item("lightbulb.slash", "Lights off")
            item("tv", "TV on")
            item("timer", "20–30 min")
        }
        .accessibilityElement(children: .combine)
    }

    private func item(_ symbol: String, _ label: String) -> some View {
        VStack(spacing: 10) {
            Image(systemName: symbol)
                .font(.system(size: 36, weight: .light))
                .foregroundStyle(ArtColour.line)
                .frame(height: 44)
            Text(label)
                .font(.small)
                .secondaryText()
        }
    }
}

/// The four formats, in the order to calibrate them, with the current one highlighted.
struct FormatOrderArt: View {
    let current: SignalMode

    var body: some View {
        VStack(spacing: 14) {
            HStack(spacing: 6) {
                ForEach(SignalMode.allCases) { m in
                    VStack(spacing: 6) {
                        Text(m.label)
                            .font(.badge)
                            .foregroundStyle(m == current ? Theme.background : m.tint)
                            .lineLimit(1)
                            .minimumScaleFactor(0.7)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 6)
                            .frame(maxWidth: .infinity)
                            .background(RoundedRectangle(cornerRadius: 8).fill(m == current ? m.tint : m.tint.opacity(0.13)))
                        Text(m.onYouTube ? "YouTube" : "USB file")
                            .font(.micro)
                            .secondaryText()
                            .lineLimit(1)
                    }
                    if m != SignalMode.allCases.last {
                        Image(systemName: "chevron.right")
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundStyle(Theme.textSecondary)
                            .padding(.bottom, 16)
                    }
                }
            }
            .padding(.horizontal, 16)
            Text("Each format has its own picture settings on the TV")
                .font(.small)
                .secondaryText()
                .multilineTextAlignment(.center)
                .padding(.horizontal, 16)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Calibrate in order: SDR, HDR10, HDR10+, Dolby Vision. Now: \(current.label)")
    }
}

/// The TV outline that the menu illustrations draw their sliders in, fitted to any size.
private func artTVRect(in size: CGSize) -> CGRect {
    let pad = max(1, min(size.width, size.height) * 0.12)
    let h = max(1, size.height - pad * 2)
    let w = max(1, min(size.width - pad * 2, h * 2.6))
    return CGRect(x: (size.width - w) / 2, y: (size.height - h) / 2, width: w, height: h)
}

private func drawArtTV(_ ctx: inout GraphicsContext, _ tv: CGRect) {
    let r = min(9, tv.height * 0.1)
    let shape = Path(roundedRect: tv, cornerRadius: r)
    ctx.fill(shape, with: .color(ArtColour.screen))
    ctx.stroke(shape, with: .color(ArtColour.line), lineWidth: 1.5)
}

/// A white-balance menu on the TV: R, G and B sliders for 2-point, or one slider per point
/// (10 or 20). Everything is sized from the frame, so it always fits.
struct WhiteBalanceArt: View {
    let points: Int

    var body: some View {
        Canvas { ctx, size in
            let tv = artTVRect(in: size)
            drawArtTV(&ctx, tv)
            let n = points <= 3 ? 3 : points
            let inner = tv.insetBy(dx: tv.width * 0.12, dy: tv.height * 0.1)
            guard inner.width > 0, inner.height > 0 else { return }
            let step = inner.width / CGFloat(n)
            let track = min(4, max(1, step * 0.3))
            let knob = min(14, max(3, step * 0.72), inner.height * 0.3)
            let heights: [CGFloat] = [0.15, 0.4, 0.5]
            for i in 0..<n {
                let x = inner.minX + step * (CGFloat(i) + 0.5)
                ctx.fill(Path(roundedRect: CGRect(x: x - track / 2, y: inner.minY, width: track, height: inner.height),
                              cornerRadius: track / 2), with: .color(ArtColour.track))
                let f: CGFloat = n == 3 ? heights[i] : 0.45 + 0.3 * CGFloat(sin(Double(i) * 1.1))
                let span = max(0, inner.height - knob)
                let y = inner.minY + knob / 2 + span * min(max(f, 0), 1)
                let colour = i % 3 == 0 ? Theme.red : i % 3 == 1 ? Theme.green : Theme.blue
                ctx.fill(Path(ellipseIn: CGRect(x: x - knob / 2, y: y - knob / 2, width: knob, height: knob)),
                         with: .color(colour))
            }
        }
        .accessibilityLabel(points <= 3 ? "Red, green and blue sliders" : "\(points) sliders")
    }
}

/// A colour menu on the TV: three slider rows (Hue / Saturation / Brightness, or Red / Green / Blue),
/// or an empty screen when the TV has none.
struct ColourMenuArt: View {
    let menu: ColourMenu

    var labels: [String] {
        switch menu {
        case .hsl: return ["H", "S", "B"]
        case .rgbSliders: return ["R", "G", "B"]
        case .off: return []
        }
    }

    var body: some View {
        Canvas { ctx, size in
            let tv = artTVRect(in: size)
            drawArtTV(&ctx, tv)
            guard menu != .off else {
                ctx.draw(Text("No colour sliders").font(.tiny).foregroundStyle(Theme.textSecondary),
                         at: CGPoint(x: tv.midX, y: tv.midY))
                return
            }
            let inner = tv.insetBy(dx: tv.width * 0.12, dy: tv.height * 0.16)
            guard inner.width > 0, inner.height > 0 else { return }
            let rowStep = inner.height / 3
            let track = min(4, max(1, rowStep * 0.15))
            let knob = min(14, max(3, rowStep * 0.55))
            let labelW: CGFloat = inner.width > 80 ? 16 : 0
            let positions: [CGFloat] = [0.27, 0.42, 0.58]
            let colours = [Theme.red, Theme.green, Theme.blue]
            for i in 0..<3 {
                let y = inner.minY + rowStep * (CGFloat(i) + 0.5)
                if labelW > 0 {
                    let colour = menu == .rgbSliders ? colours[i] : Theme.textSecondary
                    ctx.draw(Text(labels[i]).font(.micro).foregroundStyle(colour),
                             at: CGPoint(x: inner.minX + labelW / 2 - 4, y: y))
                }
                let x0 = inner.minX + labelW, w = max(1, inner.width - labelW)
                ctx.fill(Path(roundedRect: CGRect(x: x0, y: y - track / 2, width: w, height: track),
                              cornerRadius: track / 2), with: .color(ArtColour.track))
                let x = x0 + knob / 2 + max(0, w - knob) * positions[i]
                ctx.fill(Path(ellipseIn: CGRect(x: x - knob / 2, y: y - knob / 2, width: knob, height: knob)),
                         with: .color(colours[i]))
            }
        }
        .accessibilityLabel(menu == .off ? "No colour menu"
                            : menu == .hsl ? "Hue, saturation and brightness sliders" : "Red, green and blue sliders")
    }
}
