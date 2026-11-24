import CalibrationKit
import SwiftUI

enum Route: Hashable {
    case tv(UUID)
    case camera
    case live
    case settings
    case lab
}

struct RootView: View {
    @EnvironmentObject var app: AppModel
    @State private var path: [Route] = []

    var body: some View {
        NavigationStack(path: $path) {
            TVListView(path: $path)
                .navigationDestination(for: Route.self) { route in
                    switch route {
                    case .tv(let id):
                        if let tv = app.tvs.first(where: { $0.id == id }) {
                            TVHomeView(app: app, tv: tv)
                        } else {
                            Text("This screen was removed.")
                                .font(.bodyBrand)
                                .secondaryText()
                                .frame(maxWidth: .infinity, maxHeight: .infinity)
                                .screenBackground()
                        }
                    case .camera: CameraSetupView(camera: app.camera, profiles: app.profiles)
                    case .live: LiveMeterView()
                    case .settings: SettingsView(profiles: app.profiles)
                    case .lab: LabEntry()
                    }
                }
        }
    }
}

// MARK: - Home

/// The home screen (Figma "02 Home"): the camera's state, the saved screens and the tools.
struct TVListView: View {
    @EnvironmentObject var app: AppModel
    @EnvironmentObject var links: LinksService
    @Binding var path: [Route]
    /// "How it works" (WelcomeView) opens by itself once, on the first launch.
    @AppStorage("welcome.seen") private var welcomeSeen = false
    @State private var welcoming = false
    @State private var adding = false
    @State private var doomed: TVRecord?
    @State private var managing = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HomeNavigationBar { path.append(.settings) }

                VStack(alignment: .leading, spacing: 10) {
                    // The headline is for a first visit; with screens saved, they come first.
                    if app.tvs.isEmpty {
                        Text("See it the way\nit was made.")
                            .displayStyle()
                            .fixedSize(horizontal: false, vertical: true)
                        Text("Calibrate your TV’s picture with your iPhone as the meter.")
                            .font(.bodyBrand)
                            .secondaryText()
                            .fixedSize(horizontal: false, vertical: true)
                    }

                    Button { path.append(.camera) } label: { CameraStatusRow() }
                        .buttonStyle(.plain)

                    HStack {
                        Text("Your screens")
                            .font(.section)
                            .foregroundStyle(.white)
                        Spacer(minLength: 8)
                        Button("Manage Screens") { managing = true }
                            .buttonStyle(LinkButtonStyle(font: .captionStrong))
                    }

                    if app.tvs.isEmpty {
                        EmptyScreensCard(video: links.links.explainerURL) { welcoming = true }
                    }
                    ForEach(app.tvs) { tv in
                        Button { path.append(.tv(tv.id)) } label: { TVCard(tv: tv) }
                            .buttonStyle(.plain)
                            .contextMenu {
                                Button(role: .destructive) { doomed = tv } label: { Label("Delete", systemImage: "trash") }
                            }
                    }

                    Button("Add a Screen") { adding = true }
                        .buttonStyle(PrimaryButtonStyle())
                    if !app.tvs.contains(where: { $0.isDemo }) {
                        Button("Try the demo, no screen needed") {
                            let tv = app.addDemoTV()
                            path.append(.tv(tv.id))
                        }
                        .buttonStyle(SecondaryButtonStyle())
                    }

                    Group {
                        Button { welcoming = true } label: {
                            ToolTile(symbol: "play.rectangle", title: "How it works",
                                     text: links.links.explainerURL == nil ? "What you need, how it works" : "The video, and what you need")
                        }
                        Button { path.append(.live) } label: {
                            ToolTile(symbol: "viewfinder", title: "Read any colour on screen", text: "Live meter")
                        }
                        Button { path.append(.settings) } label: {
                            ToolTile(symbol: "switch.2", title: "Settings", text: "Camera profile, videos", boxed: false)
                        }
                        if AppModel.isLabBuild {
                            Button { path.append(.lab) } label: {
                                ToolTile(symbol: "flask", title: "Lab mode", text: "Train a camera profile", boxed: false)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                }
                .padding(.vertical, 12)
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 20)
        }
        .screenBackground()
        .navigationTitle("TV Calibrator")
        .toolbar(.hidden, for: .navigationBar)
        .sheet(isPresented: $adding) {
            TVSetupFlow(tv: nil) { tv in path.append(.tv(tv.id)) }
        }
        .sheet(isPresented: $managing) {
            ManageScreensView()
        }
        .sheet(isPresented: $welcoming) {
            WelcomeView { welcomeSeen = true }
        }
        .task {
            // After the opening screen has faded.
            guard !welcomeSeen, app.tvs.isEmpty else { return }
            try? await Task.sleep(nanoseconds: 2_100_000_000)
            if !welcomeSeen { welcoming = true }
        }
        .deleteScreenAlert($doomed) { tv in app.delete(tv) }
    }
}

/// The home screen's own bar: the app's name on the left, settings on the right (48 pt, as in the design).
private struct HomeNavigationBar: View {
    let openSettings: () -> Void

    var body: some View {
        HStack(spacing: 2) {
            Text("TV Calibrator")
                .font(.headlineBrand)
                .foregroundStyle(.white)
                .accessibilityAddTraits(.isHeader)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button(action: openSettings) {
                Image(systemName: "slider.horizontal.3")
                    .font(.system(size: 20, weight: .regular))
                    .foregroundStyle(Theme.tealBright)
                    .frame(width: 44, height: 44, alignment: .trailing)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Settings")
        }
        .frame(height: 48)
    }
}

/// No screens yet: how to place the phone, and what happens next.
private struct EmptyScreensCard: View {
    /// The explainer video, when its link is set.
    let video: URL?
    let howItWorks: () -> Void

    var body: some View {
        Card(spacing: 12) {
            PlacementPanel()
            if let video {
                VideoPreview(url: video, compact: true)
            } else {
                Button("New to this? See how it works", action: howItWorks)
                    .buttonStyle(LinkButtonStyle(font: .captionStrong))
            }
        }
    }
}

/// The TV with its grey centre patch, and the phone on its stand facing it (Figma "Instruction illustration").
private struct PlacementPanel: View {
    private static let patchGrey = Color(red: 0x77 / 255, green: 0x7D / 255, blue: 0x7E / 255)   // #777D7E

    var body: some View {
        GeometryReader { g in
            let w = g.size.width
            let s = w / 321
            let phoneX = w - 51
            let stemX = phoneX + 17.5
            ZStack(alignment: .topLeading) {
                // TV
                RoundedRectangle(cornerRadius: 7)
                    .fill(Theme.background)
                    .overlay(RoundedRectangle(cornerRadius: 7).strokeBorder(Theme.tealBright, lineWidth: 2))
                    .overlay(RoundedRectangle(cornerRadius: 1).fill(Self.patchGrey).frame(width: 76 * s, height: 18))
                    .frame(width: 228 * s, height: 36)
                    .offset(x: 28 * s, y: 20)
                Text("15–30 cm · facing the centre")
                    .font(.tiny)
                    .secondaryText()
                    .lineLimit(1)
                    .offset(x: 45 * s, y: 75)
                // Stand
                Path { p in
                    p.move(to: CGPoint(x: stemX, y: 66))
                    p.addLine(to: CGPoint(x: stemX, y: 76))
                    p.move(to: CGPoint(x: stemX - 20, y: 86))
                    p.addLine(to: CGPoint(x: stemX, y: 76))
                    p.addLine(to: CGPoint(x: stemX + 20, y: 86))
                }
                .stroke(Theme.tealBright, style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
                // Phone, with the measuring box
                RoundedRectangle(cornerRadius: 6)
                    .fill(Theme.background)
                    .overlay(RoundedRectangle(cornerRadius: 6).strokeBorder(Theme.tealBright, lineWidth: 2))
                    .overlay(RoundedRectangle(cornerRadius: 1).strokeBorder(Theme.accent, lineWidth: 1.5)
                        .frame(width: 10, height: 10))
                    .frame(width: 35, height: 62)
                    .offset(x: phoneX, y: 5)
            }
        }
        .frame(height: 100)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 18))
        .clipShape(RoundedRectangle(cornerRadius: 18))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Put the iPhone on a stand 15 to 30 centimetres from the TV, facing the centre of the screen")
    }
}

/// One TV on the home screen (Figma "Screen card"): name, brand and how far each format has got.
struct TVCard: View {
    @EnvironmentObject var app: AppModel
    let tv: TVRecord

    var body: some View {
        Card(spacing: 12) {
            HStack(spacing: 12) {
                IconBubble(symbol: tv.isDemo ? "sparkles.tv" : "tv", size: 44)
                VStack(alignment: .leading, spacing: 4) {
                    Text(tv.name)
                        .font(.bodyStrong)
                        .foregroundStyle(.white)
                    Text(tv.isDemo ? "Demo: simulated readings" : tv.brand.label)
                        .font(.captionBrand)
                        .secondaryText()
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "chevron.right")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(Theme.tealBright)
            }
            HStack(spacing: 6) {
                ForEach(SignalMode.allCases) { m in
                    FormatChip(mode: m, stage: stage(m))
                }
                Spacer(minLength: 0)
                if let score {
                    Text(ScoreTile.word(score))
                        .font(.captionStrong)
                        .foregroundStyle(ScoreTile.tint(score))
                        .lineLimit(1)
                }
            }
        }
        .accessibilityElement(children: .combine)
    }

    /// The greyscale score of the latest finished calibration (SDR first), shown as its word.
    private var score: Double? {
        for m in SignalMode.allCases {
            if let a = app.store.guideState(tv, mode: m).sessions.last(where: { $0.after != nil })?.after {
                let de = Analysis.greyAverage(Analysis.analyse(a, hdr: m.isHDR), hdr: m.isHDR)
                if de.isFinite { return de }
            }
        }
        return nil
    }

    /// Filled when a format is calibrated, half when started, an outline when not started.
    private func stage(_ m: SignalMode) -> FormatStage {
        let done = app.store.guideState(tv, mode: m).progress.done.count
        return done >= GuideStep.stepper.count - 1 ? .done : done > 0 ? .started : .notStarted
    }
}

private enum FormatStage { case notStarted, started, done }

/// A format on a screen's card: filled in its colour with a tick when calibrated, outlined in its
/// colour when started, faint when not started.
private struct FormatChip: View {
    let mode: SignalMode
    let stage: FormatStage

    var body: some View {
        HStack(spacing: 3) {
            if stage == .done {
                Image(systemName: "checkmark")
                    .font(.system(size: 8, weight: .bold))
            }
            Text(mode.label)
                .font(.micro)
                .fontWeight(.semibold)
                .lineLimit(1)
                .fixedSize()
        }
        .foregroundStyle(stage == .done ? Theme.background : stage == .started ? mode.tint : Theme.textSecondary)
        .padding(.horizontal, 7)
        .padding(.vertical, 4)
        .background(RoundedRectangle(cornerRadius: 6).fill(stage == .done ? mode.tint : Color.clear))
        .overlay(RoundedRectangle(cornerRadius: 6)
            .stroke(stage == .done ? Color.clear : stage == .started ? mode.tint.opacity(0.7) : Theme.hairline, lineWidth: 1))
        .accessibilityLabel("\(mode.label): \(stage == .done ? "calibrated" : stage == .started ? "started" : "not started")")
    }
}

/// A home-screen row: a label over a secondary line, with a teal icon on the right. Boxed in a card
/// (the live meter) or bare (settings).
struct ToolTile: View {
    let symbol: String
    let title: String
    let text: String
    var boxed = true

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.bodyBrand)
                    .foregroundStyle(.white)
                Text(text)
                    .font(.captionBrand)
                    .secondaryText()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Image(systemName: symbol)
                .font(.system(size: 18, weight: .regular))
                .foregroundStyle(Theme.tealBright)
                .frame(width: 22)
        }
        .frame(minHeight: 48)
        .padding(boxed ? 12 : 0)
        .background(boxed ? Theme.card : Color.clear, in: RoundedRectangle(cornerRadius: 18))
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(boxed ? Theme.hairline : Color.clear, lineWidth: 1))
        .contentShape(Rectangle())
    }
}

// MARK: - Camera status

struct CameraStatusRow: View {
    @EnvironmentObject var app: AppModel

    var body: some View {
        CameraStatusContent(profiles: app.profiles, camera: app.camera)
    }
}

/// The teal camera card (Figma "Camera status card"): checking, set up, measure the darkness, ready,
/// standard profile or unavailable.
private struct CameraStatusContent: View {
    @ObservedObject var profiles: ProfileService
    @ObservedObject var camera: CameraMeter

    private enum Phase { case checking, unavailable, setUp, darkness, standard, ready }

    /// Something for the user to do (set up, measure the darkness): the teal card. Otherwise one line.
    private var needsAction: Bool { phase == .setUp || phase == .darkness || phase == .unavailable }

    var body: some View {
        Group {
            if needsAction {
                Card(hero: true) {
                    HStack(spacing: 10) {
                        IconBubble(symbol: symbol, tint: colour, size: 36)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(title)
                                .font(.headlineBrand)
                                .foregroundStyle(colour)
                            Text(detail)
                                .font(.captionBrand)
                                .secondaryText()
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        Image(systemName: "chevron.right")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(Theme.tealBright)
                    }
                }
            } else {
                HStack(spacing: 10) {
                    stateIcon
                        .frame(width: 19, height: 19)
                    Text(title)
                        .font(.captionStrong)
                        .foregroundStyle(colour)
                    Text(phase == .standard ? "Readings approximate" : "iPhone camera")
                        .font(.small)
                        .secondaryText()
                        .lineLimit(1)
                    Spacer(minLength: 8)
                    Image(systemName: "chevron.right")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(Theme.tealBright)
                }
                .padding(.horizontal, 14)
                .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
                .background(Theme.card, in: RoundedRectangle(cornerRadius: 16))
                .overlay(RoundedRectangle(cornerRadius: 16).stroke(Theme.hairline, lineWidth: 1))
                .contentShape(Rectangle())
            }
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder private var stateIcon: some View {
        if phase == .checking {
            ProgressView()
                .controlSize(.small)
                .tint(Theme.tealBright)
        } else {
            Image(systemName: symbol)
                .font(.system(size: 17, weight: .regular))
                .foregroundStyle(colour)
        }
    }

    private var phase: Phase {
        guard profiles.profile != nil else { return profiles.status == .checking ? .checking : .unavailable }
        if !camera.running { return .setUp }
        if camera.dark == nil { return .darkness }
        return profiles.isStandard ? .standard : .ready
    }

    private var symbol: String {
        switch phase {
        case .checking: return "arrow.triangle.2.circlepath"
        case .unavailable: return "video.slash"
        case .setUp: return "camera"
        case .darkness: return "moon"
        case .standard: return "exclamationmark.triangle"
        case .ready: return "checkmark.circle"
        }
    }

    private var colour: Color {
        switch phase {
        case .checking, .setUp, .darkness: return Theme.tealBright
        case .unavailable, .standard: return Theme.limit
        case .ready: return Theme.green
        }
    }

    private var title: String {
        switch phase {
        case .checking: return "Checking this iPhone"
        case .unavailable: return "Camera not available"
        case .setUp: return "Set up the camera"
        case .darkness: return "Measure the darkness"
        case .standard: return "Standard profile"
        case .ready: return "Ready to measure"
        }
    }

    private var detail: String {
        switch phase {
        case .checking: return "Finding the right camera profile."
        case .unavailable: return profiles.summary
        case .setUp: return "A minute, before your first reading."
        case .darkness: return "Cover the camera to set its baseline."
        case .standard: return "Readings approximate. You can still get started."
        case .ready: return "Camera set up. Place your phone on its stand."
        }
    }
}

// MARK: - Manage Screens

/// Every saved screen (Figma "04 Manage Screens"): edit its menus, or delete it (button or swipe).
struct ManageScreensView: View {
    @EnvironmentObject var app: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var editing: TVRecord?
    @State private var doomed: TVRecord?
    @State private var adding = false

    var body: some View {
        NavigationStack {
            List {
                ForEach(app.tvs) { tv in
                    ManagedScreenRow(tv: tv, detail: detail(tv), onEdit: { editing = tv }, onDelete: { doomed = tv })
                        .manageRow(top: 6, bottom: 6)
                        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                            Button(role: .destructive) { doomed = tv } label: { Label("Delete", systemImage: "trash") }
                                .tint(Theme.red)
                        }
                }

                Button("Add a Screen") { adding = true }
                    .buttonStyle(SecondaryButtonStyle())
                    .manageRow(top: 6, bottom: 6)

            }
            .listStyle(.plain)
            .screenBackground()
            .overlay {
                if app.tvs.isEmpty {
                    Text("No screens yet.")
                        .font(.bodyBrand)
                        .secondaryText()
                }
            }
            .navigationTitle("Manage Screens")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { Image(systemName: "xmark") }
                        .accessibilityLabel("Close")
                }
            }
            .bottomAction {
                Button("Done") { dismiss() }
                    .buttonStyle(SecondaryButtonStyle())
            }
            .sheet(item: $editing) { tv in
                TVSetupFlow(tv: tv)
            }
            .sheet(isPresented: $adding) {
                TVSetupFlow(tv: nil)
            }
            .deleteScreenAlert($doomed) { tv in app.delete(tv) }
        }
    }

    /// "LG · SDR calibrated", "Samsung · Not started".
    private func detail(_ tv: TVRecord) -> String {
        let full = GuideStep.stepper.count - 1
        let counts = SignalMode.allCases.map { m in (m, app.store.guideState(tv, mode: m).progress.done.count) }
        let calibrated = counts.filter { $0.1 >= full }.map { $0.0.label }
        let progress: String
        if !calibrated.isEmpty {
            progress = calibrated.joined(separator: ", ") + " calibrated"
        } else if let started = counts.first(where: { $0.1 > 0 }) {
            progress = started.0.label + " in progress"
        } else {
            progress = "Not started"
        }
        return (tv.isDemo ? "Demo" : tv.brand.label) + " · " + progress
    }
}

/// One saved screen: name and progress, with edit (teal) and delete (red) buttons.
private struct ManagedScreenRow: View {
    let tv: TVRecord
    let detail: String
    let onEdit: () -> Void
    let onDelete: () -> Void

    var body: some View {
        HStack(spacing: 4) {
            VStack(alignment: .leading, spacing: 5) {
                Text(tv.name)
                    .font(.bodyStrong)
                    .foregroundStyle(.white)
                Text(detail)
                    .font(.captionBrand)
                    .secondaryText()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            if !tv.isDemo {
                Button(action: onEdit) {
                    Image(systemName: "pencil")
                        .font(.system(size: 19, weight: .regular))
                        .foregroundStyle(Theme.tealBright)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.borderless)
                .accessibilityLabel("Edit \(tv.name)")
            }
            Button(action: onDelete) {
                Image(systemName: "trash")
                    .font(.system(size: 19, weight: .regular))
                    .foregroundStyle(Theme.red)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .accessibilityLabel("Delete \(tv.name)")
        }
        .padding(.leading, 16)
        .padding(.trailing, 6)
        .padding(.vertical, 12)
        .frame(minHeight: 72)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 18))
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(Theme.hairline, lineWidth: 1))
    }
}

private extension View {
    /// A Manage Screens list row: no row background or separator, 20 pt screen margins.
    func manageRow(top: CGFloat, bottom: CGFloat) -> some View {
        listRowInsets(EdgeInsets(top: top, leading: 20, bottom: bottom, trailing: 20))
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
    }

    /// "Delete Living room OLED?" with Cancel and Delete, shown while `doomed` holds a screen.
    func deleteScreenAlert(_ doomed: Binding<TVRecord?>, delete: @escaping (TVRecord) -> Void) -> some View {
        alert("Delete \(doomed.wrappedValue?.name ?? "this screen")?",
              isPresented: Binding(get: { doomed.wrappedValue != nil }, set: { if !$0 { doomed.wrappedValue = nil } }),
              presenting: doomed.wrappedValue) { tv in
            Button("Cancel", role: .cancel) { doomed.wrappedValue = nil }
            Button("Delete", role: .destructive) {
                delete(tv)
                doomed.wrappedValue = nil
            }
        } message: { _ in
            Text("Delete the screen and its history. Its measurements and calibration progress are removed from this iPhone.")
        }
    }
}
