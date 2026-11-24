import CalibrationKit
import SwiftUI
import UniformTypeIdentifiers

// Lab mode exists only in the Lab build (Xcode scheme "TV Calibrator Lab", which compiles with
// LAB_MODE). The App Store build has no Mac pairing, no local-network access and no profile import.

#if LAB_MODE
/// Lab mode: pair with the Mac app and answer its camera readings while it trains this iPhone model
/// against the Display Plus HL.
struct LabModeView: View {
    @ObservedObject var lab: LabLink
    @ObservedObject var camera: CameraMeter
    @EnvironmentObject var app: AppModel
    @State private var chosen: FoundMac?
    @State private var code = ""
    @State private var problem: String?
    @State private var busy = false

    var body: some View {
        Form {
            Section {
                Text("On the Mac: open TV Calibrator, turn on Pro tools, and use “Train the iPhone camera” on each TV (the more TVs, the better the profile for TVs it has never seen). Then export the profile from Camera profiles and publish it with build_catalog.py.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Section("Camera") {
                if camera.ready {
                    Label("Camera ready", systemImage: "checkmark.circle").foregroundStyle(Theme.green)
                } else {
                    Text("Start the camera and measure the darkness first.").foregroundStyle(.secondary)
                    Button("Start the camera") { Task { await app.startCamera() } }
                    Button("Measure darkness (cover the lens)") { Task { await camera.measureDark() } }
                        .disabled(!camera.running)
                }
            }
            LabProfileImport(profiles: app.profiles)
            if lab.isPaired {
                Section("Mac") {
                    LabeledContent("Paired with", value: lab.macName)
                    LabeledContent("Status", value: lab.connected ? "Connected" : "Not reachable")
                    LabeledContent("Readings sent", value: "\(lab.readingsSent)")
                    if !lab.lastError.isEmpty { Text(lab.lastError).font(.footnote).foregroundStyle(.orange) }
                    Button("Forget this Mac", role: .destructive) { lab.forget() }
                }
            } else {
                Section {
                    if lab.found.isEmpty {
                        HStack { ProgressView(); Text("Looking for TV Calibrator on your Wi-Fi…").foregroundStyle(.secondary) }
                    }
                    ForEach(lab.found) { mac in
                        Button { chosen = mac } label: { Label(mac.name, systemImage: "desktopcomputer") }
                    }
                } header: {
                    Text("Macs nearby")
                } footer: {
                    Text("The pairing code is in the Mac app's sidebar, under iPhone.")
                }
                if let mac = chosen {
                    Section("Pair with \(mac.name)") {
                        TextField("4-digit code", text: $code).keyboardType(.numberPad)
                        Button(busy ? "Pairing…" : "Pair") {
                            busy = true
                            Task {
                                problem = await lab.pair(mac: mac, code: code)
                                busy = false
                            }
                        }
                        .disabled(code.count != 4 || busy)
                        if let p = problem { Text(p).foregroundStyle(.orange) }
                    }
                }
            }
        }
        .navigationTitle("Lab mode")
        .onAppear { lab.start() }
        .onDisappear { lab.stop() }
    }
}

/// Lab-only: load a profile exported from the Mac app to test it before publishing.
struct LabProfileImport: View {
    @ObservedObject var profiles: ProfileService
    @State private var importing = false
    @State private var importError: String?

    var body: some View {
        Section {
            Button("Import a profile file…") { importing = true }
            if profiles.isImported {
                Button("Stop using the imported profile") { Task { await profiles.clearImported() } }
            }
            if let e = importError { Text(e).foregroundStyle(.orange) }
        } header: {
            Text("Test a profile")
        } footer: {
            Text("A profile exported from the Mac app (Camera profiles › Export), before it's published.")
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json]) { result in
            switch result {
            case .success(let url):
                do {
                    try profiles.importProfile(from: url)
                    importError = nil
                } catch {
                    importError = error.localizedDescription
                }
            case .failure(let e):
                importError = e.localizedDescription
            }
        }
    }
}

struct LabEntry: View {
    @EnvironmentObject var app: AppModel
    var body: some View { LabModeView(lab: app.lab, camera: app.camera) }
}
#else
struct LabEntry: View {
    var body: some View { EmptyView() }
}
#endif
