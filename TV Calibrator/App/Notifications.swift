import SwiftUI
import UserNotifications

/// Notifications, both opt-in and both switchable in Settings:
///  - a reminder to re-check a TV six months after calibrating it (a local notification, scheduled on the
///    iPhone; pictures drift as panels age), and
///  - a heads-up when Slidebean posts a new video on YouTube (a push from the push worker, see
///    docs/PUSH.md). Tapping it opens the video.
/// The app asks only after a finished calibration (or from Settings), never at launch. The Lab build has
/// no push entitlement, so it only does the reminders.
@MainActor
final class Notifications: NSObject, ObservableObject, UNUserNotificationCenterDelegate {
    static let shared = Notifications()

    /// The push worker: the same Cloudflare worker that serves the profiles (Config.server, read from
    /// ServerConfig.plist). Without it the app never registers for video pushes; the re-check reminders
    /// work without it.
    static var pushServer: URL? { Config.server }
    static var pushConfigured: Bool { pushServer != nil && !AppModel.isLabBuild }

    static let videosKey = "notify.videos"
    static let recheckKey = "notify.recheck"
    static let cardDismissedKey = "notify.cardDismissed"
    private static let tokenKey = "notify.token"
    /// Six months.
    static let recheckAfter: TimeInterval = 182 * 24 * 3600

    @Published private(set) var status: UNAuthorizationStatus = .notDetermined
    @Published var videos: Bool = UserDefaults.standard.object(forKey: Notifications.videosKey) as? Bool ?? true {
        didSet {
            UserDefaults.standard.set(videos, forKey: Self.videosKey)
            Task { await syncPush() }
        }
    }
    @Published var recheck: Bool = UserDefaults.standard.object(forKey: Notifications.recheckKey) as? Bool ?? true {
        didSet {
            UserDefaults.standard.set(recheck, forKey: Self.recheckKey)
            if !recheck { cancelAllRechecks() }
        }
    }

    private var center: UNUserNotificationCenter { .current() }

    /// At launch: become the notification delegate, read the permission, and re-register for pushes
    /// (Apple may hand out a new device token at any launch).
    func start() {
        center.delegate = self
        Task {
            await refreshStatus()
            if status == .authorized, Self.pushConfigured { UIApplication.shared.registerForRemoteNotifications() }
        }
    }

    func refreshStatus() async {
        status = await center.notificationSettings().authorizationStatus
    }

    /// The "Turn on notifications" button. Turns both kinds on; Settings can turn either off.
    @discardableResult
    func turnOn() async -> Bool {
        let granted = (try? await center.requestAuthorization(options: [.alert, .sound, .badge])) ?? false
        await refreshStatus()
        guard granted else { return false }
        videos = true
        recheck = true
        if Self.pushConfigured { UIApplication.shared.registerForRemoteNotifications() }
        return true
    }

    // MARK: Re-check reminders

    /// After a finished calibration: remind in six months to re-check this screen (replacing any earlier
    /// reminder for it).
    func scheduleRecheck(tvID: String, tvName: String) {
        guard recheck else { return }
        Task {
            await refreshStatus()
            guard status == .authorized else { return }
            let c = UNMutableNotificationContent()
            c.title = "Time to re-check \(tvName)"
            c.body = "TV pictures drift as the panel ages. A quick check takes a few minutes."
            c.sound = .default
            let req = UNNotificationRequest(identifier: "recheck-\(tvID)", content: c,
                                            trigger: UNTimeIntervalNotificationTrigger(timeInterval: Self.recheckAfter,
                                                                                       repeats: false))
            try? await center.add(req)
        }
    }

    /// A screen was deleted.
    func cancelRecheck(tvID: String) {
        center.removePendingNotificationRequests(withIdentifiers: ["recheck-\(tvID)"])
    }

    private func cancelAllRechecks() {
        Task {
            let ids = await center.pendingNotificationRequests().map(\.identifier).filter { $0.hasPrefix("recheck-") }
            center.removePendingNotificationRequests(withIdentifiers: ids)
        }
    }

    // MARK: Video pushes

    func didRegister(token: Data) {
        let hex = token.map { String(format: "%02x", $0) }.joined()
        UserDefaults.standard.set(hex, forKey: Self.tokenKey)
        #if DEBUG
        print("Push token:", hex)          // for a test push from the worker (docs/PUSH.md)
        #endif
        Task { await syncPush() }
    }

    /// Tells the push worker whether this iPhone wants video pushes. Only the device token is sent: no
    /// name, account or anything else.
    func syncPush() async {
        guard Self.pushConfigured, let server = Self.pushServer,
              let token = UserDefaults.standard.string(forKey: Self.tokenKey) else { return }
        let on = videos && status == .authorized
        var req = URLRequest(url: server.appendingPathComponent(on ? "register" : "unregister"))
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        #if DEBUG
        let env = "sandbox"                // Xcode builds use Apple's sandbox push service
        #else
        let env = "production"             // TestFlight and the App Store
        #endif
        req.httpBody = try? JSONSerialization.data(withJSONObject: ["token": token, "env": env])
        _ = try? await URLSession.shared.data(for: req)
    }

    // MARK: UNUserNotificationCenterDelegate

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification)
        async -> UNNotificationPresentationOptions {
        [.banner, .sound]
    }

    /// Tapping a video push opens the video (YouTube's app if installed).
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse)
        async {
        guard let s = response.notification.request.content.userInfo["url"] as? String, let url = URL(string: s),
              url.scheme == "https" else { return }
        await MainActor.run { UIApplication.shared.open(url) }
    }
}

/// Hands the push device token to Notifications.
@MainActor
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        Notifications.shared.start()
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        Notifications.shared.didRegister(token: deviceToken)
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        #if DEBUG
        print("Push registration failed:", error.localizedDescription)
        #endif
    }
}

/// After a finished calibration, once: the reason to turn notifications on. Apple requires opting in to
/// promotional pushes in the app's own words, so the card says plainly that it includes new videos.
struct NotificationsCard: View {
    @ObservedObject var notifications = Notifications.shared
    @AppStorage(Notifications.cardDismissedKey) private var dismissed = false
    let tvID: String
    let tvName: String

    var body: some View {
        if notifications.status == .notDetermined && !dismissed {
            Card(spacing: 12) {
                Eyebrow(text: "Stay calibrated")
                Text("Get a reminder to re-check \(tvName)")
                    .font(.headlineBrand)
                    .foregroundStyle(.white)
                    .fixedSize(horizontal: false, vertical: true)
                Text("In six months, plus a heads-up when Slidebean posts a new TV video. Turn either off in Settings.")
                    .font(.bodyBrand)
                    .secondaryText()
                    .fixedSize(horizontal: false, vertical: true)
                Button {
                    Task {
                        if await notifications.turnOn() {
                            notifications.scheduleRecheck(tvID: tvID, tvName: tvName)
                        }
                    }
                } label: {
                    Label("Turn on notifications", systemImage: "bell")
                }
                .buttonStyle(SecondaryButtonStyle())
                Button("Not now") { dismissed = true }
                    .buttonStyle(LinkButtonStyle())
                    .frame(maxWidth: .infinity)
            }
            .task { await notifications.refreshStatus() }
        }
    }
}
