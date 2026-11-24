import CalibrationKit
import SwiftUI
import UIKit

/// The screen home (Figma: 06 Screen home · new / progress / calibrated / menu / advanced / step navigator):
/// the formats to calibrate and how far each has got. The guided steps are their own screen
/// (`GuidedScreen`), pushed by the pinned main action.
struct TVHomeView: View {
    @EnvironmentObject var app: AppModel
    let tvID: UUID
    @State private var mode: SignalMode
    @State private var editing = false
    @State private var export: ExportItem?
    @State private var askAdvanced: SignalMode?
    @State private var deleting = false
    @State private var showingSteps = false
    @State private var navigating = false
    @State private var openAfterNavigating = false
    @Environment(\.dismiss) private var dismiss
    @StateObject private var guided: GuidedController

    init(app: AppModel, tv: TVRecord) {
        tvID = tv.id
        let saved = SignalMode(rawValue: UserDefaults.standard.string(forKey: "mode-\(tv.id.uuidString)") ?? "") ?? .sdr
        _mode = State(initialValue: saved)
        _guided = StateObject(wrappedValue: GuidedController(app: app, tv: tv, mode: saved))
    }

    var tv: TVRecord { app.tvs.first { $0.id == tvID } ?? guided.tv }

    private var total: Int { GuideStep.stepper.count }
    private var done: Int { app.store.guideState(tv, mode: mode).progress.done.count }
    private var calibrated: Bool { done >= total - 1 }

    /// The latest finished calibration (its After check, with where it started), or else the latest
    /// starting point, for this format.
    private var latest: (report: Report, before: Report?, date: Date?, finished: Bool)? {
        let sessions = guided.state.sessions
        if let s = sessions.last(where: { $0.after != nil }), let a = s.after {
            return (Analysis.analyse(a, hdr: mode.isHDR), s.before.map { Analysis.analyse($0, hdr: mode.isHDR) },
                    s.finished, true)
        }
        if let s = sessions.last(where: { $0.before != nil }), let b = s.before {
            return (Analysis.analyse(b, hdr: mode.isHDR), nil, s.started, false)
        }
        return nil
    }

    private var mainTitle: String {
        if done == 0 { return "Start with \(mode.label)" }
        return calibrated ? "View \(mode.label) results" : "Continue \(mode.label)"
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                // The report first, then the formats.
                if let latest {
                    ScreenReportCard(report: latest.report, before: latest.before, hdr: mode.isHDR, mode: mode,
                                     date: latest.date, finished: latest.finished,
                                     margin: tv.isDemo ? nil : app.profiles.marginDE(panel: tv.panel)) {
                        guided.go(latest.finished ? .after : .before)
                        showingSteps = true
                    }
                } else {
                    // Nothing measured yet: the picture of what's about to happen.
                    WalkArtPanel(height: 150) { PlacementArt() }
                }
                ModeSelector(tv: tv, mode: mode) { m in choose(m) }
                if done > 0 && !calibrated {
                    // The bar opens "Jump to a step" (the step navigator).
                    StepBar(guided: guided)
                        .allowsHitTesting(false)
                        .overlay {
                            Button { navigating = true } label: {
                                Color.clear.contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("Jump to a step")
                        }
                        .padding(.top, 4)
                }
                if tv.isDemo { demoNote }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
        }
        .screenBackground()
        .navigationTitle(tv.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) { screenMenu }
        }
        .bottomAction {
            Button(mainTitle) { showingSteps = true }
                .buttonStyle(PrimaryButtonStyle())
        }
        .navigationDestination(isPresented: $showingSteps) {
            GuidedScreen(guided: guided)
        }
        .sheet(isPresented: $editing) {
            TVSetupFlow(tv: tv)
        }
        .sheet(item: $export) { item in
            ShareSheet(items: [item.url])
        }
        .sheet(item: $askAdvanced) { m in
            AdvancedFormatSheet(mode: m) {
                UserDefaults.standard.set(true, forKey: "advanced-ok-\(tvID.uuidString)")
                mode = m
            }
        }
        .sheet(isPresented: $navigating, onDismiss: {
            // Push the step screen once the sheet has gone.
            if openAfterNavigating {
                openAfterNavigating = false
                showingSteps = true
            }
        }) {
            StepNavigatorSheet(guided: guided, screenName: tv.name, mode: mode) {
                openAfterNavigating = true
            }
        }
        .confirmationDialog("Delete \(tv.name)?", isPresented: $deleting, titleVisibility: .visible) {
            Button("Delete the screen and its history", role: .destructive) {
                let doomed = tv
                guided.stopLive()
                dismiss()
                // Remove it once this screen has left the navigation stack.
                Task { @MainActor in
                    try? await Task.sleep(nanoseconds: 500_000_000)
                    app.delete(doomed)
                }
            }
        } message: {
            Text("Its measurements and calibration progress are removed from this iPhone.")
        }
        .onChange(of: mode) { _, m in
            UserDefaults.standard.set(m.rawValue, forKey: "mode-\(tvID.uuidString)")
            guided.reload(tv: tv, mode: m)
        }
        .onChange(of: tv) { _, t in
            guided.reload(tv: t, mode: mode)
        }
        .onDisappear {
            // Leaving this screen for the home screen (not for its own steps): nothing should keep
            // measuring behind the user's back.
            if !showingSteps {
                guided.runner.cancel()
                guided.stopLive()
            }
        }
    }

    /// The screen's menu, in the navigation bar: its settings, its history, delete.
    private var screenMenu: some View {
        Menu {
            Button { editing = true } label: { Label("Screen settings", systemImage: "pencil") }
            Button {
                export = app.store.export(tv, profile: app.profiles.profile).map { ExportItem(url: $0) }
            } label: { Label("Export history", systemImage: "square.and.arrow.up") }
            Divider()
            Button(role: .destructive) { deleting = true } label: { Label("Delete this screen", systemImage: "trash") }
        } label: {
            Image(systemName: "ellipsis.circle")
                .foregroundStyle(Theme.tealBright)
        }
        .accessibilityLabel("Screen options")
    }

    private var demoNote: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "sparkles")
                .font(.system(size: 15))
                .foregroundStyle(Theme.tealBright)
            Text("Demo: simulated readings. The on-screen buttons stand in for the TV's remote.")
                .font(.captionBrand)
                .secondaryText()
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.top, 4)
    }

    private func choose(_ m: SignalMode) {
        if !m.isAdvanced || UserDefaults.standard.bool(forKey: "advanced-ok-\(tvID.uuidString)") {
            mode = m
        } else {
            askAdvanced = m
        }
    }
}

/// The latest results, first thing on the screen home: the report panel (scores on their gauges, the
/// white point, the colours), opening the full results.
private struct ScreenReportCard: View {
    let report: Report
    let before: Report?
    let hdr: Bool
    let mode: SignalMode
    let date: Date?
    let finished: Bool
    let margin: Double?
    let open: () -> Void

    var body: some View {
        ReportPanel {
            HStack(alignment: .center, spacing: 8) {
                Text(finished ? "CALIBRATED" : "STARTING POINT")
                    .font(.badge)
                    .tracking(0.8)
                    .foregroundStyle(finished ? Theme.green : Theme.accent)
                ModeBadge(mode: mode)
                Spacer(minLength: 8)
                if let date {
                    Text(date.formatted(date: .abbreviated, time: .omitted))
                        .font(.small)
                        .secondaryText()
                }
            }
            ReportHero(report: report, before: before, hdr: hdr, margin: margin)
            Button(action: open) {
                HStack(spacing: 6) {
                    Text(finished ? "Full results" : "See the starting point")
                    Image(systemName: "chevron.right")
                        .font(.system(size: 11, weight: .semibold))
                }
            }
            .buttonStyle(LinkButtonStyle(font: .captionStrong))
        }
    }
}

/// Which format to calibrate: SDR then HDR10 (YouTube) as format cards, with HDR10+ and Dolby Vision
/// in the Advanced card.
struct ModeSelector: View {
    @EnvironmentObject var app: AppModel
    let tv: TVRecord
    let mode: SignalMode
    let choose: (SignalMode) -> Void

    func stepsDone(_ m: SignalMode) -> Int { app.store.guideState(tv, mode: m).progress.done.count }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 12) {
                ForEach([SignalMode.sdr, .hdr10]) { m in
                    Button { choose(m) } label: { card(m) }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(m == mode ? .isSelected : [])
                }
            }
            advanced
        }
    }

    /// A format tile: its badge and a status mark, how far it has got, a short line, and its progress.
    func card(_ m: SignalMode) -> some View {
        let done = stepsDone(m)
        let total = GuideStep.stepper.count
        let calibrated = done >= total - 1
        let selected = m == mode
        let progress = calibrated ? 1 : Double(min(done, total)) / Double(max(total, 1))
        return VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                ModeBadge(mode: m)
                Spacer(minLength: 0)
                Image(systemName: calibrated ? "checkmark.circle.fill" : selected ? "largecircle.fill.circle" : "circle")
                    .font(.system(size: 18))
                    .foregroundStyle(calibrated ? Theme.green : selected ? m.tint : Theme.textSecondary)
            }
            Text(done == 0 ? "Not started" : calibrated ? "Calibrated" : "\(done) of \(total) steps")
                .font(.bodyStrong)
                .foregroundStyle(calibrated ? Theme.green : Color.white)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
            Text(m.blurb)
                .font(.small)
                .secondaryText()
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)
            ProgressTrack(value: progress, colour: calibrated ? Theme.green : m.tint)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 18))
        .overlay(RoundedRectangle(cornerRadius: 18)
            .stroke(selected ? m.tint.opacity(0.7) : Theme.hairline, lineWidth: selected ? 1.5 : 1))
        .contentShape(RoundedRectangle(cornerRadius: 18))
    }

    /// HDR10+ and Dolby Vision: USB files, after SDR and HDR10.
    var advanced: some View {
        HStack(spacing: 8) {
            Text("Advanced")
                .font(.captionStrong)
                .foregroundStyle(.white)
            Text("USB files")
                .font(.small)
                .secondaryText()
            Spacer(minLength: 8)
            ForEach([SignalMode.hdr10p, .dv]) { m in
                Button { choose(m) } label: { chip(m) }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(m == mode ? .isSelected : [])
            }
        }
        .padding(.horizontal, 14)
        .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 18))
        .overlay(RoundedRectangle(cornerRadius: 18)
            .stroke(mode.isAdvanced ? mode.tint.opacity(0.7) : Theme.hairline, lineWidth: mode.isAdvanced ? 1.5 : 1))
    }

    /// The format badge; filled in the format's colour when it's the one being calibrated.
    @ViewBuilder func chip(_ m: SignalMode) -> some View {
        if m == mode {
            Text(m.label)
                .font(.badge)
                .foregroundStyle(Theme.background)
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(RoundedRectangle(cornerRadius: 8).fill(m.tint))
        } else {
            ModeBadge(mode: m)
        }
    }
}

/// Asked before an advanced format (HDR10+, Dolby Vision) the first time on this screen.
private struct AdvancedFormatSheet: View {
    let mode: SignalMode
    let confirm: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    ModeBadge(mode: mode)
                    Text("\(mode.label) needs a USB drive")
                        .stepTitleStyle()
                    WalkArtPanel(height: 120) {
                        HStack(spacing: 18) {
                            Image(systemName: "square.and.arrow.down")
                            Image(systemName: "arrow.right")
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundStyle(Theme.textSecondary)
                            Image(systemName: "externaldrive")
                            Image(systemName: "arrow.right")
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundStyle(Theme.textSecondary)
                            Image(systemName: "tv")
                        }
                        .font(.system(size: 34, weight: .light))
                        .foregroundStyle(Theme.tealBright)
                        .accessibilityHidden(true)
                    }
                    Text("YouTube can't play \(mode.label): download the pattern files, copy them to a USB drive and play them on the TV. Do SDR and HDR10 first; this builds on those settings.")
                        .font(.bodyBrand)
                        .secondaryText()
                        .lineSpacing(4)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 12)
            }
            .screenBackground()
            .bottomAction {
                VStack(spacing: 10) {
                    Button("Calibrate \(mode.label) anyway") {
                        confirm()
                        dismiss()
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    Button("Not now") { dismiss() }
                        .buttonStyle(SecondaryButtonStyle())
                }
            }
            .navigationTitle("Advanced formats")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { Image(systemName: "xmark") }
                        .accessibilityLabel("Close")
                }
            }
        }
    }
}

/// "Jump to a step": the seven steps with Done / Current / Upcoming; pick one and go there.
struct StepNavigatorSheet: View {
    @ObservedObject var guided: GuidedController
    let screenName: String
    let mode: SignalMode
    /// Called after jumping, to open the step screen.
    let open: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var picked: GuideStep? = nil

    /// The fine-tune step lives under Check in the bar.
    private var currentStep: GuideStep { guided.progress.step == .fine ? .check : guided.progress.step }
    private var target: GuideStep { picked ?? currentStep }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    HStack(alignment: .center, spacing: 8) {
                        Text(screenName)
                            .font(.bodyBrand)
                            .foregroundStyle(.white)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        ModeBadge(mode: mode)
                    }
                    Card(spacing: 0) {
                        ForEach(Array(GuideStep.stepper.enumerated()), id: \.element) { i, step in
                            row(i, step)
                        }
                    }
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 12)
            }
            .screenBackground()
            .bottomAction {
                Button("Go to \(target.label.lowercased())") {
                    guided.go(target)
                    open()
                    dismiss()
                }
                .buttonStyle(PrimaryButtonStyle())
            }
            .navigationTitle("Jump to a step")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { Image(systemName: "xmark") }
                        .accessibilityLabel("Close")
                }
            }
        }
    }

    private func row(_ i: Int, _ step: GuideStep) -> some View {
        let done = guided.progress.done.contains(step)
        let isCurrent = step == currentStep
        let isTarget = step == target
        let status = isCurrent ? "Current" : done ? "Done" : "Upcoming"
        return Button { picked = step } label: {
            HStack(spacing: 14) {
                Text(done && !isTarget ? "✓" : "\(i + 1)")
                    .font(.brand(16, relativeTo: .body))
                    .foregroundStyle(isTarget ? Theme.accent : done ? Theme.tealBright : Theme.textSecondary)
                    .frame(width: 20, alignment: .leading)
                Text(step.label)
                    .font(isTarget ? Font.bodyStrong : Font.bodyBrand)
                    .foregroundStyle(isTarget ? Theme.accent : Color.white)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text(status)
                    .font(.small)
                    .secondaryText()
            }
            .frame(minHeight: 48)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isTarget ? .isSelected : [])
    }
}

struct ExportItem: Identifiable {
    let id = UUID()
    let url: URL
}

struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}
