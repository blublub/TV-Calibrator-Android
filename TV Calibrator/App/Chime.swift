import AVFoundation
import UIKit

/// The little success sound (and a tap of haptics) the first time a setting reaches its target: Red gain on
/// the line, Green's saturation in the zone. Each setting chimes once per TV, format and step while the app
/// is open; overshooting and coming back doesn't chime again. It needs two on-target readings in a row, so
/// a single noisy reading passing through the zone doesn't set it off.
@MainActor
final class Chime {
    static let shared = Chime()
    /// Settings › Sounds. On unless turned off.
    static let enabledKey = "feedback.sounds"

    private var fired: Set<String> = []
    private var streak: [String: Int] = [:]
    private var player: AVAudioPlayer?
    private let haptic = UINotificationFeedbackGenerator()

    static var enabled: Bool { UserDefaults.standard.object(forKey: enabledKey) as? Bool ?? true }

    /// Call with every new reading of a setting. `key` names the setting, e.g. "<tv>|sdr|gain|R".
    func observe(_ key: String?, ok: Bool) {
        guard let key else { return }
        guard ok else {
            streak[key] = 0
            return
        }
        let n = (streak[key] ?? 0) + 1
        streak[key] = n
        guard n >= 2, !fired.contains(key) else { return }
        fired.insert(key)
        play()
    }

    func play() {
        guard Self.enabled else { return }
        haptic.notificationOccurred(.success)
        if player == nil, let url = Bundle.main.url(forResource: "success", withExtension: "wav") {
            // Ambient: mixes with whatever else is playing and stays quiet when the ring switch is on silent.
            try? AVAudioSession.sharedInstance().setCategory(.ambient, options: [.mixWithOthers])
            player = try? AVAudioPlayer(contentsOf: url)
            player?.volume = 0.7
            player?.prepareToPlay()
        }
        player?.currentTime = 0
        player?.play()
    }
}
