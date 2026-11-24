import CalibrationKit
import Foundation

/// Menu names and setup advice per TV brand. Menu names vary a little between models and years, so
/// the app always says "usually".
enum Brand: String, Codable, CaseIterable, Identifiable {
    case lg, samsung, sony, tcl, hisense, panasonic, philips, vizio, toshiba, sharp, roku, other

    var id: String { rawValue }

    var label: String {
        switch self {
        case .lg: return "LG"
        case .samsung: return "Samsung"
        case .sony: return "Sony"
        case .tcl: return "TCL"
        case .hisense: return "Hisense"
        case .panasonic: return "Panasonic"
        case .philips: return "Philips"
        case .vizio: return "Vizio"
        case .toshiba: return "Toshiba"
        case .sharp: return "Sharp"
        case .roku: return "Roku TV"
        case .other: return "Other"
        }
    }

    /// What the brand calls the bright-end and dark-end white-balance controls.
    var gainName: String { "Gain" }
    var offsetName: String {
        switch self {
        case .sony: return "Bias"
        case .panasonic: return "Cutoff"
        default: return "Offset"
        }
    }

    var whiteBalancePath: String {
        switch self {
        case .lg: return "Picture › Advanced Settings › Colour › White Balance (set it to 2 Point)"
        case .samsung: return "Picture › Expert Settings › White Balance (2 Point)"
        case .sony: return "Picture › Advanced settings › Colour › White balance"
        case .tcl: return "Picture › Advanced (or Expert) settings › White balance"
        case .hisense: return "Picture › Picture Mode Settings › White Balance"
        case .panasonic: return "Picture › Advanced Settings › White Balance (2p)"
        case .philips: return "Picture › Advanced › Colour › Colour temperature › Custom"
        case .vizio: return "Picture › Picture Mode Edit › Color Calibration › Color Temperature"
        case .toshiba: return "Picture › Advanced settings › White balance"
        case .sharp: return "Picture › Expert settings › White balance"
        case .roku: return "★ button › Advanced picture settings › Expert settings › White balance"
        case .other: return "the White Balance menu (often under Picture › Advanced or Expert settings)"
        }
    }

    var colourMenuPath: String {
        switch self {
        case .lg: return "Picture › Advanced Settings › Colour › Colour Management System"
        case .samsung: return "Picture › Expert Settings › Color Space Settings › Custom"
        case .sony: return "Picture › Advanced settings › Colour › Advanced colour adjustment"
        case .tcl: return "Picture › Advanced settings › Colour tuner"
        case .hisense: return "Picture › Picture Mode Settings › Color Tuner"
        case .panasonic: return "Picture › Advanced Settings › Colour Management"
        case .philips: return "Picture › Advanced › Colour › Colour control"
        case .vizio: return "Picture › Picture Mode Edit › Color Calibration › Color Tuner"
        case .toshiba: return "Picture › Advanced settings › Colour tuner"
        case .sharp: return "Picture › Expert settings › Colour tuner"
        case .roku: return "★ button › Advanced picture settings › Expert settings › Color tuning"
        case .other: return "the colour management menu (often called Colour Tuner, CMS or Colour Space)"
        }
    }

    /// The usual colour menu type for the brand (the user confirms it).
    var colourMenu: ColourMenu { self == .samsung ? .rgbSliders : .hsl }

    /// The usual multipoint white balance for the brand (the user confirms it).
    var multipoint: Multipoint {
        switch self {
        case .lg, .samsung, .hisense: return .twenty
        case .sony, .panasonic, .vizio, .tcl: return .ten
        case .philips, .toshiba, .sharp, .roku, .other: return .none
        }
    }

    /// The calibrated picture mode, per format.
    func pictureMode(_ mode: SignalMode) -> String {
        switch (self, mode) {
        case (.samsung, .dv): return "Samsung TVs don't support Dolby Vision: calibrate HDR10 or HDR10+ instead"
        case (.lg, .dv): return "Dolby Vision Filmmaker Mode (or Cinema Home)"
        case (.sony, .sdr), (.sony, .hdr10), (.sony, .hdr10p): return "Professional (or Custom)"
        case (.vizio, .dv): return "Dolby Vision Dark"
        case (.vizio, _): return "Calibrated Dark"
        case (.roku, _): return mode == .dv ? "Dolby Vision Dark" : "Movie"
        case (.panasonic, .dv): return "Dolby Vision Dark (or Dolby Vision Cinema)"
        case (.hisense, .sdr): return "Filmmaker Mode (or Theater Night)"
        case (.sharp, .sdr): return "Movie (or Filmmaker Mode)"
        case (_, .dv): return "Dolby Vision Dark (or Dolby Vision Cinema)"
        case (_, .sdr): return "Filmmaker Mode (or Movie / Cinema)"
        case (_, .hdr10), (_, .hdr10p): return "Filmmaker Mode (or Movie / Cinema)"
        }
    }

    /// Picture settings to make before measuring (after the picture mode).
    func extras(_ mode: SignalMode) -> [String] {
        var items: [String] = []
        switch self {
        case .lg:
            items = ["Energy Saving and AI Brightness: Off"]
            if mode == .sdr { items.append("Gamma: BT.1886, Colour Temperature: Warm 50") }
            if mode.isHDR { items.append("Dynamic Tone Mapping: Off") }
        case .samsung:
            items = ["Energy saving, Adaptive Brightness and Contrast Enhancer: Off"]
            if mode == .sdr { items.append("Colour Tone: Warm2, Gamma: BT.1886") }
            if mode.isHDR { items.append("HDR Tone Mapping: Static, ST.2084: 0") }
            items.append("Readings jumping around? Turn off Brightness Optimisation and set Local Dimming to Low")
        case .sony:
            items = ["Light sensor, Live colour and Advanced contrast enhancer: Off"]
            if mode == .sdr { items.append("Colour temperature: Expert 1") }
            if mode.isHDR { items.append("HDR tone mapping: Off") }
        case .tcl, .hisense, .toshiba, .sharp:
            items = ["Dynamic contrast, Eco / energy saving and the light sensor: Off"]
            if mode == .sdr { items.append("Colour temperature: Warm (or Low)") }
            if mode.isHDR { items.append("Any dynamic tone mapping: Off") }
            if self == .hisense || self == .tcl { items.append("Local dimming: Low or Medium") }
        case .panasonic:
            items = ["Ambient sensor and Dynamic Contrast: Off"]
            if mode == .sdr { items.append("Colour Temperature: Warm2") }
            if mode.isHDR { items.append("HDR Brightness Enhancer / Dynamic HDR Effect: Off") }
        case .philips:
            items = ["Ambilight, the light sensor and Dynamic contrast: Off"]
            if mode == .sdr { items.append("Colour temperature: Warm") }
            if mode.isHDR { items.append("HDR Plus / tone mapping: Off or Standard") }
        case .vizio:
            items = ["Auto Brightness Control: Off, Contrast Control: Off"]
            if mode == .sdr { items.append("Color Temperature: Normal (Vizio's is the film standard)") }
        case .roku:
            items = ["Dynamic contrast and Action smoothing: Off"]
            if mode == .sdr { items.append("Color temperature: Warm") }
        case .other:
            items = ["Energy saving, light sensors and contrast boosters: Off"]
            if mode == .sdr { items.append("Colour temperature: Warm") }
            if mode.isHDR { items.append("Dynamic tone mapping: Off") }
        }
        if mode == .hdr10p { items.append("HDR10+ mode: the plain setting, not Adaptive / Dynamic") }
        return items
    }

    /// Everything to set before measuring, as one list (picture mode first).
    func setup(_ mode: SignalMode) -> [String] {
        ["Picture mode: \(pictureMode(mode))"] + extras(mode)
    }
}

/// Multipoint white balance menus.
enum Multipoint: Int, Codable, CaseIterable, Identifiable {
    case twenty = 20, ten = 11, none = 0
    var id: Int { rawValue }
    var label: String {
        switch self {
        case .twenty: return "20 points (5%, 10% … 100%)"
        case .ten: return "10 points (10%, 20% … 100%)"
        case .none: return "None (2-point only)"
        }
    }
}

extension ColourMenu {
    var label: String {
        switch self {
        case .rgbSliders: return "Red / Green / Blue sliders"
        case .hsl: return "Hue / Saturation / Luminance"
        case .off: return "None"
        }
    }
}

let colourSwatch: [String: (Double, Double, Double)] = [
    "RED": (0.90, 0.27, 0.24), "GREEN": (0.24, 0.78, 0.44), "BLUE": (0.25, 0.47, 0.91),
    "CYAN": (0.25, 0.82, 0.85), "MAGENTA": (0.84, 0.36, 0.82), "YELLOW": (0.91, 0.85, 0.29),
]

/// The kind of screen: each makes white and colours from a different mix of light, and the camera needs a
/// matching conversion (its profile has one per kind it was trained on).
enum PanelType: String, Codable, CaseIterable, Identifiable {
    case woled, qdoled, qdlcd, lcd, rgbled, plasma, unknown
    var id: String { rawValue }

    var label: String {
        switch self {
        case .woled: return "OLED"
        case .qdoled: return "QD-OLED"
        case .qdlcd: return "Quantum dot LED or mini-LED"
        case .lcd: return "LED (LCD)"
        case .rgbled: return "Micro RGB or RGB mini-LED"
        case .plasma: return "Plasma"
        case .unknown: return "Not sure"
        }
    }

    /// Examples, to help pick.
    var detail: String? {
        switch self {
        case .woled: return "LG OLED, Panasonic, Philips, most Sony OLEDs"
        case .qdoled: return "Samsung S90 and S95, Sony A95"
        case .qdlcd: return "QLED, Neo QLED, QNED, Bravia 7 and 9, TCL and Hisense mini-LED"
        case .lcd: return "Other LED TVs and most monitors"
        case .rgbled: return "Hisense, Samsung and Sony RGB backlights"
        case .plasma: return "Panasonic, Pioneer, Samsung, LG plasma"
        case .unknown: return "Readings will be approximate"
        }
    }

    /// The key in a camera profile's `panels`, nil when not known.
    var profileKey: String? { self == .unknown ? nil : rawValue }
}

/// Colours far outside their targets, all the same way, mean a setting (the TV's colour gamut, or the
/// player's output) rather than something the colour sliders can fix. Seen on an LG C5 fed by an Apple TV:
/// red and green outside the SDR gamut, green almost twice as bright as it should be.
enum GamutCheck {
    /// A note for the Before results and the Colours step, or nil when the colours are in range.
    static func note(_ report: Report, mode: SignalMode, brand: Brand) -> String? {
        let primaries = report.colours.filter { ["RED", "GREEN", "BLUE"].contains($0.name.uppercased()) }
        // Stronger: well outside the target, or far brighter than it next to the TV's own white.
        let over = primaries.filter { $0.sat > 10 || $0.lum > 40 }.count
        let under = primaries.filter { $0.sat < -12 }.count
        guard over >= 2 || under >= 2 else { return nil }
        let how = over >= 2 ? "stronger" : "weaker"
        return "Your TV is showing \(how) colours than \(mode.label) uses. That's usually a setting, not something "
            + "the colour sliders can fix. Check that the picture mode is Filmmaker or Cinema and \(brand.gamutSetting). "
            + "Playing from an Apple TV or another box? Set it to match the video's dynamic range (Apple TV: Settings › "
            + "Video and Audio › Match Content › Match Dynamic Range), so the TV gets the video as it is."
    }
}

extension Brand {
    /// Where the TV's colour gamut setting is, and what it should be.
    var gamutSetting: String {
        switch self {
        case .lg: return "Picture › Advanced Settings › Colour › Colour Gamut is Auto Detect"
        case .samsung: return "Picture › Expert Settings › Color Space is Auto"
        case .sony: return "Live Colour is Off"
        default: return "the Colour Gamut (or Colour Space) setting is Auto"
        }
    }
}

extension Brand {
    /// What this brand calls the colour menu's three controls: LG says Tint and Luminance, Sony Lightness;
    /// most others say Hue, Saturation and Luminance.
    var cmsNames: (hue: String, saturation: String, brightness: String) {
        switch self {
        case .lg: return ("Tint", "Saturation", "Luminance")
        case .sony: return ("Hue", "Saturation", "Lightness")
        case .vizio: return ("Hue", "Saturation", "Brightness")
        default: return ("Hue", "Saturation", "Luminance")
        }
    }

    /// "Tint / Saturation / Luminance".
    var cmsMenuLabel: String { "\(cmsNames.hue) / \(cmsNames.saturation) / \(cmsNames.brightness)" }
}
