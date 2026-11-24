import CalibrationKit
import SwiftUI

// Playing pattern videos and measuring them: the "play this on the TV" card and the progress while a
// video is measured (Figma: "Play on TV card", "08 … · waiting / measuring").

/// "Play on the TV": the format's badge and where the video comes from, the video's title, and a link to
/// open it on YouTube (or get the USB file).
/// `compact`: the small card beside a live reading ("Play on the TV · SDR · YouTube", the title and, with
/// `patch`, a swatch of the pattern: "100% white").
struct PlayFileCard: View {
    @EnvironmentObject var links: LinksService
    let file: String
    let mode: SignalMode
    var fromStart = false
    var compact = false
    /// The pattern on screen, shown as a swatch and its name in the compact card.
    var patch: Patch? = nil

    var body: some View {
        if compact {
            compactCard
        } else {
            fullCard
        }
    }

    var sourceLabel: String { mode.onYouTube ? "YouTube" : "USB file" }

    var playLabel: String { fromStart ? "Play on the TV, from the start" : "Play on the TV" }

    /// The video itself on YouTube (or its playlist), or the pattern download.
    var sourceURL: URL? {
        mode.onYouTube ? (links.links.video(file) ?? links.links.playlist(mode)) : links.links.download(mode)
    }

    /// The video as a row: a little TV with a play button, the video's title, and where to open it.
    /// The whole row is the link when there is one.
    @ViewBuilder var fullCard: some View {
        if let url = sourceURL {
            Link(destination: url) { fileRow(linked: true) }
                .buttonStyle(.plain)
        } else {
            fileRow(linked: false)
        }
    }

    func fileRow(linked: Bool) -> some View {
        HStack(spacing: 12) {
            TVPlayArt(tint: mode.tint)
                .frame(width: 92, height: 64)
            VStack(alignment: .leading, spacing: 4) {
                Text(playLabel.uppercased())
                    .font(.micro)
                    .fontWeight(.semibold)
                    .tracking(0.6)
                    .foregroundStyle(mode.tint)
                Text(file)
                    .font(.bodyStrong)
                    .foregroundStyle(.white)
                    .fixedSize(horizontal: false, vertical: true)
                if linked {
                    HStack(spacing: 4) {
                        Text(mode.onYouTube ? "Open on YouTube" : "Get the USB file")
                        Image(systemName: mode.onYouTube ? "arrow.up.right" : "arrow.down")
                            .font(.system(size: 10, weight: .semibold))
                    }
                    .font(.small)
                    .foregroundStyle(Theme.tealBright)
                } else {
                    Text(mode.onYouTube ? "YouTube · on your TV" : "USB file")
                        .font(.small)
                        .secondaryText()
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 18))
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(Theme.hairline, lineWidth: 1))
        .contentShape(RoundedRectangle(cornerRadius: 18))
    }

    var compactCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("\(playLabel) · \(mode.label) · \(sourceLabel)")
                .font(.tiny)
                .foregroundStyle(Theme.tealBright)
                .fixedSize(horizontal: false, vertical: true)
            Text(file)
                .font(Font.brand(14, .semibold, relativeTo: .callout))
                .foregroundStyle(.white)
                .fixedSize(horizontal: false, vertical: true)
            if let patch {
                HStack(spacing: 8) {
                    RoundedRectangle(cornerRadius: 2)
                        .fill(patchColour(kind: patch.kind, id: patch.id, level: patch.level))
                        .frame(width: 24, height: 18)
                    Text(patchLabel(patch.name))
                        .font(.tiny)
                        .secondaryText()
                }
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 18))
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(Theme.hairline, lineWidth: 1))
    }
}

/// Progress while a video is measured: waiting for its flashes, then each patch in turn.
/// Shows the screen's headline ("Waiting for the video's flashes…", "Measuring 50% grey") unless
/// `showsTitle` is false. The Cancel button is pinned by the hosting screen (GuidedScreen); pass
/// `showsCancel: true` to draw it in the card instead.
struct MeasuringCard: View {
    @ObservedObject var runner: SweepRunner
    let file: String
    let mode: SignalMode
    var showsTitle = true
    var showsCancel = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            switch runner.phase {
            case .waiting:
                waiting
            case .measuring:
                measuring
            default:
                EmptyView()
            }
            if showsCancel && runner.running {
                Button("Cancel") { runner.cancel() }
                    .buttonStyle(SecondaryButtonStyle())
            }
        }
    }

    // MARK: Waiting for the flashes

    @ViewBuilder var waiting: some View {
        if showsTitle {
            Text("Start the video on the TV")
                .stepTitleStyle()
        }
        Card(spacing: 12, hero: true) {
            Image(systemName: "viewfinder")
                .font(.system(size: 54, weight: .light))
                .foregroundStyle(Theme.tealBright)
                .symbolEffect(.pulse, options: .repeating)
                .frame(maxWidth: .infinity)
                .frame(height: 120)
            Text("Waiting for its flashes…")
                .font(.bodyStrong)
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)
        }
        WaitingFileCard(file: file, mode: mode)
        Text(mode.onYouTube ? "Its title starts with \(mode.packPrefix). Ads are fine: the app keeps waiting."
                            : "Its file name starts with \(mode.packPrefix).")
            .font(.captionBrand)
            .secondaryText()
            .fixedSize(horizontal: false, vertical: true)
    }

    // MARK: Measuring each patch

    var current: SweepRunner.PatchProgress? {
        runner.index >= 0 && runner.index < runner.patches.count ? runner.patches[runner.index] : nil
    }

    var doneCount: Int { runner.patches.filter { $0.done }.count }

    @ViewBuilder var measuring: some View {
        if showsTitle {
            Text(current.map { "Measuring \(patchLabel($0.name))" } ?? "Measuring…")
                .stepTitleStyle()
        }
        Card(spacing: 12, hero: true) {
            RoundedRectangle(cornerRadius: 9)
                .fill(current.map { colour(for: $0) } ?? Theme.card)
                .frame(width: 110, height: 110)
                .frame(maxWidth: .infinity)
                .frame(height: 130)
                .animation(.easeInOut(duration: 0.3), value: runner.index)
            Text("\(doneCount) of \(runner.patches.count) patches")
                .font(Font.brand(19, .semibold, relativeTo: .title3))
                .foregroundStyle(.white)
                .monospacedDigit()
                .frame(maxWidth: .infinity)
        }
        Card(spacing: 16) {
            HStack(spacing: 4) {
                ForEach(Array(runner.patches.enumerated()), id: \.offset) { i, p in
                    RoundedRectangle(cornerRadius: 3)
                        .fill(p.done ? (p.Y == nil ? Theme.limit : colour(for: p)) : Theme.card)
                        .overlay(RoundedRectangle(cornerRadius: 3)
                            .stroke(!p.done && i == runner.index ? Theme.accent : Theme.hairline, lineWidth: 1))
                        .frame(maxWidth: .infinity)
                        .frame(height: 28)
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("\(doneCount) of \(runner.patches.count) patches measured")
            Sparkline(values: runner.recent)
                .frame(height: 62)
            Text("Camera sees \(Text(reading).font(.captionStrong).foregroundStyle(.white))")
                .font(.captionBrand)
                .secondaryText()
        }
        Text("Keep the TV menu closed and the phone still.")
            .font(.captionBrand)
            .secondaryText()
    }

    var reading: String {
        "\(fmt(runner.latestY, (runner.latestY ?? 0) < 10 ? 2 : 1)) cd/m²"
    }

    func colour(for p: SweepRunner.PatchProgress) -> Color {
        patchColour(kind: p.kind, id: p.id, level: Patterns.patch(hdr: mode.isHDR, id: p.id)?.level)
    }
}

/// The video to play while waiting: its format, where it comes from and its title.
private struct WaitingFileCard: View {
    let file: String
    let mode: SignalMode

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                ModeBadge(mode: mode)
                Spacer(minLength: 8)
                Text(mode.onYouTube ? "YouTube · on your TV" : "USB file")
                    .font(.small)
                    .secondaryText()
                    .lineLimit(1)
            }
            Text(file)
                .font(Font.brand(14, .semibold, relativeTo: .callout))
                .foregroundStyle(.white)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 18))
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(Theme.hairline, lineWidth: 1))
    }
}

/// How a pattern looks on screen: its colour, or its grey level.
private func patchColour(kind: PatchKind, id: String, level: Double?) -> Color {
    if kind == .colour { return Theme.swatch(id) }
    return Color(white: min(max(level ?? 1, 0), 1))
}

/// "100% WHITE" -> "100% white", "PQ 50% GREY (92 nits)" -> "50% grey (92 nits)".
private func patchLabel(_ name: String) -> String {
    name.replacingOccurrences(of: "PQ ", with: "").lowercased()
}

/// The camera's recent brightness readings, as a line.
struct Sparkline: View {
    let values: [Double]
    var colour: Color = Theme.tealBright

    var body: some View {
        Canvas { ctx, size in
            guard values.count > 1 else { return }
            let logs = values.map { log10(max($0, 0.01)) }
            let lo = logs.min() ?? 0, hi = max(logs.max() ?? 1, lo + 0.5)
            var path = Path()
            for (i, v) in logs.enumerated() {
                let pt = CGPoint(x: size.width * CGFloat(i) / CGFloat(logs.count - 1),
                                 y: 1 + (size.height - 2) * (1 - CGFloat((v - lo) / (hi - lo))))
                if i == 0 { path.move(to: pt) } else { path.addLine(to: pt) }
            }
            ctx.stroke(path, with: .color(colour), style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
        }
    }
}
