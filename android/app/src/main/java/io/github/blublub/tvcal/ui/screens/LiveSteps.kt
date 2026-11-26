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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.blublub.tvcal.R
import io.github.blublub.tvcal.engine.Analysis
import io.github.blublub.tvcal.engine.LiveReading
import io.github.blublub.tvcal.engine.Patterns
import io.github.blublub.tvcal.engine.PointFinder
import io.github.blublub.tvcal.engine.signedFixed
import io.github.blublub.tvcal.measure.GuidedController
import io.github.blublub.tvcal.measure.SweepRunner
import io.github.blublub.tvcal.measure.uiScope
import io.github.blublub.tvcal.storage.Brand
import io.github.blublub.tvcal.storage.ColourMenu
import io.github.blublub.tvcal.storage.GamutCheck
import io.github.blublub.tvcal.storage.GuideStep
import io.github.blublub.tvcal.storage.Multipoint
import io.github.blublub.tvcal.ui.L10n
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

/** "RED" -> "Red" / "Rot" (Swift's `.capitalized`, in the app's language). */
private fun capitalized(s: String): String = L10n.colourName(s)

/** A percentage in the language's style: "50%" / "50 %". */
private fun pct(v: Any): String = L10n.tr("$v%", "$v %")

/** A short patch name ("50%", "50% (92 nits)") in the app's language. */
private fun shortLabel(name: String): String =
    if (L10n.german) L10n.patchNameDe(shortName(name)) else shortName(name)

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
                stringResource(R.string.live_jitter, fmt(reading.jitter * 100)),
                style = Type.small, color = Theme.textSecondary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                pluralStringResource(R.plurals.live_average_of, reading.averaged, reading.averaged.toString()) +
                    (if (reading.averaged < reading.need) " " + stringResource(R.string.live_average_needs, reading.need.toString()) else "") +
                    " · " + stringResource(R.string.live_average_latest, fmt(reading.latestY, if (reading.latestY < 10) 2 else 1)),
                style = Type.small.copy(fontFeatureSettings = "tnum"), color = Theme.textSecondary,
                modifier = Modifier.weight(1f),
            )
            val hint = stringResource(R.string.live_restart_hint)
            LinkButton(
                stringResource(R.string.live_restart), restart, style = Type.captionStrong,
                modifier = Modifier.semantics { stateDescription = hint },
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
    return L10n.signedNum(v, digits).replace("-", "−") + suffix
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
    val onTarget = stringResource(R.string.live_on_target)
    val percentWord = " " + stringResource(R.string.live_percent_word)
    Column(
        Modifier.clearAndSetSemantics {
            contentDescription = label
            stateDescription = if (ok) onTarget else bigSigned(value?.let { it * 100 }, percentWord)
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
        Text(stringResource(R.string.live_too_little), style = Type.tiny, color = Theme.textSecondary, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.live_on_target), style = Type.tiny, color = Theme.green)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Text(stringResource(R.string.live_too_much), style = Type.tiny, color = Theme.textSecondary)
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
    val onTarget = stringResource(R.string.live_on_target)
    Column(
        Modifier.clearAndSetSemantics {
            contentDescription = label
            stateDescription = if (ok) onTarget else (caption ?: bigSigned(value))
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
        Text(stringResource(R.string.live_demo_title), style = Type.captionStrong, color = Theme.accent)
        Text(stringResource(R.string.live_demo_hint), style = Type.small, color = Theme.textSecondary)
    }
}

/** Shown until the first reading arrives. */
@Composable
private fun WaitingForReading() {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), color = Theme.tealBright, strokeWidth = 2.dp)
        Text(
            stringResource(R.string.live_waiting),
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
        StepMainAction(wbNextTitle(LocalContext.current, guided, kind, pid)) { wbNext(guided, kind, guided.live.reading?.steady ?: false) }
    }
}

/** The step without its main action (fine-tune puts that below the grey chips). */
@Composable
private fun WhiteBalanceBody(guided: GuidedController, kind: WBKind, pid: String) {
    val context = LocalContext.current
    val brand = guided.tv.brand
    val suggestion: Int? = if (kind == WBKind.fine) guided.suggestions[pid] else null
    var still by remember(pid) { mutableStateOf<WBStill?>(null) }

    /** "10% grey", "50% grey (92 nits)", "100 nits". */
    val greyName = shortLabel(Patterns.patch(guided.hdr, pid)?.name ?: pid).replaceFirst("%", L10n.tr("% grey", "% Grau"))

    /** What to adjust: "Adjust Red and Blue Gain". */
    val instructions = when (kind) {
        WBKind.gain -> stringResource(R.string.live_adjust_rb, brand.gainName)
        WBKind.offset -> stringResource(R.string.live_adjust_rb, brand.offsetName)
        WBKind.fine ->
            if (suggestion != null) stringResource(R.string.live_adjust_fine_control, suggestion.toString())
            else stringResource(R.string.live_adjust_fine_nearest)
    }

    // The menu without LG's "(set it to 2 Point)" hint, which doesn't apply to fine-tuning.
    val whereText = if (kind == WBKind.fine)
        brand.whiteBalanceMenu + " › " + stringResource(R.string.live_ten_or_twenty_point)
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
        return context.getString(R.string.live_still_notice)
    }

    val demoControls: List<Pair<String, String>> = when (kind) {
        WBKind.gain -> listOf("${capitalized("RED")} ${brand.gainName}" to "gain:R", "${capitalized("BLUE")} ${brand.gainName}" to "gain:B")
        WBKind.offset -> listOf("${capitalized("RED")} ${brand.offsetName}" to "offset:R", "${capitalized("BLUE")} ${brand.offsetName}" to "offset:B")
        WBKind.fine -> {
            val p = suggestion ?: (Patterns.patch(guided.hdr, pid)?.percent ?: 50.0).roundToInt()
            listOf("${capitalized("RED")} ${pct(p)}" to "p20:R:$p", "${capitalized("BLUE")} ${pct(p)}" to "p20:B:$p")
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // The instruction, then the white-point target, big: the dot has to land in the centre ring. Then the
        // video to play (the grey's details when fine-tuning).
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (kind == WBKind.fine) Eyebrow(text = stringResource(R.string.live_fine_eyebrow, greyName))
            Text(instructions, style = Type.stepTitleStyle)
            Text(stringResource(R.string.live_until_line), style = Type.captionBrand, color = Theme.textSecondary)
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
                TipRow(tone = TipTone.limit, text = stringResource(R.string.live_mismatch, m))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    ChannelBalanceRow(label = capitalized("RED"), value = reading.qR, colour = Theme.red, chime = chimeKey("R"))
                    ChannelBalanceRow(label = capitalized("BLUE"), value = reading.qB, colour = Theme.blue, chime = chimeKey("B"))
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
            LinkButton(stringResource(R.string.live_cant_get_closer), {
                guided.markLimit(if (kind == WBKind.gain) "bright" else if (kind == WBKind.offset) "dark" else "fine:$pid")
                wbNext(guided, kind, done = true)
            })
            if (kind == WBKind.fine && nextGrey(guided, pid) != null) {
                LinkButton(stringResource(R.string.live_back_to_check), { guided.go(GuideStep.CHECK) })
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.live_tv_menu, whereText), style = Type.small, color = Theme.textSecondary)
            if (valid != null) AverageLine(reading = valid) { guided.live.restartAverage() }
        }
    }
}

private fun wbNextTitle(context: Context, guided: GuidedController, kind: WBKind, pid: String): String = when (kind) {
    WBKind.gain -> context.getString(R.string.live_next_dark)
    WBKind.offset -> context.getString(R.string.live_next_check)
    WBKind.fine -> context.getString(if (nextGrey(guided, pid) != null) R.string.live_next_grey else R.string.live_back_to_check)
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
private fun wbTips(context: Context, guided: GuidedController, kind: WBKind, pid: String, r: LiveReading, stillSince: Long?): List<Pair<String, TipKind>> {
    if (r.steady) return listOf(L10n.tr("Both channels are on target. Keep these TV settings.",
        "Beide Kanäle sind im Ziel. Behalte diese TV-Einstellungen.") to TipKind.done)
    val out = mutableListOf(wbTip(guided, kind, pid, capitalized("RED"), r.qR ?: 0.0), wbTip(guided, kind, pid, capitalized("BLUE"), r.qB ?: 0.0))
    if (kind == WBKind.fine && !r.ok && stillSince != null && System.currentTimeMillis() - stillSince > 60_000) {
        out.add(context.getString(R.string.live_still_notice) to TipKind.limit)
    }
    return out
}

private fun wbTip(guided: GuidedController, kind: WBKind, pid: String, name: String, e: Double): Pair<String, TipKind> {
    if (abs(e) <= 0.012) return L10n.tr("$name is on target", "$name ist im Ziel") to TipKind.ok
    val clicks = if (e.isFinite()) (abs(e) / 0.008).roundToInt().coerceIn(1, 10) else 1
    val brand = guided.tv.brand
    val suggestion = if (kind == WBKind.fine) guided.suggestions[pid] else null
    val control = when (kind) {
        WBKind.gain -> "$name ${brand.gainName}"
        WBKind.offset -> "$name ${brand.offsetName}"
        WBKind.fine -> suggestion?.let { L10n.tr("$name on the $it% control", "$name am $it-%-Regler") }
            ?: L10n.tr("$name on the multipoint control", "$name am Mehrpunkt-Regler")
    }
    if (L10n.german) {
        return ("$name ist ${fmt(abs(e) * 100, 0)} % ${if (e > 0) "zu stark" else "zu schwach"}: " +
            "$control um etwa $clicks ${if (clicks > 1) "Klicks" else "Klick"} ${if (e > 0) "senken" else "anheben"}, " +
            "dann den Balken beobachten.") to TipKind.todo
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
    val targetText = stringResource(R.string.live_white_target)
    val onTarget = stringResource(R.string.live_on_target)
    val fromD65 = stringResource(R.string.live_de_from_d65, fmt(reading.de, 1))
    Row(
        Modifier
            .padding(vertical = 4.dp)
            .clearAndSetSemantics {
                contentDescription = targetText
                stateDescription = if (reading.steady) onTarget else fromD65
            },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WhiteTarget(uv = reading.uv, d65 = reading.d65uv, ok = reading.ok, modifier = Modifier.size(150.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (reading.steady) onTarget else if (reading.ok) stringResource(R.string.live_in_ring) else stringResource(R.string.live_bring_dot),
                style = Type.bodyStrong, color = if (reading.ok) Theme.green else Color.White,
            )
            Text(fromD65, style = Type.small.copy(fontFeatureSettings = "tnum"),
                color = Theme.textSecondary)
        }
    }
}

// MARK: - 6. Colours, one at a time

/** One of the six colours: yellow ring when it's the one on screen, a check when done, a minus at the TV's limit. */
@Composable
private fun ColourChip(id: String, selected: Boolean, done: Boolean, limit: Boolean, modifier: Modifier = Modifier, action: () -> Unit) {
    val c = Theme.swatch(id)
    val description = if (limit) stringResource(R.string.live_chip_limit, capitalized(id))
        else if (done) stringResource(R.string.live_chip_done, capitalized(id)) else capitalized(id)
    Column(
        modifier
            .clickable(role = Role.Button, onClick = action)
            .semantics(mergeDescendants = true) {
                contentDescription = description
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
        Text(stringResource(R.string.live_bring_colour, name), style = Type.stepTitleStyle)
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
            TipRow(tone = TipTone.limit, text = stringResource(R.string.live_run_before_first))
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
                    listOf("$name ${capitalized("RED")}" to "cms:$cur:R", "$name ${capitalized("GREEN")}" to "cms:$cur:G", "$name ${capitalized("BLUE")}" to "cms:$cur:B")
                else listOf("$name ${n.hue}" to "hsl:$cur:hue", "$name ${n.saturation}" to "hsl:$cur:sat",
                    "$name ${n.brightness}" to "hsl:$cur:lum"),
            )
        }
        LinkButton(stringResource(R.string.live_cant_get_closer), {
            val c = currentColour(guided)
            guided.markLimit(c)
            guided.finishColour(c, done = true)
            show()
        })
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (cms == ColourMenu.OFF) {
                Text(
                    stringResource(R.string.live_no_colour_controls),
                    style = Type.small, color = Theme.textSecondary,
                )
            }
            Text(stringResource(R.string.live_tv_menu_colour, guided.tv.brand.colourMenuPath, name), style = Type.small, color = Theme.textSecondary)
        }
        StepMainAction(stringResource(if (isLastColour(cur)) R.string.live_finish_colours else R.string.live_next_colour)) {
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
            title = { Text(stringResource(R.string.live_not_on_target_title, capitalized(u)), style = Type.headlineBrand, color = Color.White) },
            text = {
                Text(
                    stringResource(R.string.live_not_on_target_body),
                    style = Type.captionBrand, color = Theme.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    guided.markLimit(u)
                    guided.finishColour(u, done = true)
                    show()
                    unfinished = null
                }) { Text(stringResource(R.string.live_move_on), style = Type.bodyStrong, color = Theme.tealBright) }
            },
            dismissButton = {
                TextButton(onClick = { unfinished = null }) {
                    Text(stringResource(R.string.live_keep_adjusting), style = Type.bodyBrand, color = Theme.tealBright)
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
            TipRow(tone = TipTone.limit, text = stringResource(R.string.live_mismatch, m))
        } else {
            val t = r.tuv
            if (t != null) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    GamutChart(dots = listOf(GamutDot(id = cur, uv = r.uv, target = t, de = r.de)), hdr = guided.hdr,
                        modifier = Modifier.heightIn(max = 220.dp))
                    Text(stringResource(R.string.live_ring_legend, fmt(r.de, 1)), style = Type.small.copy(fontFeatureSettings = "tnum"),
                        color = Theme.textSecondary)
                }
            }
            ColourControlRows(guided, r, cur)
            AverageLine(reading = r) { guided.live.restartAverage() }
            TipList(tips = colourTips(LocalContext.current, guided, r, cur))
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
        val tooMuch = stringResource(R.string.live_too_much_lower)
        val tooLittle = stringResource(R.string.live_too_little_raise)
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // The English names also key the chime.
            listOf("Red", "Green", "Blue").forEachIndexed { j, key ->
                val label = capitalized(key.uppercase())
                // The hint says how far to move the slider (+ raise); drawn as the error (+ too much).
                val v: Double? = if (h == null || listOf("R", "G", "B")[j] in stuck) null else -h[j] * 100
                ColourControlRow(
                    label = label, value = v, colour = colour, fullScale = 20.0, tolerance = 3.0,
                    caption = v?.let { if (it > 0) tooMuch else tooLittle },
                    chime = colourChimeKey(guided, cur, key),
                )
            }
            BalanceScale()
        }
        if (r.rgbApprox) {
            Text(stringResource(R.string.live_estimated), style = Type.small, color = Theme.textSecondary)
        }
    } else {
        val n = guided.tv.brand.cmsNames
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ColourControlRow(
                label = n.hue, value = r.hue, colour = colour, fullScale = 10.0, tolerance = 2.0,
                caption = stringResource(R.string.live_leans_toward, r.toward?.let { capitalized(it) } ?: stringResource(R.string.live_a_neighbour), n.hue),
                chime = colourChimeKey(guided, cur, "hue"),
            )
            ColourControlRow(
                label = n.saturation, value = r.sat, colour = colour, fullScale = 15.0, tolerance = 3.0,
                caption = if ((r.sat ?: 0.0) > 0) stringResource(R.string.live_too_strong_lower, n.saturation) else stringResource(R.string.live_too_weak_raise, n.saturation),
                chime = colourChimeKey(guided, cur, "sat"),
            )
            ColourControlRow(
                label = n.brightness, value = r.lum, colour = colour, fullScale = 25.0, tolerance = 5.0,
                caption = if ((r.lum ?: 0.0) > 0) stringResource(R.string.live_too_bright_lower, n.brightness) else stringResource(R.string.live_too_dim_raise, n.brightness),
                chime = colourChimeKey(guided, cur, "lum"),
            )
            BalanceScale()
        }
    }
}

private fun colourTips(context: Context, guided: GuidedController, d: LiveReading, cur: String): List<Pair<String, TipKind>> {
    val name = capitalized(cur)
    if (d.steady) return listOf(context.getString(R.string.live_colour_on_target, name) to TipKind.done)
    val t = mutableListOf<Pair<String, TipKind>>()
    when (guided.tv.colourMenu) {
        ColourMenu.OFF -> return listOf(context.getString(R.string.live_nothing_to_adjust) to TipKind.ok)
        ColourMenu.HSL -> {}                                   // each bar says what to do
        ColourMenu.RGB_SLIDERS -> {
            if (d.rgbHint == null) {
                return listOf(context.getString(R.string.live_rgb_needs_before) to TipKind.todo)
            }
            val names = listOf("RED", "GREEN", "BLUE").map { capitalized(it) }
            val and = L10n.tr(" and ", " und ")
            val stuck = (d.outOfReach?.channels ?: emptyList()).mapNotNull { c -> listOf("R", "G", "B").indexOf(c).takeIf { it >= 0 } }
            val o = d.outOfReach
            if (o != null) {
                // German nouns keep their capital.
                val others = listOf(0, 1, 2).filter { it !in stuck }.joinToString(and) { if (L10n.german) names[it] else names[it].lowercase() }
                t.add(
                    context.getString(R.string.live_out_of_reach, name, stuck.joinToString(and) { names[it] }, others, fmt(o.bestDE)) to TipKind.limit,
                )
            }
        }
    }
    if (t.isEmpty() && d.ok) t.add(context.getString(R.string.live_close_enough) to TipKind.ok)
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
            Text(stringResource(R.string.live_fine_title), style = Type.stepTitleStyle)
            Text(
                stringResource(R.string.live_only_two_point),
                style = Type.bodyBrand, color = Theme.textSecondary,
            )
            StepMainAction(stringResource(R.string.live_back_to_check)) { guided.go(GuideStep.CHECK) }
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
            Eyebrow(text = stringResource(R.string.live_greys))
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 1.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (pid in guided.plan.fine) {
                    val selected = pid == cur
                    val shape = RoundedCornerShape(percent = 50)
                    Text(
                        shortLabel(Patterns.patch(guided.hdr, pid)?.name ?: pid) +
                            (sugg[pid]?.let { " → TV ${pct(it)}" } ?: "") +
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
                    Text(stringResource(R.string.live_control_that_moves), style = Type.bodyStrong, color = Color.White,
                        modifier = Modifier.weight(1f))
                    ControlPicker(guided, cur, sugg[cur])
                }
                Text(
                    stringResource(R.string.live_pick_remembered),
                    style = Type.small, color = Theme.textSecondary,
                )
                LinkButton(stringResource(R.string.live_run_finder_again), { guided.resetFinder() }, style = Type.captionStrong)
            } else {
                Text(stringResource(R.string.live_try_step_lower), style = Type.small,
                    color = Theme.textSecondary)
            }
        }
        StepMainAction(wbNextTitle(LocalContext.current, guided, WBKind.fine, cur)) {
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
    val context = LocalContext.current
    val pickerName = stringResource(R.string.live_control_that_moves)
    fun label(v: Int) = if (v == 0) suggestion?.let { context.getString(R.string.live_tv_percent_measured, it.toString()) }
        ?: context.getString(R.string.live_measured) else context.getString(R.string.live_tv_percent, v.toString())
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.semantics {
            contentDescription = pickerName
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
                FinderRowStatus.done -> Icon(symbol("checkmark"), contentDescription = stringResource(R.string.live_done), tint = Theme.tealBright,
                    modifier = Modifier.size(17.dp))
                FinderRowStatus.active -> CircularProgressIndicator(Modifier.size(16.dp), color = Theme.tealBright, strokeWidth = 2.dp)
                FinderRowStatus.waiting -> Icon(symbol("clock"), contentDescription = stringResource(R.string.live_waiting_state), tint = Theme.tealBright,
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
        showingResult -> stringResource(R.string.live_finder_use_points)
        running -> null
        haveBase -> stringResource(R.string.live_finder_made_changes)
        else -> stringResource(R.string.live_finder_measure)
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
    Text(stringResource(R.string.live_finder_result_title), style = Type.stepTitleStyle)
    Text(stringResource(R.string.live_finder_result_header), style = Type.bodyBrand, color = Theme.textSecondary)
    Card(spacing = 4.dp) {
        for (m in guided.finderMap) {
            val shift = m.level - m.point.toDouble()
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).semantics(mergeDescendants = true) { },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${if (m.channel == "R") capitalized("RED") else if (m.channel == "B") capitalized("BLUE") else "TV"} ${m.point}",
                    style = Type.brand(16f), color = Color.White, modifier = Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.live_finder_level, fmt(m.level, 0)), style = Type.brand(17f), color = Theme.tealBright)
                    Text(stringResource(R.string.live_finder_shift, signed(shift, 0)), style = Type.tiny,
                        color = if (abs(shift) >= 5) Theme.limit else Theme.green)
                }
            }
        }
    }
    TipRow(tone = TipTone.todo, text = stringResource(R.string.live_finder_put_back))
}

@Composable
private fun FinderFinding(guided: GuidedController, step: Int, haveBase: Boolean) {
    val running = guided.runner.running
    Text(stringResource(if (running) R.string.live_finder_finding else R.string.live_finder_find), style = Type.stepTitleStyle)
    Card(hero = true) {
        Eyebrow(text = stringResource(R.string.live_finder_optional))
        Text(stringResource(R.string.live_finder_step, step.toString()), style = Type.cardTitle.copy(letterSpacing = (-0.6).sp), color = Color.White)
        when (step) {
            1 -> Text(
                markdown(stringResource(R.string.live_finder_step1, guided.finderFile)),
                style = Type.bodyBrand, color = Color.White,
            )
            2 -> {
                Text(stringResource(R.string.live_finder_nudge_intro), style = Type.bodyBrand, color = Color.White)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((channel, point) in PointFinder.nudges) {
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            Text(stringResource(R.string.live_finder_tv_control, point.toString()), style = Type.bodyBrand, color = Color.White, modifier = Modifier.width(150.dp))
                            Text(capitalized(if (channel == "R") "RED" else "BLUE"), style = Type.bodyBrand, color = Theme.channel(channel),
                                modifier = Modifier.width(48.dp))
                            Text("+${PointFinder.NUDGE}", style = Type.bodyBrand, color = Color.White)
                        }
                    }
                }
                Text(
                    stringResource(R.string.live_finder_then_again),
                    style = Type.captionBrand, color = Theme.textSecondary,
                )
            }
            else -> Text(
                stringResource(R.string.live_finder_again),
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
        stringResource(R.string.live_finder_explain),
        style = Type.captionBrand, color = Theme.textSecondary,
    )
    (guided.runner.phase as? SweepRunner.Phase.Failed)?.let { TipRow(tone = TipTone.limit, text = it.message) }
    if (running) {
        MeasuringCard(runner = guided.runner, file = guided.finderFile, mode = guided.mode)
    }
    Card(spacing = 0.dp) {
        FinderStatusRow(stringResource(R.string.live_finder_row1),
            if (haveBase) FinderRowStatus.done else if (running) FinderRowStatus.active else FinderRowStatus.waiting)
        FinderStatusRow(stringResource(R.string.live_finder_row2, PointFinder.NUDGE.toString()),
            if (step == 3) FinderRowStatus.done else if (step == 2) FinderRowStatus.active else FinderRowStatus.waiting)
        FinderStatusRow(stringResource(R.string.live_finder_row3),
            if (step == 3) FinderRowStatus.active else FinderRowStatus.waiting)
    }
    if (running) {
        SecondaryButton(stringResource(R.string.live_cancel), { guided.runner.cancel() })
    } else {
        LinkButton(stringResource(R.string.live_finder_skip), { guided.skipFinder() })
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
                DemoKey("−", stringResource(R.string.live_demo_down, label)) { guided.demoPress(mapOf(key to -1)) }
                DemoKey("+", stringResource(R.string.live_demo_up, label)) { guided.demoPress(mapOf(key to 1)) }
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
