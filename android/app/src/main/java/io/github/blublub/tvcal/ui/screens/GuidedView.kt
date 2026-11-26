// Port of TV Calibrator/Views/GuidedView.swift (TV Calibrator by Slidebean, https://github.com/slidebean/tv-calibrator, CC BY-NC 4.0). Changed: translated to Kotlin/Compose.
package io.github.blublub.tvcal.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.blublub.tvcal.AppModel
import io.github.blublub.tvcal.R
import io.github.blublub.tvcal.engine.Analysis
import io.github.blublub.tvcal.engine.GreyResult
import io.github.blublub.tvcal.engine.Measurements
import io.github.blublub.tvcal.engine.Patterns
import io.github.blublub.tvcal.engine.Report
import io.github.blublub.tvcal.engine.SignalMode
import io.github.blublub.tvcal.measure.GuidedController
import io.github.blublub.tvcal.measure.QuickWhich
import io.github.blublub.tvcal.measure.SweepRunner
import io.github.blublub.tvcal.profiles.PatternLinks
import io.github.blublub.tvcal.profiles.onYouTube
import io.github.blublub.tvcal.storage.Brand
import io.github.blublub.tvcal.storage.GamutCheck
import io.github.blublub.tvcal.storage.GuideStep
import io.github.blublub.tvcal.storage.Multipoint
import io.github.blublub.tvcal.storage.PanelType
import io.github.blublub.tvcal.ui.FullSheet
import io.github.blublub.tvcal.ui.LocalApp
import io.github.blublub.tvcal.ui.components.GreyBalanceChart
import io.github.blublub.tvcal.ui.components.ReportSummary
import io.github.blublub.tvcal.ui.components.Score
import io.github.blublub.tvcal.ui.components.ScoreInfo
import io.github.blublub.tvcal.ui.openUrl
import io.github.blublub.tvcal.ui.theme.Card
import io.github.blublub.tvcal.ui.theme.LinkButton
import io.github.blublub.tvcal.ui.theme.ModeBadge
import io.github.blublub.tvcal.ui.theme.PrimaryButton
import io.github.blublub.tvcal.ui.theme.ScreenWithBottomAction
import io.github.blublub.tvcal.ui.theme.SecondaryButton
import io.github.blublub.tvcal.ui.theme.Theme
import io.github.blublub.tvcal.ui.theme.TipRow
import io.github.blublub.tvcal.ui.theme.TipTone
import io.github.blublub.tvcal.ui.theme.Type
import io.github.blublub.tvcal.ui.theme.fmt
import io.github.blublub.tvcal.ui.theme.signed
import io.github.blublub.tvcal.ui.theme.symbol
import io.github.blublub.tvcal.ui.theme.tint
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.max

// The guided calibration, one step per screen (Figma: 07 Set up, 08 Before / Check / After,
// 10 Check and fine-tune, and the seven-step bar).

/** Tabular digits, like SwiftUI's `.monospacedDigit()`. */
private val TextStyleTnum = "tnum"

@Composable
fun GuidedView(guided: GuidedController) {
    val app = LocalApp.current
    var showingCamera by remember { mutableStateOf(false) }
    /** Bumped when the camera setup closes during a live step, so the step starts reading again. */
    var restarts by remember { mutableIntStateOf(0) }

    val step = guided.progress.step
    // The hands-on steps (bright end, dark end, colours, fine-tune) show a one-line context;
    // the others show the screen, the format and the seven-step bar.
    val compactContext = step in listOf(GuideStep.BRIGHT, GuideStep.DARK, GuideStep.COLOURS, GuideStep.FINE)

    // Back from the camera setup: forget the "start the camera" message once it no longer applies, and
    // let a live step begin reading.
    fun cameraClosed() {
        showingCamera = false
        if (app.cameraBlocker != null) return
        guided.message = null
        if (compactContext) restarts += 1
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GuidedContextHeader(guided = guided, compact = compactContext)
        val m = guided.message
        if (m != null) {
            TipRow(tone = TipTone.limit, text = m)
            if (!guided.tv.isDemo && app.cameraBlocker != null) {
                SecondaryButton("Set up the camera", onClick = { showingCamera = true })
            }
        }
        key("${guided.mode.raw}-${step.raw}-$restarts") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (step) {
                    GuideStep.SETUP -> SetupStep(guided = guided, openCamera = { showingCamera = true })
                    GuideStep.BEFORE -> QuickStep(guided = guided, which = QuickWhich.BEFORE)
                    GuideStep.BRIGHT -> WhiteBalanceStep(guided = guided, kind = WBKind.gain, pid = guided.plan.gain)
                    GuideStep.DARK -> WhiteBalanceStep(guided = guided, kind = WBKind.offset, pid = guided.plan.offset)
                    GuideStep.CHECK -> CheckStep(guided = guided)
                    GuideStep.COLOURS -> ColoursStep(guided = guided)
                    GuideStep.AFTER -> QuickStep(guided = guided, which = QuickWhich.AFTER)
                    GuideStep.FINE -> FineStep(guided = guided)
                }
            }
        }
    }
    // A sheet, not a push: this screen is itself pushed outside the home screen's navigation path.
    if (showingCamera) {
        FullSheet(onDismiss = { cameraClosed() }) {
            CameraSetupSheet(onDismiss = { cameraClosed() })
        }
    }
}

/** Camera setup opened from a calibration step. */
@Composable
fun CameraSetupSheet(onDismiss: () -> Unit) {
    val app = LocalApp.current
    Column(
        Modifier
            .fillMaxSize()
            .background(Theme.background)
            .systemBarsPadding(),
    ) {
        Box(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp)) {
            Box(
                Modifier
                    .size(44.dp)
                    .align(Alignment.CenterStart)
                    .clickable(role = Role.Button, onClick = onDismiss)
                    .semantics { contentDescription = "Close" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(symbol("xmark"), contentDescription = null, tint = Theme.tealBright, modifier = Modifier.size(22.dp))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            CameraSetupView(camera = app.camera, profiles = app.profiles, onDismiss = onDismiss)
        }
    }
}

/**
 * One step of the guided calibration as its own screen, pushed from the screen home (TVHomeView).
 * The step's main action is pinned at the bottom ([PinnedActionOrInline] in the step, [LocalPinnedActionHost]
 * here); while a video is being measured, Cancel takes its place.
 * `onBack` (not in the original, where the navigation bar has its own back button): shows a back arrow.
 */
@Composable
fun GuidedScreen(guided: GuidedController, onBack: (() -> Unit)? = null) {
    val app = LocalApp.current
    val pinned = remember { PinnedActionHostState() }
    val title = if (guided.progress.step == GuideStep.CHECK && guided.checkReport != null && !guided.runner.running) {
        "Check and fine-tune"
    } else {
        guided.progress.step.label
    }

    // Measuring takes minutes with nobody touching the phone (and the demo has no camera to keep it awake).
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            guided.stopLive()
            view.keepScreenOn = app.camera.running.value
        }
    }

    val running = guided.runner.running
    val hasPinned = pinned.current != null
    ScreenWithBottomAction(
        modifier = Modifier.fillMaxSize(),
        bottom = if (running || hasPinned) {
            {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PinnedActionBar(pinned)
                    if (running) SecondaryButton("Cancel", onClick = { guided.runner.cancel() })
                }
            }
        } else {
            null
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            GuidedTopBar(title = title, onBack = onBack)
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                CompositionLocalProvider(LocalPinnedActionHost provides pinned) {
                    GuidedView(guided = guided)
                }
            }
        }
    }
}

/** The inline navigation title, with a back arrow when the host gives one. */
@Composable
private fun GuidedTopBar(title: String, onBack: (() -> Unit)?) {
    Box(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp)) {
        if (onBack != null) {
            Box(
                Modifier
                    .size(44.dp)
                    .align(Alignment.CenterStart)
                    .clickable(role = Role.Button, onClick = onBack)
                    .semantics { contentDescription = "Back" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Theme.tealBright,
                    modifier = Modifier.size(24.dp))
            }
        }
        Text(
            title, style = Type.headlineBrand, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 260.dp)
                .semantics { heading() },
        )
    }
}

/**
 * "Living room OLED" and the format badge, then the seven-step bar; or, compact,
 * "Living room OLED · Step 3 of 7" and the badge.
 */
@Composable
private fun GuidedContextHeader(guided: GuidedController, compact: Boolean) {
    var navigating by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (compact) {
                // The hands-on steps keep the bar out of the way: "Step 3 of 7" opens the step list.
                val pill = RoundedCornerShape(percent = 50)
                Row(
                    Modifier
                        .weight(1f, fill = false)
                        .heightIn(min = 32.dp)
                        .background(Theme.card, pill)
                        .border(1.dp, Theme.hairline, pill)
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button,
                            onClickLabel = "Shows all the steps, to go back or ahead") { navigating = true }
                        .padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${guided.tv.name} · ${StepBar.position(guided.progress.step)}",
                        style = Type.captionStrong, color = Theme.tealBright, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    Icon(symbol("list.number"), contentDescription = null, tint = Theme.tealBright,
                        modifier = Modifier.size(14.dp))
                }
                Spacer(Modifier.weight(1f))
            } else {
                Text(guided.tv.name, style = Type.captionBrand, color = Theme.textSecondary, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            ModeBadge(mode = guided.mode)
        }
        if (!compact) {
            StepBar(guided = guided)
        }
    }
    if (navigating) {
        FullSheet(onDismiss = { navigating = false }) {
            StepNavigatorSheet(guided = guided, screenName = guided.tv.name, mode = guided.mode, open = {},
                onDismiss = { navigating = false })
        }
    }
}

/** The step shown as current, and "Step 3 of 7" (the static functions of the Swift StepBar). */
object StepBar {
    /** The step shown as current: fine-tuning belongs to the Check step. */
    fun shown(step: GuideStep): GuideStep = if (step == GuideStep.FINE) GuideStep.CHECK else step

    /** "Step 3 of 7". */
    fun position(of: GuideStep): String {
        val i = max(GuideStep.stepper.indexOf(shown(of)), 0) + 1
        return "Step $i of ${GuideStep.stepper.size}"
    }
}

/**
 * The seven steps as buttons: done in teal with ✓, the current one in yellow with its number, the rest
 * outlined. Under them, the current step's name and "Step 3 of 7 · tap a step to jump". Tapping goes there.
 */
@Composable
fun StepBar(guided: GuidedController) {
    val current = StepBar.shown(guided.progress.step)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
            GuideStep.stepper.forEachIndexed { i, step ->
                val now = step == current
                val done = !now && step in guided.progress.done
                val shape = RoundedCornerShape(8.dp)
                val fill = if (now) Theme.accent else if (done) Theme.tealBright else Theme.card
                val fg = if (now || done) Theme.background else Theme.textSecondary
                var m = Modifier
                    .weight(1f)
                    .heightIn(min = 30.dp)
                    .background(fill, shape)
                if (!now && !done) m = m.border(1.dp, Theme.hairline, shape)
                Box(
                    m
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                            guided.go(step)
                        }
                        .clearAndSetSemantics {
                            contentDescription = "${step.label}, step ${i + 1}" +
                                if (now) ", current" else if (done) ", done" else ""
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (done) {
                        Icon(symbol("checkmark"), contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
                    } else {
                        Text("${i + 1}", style = Type.captionStrong, color = fg)
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(current.label, style = Type.captionBrand, color = Theme.accent)
            Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
            Text(StepBar.position(guided.progress.step) + " · tap a step to jump", style = Type.small,
                color = Theme.textSecondary)
        }
    }
}

// MARK: - 1. Set up

/** Getting ready, one idea per page: the format, the videos, the TV's settings, the room, the phone, the camera. */
@Composable
fun SetupStep(guided: GuidedController, openCamera: () -> Unit) {
    val mode = guided.mode
    val brand = guided.tv.brand
    val brandName = if (brand == Brand.OTHER) "TV" else brand.label

    val formatText = when (mode) {
        SignalMode.SDR -> "Start with everyday TV. Each format keeps its own settings on the TV."
        SignalMode.HDR10 -> "The TV has separate settings for HDR, so it’s calibrated after SDR."
        SignalMode.HDR10P, SignalMode.DV ->
            "Advanced: do SDR and HDR10 first. ${mode.label} has its own settings on the TV."
    }

    val pages = buildList {
        add(WalkPage(symbol = "rectangle.stack", tint = mode.tint, title = "Calibrating ${mode.label}",
            text = formatText, art = { FormatOrderArt(current = mode) }))
        add(WalkPage(
            symbol = if (mode.onYouTube) "play.circle" else "externaldrive", tint = mode.tint,
            title = if (mode.onYouTube) "The ${mode.label} videos are on YouTube" else "Download the ${mode.label} patterns",
            text = "",
            art = { GuidedSourceHero(mode = mode) },
            extra = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    GuidedNoticeCard(
                        title = if (mode.onYouTube) "Only play videos whose title starts with ‘${mode.packPrefix}’"
                        else "Only play files whose name starts with ‘${mode.packPrefix}’",
                        titleColour = Theme.accent,
                        text = (if (mode == SignalMode.SDR) "The TV should show no HDR badge. "
                        else "The TV should show its ${mode.badge} badge. ") +
                            "You start each video with the TV's remote.",
                    )
                    PatternLinkButton(mode = mode)
                }
            },
        ))
        add(WalkPage(
            symbol = "", tint = Theme.accent,
            title = if (guided.tv.isDemo) "On a real TV: picture settings" else "Set up the $brandName",
            text = "On the TV, open Picture and match these.",
            extra = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    GuidedSettingsList(items = brand.setup(mode))
                    Text("Menu names may vary a little by $brandName model.", style = Type.captionBrand,
                        color = Theme.textSecondary)
                }
            },
        ))
        add(WalkPage(
            symbol = "moon", title = "Dim the room", text = "", art = { DarkRoomArt() },
            extra = {
                Text(
                    "Lights off, curtains closed, and the TV on for 20–30 minutes first: screens shift as they warm up.",
                    style = Type.bodyBrand.copy(lineHeight = 22.sp), color = Theme.textSecondary,
                )
            },
        ))
        add(WalkPage(
            symbol = "iphone", title = "Place the phone",
            text = "Keep the yellow box inside the grey patch, and don’t move the phone once you start.",
            art = { PlacementArt() },
            lead = "A hand's length (15–30 cm) from the TV, facing its middle, not touching the screen.",
        ))
        if (!guided.tv.isDemo) {
            add(WalkPage(
                symbol = "", title = "Get the camera ready", text = "",
                extra = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.clickable(remember { MutableInteractionSource() }, indication = null,
                            role = Role.Button, onClick = openCamera)) {
                            CameraStatusRow()
                        }
                        Text("Next, the app measures the TV as it is, for your before and after. About two minutes.",
                            style = Type.captionBrand, color = Theme.textSecondary)
                    }
                },
            ))
        }
    }

    key(mode) {
        Walkthrough(pages = pages, finishTitle = "Start: measure the TV as it is", section = "Getting ready") {
            guided.go(GuideStep.BEFORE, markDone = GuideStep.SETUP)
        }
    }
}

/** The big "where the videos come from" instruction on the teal panel. */
@Composable
private fun GuidedSourceHero(mode: SignalMode) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Icon(symbol(if (mode.onYouTube) "play.circle" else "externaldrive"), contentDescription = null,
            tint = Theme.tealBright, modifier = Modifier.size(36.dp))
        Text(
            if (mode.onYouTube) "Play the videos in your TV’s YouTube app, or cast from your phone."
            else "Copy the ${mode.packFolder} folder to a USB stick and play the files with the TV’s media player.",
            style = Type.brand(20f, FontWeight.SemiBold),
            color = Color.White,
        )
    }
}

/** A card with a coloured title and a line of secondary text. */
@Composable
private fun GuidedNoticeCard(title: String, titleColour: Color, text: String, big: Boolean = false) {
    Card(spacing = 12.dp) {
        Text(title, style = if (big) Type.section else Type.bodyStrong, color = titleColour)
        Text(text, style = if (big) Type.bodyBrand else Type.captionBrand, color = Theme.textSecondary)
    }
}

/** One TV setting: "Picture mode" over "Filmmaker Mode". */
private data class GuidedSettingRow(val label: String?, val value: String)

/** The TV settings to make, ticked: each "Name: value" from the brand's list as a label over its value. */
@Composable
private fun GuidedSettingsList(items: List<String>) {
    val rows = buildList {
        for (item in items) {
            // "Gamma: BT.1886, Colour Temperature: Warm 50" holds two settings.
            val parts = if (item.split(": ").size > 2) item.split(", ") else listOf(item)
            for (part in parts) {
                val r = part.indexOf(": ")
                if (r >= 0) add(GuidedSettingRow(part.substring(0, r), part.substring(r + 2)))
                else add(GuidedSettingRow(null, part))
            }
        }
    }
    Card(spacing = 0.dp) {
        rows.forEach { row ->
            Row(
                Modifier.padding(vertical = 10.dp).semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(symbol("checkmark.square"), contentDescription = null, tint = Theme.tealBright,
                    modifier = Modifier.padding(top = if (row.label == null) 0.dp else 8.dp).size(18.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (row.label != null) Text(row.label, style = Type.small, color = Theme.textSecondary)
                    Text(row.value, style = if (row.label == null) Type.bodyBrand else Type.bodyStrong, color = Color.White)
                }
            }
        }
    }
}

/** Opens this format's YouTube playlist, or its pattern download. */
@Composable
fun PatternLinkButton(mode: SignalMode) {
    val links = LocalApp.current.links.links
    val context = LocalContext.current
    if (mode.onYouTube) {
        val url = links.playlist(mode) ?: return
        SecondaryButton(onClick = { openUrl(context, url) }) {
            Icon(symbol("play.rectangle"), contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Open the ${mode.label} playlist")
        }
    } else {
        val url = links.download(mode) ?: return
        SecondaryButton(onClick = { openUrl(context, url) }) {
            Icon(symbol("square.and.arrow.down"), contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Download the ${mode.label} patterns")
        }
    }
}

/** Where this mode's pattern videos come from: YouTube (SDR, HDR10) or a download (HDR10+, Dolby Vision). */
@Composable
fun PatternSourceCard(mode: SignalMode) {
    val appName = stringResource(R.string.app_name)
    Card(spacing = 12.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ModeBadge(mode = mode)
            Text(if (mode.onYouTube) "Patterns on YouTube" else "Pattern files to download",
                style = Type.headlineBrand, color = Color.White)
        }
        Text(
            if (mode.onYouTube) {
                "Play the ${mode.label} patterns from the TV Calibrator playlist on the Slidebean channel, in the TV’s " +
                    "YouTube app or cast from your phone, at the highest quality."
            } else {
                "Copy the ${mode.packFolder} folder to a USB stick and play the files with the TV’s media player."
            },
            style = Type.captionBrand, color = Theme.textSecondary,
        )
        PatternLinkButton(mode = mode)
    }
}

// MARK: - Measuring a quick-check video (Before, Check, After)

/** Ready to measure: the headline, the video to play and how. Pins "Measure (about two minutes)". */
@Composable
private fun GuidedQuickReady(title: String, file: String, mode: SignalMode, measure: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = Type.stepTitleStyle)
        PlayFileCard(file = file, mode = mode, fromStart = true)
        OrderStrip(steps = listOf("Tap Measure", "Start the video on the TV"))
        Text(
            if (mode.onYouTube) "Keep the phone still. Ads are fine: the app waits for the pattern." else "Keep the phone still.",
            style = Type.captionBrand, color = Theme.textSecondary,
        )
        PinnedActionOrInline("Measure (about two minutes)", action = measure)
    }
}

/**
 * The measurement failed, most often because a video for another format (or another video) played.
 * Pins "I'll replay the SDR video", which goes back to the ready state.
 */
@Composable
private fun GuidedWrongVideo(file: String, mode: SignalMode, why: String, replay: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Is the ${mode.label} video playing?", style = Type.stepTitleStyle)
        Card(spacing = 12.dp) {
            Row(
                Modifier.fillMaxWidth().clearAndSetSemantics {
                    contentDescription = "${mode.label} expected. Nothing was saved."
                },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModeBadge(mode = mode)
                Icon(symbol("arrow.right"), contentDescription = null, tint = Theme.textSecondary,
                    modifier = Modifier.size(17.dp))
                Text("?", style = Type.badge, color = Theme.limit,
                    modifier = Modifier
                        .background(Theme.limit.copy(alpha = 0.13f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp))
                Spacer(Modifier.weight(1f))
                Text("Nothing was saved", style = Type.small, color = Theme.textSecondary)
            }
            Text("Replay the ${mode.label} video from the start, then measure again.", style = Type.bodyStrong,
                color = Color.White)
            Text(why, style = Type.captionBrand, color = Theme.textSecondary)
        }
        PlayFileCard(file = file, mode = mode, fromStart = true)
        PinnedActionOrInline("I’ll replay the ${mode.label} video", action = replay)
    }
}

private val SweepRunner.failure: String?
    get() = (phase as? SweepRunner.Phase.Failed)?.message

// The phone's margin of error (ProfileService.marginNote / marginDE in the original, not in the Kotlin
// ProfileService yet). The Android profile is always the approximate one (isStandard), so the margin is 3 dE.
private fun marginNote(app: AppModel, @Suppress("UNUSED_PARAMETER") panel: PanelType): String? {
    if (app.profiles.profile == null) return null
    return "This phone has no camera profile of its own yet and uses the standard one, so its readings are " +
        "approximate: differences under about 3 dE may not be real."
}

private fun marginDE(app: AppModel, @Suppress("UNUSED_PARAMETER") panel: PanelType): Double? =
    if (app.profiles.profile == null) null else 3.0

// MARK: - 2 + 7. Before / after

@Composable
fun QuickStep(guided: GuidedController, which: QuickWhich) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var remeasure by remember { mutableStateOf(false) }

    val s = guided.lastSession
    // The step's own result stays on screen when coming back to it (until a new session starts).
    val result: Pair<Measurements, Measurements?>? = when {
        s == null -> null
        which == QuickWhich.BEFORE -> s.before?.let { it to null }
        s.after != null -> s.after to s.before
        else -> null
    }
    val failure = guided.runner.failure

    fun measure() {
        remeasure = true
        // TODO(port): unlike the Swift Task, this coroutine is cancelled if the step leaves the screen.
        scope.launch {
            guided.measureQuick(which)
            val phase = guided.runner.phase
            // Back to the results when it worked (or was cancelled); a failure stays on screen.
            if (phase == SweepRunner.Phase.Done || phase == SweepRunner.Phase.Cancelled) remeasure = false
            // The final results arrive with the success chime.
            if (which == QuickWhich.AFTER && phase == SweepRunner.Phase.Done) app.chime()
        }
    }

    // A failure from another step's measurement doesn't belong here.
    LaunchedEffect(Unit) {
        if (guided.runner.failure != null) guided.runner.reset()
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (guided.runner.running) {
            MeasuringCard(runner = guided.runner, file = guided.quickFile, mode = guided.mode)
        } else if (failure != null) {
            GuidedWrongVideo(file = guided.quickFile, mode = guided.mode, why = failure) { guided.runner.reset() }
        } else if (result != null && !remeasure) {
            QuickResults(guided, which, result.first, result.second, onMeasureAgain = { remeasure = true })
        } else {
            GuidedQuickReady(
                title = if (which == QuickWhich.BEFORE) "Measure your starting point" else "One final check",
                file = guided.quickFile, mode = guided.mode,
            ) { measure() }
        }
    }
}

@Composable
private fun QuickResults(
    guided: GuidedController,
    which: QuickWhich,
    now: Measurements,
    before: Measurements?,
    onMeasureAgain: () -> Unit,
) {
    val app = LocalApp.current
    val report = remember(now, guided.hdr) { Analysis.analyse(now, guided.hdr) }
    val limits = guided.progress.limits.filter { it in Patterns.colourIDs }
        .map { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
    val panel = if (guided.tv.isDemo) null else guided.tv.panel
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (which == QuickWhich.BEFORE) {
            Text("Your starting point", style = Type.stepTitleStyle)
            if (!guided.tv.isDemo) {
                val note = GamutCheck.note(report, guided.mode, guided.tv.brand)
                if (note != null) TipRow(tone = TipTone.limit, text = note)
            }
            ReportSummary(
                report = report, hdr = guided.hdr, before = null,
                marginNote = panel?.let { marginNote(app, it) },
            ) {
                ReportHero(report = report, before = null, hdr = guided.hdr, margin = panel?.let { marginDE(app, it) })
            }
        } else {
            val beforeReport = remember(before, guided.hdr) { before?.let { Analysis.analyse(it, guided.hdr) } }
            ResultsView(
                report = report, before = beforeReport, hdr = guided.hdr, mode = guided.mode, tvName = guided.tv.name,
                date = guided.lastSession?.finished ?: Instant.now(),
                margin = panel, showsNewsletter = true,
            )
            if (!guided.tv.isDemo) {
                // TODO(port): NotificationsCard(tvID, tvName), the recheck reminder, once notifications are ported.
            }
            if (limits.isNotEmpty()) {
                TipRow(tone = TipTone.limit,
                    text = "${limits.joinToString(", ")}: as close as this TV gets (its controls were at their limit).")
            }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            LinkButton("Measure again", onClick = onMeasureAgain)
        }
        PinnedActionOrInline(if (which == QuickWhich.BEFORE) "Next: fix the white balance" else "Start a new session") {
            if (which == QuickWhich.BEFORE) guided.go(GuideStep.BRIGHT, markDone = GuideStep.BEFORE)
            else guided.newSession()
        }
    }
}

/** The newsletter link from the pattern links, or the built-in one (PatternLinks.newsletterURL in the original). */
private val PatternLinks.newsletterURL: String
    get() = newsletter?.takeIf { it.startsWith("https://") }
        ?: "https://untangled.slidebean.com/?utm_source=tv-calibrator&utm_medium=app&utm_campaign=calibration-complete"

/**
 * Shown when a calibration is finished: subscribe to Untangled, the Slidebean newsletter, for early access
 * to tools like this one. Hidden for good once tapped.
 */
@Composable
fun NewsletterCard() {
    val app = LocalApp.current
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("app", Context.MODE_PRIVATE) }
    var opened by remember { mutableStateOf(prefs.getBoolean("untangled.opened", false)) }
    if (opened) return
    val url = app.links.links.newsletterURL
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Theme.accent.copy(alpha = 0.08f), shape)
            .border(1.5.dp, Theme.accent, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(symbol("envelope.fill"), contentDescription = null, tint = Theme.accent, modifier = Modifier.size(14.dp))
            Text("UNTANGLED BY SLIDEBEAN", style = Type.badge.copy(letterSpacing = 0.8.sp), color = Theme.accent)
        }
        Text("Access our tools before anyone else", style = Type.section, color = Color.White,
            modifier = Modifier.widthIn(max = 230.dp))
        PrimaryButton("Subscribe to Untangled", onClick = {
            openUrl(context, url)
            opened = true
            prefs.edit().putBoolean("untangled.opened", true).apply()
        })
    }
}

// MARK: - 5. Check the greyscale

/** The static functions of the Swift CheckStep. */
object CheckStep {
    fun onTarget(g: GreyResult): Boolean = kotlin.math.abs(g.redVsGreen) <= 0.015 && kotlin.math.abs(g.blueVsGreen) <= 0.015

    /** The headline from the average grey dE. */
    fun verdict(average: Double): String {
        if (!average.isFinite()) return "Here are your greys"
        if (average < 1) return "On target"
        if (average < 2) return "A very good result"
        if (average < 3) return "A good result"
        return "Some greys need another pass"
    }

    fun tip(off: List<GreyResult>, of: Int): String {
        if (off.isEmpty()) return "Every grey is on target."
        val ends = mutableListOf<String>()
        if (off.any { it.level <= 0.4 }) ends.add("dark end")
        if (off.any { it.level > 0.4 && it.level < 0.55 }) ends.add("middle greys")
        if (off.any { it.level >= 0.55 }) ends.add("bright end")
        val which = ends.joinToString(" and the ")
        if (off.size * 2 <= of) return "Most greys are on target. The $which could be a little closer."
        return "The $which could be closer: redo it below, or fine-tune."
    }
}

@Composable
fun CheckStep(guided: GuidedController) {
    val scope = rememberCoroutineScope()
    val failure = guided.runner.failure
    /** Fine-tuning needs a TV with 10- or 20-point white balance. */
    val canFineTune = !(guided.tv.multipointKnown && guided.tv.multipoint == Multipoint.NONE)
    val fineTuneTitle = if (guided.tv.multipoint != Multipoint.NONE && !guided.finderReady && !guided.finderSkipped) {
        "Find my TV’s points"
    } else {
        "Fine-tune a grey (optional)"
    }

    fun measure() {
        // TODO(port): unlike the Swift Task, this coroutine is cancelled if the step leaves the screen.
        scope.launch { guided.measureQuick(QuickWhich.CHECK) }
    }

    // A failure from another step's measurement doesn't belong here.
    LaunchedEffect(Unit) {
        if (guided.runner.failure != null) guided.runner.reset()
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val rep = guided.checkReport
        if (guided.runner.running) {
            MeasuringCard(runner = guided.runner, file = guided.quickFile, mode = guided.mode)
        } else if (failure != null) {
            GuidedWrongVideo(file = guided.quickFile, mode = guided.mode, why = failure) { guided.runner.reset() }
        } else if (rep != null) {
            CheckSummary(guided, rep, canFineTune, fineTuneTitle, measure = { measure() })
        } else {
            GuidedQuickReady(title = "Check the greys", file = guided.quickFile, mode = guided.mode) { measure() }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (canFineTune) {
                    LinkButton(fineTuneTitle, onClick = { guided.go(GuideStep.FINE) }, style = Type.captionStrong)
                }
                Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
                LinkButton("Next: colours", onClick = { guided.go(GuideStep.COLOURS, markDone = GuideStep.CHECK) },
                    style = Type.captionStrong)
            }
        }
    }
}

@Composable
private fun CheckSummary(
    guided: GuidedController,
    rep: Report,
    canFineTune: Boolean,
    fineTuneTitle: String,
    measure: () -> Unit,
) {
    val app = LocalApp.current
    val greys = rep.greys.filter { guided.hdr || it.level >= 0.099 }
    val off = greys.filter { !CheckStep.onTarget(it) }
    val average = Analysis.greyAverage(rep, guided.hdr)
    val cell = Type.captionBrand.copy(color = Color.White, fontFeatureSettings = TextStyleTnum)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(CheckStep.verdict(average), style = Type.stepTitleStyle, modifier = Modifier.weight(1f))
            Text("dE ${fmt(average, 1)}", style = Type.captionStrong.copy(fontFeatureSettings = TextStyleTnum),
                color = Score.tint(average), modifier = Modifier.padding(bottom = 3.dp))
        }
        Card {
            GreyBalanceChart(report = rep, hdr = guided.hdr)
        }
        TipRow(tone = if (off.isEmpty()) TipTone.ok else TipTone.todo, text = CheckStep.tip(off, greys.size))
        Card(spacing = 0.dp) {
            val heading = Type.tiny.copy(color = Theme.textSecondary)
            GridTable(columns = 5, horizontalSpacing = 6.dp, minWidths = listOf(43.dp, 48.dp, 48.dp, 40.dp, 0.dp)) {
                listOf("Grey", "Red", "Blue", "dE", "").forEach {
                    Text(it, style = heading, modifier = Modifier.padding(bottom = 4.dp))
                }
                greys.forEach { g ->
                    Box(Modifier.heightIn(min = 42.dp), contentAlignment = Alignment.CenterStart) {
                        Text(shortName(g.name), style = cell)
                    }
                    Text(typographicMinus(signed(g.redVsGreen * 100)), style = cell)
                    Text(typographicMinus(signed(g.blueVsGreen * 100)), style = cell)
                    Text(fmt(g.de, 1), style = cell)
                    GreyAction(guided, g)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinkButton("Measure again", onClick = measure, style = Type.captionStrong)
            Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
            if (canFineTune) {
                LinkButton(fineTuneTitle, onClick = { guided.go(GuideStep.FINE) }, style = Type.captionStrong)
            }
        }
        ScoreInfo(marginNote = if (guided.tv.isDemo) null else marginNote(app, guided.tv.panel))
        PinnedActionOrInline("Continue to colours") { guided.go(GuideStep.COLOURS, markDone = GuideStep.CHECK) }
    }
}

@Composable
private fun GreyAction(guided: GuidedController, g: GreyResult) {
    if (CheckStep.onTarget(g)) {
        Text("✓", style = Type.tiny, color = Theme.tealBright,
            modifier = Modifier.semantics { stateDescription = "On target"; contentDescription = "On target" })
    } else {
        val target = if (g.level >= 0.55) GuideStep.BRIGHT else if (g.level <= 0.4) GuideStep.DARK else GuideStep.FINE
        LinkButton(
            when (target) {
                GuideStep.BRIGHT -> "Redo bright end"
                GuideStep.DARK -> "Redo dark end"
                else -> "Fine-tune"
            },
            onClick = { guided.go(target) },
            style = Type.tiny.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

/**
 * A small table like SwiftUI's Grid: the children are the cells, row by row; each column is as wide as
 * its widest cell (at least `minWidths`), cells are leading-aligned and centred vertically in their row.
 */
@Composable
private fun GridTable(
    columns: Int,
    horizontalSpacing: Dp,
    minWidths: List<Dp>,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = Modifier.fillMaxWidth().wrapContentSize(Alignment.TopStart)) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val gap = horizontalSpacing.roundToPx()
        val widths = IntArray(columns) { c -> minWidths.getOrNull(c)?.roundToPx() ?: 0 }
        placeables.forEachIndexed { i, p -> val c = i % columns; widths[c] = max(widths[c], p.width) }
        val rows = (placeables.size + columns - 1) / columns
        val heights = IntArray(rows) { r ->
            (0 until columns).maxOf { c -> placeables.getOrNull(r * columns + c)?.height ?: 0 }
        }
        val total = widths.sum() + gap * (columns - 1)
        layout(max(total, constraints.minWidth).coerceAtMost(constraints.maxWidth), heights.sum()) {
            var y = 0
            for (r in 0 until rows) {
                var x = 0
                for (c in 0 until columns) {
                    val p = placeables.getOrNull(r * columns + c) ?: break
                    p.placeRelative(x, y + (heights[r] - p.height) / 2)
                    x += widths[c] + gap
                }
                y += heights[r]
            }
        }
    }
}

/** "-1.1" -> "−1.1" (a real minus sign). */
private fun typographicMinus(s: String): String = s.replace("-", "−")

/** "50% GREY" -> "50%", "PQ 50% GREY (92 nits)" -> "50% (92 nits)". */
fun shortName(name: String): String =
    name.replace("PQ ", "").replace(" GREY", "")
        .replace("100% WHITE", "100%").replace("100 nit WHITE", "100 nits")
