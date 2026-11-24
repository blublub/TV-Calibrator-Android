import CalibrationKit
import SwiftUI

/// Add (or edit) a TV, one page at a time: name and brand, then what its white-balance and colour menus
/// look like. Either menu can be "not sure": the app asks again at the step that needs it.
/// (Figma: 03 Add a Screen · empty / selected / white / colour / edit.)
struct TVSetupFlow: View {
    @EnvironmentObject var app: AppModel
    @Environment(\.dismiss) private var dismiss
    let tv: TVRecord?
    /// Called with the new TV after adding it.
    var onAdded: (TVRecord) -> Void = { _ in }

    @State private var name = ""
    @State private var brand: Brand = .lg
    @State private var multipoint: Multipoint = .twenty
    @State private var multipointKnown = false
    @State private var colourMenu: ColourMenu = .hsl
    @State private var colourMenuKnown = false
    @State private var panel: PanelType = .unknown
    @State private var loaded = false
    @FocusState private var nameFocused: Bool

    var body: some View {
        NavigationStack {
            ScrollView {
                Walkthrough(pages: pages, finishTitle: tv == nil ? "Add this screen" : "Save",
                            section: "Screen details",
                            canAdvance: { i in i != 0 || !trimmedName.isEmpty }) {
                    save()
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 12)
            }
            .scrollDismissesKeyboard(.interactively)
            .screenBackground()
            .pinnedActionHost()
            .navigationTitle(tv == nil ? "Add a Screen" : "Screen settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { Image(systemName: "xmark") }
                        .accessibilityLabel("Cancel")
                }
            }
        }
        .onAppear(perform: load)
    }

    var trimmedName: String { name.trimmingCharacters(in: .whitespaces) }

    var pages: [WalkPage] {
        [
            WalkPage(symbol: "", title: "Which screen is this?",
                     text: "You can change these details later.",
                     extra: AnyView(nameAndBrand)),
            WalkPage(symbol: "", title: "What kind of screen is it?",
                     text: "The camera reads each kind differently. The model name or the box usually says.",
                     extra: AnyView(PanelChoice(selection: $panel))),
            WalkPage(symbol: "", title: "Its white balance menu",
                     text: "On the TV, open \(brand.whiteBalancePath)",
                     extra: AnyView(VStack(spacing: 12) {
                         WalkArtPanel(height: 130) { WhiteBalanceArt(points: multipoint.artPoints) }
                         MultipointChoice(brand: brand, selection: $multipoint, known: $multipointKnown)
                     })),
            WalkPage(symbol: "", title: "Its colour menu",
                     text: "On the TV, open \(brand.colourMenuPath). Which controls can you see?",
                     extra: AnyView(VStack(spacing: 12) {
                         WalkArtPanel(height: 130) { ColourMenuArt(menu: colourMenu) }
                         ColourMenuChoice(brand: brand, selection: $colourMenu, known: $colourMenuKnown)
                     })),
        ]
    }

    var nameAndBrand: some View {
        VStack(alignment: .leading, spacing: 12) {
            VStack(alignment: .leading, spacing: 8) {
                Text("Screen name")
                    .font(.captionBrand)
                    .foregroundStyle(.white)
                TextField("", text: $name, prompt: Text("e.g. Living room OLED").foregroundStyle(Theme.textSecondary))
                    .font(.brand(16, relativeTo: .body))
                    .foregroundStyle(.white)
                    .textInputAutocapitalization(.words)
                    .submitLabel(.done)
                    .focused($nameFocused)
                    .padding(.horizontal, 16)
                    .frame(minHeight: 54)
                    .background(Theme.card, in: RoundedRectangle(cornerRadius: 14))
                    .overlay(RoundedRectangle(cornerRadius: 14)
                        .stroke(nameFocused ? Theme.tealBright : Theme.hairline, lineWidth: 1))
            }
            Text("Brand")
                .font(.captionBrand)
                .foregroundStyle(.white)
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 9), count: 3), spacing: 9) {
                ForEach(Brand.allCases) { b in
                    Button {
                        brand = b
                        if !multipointKnown { multipoint = b.multipoint }
                        if !colourMenuKnown { colourMenu = b.colourMenu }
                    } label: {
                        brandLabel(b)
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(b == brand ? .isSelected : [])
                }
            }
            Text("Choose the name you’ll recognise from the sofa.")
                .font(.captionBrand)
                .secondaryText()
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    func brandLabel(_ b: Brand) -> some View {
        let selected = b == brand
        return Text(b.label)
            .font(selected ? Font.brand(14, .semibold, relativeTo: .callout) : Font.callout14)
            .foregroundStyle(selected ? Theme.accent : Color.white)
            .lineLimit(1)
            .minimumScaleFactor(0.8)
            .padding(.horizontal, 6)
            .frame(maxWidth: .infinity, minHeight: 49)
            .background(RoundedRectangle(cornerRadius: 13).fill(selected ? Theme.accent.opacity(0.1) : Theme.card))
            .overlay(RoundedRectangle(cornerRadius: 13).stroke(selected ? Theme.accent : Theme.hairline, lineWidth: 1))
            .contentShape(RoundedRectangle(cornerRadius: 13))
    }

    func load() {
        guard !loaded else { return }
        loaded = true
        if let tv {
            name = tv.name
            brand = tv.brand
            multipoint = tv.multipoint
            multipointKnown = tv.multipointKnown
            colourMenu = tv.colourMenu
            colourMenuKnown = tv.colourMenuKnown
            panel = tv.panel
        } else {
            multipoint = brand.multipoint
            colourMenu = brand.colourMenu
        }
    }

    func save() {
        var t = tv ?? TVRecord(name: "")
        t.name = trimmedName
        t.brand = brand
        t.multipoint = multipoint
        t.multipointKnown = multipointKnown
        t.colourMenu = colourMenu
        t.colourMenuKnown = colourMenuKnown
        t.panel = panel
        if tv == nil {
            app.add(t)
            dismiss()
            onAdded(t)
        } else {
            app.update(t)
            dismiss()
        }
    }
}

private extension Multipoint {
    /// How many sliders the white-balance illustration shows.
    var artPoints: Int {
        switch self {
        case .none: return 2
        case .ten: return 10
        case .twenty: return 20
        }
    }
}

/// A big tappable option: an icon, a title (with optional detail and note) and a radio.
/// Selected: yellow 1.5 pt border on a yellow tint, the icon and radio in yellow.
struct ChoiceTile<Art: View>: View {
    let title: String
    /// A secondary line under the title ("Gain and Offset").
    var detail: String?
    /// A teal line under it ("Usual for LG").
    var note: String?
    let selected: Bool
    /// The icon (an SF Symbol image), drawn 28 pt in teal, yellow when selected.
    @ViewBuilder var art: Art
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                art
                    .font(.system(size: 22, weight: .regular))
                    .foregroundStyle(selected ? Theme.accent : Theme.tealBright)
                    .frame(width: 28, height: 28)
                VStack(alignment: .leading, spacing: 3) {
                    Text(title)
                        .font(.bodyBrand)
                        .foregroundStyle(.white)
                        .fixedSize(horizontal: false, vertical: true)
                    if let detail {
                        Text(detail)
                            .font(.small)
                            .secondaryText()
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    if let note {
                        Text(note)
                            .font(.small)
                            .foregroundStyle(Theme.tealBright)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                radio
            }
            .padding(12)
            .frame(maxWidth: .infinity, minHeight: 64)
            .background(RoundedRectangle(cornerRadius: 16).fill(selected ? Theme.accent.opacity(0.05) : Theme.card))
            .overlay(RoundedRectangle(cornerRadius: 16)
                .stroke(selected ? Theme.accent : Theme.hairline, lineWidth: selected ? 1.5 : 1))
            .contentShape(RoundedRectangle(cornerRadius: 16))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    @ViewBuilder private var radio: some View {
        if selected {
            Circle()
                .fill(Theme.accent)
                .frame(width: 20, height: 20)
        } else {
            Circle()
                .strokeBorder(Color.white.opacity(0.35), lineWidth: 1.5)
                .frame(width: 20, height: 20)
        }
    }
}

/// The kind of screen: OLED, QD-OLED, quantum dot or mini-LED, LED, RGB backlight, plasma, or not sure.
struct PanelChoice: View {
    @Binding var selection: PanelType

    var body: some View {
        VStack(spacing: 12) {
            ForEach(PanelType.allCases) { p in
                ChoiceTile(title: p.label, detail: p.detail, selected: selection == p) {
                    Image(systemName: p == .unknown ? "questionmark.circle" : "tv")
                } action: {
                    selection = p
                }
            }
        }
    }
}

/// 2-point only, 10 points or 20 points, or not sure.
struct MultipointChoice: View {
    let brand: Brand
    @Binding var selection: Multipoint
    @Binding var known: Bool

    var body: some View {
        VStack(spacing: 12) {
            ForEach([Multipoint.none, .ten, .twenty]) { m in
                ChoiceTile(title: title(m), detail: detail(m),
                           note: m == brand.multipoint ? "Usual for \(brand.label)" : nil,
                           selected: known && selection == m) {
                    Image(systemName: "slider.vertical.3")
                } action: {
                    selection = m
                    known = true
                }
            }
            ChoiceTile(title: "Not sure: ask me when we get there", selected: !known) {
                Image(systemName: "slider.vertical.3")
            } action: {
                known = false
                selection = brand.multipoint
            }
        }
    }

    func title(_ m: Multipoint) -> String {
        switch m {
        case .none: return "Only 2 points"
        case .ten: return "10 points"
        case .twenty: return "20 points"
        }
    }

    func detail(_ m: Multipoint) -> String {
        switch m {
        case .none: return "Gain and \(brand.offsetName)"
        case .ten: return "10%, 20% … 100%"
        case .twenty: return "5%, 10% … 100%"
        }
    }
}

/// Hue / Saturation / Brightness, Red / Green / Blue, none, or not sure.
struct ColourMenuChoice: View {
    let brand: Brand
    @Binding var selection: ColourMenu
    @Binding var known: Bool

    var body: some View {
        VStack(spacing: 12) {
            ForEach([ColourMenu.hsl, .rgbSliders, .off], id: \.self) { c in
                ChoiceTile(title: title(c), note: c == brand.colourMenu ? "Usual for \(brand.label)" : nil,
                           selected: known && selection == c) {
                    Image(systemName: "slider.horizontal.3")
                } action: {
                    selection = c
                    known = true
                }
            }
            ChoiceTile(title: "Not sure", selected: !known) {
                Image(systemName: "slider.horizontal.3")
            } action: {
                known = false
                selection = brand.colourMenu
            }
        }
    }

    func title(_ c: ColourMenu) -> String {
        switch c {
        case .hsl: return brand.cmsMenuLabel
        case .rgbSliders: return "Red / Green / Blue"
        case .off: return "No colour menu"
        }
    }
}

/// Asked at the step that needs it, when the user said "not sure" while adding the TV.
/// Its Continue is the screen's pinned main action (`pinnedAction`; GuidedScreen pins it).
struct MenuQuestionCard: View {
    @EnvironmentObject var app: AppModel
    let tv: TVRecord
    let colours: Bool
    @State private var multipoint: Multipoint = .twenty
    @State private var colourMenu: ColourMenu = .hsl
    @State private var known = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(colours ? "First: your TV’s colour menu" : "First: your TV’s white balance menu")
                .stepTitleStyle()
            Text(colours ? "On the TV, open \(tv.brand.colourMenuPath). Which sliders does each colour have?"
                 : "On the TV, open \(tv.brand.whiteBalancePath) and look for a 10 or 20 point option. Which does it have?")
                .font(.bodyBrand)
                .secondaryText()
                .lineSpacing(4)
                .fixedSize(horizontal: false, vertical: true)
            if colours {
                WalkArtPanel(height: 130) { ColourMenuArt(menu: colourMenu) }
                ColourMenuChoice(brand: tv.brand, selection: $colourMenu, known: $known)
            } else {
                WalkArtPanel(height: 130) { WhiteBalanceArt(points: multipoint.artPoints) }
                MultipointChoice(brand: tv.brand, selection: $multipoint, known: $known)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .pinnedAction("Continue", enabled: known) {
            var t = tv
            if colours {
                t.colourMenu = colourMenu
                t.colourMenuKnown = true
            } else {
                t.multipoint = multipoint
                t.multipointKnown = true
            }
            app.update(t)
        }
        .onAppear {
            multipoint = tv.multipoint
            colourMenu = tv.colourMenu
        }
    }
}
