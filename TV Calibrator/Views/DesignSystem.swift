import CalibrationKit
import SwiftUI
import UIKit

// The design system, from the Figma file "Calibrator" (pages 02 Components, 03 Colour and type styles).
// Every screen is built from these tokens and parts. Type is the iOS system font (SF Pro), with SF Pro
// Rounded for live readings.

// MARK: - Colour

enum Theme {
    /// Screens: near-black with a teal cast.
    static let background = Color(red: 0x05 / 255, green: 0x17 / 255, blue: 0x1A / 255)          // #05171A
    /// Hero cards and illustration panels.
    static let surface = Color(red: 0x0D / 255, green: 0x3B / 255, blue: 0x42 / 255)             // #0D3B42
    static let teal = surface
    /// Links, back arrows, icons, eyebrows, the SDR format.
    static let tealBright = Color(red: 0x40 / 255, green: 0xB8 / 255, blue: 0xB0 / 255)          // #40B8B0
    /// The one main action per screen, the current step, the HDR10 format.
    static let accent = Color(red: 0xFF / 255, green: 0xD1 / 255, blue: 0x33 / 255)              // #FFD133
    static let accentPressed = Color(red: 0xDA / 255, green: 0xB0 / 255, blue: 0x25 / 255)       // #DAB025
    /// On target, done.
    static let green = Color(red: 0x4D / 255, green: 0xCC / 255, blue: 0x73 / 255)               // #4DCC73
    /// At the TV's limit, warnings.
    static let limit = Color(red: 0xFF / 255, green: 0xB3 / 255, blue: 0x6B / 255)               // #FFB36B
    static let red = Color(red: 0xF2 / 255, green: 0x54 / 255, blue: 0x4D / 255)                 // #F2544D
    static let blue = Color(red: 0x54 / 255, green: 0x8C / 255, blue: 0xFA / 255)                // #548CFA
    static let hdr10Plus = Color(red: 0x9E / 255, green: 0x85 / 255, blue: 0xFA / 255)           // #9E85FA
    static let dolbyVision = Color(red: 0xED / 255, green: 0x73 / 255, blue: 0xBF / 255)         // #ED73BF
    /// Card fill: white 6%.
    static let card = Color.white.opacity(0.06)
    /// Hairline borders and empty tracks: white 9%.
    static let hairline = Color.white.opacity(0.09)
    static let textSecondary = Color.white.opacity(0.6)
    /// The camera preview's empty fill.
    static let previewFill = Color(red: 0x30 / 255, green: 0x38 / 255, blue: 0x3A / 255)        // #30383A
    static let tealGradient = LinearGradient(colors: [Color(red: 0.07, green: 0.30, blue: 0.33), surface],
                                             startPoint: .topLeading, endPoint: .bottomTrailing)

    static func channel(_ c: String) -> Color { c == "R" ? red : c == "G" ? green : blue }

    static func swatch(_ name: String) -> Color {
        let s = colourSwatch[name] ?? (0.8, 0.8, 0.8)
        return Color(red: s.0, green: s.1, blue: s.2)
    }

    static func deColour(_ de: Double?) -> Color {
        guard let de, !de.isNaN else { return textSecondary }
        return de < 1 ? green : de < 3 ? .white : limit
    }
}

extension SignalMode {
    /// Each format has its own colour, so it's always clear which videos belong to which.
    var tint: Color {
        switch self {
        case .sdr: return Theme.tealBright
        case .hdr10: return Theme.accent
        case .hdr10p: return Theme.hdr10Plus
        case .dv: return Theme.dolbyVision
        }
    }

    /// SDR and HDR10 come first (YouTube); HDR10+ and Dolby Vision are advanced (files played on the TV).
    var isAdvanced: Bool { !onYouTube }

    /// Recommended order, from 1.
    var order: Int { SignalMode.allCases.firstIndex(of: self)! + 1 }

    var blurb: String {
        switch self {
        case .sdr: return "Everyday TV. Start here."
        case .hdr10: return "HDR films. After SDR."
        case .hdr10p: return "Pattern files on a USB stick. After HDR10."
        case .dv: return "Pattern files on a USB stick. After HDR10."
        }
    }
}

// MARK: - Type

extension Font {
    /// The app's typeface (the system font) at a design size, scaled for the user's text size like `style`.
    static func brand(_ size: CGFloat, _ weight: Font.Weight = .regular, relativeTo style: Font.TextStyle = .body,
                      design: Font.Design = .default) -> Font {
        .system(size: scaled(size, like: style), weight: weight, design: design)
    }

    /// A design size scaled by the user's Dynamic Type setting, as `style` scales.
    private static func scaled(_ size: CGFloat, like style: Font.TextStyle) -> CGFloat {
        let ui: UIFont.TextStyle
        switch style {
        case .largeTitle: ui = .largeTitle
        case .title: ui = .title1
        case .title2: ui = .title2
        case .title3: ui = .title3
        case .headline: ui = .headline
        case .subheadline: ui = .subheadline
        case .callout: ui = .callout
        case .footnote: ui = .footnote
        case .caption: ui = .caption1
        case .caption2: ui = .caption2
        default: ui = .body
        }
        return UIFontMetrics(forTextStyle: ui).scaledValue(for: size)
    }

    // The type scale from the Figma file (03 Colour and type styles).
    /// 28 regular: screen headlines ("See it the way it was made.").
    static let display = Font.brand(28, .regular, relativeTo: .largeTitle)
    /// 25 regular: walkthrough page titles.
    static let pageTitle = Font.brand(25, .regular, relativeTo: .title)
    /// 22 semibold: a step's title ("Measure your starting point"). The 28 pt display headline is kept
    /// for the two places that earn it: the empty home screen and the opening pages.
    static let stepTitle = Font.brand(22, .semibold, relativeTo: .title2)
    /// 23 regular: card headings ("1 SDR").
    static let cardTitle = Font.brand(23, .regular, relativeTo: .title2)
    /// 18 semibold: section headings ("Your screens").
    static let section = Font.brand(18, .semibold, relativeTo: .title3)
    /// 17 semibold: emphasised titles, video names.
    static let headlineBrand = Font.brand(17, .semibold, relativeTo: .headline)
    /// 15: body text; semibold for names and links.
    static let bodyBrand = Font.brand(15, .regular, relativeTo: .body)
    static let bodyStrong = Font.brand(15, .semibold, relativeTo: .body)
    /// 14: format card descriptions.
    static let callout14 = Font.brand(14, .regular, relativeTo: .callout)
    /// 13: secondary text; semibold for eyebrows and small links.
    static let captionBrand = Font.brand(13, .regular, relativeTo: .footnote)
    static let captionStrong = Font.brand(13, .semibold, relativeTo: .footnote)
    /// 12: small labels; bold for format badges.
    static let small = Font.brand(12, .regular, relativeTo: .caption)
    static let badge = Font.brand(12, .bold, relativeTo: .caption)
    /// 11 and 10: step numbers, format progress labels.
    static let tiny = Font.brand(11, .regular, relativeTo: .caption2)
    static let micro = Font.brand(10, .regular, relativeTo: .caption2)

    /// Live readings, readable from across the room (numeric scale: 56 / 40 / 30).
    static func reading(_ size: CGFloat = 40) -> Font {
        Font.brand(size, .semibold, relativeTo: .largeTitle, design: .rounded).monospacedDigit()
    }
}

extension View {
    /// A 28 pt display headline with the design's tight tracking.
    func displayStyle() -> some View {
        font(.display).tracking(-0.6).lineSpacing(-2).foregroundStyle(.white)
    }

    /// A 25 pt page title.
    func pageTitleStyle() -> some View {
        font(.pageTitle).tracking(-0.6).foregroundStyle(.white)
    }

    /// A 22 pt semibold step title, wrapping as needed.
    func stepTitleStyle() -> some View {
        font(.stepTitle).tracking(-0.4).foregroundStyle(.white).fixedSize(horizontal: false, vertical: true)
    }

    /// Secondary text colour.
    func secondaryText() -> some View { foregroundStyle(Theme.textSecondary) }
}

func fmt(_ v: Double?, _ digits: Int = 1) -> String {
    guard let v, v.isFinite else { return "–" }
    return String(format: "%.\(digits)f", v)
}

func signed(_ v: Double?, _ digits: Int = 1) -> String {
    guard let v, v.isFinite else { return "–" }
    return String(format: "%+.\(digits)f", v)
}

// MARK: - Screens

/// Screen background: near-black with the brand teal cast.
struct ScreenBackground: ViewModifier {
    func body(content: Content) -> some View {
        content
            .scrollContentBackground(.hidden)
            .background(Theme.background.ignoresSafeArea())
    }
}

extension View {
    func screenBackground() -> some View { modifier(ScreenBackground()) }

    /// The screen's one main action, pinned above the home indicator while the content scrolls.
    func bottomAction<Action: View>(@ViewBuilder _ action: () -> Action) -> some View {
        safeAreaInset(edge: .bottom, spacing: 0) {
            action()
                .padding(.horizontal, 20)
                .padding(.top, 12)
                .padding(.bottom, 8)
                .background(Theme.background.opacity(0.96).ignoresSafeArea())
        }
    }
}

// MARK: - Buttons

/// The main action: a yellow capsule, dark label, at least 52 pt tall.
struct PrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.bodyBrand)
            .multilineTextAlignment(.center)
            .foregroundStyle(enabled ? Theme.background : Theme.textSecondary)
            .frame(maxWidth: .infinity, minHeight: 52)
            .padding(.horizontal, 16)
            .background(Capsule().fill(enabled ? (configuration.isPressed ? Theme.accentPressed : Theme.accent)
                                       : Theme.accent.opacity(0.2)))
            .contentShape(Capsule())
    }
}

/// A secondary action: a teal-outlined capsule.
struct SecondaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.bodyBrand)
            .multilineTextAlignment(.center)
            .foregroundStyle(enabled ? Color.white : Theme.textSecondary)
            .frame(maxWidth: .infinity, minHeight: 52)
            .padding(.horizontal, 16)
            .background(Capsule().fill(configuration.isPressed ? Theme.card : Color.clear))
            .overlay(Capsule().stroke(enabled ? Theme.tealBright : Theme.hairline, lineWidth: 1))
            .contentShape(Capsule())
    }
}

/// A text link: teal, semibold.
struct LinkButtonStyle: ButtonStyle {
    var font: Font = .bodyStrong
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(font)
            .foregroundStyle(Theme.tealBright.opacity(configuration.isPressed ? 0.6 : 1))
            .frame(minHeight: 44)
            .contentShape(Rectangle())
    }
}

/// Lets one button switch between two styles.
struct AnyButtonStyle: ButtonStyle {
    private let make: (Configuration) -> AnyView
    init<S: ButtonStyle>(_ style: S) { make = { AnyView(style.makeBody(configuration: $0)) } }
    func makeBody(configuration: Configuration) -> some View { make(configuration) }
}

// MARK: - Surfaces

/// A card: white 6%, hairline border, 18 pt corners, 16 pt padding.
struct Card<Content: View>: View {
    var spacing: CGFloat = 12
    var hero = false
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: spacing) { content }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(hero ? Theme.surface : Theme.card, in: RoundedRectangle(cornerRadius: 18))
            .overlay(RoundedRectangle(cornerRadius: 18).stroke(Theme.hairline, lineWidth: 1))
    }
}

/// An SF Symbol in a round bubble (36, 44 or 64 pt).
struct IconBubble: View {
    let symbol: String
    var tint: Color = Theme.tealBright
    var size: CGFloat = 44

    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: size * 0.42, weight: .regular))
            .foregroundStyle(tint)
            .frame(width: size, height: size)
            .background(Circle().fill(tint.opacity(0.1)))
    }
}

/// "SDR" / "HDR10" ... in the format's colour on a tint of it.
struct ModeBadge: View {
    let mode: SignalMode
    var big = false

    var body: some View {
        Text(mode.label)
            .font(.badge)
            .foregroundStyle(mode.tint)
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(RoundedRectangle(cornerRadius: 8).fill(mode.tint.opacity(0.13)))
    }
}

typealias FormatBadge = ModeBadge

/// A small eyebrow line above a title: "Set up · 5 of 6", "Before → after".
struct Eyebrow: View {
    let text: String
    var colour: Color = Theme.tealBright
    var body: some View { Text(text).font(.small).foregroundStyle(colour) }
}

/// One tip: to do (teal, lightbulb), on target (green, check) or at the TV's limit (orange, minus).
struct TipRow: View {
    enum Tone { case todo, ok, limit }
    let tone: Tone
    let text: String

    var colour: Color { tone == .ok ? Theme.green : tone == .limit ? Theme.limit : Theme.tealBright }
    var symbol: String { tone == .ok ? "checkmark.circle" : tone == .limit ? "minus.circle" : "lightbulb" }

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: symbol)
                .font(.system(size: 17))
                .foregroundStyle(colour)
                .frame(width: 20, height: 20)
            Text(.init(text))
                .font(.captionBrand)
                .foregroundStyle(tone == .todo ? Color.white : colour)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 16).fill(colour.opacity(0.07)))
    }
}

/// Page dots: 6 pt, the current one 8 pt and yellow.
struct PageDots: View {
    let count: Int
    let index: Int

    var body: some View {
        HStack(spacing: 7) {
            ForEach(0..<count, id: \.self) { i in
                Circle()
                    .fill(i == index ? Theme.accent : Color.white.opacity(0.25))
                    .frame(width: i == index ? 8 : 6, height: i == index ? 8 : 6)
            }
        }
        .frame(maxWidth: .infinity)
        .animation(.easeInOut(duration: 0.2), value: index)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Page \(index + 1) of \(count)")
    }
}

/// A thin progress track (4 pt) in a colour.
struct ProgressTrack: View {
    let value: Double          // 0 ... 1
    var colour: Color = Theme.tealBright

    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(Theme.hairline)
                Capsule().fill(colour).frame(width: g.size.width * CGFloat(min(max(value, 0), 1)))
            }
        }
        .frame(height: 4)
    }
}

// MARK: - The pinned main action

/// Holds the newest closure for a pinned button. The button is drawn by the hosting screen, which only
/// hears about a change of title, state or owner; reading the closure through this box means a tap
/// always runs the action of the view that is on screen now.
final class PinnedActionBox {
    var action: () -> Void = {}
}

/// A screen's main action, declared by whatever view is showing (a step, a page) and drawn pinned at the
/// bottom by the screen that hosts it (`pinnedActionHost()`), above the home indicator.
struct PinnedAction: Equatable {
    var title: String
    var enabled = true
    let box: PinnedActionBox

    static func == (a: PinnedAction, b: PinnedAction) -> Bool {
        a.title == b.title && a.enabled == b.enabled && a.box === b.box
    }
}

struct PinnedActionKey: PreferenceKey {
    static let defaultValue: PinnedAction? = nil
    static func reduce(value: inout PinnedAction?, nextValue: () -> PinnedAction?) {
        if let next = nextValue() { value = next }
    }
}

/// Declares the pinned action for the view it is attached to. Each view that declares one owns a box
/// (so two steps with the same title are still different actions), refreshed with the latest closure
/// every time the view is drawn.
private struct PinnedActionModifier: ViewModifier {
    let title: String?
    let enabled: Bool
    let action: () -> Void
    @State private var box = PinnedActionBox()

    func body(content: Content) -> some View {
        box.action = action
        return content.preference(key: PinnedActionKey.self,
                                  value: title.map { PinnedAction(title: $0, enabled: enabled, box: box) })
    }
}

extension View {
    /// Declares this screen's main (yellow) action; the hosting screen pins it at the bottom.
    /// A nil title declares none (while measuring, say).
    func pinnedAction(_ title: String?, enabled: Bool = true, action: @escaping () -> Void) -> some View {
        modifier(PinnedActionModifier(title: title, enabled: enabled, action: action))
    }

    /// Pins the main action declared inside this view (`pinnedAction`) at the bottom of the screen.
    func pinnedActionHost() -> some View { modifier(PinnedActionHost()) }
}

struct PinnedActionHost: ViewModifier {
    @State private var current: PinnedAction?

    func body(content: Content) -> some View {
        content
            .onPreferenceChange(PinnedActionKey.self) { current = $0 }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if let c = current {
                    Button(c.title) { c.box.action() }
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled(!c.enabled)
                        .padding(.horizontal, 20)
                        .padding(.top, 12)
                        .padding(.bottom, 8)
                        .background(Theme.background.opacity(0.96).ignoresSafeArea())
                }
            }
    }
}
