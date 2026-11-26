package io.github.blublub.tvcal.engine

/**
 * The engine's user-facing messages. English by default (the original app's wording, which the
 * fixture tests compare against); the app swaps in a translation for the phone's language.
 */
interface EngineText {
    // Analysis warnings
    val noWhite: String
    fun drifted(percentSigned: String): String
    fun missedTook(name: String, tookSeconds: String, holdSeconds: String): String
    fun missedNothing(name: String): String
    fun darkerThanBefore(name: String): String
    fun topsOut(peakNits: String, names: String): String
    // score words
    val excellent: String
    val veryGood: String
    val good: String
    val noticeablyOff: String
    val clearlyOff: String
    val notRated: String
    val approximate: String
    // live mismatch
    val seesBlack: String
    val seesColour: String
    val seesGrey: String
    val brightnessMismatch: String
    // live meter advice
    val whiteOnTarget: String
    /** Channel names, index 0 red, 1 green, 2 blue. */
    fun channel(i: Int): String
    fun highLow(high: String, low: String): String
    // sweep errors
    val noSync: String
    val outOfOrder: String
    /** Decimal separator for numbers inside messages. */
    val decimal: Char get() = '.'
}

object EnglishText : EngineText {
    override val noWhite = "No 100% white reading - can't analyse this round."
    override fun drifted(percentSigned: String) =
        "Brightness drifted $percentSigned% during the run (TV not warmed up, or auto-brightness/energy saving still on)."
    override fun missedTook(name: String, tookSeconds: String, holdSeconds: String) =
        "$name: no reading. The camera's readings took up to ${tookSeconds}s on it, against ${holdSeconds}s on screen."
    override fun missedNothing(name: String) = "$name: no reading. The camera didn't return anything while it was on screen."
    override fun darkerThanBefore(name: String) =
        "$name is darker than the step before it - timing may be off, or the TV is clipping/tone-mapping."
    override fun topsOut(peakNits: String, names: String) =
        "The TV tops out around $peakNits cd/m² - steps above that ($names) are left out of the corrections."
    override val excellent = "Excellent"
    override val veryGood = "Very good"
    override val good = "Good"
    override val noticeablyOff = "Noticeably off"
    override val clearlyOff = "Clearly off"
    override val notRated = "Not rated"
    override val approximate = "Approximate"
    override val seesBlack = "The camera sees black."
    override val seesColour = "The camera is seeing a colour, not a grey."
    override val seesGrey = "The camera is seeing a grey, not a colour."
    override val brightnessMismatch = "The brightness doesn't match this pattern."
    override val whiteOnTarget = "White is on target."
    override fun channel(i: Int) = listOf("Red", "Green", "Blue")[i]
    override fun highLow(high: String, low: String) = "$high is high and $low is low: lower $high or raise $low."
    override val noSync =
        "No sync flashes seen. Check the camera is pointed at the centre of the screen and the video is playing from the start."
    override val outOfOrder =
        "The greys came in out of order, so the camera and the video got out of step. Start the measurement, then play the video from the very beginning."
}

/** The messages in use. Set once at app start. */
object EngineTexts {
    @Volatile var current: EngineText = EnglishText

    /** A number formatted like C's "%.Nf", with the language's decimal separator. */
    fun num(x: Double, digits: Int): String = fixed(x, digits).replace('.', current.decimal)
    fun signedNum(x: Double, digits: Int): String = signedFixed(x, digits).replace('.', current.decimal)
    fun g(x: Double): String = gFormat(x).replace('.', current.decimal)
}
