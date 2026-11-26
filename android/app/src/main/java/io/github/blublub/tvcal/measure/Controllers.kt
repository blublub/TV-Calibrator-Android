// Port of "TV Calibrator/Measure/Controllers.swift" (TV Calibrator by Slidebean,
// https://github.com/slidebean/tv-calibrator, CC BY-NC 4.0). Changed: translated to Kotlin; state is
// Compose snapshot state.
package io.github.blublub.tvcal.measure

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.blublub.tvcal.AppModel
import io.github.blublub.tvcal.engine.Analysis
import io.github.blublub.tvcal.engine.FinderPoint
import io.github.blublub.tvcal.engine.GuidePlan
import io.github.blublub.tvcal.engine.Measurements
import io.github.blublub.tvcal.engine.Patterns
import io.github.blublub.tvcal.engine.PointFinder
import io.github.blublub.tvcal.engine.Report
import io.github.blublub.tvcal.engine.ShownPatch
import io.github.blublub.tvcal.engine.SignalMode
import io.github.blublub.tvcal.engine.fixed
import io.github.blublub.tvcal.engine.signedFixed
import io.github.blublub.tvcal.storage.FinderData
import io.github.blublub.tvcal.storage.GuideProgress
import io.github.blublub.tvcal.storage.GuideState
import io.github.blublub.tvcal.storage.GuideStep
import io.github.blublub.tvcal.storage.GuidedSession
import io.github.blublub.tvcal.storage.TVRecord
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

enum class QuickWhich { BEFORE, AFTER, CHECK }

/** One TV's guided calibration in one signal mode. */
class GuidedController(val app: AppModel, tv: TVRecord, mode: SignalMode) {
    var tv by mutableStateOf(tv); private set
    var mode by mutableStateOf(mode); private set
    var state by mutableStateOf(app.store.guideState(tv, mode)); private set
    var message by mutableStateOf<String?>(null)
    /** The latest Check step result (not kept between launches). */
    var checkReport by mutableStateOf<Report?>(state.check?.let { Analysis.analyse(it, mode.isHDR) }); private set
    val live = LiveSession()
    val runner = SweepRunner()
    val shown = ShownPatch()

    val hdr: Boolean get() = mode.isHDR
    val plan: GuidePlan get() = GuidePlan.forHDR(hdr)
    val progress: GuideProgress get() = state.progress
    val lastSession: GuidedSession? get() = state.sessions.lastOrNull()
    /** The latest session's Before measurement, if it has one. */
    val latestBefore: Measurements? get() = state.sessions.lastOrNull()?.before

    fun reload(tv: TVRecord, mode: SignalMode) {
        stopLive()
        runner.reset()
        this.tv = tv
        this.mode = mode
        state = app.store.guideState(tv, mode)
        checkReport = state.check?.let { Analysis.analyse(it, mode.isHDR) }
        message = null
    }

    private fun save() = app.store.save(state, tv, mode)

    private fun updateProgress(f: (GuideProgress) -> GuideProgress) {
        state = state.copy(progress = f(state.progress))
    }

    fun go(step: GuideStep, markDone: GuideStep? = null) {
        updateProgress { p ->
            val done = if (markDone != null && markDone !in p.done) p.done + markDone else p.done
            p.copy(step = step, done = done)
        }
        message = null
        if (step !in listOf(GuideStep.BRIGHT, GuideStep.DARK, GuideStep.COLOURS, GuideStep.FINE)) stopLive()
        save()
    }

    fun markLimit(key: String) {
        if (key !in state.progress.limits) updateProgress { it.copy(limits = it.limits + key) }
        save()
    }

    fun setColour(c: String?) {
        updateProgress { it.copy(colour = c) }
        save()
    }

    fun finishColour(c: String, done: Boolean) {
        if (done && c !in state.progress.colours) updateProgress { it.copy(colours = it.colours + c) }
        val rest = Patterns.colourIDs.filter { it !in state.progress.colours && it != c }
        val next = rest.firstOrNull()
        if (next != null) {
            updateProgress { it.copy(colour = next) }
            save()
        } else {
            updateProgress { it.copy(colour = null) }
            go(GuideStep.AFTER, markDone = GuideStep.COLOURS)
        }
    }

    fun setFinePid(pid: String) {
        updateProgress { it.copy(finePid = pid) }
        save()
    }

    /** Starts again from the Before check. The finished sessions stay in the history. */
    fun newSession() {
        state = state.copy(
            progress = GuideProgress(step = GuideStep.BEFORE, done = listOf(GuideStep.SETUP)),
            sessions = state.sessions + GuidedSession(before = null),
            check = null,
        )
        checkReport = null
        save()
    }

    // the file to play

    fun file(pid: String): String? = Patterns.packFile(mode, pid)
    val quickFile: String get() = file("QUICK") ?: ""
    val finderFile: String get() = Patterns.finderFile(mode)

    // quick checks (before / after / check)

    suspend fun measureQuick(which: QuickWhich) {
        stopLive()
        val seq = Patterns.quickSequence(hdr, plan.quickWithColours)
        val meter = app.videoMeter(tv, hdr, seq)
        if (meter == null) {
            message = app.cameraBlocker
            return
        }
        val meas = runner.run(meter, seq, checkOrder = true) ?: return
        val rep = Analysis.analyse(meas, hdr)
        val sessions = state.sessions.toMutableList()
        when (which) {
            QuickWhich.CHECK -> {
                checkReport = rep
                state = state.copy(check = meas)
                save()
                return
            }
            QuickWhich.BEFORE -> {
                // Fill a fresh session (from Start a new session), or start one.
                val last = sessions.lastOrNull()
                if (last != null && last.before == null && last.after == null) {
                    sessions[sessions.size - 1] = last.copy(before = meas, started = Instant.now())
                } else {
                    sessions.add(GuidedSession(before = meas))
                }
                state = state.copy(sessions = sessions)
            }
            QuickWhich.AFTER -> {
                if (sessions.isEmpty() || sessions.last().after != null) {
                    // Measuring "after" again: keep comparing with the same starting point.
                    sessions.add(GuidedSession(before = sessions.lastOrNull()?.before))
                }
                sessions[sessions.size - 1] = sessions.last().copy(after = meas, finished = Instant.now())
                state = state.copy(sessions = sessions)
                if (GuideStep.AFTER !in state.progress.done) updateProgress { it.copy(done = it.done + GuideStep.AFTER) }
                // TODO: schedule the recheck reminder once notifications are ported.
            }
        }
        save()
        app.store.appendLog(tv, Log.quick(mode, which, rep))
    }

    // live readings

    fun showLive(pid: String) {
        val p = Patterns.patch(hdr, pid) ?: return
        shown.patch = p
        val meter = app.liveMeter(tv, hdr, shown)
        if (meter == null) {
            message = app.cameraBlocker
            return
        }
        message = null
        val ref = state.reference
        live.show(p, hdr, ref.whiteY, ref.basis, meter)
    }

    fun stopLive() {
        live.stop()
        shown.patch = null
    }

    /** Demo only: press the "remote". */
    fun demoPress(changes: Map<String, Int>) {
        app.demoApply(tv, hdr, changes)
        live.restartAverage()
    }

    // point finder

    val finderMap: List<FinderPoint>
        get() {
            val b = state.finder.base ?: return emptyList()
            val n = state.finder.nudged ?: return emptyList()
            return PointFinder.map(b, n, hdr)
        }

    val grid: Int get() = PointFinder.grid(tv.multipoint.raw)

    val suggestions: Map<String, Int>
        get() = PointFinder.suggestions(finderMap, state.finder.overrides, hdr, grid)

    val finderReady: Boolean get() = finderMap.isNotEmpty() || state.finder.overrides.isNotEmpty()

    suspend fun measureFinder(nudged: Boolean) {
        stopLive()
        val seq = Patterns.finderSequence(hdr)
        val nudges = PointFinder.nudges.associate { "p20:${it.first}:${it.second}" to PointFinder.NUDGE }
        if (tv.isDemo && nudged) app.demoApply(tv, hdr, nudges)
        try {
            val meter = app.videoMeter(tv, hdr, seq)
            if (meter == null) {
                message = app.cameraBlocker
                return
            }
            val meas = runner.run(meter, seq) ?: return
            meas.missed.clear()
            if (!nudged) {
                state = state.copy(finder = FinderData(base = meas, nudged = null, overrides = state.finder.overrides))
            } else {
                state = state.copy(finder = state.finder.copy(nudged = meas))
                val map = finderMap
                if (map.size < 2) {
                    message = io.github.blublub.tvcal.ui.L10n.tr(
                        "The nudges didn't show up clearly enough to find the controls. Check the four changes were " +
                            "made (+10 each), then measure the nudged pass again.",
                        "Die Änderungen waren nicht deutlich genug zu sehen, um die Regler zu finden. Prüfe, ob alle vier " +
                            "Änderungen gemacht wurden (je +10), und miss den geänderten Durchgang noch einmal.",
                    )
                } else {
                    app.store.appendLog(
                        tv,
                        "\n## Point finder (${mode.label}) ${Log.stamp()}\n" +
                            map.joinToString("") { "- TV ${it.point}% control (${it.channel}) acts on the ${fixed(it.level, 1)}% grey\n" },
                    )
                }
            }
            save()
        } finally {
            if (tv.isDemo && nudged) app.demoApply(tv, hdr, nudges.mapValues { -it.value })
        }
    }

    fun setOverride(pid: String, point: Int?) {
        val o = state.finder.overrides.toMutableMap()
        if (point != null) o[pid] = point else o.remove(pid)
        state = state.copy(finder = state.finder.copy(overrides = o))
        save()
    }

    fun resetFinder() {
        state = state.copy(finder = FinderData(base = null, nudged = null, overrides = state.finder.overrides))
        save()
    }

    fun skipFinder() {
        state = state.copy(finder = state.finder.copy(overrides = state.finder.overrides + ("_skip" to 0)))
        save()
    }

    val finderSkipped: Boolean get() = state.finder.overrides["_skip"] != null
}

/** The readable per-TV log (log.md), like the Mac app's. */
object Log {
    fun stamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date())

    fun table(rep: Report, hdr: Boolean): String {
        val out = StringBuilder()
        out.append("Greyscale dE ${fixed(Analysis.greyAverage(rep, hdr), 2)}, colour dE ${fixed(Analysis.colourAverage(rep), 2)}\n\n")
        out.append("| step | cd/m² | R | G | B | dE |\n|---|---|---|---|---|---|\n")
        for (g in rep.greys) {
            out.append("| ${g.name} | ${fixed(g.Y, 2)} | ${fixed(g.bal.x * 100, 1)} | ${fixed(g.bal.y * 100, 1)} | ${fixed(g.bal.z * 100, 1)} | ${fixed(g.de, 2)} |\n")
        }
        out.append("\n| colour | dE | saturation | hue | brightness |\n|---|---|---|---|---|\n")
        for (c in rep.colours) {
            val name = c.name.lowercase().replaceFirstChar { it.uppercase() }
            out.append("| $name | ${fixed(c.de, 2)} | ${signedFixed(c.sat, 1)}% | ${signedFixed(c.hue, 1)}° | ${signedFixed(c.lum, 1)}% |\n")
        }
        for (w in rep.warnings) out.append("\nWarning: $w\n")
        return out.toString()
    }

    fun quick(mode: SignalMode, which: QuickWhich, report: Report): String {
        val label = when (which) {
            QuickWhich.BEFORE -> "before"
            QuickWhich.AFTER -> "after"
            QuickWhich.CHECK -> "check"
        }
        return "\n## Guided ${mode.label} - $label check (${stamp()})\n" + table(report, mode.isHDR)
    }
}
