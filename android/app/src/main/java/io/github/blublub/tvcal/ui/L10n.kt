package io.github.blublub.tvcal.ui

import io.github.blublub.tvcal.engine.EngineText
import io.github.blublub.tvcal.engine.EngineTexts
import io.github.blublub.tvcal.engine.EnglishText
import io.github.blublub.tvcal.engine.Patch
import io.github.blublub.tvcal.engine.PatchKind
import io.github.blublub.tvcal.engine.fixed
import java.util.Locale

/**
 * Language support. The app's texts live in res/values (English, the original wording) and
 * res/values-de (German). Texts made by code (engine messages, pattern names) go through here.
 */
object L10n {
    /** True when the app runs in German (the phone's or the app's language setting). */
    val german: Boolean get() = Locale.getDefault().language == "de"

    /** Call at start and whenever the language may have changed. */
    fun apply() {
        EngineTexts.current = if (german) GermanText else EnglishText
    }

    /** English or German text, for messages made in code. */
    fun tr(en: String, de: String): String = if (german) de else en

    /** "7.5" or "7,5": numbers in the language's style (C-style rounding, like the original). */
    fun num(x: Double, digits: Int): String = EngineTexts.num(x, digits)
    fun signedNum(x: Double, digits: Int): String = EngineTexts.signedNum(x, digits)

    /** A colour patch id ("RED") in words: "Red" / "Rot". */
    fun colourName(id: String): String = when (id) {
        "RED" -> if (german) "Rot" else "Red"
        "GREEN" -> if (german) "Grün" else "Green"
        "BLUE" -> if (german) "Blau" else "Blue"
        "CYAN" -> "Cyan"
        "MAGENTA" -> "Magenta"
        "YELLOW" -> if (german) "Gelb" else "Yellow"
        else -> id.lowercase().replaceFirstChar { it.uppercase() }
    }

    /** A colour name in capitals, as the patterns label them: "RED" / "ROT". */
    fun colourCaps(id: String): String = colourName(id).uppercase(Locale.ROOT)

    /**
     * A patch's name for display. The engine's names stay English (they're part of the pattern videos'
     * timeline and the history files); this gives the German one: "100% WHITE" -> "100 % WEISS".
     */
    fun patchName(p: Patch): String = if (!german) p.name else patchNameDe(p.name)

    /** German for a patch name string (also for names stored in old measurements). */
    fun patchNameDe(name: String): String {
        var s = name
        for ((en, de) in listOf(
            "(drift check)" to "(Driftkontrolle)", "WHITE" to "WEISS", "GREY" to "GRAU", "nit" to "Nit",
            "nits" to "Nits", "RED" to "ROT", "GREEN" to "GRÜN", "BLUE" to "BLAU", "YELLOW" to "GELB",
        )) s = s.replace(en, de)
        // "50%" -> "50 %", German style
        return Regex("(\\d)%").replace(s) { "${it.groupValues[1]} %" }
    }

    /** Short grey name: "G50" -> "50 % grey" / "50 % Grau". */
    fun greyLevel(p: Patch): String {
        val pct = fixed(p.percent, 0)
        return when {
            p.kind == PatchKind.colour -> colourName(p.id)
            german -> "$pct % Grau"
            else -> "$pct% grey"
        }
    }
}

/** The engine's messages in German. */
object GermanText : EngineText {
    override val decimal = ','
    override val noWhite = "Keine Messung von 100 % Weiß – diese Runde lässt sich nicht auswerten."
    override fun drifted(percentSigned: String) =
        "Die Helligkeit hat sich während der Messung um $percentSigned % verändert (TV noch nicht warm, " +
            "oder automatische Helligkeit/Energiesparen noch an)."
    override fun missedTook(name: String, tookSeconds: String, holdSeconds: String) =
        "${L10n.patchNameDe(name)}: keine Messung. Die Kamera brauchte bis zu $tookSeconds s, das Feld war nur $holdSeconds s zu sehen."
    override fun missedNothing(name: String) =
        "${L10n.patchNameDe(name)}: keine Messung. Die Kamera hat nichts geliefert, solange es zu sehen war."
    override fun darkerThanBefore(name: String) =
        "${L10n.patchNameDe(name)} ist dunkler als die Stufe davor – das Timing stimmt vielleicht nicht, " +
            "oder der TV beschneidet/passt die Helligkeit an (Tone-Mapping)."
    override fun topsOut(peakNits: String, names: String) =
        "Der TV schafft höchstens etwa $peakNits cd/m² – Stufen darüber (${L10n.patchNameDe(names)}) " +
            "bleiben bei den Korrekturen außen vor."
    override val excellent = "Ausgezeichnet"
    override val veryGood = "Sehr gut"
    override val good = "Gut"
    override val noticeablyOff = "Sichtbar daneben"
    override val clearlyOff = "Deutlich daneben"
    override val notRated = "Nicht bewertet"
    override val approximate = "Näherung"
    override val seesBlack = "Die Kamera sieht Schwarz."
    override val seesColour = "Die Kamera sieht eine Farbe, kein Grau."
    override val seesGrey = "Die Kamera sieht ein Grau, keine Farbe."
    override val brightnessMismatch = "Die Helligkeit passt nicht zu diesem Testbild."
    override val whiteOnTarget = "Weiß liegt im Ziel."
    override fun channel(i: Int) = listOf("Rot", "Grün", "Blau")[i]
    override fun highLow(high: String, low: String) = "$high ist zu hoch und $low zu niedrig: $high senken oder $low anheben."
    override val noSync =
        "Keine Sync-Blitze gesehen. Prüfe, ob die Kamera auf die Bildmitte zeigt und das Video von Anfang an läuft."
    override val outOfOrder =
        "Die Grautöne kamen in falscher Reihenfolge, Kamera und Video sind aus dem Takt geraten. Starte die Messung " +
            "und spiele das Video dann ganz von vorne ab."
}
