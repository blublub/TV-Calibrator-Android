import CalibrationKit
import Combine
import Foundation

/// Follows a pattern video with the camera: waits for the sync flashes, then measures each patch.
/// Publishes progress for the measuring card.
@MainActor
final class SweepRunner: ObservableObject {
    enum Phase: Equatable {
        case idle, waiting, measuring, done, cancelled
        case failed(String)
    }

    struct PatchProgress: Identifiable {
        let id: String
        let name: String
        let kind: PatchKind
        var Y: Double?
        var done = false
    }

    @Published private(set) var phase: Phase = .idle
    @Published private(set) var patches: [PatchProgress] = []
    @Published private(set) var index = -1
    /// Recent brightness readings, for the little live graph.
    @Published private(set) var recent: [Double] = []
    @Published private(set) var latestY: Double?
    private var task: Task<Measurements?, Never>?

    var running: Bool { phase == .waiting || phase == .measuring }

    /// Measures a video. Returns nil if cancelled or failed (the phase says why).
    func run(meter: Meter, sequence: PatternSequence, checkOrder: Bool = false) async -> Measurements? {
        cancel()
        phase = .waiting
        index = -1
        recent = []
        latestY = nil
        patches = sequence.patches.map { PatchProgress(id: $0.id, name: $0.name, kind: $0.kind) }
        let (events, cont) = AsyncStream<SweepEvent>.makeStream()
        let consumer = Task { @MainActor [weak self] in
            for await e in events { self?.handle(e) }
        }
        let work = Task<Measurements?, Never> { @MainActor [weak self] in
            do {
                let meas = try await Sweep.sweep(meter, sequence: sequence, onEvent: { event in _ = cont.yield(event) })
                cont.finish()
                await consumer.value
                if checkOrder, Sweep.outOfOrder(meas, hdr: sequence.hdr) {
                    self?.phase = .failed(MeasureError.outOfOrder.errorDescription ?? "")
                    return nil
                }
                self?.phase = .done
                return meas
            } catch is CancellationError {
                cont.finish()
                self?.phase = .cancelled
            } catch {
                cont.finish()
                self?.phase = .failed(error.localizedDescription)
            }
            return nil
        }
        task = work
        return await work.value
    }

    func cancel() {
        task?.cancel()
        task = nil
    }

    func reset() {
        cancel()
        phase = .idle
    }

    private func handle(_ e: SweepEvent) {
        switch e {
        case .waiting:
            phase = .waiting
        case .reading(let y):
            latestY = y
            if let y {
                recent.append(y)
                if recent.count > 40 { recent.removeFirst(recent.count - 40) }
            }
        case .synced:
            phase = .measuring
        case .patchStart(let i, _, _):
            index = i
        case .patch(let i, _, _, let xyz):
            if i < patches.count {
                patches[i].done = true
                patches[i].Y = xyz?.y
            }
        }
    }
}

/// Reads one pattern continuously for the live guided steps, and says how far off it is.
@MainActor
final class LiveSession: ObservableObject {
    @Published private(set) var reading: LiveReading?
    @Published private(set) var error: String?
    @Published private(set) var patch: Patch?

    private var readings: [Vec3] = []
    private var since = 0.0
    private var hdr = false
    private var whiteY: Double?
    private var basis: [Vec3]?
    private var meter: Meter?
    private var loop: Task<Void, Never>?

    /// Starts (or switches to) reading a pattern.
    func show(_ p: Patch, hdr: Bool, whiteY: Double?, basis: [Vec3]?, meter: Meter) {
        self.patch = p
        self.hdr = hdr
        self.whiteY = whiteY
        self.basis = basis
        self.meter = meter
        readings = []
        reading = nil
        error = nil
        since = MeterClock.now()
        if loop == nil {
            loop = Task { [weak self] in await self?.run() }
        }
    }

    /// Use after changing a setting, to see its effect sooner.
    func restartAverage() {
        readings = []
        since = MeterClock.now()
    }

    func stop() {
        loop?.cancel()
        loop = nil
        patch = nil
        reading = nil
    }

    private func run() async {
        while !Task.isCancelled {
            guard let p = patch, let meter else {
                try? await Task.sleep(nanoseconds: 200_000_000)
                continue
            }
            let started = MeterClock.now()
            do {
                let r = try await meter.read()
                error = nil
                // ignore readings that began before this pattern was asked for
                if let xyz = r.xyz, patch?.id == p.id, started >= since + 0.6 {
                    readings.append(xyz)
                    if readings.count > 14 { readings.removeFirst(readings.count - 14) }
                    reading = Guided.evaluate(patch: p, readings: readings, hdr: hdr, whiteY: whiteY, basis: basis)
                }
            } catch is CancellationError {
                return
            } catch {
                self.error = error.localizedDescription
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
        }
    }
}

/// The free-running live meter (no pattern needed).
@MainActor
final class FreeMeter: ObservableObject {
    @Published private(set) var analysis: LiveAnalysis?
    @Published private(set) var error: String?
    private var loop: Task<Void, Never>?

    func start(meter: Meter, hdr: Bool) {
        stop()
        loop = Task { [weak self] in
            while !Task.isCancelled {
                do {
                    let r = try await meter.read()
                    if let xyz = r.xyz { self?.analysis = LiveAnalysis.of(xyz, hdr: hdr) }
                    self?.error = nil
                } catch is CancellationError {
                    return
                } catch {
                    self?.error = error.localizedDescription
                    try? await Task.sleep(nanoseconds: 1_000_000_000)
                }
            }
        }
    }

    func stop() {
        loop?.cancel()
        loop = nil
    }
}
