import CalibrationKit
import Combine
import Foundation

/// Where users get the pattern videos:
/// - SDR and HDR10: the TV Calibrator playlists on the Slidebean YouTube channel, played in the TV's
///   own YouTube app (or cast from the phone), so the TV receives the real SDR / HDR10 signal.
/// - HDR10+ and Dolby Vision: YouTube doesn't carry them, so they're downloads, played from a USB stick
///   or cast from a media app.
///
/// The links live in patterns.json on the server (next to the camera profiles), so they can change
/// without an app update. A copy ships inside the app for the first launch and offline use.
struct PatternLinks: Codable, Equatable {
    var format = 1
    /// The YouTube channel or a help page.
    var channel: String?
    var help: String?
    /// The Untangled newsletter's sign-up page, offered when a calibration is finished.
    var newsletter: String?
    /// The Slidebean video that explains calibration and this app, for people who found it in the App Store.
    var explainer: String?
    /// Slidebean's Instagram, for questions (Settings › About).
    var instagram: String?
    /// Signal mode ("sdr", "hdr10") -> playlist URL.
    var playlists: [String: String] = [:]
    /// Signal mode ("hdr10p", "dv") -> download URL (a zip of that mode's folder).
    var downloads: [String: String] = [:]
    /// Video title (the pattern-pack file name, e.g. "SDR 01 - White (bright end)") -> YouTube URL.
    var videos: [String: String] = [:]

    init() {}

    enum CodingKeys: String, CodingKey { case format, channel, help, newsletter, explainer, instagram, playlists, downloads, videos }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        format = try c.decodeIfPresent(Int.self, forKey: .format) ?? 1
        channel = try c.decodeIfPresent(String.self, forKey: .channel)
        help = try c.decodeIfPresent(String.self, forKey: .help)
        newsletter = try c.decodeIfPresent(String.self, forKey: .newsletter)
        explainer = try c.decodeIfPresent(String.self, forKey: .explainer)
        instagram = try c.decodeIfPresent(String.self, forKey: .instagram)
        playlists = try c.decodeIfPresent([String: String].self, forKey: .playlists) ?? [:]
        downloads = try c.decodeIfPresent([String: String].self, forKey: .downloads) ?? [:]
        videos = try c.decodeIfPresent([String: String].self, forKey: .videos) ?? [:]
    }

    private static func url(_ s: String?) -> URL? {
        guard let s, !s.isEmpty, let u = URL(string: s), u.scheme == "https" else { return nil }
        return u
    }

    func playlist(_ mode: SignalMode) -> URL? { Self.url(playlists[mode.rawValue]) }
    func download(_ mode: SignalMode) -> URL? { Self.url(downloads[mode.rawValue]) }
    func video(_ file: String) -> URL? { Self.url(videos[file]) }
    var helpURL: URL? { Self.url(help) ?? Self.url(channel) }
    /// The explainer video; nil until its link is filled in (the app then just leaves the video out).
    var explainerURL: URL? { Self.url(explainer) }
    /// The Instagram link from the server, or the built-in one.
    var instagramURL: URL? { Self.url(instagram) ?? URL(string: "https://www.instagram.com/slidebean/") }
    /// The newsletter link from the server, or the built-in one.
    var newsletterURL: URL? {
        Self.url(newsletter) ?? URL(string: "https://untangled.slidebean.com/?utm_source=tv-calibrator&utm_medium=app&utm_campaign=calibration-complete")
    }
}

extension SignalMode {
    /// SDR and HDR10 patterns are on YouTube; HDR10+ and Dolby Vision are downloads.
    var onYouTube: Bool { self == .sdr || self == .hdr10 }

    /// Where a pattern file comes from, in words.
    var patternSource: String {
        onYouTube ? "in the \(label) playlist on YouTube"
            : "from the \(label) download (USB stick or a media app)"
    }
}

@MainActor
final class LinksService: ObservableObject {
    @Published private(set) var links: PatternLinks

    private let cacheURL: URL?

    init() {
        let cache = (try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                                  appropriateFor: nil, create: true))?.appendingPathComponent("patterns.json")
        cacheURL = cache
        let decoder = JSONDecoder()
        if let u = cache, let data = try? Data(contentsOf: u), let l = try? decoder.decode(PatternLinks.self, from: data) {
            links = l
        } else if let u = Bundle.main.url(forResource: "patterns", withExtension: "json"),
                  let data = try? Data(contentsOf: u), let l = try? decoder.decode(PatternLinks.self, from: data) {
            links = l
        } else {
            links = PatternLinks()
        }
    }

    func refresh() async {
        guard let server = Config.server else { return }
        do {
            let (data, response) = try await URLSession.shared.data(from: server.appendingPathComponent("patterns.json"))
            guard (response as? HTTPURLResponse)?.statusCode == 200 else { return }
            let l = try JSONDecoder().decode(PatternLinks.self, from: data)
            links = l
            if let u = cacheURL { try? data.write(to: u, options: .atomic) }
        } catch {
            // keep the cached or bundled links
        }
    }
}
