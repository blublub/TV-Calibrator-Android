import CalibrationKit
import Combine
import Foundation

enum QuickWhich {
    case before, after, check
}

/// One TV's guided calibration in one signal mode.
@MainActor
final class GuidedController: ObservableObject {
    let app: AppModel
    @Published private(set) var tv: TVRecord
    @Published private(set) var mode: SignalMode
    @Published private(set) var state: GuideState
    @Published var message: String?
    /// The latest Check step result (not kept between launches).
    @Published private(set) var checkReport: Report?
    let live = LiveSession()
    let runner = SweepRunner()
    let shown = ShownPatch()
    private var forwarding: [AnyCancellable] = []

    init(app: AppModel, tv: TVRecord, mode: SignalMode) {
        self.app = app
        self.tv = tv
        self.mode = mode
        self.state = app.store.guideState(tv, mode: mode)
        self.checkReport = state.check.map { Analysis.analyse($0, hdr: mode.isHDR) }
        // views observe this controller; pass on changes from the runner and the live session
        forwarding = [runner.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() },
                      live.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }]
    }

    var hdr: Bool { mode.isHDR }
    var plan: GuidePlan { .forHDR(hdr) }
    var progress: GuideProgress { state.progress }
    var lastSession: GuidedSession? { state.sessions.last }
    /// The latest session's Before measurement, if it has one.
    var latestBefore: Measurements? { state.sessions.last?.before }

    func reload(tv: TVRecord, mode: SignalMode) {
        stopLive()
        runner.reset()
        self.tv = tv
        self.mode = mode
        state = app.store.guideState(tv, mode: mode)
        checkReport = state.check.map { Analysis.analyse($0, hdr: mode.isHDR) }
        message = nil
    }

    private func save() { app.store.save(state, tv, mode: mode) }

    func go(_ step: GuideStep, markDone: GuideStep? = nil) {
        if let d = markDone, !state.progress.done.contains(d) { state.progress.done.append(d) }
        state.progress.step = step
        message = nil
        if ![.bright, .dark, .colours, .fine].contains(step) { stopLive() }
        save()
    }

    func markLimit(_ key: String) {
        if !state.progress.limits.contains(key) { state.progress.limits.append(key) }
        save()
    }

    func setColour(_ c: String?) {
        state.progress.colour = c
        save()
    }

    func finishColour(_ c: String, done: Bool) {
        if done && !state.progress.colours.contains(c) { state.progress.colours.append(c) }
        let rest = Patterns.colourIDs.filter { !state.progress.colours.contains($0) && $0 != c }
        if let next = rest.first {
            state.progress.colour = next
            save()
        } else {
            state.progress.colour = nil
            go(.after, markDone: .colours)
        }
    }

    func setFinePid(_ pid: String) {
        state.progress.finePid = pid
        save()
    }

    /// Starts again from the Before check. The finished sessions stay in the history.
    func newSession() {
        state.progress = GuideProgress(step: .before, done: [.setup])
        state.sessions.append(GuidedSession(before: nil))
        state.check = nil
        checkReport = nil
        save()
    }

    // MARK: the file to play

    func file(_ pid: String) -> String? { Patterns.packFile(mode: mode, id: pid) }
    var quickFile: String { file("QUICK") ?? "" }
    var finderFile: String { Patterns.finderFile(mode: mode) }

    // MARK: quick checks (before / after / check)

    func measureQuick(_ which: QuickWhich) async {
        stopLive()
        let seq = Patterns.quickSequence(hdr: hdr, ids: plan.quickWithColours)
        guard let meter = app.videoMeter(tv, hdr: hdr, sequence: seq) else {
            message = app.cameraBlocker
            return
        }
        guard let meas = await runner.run(meter: meter, sequence: seq, checkOrder: true) else { return }
        let rep = Analysis.analyse(meas, hdr: hdr)
        switch which {
        case .check:
            checkReport = rep
            state.check = meas
            save()
            return
        case .before:
            // Fill a fresh session (from Start a new session), or start one.
            if let last = state.sessions.last, last.before == nil, last.after == nil {
                state.sessions[state.sessions.count - 1].before = meas
                state.sessions[state.sessions.count - 1].started = Date()
            } else {
                state.sessions.append(GuidedSession(before: meas))
            }
        case .after:
            if state.sessions.isEmpty || state.sessions[state.sessions.count - 1].after != nil {
                // Measuring "after" again: keep comparing with the same starting point.
                state.sessions.append(GuidedSession(before: state.sessions.last?.before))
            }
            state.sessions[state.sessions.count - 1].after = meas
            state.sessions[state.sessions.count - 1].finished = Date()
            if !state.progress.done.contains(.after) { state.progress.done.append(.after) }
            if !tv.isDemo { Notifications.shared.scheduleRecheck(tvID: tv.id.uuidString, tvName: tv.name) }
        }
        save()
        app.store.appendLog(tv, Log.quick(mode: mode, which: which, report: rep))
    }

    // MARK: live readings

    func showLive(_ pid: String) {
        guard let p = Patterns.patch(hdr: hdr, id: pid) else { return }
        shown.patch = p
        guard let meter = app.liveMeter(tv, hdr: hdr, shown: shown) else {
            message = app.cameraBlocker
            return
        }
        message = nil
        let ref = state.reference
        live.show(p, hdr: hdr, whiteY: ref.whiteY, basis: ref.basis, meter: meter)
    }

    func stopLive() {
        live.stop()
        shown.patch = nil
    }

    /// Demo only: press the "remote".
    func demoPress(_ changes: [String: Int]) {
        app.demoApply(tv, hdr: hdr, changes)
        live.restartAverage()
    }

    // MARK: point finder

    var finderMap: [FinderPoint] {
        guard let b = state.finder.base, let n = state.finder.nudged else { return [] }
        return PointFinder.map(base: b, nudged: n, hdr: hdr)
    }

    var grid: Int { PointFinder.grid(points: tv.multipoint.rawValue) }

    var suggestions: [String: Int] {
        PointFinder.suggestions(map: finderMap, overrides: state.finder.overrides, hdr: hdr, grid: grid)
    }

    var finderReady: Bool { !finderMap.isEmpty || !state.finder.overrides.isEmpty }

    func measureFinder(nudged: Bool) async {
        stopLive()
        let seq = Patterns.finderSequence(hdr: hdr)
        let nudges = Dictionary(uniqueKeysWithValues: PointFinder.nudges.map { ("p20:\($0.channel):\($0.point)", PointFinder.nudge) })
        if tv.isDemo && nudged { app.demoApply(tv, hdr: hdr, nudges) }
        defer { if tv.isDemo && nudged { app.demoApply(tv, hdr: hdr, nudges.mapValues { -$0 }) } }
        guard let meter = app.videoMeter(tv, hdr: hdr, sequence: seq) else {
            message = app.cameraBlocker
            return
        }
        guard var meas = await runner.run(meter: meter, sequence: seq) else { return }
        meas.missed = [:]
        if !nudged {
            state.finder = FinderData(base: meas, nudged: nil, overrides: state.finder.overrides)
        } else {
            state.finder.nudged = meas
            let map = finderMap
            if map.count < 2 {
                message = "The nudges didn't show up clearly enough to find the controls. Check the four changes were "
                    + "made (+10 each), then measure the nudged pass again."
            } else {
                app.store.appendLog(tv, "\n## Point finder (\(mode.label)) \(Log.stamp())\n"
                                    + map.map { "- TV \($0.point)% control (\($0.channel)) acts on the \(String(format: "%.1f", $0.level))% grey\n" }.joined())
            }
        }
        save()
    }

    func setOverride(_ pid: String, point: Int?) {
        if let point { state.finder.overrides[pid] = point } else { state.finder.overrides.removeValue(forKey: pid) }
        save()
    }

    func resetFinder() {
        state.finder = FinderData(base: nil, nudged: nil, overrides: state.finder.overrides)
        save()
    }

    func skipFinder() {
        state.finder.overrides["_skip"] = 0
        save()
    }

    var finderSkipped: Bool { state.finder.overrides["_skip"] != nil }
}

/// The readable per-TV log (log.md), like the Mac app's.
enum Log {
    static func stamp() -> String {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd HH:mm"
        return f.string(from: Date())
    }

    static func table(_ rep: Report, hdr: Bool) -> String {
        var out = "Greyscale dE \(String(format: "%.2f", Analysis.greyAverage(rep, hdr: hdr))), colour dE "
            + "\(String(format: "%.2f", Analysis.colourAverage(rep)))\n\n| step | cd/m² | R | G | B | dE |\n|---|---|---|---|---|---|\n"
        for g in rep.greys {
            out += "| \(g.name) | \(String(format: "%.2f", g.Y)) | \(String(format: "%.1f", g.bal.x * 100)) | "
                + "\(String(format: "%.1f", g.bal.y * 100)) | \(String(format: "%.1f", g.bal.z * 100)) | \(String(format: "%.2f", g.de)) |\n"
        }
        out += "\n| colour | dE | saturation | hue | brightness |\n|---|---|---|---|---|\n"
        for c in rep.colours {
            out += "| \(c.name.capitalized) | \(String(format: "%.2f", c.de)) | \(String(format: "%+.1f", c.sat))% | "
                + "\(String(format: "%+.1f", c.hue))° | \(String(format: "%+.1f", c.lum))% |\n"
        }
        for w in rep.warnings { out += "\nWarning: \(w)\n" }
        return out
    }

    static func quick(mode: SignalMode, which: QuickWhich, report: Report) -> String {
        let label = which == .before ? "before" : which == .after ? "after" : "check"
        return "\n## Guided \(mode.label) - \(label) check (\(stamp()))\n" + table(report, hdr: mode.isHDR)
    }
}
