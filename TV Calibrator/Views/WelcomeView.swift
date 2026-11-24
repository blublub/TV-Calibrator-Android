import SwiftUI

/// For people who found the app in the App Store without seeing the Slidebean video: what calibration
/// is, how the app does it, what you need, and the video that explains it. Shown once on first launch,
/// and again from "How it works" on the home screen.
struct WelcomeView: View {
    @EnvironmentObject var links: LinksService
    @Environment(\.dismiss) private var dismiss
    let onFinish: () -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                Walkthrough(pages: pages, finishTitle: "Get started", section: "How it works") {
                    onFinish()
                    dismiss()
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 12)
            }
            .screenBackground()
            .pinnedActionHost()
            .navigationTitle("Welcome")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button {
                        onFinish()
                        dismiss()
                    } label: { Image(systemName: "xmark") }
                        .accessibilityLabel("Close")
                }
            }
        }
    }

    private var pages: [WalkPage] {
        var p: [WalkPage] = []
        if let url = links.links.explainerURL {
            p.append(WalkPage(symbol: "",
                              title: "New to calibration? Start with the video",
                              text: "This app goes with Slidebean's videos on what calibration does to a TV's picture, with a full session start to finish. If you haven't seen them, they're worth a few minutes first.",
                              extra: AnyView(VideoPreview(url: url))))
        }
        p.append(WalkPage(symbol: "tv",
                          title: "Make your TV look the way the film was made",
                          text: "Most TVs come out of the box too blue, too bright and too saturated. TV Calibrator walks you through your TV's own picture settings until they match the standard that films and shows are made to."))
        p.append(WalkPage(symbol: "camera.viewfinder",
                          title: "Your iPhone is the meter",
                          text: "The TV plays test-pattern videos from our YouTube channel. Your iPhone's camera reads each colour, and the app tells you which setting to change and by how much. Nothing is installed on the TV.",
                          bullets: ["Start from the most accurate picture mode (often Filmmaker Mode), then fine-tune it",
                                    "One step at a time: white, greys, then colours",
                                    "A before and after score at the end"]))
        p.append(WalkPage(symbol: "", title: "What you need",
                          text: "No TV handy? Try the demo on the home screen to see how it works.",
                          bullets: ["Your TV's remote",
                                    "The YouTube app on the TV",
                                    "Something to stand the iPhone on, 15–30 cm from the screen",
                                    "A dark room"],
                          art: AnyView(PlacementArt())))
        return p
    }
}

/// The explainer video as something that plainly plays on YouTube: a 16:9 frame with the
/// glowing colour chart from the app icon, a play button, the Slidebean logo and "YouTube". `compact` is
/// the small thumbnail-and-text row for the home screen.
struct VideoPreview: View {
    let url: URL
    var compact = false

    var body: some View {
        Link(destination: url) {
            if compact {
                HStack(spacing: 12) {
                    frame(play: 30)
                        .frame(width: 112)
                    VStack(alignment: .leading, spacing: 3) {
                        Text("New to this? Watch how it works")
                            .font(.bodyStrong)
                            .foregroundStyle(.white)
                            .fixedSize(horizontal: false, vertical: true)
                        source
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            } else {
                VStack(alignment: .leading, spacing: 10) {
                    frame(play: 60)
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Watch how TV calibration works")
                            .font(.bodyStrong)
                            .foregroundStyle(.white)
                            .fixedSize(horizontal: false, vertical: true)
                        source
                    }
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Watch how TV calibration works, on YouTube")
        .accessibilityAddTraits(.isLink)
    }

    private var source: some View {
        HStack(spacing: 4) {
            Text("Slidebean · opens YouTube")
            Image(systemName: "arrow.up.right")
                .font(.system(size: 11, weight: .semibold))
        }
        .font(.small)
        .foregroundStyle(Theme.tealBright)
    }

    /// The 16:9 picture: dark teal, the app icon's glowing colour chart behind, a white play button in front.
    private func frame(play: CGFloat) -> some View {
        ZStack {
            RadialGradient(colors: [Color(red: 0x0A / 255, green: 0x3A / 255, blue: 0x4A / 255),
                                    Color(red: 0x01 / 255, green: 0x0D / 255, blue: 0x12 / 255)],
                           center: UnitPoint(x: 0.3, y: 0.25), startRadius: 0, endRadius: 260)
            Image("BrandChart")
                .resizable()
                .scaledToFit()
                .padding(.vertical, compact ? 4 : 10)
                .opacity(0.6)
            Color.black.opacity(0.25)
            ZStack {
                Circle()
                    .fill(.white)
                    .frame(width: play, height: play)
                    .shadow(color: .black.opacity(0.4), radius: 8, y: 2)
                Image(systemName: "play.fill")
                    .font(.system(size: play * 0.4, weight: .bold))
                    .foregroundStyle(Color(red: 0x01 / 255, green: 0x0D / 255, blue: 0x12 / 255))
                    .offset(x: play * 0.04)
            }
            if !compact {
                VStack {
                    HStack(spacing: 6) {
                        Image("SlidebeanLogo")
                            .resizable()
                            .scaledToFit()
                            .frame(height: 18)
                        Text("Slidebean")
                            .font(.captionStrong)
                            .foregroundStyle(.white)
                        Spacer(minLength: 0)
                    }
                    Spacer(minLength: 0)
                    HStack {
                        Spacer(minLength: 0)
                        HStack(spacing: 5) {
                            Image(systemName: "play.rectangle.fill")
                            Text("YouTube")
                        }
                        .font(.captionStrong)
                        .foregroundStyle(.white)
                        .padding(.horizontal, 9)
                        .padding(.vertical, 5)
                        .background(.black.opacity(0.55), in: Capsule())
                    }
                }
                .padding(12)
            }
        }
        .aspectRatio(16 / 9, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: compact ? 10 : 16))
        .overlay(RoundedRectangle(cornerRadius: compact ? 10 : 16).stroke(Theme.hairline, lineWidth: 1))
    }
}
