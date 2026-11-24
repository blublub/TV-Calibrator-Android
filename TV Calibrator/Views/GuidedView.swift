import CalibrationKit
import SwiftUI
import UIKit

// The guided calibration, one step per screen (Figma: 07 Set up, 08 Before / Check / After,
// 10 Check and fine-tune, and the seven-step bar).

struct GuidedView: View {
    @ObservedObject var guided: GuidedController
    @State private var showingCamera = false
    /// Bumped when the camera setup closes during a live step, so the step starts reading again.
    @State private var restarts = 0

    var step: GuideStep { guided.progress.step }

    /// The hands-on steps (bright end, dark end, colours, fine-tune) show a one-line context;
    /// the others show the screen, the format and the seven-step bar.
    var compactContext: Bool { [.bright, .dark, .colours, .fine].contains(step) }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            GuidedContextHeader(guided: guided, compact: compactContext)
            if let m = guided.message {
                TipRow(tone: .limit, text: m)
                if !guided.tv.isDemo && guided.app.cameraBlocker != nil {
                    Button("Set up the camera") { showingCamera = true }
                        .buttonStyle(SecondaryButtonStyle())
                }
            }
            Group {
                switch step {
                case .setup: SetupStep(guided: guided) { showingCamera = true }
                case .before: QuickStep(guided: guided, which: .before)
                case .bright: WhiteBalanceStep(guided: guided, kind: .gain, pid: guided.plan.gain)
                case .dark: WhiteBalanceStep(guided: guided, kind: .offset, pid: guided.plan.offset)
                case .check: CheckStep(guided: guided)
                case .colours: ColoursStep(guided: guided)
                case .after: QuickStep(guided: guided, which: .after)
                case .fine: FineStep(guided: guided)
                }
            }
            .id("\(guided.mode.rawValue)-\(step.rawValue)-\(restarts)")
        }
        // A sheet, not a push: this screen is itself pushed outside the home screen's navigation path.
        .sheet(isPresented: $showingCamera, onDismiss: cameraClosed) {
            CameraSetupSheet()
        }
    }

    /// Back from the camera setup: forget the "start the camera" message once it no longer applies, and
    /// let a live step begin reading.
    private func cameraClosed() {
        guard guided.app.cameraBlocker == nil else { return }
        guided.message = nil
        if compactContext { restarts += 1 }
    }
}

/// Camera setup opened from a calibration step.
struct CameraSetupSheet: View {
    @EnvironmentObject var app: AppModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            CameraSetupView(camera: app.camera, profiles: app.profiles)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button { dismiss() } label: { Image(systemName: "xmark") }
                            .accessibilityLabel("Close")
                    }
                }
        }
    }
}

/// One step of the guided calibration as its own screen, pushed from the screen home (TVHomeView).
/// The step's main action is pinned at the bottom (`pinnedAction` in the step, `pinnedActionHost` here);
/// while a video is being measured, Cancel takes its place.
struct GuidedScreen: View {
    @ObservedObject var guided: GuidedController

    var title: String {
        guided.progress.step == .check && guided.checkReport != nil && !guided.runner.running
            ? "Check and fine-tune" : guided.progress.step.label
    }

    var body: some View {
        ScrollView {
            GuidedView(guided: guided)
                .padding(.horizontal, 20)
                .padding(.vertical, 12)
        }
        .screenBackground()
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .pinnedActionHost()
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if guided.runner.running {
                Button("Cancel") { guided.runner.cancel() }
                    .buttonStyle(SecondaryButtonStyle())
                    .padding(.horizontal, 20)
                    .padding(.top, 8)
                    .padding(.bottom, 10)
                    .background(Theme.background.opacity(0.96).ignoresSafeArea())
            }
        }
        // Measuring takes minutes with nobody touching the phone (and the demo has no camera to keep it awake).
        .onAppear { UIApplication.shared.isIdleTimerDisabled = true }
        .onDisappear {
            guided.stopLive()
            UIApplication.shared.isIdleTimerDisabled = guided.app.camera.running
        }
    }
}

/// "Living room OLED" and the format badge, then the seven-step bar; or, compact,
/// "Living room OLED · Step 3 of 7" and the badge.
private struct GuidedContextHeader: View {
    @ObservedObject var guided: GuidedController
    let compact: Bool
    @State private var navigating = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                if compact {
                    // The hands-on steps keep the bar out of the way: "Step 3 of 7" opens the step list.
                    Button { navigating = true } label: {
                        HStack(spacing: 6) {
                            Text("\(guided.tv.name) · \(StepBar.position(of: guided.progress.step))")
                                .lineLimit(1)
                            Image(systemName: "list.number")
                                .font(.system(size: 12, weight: .semibold))
                        }
                        .font(.captionStrong)
                        .foregroundStyle(Theme.tealBright)
                        .padding(.horizontal, 10)
                        .frame(minHeight: 32)
                        .background(Capsule().fill(Theme.card))
                        .overlay(Capsule().stroke(Theme.hairline, lineWidth: 1))
                        .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityHint("Shows all the steps, to go back or ahead")
                    Spacer(minLength: 0)
                } else {
                    Text(guided.tv.name)
                        .font(.captionBrand)
                        .secondaryText()
                        .lineLimit(1)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                ModeBadge(mode: guided.mode)
            }
            if !compact {
                StepBar(guided: guided)
            }
        }
        .sheet(isPresented: $navigating) {
            StepNavigatorSheet(guided: guided, screenName: guided.tv.name, mode: guided.mode) {}
        }
    }
}

/// The seven steps as buttons: done in teal with ✓, the current one in yellow with its number, the rest
/// outlined. Under them, the current step's name and "Step 3 of 7 · tap a step to jump". Tapping goes there.
struct StepBar: View {
    @ObservedObject var guided: GuidedController

    /// The step shown as current: fine-tuning belongs to the Check step.
    static func shown(_ step: GuideStep) -> GuideStep { step == .fine ? .check : step }

    /// "Step 3 of 7".
    static func position(of step: GuideStep) -> String {
        let i = (GuideStep.stepper.firstIndex(of: shown(step)) ?? 0) + 1
        return "Step \(i) of \(GuideStep.stepper.count)"
    }

    var current: GuideStep { Self.shown(guided.progress.step) }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 6) {
                ForEach(Array(GuideStep.stepper.enumerated()), id: \.element) { i, step in
                    let now = step == current
                    let done = !now && guided.progress.done.contains(step)
                    Button {
                        guided.go(step)
                    } label: {
                        Group {
                            if done {
                                Image(systemName: "checkmark")
                                    .font(.system(size: 12, weight: .bold))
                            } else {
                                Text("\(i + 1)")
                                    .font(.captionStrong)
                            }
                        }
                        .foregroundStyle(now ? Theme.background : done ? Theme.background : Theme.textSecondary)
                        .frame(maxWidth: .infinity, minHeight: 30)
                        .background(RoundedRectangle(cornerRadius: 8)
                            .fill(now ? Theme.accent : done ? Theme.tealBright : Theme.card))
                        .overlay(RoundedRectangle(cornerRadius: 8)
                            .stroke(now || done ? Color.clear : Theme.hairline, lineWidth: 1))
                        .contentShape(RoundedRectangle(cornerRadius: 8))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("\(step.label), step \(i + 1)\(now ? ", current" : done ? ", done" : "")")
                }
            }
            HStack(alignment: .firstTextBaseline) {
                Text(current.label)
                    .font(.captionBrand)
                    .foregroundStyle(Theme.accent)
                Spacer(minLength: 8)
                Text(Self.position(of: guided.progress.step) + " · tap a step to jump")
                    .font(.small)
                    .secondaryText()
            }
        }
    }
}

// MARK: - 1. Set up

/// Getting ready, one idea per page: the format, the videos, the TV's settings, the room, the phone, the camera.
struct SetupStep: View {
    @EnvironmentObject var app: AppModel
    @ObservedObject var guided: GuidedController
    /// Opens the camera setup.
    let openCamera: () -> Void

    var mode: SignalMode { guided.mode }
    var brand: Brand { guided.tv.brand }
    var brandName: String { brand == .other ? "TV" : brand.label }

    var body: some View {
        Walkthrough(pages: pages, finishTitle: "Start: measure the TV as it is",
                    section: "Getting ready") {
            guided.go(.before, markDone: .setup)
        }
        .id(mode)
    }

    var pages: [WalkPage] {
        var p: [WalkPage] = [
            WalkPage(symbol: "rectangle.stack", tint: mode.tint, title: "Calibrating \(mode.label)",
                     text: formatText, art: AnyView(FormatOrderArt(current: mode))),
            WalkPage(symbol: mode.onYouTube ? "play.circle" : "externaldrive", tint: mode.tint,
                     title: mode.onYouTube ? "The \(mode.label) videos are on YouTube" : "Download the \(mode.label) patterns",
                     text: "",
                     art: AnyView(GuidedSourceHero(mode: mode)),
                     extra: AnyView(sourceExtra)),
            WalkPage(symbol: "", tint: Theme.accent,
                     title: guided.tv.isDemo ? "On a real TV: picture settings" : "Set up the \(brandName)",
                     text: "On the TV, open Picture and match these.",
                     extra: AnyView(settingsExtra)),
            WalkPage(symbol: "moon", title: "Dim the room",
                     text: "", art: AnyView(DarkRoomArt()),
                     extra: AnyView(roomExtra)),
            WalkPage(symbol: "iphone", title: "Place the phone",
                     text: "Keep the yellow box inside the grey patch, and don’t move the phone once you start.",
                     art: AnyView(PlacementArt()),
                     lead: "A hand's length (15–30 cm) from the TV, facing its middle, not touching the screen."),
        ]
        if !guided.tv.isDemo {
            p.append(WalkPage(symbol: "", title: "Get the camera ready",
                              text: "", extra: AnyView(cameraExtra)))
        }
        return p
    }

    var formatText: String {
        switch mode {
        case .sdr:
            return "Start with everyday TV. Each format keeps its own settings on the TV."
        case .hdr10:
            return "The TV has separate settings for HDR, so it’s calibrated after SDR."
        case .hdr10p, .dv:
            return "Advanced: do SDR and HDR10 first. \(mode.label) has its own settings on the TV."
        }
    }

    var sourceExtra: some View {
        VStack(alignment: .leading, spacing: 12) {
            GuidedNoticeCard(
                title: mode.onYouTube ? "Only play videos whose title starts with ‘\(mode.packPrefix)’"
                                      : "Only play files whose name starts with ‘\(mode.packPrefix)’",
                titleColour: Theme.accent,
                text: (mode == .sdr ? "The TV should show no HDR badge. " : "The TV should show its \(mode.badge) badge. ")
                    + "You start each video with the TV's remote.")
            PatternLinkButton(mode: mode)
        }
    }

    var settingsExtra: some View {
        VStack(alignment: .leading, spacing: 12) {
            GuidedSettingsList(items: brand.setup(mode))
            Text("Menu names may vary a little by \(brandName) model.")
                .font(.captionBrand)
                .secondaryText()
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    var roomExtra: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Lights off, curtains closed, and the TV on for 20–30 minutes first: screens shift as they warm up.")
                .font(.bodyBrand)
                .secondaryText()
                .lineSpacing(4)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    var cameraExtra: some View {
        VStack(alignment: .leading, spacing: 12) {
            Button(action: openCamera) { CameraStatusRow() }
                .buttonStyle(.plain)
            Text("Next, the app measures the TV as it is, for your before and after. About two minutes.")
                .font(.captionBrand)
                .secondaryText()
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

/// The big "where the videos come from" instruction on the teal panel.
private struct GuidedSourceHero: View {
    let mode: SignalMode

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Image(systemName: mode.onYouTube ? "play.circle" : "externaldrive")
                .font(.system(size: 36, weight: .light))
                .foregroundStyle(Theme.tealBright)
            Text(mode.onYouTube
                 ? "Play the videos in your TV’s YouTube app, or cast from your iPhone."
                 : "Copy the \(mode.packFolder) folder to a USB stick and play the files with the TV’s media player.")
                .font(Font.brand(20, .semibold, relativeTo: .title3))
                .foregroundStyle(.white)
                .minimumScaleFactor(0.8)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(16)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
    }
}

/// A card with a coloured title and a line of secondary text.
private struct GuidedNoticeCard: View {
    let title: String
    let titleColour: Color
    let text: String
    var big = false

    var body: some View {
        Card(spacing: 12) {
            Text(title)
                .font(big ? Font.section : Font.bodyStrong)
                .foregroundStyle(titleColour)
                .fixedSize(horizontal: false, vertical: true)
            Text(text)
                .font(big ? Font.bodyBrand : Font.captionBrand)
                .secondaryText()
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

/// One TV setting: "Picture mode" over "Filmmaker Mode".
private struct GuidedSettingRow {
    let label: String?
    let value: String
}

/// The TV settings to make, ticked: each "Name: value" from the brand's list as a label over its value.
private struct GuidedSettingsList: View {
    let items: [String]

    var rows: [GuidedSettingRow] {
        var rows: [GuidedSettingRow] = []
        for item in items {
            // "Gamma: BT.1886, Colour Temperature: Warm 50" holds two settings.
            let parts = item.components(separatedBy: ": ").count > 2 ? item.components(separatedBy: ", ") : [item]
            for part in parts {
                if let r = part.range(of: ": ") {
                    rows.append(GuidedSettingRow(label: String(part[..<r.lowerBound]), value: String(part[r.upperBound...])))
                } else {
                    rows.append(GuidedSettingRow(label: nil, value: part))
                }
            }
        }
        return rows
    }

    var body: some View {
        Card(spacing: 0) {
            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                HStack(alignment: .top, spacing: 14) {
                    Image(systemName: "checkmark.square")
                        .font(.system(size: 18))
                        .foregroundStyle(Theme.tealBright)
                        .padding(.top, row.label == nil ? 0 : 8)
                    VStack(alignment: .leading, spacing: 3) {
                        if let label = row.label {
                            Text(label)
                                .font(.small)
                                .secondaryText()
                        }
                        Text(row.value)
                            .font(row.label == nil ? Font.bodyBrand : Font.bodyStrong)
                            .foregroundStyle(.white)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(.vertical, 10)
                .accessibilityElement(children: .combine)
            }
        }
    }
}

/// Opens this format's YouTube playlist, or its pattern download.
struct PatternLinkButton: View {
    @EnvironmentObject var links: LinksService
    let mode: SignalMode

    var body: some View {
        if mode.onYouTube, let url = links.links.playlist(mode) {
            Link(destination: url) { Label("Open the \(mode.label) playlist", systemImage: "play.rectangle") }
                .buttonStyle(SecondaryButtonStyle())
        } else if !mode.onYouTube, let url = links.links.download(mode) {
            Link(destination: url) { Label("Download the \(mode.label) patterns", systemImage: "square.and.arrow.down") }
                .buttonStyle(SecondaryButtonStyle())
        }
    }
}

/// Where this mode's pattern videos come from: YouTube (SDR, HDR10) or a download (HDR10+, Dolby Vision).
struct PatternSourceCard: View {
    let mode: SignalMode

    var body: some View {
        Card(spacing: 12) {
            HStack(spacing: 10) {
                ModeBadge(mode: mode)
                Text(mode.onYouTube ? "Patterns on YouTube" : "Pattern files to download")
                    .font(.headlineBrand)
                    .foregroundStyle(.white)
            }
            Text(mode.onYouTube
                 ? "Play the \(mode.label) patterns from the TV Calibrator playlist on the Slidebean channel, in the TV’s YouTube app or cast from your phone, at the highest quality."
                 : "Copy the \(mode.packFolder) folder to a USB stick and play the files with the TV’s media player.")
                .font(.captionBrand)
                .secondaryText()
                .fixedSize(horizontal: false, vertical: true)
            PatternLinkButton(mode: mode)
        }
    }
}

// MARK: - Measuring a quick-check video (Before, Check, After)

/// Ready to measure: the headline, the video to play and how. Pins "Measure (about two minutes)".
private struct GuidedQuickReady: View {
    let title: String
    let file: String
    let mode: SignalMode
    let measure: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title)
                .stepTitleStyle()
            PlayFileCard(file: file, mode: mode, fromStart: true)
            OrderStrip(steps: ["Tap Measure", "Start the video on the TV"])
            Text(mode.onYouTube ? "Keep the phone still. Ads are fine: the app waits for the pattern."
                                : "Keep the phone still.")
                .font(.captionBrand)
                .secondaryText()
                .fixedSize(horizontal: false, vertical: true)
        }
        .pinnedAction("Measure (about two minutes)", action: measure)
    }
}

/// The measurement failed, most often because a video for another format (or another video) played.
/// Pins "I'll replay the SDR video", which goes back to the ready state.
private struct GuidedWrongVideo: View {
    let file: String
    let mode: SignalMode
    let why: String
    let replay: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Is the \(mode.label) video playing?")
                .stepTitleStyle()
            Card(spacing: 12) {
                HStack(spacing: 12) {
                    ModeBadge(mode: mode)
                    Image(systemName: "arrow.right")
                        .font(.system(size: 17))
                        .secondaryText()
                    Text("?")
                        .font(.badge)
                        .foregroundStyle(Theme.limit)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .background(RoundedRectangle(cornerRadius: 8).fill(Theme.limit.opacity(0.13)))
                    Spacer(minLength: 0)
                    Text("Nothing was saved")
                        .font(.small)
                        .secondaryText()
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("\(mode.label) expected. Nothing was saved.")
                Text("Replay the \(mode.label) video from the start, then measure again.")
                    .font(.bodyStrong)
                    .foregroundStyle(.white)
                    .fixedSize(horizontal: false, vertical: true)
                Text(why)
                    .font(.captionBrand)
                    .secondaryText()
                    .fixedSize(horizontal: false, vertical: true)
            }
            PlayFileCard(file: file, mode: mode, fromStart: true)
        }
        .pinnedAction("I’ll replay the \(mode.label) video", action: replay)
    }
}

// MARK: - 2 + 7. Before / after

struct QuickStep: View {
    @ObservedObject var guided: GuidedController
    let which: QuickWhich
    @State private var remeasure = false

    var result: (now: Measurements, before: Measurements?)? {
        guard let s = guided.lastSession else { return nil }
        // The step's own result stays on screen when coming back to it (until a new session starts).
        if which == .before { return s.before.map { ($0, nil) } }
        if let a = s.after { return (a, s.before) }
        return nil
    }

    var failure: String? {
        if case .failed(let why) = guided.runner.phase { return why }
        return nil
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if guided.runner.running {
                MeasuringCard(runner: guided.runner, file: guided.quickFile, mode: guided.mode)
            } else if let why = failure {
                GuidedWrongVideo(file: guided.quickFile, mode: guided.mode, why: why) {
                    guided.runner.reset()
                }
            } else if let r = result, !remeasure {
                results(now: r.now, before: r.before)
            } else {
                GuidedQuickReady(title: which == .before ? "Measure your starting point" : "One final check",
                                 file: guided.quickFile, mode: guided.mode) {
                    measure()
                }
            }
        }
        .onAppear {
            // A failure from another step's measurement doesn't belong here.
            if failure != nil { guided.runner.reset() }
        }
    }

    func measure() {
        remeasure = true
        Task {
            await guided.measureQuick(which)
            // Back to the results when it worked (or was cancelled); a failure stays on screen.
            if guided.runner.phase == .done || guided.runner.phase == .cancelled { remeasure = false }
            // The final results arrive with the success chime.
            if which == .after, guided.runner.phase == .done { Chime.shared.play() }
        }
    }

    func results(now: Measurements, before: Measurements?) -> some View {
        let report = Analysis.analyse(now, hdr: guided.hdr)
        let limits = guided.progress.limits.filter { Patterns.colourIDs.contains($0) }.map { $0.capitalized }
        return VStack(alignment: .leading, spacing: 12) {
            if which == .before {
                Text("Your starting point")
                    .stepTitleStyle()
                if !guided.tv.isDemo, let note = GamutCheck.note(report, mode: guided.mode, brand: guided.tv.brand) {
                    TipRow(tone: .limit, text: note)
                }
                ReportSummary(report: report, before: nil, hdr: guided.hdr,
                              margin: guided.tv.isDemo ? nil : guided.tv.panel)
            } else {
                ResultsView(report: report, before: before.map { Analysis.analyse($0, hdr: guided.hdr) },
                            hdr: guided.hdr, mode: guided.mode, tvName: guided.tv.name,
                            date: guided.lastSession?.finished ?? Date(),
                            margin: guided.tv.isDemo ? nil : guided.tv.panel, showsNewsletter: true)
                if !guided.tv.isDemo {
                    NotificationsCard(tvID: guided.tv.id.uuidString, tvName: guided.tv.name)
                }
                if !limits.isEmpty {
                    TipRow(tone: .limit,
                           text: "\(limits.joined(separator: ", ")): as close as this TV gets (its controls were at their limit).")
                }
            }
            Button("Measure again") { remeasure = true }
                .buttonStyle(LinkButtonStyle())
                .frame(maxWidth: .infinity)
        }
        .pinnedAction(which == .before ? "Next: fix the white balance" : "Start a new session") {
            if which == .before {
                guided.go(.bright, markDone: .before)
            } else {
                guided.newSession()
            }
        }
    }
}

/// Shown when a calibration is finished: subscribe to Untangled, the Slidebean newsletter, for early access
/// to tools like this one. Hidden for good once tapped.
struct NewsletterCard: View {
    @EnvironmentObject var links: LinksService
    @AppStorage("untangled.opened") private var opened = false

    var body: some View {
        if !opened, let url = links.links.newsletterURL {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 8) {
                    Image(systemName: "envelope.fill")
                        .font(.system(size: 14, weight: .semibold))
                    Text("UNTANGLED BY SLIDEBEAN")
                        .font(.badge)
                        .tracking(0.8)
                }
                .foregroundStyle(Theme.accent)
                Text("Access our tools before anyone else")
                    .font(.section)
                    .foregroundStyle(.white)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: 230, alignment: .leading)
                Link(destination: url) {
                    Text("Subscribe to Untangled")
                }
                .buttonStyle(PrimaryButtonStyle())
                .simultaneousGesture(TapGesture().onEnded { opened = true })
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            // The app icon's colour chart, dimmed, in the top right corner.
            .background(alignment: .topTrailing) {
                Image("BrandChart")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 200)
                    .offset(x: 46, y: -40)
                    .opacity(0.5)
                    .accessibilityHidden(true)
            }
            .background(Theme.accent.opacity(0.08))
            .clipShape(RoundedRectangle(cornerRadius: 18))
            .overlay(RoundedRectangle(cornerRadius: 18).stroke(Theme.accent, lineWidth: 1.5))
        }
    }
}

// MARK: - 5. Check the greyscale

struct CheckStep: View {
    @ObservedObject var guided: GuidedController

    var failure: String? {
        if case .failed(let why) = guided.runner.phase { return why }
        return nil
    }

    /// Fine-tuning needs a TV with 10- or 20-point white balance.
    var canFineTune: Bool { !(guided.tv.multipointKnown && guided.tv.multipoint == .none) }

    var fineTuneTitle: String {
        guided.tv.multipoint != .none && !guided.finderReady && !guided.finderSkipped
            ? "Find my TV’s points" : "Fine-tune a grey (optional)"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if guided.runner.running {
                MeasuringCard(runner: guided.runner, file: guided.quickFile, mode: guided.mode)
            } else if let why = failure {
                GuidedWrongVideo(file: guided.quickFile, mode: guided.mode, why: why) {
                    guided.runner.reset()
                }
            } else if let rep = guided.checkReport {
                summary(rep)
            } else {
                GuidedQuickReady(title: "Check the greys", file: guided.quickFile, mode: guided.mode) {
                    measure()
                }
                HStack {
                    if canFineTune {
                        Button(fineTuneTitle) { guided.go(.fine) }
                            .buttonStyle(LinkButtonStyle(font: .captionBrand))
                    }
                    Spacer(minLength: 8)
                    Button("Next: colours") { guided.go(.colours, markDone: .check) }
                        .buttonStyle(LinkButtonStyle(font: .captionBrand))
                }
            }
        }
        .onAppear {
            // A failure from another step's measurement doesn't belong here.
            if failure != nil { guided.runner.reset() }
        }
    }

    func measure() {
        Task { await guided.measureQuick(.check) }
    }

    static func onTarget(_ g: GreyResult) -> Bool {
        abs(g.redVsGreen) <= 0.015 && abs(g.blueVsGreen) <= 0.015
    }

    /// The headline from the average grey dE.
    static func verdict(_ average: Double) -> String {
        guard average.isFinite else { return "Here are your greys" }
        if average < 1 { return "On target" }
        if average < 2 { return "A very good result" }
        if average < 3 { return "A good result" }
        return "Some greys need another pass"
    }

    static func tip(off: [GreyResult], of count: Int) -> String {
        if off.isEmpty { return "Every grey is on target." }
        var ends: [String] = []
        if off.contains(where: { $0.level <= 0.4 }) { ends.append("dark end") }
        if off.contains(where: { $0.level > 0.4 && $0.level < 0.55 }) { ends.append("middle greys") }
        if off.contains(where: { $0.level >= 0.55 }) { ends.append("bright end") }
        let which = ends.joined(separator: " and the ")
        if off.count * 2 <= count {
            return "Most greys are on target. The \(which) could be a little closer."
        }
        return "The \(which) could be closer: redo it below, or fine-tune."
    }

    func summary(_ rep: Report) -> some View {
        let greys = rep.greys.filter { guided.hdr || $0.level >= 0.099 }
        let off = greys.filter { !Self.onTarget($0) }
        let average = Analysis.greyAverage(rep, hdr: guided.hdr)
        return VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(Self.verdict(average))
                    .stepTitleStyle()
                Spacer(minLength: 8)
                Text("dE \(fmt(average, 1))")
                    .font(.captionStrong)
                    .monospacedDigit()
                    .foregroundStyle(ScoreTile.tint(average))
            }
            Card {
                GreyBalanceChart(report: rep, hdr: guided.hdr)
            }
            TipRow(tone: off.isEmpty ? .ok : .todo, text: Self.tip(off: off, of: greys.count))
            Card(spacing: 0) {
                Grid(alignment: .leading, horizontalSpacing: 6, verticalSpacing: 0) {
                    GridRow {
                        heading("Grey")
                        heading("Red")
                        heading("Blue")
                        heading("dE")
                        heading("")
                    }
                    ForEach(greys) { g in
                        GridRow {
                            Text(shortName(g.name))
                                .frame(minWidth: 43, minHeight: 42, alignment: .leading)
                            Text(typographicMinus(signed(g.redVsGreen * 100)))
                                .frame(minWidth: 48, alignment: .leading)
                            Text(typographicMinus(signed(g.blueVsGreen * 100)))
                                .frame(minWidth: 48, alignment: .leading)
                            Text(fmt(g.de, 1))
                                .frame(minWidth: 40, alignment: .leading)
                            action(g)
                        }
                    }
                }
                .font(.captionBrand)
                .monospacedDigit()
                .foregroundStyle(.white)
            }
            HStack {
                Button("Measure again") { measure() }
                    .buttonStyle(LinkButtonStyle(font: .captionBrand))
                Spacer(minLength: 8)
                if canFineTune {
                    Button(fineTuneTitle) { guided.go(.fine) }
                        .buttonStyle(LinkButtonStyle(font: .captionBrand))
                }
            }
            ScoreInfo(margin: guided.tv.isDemo ? nil : guided.tv.panel)
        }
        .pinnedAction("Continue to colours") { guided.go(.colours, markDone: .check) }
    }

    func heading(_ text: String) -> some View {
        Text(text)
            .font(.tiny)
            .secondaryText()
            .padding(.bottom, 4)
    }

    @ViewBuilder func action(_ g: GreyResult) -> some View {
        if Self.onTarget(g) {
            Text("✓")
                .font(.tiny)
                .foregroundStyle(Theme.tealBright)
                .accessibilityLabel("On target")
        } else {
            let target: GuideStep = g.level >= 0.55 ? .bright : g.level <= 0.4 ? .dark : .fine
            Button(target == .bright ? "Redo bright end" : target == .dark ? "Redo dark end" : "Fine-tune") {
                guided.go(target)
            }
            .buttonStyle(LinkButtonStyle(font: .tiny))
        }
    }
}

/// "-1.1" -> "−1.1" (a real minus sign).
private func typographicMinus(_ s: String) -> String {
    s.replacingOccurrences(of: "-", with: "\u{2212}")
}

/// "50% GREY" -> "50%", "PQ 50% GREY (92 nits)" -> "50% (92 nits)".
func shortName(_ name: String) -> String {
    var s = name.replacingOccurrences(of: "PQ ", with: "").replacingOccurrences(of: " GREY", with: "")
    s = s.replacingOccurrences(of: "100% WHITE", with: "100%").replacingOccurrences(of: "100 nit WHITE", with: "100 nits")
    return s
}
