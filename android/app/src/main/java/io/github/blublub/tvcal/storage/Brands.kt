// Port of TV Calibrator/Storage/Brands.swift (TV Calibrator by Slidebean,
// https://github.com/slidebean/tv-calibrator, CC BY-NC 4.0). Changed: translated to Kotlin, German added.
package io.github.blublub.tvcal.storage

import io.github.blublub.tvcal.engine.Report
import io.github.blublub.tvcal.engine.SignalMode
import io.github.blublub.tvcal.ui.L10n
import io.github.blublub.tvcal.ui.L10n.tr
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Menu names and setup advice per TV brand. Menu names vary a little between models and years, so
 * the app always says "usually".
 *
 * German: the names the TVs show with their menu language set to German. Vizio and Roku TVs are sold
 * mainly in the US, so their menu names stay English; only the words around them are translated.
 */
@Serializable
enum class Brand(val raw: String) {
    @SerialName("lg") LG("lg"),
    @SerialName("samsung") SAMSUNG("samsung"),
    @SerialName("sony") SONY("sony"),
    @SerialName("tcl") TCL("tcl"),
    @SerialName("hisense") HISENSE("hisense"),
    @SerialName("panasonic") PANASONIC("panasonic"),
    @SerialName("philips") PHILIPS("philips"),
    @SerialName("vizio") VIZIO("vizio"),
    @SerialName("toshiba") TOSHIBA("toshiba"),
    @SerialName("sharp") SHARP("sharp"),
    @SerialName("roku") ROKU("roku"),
    @SerialName("other") OTHER("other");

    val id: String get() = raw

    val label: String
        get() = when (this) {
            LG -> "LG"
            SAMSUNG -> "Samsung"
            SONY -> "Sony"
            TCL -> "TCL"
            HISENSE -> "Hisense"
            PANASONIC -> "Panasonic"
            PHILIPS -> "Philips"
            VIZIO -> "Vizio"
            TOSHIBA -> "Toshiba"
            SHARP -> "Sharp"
            ROKU -> "Roku TV"
            OTHER -> tr("Other", "Andere")
        }

    /** What the brand calls the bright-end and dark-end white-balance controls (the same in German menus). */
    val gainName: String get() = "Gain"
    val offsetName: String
        get() = when (this) {
            SONY -> "Bias"
            PANASONIC -> "Cutoff"
            else -> "Offset"
        }

    /** The white balance menu, without LG's "set it to 2 Point" hint (for the multipoint fine-tune step). */
    val whiteBalanceMenu: String
        get() = when (this) {
            LG -> tr("Picture › Advanced Settings › Colour › White Balance",
                "Bild › Erweiterte Einstellungen › Farbe › Weißabgleich")
            else -> whiteBalancePath
        }

    val whiteBalancePath: String
        get() = when (this) {
            LG -> whiteBalanceMenu + tr(" (set it to 2 Point)", " (auf „2 Punkte“ stellen)")
            SAMSUNG -> tr("Picture › Expert Settings › White Balance (2 Point)",
                "Bild › Experteneinstellungen › Weißabgleich (2 Punkte)")
            SONY -> tr("Picture › Advanced settings › Colour › White balance",
                "Bild & Anzeige › Bild › Erweiterte Einstellungen › Farbe › Weißabgleich")
            TCL -> tr("Picture › Advanced (or Expert) settings › White balance",
                "Bild › Erweiterte Einstellungen (oder Experteneinstellungen) › Weißabgleich")
            HISENSE -> tr("Picture › Picture Mode Settings › White Balance",
                "Bild › Bildmodus-Einstellungen › Weißabgleich")
            PANASONIC -> tr("Picture › Advanced Settings › White Balance (2p)",
                "Bild › Erweiterte Einstellungen › Weißabgleich (2 Punkte)")
            PHILIPS -> tr("Picture › Advanced › Colour › Colour temperature › Custom",
                "Bild › Erweitert › Farbe › Farbtemperatur › Benutzerdefiniert")
            VIZIO -> "Picture › Picture Mode Edit › Color Calibration › Color Temperature"
            TOSHIBA -> tr("Picture › Advanced settings › White balance",
                "Bild › Erweiterte Einstellungen › Weißabgleich")
            SHARP -> tr("Picture › Expert settings › White balance",
                "Bild › Experteneinstellungen › Weißabgleich")
            ROKU -> tr("★ button › Advanced picture settings › Expert settings › White balance",
                "★-Taste › Advanced picture settings › Expert settings › White balance")
            OTHER -> tr("the White Balance menu (often under Picture › Advanced or Expert settings)",
                "das Menü „Weißabgleich“ (oft unter Bild › Erweiterte Einstellungen oder Experteneinstellungen)")
        }

    val colourMenuPath: String
        get() = when (this) {
            LG -> tr("Picture › Advanced Settings › Colour › Colour Management System",
                "Bild › Erweiterte Einstellungen › Farbe › Farbmanagementsystem")
            SAMSUNG -> tr("Picture › Expert Settings › Color Space Settings › Custom",
                "Bild › Experteneinstellungen › Farbraumeinstellungen › Benutzerdefiniert")
            SONY -> tr("Picture › Advanced settings › Colour › Advanced colour adjustment",
                "Bild & Anzeige › Bild › Erweiterte Einstellungen › Farbe › Erweiterte Farbeinstellung")
            TCL -> tr("Picture › Advanced settings › Colour tuner",
                "Bild › Erweiterte Einstellungen › Farbabstimmung (Colour Tuner)")
            HISENSE -> tr("Picture › Picture Mode Settings › Color Tuner",
                "Bild › Bildmodus-Einstellungen › Farbabstimmung (Color Tuner)")
            PANASONIC -> tr("Picture › Advanced Settings › Colour Management",
                "Bild › Erweiterte Einstellungen › Farbmanagement")
            PHILIPS -> tr("Picture › Advanced › Colour › Colour control",
                "Bild › Erweitert › Farbe › Farbsteuerung (Colour control)")
            VIZIO -> "Picture › Picture Mode Edit › Color Calibration › Color Tuner"
            TOSHIBA -> tr("Picture › Advanced settings › Colour tuner",
                "Bild › Erweiterte Einstellungen › Farbabstimmung (Colour Tuner)")
            SHARP -> tr("Picture › Expert settings › Colour tuner",
                "Bild › Experteneinstellungen › Farbabstimmung (Colour Tuner)")
            ROKU -> tr("★ button › Advanced picture settings › Expert settings › Color tuning",
                "★-Taste › Advanced picture settings › Expert settings › Color tuning")
            OTHER -> tr("the colour management menu (often called Colour Tuner, CMS or Colour Space)",
                "das Farbmanagement-Menü (oft „Farbabstimmung“, „Colour Tuner“, „CMS“ oder „Farbraum“ genannt)")
        }

    /** The usual colour menu type for the brand (the user confirms it). */
    val colourMenu: ColourMenu get() = if (this == SAMSUNG) ColourMenu.RGB_SLIDERS else ColourMenu.HSL

    /** The usual multipoint white balance for the brand (the user confirms it). */
    val multipoint: Multipoint
        get() = when (this) {
            LG, SAMSUNG, HISENSE -> Multipoint.TWENTY
            SONY, PANASONIC, VIZIO, TCL -> Multipoint.TEN
            PHILIPS, TOSHIBA, SHARP, ROKU, OTHER -> Multipoint.NONE
        }

    /** The calibrated picture mode, per format. */
    fun pictureMode(mode: SignalMode): String = when {
        this == SAMSUNG && mode == SignalMode.DV ->
            tr("Samsung TVs don't support Dolby Vision: calibrate HDR10 or HDR10+ instead",
                "Samsung-Fernseher unterstützen kein Dolby Vision: Kalibriere stattdessen HDR10 oder HDR10+")
        this == LG && mode == SignalMode.DV ->
            tr("Dolby Vision Filmmaker Mode (or Cinema Home)", "Dolby Vision Filmmaker Mode (oder Kino Home)")
        this == SONY && (mode == SignalMode.SDR || mode == SignalMode.HDR10 || mode == SignalMode.HDR10P) ->
            tr("Professional (or Custom)", "Professionell (oder Anwender)")
        this == VIZIO && mode == SignalMode.DV -> "Dolby Vision Dark"
        this == VIZIO -> "Calibrated Dark"
        this == ROKU -> if (mode == SignalMode.DV) "Dolby Vision Dark" else "Movie"
        this == PANASONIC && mode == SignalMode.DV ->
            tr("Dolby Vision Dark (or Dolby Vision Cinema)", "Dolby Vision Dunkel (oder Dolby Vision Kino)")
        this == HISENSE && mode == SignalMode.SDR ->
            tr("Filmmaker Mode (or Theater Night)", "Filmmaker Mode (oder Kino Nacht)")
        this == SHARP && mode == SignalMode.SDR -> tr("Movie (or Filmmaker Mode)", "Film (oder Filmmaker Mode)")
        mode == SignalMode.DV ->
            tr("Dolby Vision Dark (or Dolby Vision Cinema)", "Dolby Vision Dunkel (oder Dolby Vision Kino)")
        mode == SignalMode.SDR -> tr("Filmmaker Mode (or Movie / Cinema)", "Filmmaker Mode (oder Film / Kino)")
        else -> tr("Filmmaker Mode (or Movie / Cinema)", "Filmmaker Mode (oder Film / Kino)") // HDR10, HDR10+
    }

    /** Picture settings to make before measuring (after the picture mode). */
    fun extras(mode: SignalMode): List<String> {
        val items = ArrayList<String>()
        val sdr = mode == SignalMode.SDR
        when (this) {
            LG -> {
                items += tr("Energy Saving and AI Brightness: Off", "Energiesparen und KI-Helligkeit: Aus")
                if (sdr) items += tr("Gamma: BT.1886, Colour Temperature: Warm 50", "Gamma: BT.1886, Farbtemperatur: Warm 50")
                if (mode.isHDR) items += tr("Dynamic Tone Mapping: Off", "Dynamisches Tone Mapping: Aus")
            }
            SAMSUNG -> {
                items += tr("Energy saving, Adaptive Brightness and Contrast Enhancer: Off",
                    "Energiesparmodus, Adaptive Helligkeit und Kontrastverbesserung: Aus")
                if (sdr) items += tr("Colour Tone: Warm2, Gamma: BT.1886", "Farbton: Warm2, Gamma: BT.1886")
                if (mode.isHDR) items += tr("HDR Tone Mapping: Static, ST.2084: 0", "HDR-Tone-Mapping: Statisch, ST.2084: 0")
                items += tr("Readings jumping around? Turn off Brightness Optimisation and set Local Dimming to Low",
                    "Springen die Messwerte? Schalte „Helligkeitsoptimierung“ aus und stell „Local Dimming“ auf „Niedrig“")
            }
            SONY -> {
                items += tr("Light sensor, Live colour and Advanced contrast enhancer: Off",
                    "Lichtsensor, Live Colour und Erweiterte Kontrastverbesserung: Aus")
                if (sdr) items += tr("Colour temperature: Expert 1", "Farbtemperatur: Experte 1")
                if (mode.isHDR) items += tr("HDR tone mapping: Off", "HDR-Tone-Mapping: Aus")
            }
            TCL, HISENSE, TOSHIBA, SHARP -> {
                items += tr("Dynamic contrast, Eco / energy saving and the light sensor: Off",
                    "Dynamischer Kontrast, Eco / Energiesparen und Lichtsensor: Aus")
                if (sdr) items += tr("Colour temperature: Warm (or Low)", "Farbtemperatur: Warm (oder Niedrig)")
                if (mode.isHDR) items += tr("Any dynamic tone mapping: Off", "Jedes dynamische Tone Mapping: Aus")
                if (this == HISENSE || this == TCL) items += tr("Local dimming: Low or Medium", "Local Dimming: Niedrig oder Mittel")
            }
            PANASONIC -> {
                items += tr("Ambient sensor and Dynamic Contrast: Off", "Umgebungssensor und Dynamischer Kontrast: Aus")
                if (sdr) items += tr("Colour Temperature: Warm2", "Farbtemperatur: Warm2")
                if (mode.isHDR) items += tr("HDR Brightness Enhancer / Dynamic HDR Effect: Off",
                    "HDR-Helligkeitsverbesserung / Dynamischer HDR-Effekt: Aus")
            }
            PHILIPS -> {
                items += tr("Ambilight, the light sensor and Dynamic contrast: Off",
                    "Ambilight, Lichtsensor und Dynamischer Kontrast: Aus")
                if (sdr) items += tr("Colour temperature: Warm", "Farbtemperatur: Warm")
                if (mode.isHDR) items += tr("HDR Plus / tone mapping: Off or Standard", "HDR Plus / Tone Mapping: Aus oder Standard")
            }
            VIZIO -> {
                items += "Auto Brightness Control: Off, Contrast Control: Off"
                if (sdr) items += tr("Color Temperature: Normal (Vizio's is the film standard)",
                    "Color Temperature: Normal (bei Vizio ist das der Filmstandard)")
            }
            ROKU -> {
                items += tr("Dynamic contrast and Action smoothing: Off", "Dynamic contrast und Action smoothing: Off")
                if (sdr) items += "Color temperature: Warm"
            }
            OTHER -> {
                items += tr("Energy saving, light sensors and contrast boosters: Off",
                    "Energiesparen, Lichtsensoren und Kontrastverstärker: Aus")
                if (sdr) items += tr("Colour temperature: Warm", "Farbtemperatur: Warm")
                if (mode.isHDR) items += tr("Dynamic tone mapping: Off", "Dynamisches Tone Mapping: Aus")
            }
        }
        if (mode == SignalMode.HDR10P) items += tr(
            "HDR10+ mode: the plain setting, not Adaptive / Dynamic",
            if (this == VIZIO || this == ROKU) "HDR10+-Modus: die einfache Einstellung, nicht Adaptive / Dynamic"
            else "HDR10+-Modus: die einfache Einstellung, nicht Adaptiv / Dynamisch",
        )
        return items
    }

    /** Everything to set before measuring, as one list (picture mode first). */
    fun setup(mode: SignalMode): List<String> {
        val prefix = tr("Picture mode: ", if (this == VIZIO || this == ROKU) "Bildmodus (Picture Mode): " else "Bildmodus: ")
        return listOf(prefix + pictureMode(mode)) + extras(mode)
    }

    /**
     * Where the TV's colour gamut setting is, and what it should be. Ends the [GamutCheck] sentence:
     * "... and <this>" / "... und ob <this>" (so in German the verb comes last).
     */
    val gamutSetting: String
        get() = when (this) {
            LG -> tr("Picture › Advanced Settings › Colour › Colour Gamut is Auto Detect",
                "Bild › Erweiterte Einstellungen › Farbe › Farbraum (Colour Gamut) auf „Automatische Erkennung“ steht")
            SAMSUNG -> tr("Picture › Expert Settings › Color Space is Auto",
                "Bild › Experteneinstellungen › Farbraum auf „Auto“ steht")
            SONY -> tr("Live Colour is Off", "Live Colour auf „Aus“ steht")
            VIZIO, ROKU -> tr("the Colour Gamut (or Colour Space) setting is Auto",
                "die Einstellung Color Gamut (oder Color Space) auf „Auto“ steht")
            else -> tr("the Colour Gamut (or Colour Space) setting is Auto",
                "die Einstellung Farbraum (Colour Gamut / Colour Space) auf „Auto“ steht")
        }

    /**
     * What this brand calls the colour menu's three controls: LG says Tint and Luminance, Sony Lightness;
     * most others say Hue, Saturation and Luminance.
     */
    val cmsNames: CmsNames
        get() = when (this) {
            LG -> CmsNames(tr("Tint", "Farbton"), tr("Saturation", "Sättigung"), tr("Luminance", "Luminanz"))
            SONY -> CmsNames(tr("Hue", "Farbton"), tr("Saturation", "Sättigung"), tr("Lightness", "Helligkeit"))
            VIZIO -> CmsNames("Hue", "Saturation", "Brightness")
            ROKU -> CmsNames("Hue", "Saturation", "Luminance")
            else -> CmsNames(tr("Hue", "Farbton"), tr("Saturation", "Sättigung"), tr("Luminance", "Luminanz"))
        }

    /** "Tint / Saturation / Luminance". */
    val cmsMenuLabel: String get() = "${cmsNames.hue} / ${cmsNames.saturation} / ${cmsNames.brightness}"

    companion object {
        fun fromRaw(s: String): Brand? = entries.firstOrNull { it.raw == s }
    }
}

/** The names of a colour menu's three controls (Swift: a labelled tuple). */
data class CmsNames(val hue: String, val saturation: String, val brightness: String)

/** Multipoint white balance menus. Saved as its number (20, 11 or 0), like the Swift Int raw value. */
@Serializable(with = MultipointSerializer::class)
enum class Multipoint(val raw: Int) {
    TWENTY(20), TEN(11), NONE(0);

    val id: Int get() = raw
    val label: String
        get() = when (this) {
            TWENTY -> tr("20 points (5%, 10% … 100%)", "20 Punkte (5 %, 10 % … 100 %)")
            TEN -> tr("10 points (10%, 20% … 100%)", "10 Punkte (10 %, 20 % … 100 %)")
            NONE -> tr("None (2-point only)", "Keins (nur 2 Punkte)")
        }

    companion object {
        fun fromRaw(v: Int): Multipoint? = entries.firstOrNull { it.raw == v }
    }
}

object MultipointSerializer : KSerializer<Multipoint> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("io.github.blublub.tvcal.storage.Multipoint", PrimitiveKind.INT)
    override fun serialize(encoder: Encoder, value: Multipoint) = encoder.encodeInt(value.raw)
    override fun deserialize(decoder: Decoder): Multipoint {
        val v = decoder.decodeInt()
        return Multipoint.fromRaw(v) ?: throw SerializationException("Unknown Multipoint $v")
    }
}

/**
 * Which colour menu the TV has.
 *
 * TODO(port): this is CalibrationKit's `ColourMenu` (AutoCalibration.swift), which the Kotlin engine
 * does not have yet. Move it into io.github.blublub.tvcal.engine when AutoCalibration is ported.
 */
@Serializable
enum class ColourMenu(val raw: String) {
    /** Red, Green and Blue sliders for each colour (Samsung's Color Space > Custom). */
    @SerialName("samsung") RGB_SLIDERS("samsung"),
    /** Hue, Saturation and Brightness per colour (LG, Sony, TCL...). */
    @SerialName("hsl") HSL("hsl"),
    @SerialName("off") OFF("off");

    val label: String
        get() = when (this) {
            RGB_SLIDERS -> tr("Red / Green / Blue sliders", "Regler für Rot / Grün / Blau")
            HSL -> tr("Hue / Saturation / Luminance", "Farbton / Sättigung / Luminanz")
            OFF -> tr("None", "Keins")
        }

    companion object {
        fun fromRaw(s: String): ColourMenu? = entries.firstOrNull { it.raw == s }
    }
}

/** Display colour (r, g, b in 0-1) for each colour patch. */
val colourSwatch: Map<String, Triple<Double, Double, Double>> = mapOf(
    "RED" to Triple(0.90, 0.27, 0.24), "GREEN" to Triple(0.24, 0.78, 0.44), "BLUE" to Triple(0.25, 0.47, 0.91),
    "CYAN" to Triple(0.25, 0.82, 0.85), "MAGENTA" to Triple(0.84, 0.36, 0.82), "YELLOW" to Triple(0.91, 0.85, 0.29),
)

/**
 * The kind of screen: each makes white and colours from a different mix of light, and the camera needs a
 * matching conversion (its profile has one per kind it was trained on).
 */
@Serializable
enum class PanelType(val raw: String) {
    @SerialName("woled") WOLED("woled"),
    @SerialName("qdoled") QDOLED("qdoled"),
    @SerialName("qdlcd") QDLCD("qdlcd"),
    @SerialName("lcd") LCD("lcd"),
    @SerialName("rgbled") RGBLED("rgbled"),
    @SerialName("plasma") PLASMA("plasma"),
    @SerialName("unknown") UNKNOWN("unknown");

    val id: String get() = raw

    val label: String
        get() = when (this) {
            WOLED -> "OLED"
            QDOLED -> "QD-OLED"
            QDLCD -> tr("Quantum dot LED or mini-LED", "Quantum-Dot-LED oder Mini-LED")
            LCD -> "LED (LCD)"
            RGBLED -> tr("Micro RGB or RGB mini-LED", "Micro-RGB oder RGB-Mini-LED")
            PLASMA -> "Plasma"
            UNKNOWN -> tr("Not sure", "Weiß nicht")
        }

    /** Examples, to help pick. */
    val detail: String?
        get() = when (this) {
            WOLED -> tr("LG OLED, Panasonic, Philips, most Sony OLEDs", "LG OLED, Panasonic, Philips, die meisten Sony-OLEDs")
            QDOLED -> tr("Samsung S90 and S95, Sony A95", "Samsung S90 und S95, Sony A95")
            QDLCD -> tr("QLED, Neo QLED, QNED, Bravia 7 and 9, TCL and Hisense mini-LED",
                "QLED, Neo QLED, QNED, Bravia 7 und 9, Mini-LED von TCL und Hisense")
            LCD -> tr("Other LED TVs and most monitors", "Andere LED-Fernseher und die meisten Monitore")
            RGBLED -> tr("Hisense, Samsung and Sony RGB backlights", "RGB-Hintergrundbeleuchtung von Hisense, Samsung und Sony")
            PLASMA -> tr("Panasonic, Pioneer, Samsung, LG plasma", "Plasma von Panasonic, Pioneer, Samsung, LG")
            UNKNOWN -> tr("Readings will be approximate", "Die Messwerte sind dann nur ungefähr")
        }

    /** The key in a camera profile's `panels`, null when not known. */
    val profileKey: String? get() = if (this == UNKNOWN) null else raw

    companion object {
        fun fromRaw(s: String): PanelType? = entries.firstOrNull { it.raw == s }
    }
}

/**
 * Colours far outside their targets, all the same way, mean a setting (the TV's colour gamut, or the
 * player's output) rather than something the colour sliders can fix. Seen on an LG C5 fed by an Apple TV:
 * red and green outside the SDR gamut, green almost twice as bright as it should be.
 */
object GamutCheck {
    /** A note for the Before results and the Colours step, or null when the colours are in range. */
    fun note(report: Report, mode: SignalMode, brand: Brand): String? {
        val primaries = report.colours.filter { it.name.uppercase() in listOf("RED", "GREEN", "BLUE") }
        // Stronger: well outside the target, or far brighter than it next to the TV's own white.
        val over = primaries.count { it.sat > 10 || it.lum > 40 }
        val under = primaries.count { it.sat < -12 }
        if (!(over >= 2 || under >= 2)) return null
        val stronger = over >= 2
        if (L10n.german) {
            val how = if (stronger) "kräftigere" else "blassere"
            return "Dein Fernseher zeigt $how Farben, als ${mode.label} vorsieht. Das liegt meist an einer Einstellung " +
                "und lässt sich nicht mit den Farbreglern beheben. Prüfe, ob der Bildmodus auf Filmmaker oder Kino steht " +
                "und ob ${brand.gamutSetting}. Spielst du von einem Apple TV oder einer anderen Box ab? Stell sie so ein, " +
                "dass sie den Dynamikbereich des Videos übernimmt (Apple TV: Einstellungen › Video und Audio › " +
                "Inhalt anpassen › Dynamikbereich anpassen), damit der Fernseher das Video so bekommt, wie es ist."
        }
        val how = if (stronger) "stronger" else "weaker"
        return "Your TV is showing $how colours than ${mode.label} uses. That's usually a setting, not something " +
            "the colour sliders can fix. Check that the picture mode is Filmmaker or Cinema and ${brand.gamutSetting}. " +
            "Playing from an Apple TV or another box? Set it to match the video's dynamic range (Apple TV: Settings › " +
            "Video and Audio › Match Content › Match Dynamic Range), so the TV gets the video as it is."
    }
}
