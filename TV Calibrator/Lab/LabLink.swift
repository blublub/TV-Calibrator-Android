import CalibrationKit
import Foundation
import Network
import SwiftUI
import UIKit

#if LAB_MODE

// Lab mode: matching a new iPhone model to the pro calibrator.
//
// The Mac app (TV Calibrator, with the Display Plus HL) runs "Train the iPhone camera": it plays
// patches, measures each with the HL, and asks the paired phone for a camera reading of the same
// patch. This file is the phone's side of that conversation - the same protocol the old Xcode-built
// iPhone app used, but taking readings with this app's own camera code, so the profile that comes out
// matches exactly what App Store users will run. Hidden from normal users (Settings > About, tap the
// version five times).

struct FoundMac: Identifiable, Hashable {
    let name: String
    let ip: String
    let port: Int
    var hostName: String?
    var id: String { name + ip }
}

@MainActor
final class LabLink: ObservableObject {
    @Published private(set) var found: [FoundMac] = []
    @Published private(set) var host: String?
    @Published private(set) var port = 8765
    @Published private(set) var token: String?
    @Published private(set) var macName = "your Mac"
    @Published private(set) var connected = false
    @Published private(set) var lastError = ""
    @Published private(set) var readingsSent = 0

    let camera: CameraMeter
    private var browser: NWBrowser?
    private var pollTask: Task<Void, Never>?
    private var lastSeq = -1
    private var lastMeasure = -1
    private var measuring = false
    private let session: URLSession
    private let defaults = UserDefaults.standard

    init(camera: CameraMeter) {
        self.camera = camera
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 3
        config.waitsForConnectivity = false
        session = URLSession(configuration: config)
        let d = UserDefaults.standard
        host = d.string(forKey: "lab.host")
        let p = d.integer(forKey: "lab.port")
        port = p == 0 ? 8765 : p
        token = d.string(forKey: "lab.token")
        macName = d.string(forKey: "lab.macName") ?? "your Mac"
    }

    var isPaired: Bool { host != nil && token != nil }

    func start() {
        if isPaired { startPolling() } else { startBrowsing() }
        camera.onLog = { [weak self] line in self?.sendLog(line) }
    }

    func stop() {
        pollTask?.cancel()
        pollTask = nil
        browser?.cancel()
        browser = nil
        connected = false
        camera.onLog = nil
    }

    // MARK: finding the Mac (it advertises _tvcal._tcp with its address)

    func startBrowsing() {
        browser?.cancel()
        let b = NWBrowser(for: .bonjourWithTXTRecord(type: "_tvcal._tcp", domain: nil), using: .tcp)
        b.browseResultsChangedHandler = { [weak self] results, _ in
            var macs: [FoundMac] = []
            for result in results {
                guard case let .service(name, _, _, _) = result.endpoint,
                      case let .bonjour(txt) = result.metadata,
                      let ip = txt["ip"] else { continue }
                macs.append(FoundMac(name: name, ip: ip, port: Int(txt["port"] ?? "") ?? 8765, hostName: txt["host"]))
            }
            let sorted = macs.sorted { $0.name < $1.name }
            Task { @MainActor in self?.found = sorted }
        }
        b.start(queue: .main)
        browser = b
    }

    static let unreachable = "Couldn't reach the Mac. Check that TV Calibrator is open on it and both are on the same Wi-Fi."

    /// Pairs with the code shown in the Mac app's sidebar. Returns nil on success, or a message.
    func pair(mac: FoundMac, code: String) async -> String? {
        var candidates = [mac.ip]
        if let h = mac.hostName { candidates.append(h) }
        var lastProblem: String?
        for candidate in candidates {
            let problem = await pair(host: candidate, port: mac.port, code: code)
            if problem == nil { return nil }
            lastProblem = problem
            if problem != Self.unreachable { return problem }
        }
        return lastProblem
    }

    func pair(host: String, port: Int, code: String) async -> String? {
        guard let url = URL(string: "http://\(host):\(port)/api/phone/pair") else { return "That address doesn't look right." }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try? JSONSerialization.data(withJSONObject: ["code": code, "device": UIDevice.current.name])
        do {
            let (data, response) = try await session.data(for: request)
            if (response as? HTTPURLResponse)?.statusCode == 200,
               let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any],
               let tok = obj["token"] as? String {
                self.host = host
                self.port = port
                self.token = tok
                self.macName = obj["mac"] as? String ?? host
                defaults.set(host, forKey: "lab.host")
                defaults.set(port, forKey: "lab.port")
                defaults.set(tok, forKey: "lab.token")
                defaults.set(self.macName, forKey: "lab.macName")
                browser?.cancel()
                browser = nil
                startPolling()
                return nil
            }
            let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
            return obj?["error"] as? String ?? "The Mac didn't accept the code."
        } catch {
            return Self.unreachable
        }
    }

    func forget() {
        pollTask?.cancel()
        pollTask = nil
        host = nil
        token = nil
        connected = false
        for key in ["lab.host", "lab.port", "lab.token", "lab.macName"] { defaults.removeObject(forKey: key) }
        startBrowsing()
    }

    // MARK: following the Mac

    private func startPolling() {
        pollTask?.cancel()
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                let pause = await self.pollOnce()
                try? await Task.sleep(nanoseconds: pause)
            }
        }
    }

    private func pollOnce() async -> UInt64 {
        guard let host, let token else { return 1_000_000_000 }
        var parts = URLComponents()
        parts.scheme = "http"
        parts.host = host
        parts.port = port
        parts.path = "/api/phone/pattern"
        parts.queryItems = [
            URLQueryItem(name: "t", value: token),
            URLQueryItem(name: "ext", value: "0"),
            URLQueryItem(name: "device", value: UIDevice.current.name),
            URLQueryItem(name: "cam", value: camera.ready ? "1" : "0"),
            URLQueryItem(name: "model", value: DeviceModel.identifier),
        ]
        guard let url = parts.url else { return 1_000_000_000 }
        do {
            let (data, response) = try await session.data(from: url)
            if (response as? HTTPURLResponse)?.statusCode == 403 {      // the Mac forgot this phone
                forget()
                return 1_000_000_000
            }
            guard let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { return 1_000_000_000 }
            connected = true
            lastError = ""
            if let seq = obj["seq"] as? Int, seq != lastSeq {
                lastSeq = seq
                await acknowledge(seq)
            }
            if let m = obj["measure"] as? Int, m > lastMeasure, !measuring {
                lastMeasure = m
                measuring = true
                Task { await self.answerMeasurement(m) }
            }
            return 120_000_000
        } catch {
            connected = false
            lastError = (error as NSError).code == NSURLErrorTimedOut
                ? "No answer from \(host):\(port). Check the Mac's firewall and that both are on the same Wi-Fi."
                : error.localizedDescription
            return 1_000_000_000
        }
    }

    private func post(_ path: String, _ body: [String: Any]) async {
        guard let host, let token, let url = URL(string: "http://\(host):\(port)\(path)?t=\(token)") else { return }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)
        _ = try? await session.data(for: request)
    }

    private func acknowledge(_ seq: Int) async {
        await post("/api/phone/ack", ["seq": seq])
    }

    /// The Mac asked for a camera reading: take it and send the raw numbers back.
    private func answerMeasurement(_ seq: Int) async {
        defer { measuring = false }
        let (reading, problem) = await camera.measure()
        var body: [String: Any] = ["seq": seq, "ok": reading != nil, "model": DeviceModel.identifier,
                                   "ios": UIDevice.current.systemVersion]
        if let reading {
            body["rgb"] = reading.rgb
            body["duration"] = reading.duration
            body["uneven"] = reading.uneven
            body["peak"] = reading.peak
            body["iso"] = reading.settings.iso ?? 0
            body["format"] = reading.settings.format ?? ""
            body["lens"] = reading.settings.lens ?? ""
            if let ap = reading.settings.aperture { body["aperture"] = ap }
            readingsSent += 1
        } else {
            body["error"] = problem ?? "No reading."
        }
        await post("/api/phone/reading", body)
    }

    /// Sends a line to the Mac's ~/tvcal/phone.log, for troubleshooting.
    func sendLog(_ line: String) {
        guard connected else { return }
        Task { await post("/api/phone/log", ["line": line, "model": DeviceModel.identifier]) }
    }
}
#endif
