import SwiftUI

/// The opening screen: only the Slidebean mark, on a near-black with a faint teal glow, for a moment at
/// launch. The system launch screen (LaunchBackground) is the glow's outer colour, so the hand-over is
/// seamless.
struct IntroView: View {
    @State private var shown = false

    /// Near-black #030F11 with the faintest teal (#082A2C) behind the logo.
    static let base = Color(red: 0x03 / 255, green: 0x0F / 255, blue: 0x11 / 255)
    private static let glow = Color(red: 0x08 / 255, green: 0x2A / 255, blue: 0x2C / 255)

    var body: some View {
        ZStack {
            Self.base.ignoresSafeArea()
            RadialGradient(colors: [Self.glow, Self.base], center: .center, startRadius: 0, endRadius: 320)
                .ignoresSafeArea()
                .opacity(shown ? 1 : 0)

            Image("SlidebeanLogo")
                .resizable()
                .scaledToFit()
                .frame(width: 84)
                .shadow(color: Color(red: 1, green: 0.2, blue: 0.4).opacity(shown ? 0.28 : 0), radius: 30)
                .scaleEffect(shown ? 1 : 0.92)
                .opacity(shown ? 1 : 0)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("TV Calibrator by Slidebean")
        .onAppear {
            withAnimation(.easeOut(duration: 0.9)) { shown = true }
        }
    }
}
