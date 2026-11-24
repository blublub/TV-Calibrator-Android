import CalibrationKit
import SwiftUI
import UniformTypeIdentifiers

/// Getting the camera ready: profile, start, darkness, placement (Figma: 05 Camera setup).
struct CameraSetupView: View {
    @EnvironmentObject var app: AppModel
    @ObservedObject var camera: CameraMeter
    @ObservedObject var profiles: ProfileService
    @Environment(\.dismiss) private var dismiss
    @State private var test: TestOutcome?
    @State private var showLog = false
    /// Measuring the darkness again (after an iOS update, say) while a measurement is already kept.
    @State private var redoDark = false

    /// What a test reading gave.
    enum TestOutcome {
        case reading(luminance: Double, x: Double, y: Double, uneven: Bool)
        case failed(String)
    }

    /// Where the setup is: one main (yellow) action per stage.
    enum Stage { case start, darkness, placement, ready }

    var stage: Stage {
        if !camera.running { return .start }
        if camera.dark == nil || redoDark { return .darkness }
        if testPassed { return .ready }
        return .placement
    }

    var testPassed: Bool {
        if case .reading(_, _, _, let uneven)? = test { return !uneven }
        return false
    }

    var body: some View {
        VStack(spacing: 0) {
            // The preview stays put while the steps scroll underneath.
            VStack(spacing: 8) {
                CameraViewfinder(camera: camera)
                    .frame(height: 240)
                Text("Blurry on purpose: it averages out the TV's pixels")
                    .font(.small)
                    .secondaryText()
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, 20)
            .padding(.top, 12)
            .padding(.bottom, 4)
            .background(Theme.background)

            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if profiles.isStandard {
                        TipRow(tone: .limit, text: "Using the standard profile: readings are approximate.")
                    }
                    switch stage {
                    case .start: startStep
                    case .darkness: darknessStep
                    case .placement: placementStep
                    case .ready: readySummary
                    }
                    if stage != .start || !camera.logLines.isEmpty {
                        cameraLog
                    }
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 12)
            }
        }
        .screenBackground()
        .navigationTitle("Set up the camera")
        .navigationBarTitleDisplayMode(.inline)
        .bottomAction {
            mainAction
                .buttonStyle(PrimaryButtonStyle())
        }
        .animation(.easeInOut(duration: 0.2), value: stage)
        .onChange(of: camera.running) { _, running in
            if !running {
                test = nil
                redoDark = false
            }
        }
    }

    // MARK: - The pinned main action

    @ViewBuilder var mainAction: some View {
        switch stage {
        case .start:
            Button("Start the camera") { Task { await app.startCamera() } }
                .disabled(profiles.profile == nil || camera.busy)
        case .darkness:
            Button(darknessActionTitle) { Task { await measureDark() } }
                .disabled(camera.busy)
        case .placement:
            Button("Test reading") { Task { await runTest() } }
                .disabled(!camera.ready || camera.busy || profiles.profile == nil)
        case .ready:
            Button("Done") { dismiss() }
        }
    }

    var darknessActionTitle: String {
        if redoDark || messageTip?.tone == .limit { return "Measure the darkness again" }
        return "Measure the darkness"
    }

    // MARK: - Stages

    /// Steps 1 and 2: the camera profile, then starting the camera.
    @ViewBuilder var startStep: some View {
        if let tip = messageTip {
            TipRow(tone: tip.tone, text: tip.text)
        }
        stepCard("1 · Camera profile") {
            if profiles.profile != nil && profiles.isStandard {
                Text("No matching camera profile is installed for this iPhone.")
                    .font(.bodyBrand)
                    .foregroundStyle(.white)
                Text("The standard profile works for now. Settings checks for a newer one.")
                    .font(.captionBrand)
                    .secondaryText()
            } else if profiles.profile != nil {
                Text(profiles.summary)
                    .font(.bodyStrong)
                    .foregroundStyle(.white)
            } else {
                Text(profiles.summary)
                    .font(.bodyBrand)
                    .foregroundStyle(.white)
                Button("Check again") { Task { await profiles.refresh() } }
                    .buttonStyle(SecondaryButtonStyle())
            }
        }
        stepCard("2 · Start camera") {
            Text("Exposure and focus stay locked after you start.")
                .font(.bodyBrand)
                .foregroundStyle(.white)
        }
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text("Next: measure darkness")
                    .font(.bodyBrand)
                    .foregroundStyle(.white)
                Text("Then place your phone on a stand")
                    .font(.captionBrand)
                    .secondaryText()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Image(systemName: "arrow.down")
                .font(.system(size: 15, weight: .regular))
                .foregroundStyle(Theme.tealBright)
                .accessibilityHidden(true)
        }
        .frame(minHeight: 48)
    }

    /// Step 3: the sensor's baseline with the lens in total darkness.
    @ViewBuilder var darknessStep: some View {
        stepCard(doneSoFar) {
            Text("3 · Measure darkness")
                .font(.cardTitle)
                .tracking(-0.6)
                .foregroundStyle(.white)
            DarknessIllustration()
            Text("This needs **total darkness**: lay the iPhone screen-up on a table, so the camera is pressed flat against it. A fingertip isn't enough.")
                .font(.captionBrand)
                .secondaryText()
                .fixedSize(horizontal: false, vertical: true)
            if redoDark && !camera.busy {
                Button("Keep the last measurement") { redoDark = false }
                    .buttonStyle(LinkButtonStyle(font: .captionStrong))
            }
        }
        if let tip = messageTip {
            TipRow(tone: tip.tone, text: tip.text)
        }
    }

    /// Step 4: the phone on a stand, pointing at the middle of the TV, and a test reading.
    @ViewBuilder var placementStep: some View {
        Text("4 · Place the phone")
            .stepTitleStyle()
        PlacementIllustration()
        Text("A hand's length (15–30 cm) from the TV, square to it, level with the centre. The grey patch should fill the yellow box with room to spare.")
            .font(.bodyBrand)
            .secondaryText()
            .fixedSize(horizontal: false, vertical: true)
        if let tip = messageTip {
            TipRow(tone: tip.tone, text: tip.text)
        }
        switch test {
        case .reading(let luminance, let x, let y, let uneven)?:
            TipRow(tone: .ok, text: "Test reading received")
            if uneven {
                TipRow(tone: .limit, text: "The box isn't evenly lit: move closer so the patch fills it.")
            }
            Card {
                Text("\(fmt(luminance, luminance < 10 ? 2 : 1)) cd/m²")
                    .font(.bodyStrong)
                    .monospacedDigit()
                    .foregroundStyle(.white)
                Text(String(format: "x %.4f · y %.4f", x, y))
                    .font(.captionBrand)
                    .monospacedDigit()
                    .secondaryText()
            }
        case .failed(let why)?:
            TipRow(tone: .limit, text: why)
        case nil:
            EmptyView()
        }
    }

    /// Everything done: the green summary.
    @ViewBuilder var readySummary: some View {
        Card {
            Text(doneSoFar)
                .font(.small)
                .foregroundStyle(Theme.green)
            Text("✓ Darkness measured")
                .font(.bodyBrand)
                .foregroundStyle(Theme.green)
            Text("✓ Phone placement checked")
                .font(.bodyBrand)
                .foregroundStyle(Theme.green)
            Text(lockedSummary)
                .font(.captionBrand)
                .secondaryText()
        }
        TipRow(tone: .ok, text: "Ready to measure. Keep the phone still.")
    }

    // MARK: - Camera log

    var cameraLog: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button {
                withAnimation(.easeInOut(duration: 0.2)) { showLog.toggle() }
            } label: {
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Camera log")
                            .font(.bodyBrand)
                            .foregroundStyle(.white)
                        Text(checksText)
                            .font(.captionBrand)
                            .secondaryText()
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    Image(systemName: "chevron.down")
                        .font(.system(size: 15, weight: .regular))
                        .foregroundStyle(Theme.tealBright)
                        .rotationEffect(.degrees(showLog ? 180 : 0))
                }
                .frame(minHeight: 48)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityValue(showLog ? "Shown" : "Hidden")

            if showLog {
                VStack(alignment: .leading, spacing: 8) {
                    if camera.running {
                        Text(lockedText)
                            .font(.captionBrand)
                            .secondaryText()
                            .fixedSize(horizontal: false, vertical: true)
                        VStack(alignment: .leading, spacing: 0) {
                            if camera.dark != nil && stage != .darkness {
                                Button("Measure the darkness again") {
                                    test = nil
                                    redoDark = true
                                }
                                .disabled(camera.busy)
                            }
                            if stage == .ready {
                                Button("Test again") { test = nil }
                                    .disabled(camera.busy)
                            }
                            Button("Stop the camera") { camera.stop() }
                                .disabled(camera.busy)
                        }
                        .buttonStyle(LinkButtonStyle(font: .captionStrong))
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        ForEach(Array(camera.logLines.suffix(40).enumerated()), id: \.offset) { _, line in
                            Text(line)
                                .font(.tiny.monospaced())
                                .secondaryText()
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    /// "4 setup checks complete" / "2 of 4 setup checks complete".
    var checksText: String {
        let done = [profiles.profile != nil, camera.running, camera.running && camera.dark != nil && !redoDark, testPassed]
            .filter { $0 }.count
        return done == 4 ? "4 setup checks complete" : "\(done) of 4 setup checks complete"
    }

    // MARK: - Text

    /// The steps already done, shown above the next one.
    var doneSoFar: String {
        profiles.profile != nil ? "✓ Profile · ✓ camera locked" : "✓ Camera locked"
    }

    var lockedText: String {
        let s = camera.settings
        let ap = s.aperture.map { String(format: "aperture f/%.1f", $0) } ?? "fixed aperture"
        let size = camera.engine.capturedSize.isEmpty ? "" : ", RAW photos \(camera.engine.capturedSize)"
        return "Locked: back main camera, ISO \(Int(s.iso ?? 0)), \(ap), focus closest\(size)"
    }

    /// One line for the ready summary.
    var lockedSummary: String {
        let s = camera.settings
        let ap = s.aperture.map { String(format: "f/%.1f", $0) } ?? "fixed aperture"
        return "Settings locked · ISO \(Int(s.iso ?? 0)) · \(ap) · focus fixed"
    }

    /// The camera's own message, when it says something the stages don't: progress (teal) or a problem
    /// (orange), such as light getting in while measuring the darkness.
    var messageTip: (tone: TipRow.Tone, text: String)? {
        let m = camera.message
        let quiet = ["The camera is off.", "Cover the lens and measure the darkness once.", "Ready to measure.",
                     "Ready. Point the box at the centre of the TV."]
        if m.isEmpty || quiet.contains(m) { return nil }
        // "Measuring with the lens covered…" would contradict the screen-down instructions; the button
        // being busy says enough.
        if m.hasPrefix("Measuring") { return nil }
        let tone: TipRow.Tone = m.hasSuffix("…") ? .todo : .limit
        return (tone: tone, text: m)
    }

    // MARK: - Actions

    func measureDark() async {
        test = nil
        await camera.measureDark()
        if camera.message.hasPrefix("Ready") { redoDark = false }
    }

    func runTest() async {
        guard let p = profiles.profile else { return }
        let meter = CameraXYZMeter(camera: camera, profile: p, checkSettings: !profiles.isStandard)
        do {
            let r = try await meter.read()
            guard let xyz = r.xyz else {
                test = .failed("No reading. Is the TV showing something bright in the box?")
                return
            }
            let a = LiveAnalysis.of(xyz, hdr: false)
            let uneven = (camera.lastReading?.uneven ?? 0) > 0.05
            test = .reading(luminance: xyz.y, x: a.xy.x, y: a.xy.y, uneven: uneven)
        } catch {
            test = .failed(error.localizedDescription)
        }
    }

    // MARK: - Parts

    /// A step card: a teal eyebrow ("1 · Camera profile"), then the step.
    func stepCard<C: View>(_ eyebrow: String, @ViewBuilder _ content: () -> C) -> some View {
        Card(spacing: 12) {
            Text(eyebrow)
                .font(.small)
                .foregroundStyle(Theme.tealBright)
            content()
        }
    }
}

/// The phone lying screen-up on a table, in the dark (design: darkness "Instruction illustration").
private struct DarknessIllustration: View {
    var body: some View {
        VStack(spacing: 0) {
            Image(systemName: "moon")
                .font(.system(size: 22, weight: .regular))
                .foregroundStyle(Theme.accent)
                .frame(width: 28, height: 28)
                .padding(.top, 10)
            RoundedRectangle(cornerRadius: 8)
                .fill(Theme.background)
                .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(Theme.tealBright, lineWidth: 2))
                .frame(width: 160, height: 18)
                .padding(.top, 10)
            Rectangle()
                .fill(Theme.tealBright)
                .frame(height: 2)
                .padding(.horizontal, 20)
                .padding(.top, 2)
            Text("Screen facing up")
                .font(.tiny)
                .secondaryText()
                .padding(.top, 5)
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity)
        .frame(height: 100)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 18))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("The iPhone lying screen-up on a table, its back camera pressed flat against it")
    }
}

/// The TV with a grey patch in the middle, and the phone on its stand facing it (design: placement
/// "Instruction illustration", 190 pt tall). Drawn on the design's 353 pt canvas, centred.
private struct PlacementIllustration: View {
    var body: some View {
        GeometryReader { geo in
            let ox = (geo.size.width - 353) / 2
            ZStack(alignment: .topLeading) {
                // TV, its grey centre patch, and its stand.
                RoundedRectangle(cornerRadius: 7)
                    .fill(Theme.background)
                    .overlay(RoundedRectangle(cornerRadius: 7).strokeBorder(Theme.tealBright, lineWidth: 2))
                    .overlay(alignment: .topLeading) {
                        RoundedRectangle(cornerRadius: 1)
                            .fill(Color(red: 0x77 / 255, green: 0x7D / 255, blue: 0x7E / 255))
                            .frame(width: 76, height: 90)
                            .offset(x: 76, y: 13)
                    }
                    .frame(width: 228, height: 116)
                    .offset(x: ox + 28, y: 20)
                Rectangle()
                    .fill(Theme.tealBright)
                    .frame(width: 40, height: 3)
                    .offset(x: ox + 122, y: 141)
                // The phone, its measuring box, and the stand.
                RoundedRectangle(cornerRadius: 6)
                    .fill(Theme.background)
                    .overlay(RoundedRectangle(cornerRadius: 6).strokeBorder(Theme.tealBright, lineWidth: 2))
                    .overlay(alignment: .topLeading) {
                        RoundedRectangle(cornerRadius: 1)
                            .strokeBorder(Theme.accent, lineWidth: 1.5)
                            .frame(width: 10, height: 10)
                            .offset(x: 12, y: 25)
                    }
                    .frame(width: 35, height: 62)
                    .offset(x: ox + 270, y: 90)
                Path { p in
                    let x = ox + 287.5
                    p.move(to: CGPoint(x: x, y: 152))
                    p.addLine(to: CGPoint(x: x, y: 166))
                    p.move(to: CGPoint(x: x, y: 166))
                    p.addLine(to: CGPoint(x: ox + 268, y: 176))
                    p.move(to: CGPoint(x: x, y: 166))
                    p.addLine(to: CGPoint(x: ox + 307, y: 176))
                }
                .stroke(Theme.tealBright, style: StrokeStyle(lineWidth: 2, lineCap: .round))
                Text("15–30 cm · facing the centre")
                    .font(.tiny)
                    .secondaryText()
                    .fixedSize()
                    .offset(x: ox + 45, y: 165)
            }
            .frame(width: geo.size.width, height: geo.size.height, alignment: .topLeading)
        }
        .frame(height: 190)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 18))
        .clipShape(RoundedRectangle(cornerRadius: 18))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("The phone on a stand, 15 to 30 centimetres from the TV, facing the middle of the screen")
    }
}

/// Whatever the camera sees: white balance or the nearest colour (Figma: 13 Live meter).
struct LiveMeterView: View {
    @EnvironmentObject var app: AppModel
    @StateObject private var meter = FreeMeter()
    @State private var hdr = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                Picker("Targets", selection: $hdr) {
                    Text("SDR").tag(false)
                    Text("HDR").tag(true)
                }
                .pickerStyle(.segmented)
                .controlSize(.large)
                .padding(.bottom, 2)

                if let blocker = app.cameraBlocker {
                    TipRow(tone: .limit, text: blocker)
                    NavigationLink("Set up the camera", value: Route.camera)
                        .buttonStyle(LinkButtonStyle())
                }
                if let e = meter.error {
                    TipRow(tone: .limit, text: e)
                }
                if let a = meter.analysis {
                    if a.kind == .white, let bal = a.bal {
                        Eyebrow(text: "Live · greyscale")
                        reading(a)
                        HStack(spacing: 24) {
                            WhiteTarget(uv: a.uv, d65: a.d65uv, ok: (a.de ?? 9) < 1)
                                .frame(width: 110, height: 110)
                            VStack(alignment: .leading, spacing: 8) {
                                Text("White point")
                                    .font(.bodyStrong)
                                    .foregroundStyle(.white)
                                Text("D65 target · dE \(fmt(a.de, 2))")
                                    .font(.captionBrand)
                                    .monospacedDigit()
                                    .foregroundStyle((a.de ?? 9) < 1 ? Theme.green : Theme.textSecondary)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .frame(minHeight: 110)
                        VStack(alignment: .leading, spacing: 12) {
                            BalanceBar(label: "Red", value: bal.x - 1, colour: Theme.red)
                            BalanceBar(label: "Green", value: bal.y - 1, colour: Theme.green)
                            BalanceBar(label: "Blue", value: bal.z - 1, colour: Theme.blue)
                        }
                        if let adv = a.advice {
                            TipRow(tone: (a.de ?? 9) < 1 ? .ok : .todo, text: adv)
                        }
                    } else if let name = a.name, let t = a.tuv {
                        Eyebrow(text: "Live · colour · \(name.capitalized)")
                        reading(a)
                        GamutChart(dots: [GamutChart.Dot(id: name, uv: a.uv, target: t)], hdr: hdr)
                            .frame(maxWidth: 320, maxHeight: 225)
                            .frame(maxWidth: .infinity)
                        Card {
                            HStack(alignment: .top, spacing: 20) {
                                figure("Hue", "\(signed(a.hue))°")
                                figure("Saturation", "\(signed(a.sat))%")
                            }
                        }
                    } else {
                        reading(a)
                    }
                } else if app.cameraBlocker == nil {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text("Reading…")
                            .font(.bodyBrand)
                            .secondaryText()
                    }
                    .padding(.vertical, 8)
                }
                Text("Point the measuring box at a solid patch. Readings stay on this iPhone.")
                    .font(.small)
                    .secondaryText()
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
        }
        .screenBackground()
        .navigationTitle("Live meter")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { start() }
        .onChange(of: hdr) { _, _ in start() }
        .onDisappear { meter.stop() }
    }

    /// The light level, big enough to read from across the room, and its chromaticity.
    func reading(_ a: LiveAnalysis) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(fmt(a.Y, a.Y < 10 ? 2 : 1))
                .font(.reading(76))
                .tracking(-1)
                .foregroundStyle(.white)
                .lineLimit(1)
                .minimumScaleFactor(0.5)
            Text("cd/m²")
                .font(.brand(17, .regular, relativeTo: .body))
                .foregroundStyle(.white)
            Text(String(format: "x %.4f · y %.4f", a.xy.x, a.xy.y))
                .font(.captionBrand)
                .monospacedDigit()
                .secondaryText()
        }
        .accessibilityElement(children: .combine)
    }

    /// A labelled figure: "Hue" over "+2.4°".
    func figure(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label)
                .font(.captionBrand)
                .secondaryText()
            Text(value)
                .font(.reading(34))
                .tracking(-1)
                .foregroundStyle(.white)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    func start() {
        guard app.cameraBlocker == nil, let p = app.profiles.profile else { return }
        meter.start(meter: CameraXYZMeter(camera: app.camera, profile: p, checkSettings: !app.profiles.isStandard), hdr: hdr)
    }
}
