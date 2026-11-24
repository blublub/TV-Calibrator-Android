import CalibrationKit
import SwiftUI

/// Settings (Figma "14 Settings"): the camera profile, the pattern videos and about, each on a card.
struct SettingsView: View {
    @EnvironmentObject var app: AppModel
    @ObservedObject var profiles: ProfileService
    @EnvironmentObject var links: LinksService
    @ObservedObject private var notifications = Notifications.shared
    @AppStorage(Chime.enabledKey) private var sounds = true

    var version: String {
        let v = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
        let b = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?"
        return "\(v) (\(b))"
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                profileCard
                videosCard
                notificationsCard
                aboutCard
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
        }
        .screenBackground()
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
    }

    // MARK: Camera profile

    private var profileCard: some View {
        Card(spacing: 12) {
            Eyebrow(text: "Camera profile")
            SettingsRow(title: "This iPhone",
                        detail: profiles.profile == nil ? profiles.model : "\(profiles.model) · \(profiles.profileLabel)",
                        symbol: "camera")
            if let p = profiles.profile {
                if profiles.isStandard {
                    Text(profiles.summary)
                        .font(.captionBrand)
                        .foregroundStyle(Theme.limit)
                        .fixedSize(horizontal: false, vertical: true)
                }
                SettingsRow(title: "TVs matched",
                            detail: "Matched on \(p.stats.tvs.count) TV\(p.stats.tvs.count == 1 ? "" : "s")",
                            symbol: "checkmark")
                if let u = p.stats.unseen {
                    SettingsRow(title: "Accuracy: \(p.accuracyWord)", detail: "dE \(fmt(u, 2)) on a new TV",
                                symbol: "info.circle")
                } else if let a = p.stats.deAvg {
                    SettingsRow(title: "Accuracy: \(p.accuracyWord)", detail: "dE \(fmt(a, 2)) in training",
                                symbol: "info.circle")
                }
                if let ap = p.camera.aperture {
                    SettingsRow(title: "Aperture", detail: String(format: "f/%.1f", ap), symbol: "camera.aperture")
                }
            } else {
                Text(profiles.summary)
                    .font(.captionBrand)
                    .secondaryText()
                    .fixedSize(horizontal: false, vertical: true)
            }
            VStack(alignment: .leading, spacing: 0) {
                Button("Check for a newer profile") { Task { await profiles.refresh() } }
                    .buttonStyle(LinkButtonStyle())
                if let d = profiles.lastChecked {
                    Text("Last checked \(d.formatted(date: .abbreviated, time: .shortened))")
                        .font(.small)
                        .secondaryText()
                }
            }
            Text("Each iPhone model's camera is matched to a professional colorimeter, so the readings mean the same thing on every phone. Profiles come built into the app; new models and updates download automatically. An iPhone without its own profile uses the standard one.")
                .font(.captionBrand)
                .secondaryText()
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    // MARK: Pattern videos

    private var videosCard: some View {
        Card(spacing: 12) {
            Eyebrow(text: "Pattern videos")
            ForEach([SignalMode.sdr, .hdr10], id: \.self) { m in
                if let url = links.links.playlist(m) {
                    Link(destination: url) {
                        SettingsRow(title: "\(m.label) playlist", detail: "YouTube", symbol: "arrow.up.right.square")
                    }
                    .buttonStyle(.plain)
                }
            }
            ForEach([SignalMode.hdr10p, .dv], id: \.self) { m in
                if let url = links.links.download(m) {
                    Link(destination: url) {
                        SettingsRow(title: "\(m.label) patterns", detail: "USB download", symbol: "arrow.down.circle")
                    }
                    .buttonStyle(.plain)
                }
            }
            if let url = links.links.explainerURL {
                Link(destination: url) {
                    SettingsRow(title: "How calibration works", detail: "The Slidebean video", symbol: "play.rectangle")
                }
                .buttonStyle(.plain)
            }
            if let help = links.links.helpURL {
                Link(destination: help) {
                    SettingsRow(title: "Help and videos", symbol: "questionmark.circle")
                }
                .buttonStyle(.plain)
            }
            Text("SDR and HDR10 patterns play from YouTube in the TV's own YouTube app. HDR10+ and Dolby Vision patterns are a download, played from a USB stick or a media app.")
                .font(.captionBrand)
                .secondaryText()
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    // MARK: Notifications and sounds

    private var notificationsCard: some View {
        Card(spacing: 12) {
            Eyebrow(text: "Notifications and sounds")
            switch notifications.status {
            case .authorized, .provisional, .ephemeral:
                Toggle(isOn: $notifications.recheck) {
                    SettingsRow(title: "Re-check reminders", detail: "Six months after calibrating a screen")
                }
                if Notifications.pushConfigured {
                    Toggle(isOn: $notifications.videos) {
                        SettingsRow(title: "New Slidebean videos", detail: "When a new video goes up on YouTube")
                    }
                }
            case .denied:
                SettingsRow(title: "Notifications are off", detail: "Turn them on for TV Calibrator in the iPhone's Settings",
                            symbol: "bell.slash")
                if let url = URL(string: UIApplication.openNotificationSettingsURLString) {
                    Link("Open Settings", destination: url)
                        .buttonStyle(LinkButtonStyle())
                }
            default:
                SettingsRow(title: "Notifications", detail: "Re-check reminders and new Slidebean videos", symbol: "bell")
                Button("Turn on notifications") { Task { await notifications.turnOn() } }
                    .buttonStyle(LinkButtonStyle())
            }
            Toggle(isOn: $sounds) {
                SettingsRow(title: "Sounds", detail: "A chime when a setting reaches its target")
            }
        }
        .task { await notifications.refreshStatus() }
    }

    // MARK: About

    private var aboutCard: some View {
        Card(spacing: 12) {
            SettingsRow(title: "TV Calibrator by Slidebean", detail: "Version \(version)", symbol: "info.circle")
            if let ig = links.links.instagramURL {
                Link(destination: ig) {
                    SettingsRow(title: "Questions or feedback?", detail: "Send us a message on Instagram",
                                symbol: "bubble.left")
                }
                .buttonStyle(.plain)
            }
            if AppModel.isLabBuild {
                NavigationLink(value: Route.lab) {
                    SettingsRow(title: "Lab mode", detail: "Train with the Mac", symbol: "flask")
                }
                .buttonStyle(.plain)
            }
            Text("Measurements stay on this iPhone; the app only downloads camera profiles.")
                .font(.captionBrand)
                .secondaryText()
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

/// A settings row: a label over a secondary line, with a teal icon on the right (Figma "Settings row").
private struct SettingsRow: View {
    let title: String
    var detail: String? = nil
    var symbol: String? = nil

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.bodyBrand)
                    .foregroundStyle(.white)
                    .fixedSize(horizontal: false, vertical: true)
                if let detail {
                    Text(detail)
                        .font(.captionBrand)
                        .secondaryText()
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if let symbol {
                Image(systemName: symbol)
                    .font(.system(size: 18, weight: .regular))
                    .foregroundStyle(Theme.tealBright)
                    .frame(width: 22)
            }
        }
        .frame(minHeight: 48)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}
