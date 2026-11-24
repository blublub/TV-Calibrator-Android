import CalibrationKit
import SwiftUI

enum WBKind {
    case gain, offset, fine
}

/// "This TV's readings wobble…" and "Average of 5 readings · latest 42.1 cd/m²  [Restart]".
struct AverageLine: View {
    let reading: LiveReading
    let restart: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            if reading.jitter >= 0.004 {
                Text("This TV's readings wobble about ±\(fmt(reading.jitter * 100))%, so the bars show an average.")
                    .font(.small)
                    .secondaryText()
                    .fixedSize(horizontal: false, vertical: true)
            }
            HStack(alignment: .center, spacing: 8) {
                Text("Average of \(reading.averaged) reading\(reading.averaged == 1 ? "" : "s")"
                     + (reading.averaged < reading.need ? " (needs \(reading.need) to call it steady)" : "")
                     + " · latest \(fmt(reading.latestY, reading.latestY < 10 ? 2 : 1)) cd/m²")
                    .font(.small)
                    .monospacedDigit()
                    .secondaryText()
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 8)
                Button("Restart", action: restart)
                    .buttonStyle(LinkButtonStyle(font: .captionStrong))
                    .accessibilityHint("Use after changing a setting, to see its effect sooner")
            }
        }
    }
}

/// The step's tips, each one a `TipRow` (to do, on target, at the TV's limit).
struct TipList: View {
    let tips: [(text: String, kind: TipKind)]
    enum TipKind { case todo, ok, done, limit }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(Array(tips.enumerated()), id: \.offset) { _, t in
                TipRow(tone: TipList.tone(t.kind), text: t.text)
            }
        }
    }

    static func tone(_ kind: TipKind) -> TipRow.Tone {
        switch kind {
        case .todo: return .todo
        case .ok, .done: return .ok
        case .limit: return .limit
        }
    }
}

// MARK: - Shared parts of the live steps

/// A big signed reading with a true minus sign: "+3.2%", "−2.1". One decimal below 10, none above.
fileprivate func bigSigned(_ v: Double?, suffix: String = "") -> String {
    guard let v, v.isFinite else { return "–" }
    let digits = abs(v) < 9.95 ? 1 : 0
    return String(format: "%+.\(digits)f", v).replacingOccurrences(of: "-", with: "\u{2212}") + suffix
}

/// One channel of the white balance: "Red  +3.2%" over a centred bar with a green tolerance zone.
fileprivate struct ChannelBalanceRow: View {
    let label: String
    let value: Double?          // fraction, e.g. 0.032 = 3.2% too much
    let colour: Color
    var fullScale = 0.05        // this much fills half the bar
    var tolerance = 0.012
    /// Names this setting for the success chime (Chime); nil: no chime.
    var chime: String? = nil

    var ok: Bool { value.map { abs($0) <= tolerance } ?? false }

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack(alignment: .center, spacing: 8) {
                Text(label)
                    .font(.brand(16, .semibold, relativeTo: .headline))
                    .foregroundStyle(.white)
                Spacer(minLength: 8)
                HStack(spacing: 8) {
                    if ok {
                        Image(systemName: "checkmark")
                            .font(.system(size: 28, weight: .semibold))
                    }
                    Text(bigSigned(value.map { $0 * 100 }, suffix: "%"))
                        .font(.reading(38))
                        .tracking(-1)
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                }
                .foregroundStyle(ok ? Theme.green : Color.white)
            }
            CentredTrack(value: value, colour: colour, fullScale: fullScale, tolerance: tolerance, ok: ok)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
        .accessibilityValue(ok ? "On target" : bigSigned(value.map { $0 * 100 }, suffix: " percent"))
        .onChange(of: value) { _, v in Chime.shared.observe(chime, ok: v.map { abs($0) <= tolerance } ?? false) }
    }
}

/// The centred track: the white target line in the middle, the green tolerance zone around it, and the
/// channel's amount growing left (too little) or right (too much).
fileprivate struct CentredTrack: View {
    let value: Double?
    let colour: Color
    let fullScale: Double
    let tolerance: Double
    let ok: Bool

    var body: some View {
        GeometryReader { g in
            let w = g.size.width
            let mid = w / 2
            let zone = min(w, max(4, CGFloat(tolerance / fullScale) * mid * 2))
            let off = CGFloat(clamp((value ?? 0) / fullScale, -1, 1)) * mid
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: 6).fill(Color.white.opacity(0.07))
                Rectangle()
                    .fill(Theme.green.opacity(0.16))
                    .frame(width: zone, height: 20)
                    .offset(x: mid - zone / 2)
                if value != nil {
                    if ok {
                        RoundedRectangle(cornerRadius: 4)
                            .fill(Theme.green)
                            .frame(width: 8, height: 10)
                            .offset(x: mid - 4)
                    } else {
                        RoundedRectangle(cornerRadius: 4)
                            .fill(colour)
                            .frame(width: max(abs(off), 4), height: 10)
                            .offset(x: off < 0 ? mid + off : mid)
                    }
                }
                Rectangle()
                    .fill(Color.white)
                    .frame(width: 2, height: 20)
                    .offset(x: mid - 1)
            }
            .frame(width: w, height: 20)
            .clipShape(RoundedRectangle(cornerRadius: 6))
        }
        .frame(height: 20)
    }
}

/// "Too little · On target · Too much" under the balance bars.
fileprivate struct BalanceScale: View {
    var body: some View {
        HStack(spacing: 0) {
            Text("Too little").secondaryText().frame(maxWidth: .infinity, alignment: .leading)
            Text("On target").foregroundStyle(Theme.green)
            Text("Too much").secondaryText().frame(maxWidth: .infinity, alignment: .trailing)
        }
        .font(.tiny)
        .accessibilityHidden(true)
    }
}

/// One colour-menu control, drawn like the white balance bars: "Saturation  +3.1" over a centred track.
/// The centre line is the target; the bar grows right when it's too much (lower it) and left when it's too
/// little (raise it). Under it, what to do: "Too strong: lower it".
fileprivate struct ColourControlRow: View {
    let label: String
    let value: Double?          // signed error: + too much, − too little
    let colour: Color
    let fullScale: Double       // this much fills half the track
    let tolerance: Double
    var caption: String? = nil
    /// Names this setting for the success chime (Chime); nil: no chime.
    var chime: String? = nil

    var ok: Bool { value.map { abs($0) <= tolerance } ?? false }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .center, spacing: 8) {
                Text(label)
                    .font(.brand(16, .semibold, relativeTo: .headline))
                    .foregroundStyle(.white)
                Spacer(minLength: 8)
                HStack(spacing: 6) {
                    if ok {
                        Image(systemName: "checkmark")
                            .font(.system(size: 20, weight: .semibold))
                    }
                    Text(bigSigned(value))
                        .font(.reading(30))
                        .tracking(-1)
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                }
                .foregroundStyle(ok ? Theme.green : Color.white)
            }
            CentredTrack(value: value, colour: colour, fullScale: fullScale, tolerance: tolerance, ok: ok)
            if let caption, !ok, value != nil {
                Text(caption)
                    .font(.small)
                    .secondaryText()
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
        .accessibilityValue(ok ? "On target" : (caption ?? bigSigned(value)))
        .onChange(of: value) { _, v in Chime.shared.observe(chime, ok: v.map { abs($0) <= tolerance } ?? false) }
    }
}

/// Demo TVs: what the readings are, in place of the video to play.
fileprivate struct DemoNote: View {
    var body: some View {
        Card(hero: true) {
            Text("Demo · simulated readings")
                .font(.captionStrong)
                .foregroundStyle(Theme.accent)
            Text("Try the remote buttons below.")
                .font(.small)
                .secondaryText()
        }
    }
}

/// Shown until the first reading arrives.
fileprivate struct WaitingForReading: View {
    var body: some View {
        HStack(spacing: 10) {
            ProgressView().tint(Theme.tealBright)
            Text("Start the video on the TV: readings appear as soon as the camera sees it.")
                .font(.captionBrand)
                .secondaryText()
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

// MARK: - 3, 4 + fine-tune: live white balance

struct WhiteBalanceStep: View {
    @ObservedObject var guided: GuidedController
    let kind: WBKind
    let pid: String
    @State private var still: (q: [Double], since: Date)?

    var brand: Brand { guided.tv.brand }
    var suggestion: Int? { kind == .fine ? guided.suggestions[pid] : nil }

    /// "10% grey", "50% grey (92 nits)", "100 nits".
    var greyName: String {
        var s = shortName(Patterns.patch(hdr: guided.hdr, id: pid)?.name ?? pid)
        if let r = s.range(of: "%") { s.replaceSubrange(r, with: "% grey") }
        return s
    }

    /// What to adjust: "Adjust Red and Blue Gain".
    var instructions: String {
        switch kind {
        case .gain: return "Adjust Red and Blue \(brand.gainName)"
        case .offset: return "Adjust Red and Blue \(brand.offsetName)"
        case .fine:
            if let s = suggestion { return "Adjust Red and Blue on the \(s)% control" }
            return "Adjust Red and Blue on the control nearest this grey"
        }
    }

    var whereText: String {
        kind == .fine ? brand.whiteBalancePath.replacingOccurrences(of: " (set it to 2 Point)", with: "") + " › 10 or 20 Point"
            : brand.whiteBalancePath
    }

    /// The reading, when it's for the pattern on screen.
    var valid: LiveReading? {
        guard let r = guided.live.reading, r.mismatch == nil else { return nil }
        return r
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            header
            live
            if guided.tv.isDemo {
                DemoRemote(guided: guided, controls: demoControls)
                    .padding(.top, 4)
            }
            HStack(spacing: 20) {
                Button("Can't get closer") {
                    guided.markLimit(kind == .gain ? "bright" : kind == .offset ? "dark" : "fine:\(pid)")
                    next(done: true)
                }
                if kind == .fine && nextGrey(after: pid) != nil {
                    Button("Back to the check") { guided.go(.check) }
                }
            }
            .buttonStyle(LinkButtonStyle())
            VStack(alignment: .leading, spacing: 6) {
                Text("TV menu: \(whereText)")
                    .font(.small)
                    .secondaryText()
                    .fixedSize(horizontal: false, vertical: true)
                if let r = valid {
                    AverageLine(reading: r) { guided.live.restartAverage() }
                }
            }
        }
        .pinnedAction(nextTitle) { next(done: guided.live.reading?.steady ?? false) }
        .onAppear { guided.showLive(pid) }
        .onChange(of: guided.live.reading?.qR) { _, _ in trackStill() }
    }

    /// The instruction, then the white-point target, big: the dot has to land in the centre ring. Then the
    /// video to play (the grey's details when fine-tuning).
    @ViewBuilder var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            if kind == .fine {
                Eyebrow(text: "Fine-tune \(greyName)")
            }
            Text(instructions)
                .stepTitleStyle()
            Text("Until both bars sit on the line. Leave Green alone.")
                .font(.captionBrand)
                .secondaryText()
        }
        if let r = valid {
            BigWhiteTarget(reading: r)
        }
        if guided.tv.isDemo {
            DemoNote()
        } else if let f = guided.file(pid) {
            PlayFileCard(file: f, mode: guided.mode, compact: true,
                         patch: Patterns.patch(hdr: guided.hdr, id: pid))
        }
    }

    var nextTitle: String {
        switch kind {
        case .gain: return "Next: dark end"
        case .offset: return "Next: check"
        case .fine: return nextGrey(after: pid) != nil ? "Next grey" : "Back to the check"
        }
    }

    func nextGrey(after p: String) -> String? {
        let f = guided.plan.fine
        guard let i = f.firstIndex(of: p), i + 1 < f.count else { return nil }
        return f[i + 1]
    }

    /// Moves on. The step is marked done only when it was on target and steady (or at the TV's limit).
    /// Fine-tune works out the grey at call time: the pinned button can outlive this view.
    func next(done: Bool) {
        switch kind {
        case .gain: guided.go(.dark, markDone: done ? .bright : nil)
        case .offset: guided.go(.check, markDone: done ? .dark : nil)
        case .fine:
            let cur = guided.progress.finePid ?? guided.plan.fine[0]
            if let n = nextGrey(after: cur) {
                guided.setFinePid(n)
                guided.showLive(n)
            } else {
                guided.go(.check)
            }
        }
    }

    var demoControls: [(label: String, key: String)] {
        switch kind {
        case .gain: return [("Red \(brand.gainName)", "gain:R"), ("Blue \(brand.gainName)", "gain:B")]
        case .offset: return [("Red \(brand.offsetName)", "offset:R"), ("Blue \(brand.offsetName)", "offset:B")]
        case .fine:
            let p = suggestion ?? Int((Patterns.patch(hdr: guided.hdr, id: pid)?.percent ?? 50).rounded())
            return [("Red \(p)%", "p20:R:\(p)"), ("Blue \(p)%", "p20:B:\(p)")]
        }
    }

    /// Fine-tune: notice when the bars haven't moved for a minute (maybe the wrong control).
    func trackStill() {
        guard kind == .fine, let r = guided.live.reading, let qR = r.qR, let qB = r.qB else { return }
        if let s = still, abs(s.q[0] - qR) <= 0.006, abs(s.q[1] - qB) <= 0.006 { return }
        still = ([qR, qB], Date())
    }

    @ViewBuilder var live: some View {
        if let e = guided.live.error {
            TipRow(tone: .limit, text: e)
        }
        if let r = guided.live.reading {
            if let m = r.mismatch {
                TipRow(tone: .limit, text: "\(m) Is the right file playing, with the camera pointed at the centre?")
            } else {
                VStack(alignment: .leading, spacing: 14) {
                    ChannelBalanceRow(label: "Red", value: r.qR, colour: Theme.red, chime: chimeKey("R"))
                    ChannelBalanceRow(label: "Blue", value: r.qB, colour: Theme.blue, chime: chimeKey("B"))
                    BalanceScale()
                }
                if let stuck = stillNotice(r) {
                    TipRow(tone: .limit, text: stuck)
                }
            }
        } else {
            WaitingForReading()
        }
    }

    /// The success chime's name for one channel of this step: per TV, format, step and grey.
    func chimeKey(_ channel: String) -> String {
        "\(guided.tv.id)|\(guided.mode)|\(kind)|\(pid)|\(channel)"
    }

    /// Fine-tune: the bars haven't moved for a minute, so it may be the wrong control.
    func stillNotice(_ r: LiveReading) -> String? {
        guard kind == .fine, !r.ok, let s = still, Date().timeIntervalSince(s.since) > 60 else { return nil }
        return "The bars haven't moved for a minute. If you've been adjusting, it may be the wrong control: try the one a step above or below, and when you find it, pick it under “The control that moves this grey”."
    }

    func tips(_ r: LiveReading) -> [(text: String, kind: TipList.TipKind)] {
        if r.steady { return [("Both channels are on target. Keep these TV settings.", .done)] }
        var out = [wbTip("Red", r.qR ?? 0), wbTip("Blue", r.qB ?? 0)]
        if kind == .fine, !r.ok, let s = still, Date().timeIntervalSince(s.since) > 60 {
            out.append(("The bars haven't moved for a minute. If you've been adjusting, it may be the wrong control: try the one a step above or below, and when you find it, pick it under “The control that moves this grey”.", .limit))
        }
        return out
    }

    func wbTip(_ name: String, _ e: Double) -> (text: String, kind: TipList.TipKind) {
        if abs(e) <= 0.012 { return ("\(name) is on target", .ok) }
        let clicks = e.isFinite ? clamp(Int((abs(e) / 0.008).rounded()), 1, 10) : 1
        let control: String
        switch kind {
        case .gain: control = "\(name) \(brand.gainName)"
        case .offset: control = "\(name) \(brand.offsetName)"
        case .fine: control = suggestion.map { "\(name) on the \($0)% control" } ?? "\(name) on the multipoint control"
        }
        return ("\(name) is \(fmt(abs(e) * 100, 0))% \(e > 0 ? "too strong" : "too weak"): "
                + "\(e > 0 ? "lower" : "raise") \(control) about \(clicks) click\(clicks > 1 ? "s" : ""), then watch the bar.", .todo)
    }
}

/// The white-point target, as big as the screen allows: where the grey is now (the dot) against D65 (the
/// centre). The main thing to watch while adjusting.
fileprivate struct BigWhiteTarget: View {
    let reading: LiveReading

    var body: some View {
        // Beside its message, so the Red and Blue bars below still fit on screen.
        HStack(alignment: .center, spacing: 16) {
            WhiteTarget(uv: reading.uv, d65: reading.d65uv, ok: reading.ok)
                .frame(width: 150, height: 150)
            VStack(alignment: .leading, spacing: 6) {
                Text(reading.steady ? "On target"
                     : reading.ok ? "In the ring. Hold still…" : "Bring the dot into the ring")
                    .font(.bodyStrong)
                    .foregroundStyle(reading.ok ? Theme.green : Color.white)
                    .fixedSize(horizontal: false, vertical: true)
                Text("dE \(fmt(reading.de, 1)) from D65")
                    .font(.small)
                    .monospacedDigit()
                    .secondaryText()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("White point target")
        .accessibilityValue(reading.steady ? "On target" : "dE \(fmt(reading.de, 1)) from D65")
    }
}

// MARK: - 6. Colours, one at a time

/// One of the six colours: yellow ring when it's the one on screen, a check when done, a minus at the TV's limit.
fileprivate struct ColourChip: View {
    let id: String
    let selected: Bool
    let done: Bool
    let limit: Bool
    let action: () -> Void

    var body: some View {
        let c = Theme.swatch(id)
        Button(action: action) {
            VStack(spacing: 5) {
                ZStack {
                    Circle().fill(selected ? c.opacity(0.15) : Theme.card)
                    Circle().strokeBorder(selected ? Theme.accent : c, lineWidth: 1.5)
                    if done {
                        Image(systemName: limit ? "minus" : "checkmark")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(c)
                    } else {
                        Circle().fill(c).frame(width: 5, height: 5)
                    }
                }
                .frame(width: 32, height: 32)
                Text(id.capitalized)
                    .font(.micro)
                    .foregroundStyle(selected ? Color.white : Theme.textSecondary)
                    .lineLimit(1)
                    .fixedSize()
            }
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(id.capitalized + (limit ? ", as close as this TV gets" : done ? ", done" : ""))
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

struct ColoursStep: View {
    @ObservedObject var guided: GuidedController
    /// "Next colour" was tapped before this colour got on target: ask whether it's as close as it gets.
    @State private var unfinished: String?

    var current: String {
        guided.progress.colour ?? Patterns.colourIDs.first { !guided.progress.colours.contains($0) } ?? "RED"
    }
    var cms: ColourMenu { guided.tv.colourMenu }

    var body: some View {
        if !guided.tv.colourMenuKnown && !guided.tv.isDemo {
            MenuQuestionCard(tv: guided.tv, colours: true)
        } else {
            colours
        }
    }

    @ViewBuilder var colours: some View {
        let cur = current
        let name = cur.capitalized
        VStack(alignment: .leading, spacing: 12) {
            if guided.tv.isDemo { DemoNote() }
            Text("Bring \(name) onto its ring")
                .stepTitleStyle()
            HStack(alignment: .top, spacing: 0) {
                ForEach(Patterns.colourIDs, id: \.self) { c in
                    ColourChip(id: c, selected: c == cur,
                               done: guided.progress.colours.contains(c),
                               limit: guided.progress.colours.contains(c) && guided.progress.limits.contains(c)) {
                        guided.setColour(c)
                        guided.showLive(c)
                    }
                }
            }
            if guided.latestBefore == nil {
                TipRow(tone: .limit, text: "Run the Before check first, so brightness can be judged against this TV's own white.")
            } else if !guided.tv.isDemo, let before = guided.lastSession?.before,
                      let note = GamutCheck.note(Analysis.analyse(before, hdr: guided.hdr), mode: guided.mode, brand: guided.tv.brand) {
                TipRow(tone: .limit, text: note)
            }
            if !guided.tv.isDemo, let f = guided.file(cur) { PlayFileCard(file: f, mode: guided.mode) }
            liveView(cur)
            if guided.tv.isDemo && cms != .off {
                DemoRemote(guided: guided, controls: cms == .rgbSliders
                           ? [("\(name) Red", "cms:\(cur):R"), ("\(name) Green", "cms:\(cur):G"), ("\(name) Blue", "cms:\(cur):B")]
                           : [("\(name) \(guided.tv.brand.cmsNames.hue)", "hsl:\(cur):hue"),
                              ("\(name) \(guided.tv.brand.cmsNames.saturation)", "hsl:\(cur):sat"),
                              ("\(name) \(guided.tv.brand.cmsNames.brightness)", "hsl:\(cur):lum")])
            }
            Button("Can't get closer") {
                let c = current
                guided.markLimit(c)
                guided.finishColour(c, done: true)
                show()
            }
            .buttonStyle(LinkButtonStyle())
            VStack(alignment: .leading, spacing: 6) {
                if cms == .off {
                    Text("No colour controls are set for this TV: just check the colours, or choose its colour menu in Screen settings.")
                        .font(.small)
                        .secondaryText()
                        .fixedSize(horizontal: false, vertical: true)
                }
                Text("TV menu: \(guided.tv.brand.colourMenuPath) › \(name)")
                    .font(.small)
                    .secondaryText()
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .pinnedAction(isLastColour(cur) ? "Finish colours" : "Next colour") {
            // Worked out at tap time: the pinned button can outlive this colour.
            let c = current
            let onTarget = (guided.live.reading?.id == c && (guided.live.reading?.steady ?? false)) || cms == .off
            if onTarget {
                guided.finishColour(c, done: true)
                show()
            } else {
                unfinished = c
            }
        }
        .confirmationDialog("\((unfinished ?? cur).capitalized) isn't on target yet",
                            isPresented: Binding(get: { unfinished != nil }, set: { if !$0 { unfinished = nil } }),
                            titleVisibility: .visible) {
            Button("Move on: it's as close as this TV gets") {
                if let c = unfinished {
                    guided.markLimit(c)
                    guided.finishColour(c, done: true)
                    show()
                }
                unfinished = nil
            }
            Button("Keep adjusting", role: .cancel) { unfinished = nil }
        } message: {
            Text("If the sliders have stopped bringing the dot closer to its ring, the TV has reached its limit for this colour. That's common, and the app will note it in your results.")
        }
        .onAppear { show() }
    }

    func show() {
        if guided.progress.step == .colours { guided.showLive(current) }
    }

    /// Every other colour is already done.
    func isLastColour(_ c: String) -> Bool {
        Patterns.colourIDs.allSatisfy { $0 == c || guided.progress.colours.contains($0) }
    }

    @ViewBuilder func liveView(_ cur: String) -> some View {
        if let e = guided.live.error {
            TipRow(tone: .limit, text: e)
        }
        if let r = guided.live.reading, r.id == cur {
            if let m = r.mismatch {
                TipRow(tone: .limit, text: "\(m) Is the right file playing, with the camera pointed at the centre?")
            } else {
                if let t = r.tuv {
                    VStack(spacing: 4) {
                        GamutChart(dots: [GamutChart.Dot(id: cur, uv: r.uv, target: t, de: r.de)], hdr: guided.hdr)
                            .frame(maxHeight: 220)
                        Text("Ring: target · dot: now · dE \(fmt(r.de, 1))")
                            .font(.small)
                            .monospacedDigit()
                            .secondaryText()
                    }
                    .frame(maxWidth: .infinity)
                }
                controlRows(r, cur)
                AverageLine(reading: r) { guided.live.restartAverage() }
                TipList(tips: tips(r, cur))
            }
        } else {
            WaitingForReading()
        }
    }

    @ViewBuilder func controlRows(_ r: LiveReading, _ cur: String) -> some View {
        let colour = Theme.swatch(cur)
        if cms == .rgbSliders {
            let h = r.rgbHint
            let stuck = r.outOfReach?.channels ?? []
            VStack(spacing: 14) {
                ForEach(Array(["Red", "Green", "Blue"].enumerated()), id: \.offset) { j, label in
                    // The hint says how far to move the slider (+ raise); drawn as the error (+ too much).
                    let v: Double? = (h == nil || stuck.contains(["R", "G", "B"][j])) ? nil : -h![j] * 100
                    ColourControlRow(label: label, value: v, colour: colour, fullScale: 20, tolerance: 3,
                                     caption: v.map { $0 > 0 ? "Too much: lower it" : "Too little: raise it" },
                                     chime: chimeKey(cur, label))
                }
                BalanceScale()
            }
            if r.rgbApprox {
                Text("Estimated: the Before check didn't get all three primaries.")
                    .font(.small)
                    .secondaryText()
                    .fixedSize(horizontal: false, vertical: true)
            }
        } else {
            let n = guided.tv.brand.cmsNames
            VStack(spacing: 14) {
                ColourControlRow(label: n.hue, value: r.hue, colour: colour, fullScale: 10, tolerance: 2,
                                 caption: "Leans toward \(r.toward?.capitalized ?? "a neighbour"): move \(n.hue) a click and keep going the way that shrinks the bar",
                                 chime: chimeKey(cur, "hue"))
                ColourControlRow(label: n.saturation, value: r.sat, colour: colour, fullScale: 15, tolerance: 3,
                                 caption: (r.sat ?? 0) > 0 ? "Too strong: lower \(n.saturation)" : "Too weak: raise \(n.saturation)",
                                 chime: chimeKey(cur, "sat"))
                ColourControlRow(label: n.brightness, value: r.lum, colour: colour, fullScale: 25, tolerance: 5,
                                 caption: (r.lum ?? 0) > 0 ? "Too bright: lower \(n.brightness)" : "Too dim: raise \(n.brightness)",
                                 chime: chimeKey(cur, "lum"))
                BalanceScale()
            }
        }
    }

    /// The success chime's name for one control of one colour: per TV, format, colour and control.
    func chimeKey(_ colour: String, _ control: String) -> String {
        "\(guided.tv.id)|\(guided.mode)|colour|\(colour)|\(control)"
    }

    func tips(_ d: LiveReading, _ cur: String) -> [(text: String, kind: TipList.TipKind)] {
        let name = cur.capitalized
        if d.steady { return [("\(name) is on target. Nice.", .done)] }
        var t: [(text: String, kind: TipList.TipKind)] = []
        switch cms {
        case .off:
            return [("Nothing to adjust on this TV; just note the numbers.", .ok)]
        case .hsl:
            break                                   // each bar says what to do
        case .rgbSliders:
            guard let h = d.rgbHint else {
                return [("Run the Before check first: the Red/Green/Blue hints need this TV's white as a reference.", .todo)]
            }
            let names = ["Red", "Green", "Blue"]
            let stuck = (d.outOfReach?.channels ?? []).compactMap { ["R", "G", "B"].firstIndex(of: $0) }
            let order = [0, 1, 2].filter { !stuck.contains($0) }.sorted { abs(h[$0]) > abs(h[$1]) }
            _ = order                               // each bar says what to do
            if let o = d.outOfReach {
                let others = [0, 1, 2].filter { !stuck.contains($0) }.map { names[$0].lowercased() }.joined(separator: " and ")
                t.append(("**Out of reach:** to match exactly, \(name) would need \(stuck.map { names[$0] }.joined(separator: " and ")) below zero. \(name) sits beyond what this TV's \(others) can mix, so that slider stops helping once it reaches zero. The best this colour can get is about **dE \(fmt(o.bestDE))**. When you're there, use **Can't get closer**.", .limit))
            }
        }
        if t.isEmpty && d.ok { t.append(("Close enough - holding steady…", .ok)) }
        return t
    }
}

// MARK: - Fine-tune (multipoint) and the point finder

struct FineStep: View {
    @ObservedObject var guided: GuidedController
    /// Keeps the point finder on screen through its second measurement and its result page (where the
    /// user is told to put the controls back), even though the finder's map is ready by then.
    @State private var holdingFinder = false

    var body: some View {
        if !guided.tv.multipointKnown && !guided.tv.isDemo {
            MenuQuestionCard(tv: guided.tv, colours: false)
        } else if guided.tv.multipoint == .none {
            VStack(alignment: .leading, spacing: 10) {
                Text("Fine-tune").stepTitleStyle()
                Text("This TV has only 2-point white balance (Screen settings), so there's nothing to fine-tune beyond the bright and dark ends.")
                    .font(.bodyBrand)
                    .secondaryText()
                    .fixedSize(horizontal: false, vertical: true)
            }
            .pinnedAction("Back to the check") { guided.go(.check) }
        } else if holdingFinder || (!guided.finderReady && !guided.finderSkipped) {
            PointFinderView(guided: guided, holding: $holdingFinder)
        } else {
            FineTuneView(guided: guided)
        }
    }
}

struct FineTuneView: View {
    @ObservedObject var guided: GuidedController

    var current: String { guided.progress.finePid ?? guided.plan.fine[0] }

    var body: some View {
        let cur = current
        let sugg = guided.suggestions
        VStack(alignment: .leading, spacing: 16) {
            WhiteBalanceStep(guided: guided, kind: .fine, pid: cur)
                .id(cur)
            VStack(alignment: .leading, spacing: 8) {
                Eyebrow(text: "Greys")
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(guided.plan.fine, id: \.self) { pid in
                            let selected = pid == cur
                            Button {
                                guided.setFinePid(pid)
                                guided.showLive(pid)
                            } label: {
                                Text(shortName(Patterns.patch(hdr: guided.hdr, id: pid)?.name ?? pid)
                                     + (sugg[pid].map { " → TV \($0)%" } ?? "")
                                     + (guided.state.finder.overrides[pid] != nil ? " ✓" : ""))
                                    .font(.captionBrand)
                                    .foregroundStyle(selected ? Color.white : Theme.textSecondary)
                                    .padding(.vertical, 8)
                                    .padding(.horizontal, 12)
                                    .background(Capsule().fill(selected ? Theme.surface : Theme.card))
                                    .overlay(Capsule().stroke(selected ? Theme.accent : Theme.hairline,
                                                              lineWidth: selected ? 1.5 : 1))
                            }
                            .buttonStyle(.plain)
                            .accessibilityAddTraits(selected ? .isSelected : [])
                        }
                    }
                    .padding(.vertical, 1)
                }
            }
            Card(spacing: 8) {
                if !guided.finderSkipped {
                    HStack(spacing: 8) {
                        Text("The control that moves this grey")
                            .font(.bodyStrong)
                            .foregroundStyle(.white)
                        Spacer(minLength: 4)
                        Picker("The control that moves this grey", selection: Binding(
                            get: { guided.state.finder.overrides[cur] ?? 0 },
                            set: { guided.setOverride(cur, point: $0 == 0 ? nil : $0) })) {
                            Text(sugg[cur].map { "TV \($0)% (measured)" } ?? "Measured").tag(0)
                            ForEach(Array(stride(from: guided.grid, through: 100, by: guided.grid)), id: \.self) { v in
                                Text("TV \(v)%").tag(v)
                            }
                        }
                        .pickerStyle(.menu)
                        .tint(Theme.tealBright)
                        .font(.bodyBrand)
                    }
                    Text("If you pick one, it's remembered for this TV, and the suggestions for nearby greys adjust to match.")
                        .font(.small)
                        .secondaryText()
                        .fixedSize(horizontal: false, vertical: true)
                    Button("Run the point finder again") { guided.resetFinder() }
                        .buttonStyle(LinkButtonStyle(font: .captionStrong))
                } else {
                    Text("If the bars don't move, try the control one step lower or higher.")
                        .font(.small)
                        .secondaryText()
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }
}

/// One line of the point finder's progress: done (check), happening now (spinner) or still to come (clock).
fileprivate struct FinderStatusRow: View {
    enum Status { case done, active, waiting }
    let text: String
    let state: Status

    var body: some View {
        HStack(spacing: 12) {
            Text(text)
                .font(.bodyBrand)
                .foregroundStyle(state == .waiting ? Theme.textSecondary : Color.white)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
            Group {
                switch state {
                case .done:
                    Image(systemName: "checkmark").font(.system(size: 15, weight: .semibold))
                case .active:
                    ProgressView().controlSize(.small).tint(Theme.tealBright)
                case .waiting:
                    Image(systemName: "clock").font(.system(size: 16))
                }
            }
            .foregroundStyle(Theme.tealBright)
            .frame(width: 20, height: 20)
        }
        .frame(minHeight: 48)
    }
}

struct PointFinderView: View {
    @ObservedObject var guided: GuidedController
    /// True from the second measurement until the result page is left: keeps this view on screen.
    @Binding var holding: Bool
    @State private var showingResult = false

    var haveBase: Bool { guided.state.finder.base != nil }

    /// 1: measure as it is, 2: nudge four controls, 3: measure again.
    var step: Int {
        if !haveBase { return 1 }
        return guided.runner.running ? 3 : 2
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if showingResult {
                result
            } else {
                finding
            }
        }
        .pinnedAction(pinnedTitle) { pinnedTap() }
    }

    /// The main action for the state on screen; none while measuring (Cancel is in the page).
    var pinnedTitle: String? {
        if showingResult { return "Fine-tune with these points" }
        if guided.runner.running { return nil }
        return haveBase ? "I've made the four changes - measure" : "Measure (about 2½ minutes)"
    }

    func pinnedTap() {
        if showingResult {
            showingResult = false
            holding = false
            guided.go(.fine)
        } else if guided.runner.running {
            return
        } else if haveBase {
            holding = true
            Task {
                await guided.measureFinder(nudged: true)
                if guided.finderMap.count >= 2 { showingResult = true } else { holding = false }
            }
        } else {
            Task { await guided.measureFinder(nudged: false) }
        }
    }

    @ViewBuilder var result: some View {
        Text("Here's what each control moves").stepTitleStyle()
        Text("TV control → moves the grey at")
            .font(.bodyBrand)
            .secondaryText()
        Card(spacing: 4) {
            ForEach(Array(guided.finderMap.enumerated()), id: \.offset) { _, m in
                let shift = m.level - Double(m.point)
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(verbatim: "\(m.channel == "R" ? "Red" : m.channel == "B" ? "Blue" : "TV") \(m.point)")
                        .font(.brand(16, relativeTo: .body))
                        .foregroundStyle(.white)
                    Spacer(minLength: 8)
                    VStack(alignment: .trailing, spacing: 2) {
                        Text(verbatim: "→ \(fmt(m.level, 0))%")
                            .font(.brand(17, relativeTo: .body))
                            .foregroundStyle(Theme.tealBright)
                        Text(verbatim: "Shift \(signed(shift, 0))")
                            .font(.tiny)
                            .foregroundStyle(abs(shift) >= 5 ? Theme.limit : Theme.green)
                    }
                }
                .frame(minHeight: 52)
                .accessibilityElement(children: .combine)
            }
        }
        TipRow(tone: .todo, text: "**Now put them back.** Set those four controls back to where they were (−10 each) before fine-tuning.")
    }

    var stepOneText: String {
        "Measure the greys as they are. Tap Measure, then play **\(guided.finderFile)** on the TV."
    }

    @ViewBuilder var finding: some View {
        Text(guided.runner.running ? "Finding your TV's points" : "Find your TV's controls").stepTitleStyle()
        Card(hero: true) {
            Eyebrow(text: "Optional · about 6 minutes")
            Text("Step \(step) of 3")
                .font(.cardTitle)
                .tracking(-0.6)
                .foregroundStyle(.white)
            switch step {
            case 1:
                Text(LocalizedStringKey(stepOneText))
                    .font(.bodyBrand)
                    .foregroundStyle(.white)
                    .fixedSize(horizontal: false, vertical: true)
            case 2:
                Text("Nudge four controls in the multipoint menu, exactly as listed:")
                    .font(.bodyBrand)
                    .foregroundStyle(.white)
                    .fixedSize(horizontal: false, vertical: true)
                Grid(alignment: .leading, horizontalSpacing: 18, verticalSpacing: 6) {
                    ForEach(Array(PointFinder.nudges.enumerated()), id: \.offset) { _, n in
                        GridRow {
                            Text(verbatim: "TV \(n.point)% control").foregroundStyle(.white)
                            Text(n.channel == "R" ? "Red" : "Blue").foregroundStyle(Theme.channel(n.channel))
                            Text(verbatim: "+\(PointFinder.nudge)").foregroundStyle(.white)
                        }
                    }
                }
                .font(.bodyBrand)
                Text("Then measure again with the same video. The app compares the two and finds each control.")
                    .font(.captionBrand)
                    .secondaryText()
                    .fixedSize(horizontal: false, vertical: true)
            default:
                Text("Measure again with the same video. The app compares the two and finds each control.")
                    .font(.bodyBrand)
                    .foregroundStyle(.white)
                    .fixedSize(horizontal: false, vertical: true)
            }
            HStack(spacing: 7) {
                ForEach(1...3, id: \.self) { i in
                    Capsule()
                        .fill(i <= step ? Theme.tealBright : Theme.hairline)
                        .frame(height: 6)
                }
            }
            .accessibilityHidden(true)
        }
        Text("A TV's “40%” control often moves a different grey. This finds where each one really acts.")
            .font(.captionBrand)
            .secondaryText()
            .fixedSize(horizontal: false, vertical: true)
        if case .failed(let why) = guided.runner.phase {
            TipRow(tone: .limit, text: why)
        }
        if guided.runner.running {
            MeasuringCard(runner: guided.runner, file: guided.finderFile, mode: guided.mode)
        }
        Card(spacing: 0) {
            FinderStatusRow(text: "Measure the greys as they are",
                            state: haveBase ? .done : guided.runner.running ? .active : .waiting)
            FinderStatusRow(text: "Nudge four controls (+\(PointFinder.nudge) each)",
                            state: step == 3 ? .done : step == 2 ? .active : .waiting)
            FinderStatusRow(text: "Measure again and find each control",
                            state: step == 3 ? .active : .waiting)
        }
        if guided.runner.running {
            Button("Cancel") { guided.runner.cancel() }
                .buttonStyle(SecondaryButtonStyle())
        } else {
            Button("Skip: fine-tune without it") { guided.skipFinder() }
                .buttonStyle(LinkButtonStyle())
        }
    }
}

// MARK: - Demo remote

/// Demo TVs only: − / + buttons that stand in for the TV's menus.
struct DemoRemote: View {
    @ObservedObject var guided: GuidedController
    let controls: [(label: String, key: String)]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(Array(controls.enumerated()), id: \.offset) { _, c in
                HStack(spacing: 12) {
                    Text(c.label)
                        .font(.bodyBrand)
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    key("\u{2212}", accessibility: "\(c.label) down") { guided.demoPress([c.key: -1]) }
                    key("+", accessibility: "\(c.label) up") { guided.demoPress([c.key: 1]) }
                }
            }
        }
    }

    func key(_ symbol: String, accessibility: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(symbol)
                .font(.brand(24, relativeTo: .title2))
                .foregroundStyle(Theme.tealBright)
                .frame(width: 48, height: 44)
                .background(Capsule().fill(Theme.surface))
                .overlay(Capsule().stroke(Theme.tealBright, lineWidth: 1))
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibility)
    }
}
