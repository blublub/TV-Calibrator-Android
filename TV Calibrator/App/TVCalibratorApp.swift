import CalibrationKit
import SwiftUI

// TV Calibrator by Slidebean, for iPhone: calibrates a TV's picture with the iPhone camera as the meter
// and pattern videos played on the TV (YouTube for SDR and HDR10, files for HDR10+ and Dolby Vision). The
// camera is matched to a professional colorimeter (Display Plus HL) per iPhone model; those profiles are
// built in, with newer ones from the profile server.

@main
struct TVCalibratorApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @StateObject private var app = AppModel()
    @State private var intro = true

    var body: some Scene {
        WindowGroup {
            ZStack {
                RootView()
                    .environmentObject(app)
                    .environmentObject(app.links)
                if intro {
                    IntroView()
                        .transition(.opacity)
                        .zIndex(1)
                }
            }
            .tint(Theme.tealBright)          // links and back arrows are teal; main actions use PrimaryButtonStyle
            .preferredColorScheme(.dark)       // the room should be dark while measuring
            .task {
                try? await Task.sleep(nanoseconds: 1_700_000_000)
                withAnimation(.easeInOut(duration: 0.6)) { intro = false }
            }
            .task {
                await app.profiles.refresh()
                await app.links.refresh()
            }
        }
    }
}
