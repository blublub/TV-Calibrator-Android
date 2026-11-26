// Port of TV Calibrator/Views/LiveSteps.swift (TV Calibrator by Slidebean, https://github.com/slidebean/tv-calibrator, CC BY-NC 4.0). Changed: translated to Kotlin/Compose.
package io.github.blublub.tvcal.ui.screens

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.blublub.tvcal.engine.Analysis
import io.github.blublub.tvcal.engine.LiveReading
import io.github.blublub.tvcal.engine.Patterns
import io.github.blublub.tvcal.engine.PointFinder
import io.github.blublub.tvcal.engine.signedFixed
import io.github.blublub.tvcal.measure.GuidedController
import io.github.blublub.tvcal.measure.SweepRunner
import io.github.blublub.tvcal.measure.uiScope
import io.github.blublub.tvcal.storage.ColourMenu
import io.github.blublub.tvcal.storage.GamutCheck
import io.github.blublub.tvcal.storage.GuideStep
import io.github.blublub.tvcal.storage.Multipoint
import io.github.blublub.tvcal.ui.LocalApp
import io.github.blublub.tvcal.ui.components.GamutChart
import io.github.blublub.tvcal.ui.components.GamutDot
import io.github.blublub.tvcal.ui.components.WhiteTarget
import io.github.blublub.tvcal.ui.theme.Card
import io.github.blublub.tvcal.ui.theme.Eyebrow
import io.github.blublub.tvcal.ui.theme.LinkButton
import io.github.blublub.tvcal.ui.theme.SecondaryButton
import io.github.blublub.tvcal.ui.theme.Theme
import io.github.blublub.tvcal.ui.theme.TipRow
import io.github.blublub.tvcal.ui.theme.TipTone
import io.github.blublub.tvcal.ui.theme.Type
import io.github.blublub.tvcal.ui.theme.fmt
import io.github.blublub.tvcal.ui.theme.markdown
import io.github.blublub.tvcal.ui.theme.signed
import io.github.blublub.tvcal.ui.theme.symbol
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class WBKind { gain, offset, fine }

/** Measurements started from these steps outlive the step on screen (like the original's `Task {}`). */
private val liveStepsScope = uiScope()

/** "RED" -> "Red" (Swift's `.capitalized`). */
private fun capitalized(s: String): String = s.lowercase().replaceFirstChar { it.uppercase() }

// MARK: - Pinned main action

/**
 * The step's main (yellow) action (Swift `.pinnedAction`): pinned by the hosting screen (GuidedScreen),
 * or drawn in place when there is no host. A null title shows none (while measuring, say).
 */
@Composable
private fun StepMainAction(title: String?, enabled: Boolean = true, onClick: () -> Unit) {
    PinnedActionOrInline(title, enabled, onClick)
}

// MARK: - Success chime

/**
 * The little success sound (and a haptic tap) the first time a setting reaches its target. Each setting
 * chimes once per TV, format and step while the app is open; it needs two on-target readings in a row.
 * (The original's Chime; here it plays through AppModel.chime().)
 */
private object LiveChime {
    /** Settings › Sounds. On unless turned off. */
    const val ENABLED_KEY = "feedback.sounds"

    private val fired = HashSet<String>()
    private val streak = HashMap<String, Int>()

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences("app", Context.MODE_PRIVATE).getBoolean(ENABLED_KEY, true)

    /** Call with every new reading of a setting. True when it should chime now. */
    fun observe(key: String?, ok: Boolean): Boolean {
        if (key == null) return false
        if (!ok) {
            streak[key] = 0
            return false
        }
        val n = (streak[key] ?: 0) + 1
        streak[key] = n
        if (n < 2 || key in fired) return false
        fired.add(key)
        return true
    }
}

/** Feeds a setting's value to the chime whenever it changes (Swift's `.onChange(of: value)`). */
@Composable
private fun ChimeOnChange(value: Double?, chime: String?, tolerance: Double) {
    val app = LocalApp.current
    val haptic = LocalHapticFeedback.current
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(value) {
        if (first[0]) {
            first[0] = false
            return@LaunchedEffect
        }
        val ok = value?.let { abs(it) <= tolerance } ?: false
        if (LiveChime.observe(chime, ok) && LiveChime.enabled(app.appContext)) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            app.chime()
        }
    }
}

/** "This TV's readings wobble…" and "Average of 5 readings · latest 42.1 cd/m²  [Restart]". */
@Composable
fun AverageLine(reading: LiveReading, restart: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (reading.jitter >= 0.004) {
            Text(
                "This TV's readings wobble about ±${fmt(reading.jitter * 100)}%, so the bars show an average.",
                style = Type.small, color = Theme.textSecondary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Average of ${reading.averaged} reading${if (reading.averaged == 1) "" else "s"}" +
                    (if (reading.averaged < reading.need) " (needs ${reading.need} to call it steady)" else "") +
                    " · latest ${fmt(reading.latestY, if (reading.latestY < 10) 2 else 1)} cd/m²",
                style = Type.small.copy(fontFeatureSettings = "tnum"), color = Theme.textSecondary,
                modifier = Modifier.weight(1f),
            )
            LinkButton(
                "Restart", restart, style = Type.captionStrong,
                modifier = Modifier.semantics { stateDescription = "Use after changing a setting, to see its effect sooner" },
            )
        }
    }
}

/** What kind of tip: to do, on target, done, at the TV's limit (Swift's `TipList.TipKind`). */
enum class TipKind { todo, ok, done, limit }

/** The step's tips, each one a `TipRow` (to do, on target, at the TV's limit). */
@Composable
fun TipList(tips: List<Pair<String, TipKind>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (t in tips) TipRow(tone = tipListTone(t.second), text = t.first)
    }
}

/** Swift's `TipList.tone(_:)`. */
fun tipListTone(kind: TipKind): TipTone = when (kind) {
    TipKind.todo -> TipTone.todo
    TipKind.ok, TipKind.done -> TipTone.ok
    TipKind.limit -> TipTone.limit
}

// MARK: - Shared parts of the live steps

/** A big signed reading with a true minus sign: "+3.2%", "−2.1". One decimal below 10, none above. */
private fun bigSigned(v: Double?, suffix: String = ""): String {
    if (v == null || !v.isFinite()) return "–"
    val digits = if (abs(v) < 9.95) 1 else 0
    return signedFixed(v, digits).replace("-", "−") + suffix
}

/** One channel of the white balance: "Red  +3.2%" over a centred bar with a green tolerance zone. */
@Composable
private fun ChannelBalanceRow(
    label: String,
    value: Double?,          // fraction, e.g. 0.032 = 3.2% too much
    colour: Color,
    fullScale: Double = 0.05, // this much fills half the bar
    tolerance: Double = 0.012,
    /** Names this setting for the success chime; null: no chime. */
    chime: String? = null,
) {
    val ok = value?.let { abs(it) <= tolerance } ?: false
    val tint = if (ok) Theme.green else Color.White
    Column(
        Modifier.clearAndSetSemantics {
            contentDescription = label
            stateDescription = if (ok) "On target" else bigSigned(value?.let { it * 100 }, " percent")
        },
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = Type.brand(16f, FontWeight.SemiBold), color = Color.White)
            Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (ok) Icon(symbol("checkmark"), contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
                BasicText(
                    bigSigned(value?.let { it * 100 }, "%"),
                    style = Type.reading(38f).copy(letterSpacing = (-1).sp, color = tint),
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = (38f * 0.6f).sp, maxFontSize = 38.sp),
                )
            }
        }
        CentredTrack(value, colour, fullScale, tolerance, ok)
    }
    ChimeOnChange(value, chime, tolerance)
}

/**
 * The centred track: the white target line in the middle, the green tolerance zone around it, and the
 * channel's amount growing left (too little) or right (too much).
 */
@Composable
private fun CentredTrack(value: Double?, colour: Color, fullScale: Double, tolerance: Double, ok: Boolean) {
    Canvas(Modifier.fillMaxWidth().height(20.dp)) {
        val p = 1.dp.toPx()
        val w = size.width
        val h = size.height
        val mid = w / 2
        val zone = min(w, max(4 * p, (tolerance / fullScale).toFloat() * mid * 2))
        val off = ((value ?: 0.0) / fullScale).coerceIn(-1.0, 1.0).toFloat() * mid
        drawRoundRect(Color.White.copy(alpha = 0.07f), cornerRadius = CornerRadius(6 * p))
        drawRect(Theme.green.copy(alpha = 0.16f), topLeft = Offset(mid - zone / 2, 0f), size = Size(zone, h))
        if (value != null) {
            if (ok) {
                drawRoundRect(Theme.green, topLeft = Offset(mid - 4 * p, (h - 10 * p) / 2), size = Size(8 * p, 10 * p),
                    cornerRadius = CornerRadius(4 * p))
            } else {
                val len = max(abs(off), 4 * p)
                drawRoundRect(colour, topLeft = Offset(if (off < 0) mid + off else mid, (h - 10 * p) / 2),
                    size = Size(len, 10 * p), cornerRadius = CornerRadius(4 * p))
            }
        }
        drawRect(Color.White, topLeft = Offset(mid - p, 0f), size = Size(2 * p, h))
    }
}

/** "Too little · On target · Too much" under the balance bars. */
@Composable
private fun BalanceScale() {
    Row(Modifier.fillMaxWidth().clearAndSetSemantics { }) {
        Text("Too little", style = Type.tiny, color = Theme.textSecondary, modifier = Modifier.weight(1f))
        Text("On target", style = Type.tiny, color = Theme.green)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Text("Too much", style = Type.tiny, color = Theme.textSecondary)
        }
    }
}

/**
 * One colour-menu control, drawn like the white balance bars: "Saturation  +3.1" over a centred track.
 * The centre line is the target; the bar grows right when it's too much (lower it) and left when it's too
 * little (raise it). Under it, what to do: "Too strong: lower it".
 */
@Composable
private fun ColourControlRow(
    label: String,
    value: Double?,          // signed error: + too much, − too little
    colour: Color,
    fullScale: Double,       // this much fills half the track
    tolerance: Double,
    caption: String? = null,
    /** Names this setting for the success chime; null: no chime. */
    chime: String? = null,
) {
    val ok = value?.let { abs(it) <= tolerance } ?: false
    val tint = if (ok) Theme.green else Color.White
    Column(
        Modifier.clearAndSetSemantics {
            contentDescription = label
            stateDescription = if (ok) "On target" else (caption ?: bigSigned(value))
        },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = Type.brand(16f, FontWeight.SemiBold), color = Color.White)
            Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (ok) Icon(symbol("checkmark"), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                BasicText(
                    bigSigned(value),
                    style = Type.reading(30f).copy(letterSpacing = (-1).sp, color = tint),
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = (30f * 0.6f).sp, maxFontSize = 30.sp),
                )
            }
        }
        CentredTrack(value, colour, fullScale, tolerance, ok)
        if (caption != null && !ok && value != null) {
            Text(caption, style = Type.small, color = Theme.textSecondary)
        }
    }
    ChimeOnChange(value, chime, tolerance)
}

/** Demo TVs: what the readings are, in place of the video to play. */
@Composable
private fun DemoNote() {
    Card(hero = true) {
        Text("Demo · simulated readings", style = Type.captionStrong, color = Theme.accent)
        Text("Try the remote buttons below.", style = Type.small, color = Theme.textSecondary)
    }
}

/** Shown until the first reading arrives. */
@Composable
private fun WaitingForReading() {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), color = Theme.tealBright, strokeWidth = 2.dp)
        Text(
            "Start the video on the TV: readings appear as soon as the camera sees it.",
            style = Type.captionBrand, color = Theme.textSecondary,
        )
    }
}

// MARK: - 3, 4 + fine-tune: live white balance

/** Swift's `@State still`: the last Red/Blue readings and when they settled there. */
private class WBStill(val q: List<Double>, val since: Long)

@Composable
fun WhiteBalanceStep(guided: GuidedController, kind: WBKind, pid: String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        WhiteBalanceBody(guided, kind, pid)
        StepMainAction(wbNextTitle(guided, kind, pid)) { wbNext(guided, kind, guided.live.reading?.steady ?: false) }
    }
}

/** The step without its main action (fine-tune puts that below the grey chips). */
@Composable
private fun WhiteBalanceBody(guided: GuidedController, kind: WBKind, pid: String) {
    val brand = guided.tv.brand
    val suggestion: Int? = if (kind == WBKind.fine) guided.suggestions[pid] else null
    var still by remember(pid) { mutableStateOf<WBStill?>(null) }

    /** "10% grey", "50% grey (92 nits)", "100 nits". */
    val greyName = shortName(Patterns.patch(guided.hdr, pid)?.name ?: pid).replaceFirst("%", "% grey")

    /** What to adjust: "Adjust Red and Blue Gain". */
    val instructions = when (kind) {
        WBKind.gain -> "Adjust Red and Blue ${brand.gainName}"
        WBKind.offset -> "Adjust Red and Blue ${brand.offsetName}"
        WBKind.fine ->
            if (suggestion != null) "Adjust Red and Blue on the $suggestion% control"
            else "Adjust Red and Blue on the control nearest this grey"
    }

    val whereText = if (kind == WBKind.fine) brand.whiteBalancePath.replace(" (set it to 2 Point)", "") + " › 10 or 20 Point"
    else brand.whiteBalancePath

    /** The reading, when it's for the pattern on screen. */
    val reading = guided.live.reading
    val valid: LiveReading? = reading?.takeIf { it.mismatch == null }

    LaunchedEffect(pid) { guided.showLive(pid) }
    // Fine-tune: notice when the bars haven't moved for a minute (maybe the wrong control).
    LaunchedEffect(reading?.qR) {
        if (kind != WBKind.fine) return@LaunchedEffect
        val r = guided.live.reading ?: return@LaunchedEffect
        val qR = r.qR ?: return@LaunchedEffect
        val qB = r.qB ?: return@LaunchedEffect
        val s = still
        if (s != null && abs(s.q[0] - qR) <= 0.006 && abs(s.q[1] - qB) <= 0.006) return@LaunchedEffect
        still = WBStill(listOf(qR, qB), System.currentTimeMillis())
    }

    /** The success chime's name for one channel of this step: per TV, format, step and grey. */
    fun chimeKey(channel: String) = "${guided.tv.id}|${guided.mode.raw}|$kind|$pid|$channel"

    /** Fine-tune: the bars haven't moved for a minute, so it may be the wrong control. */
    fun stillNotice(r: LiveReading): String? {
        val s = still
        if (kind != WBKind.fine || r.ok || s == null || System.currentTimeMillis() - s.since <= 60_000) return null
        return STILL_NOTICE
    }

    val demoControls: List<Pair<String, String>> = when (kind) {
        WBKind.gain -> listOf("Red ${brand.gainName}" to "gain:R", "Blue ${brand.gainName}" to "gain:B")
        WBKind.offset -> listOf("Red ${brand.offsetName}" to "offset:R", "Blue ${brand.offsetName}" to "offset:B")
        WBKind.fine -> {
            val p = suggestion ?: (Patterns.patch(guided.hdr, pid)?.percent ?: 50.0).roundToInt()
            listOf("Red $p%" to "p20:R:$p", "Blue $p%" to "p20:B:$p")
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // The instruction, then the white-point target, big: the dot has to land in the centre ring. Then the
        // video to play (the grey's details when fine-tuning).
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (kind == WBKind.fine) Eyebrow(text = "Fine-tune $greyName")
            Text(instructions, style = Type.stepTitleStyle)
            Text("Until both bars sit on the line. Leave Green alone.", style = Type.captionBrand, color = Theme.textSecondary)
        }
        if (valid != null) BigWhiteTarget(valid)
        if (guided.tv.isDemo) {
            DemoNote()
        } else {
            guided.file(pid)?.let { f ->
                PlayFileCard(file = f, mode = guided.mode, compact = true, patch = Patterns.patch(guided.hdr, pid))
            }
        }

        // live
        guided.live.error?.let { TipRow(tone = TipTone.limit, text = it) }
        if (reading != null) {
            val m = reading.mismatch
            if (m != null) {
                TipRow(tone = TipTone.limit, text = "$m Is the right file playing, with the camera pointed at the centre?")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    ChannelBalanceRow(label = "Red", value = reading.qR, colour = Theme.red, chime = chimeKey("R"))
                    ChannelBalanceRow(label = "Blue", value = reading.qB, colour = Theme.blue, chime = chimeKey("B"))
                    BalanceScale()
                }
                stillNotice(reading)?.let { TipRow(tone = TipTone.limit, text = it) }
            }
        } else {
            WaitingForReading()
        }

        if (guided.tv.isDemo) {
            DemoRemote(guided = guided, controls = demoControls, modifier = Modifier.padding(top = 4.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            LinkButton("Can't get closer", {
                guided.markLimit(if (kind == WBKind.gain) "bright" else if (kind == WBKind.offset) "dark" else "fine:$pid")
                wbNext(guided, kind, done = true)
            })
            if (kind == WBKind.fine && nextGrey(guided, pid) != null) {
                LinkButton("Back to the check", { guided.go(GuideStep.CHECK) })
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("TV menu: $whereText", style = Type.small, color = Theme.textSecondary)
            if (valid != null) AverageLine(reading = valid) { guided.live.restartAverage() }
        }
    }
}

private const val STILL_NOTICE = "The bars haven't moved for a minute. If you've been adjusting, it may be the wrong " +
    "control: try the one a step above or below, and when you find it, pick it under “The control that moves this grey”."

private fun wbNextTitle(guided: GuidedController, kind: WBKind, pid: String): String = when (kind) {
    WBKind.gain -> "Next: dark end"
    WBKind.offset -> "Next: check"
    WBKind.fine -> if (nextGrey(guided, pid) != null) "Next grey" else "Back to the check"
}

private fun nextGrey(guided: GuidedController, after: String): String? {
    val f = guided.plan.fine
    val i = f.indexOf(after)
    if (i < 0 || i + 1 >= f.size) return null
    return f[i + 1]
}

/**
 * Moves on. The step is marked done only when it was on target and steady (or at the TV's limit).
 * Fine-tune works out the grey at call time.
 */
private fun wbNext(guided: GuidedController, kind: WBKind, done: Boolean) {
    when (kind) {
        WBKind.gain -> guided.go(GuideStep.DARK, markDone = if (done) GuideStep.BRIGHT else null)
        WBKind.offset -> guided.go(GuideStep.CHECK, markDone = if (done) GuideStep.DARK else null)
        WBKind.fine -> {
            val cur = guided.progress.finePid ?: guided.plan.fine[0]
            val n = nextGrey(guided, cur)
            if (n != null) {
                guided.setFinePid(n)
                guided.showLive(n)
            } else {
                guided.go(GuideStep.CHECK)
            }
        }
    }
}

/** The step's tips (kept from the original, where the bars now say what to do). */
@Suppress("unused")
private fun wbTips(guided: GuidedController, kind: WBKind, pid: String, r: LiveReading, stillSince: Long?): List<Pair<String, TipKind>> {
    if (r.steady) return listOf("Both channels are on target. Keep these TV settings." to TipKind.done)
    val out = mutableListOf(wbTip(guided, kind, pid, "Red", r.qR ?: 0.0), wbTip(guided, kind, pid, "Blue", r.qB ?: 0.0))
    if (kind == WBKind.fine && !r.ok && stillSince != null && System.currentTimeMillis() - stillSince > 60_000) {
        out.add(STILL_NOTICE to TipKind.limit)
    }
    return out
}

private fun wbTip(guided: GuidedController, kind: WBKind, pid: String, name: String, e: Double): Pair<String, TipKind> {
    if (abs(e) <= 0.012) return "$name is on target" to TipKind.ok
    val clicks = if (e.isFinite()) (abs(e) / 0.008).roundToInt().coerceIn(1, 10) else 1
    val brand = guided.tv.brand
    val suggestion = if (kind == WBKind.fine) guided.suggestions[pid] else null
    val control = when (kind) {
        WBKind.gain -> "$name ${brand.gainName}"
        WBKind.offset -> "$name ${brand.offsetName}"
        WBKind.fine -> suggestion?.let { "$name on the $it% control" } ?: "$name on the multipoint control"
    }
    return ("$name is ${fmt(abs(e) * 100, 0)}% ${if (e > 0) "too strong" else "too weak"}: " +
        "${if (e > 0) "lower" else "raise"} $control about $clicks click${if (clicks > 1) "s" else ""}, then watch the bar.") to TipKind.todo
}

/**
 * The white-point target, as big as the screen allows: where the grey is now (the dot) against D65 (the
 * centre). The main thing to watch while adjusting.
 */
@Composable
private fun BigWhiteTarget(reading: LiveReading) {
    // Beside its message, so the Red and Blue bars below still fit on screen.
    Row(
        Modifier
            .padding(vertical = 4.dp)
            .clearAndSetSemantics {
                contentDescription = "White point target"
                stateDescription = if (reading.steady) "On target" else "dE ${fmt(reading.de, 1)} from D65"
            },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WhiteTarget(uv = reading.uv, d65 = reading.d65uv, ok = reading.ok, modifier = Modifier.size(150.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (reading.steady) "On target" else if (reading.ok) "In the ring. Hold still…" else "Bring the dot into the ring",
                style = Type.bodyStrong, color = if (reading.ok) Theme.green else Color.White,
            )
            Text("dE ${fmt(reading.de, 1)} from D65", style = Type.small.copy(fontFeatureSettings = "tnum"),
                color = Theme.textSecondary)
        }
    }
}

// MARK: - 6. Colours, one at a time

/** One of the six colours: yellow ring when it's the one on screen, a check when done, a minus at the TV's limit. */
@Composable
private fun ColourChip(id: String, selected: Boolean, done: Boolean, limit: Boolean, modifier: Modifier = Modifier, action: () -> Unit) {
    val c = Theme.swatch(id)
    Column(
        modifier
            .clickable(role = Role.Button, onClick = action)
            .semantics(mergeDescendants = true) {
                contentDescription = capitalized(id) + (if (limit) ", as close as this TV gets" else if (done) ", done" else "")
                this.selected = selected
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            Modifier
                .size(32.dp)
                .background(if (selected) c.copy(alpha = 0.15f) else Theme.card, CircleShape)
                .border(1.5.dp, if (selected) Theme.accent else c, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(symbol(if (limit) "minus" else "checkmark"), contentDescription = null, tint = c, modifier = Modifier.size(13.dp))
            } else {
                Box(Modifier.size(5.dp).background(c, CircleShape))
            }
        }
        Text(capitalized(id), style = Type.micro, color = if (selected) Color.White else Theme.textSecondary,
            maxLines = 1, softWrap = false)
    }
}

@Composable
fun ColoursStep(guided: GuidedController) {
    if (!guided.tv.colourMenuKnown && !guided.tv.isDemo) {
        MenuQuestionCard(tv = guided.tv, colours = true)
    } else {
        ColoursContent(guided)
    }
}

private fun currentColour(guided: GuidedController): String =
    guided.progress.colour ?: Patterns.colourIDs.firstOrNull { it !in guided.progress.colours } ?: "RED"

@Composable
private fun ColoursContent(guided: GuidedController) {
    /** "Next colour" was tapped before this colour got on target: ask whether it's as close as it gets. */
    var unfinished by remember { mutableStateOf<String?>(null) }
    val cms = guided.tv.colourMenu
    val cur = currentColour(guided)
    val name = capitalized(cur)

    fun show() {
        if (guided.progress.step == GuideStep.COLOURS) guided.showLive(currentColour(guided))
    }

    /** Every other colour is already done. */
    fun isLastColour(c: String) = Patterns.colourIDs.all { it == c || it in guided.progress.colours }

    LaunchedEffect(Unit) { show() }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (guided.tv.isDemo) DemoNote()
        Text("Bring $name onto its ring", style = Type.stepTitleStyle)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            for (c in Patterns.colourIDs) {
                ColourChip(
                    id = c, selected = c == cur,
                    done = c in guided.progress.colours,
                    limit = c in guided.progress.colours && c in guided.progress.limits,
                    modifier = Modifier.weight(1f),
                ) {
                    guided.setColour(c)
                    guided.showLive(c)
                }
            }
        }
        val before = guided.lastSession?.before
        if (guided.latestBefore == null) {
            TipRow(tone = TipTone.limit, text = "Run the Before check first, so brightness can be judged against this TV's own white.")
        } else if (!guided.tv.isDemo && before != null) {
            GamutCheck.note(Analysis.analyse(before, guided.hdr), guided.mode, guided.tv.brand)?.let {
                TipRow(tone = TipTone.limit, text = it)
            }
        }
        if (!guided.tv.isDemo) guided.file(cur)?.let { f -> PlayFileCard(file = f, mode = guided.mode) }
        ColoursLive(guided, cur)
        if (guided.tv.isDemo && cms != ColourMenu.OFF) {
            val n = guided.tv.brand.cmsNames
            DemoRemote(
                guided = guided,
                controls = if (cms == ColourMenu.RGB_SLIDERS)
                    listOf("$name Red" to "cms:$cur:R", "$name Green" to "cms:$cur:G", "$name Blue" to "cms:$cur:B")
                else listOf("$name ${n.hue}" to "hsl:$cur:hue", "$name ${n.saturation}" to "hsl:$cur:sat",
                    "$name ${n.brightness}" to "hsl:$cur:lum"),
            )
        }
        LinkButton("Can't get closer", {
            val c = currentColour(guided)
            guided.markLimit(c)
            guided.finishColour(c, done = true)
            show()
        })
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (cms == ColourMenu.OFF) {
                Text(
                    "No colour controls are set for this TV: just check the colours, or choose its colour menu in Screen settings.",
                    style = Type.small, color = Theme.textSecondary,
                )
            }
            Text("TV menu: ${guided.tv.brand.colourMenuPath} › $name", style = Type.small, color = Theme.textSecondary)
        }
        StepMainAction(if (isLastColour(cur)) "Finish colours" else "Next colour") {
            // Worked out at tap time.
            val c = currentColour(guided)
            val r = guided.live.reading
            val onTarget = (r?.id == c && r.steady) || cms == ColourMenu.OFF
            if (onTarget) {
                guided.finishColour(c, done = true)
                show()
            } else {
                unfinished = c
            }
        }
    }

    val u = unfinished
    if (u != null) {
        AlertDialog(
            onDismissRequest = { unfinished = null },
            containerColor = Theme.surface,
            title = { Text("${capitalized(u)} isn't on target yet", style = Type.headlineBrand, color = Color.White) },
            text = {
                Text(
                    "If the sliders have stopped bringing the dot closer to its ring, the TV has reached its limit for this colour. That's common, and the app will note it in your results.",
                    style = Type.captionBrand, color = Theme.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    guided.markLimit(u)
                    guided.finishColour(u, done = true)
                    show()
                    unfinished = null
                }) { Text("Move on: it's as close as this TV gets", style = Type.bodyStrong, color = Theme.tealBright) }
            },
            dismissButton = {
                TextButton(onClick = { unfinished = null }) {
                    Text("Keep adjusting", style = Type.bodyBrand, color = Theme.tealBright)
                }
            },
        )
    }
}

@Composable
private fun ColoursLive(guided: GuidedController, cur: String) {
    guided.live.error?.let { TipRow(tone = TipTone.limit, text = it) }
    val r = guided.live.reading
    if (r != null && r.id == cur) {
        val m = r.mismatch
        if (m != null) {
            TipRow(tone = TipTone.limit, text = "$m Is the right file playing, with the camera pointed at the centre?")
        } else {
            val t = r.tuv
            if (t != null) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    GamutChart(dots = listOf(GamutDot(id = cur, uv = r.uv, target = t, de = r.de)), hdr = guided.hdr,
                        modifier = Modifier.heightIn(max = 220.dp))
                    Text("Ring: target · dot: now · dE ${fmt(r.de, 1)}", style = Type.small.copy(fontFeatureSettings = "tnum"),
                        color = Theme.textSecondary)
                }
            }
            ColourControlRows(guided, r, cur)
            AverageLine(reading = r) { guided.live.restartAverage() }
            TipList(tips = colourTips(guided, r, cur))
        }
    } else {
        WaitingForReading()
    }
}

/** The success chime's name for one control of one colour: per TV, format, colour and control. */
private fun colourChimeKey(guided: GuidedController, colour: String, control: String) =
    "${guided.tv.id}|${guided.mode.raw}|colour|$colour|$control"

@Composable
private fun ColourControlRows(guided: GuidedController, r: LiveReading, cur: String) {
    val colour = Theme.swatch(cur)
    if (guided.tv.colourMenu == ColourMenu.RGB_SLIDERS) {
        val h = r.rgbHint
        val stuck = r.outOfReach?.channels ?: emptyList()
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            listOf("Red", "Green", "Blue").forEachIndexed { j, label ->
                // The hint says how far to move the slider (+ raise); drawn as the error (+ too much).
                val v: Double? = if (h == null || listOf("R", "G", "B")[j] in stuck) null else -h[j] * 100
                ColourControlRow(
                    label = label, value = v, colour = colour, fullScale = 20.0, tolerance = 3.0,
                    caption = v?.let { if (it > 0) "Too much: lower it" else "Too little: raise it" },
                    chime = colourChimeKey(guided, cur, label),
                )
            }
            BalanceScale()
        }
        if (r.rgbApprox) {
            Text("Estimated: the Before check didn't get all three primaries.", style = Type.small, color = Theme.textSecondary)
        }
    } else {
        val n = guided.tv.brand.cmsNames
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ColourControlRow(
                label = n.hue, value = r.hue, colour = colour, fullScale = 10.0, tolerance = 2.0,
                caption = "Leans toward ${r.toward?.let { capitalized(it) } ?: "a neighbour"}: move ${n.hue} a click and keep going the way that shrinks the bar",
                chime = colourChimeKey(guided, cur, "hue"),
            )
            ColourControlRow(
                label = n.saturation, value = r.sat, colour = colour, fullScale = 15.0, tolerance = 3.0,
                caption = if ((r.sat ?: 0.0) > 0) "Too strong: lower ${n.saturation}" else "Too weak: raise ${n.saturation}",
                chime = colourChimeKey(guided, cur, "sat"),
            )
            ColourControlRow(
                label = n.brightness, value = r.lum, colour = colour, fullScale = 25.0, tolerance = 5.0,
                caption = if ((r.lum ?: 0.0) > 0) "Too bright: lower ${n.brightness}" else "Too dim: raise ${n.brightness}",
                chime = colourChimeKey(guided, cur, "lum"),
            )
            BalanceScale()
        }
    }
}

private fun colourTips(guided: GuidedController, d: LiveReading, cur: String): List<Pair<String, TipKind>> {
    val name = capitalized(cur)
    if (d.steady) return listOf("$name is on target. Nice." to TipKind.done)
    val t = mutableListOf<Pair<String, TipKind>>()
    when (guided.tv.colourMenu) {
        ColourMenu.OFF -> return listOf("Nothing to adjust on this TV; just note the numbers." to TipKind.ok)
        ColourMenu.HSL -> {}                                   // each bar says what to do
        ColourMenu.RGB_SLIDERS -> {
            if (d.rgbHint == null) {
                return listOf("Run the Before check first: the Red/Green/Blue hints need this TV's white as a reference." to TipKind.todo)
            }
            val names = listOf("Red", "Green", "Blue")
            val stuck = (d.outOfReach?.channels ?: emptyList()).mapNotNull { c -> listOf("R", "G", "B").indexOf(c).takeIf { it >= 0 } }
            val o = d.outOfReach
            if (o != null) {
                val others = listOf(0, 1, 2).filter { it !in stuck }.joinToString(" and ") { names[it].lowercase() }
                t.add(
                    ("**Out of reach:** to match exactly, $name would need ${stuck.joinToString(" and ") { names[it] }} below zero. " +
                        "$name sits beyond what this TV's $others can mix, so that slider stops helping once it reaches zero. " +
                        "The best this colour can get is about **dE ${fmt(o.bestDE)}**. When you're there, use **Can't get closer**.") to TipKind.limit,
                )
            }
        }
    }
    if (t.isEmpty() && d.ok) t.add("Close enough - holding steady…" to TipKind.ok)
    return t
}

// MARK: - Fine-tune (multipoint) and the point finder

@Composable
fun FineStep(guided: GuidedController) {
    /**
     * Keeps the point finder on screen through its second measurement and its result page (where the
     * user is told to put the controls back), even though the finder's map is ready by then.
     */
    var holdingFinder by remember { mutableStateOf(false) }

    if (!guided.tv.multipointKnown && !guided.tv.isDemo) {
        MenuQuestionCard(tv = guided.tv, colours = false)
    } else if (guided.tv.multipoint == Multipoint.NONE) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Fine-tune", style = Type.stepTitleStyle)
            Text(
                "This TV has only 2-point white balance (Screen settings), so there's nothing to fine-tune beyond the bright and dark ends.",
                style = Type.bodyBrand, color = Theme.textSecondary,
            )
            StepMainAction("Back to the check") { guided.go(GuideStep.CHECK) }
        }
    } else if (holdingFinder || (!guided.finderReady && !guided.finderSkipped)) {
        PointFinderView(guided = guided, holding = holdingFinder, onHoldingChange = { holdingFinder = it })
    } else {
        FineTuneView(guided = guided)
    }
}

@Composable
fun FineTuneView(guided: GuidedController) {
    val cur = guided.progress.finePid ?: guided.plan.fine[0]
    val sugg = guided.suggestions
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        key(cur) { WhiteBalanceBody(guided, WBKind.fine, cur) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Eyebrow(text = "Greys")
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 1.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (pid in guided.plan.fine) {
                    val selected = pid == cur
                    val shape = RoundedCornerShape(percent = 50)
                    Text(
                        shortName(Patterns.patch(guided.hdr, pid)?.name ?: pid) +
                            (sugg[pid]?.let { " → TV $it%" } ?: "") +
                            (if (guided.state.finder.overrides[pid] != null) " ✓" else ""),
                        style = Type.captionBrand,
                        color = if (selected) Color.White else Theme.textSecondary,
                        modifier = Modifier
                            .background(if (selected) Theme.surface else Theme.card, shape)
                            .border(if (selected) 1.5.dp else 1.dp, if (selected) Theme.accent else Theme.hairline, shape)
                            .clickable(role = Role.Button) {
                                guided.setFinePid(pid)
                                guided.showLive(pid)
                            }
                            .semantics { this.selected = selected }
                            .padding(vertical = 8.dp, horizontal = 12.dp),
                    )
                }
            }
        }
        Card(spacing = 8.dp) {
            if (!guided.finderSkipped) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("The control that moves this grey", style = Type.bodyStrong, color = Color.White,
                        modifier = Modifier.weight(1f))
                    ControlPicker(guided, cur, sugg[cur])
                }
                Text(
                    "If you pick one, it's remembered for this TV, and the suggestions for nearby greys adjust to match.",
                    style = Type.small, color = Theme.textSecondary,
                )
                LinkButton("Run the point finder again", { guided.resetFinder() }, style = Type.captionStrong)
            } else {
                Text("If the bars don't move, try the control one step lower or higher.", style = Type.small,
                    color = Theme.textSecondary)
            }
        }
        StepMainAction(wbNextTitle(guided, WBKind.fine, cur)) {
            wbNext(guided, WBKind.fine, guided.live.reading?.steady ?: false)
        }
    }
}

/** The menu picker "The control that moves this grey": measured, or one of the TV's controls. */
@Composable
private fun ControlPicker(guided: GuidedController, cur: String, suggestion: Int?) {
    var open by remember { mutableStateOf(false) }
    val selection = guided.state.finder.overrides[cur] ?: 0
    val grid = guided.grid
    val options = listOf(0) + (if (grid > 0) (grid..100 step grid).toList() else emptyList())
    fun label(v: Int) = if (v == 0) suggestion?.let { "TV $it% (measured)" } ?: "Measured" else "TV $v%"
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.semantics {
            contentDescription = "The control that moves this grey"
            stateDescription = label(selection)
        }) {
            Text(label(selection), style = Type.bodyBrand, color = Theme.tealBright)
            Icon(symbol("chevron.down"), contentDescription = null, tint = Theme.tealBright, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Theme.surface) {
            for (v in options) {
                DropdownMenuItem(
                    text = { Text(label(v), style = Type.bodyBrand, color = Color.White) },
                    trailingIcon = if (v == selection) {
                        { Icon(symbol("checkmark"), contentDescription = null, tint = Theme.tealBright) }
                    } else null,
                    onClick = {
                        open = false
                        guided.setOverride(cur, if (v == 0) null else v)
                    },
                )
            }
        }
    }
}

/** One line of the point finder's progress: done (check), happening now (spinner) or still to come (clock). */
private enum class FinderRowStatus { done, active, waiting }

@Composable
private fun FinderStatusRow(text: String, state: FinderRowStatus) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = Type.bodyBrand, color = if (state == FinderRowStatus.waiting) Theme.textSecondary else Color.White,
            modifier = Modifier.weight(1f))
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when (state) {
                FinderRowStatus.done -> Icon(symbol("checkmark"), contentDescription = "Done", tint = Theme.tealBright,
                    modifier = Modifier.size(17.dp))
                FinderRowStatus.active -> CircularProgressIndicator(Modifier.size(16.dp), color = Theme.tealBright, strokeWidth = 2.dp)
                FinderRowStatus.waiting -> Icon(symbol("clock"), contentDescription = "Waiting", tint = Theme.tealBright,
                    modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun PointFinderView(guided: GuidedController, holding: Boolean, onHoldingChange: (Boolean) -> Unit) {
    var showingResult by remember { mutableStateOf(false) }
    val haveBase = guided.state.finder.base != null
    val running = guided.runner.running

    /** 1: measure as it is, 2: nudge four controls, 3: measure again. */
    val step = if (!haveBase) 1 else if (running) 3 else 2

    /** The main action for the state on screen; none while measuring (Cancel is in the page). */
    val pinnedTitle: String? = when {
        showingResult -> "Fine-tune with these points"
        running -> null
        haveBase -> "I've made the four changes - measure"
        else -> "Measure (about 2½ minutes)"
    }

    fun pinnedTap() {
        if (showingResult) {
            showingResult = false
            onHoldingChange(false)
            guided.go(GuideStep.FINE)
        } else if (guided.runner.running) {
            return
        } else if (guided.state.finder.base != null) {
            onHoldingChange(true)
            liveStepsScope.launch {
                guided.measureFinder(nudged = true)
                if (guided.finderMap.size >= 2) showingResult = true else onHoldingChange(false)
            }
        } else {
            liveStepsScope.launch { guided.measureFinder(nudged = false) }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (showingResult) {
            FinderResult(guided)
        } else {
            FinderFinding(guided, step, haveBase)
        }
        StepMainAction(pinnedTitle) { pinnedTap() }
    }
}

@Composable
private fun FinderResult(guided: GuidedController) {
    Text("Here's what each control moves", style = Type.stepTitleStyle)
    Text("TV control → moves the grey at", style = Type.bodyBrand, color = Theme.textSecondary)
    Card(spacing = 4.dp) {
        for (m in guided.finderMap) {
            val shift = m.level - m.point.toDouble()
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).semantics(mergeDescendants = true) { },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${if (m.channel == "R") "Red" else if (m.channel == "B") "Blue" else "TV"} ${m.point}",
                    style = Type.brand(16f), color = Color.White, modifier = Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("→ ${fmt(m.level, 0)}%", style = Type.brand(17f), color = Theme.tealBright)
                    Text("Shift ${signed(shift, 0)}", style = Type.tiny,
                        color = if (abs(shift) >= 5) Theme.limit else Theme.green)
                }
            }
        }
    }
    TipRow(tone = TipTone.todo, text = "**Now put them back.** Set those four controls back to where they were (−10 each) before fine-tuning.")
}

@Composable
private fun FinderFinding(guided: GuidedController, step: Int, haveBase: Boolean) {
    val running = guided.runner.running
    Text(if (running) "Finding your TV's points" else "Find your TV's controls", style = Type.stepTitleStyle)
    Card(hero = true) {
        Eyebrow(text = "Optional · about 6 minutes")
        Text("Step $step of 3", style = Type.cardTitle.copy(letterSpacing = (-0.6).sp), color = Color.White)
        when (step) {
            1 -> Text(
                markdown("Measure the greys as they are. Tap Measure, then play **${guided.finderFile}** on the TV."),
                style = Type.bodyBrand, color = Color.White,
            )
            2 -> {
                Text("Nudge four controls in the multipoint menu, exactly as listed:", style = Type.bodyBrand, color = Color.White)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((channel, point) in PointFinder.nudges) {
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            Text("TV $point% control", style = Type.bodyBrand, color = Color.White, modifier = Modifier.width(150.dp))
                            Text(if (channel == "R") "Red" else "Blue", style = Type.bodyBrand, color = Theme.channel(channel),
                                modifier = Modifier.width(48.dp))
                            Text("+${PointFinder.NUDGE}", style = Type.bodyBrand, color = Color.White)
                        }
                    }
                }
                Text(
                    "Then measure again with the same video. The app compares the two and finds each control.",
                    style = Type.captionBrand, color = Theme.textSecondary,
                )
            }
            else -> Text(
                "Measure again with the same video. The app compares the two and finds each control.",
                style = Type.bodyBrand, color = Color.White,
            )
        }
        Row(Modifier.fillMaxWidth().clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            for (i in 1..3) {
                Box(Modifier.weight(1f).height(6.dp)
                    .background(if (i <= step) Theme.tealBright else Theme.hairline, RoundedCornerShape(percent = 50)))
            }
        }
    }
    Text(
        "A TV's “40%” control often moves a different grey. This finds where each one really acts.",
        style = Type.captionBrand, color = Theme.textSecondary,
    )
    (guided.runner.phase as? SweepRunner.Phase.Failed)?.let { TipRow(tone = TipTone.limit, text = it.message) }
    if (running) {
        MeasuringCard(runner = guided.runner, file = guided.finderFile, mode = guided.mode)
    }
    Card(spacing = 0.dp) {
        FinderStatusRow("Measure the greys as they are",
            if (haveBase) FinderRowStatus.done else if (running) FinderRowStatus.active else FinderRowStatus.waiting)
        FinderStatusRow("Nudge four controls (+${PointFinder.NUDGE} each)",
            if (step == 3) FinderRowStatus.done else if (step == 2) FinderRowStatus.active else FinderRowStatus.waiting)
        FinderStatusRow("Measure again and find each control",
            if (step == 3) FinderRowStatus.active else FinderRowStatus.waiting)
    }
    if (running) {
        SecondaryButton("Cancel", { guided.runner.cancel() })
    } else {
        LinkButton("Skip: fine-tune without it", { guided.skipFinder() })
    }
}

// MARK: - Demo remote

/** Demo TVs only: − / + buttons that stand in for the TV's menus. */
@Composable
fun DemoRemote(guided: GuidedController, controls: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((label, key) in controls) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = Type.bodyBrand, color = Color.White, modifier = Modifier.weight(1f))
                DemoKey("−", "$label down") { guided.demoPress(mapOf(key to -1)) }
                DemoKey("+", "$label up") { guided.demoPress(mapOf(key to 1)) }
            }
        }
    }
}

@Composable
private fun DemoKey(symbol: String, accessibility: String, action: () -> Unit) {
    val shape = RoundedCornerShape(percent = 50)
    Box(
        Modifier
            .size(width = 48.dp, height = 44.dp)
            .background(Theme.surface, shape)
            .border(1.dp, Theme.tealBright, shape)
            .clickable(role = Role.Button, onClick = action)
            .clearAndSetSemantics { contentDescription = accessibility },
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, style = Type.brand(24f), color = Theme.tealBright)
    }
}
