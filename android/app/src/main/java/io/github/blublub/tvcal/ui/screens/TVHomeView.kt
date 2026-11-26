// Port of TV Calibrator/Views/TVHomeView.swift (TV Calibrator by Slidebean, https://github.com/slidebean/tv-calibrator, CC BY-NC 4.0). Changed: translated to Kotlin/Compose.
package io.github.blublub.tvcal.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.blublub.tvcal.AppModel
import io.github.blublub.tvcal.engine.Analysis
import io.github.blublub.tvcal.engine.Report
import io.github.blublub.tvcal.engine.SignalMode
import io.github.blublub.tvcal.measure.GuidedController
import io.github.blublub.tvcal.storage.GuideStep
import io.github.blublub.tvcal.storage.TVRecord
import io.github.blublub.tvcal.storage.uuidString
import io.github.blublub.tvcal.ui.FullSheet
import io.github.blublub.tvcal.ui.LocalApp
import io.github.blublub.tvcal.ui.LocalNav
import io.github.blublub.tvcal.ui.components.ReportPanel
import io.github.blublub.tvcal.ui.shareFile
import io.github.blublub.tvcal.ui.theme.Card
import io.github.blublub.tvcal.ui.theme.ModeBadge
import io.github.blublub.tvcal.ui.theme.PrimaryButton
import io.github.blublub.tvcal.ui.theme.ProgressTrack
import io.github.blublub.tvcal.ui.theme.ScreenWithBottomAction
import io.github.blublub.tvcal.ui.theme.SecondaryButton
import io.github.blublub.tvcal.ui.theme.Theme
import io.github.blublub.tvcal.ui.theme.Type
import io.github.blublub.tvcal.ui.theme.blurb
import io.github.blublub.tvcal.ui.theme.isAdvanced
import io.github.blublub.tvcal.ui.theme.symbol
import io.github.blublub.tvcal.ui.theme.tint
import java.time.Instant

/** The per-screen settings the original keeps in UserDefaults ("mode-<id>", "advanced-ok-<id>"). */
private fun tvHomePrefs(context: Context) = context.getSharedPreferences("app", Context.MODE_PRIVATE)

private fun tvHomeVersionName(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

/** The latest finished calibration (its After check, with where it started), or the latest starting point. */
private class TVHomeLatest(val report: Report, val before: Report?, val date: Instant?, val finished: Boolean)

/**
 * The screen home (Figma: 06 Screen home · new / progress / calibrated / menu / advanced / step navigator):
 * the formats to calibrate and how far each has got. The guided steps are their own screen
 * (`GuidedScreen`), opened by the pinned main action.
 */
@Composable
fun TVHomeView(app: AppModel, tv: TVRecord) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val tvID = tv.id
    val prefs = remember { tvHomePrefs(context) }

    var mode by remember(tvID) {
        val saved = prefs.getString("mode-${tvID.uuidString}", null)
        mutableStateOf(SignalMode.entries.firstOrNull { it.raw == saved } ?: SignalMode.SDR)
    }
    val guided = remember(tvID) { GuidedController(app, tv, mode) }
    var editing by remember { mutableStateOf(false) }
    var askAdvanced by remember { mutableStateOf<SignalMode?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var showingSteps by remember { mutableStateOf(false) }
    var navigating by remember { mutableStateOf(false) }
    var openAfterNavigating by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    val screen: TVRecord = app.tvs.firstOrNull { it.id == tvID } ?: guided.tv

    // onChange(of: mode) / onChange(of: tv): keep the controller on this screen and format.
    LaunchedEffect(screen, mode) {
        prefs.edit().putString("mode-${tvID.uuidString}", mode.raw).apply()
        if (guided.tv != screen || guided.mode != mode) guided.reload(screen, mode)
    }
    // Leaving this screen for the home screen: nothing should keep measuring behind the user's back.
    // (The steps are shown inside this screen, so this only runs when the screen itself goes.)
    DisposableEffect(tvID) {
        onDispose {
            guided.runner.cancel()
            guided.stopLive()
        }
    }

    if (showingSteps) {
        BackHandler { showingSteps = false }
        GuidedScreen(guided = guided, onBack = { showingSteps = false })
        return
    }

    val total = GuideStep.stepper.size
    val done = guided.state.progress.done.size
    val calibrated = done >= total - 1
    val latest: TVHomeLatest? = remember(guided.state, guided.mode) {
        val sessions = guided.state.sessions
        val hdr = guided.mode.isHDR
        val fin = sessions.lastOrNull { it.after != null }
        val a = fin?.after
        val st = sessions.lastOrNull { it.before != null }
        val b = st?.before
        if (fin != null && a != null) {
            TVHomeLatest(Analysis.analyse(a, hdr), fin.before?.let { Analysis.analyse(it, hdr) }, fin.finished, true)
        } else if (st != null && b != null) {
            TVHomeLatest(Analysis.analyse(b, hdr), null, st.started, false)
        } else null
    }
    val mainTitle = when {
        done == 0 -> "Start with ${mode.label}"
        calibrated -> "View ${mode.label} results"
        else -> "Continue ${mode.label}"
    }

    fun choose(m: SignalMode) {
        if (!m.isAdvanced || prefs.getBoolean("advanced-ok-${tvID.uuidString}", false)) mode = m
        else askAdvanced = m
    }

    ScreenWithBottomAction(actionTitle = mainTitle, onAction = { showingSteps = true }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            TVHomeTopBar(screen.name, onBack = { nav.pop() }) {
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.semantics {
                        contentDescription = "Screen options"
                    }) {
                        Icon(symbol("ellipsis.circle"), contentDescription = null, tint = Theme.tealBright)
                    }
                    // The screen's menu: its settings, its history, delete.
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Screen settings") },
                            leadingIcon = { Icon(symbol("pencil"), contentDescription = null) },
                            onClick = { menuOpen = false; editing = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Export history") },
                            leadingIcon = { Icon(symbol("square.and.arrow.up"), contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                val file = app.store.export(screen, app.profiles.profile, app = tvHomeVersionName(context),
                                    phone = app.profiles.phoneModel)
                                if (file != null) shareFile(context, file)
                            },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Delete this screen", color = Theme.red) },
                            leadingIcon = { Icon(symbol("trash"), contentDescription = null, tint = Theme.red) },
                            onClick = { menuOpen = false; deleting = true },
                        )
                    }
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // The report first, then the formats.
                if (latest != null) {
                    // The phone's margin of error isn't available on Android (and is hidden for the demo).
                    ScreenReportCard(latest.report, latest.before, mode.isHDR, mode, latest.date, latest.finished,
                        margin = null) {
                        guided.go(if (latest.finished) GuideStep.AFTER else GuideStep.BEFORE)
                        showingSteps = true
                    }
                } else {
                    // Nothing measured yet: the picture of what's about to happen.
                    val art: @Composable () -> Unit = { PlacementArt() }
                    WalkArtPanel(height = 150.dp, content = art)
                }
                ModeSelector(screen, mode) { m -> choose(m) }
                if (done > 0 && !calibrated) {
                    // The bar opens "Jump to a step" (the step navigator).
                    Box(Modifier.padding(top = 4.dp)) {
                        StepBar(guided = guided)
                        Box(
                            Modifier
                                .matchParentSize()
                                .clickable(role = Role.Button) { navigating = true }
                                .semantics { contentDescription = "Jump to a step" },
                        )
                    }
                }
                if (screen.isDemo) TVHomeDemoNote()
            }
        }
    }

    if (editing) {
        FullSheet(onDismiss = { editing = false }) {
            TVSetupFlow(tv = screen, onDismiss = { editing = false })
        }
    }
    askAdvanced?.let { m ->
        FullSheet(onDismiss = { askAdvanced = null }) {
            AdvancedFormatSheet(m, confirm = {
                prefs.edit().putBoolean("advanced-ok-${tvID.uuidString}", true).apply()
                mode = m
            }, onDismiss = { askAdvanced = null })
        }
    }
    if (navigating) {
        val close = {
            navigating = false
            // Open the step screen once the sheet has gone.
            if (openAfterNavigating) {
                openAfterNavigating = false
                showingSteps = true
            }
        }
        FullSheet(onDismiss = close) {
            StepNavigatorSheet(guided, screen.name, mode, open = { openAfterNavigating = true }, onDismiss = close)
        }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete ${screen.name}?") },
            text = { Text("Its measurements and calibration progress are removed from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    val doomed = screen
                    guided.stopLive()
                    nav.pop()
                    app.delete(doomed)
                }) { Text("Delete the screen and its history", color = Theme.red) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
            containerColor = Theme.surface,
            titleContentColor = Color.White,
            textContentColor = Theme.textSecondary,
        )
    }
}

/** The navigation bar: back, the title, and the trailing actions. */
@Composable
private fun TVHomeTopBar(title: String, onBack: (() -> Unit)?, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Theme.tealBright)
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
        Text(title, style = Type.headlineBrand, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp).semantics { heading() })
        actions()
    }
}

/** A sheet's title bar: the title and a close button. */
@Composable
private fun TVHomeSheetBar(title: String, onClose: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(symbol("xmark"), contentDescription = "Close", tint = Theme.tealBright)
        }
        Text(title, style = Type.headlineBrand, color = Color.White, modifier = Modifier.semantics { heading() })
    }
}

@Composable
private fun TVHomeDemoNote() {
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Icon(symbol("sparkles"), contentDescription = null, tint = Theme.tealBright, modifier = Modifier.size(17.dp))
        Text("Demo: simulated readings. The on-screen buttons stand in for the TV's remote.",
            style = Type.captionBrand, color = Theme.textSecondary)
    }
}

/**
 * The latest results, first thing on the screen home: the report panel (scores on their gauges, the
 * white point, the colours), opening the full results.
 */
@Composable
private fun ScreenReportCard(
    report: Report,
    before: Report?,
    hdr: Boolean,
    mode: SignalMode,
    date: Instant?,
    finished: Boolean,
    margin: Double?,
    open: () -> Unit,
) {
    ReportPanel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(if (finished) "CALIBRATED" else "STARTING POINT", style = Type.badge.copy(letterSpacing = 0.8.sp),
                color = if (finished) Theme.green else Theme.accent)
            ModeBadge(mode)
            Spacer(Modifier.weight(1f).width(8.dp))
            if (date != null) Text(resultsDate(date), style = Type.small, color = Theme.textSecondary)
        }
        ReportHero(report, before = before, hdr = hdr, margin = margin)
        Row(
            Modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = open),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (finished) "Full results" else "See the starting point", style = Type.captionStrong,
                color = Theme.tealBright)
            Icon(symbol("chevron.right"), contentDescription = null, tint = Theme.tealBright, modifier = Modifier.size(14.dp))
        }
    }
}

private val formatShape = RoundedCornerShape(18.dp)

/**
 * Which format to calibrate: SDR then HDR10 (YouTube) as format cards, with HDR10+ and Dolby Vision
 * in the Advanced card.
 */
@Composable
fun ModeSelector(tv: TVRecord, mode: SignalMode, choose: (SignalMode) -> Unit) {
    val app = LocalApp.current
    fun stepsDone(m: SignalMode): Int = app.store.guideState(tv, m).progress.done.size

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            for (m in listOf(SignalMode.SDR, SignalMode.HDR10)) {
                ModeSelectorCard(m, mode, stepsDone(m), Modifier.weight(1f)) { choose(m) }
            }
        }
        // HDR10+ and Dolby Vision: USB files, after SDR and HDR10.
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .background(Theme.card, formatShape)
                .border(if (mode.isAdvanced) 1.5.dp else 1.dp,
                    if (mode.isAdvanced) mode.tint.copy(alpha = 0.7f) else Theme.hairline, formatShape)
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Advanced", style = Type.captionStrong, color = Color.White)
            Text("USB files", style = Type.small, color = Theme.textSecondary)
            Spacer(Modifier.weight(1f))
            for (m in listOf(SignalMode.HDR10P, SignalMode.DV)) {
                Box(Modifier
                    .clickable(role = Role.Button) { choose(m) }
                    .semantics { selected = m == mode }
                    .padding(vertical = 8.dp)) {
                    // The format badge; filled in the format's colour when it's the one being calibrated.
                    if (m == mode) {
                        Text(m.label, style = Type.badge, color = Theme.background,
                            modifier = Modifier.background(m.tint, RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 5.dp))
                    } else {
                        ModeBadge(m)
                    }
                }
            }
        }
    }
}

/** A format tile: its badge and a status mark, how far it has got, a short line, and its progress. */
@Composable
private fun ModeSelectorCard(m: SignalMode, mode: SignalMode, done: Int, modifier: Modifier, onClick: () -> Unit) {
    val total = GuideStep.stepper.size
    val calibrated = done >= total - 1
    val selected = m == mode
    val progress = if (calibrated) 1.0 else minOf(done, total).toDouble() / maxOf(total, 1)
    Column(
        modifier
            .clip(formatShape)
            .background(Theme.card, formatShape)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) m.tint.copy(alpha = 0.7f) else Theme.hairline, formatShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { this.selected = selected }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            ModeBadge(m)
            Spacer(Modifier.weight(1f))
            Icon(
                symbol(if (calibrated) "checkmark.circle.fill" else if (selected) "largecircle.fill.circle" else "circle"),
                contentDescription = null,
                tint = if (calibrated) Theme.green else if (selected) m.tint else Theme.textSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
        BasicText(
            if (done == 0) "Not started" else if (calibrated) "Calibrated" else "$done of $total steps",
            style = Type.bodyStrong.copy(color = if (calibrated) Theme.green else Color.White),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 15.sp),
        )
        Text(m.blurb, style = Type.small, color = Theme.textSecondary, maxLines = 2)
        ProgressTrack(progress, colour = if (calibrated) Theme.green else m.tint)
    }
}

/** Asked before an advanced format (HDR10+, Dolby Vision) the first time on this screen. */
@Composable
private fun AdvancedFormatSheet(mode: SignalMode, confirm: () -> Unit, onDismiss: () -> Unit) {
    ScreenWithBottomAction(bottom = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("Calibrate ${mode.label} anyway", onClick = {
                confirm()
                onDismiss()
            })
            SecondaryButton("Not now", onClick = onDismiss)
        }
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            TVHomeSheetBar("Advanced formats", onDismiss)
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ModeBadge(mode)
                Text("${mode.label} needs a USB drive", style = Type.stepTitleStyle)
                val art: @Composable () -> Unit = {
                    Row(
                        Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val big = Modifier.size(38.dp)
                        val arrow = Modifier.size(16.dp)
                        Icon(symbol("square.and.arrow.down"), contentDescription = null, tint = Theme.tealBright, modifier = big)
                        Icon(symbol("arrow.right"), contentDescription = null, tint = Theme.textSecondary, modifier = arrow)
                        Icon(symbol("externaldrive"), contentDescription = null, tint = Theme.tealBright, modifier = big)
                        Icon(symbol("arrow.right"), contentDescription = null, tint = Theme.textSecondary, modifier = arrow)
                        Icon(symbol("tv"), contentDescription = null, tint = Theme.tealBright, modifier = big)
                    }
                }
                WalkArtPanel(height = 120.dp, content = art)
                Text(
                    "YouTube can't play ${mode.label}: download the pattern files, copy them to a USB drive and play them " +
                        "on the TV. Do SDR and HDR10 first; this builds on those settings.",
                    style = Type.bodyBrand.copy(lineHeight = 22.sp), color = Theme.textSecondary,
                )
            }
        }
    }
}

/**
 * "Jump to a step": the seven steps with Done / Current / Upcoming; pick one and go there.
 * `open`: called after jumping, to open the step screen.
 */
@Composable
fun StepNavigatorSheet(
    guided: GuidedController,
    screenName: String,
    mode: SignalMode,
    open: () -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by remember { mutableStateOf<GuideStep?>(null) }
    // The fine-tune step lives under Check in the bar.
    val currentStep = if (guided.progress.step == GuideStep.FINE) GuideStep.CHECK else guided.progress.step
    val target = picked ?: currentStep

    ScreenWithBottomAction(actionTitle = "Go to ${target.label.lowercase()}", onAction = {
        guided.go(target)
        open()
        onDismiss()
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            TVHomeSheetBar("Jump to a step", onDismiss)
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(screenName, style = Type.bodyBrand, color = Color.White, modifier = Modifier.weight(1f))
                    ModeBadge(mode)
                }
                Card(spacing = 0.dp) {
                    GuideStep.stepper.forEachIndexed { i, step ->
                        val done = step in guided.progress.done
                        val isCurrent = step == currentStep
                        val isTarget = step == target
                        val status = if (isCurrent) "Current" else if (done) "Done" else "Upcoming"
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable(role = Role.Button) { picked = step }
                                .semantics(mergeDescendants = true) { selected = isTarget },
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (done && !isTarget) "✓" else "${i + 1}",
                                style = Type.brand(16f),
                                color = if (isTarget) Theme.accent else if (done) Theme.tealBright else Theme.textSecondary,
                                modifier = Modifier.width(20.dp),
                            )
                            Text(
                                step.label,
                                style = if (isTarget) Type.bodyStrong else Type.bodyBrand.copy(fontWeight = FontWeight.Normal),
                                color = if (isTarget) Theme.accent else Color.White,
                                modifier = Modifier.weight(1f),
                            )
                            Text(status, style = Type.small, color = Theme.textSecondary)
                        }
                    }
                }
            }
        }
    }
}

// ExportItem and ShareSheet (UIKit's share sheet) are not ported: Android shares with shareFile() from ui/Navigation.kt.
