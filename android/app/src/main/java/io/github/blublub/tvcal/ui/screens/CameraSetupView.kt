// Port of TV Calibrator/Views/CameraSetupView.swift (TV Calibrator by Slidebean, https://github.com/slidebean/tv-calibrator, CC BY-NC 4.0). Changed: translated to Kotlin/Compose.
package io.github.blublub.tvcal.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.blublub.tvcal.camera.CameraMeter
import io.github.blublub.tvcal.camera.CameraXYZMeter
import io.github.blublub.tvcal.engine.LiveAnalysis
import io.github.blublub.tvcal.engine.fixed
import io.github.blublub.tvcal.measure.FreeMeter
import io.github.blublub.tvcal.measure.uiScope
import io.github.blublub.tvcal.profiles.ProfileService
import io.github.blublub.tvcal.ui.CameraViewfinder
import io.github.blublub.tvcal.ui.LocalApp
import io.github.blublub.tvcal.ui.LocalCameraPermission
import io.github.blublub.tvcal.ui.LocalNav
import io.github.blublub.tvcal.ui.Route
import io.github.blublub.tvcal.ui.components.BalanceBar
import io.github.blublub.tvcal.ui.components.GamutChart
import io.github.blublub.tvcal.ui.components.GamutDot
import io.github.blublub.tvcal.ui.components.WhiteTarget
import io.github.blublub.tvcal.ui.theme.Card
import io.github.blublub.tvcal.ui.theme.Eyebrow
import io.github.blublub.tvcal.ui.theme.LinkButton
import io.github.blublub.tvcal.ui.theme.PrimaryButton
import io.github.blublub.tvcal.ui.theme.ScreenWithBottomAction
import io.github.blublub.tvcal.ui.theme.screenBackground
import io.github.blublub.tvcal.ui.theme.SecondaryButton
import io.github.blublub.tvcal.ui.theme.Theme
import io.github.blublub.tvcal.ui.theme.TipRow
import io.github.blublub.tvcal.ui.theme.TipTone
import io.github.blublub.tvcal.ui.theme.Type
import io.github.blublub.tvcal.ui.theme.fmt
import io.github.blublub.tvcal.ui.theme.markdown
import io.github.blublub.tvcal.ui.theme.signed
import io.github.blublub.tvcal.ui.theme.symbol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Camera work started here outlives the screen (like the original's `Task {}`). */
private val cameraSetupScope = uiScope()

/** What a test reading gave. */
private sealed class CameraTestOutcome {
    data class Reading(val luminance: Double, val x: Double, val y: Double, val uneven: Boolean) : CameraTestOutcome()
    data class Failed(val why: String) : CameraTestOutcome()
}

/** Where the setup is: one main (yellow) action per stage. */
private enum class CameraStage { start, darkness, placement, ready }

/**
 * A screen title with a back button (the original's navigation bar title). Back calls [onBack].
 */
@Composable
private fun ScreenTitleBar(title: String, onBack: (() -> Unit)?) {
    Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Theme.tealBright)
            }
        }
        Text(title, style = Type.headlineBrand, color = Color.White)
    }
}

/** Getting the camera ready: profile, start, darkness, placement (Figma: 05 Camera setup). */
@Composable
fun CameraSetupView(
    camera: CameraMeter,
    profiles: ProfileService,
    onDismiss: () -> Unit = LocalNav.current.let { nav -> { nav.pop() } },
) {
    val app = LocalApp.current
    val permission = LocalCameraPermission.current
    val running by camera.running.collectAsState()
    val busy by camera.busy.collectAsState()
    val message by camera.message.collectAsState()
    val dark by camera.dark.collectAsState()
    val logLines by camera.logLines.collectAsState()
    var test by remember { mutableStateOf<CameraTestOutcome?>(null) }
    var showLog by remember { mutableStateOf(false) }
    /** Measuring the darkness again (after a system update, say) while a measurement is already kept. */
    var redoDark by remember { mutableStateOf(false) }
    /** "Start the camera" was tapped before the camera permission was granted. */
    var startWhenGranted by remember { mutableStateOf(false) }

    val ready = running && dark != null
    val testPassed = (test as? CameraTestOutcome.Reading)?.let { !it.uneven } ?: false
    val stage = when {
        !running -> CameraStage.start
        dark == null || redoDark -> CameraStage.darkness
        testPassed -> CameraStage.ready
        else -> CameraStage.placement
    }

    LaunchedEffect(running) {
        if (!running) {
            test = null
            redoDark = false
        }
    }
    LaunchedEffect(permission.granted, startWhenGranted) {
        if (permission.granted && startWhenGranted) {
            startWhenGranted = false
            cameraSetupScope.launch { app.startCamera() }
        }
    }

    /**
     * The camera's own message, when it says something the stages don't: progress (teal) or a problem
     * (orange), such as light getting in while measuring the darkness.
     */
    val messageTip: Pair<TipTone, String>? = run {
        val m = message
        val quiet = listOf("The camera is off.", "Cover the lens and measure the darkness once.", "Ready to measure.",
            "Ready. Point the box at the centre of the TV.")
        // "Measuring with the lens covered…" would contradict the screen-down instructions; the button
        // being busy says enough.
        if (m.isEmpty() || m in quiet || m.startsWith("Measuring")) null
        else (if (m.endsWith("…")) TipTone.todo else TipTone.limit) to m
    }

    /** The steps already done, shown above the next one. */
    val doneSoFar = if (profiles.profile != null) "✓ Profile · ✓ camera locked" else "✓ Camera locked"

    val settings = camera.settings
    val rawSize = camera.engine.rawSize
    val sizeText = if (rawSize == null) "" else ", RAW photos ${rawSize.width}x${rawSize.height}"
    val lockedText = "Locked: back main camera, ISO ${(settings.iso ?: 0.0).toInt()}, focus closest$sizeText"
    /** One line for the ready summary. */
    // Android: no aperture lock (phone lenses have a fixed aperture), so no aperture here.
    val lockedSummary = "Settings locked · ISO ${(settings.iso ?: 0.0).toInt()} · focus fixed"

    /** "4 setup checks complete" / "2 of 4 setup checks complete". */
    val checksDone = listOf(profiles.profile != null, running, running && dark != null && !redoDark, testPassed).count { it }
    val checksText = if (checksDone == 4) "4 setup checks complete" else "$checksDone of 4 setup checks complete"

    val darknessActionTitle =
        if (redoDark || messageTip?.first == TipTone.limit) "Measure the darkness again" else "Measure the darkness"

    fun measureDark() {
        test = null
        cameraSetupScope.launch {
            camera.measureDark()
            if (camera.message.value.startsWith("Ready")) redoDark = false
        }
    }

    fun runTest() {
        val p = profiles.profile ?: return
        cameraSetupScope.launch {
            val meter = CameraXYZMeter(camera, p, checkSettings = !profiles.isStandard)
            test = try {
                val r = meter.read()
                val xyz = r.xyz
                if (xyz == null) {
                    CameraTestOutcome.Failed("No reading. Is the TV showing something bright in the box?")
                } else {
                    val a = LiveAnalysis.of(xyz, hdr = false)
                    val uneven = (camera.lastReading.value?.uneven ?: 0.0) > 0.05
                    CameraTestOutcome.Reading(xyz.y, a.xy.x, a.xy.y, uneven)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CameraTestOutcome.Failed(e.message ?: e.toString())
            }
        }
    }

    ScreenWithBottomAction(
        bottom = {
            when (stage) {
                CameraStage.start -> PrimaryButton("Start the camera", {
                    if (permission.granted) {
                        cameraSetupScope.launch { app.startCamera() }
                    } else {
                        startWhenGranted = true
                        permission.request()
                    }
                }, enabled = profiles.profile != null && !busy)
                CameraStage.darkness -> PrimaryButton(darknessActionTitle, { measureDark() }, enabled = !busy)
                CameraStage.placement -> PrimaryButton("Test reading", { runTest() },
                    enabled = ready && !busy && profiles.profile != null)
                CameraStage.ready -> PrimaryButton("Done", onDismiss)
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().screenBackground().padding(pad)) {
            ScreenTitleBar("Set up the camera", onDismiss)
            // The preview stays put while the steps scroll underneath.
            Column(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CameraViewfinder(camera, running, Modifier.height(240.dp))
                Text("Blurry on purpose: it averages out the TV's pixels", style = Type.small, color = Theme.textSecondary,
                    modifier = Modifier.fillMaxWidth())
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (profiles.isStandard) {
                    TipRow(tone = TipTone.limit,
                        text = "Using the approximate profile from the camera's own colour data: readings are approximate.")
                }
                when (stage) {
                    CameraStage.start -> {
                        // Steps 1 and 2: the camera profile, then starting the camera.
                        messageTip?.let { TipRow(tone = it.first, text = it.second) }
                        StepCard("1 · Camera profile") {
                            if (profiles.profile != null) {
                                // Android: no trained profiles; the summary explains the approximate one.
                                Text(profiles.summary, style = Type.bodyBrand, color = Color.White)
                            } else {
                                Text(profiles.summary, style = Type.bodyBrand, color = Color.White)
                                SecondaryButton("Check again", { profiles.refresh(camera.engine) })
                            }
                        }
                        StepCard("2 · Start camera") {
                            Text("Exposure and focus stay locked after you start.", style = Type.bodyBrand, color = Color.White)
                        }
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Next: measure darkness", style = Type.bodyBrand, color = Color.White)
                                Text("Then place your phone on a stand", style = Type.captionBrand, color = Theme.textSecondary)
                            }
                            Icon(symbol("arrow.down"), contentDescription = null, tint = Theme.tealBright, modifier = Modifier.size(17.dp))
                        }
                    }
                    CameraStage.darkness -> {
                        // Step 3: the sensor's baseline with the lens in total darkness.
                        StepCard(doneSoFar) {
                            Text("3 · Measure darkness", style = Type.cardTitle.copy(letterSpacing = (-0.6).sp), color = Color.White)
                            DarknessIllustration()
                            Text(
                                markdown("This needs **total darkness**: lay the phone screen-up on a table, so the camera is pressed flat against it. A fingertip isn't enough."),
                                style = Type.captionBrand, color = Theme.textSecondary,
                            )
                            if (redoDark && !busy) {
                                LinkButton("Keep the last measurement", { redoDark = false }, style = Type.captionStrong)
                            }
                        }
                        messageTip?.let { TipRow(tone = it.first, text = it.second) }
                    }
                    CameraStage.placement -> {
                        // Step 4: the phone on a stand, pointing at the middle of the TV, and a test reading.
                        Text("4 · Place the phone", style = Type.stepTitleStyle)
                        PlacementIllustration()
                        Text(
                            "A hand's length (15–30 cm) from the TV, square to it, level with the centre. The grey patch should fill the yellow box with room to spare.",
                            style = Type.bodyBrand, color = Theme.textSecondary,
                        )
                        messageTip?.let { TipRow(tone = it.first, text = it.second) }
                        when (val t = test) {
                            is CameraTestOutcome.Reading -> {
                                TipRow(tone = TipTone.ok, text = "Test reading received")
                                if (t.uneven) {
                                    TipRow(tone = TipTone.limit, text = "The box isn't evenly lit: move closer so the patch fills it.")
                                }
                                Card {
                                    Text("${fmt(t.luminance, if (t.luminance < 10) 2 else 1)} cd/m²",
                                        style = Type.bodyStrong.copy(fontFeatureSettings = "tnum"), color = Color.White)
                                    Text("x ${fixed(t.x, 4)} · y ${fixed(t.y, 4)}",
                                        style = Type.captionBrand.copy(fontFeatureSettings = "tnum"), color = Theme.textSecondary)
                                }
                            }
                            is CameraTestOutcome.Failed -> TipRow(tone = TipTone.limit, text = t.why)
                            null -> {}
                        }
                    }
                    CameraStage.ready -> {
                        // Everything done: the green summary.
                        Card {
                            Text(doneSoFar, style = Type.small, color = Theme.green)
                            Text("✓ Darkness measured", style = Type.bodyBrand, color = Theme.green)
                            Text("✓ Phone placement checked", style = Type.bodyBrand, color = Theme.green)
                            Text(lockedSummary, style = Type.captionBrand, color = Theme.textSecondary)
                        }
                        TipRow(tone = TipTone.ok, text = "Ready to measure. Keep the phone still.")
                    }
                }
                if (stage != CameraStage.start || logLines.isNotEmpty()) {
                    // Camera log
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val turn by animateFloatAsState(if (showLog) 180f else 0f, tween(200), label = "chevron")
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable(role = Role.Button) { showLog = !showLog }
                                .semantics { stateDescription = if (showLog) "Shown" else "Hidden" },
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Camera log", style = Type.bodyBrand, color = Color.White)
                                Text(checksText, style = Type.captionBrand, color = Theme.textSecondary)
                            }
                            Icon(symbol("chevron.down"), contentDescription = null, tint = Theme.tealBright,
                                modifier = Modifier.size(20.dp).rotate(turn))
                        }
                        AnimatedVisibility(showLog) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (running) {
                                    Text(lockedText, style = Type.captionBrand, color = Theme.textSecondary)
                                    Column {
                                        if (dark != null && stage != CameraStage.darkness) {
                                            LinkButton("Measure the darkness again", {
                                                test = null
                                                redoDark = true
                                            }, style = Type.captionStrong, enabled = !busy)
                                        }
                                        if (stage == CameraStage.ready) {
                                            LinkButton("Test again", { test = null }, style = Type.captionStrong, enabled = !busy)
                                        }
                                        LinkButton("Stop the camera", { camera.stop() }, style = Type.captionStrong, enabled = !busy)
                                    }
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    for (line in logLines.takeLast(40)) {
                                        Text(line, style = Type.tiny.copy(fontFamily = FontFamily.Monospace), color = Theme.textSecondary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


/** A step card: a teal eyebrow ("1 · Camera profile"), then the step. */
@Composable
private fun StepCard(eyebrow: String, content: @Composable ColumnScope.() -> Unit) {
    Card(spacing = 12.dp) {
        Text(eyebrow, style = Type.small, color = Theme.tealBright)
        content()
    }
}

/** The phone lying screen-up on a table, in the dark (design: darkness "Instruction illustration"). */
@Composable
private fun DarknessIllustration() {
    Column(
        Modifier
            .fillMaxWidth()
            .height(100.dp)
            .background(Theme.surface, RoundedCornerShape(18.dp))
            .clearAndSetSemantics {
                contentDescription = "The phone lying screen-up on a table, its back camera pressed flat against it"
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(symbol("moon"), contentDescription = null, tint = Theme.accent,
            modifier = Modifier.padding(top = 10.dp).size(28.dp).padding(2.dp))
        Box(
            Modifier
                .padding(top = 10.dp)
                .size(width = 160.dp, height = 18.dp)
                .background(Theme.background, RoundedCornerShape(8.dp))
                .border(2.dp, Theme.tealBright, RoundedCornerShape(8.dp)),
        )
        Box(
            Modifier
                .padding(top = 2.dp, start = 20.dp, end = 20.dp)
                .fillMaxWidth()
                .height(2.dp)
                .background(Theme.tealBright),
        )
        Text("Screen facing up", style = Type.tiny, color = Theme.textSecondary, modifier = Modifier.padding(top = 5.dp))
    }
}

/**
 * The TV with a grey patch in the middle, and the phone on its stand facing it (design: placement
 * "Instruction illustration", 190 dp tall). Drawn on the design's 353 dp canvas, centred.
 */
@Composable
private fun PlacementIllustration() {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(190.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Theme.surface)
            .clearAndSetSemantics {
                contentDescription = "The phone on a stand, 15 to 30 centimetres from the TV, facing the middle of the screen"
            },
    ) {
        val ox = (maxWidth - 353.dp) / 2
        Canvas(Modifier.fillMaxSize()) {
            val p = 1.dp.toPx()
            val o = ox.toPx()
            fun box(x: Float, y: Float, w: Float, h: Float, r: Float, fill: Color, stroke: Color?, sw: Float) {
                drawRoundRect(fill, topLeft = Offset(o + x * p, y * p), size = Size(w * p, h * p), cornerRadius = CornerRadius(r * p))
                if (stroke != null) {
                    val inset = sw * p / 2
                    drawRoundRect(stroke, topLeft = Offset(o + x * p + inset, y * p + inset),
                        size = Size(w * p - 2 * inset, h * p - 2 * inset), cornerRadius = CornerRadius(r * p - inset),
                        style = Stroke(sw * p))
                }
            }
            // TV, its grey centre patch, and its stand.
            box(28f, 20f, 228f, 116f, 7f, Theme.background, Theme.tealBright, 2f)
            box(28f + 76f, 20f + 13f, 76f, 90f, 1f, Color(0x77 / 255f, 0x7D / 255f, 0x7E / 255f), null, 0f)
            drawRect(Theme.tealBright, topLeft = Offset(o + 122 * p, 141 * p), size = Size(40 * p, 3 * p))
            // The phone, its measuring box, and the stand.
            box(270f, 90f, 35f, 62f, 6f, Theme.background, Theme.tealBright, 2f)
            box(270f + 12f, 90f + 25f, 10f, 10f, 1f, Color.Transparent, Theme.accent, 1.5f)
            val x = o + 287.5f * p
            drawLine(Theme.tealBright, Offset(x, 152 * p), Offset(x, 166 * p), 2 * p, StrokeCap.Round)
            drawLine(Theme.tealBright, Offset(x, 166 * p), Offset(o + 268 * p, 176 * p), 2 * p, StrokeCap.Round)
            drawLine(Theme.tealBright, Offset(x, 166 * p), Offset(o + 307 * p, 176 * p), 2 * p, StrokeCap.Round)
        }
        Text("15–30 cm · facing the centre", style = Type.tiny, color = Theme.textSecondary, softWrap = false,
            modifier = Modifier.offset(x = ox + 45.dp, y = 165.dp))
    }
}

/** Whatever the camera sees: white balance or the nearest colour (Figma: 13 Live meter). */
@Composable
fun LiveMeterView() {
    val app = LocalApp.current
    val nav = LocalNav.current
    val meter = remember { FreeMeter() }
    var hdr by remember { mutableStateOf(false) }
    // Read the camera's state so the blocker below follows it.
    val running by app.camera.running.collectAsState()
    val dark by app.camera.dark.collectAsState()
    val blocker = run {
        running; dark
        app.cameraBlocker
    }

    LaunchedEffect(hdr, blocker == null) {
        if (blocker == null) app.freeMeter()?.let { meter.start(it, hdr) }
    }
    DisposableEffect(Unit) { onDispose { meter.stop() } }

    ScreenWithBottomAction(bottom = null) { pad ->
        Column(Modifier.fillMaxSize().screenBackground().padding(pad)) {
            ScreenTitleBar("Live meter") { nav.pop() }
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 2.dp)
                    .semantics { contentDescription = "Targets" }) {
                    listOf(false to "SDR", true to "HDR").forEachIndexed { i, (v, label) ->
                        SegmentedButton(
                            selected = hdr == v,
                            onClick = { hdr = v },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = 2),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = Theme.surface,
                                activeContentColor = Color.White,
                                activeBorderColor = Theme.tealBright,
                                inactiveContainerColor = Color.Transparent,
                                inactiveContentColor = Theme.textSecondary,
                                inactiveBorderColor = Theme.hairline,
                            ),
                            icon = {},
                            modifier = Modifier.heightIn(min = 44.dp),
                        ) { Text(label, style = Type.bodyStrong) }
                    }
                }

                if (blocker != null) {
                    TipRow(tone = TipTone.limit, text = blocker)
                    LinkButton("Set up the camera", { nav.push(Route.Camera) })
                }
                meter.error?.let { TipRow(tone = TipTone.limit, text = it) }
                val a = meter.analysis
                if (a != null) {
                    val bal = a.bal
                    val name = a.name
                    val t = a.tuv
                    if (a.kind == LiveAnalysis.Kind.white && bal != null) {
                        Eyebrow(text = "Live · greyscale")
                        LiveReadingFigures(a)
                        Row(Modifier.fillMaxWidth().heightIn(min = 110.dp), horizontalArrangement = Arrangement.spacedBy(24.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            WhiteTarget(uv = a.uv, d65 = a.d65uv, ok = (a.de ?: 9.0) < 1, modifier = Modifier.size(110.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("White point", style = Type.bodyStrong, color = Color.White)
                                Text("D65 target · dE ${fmt(a.de, 2)}", style = Type.captionBrand.copy(fontFeatureSettings = "tnum"),
                                    color = if ((a.de ?: 9.0) < 1) Theme.green else Theme.textSecondary)
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            BalanceBar(label = "Red", value = bal.x - 1, colour = Theme.red)
                            BalanceBar(label = "Green", value = bal.y - 1, colour = Theme.green)
                            BalanceBar(label = "Blue", value = bal.z - 1, colour = Theme.blue)
                        }
                        a.advice?.let { TipRow(tone = if ((a.de ?: 9.0) < 1) TipTone.ok else TipTone.todo, text = it) }
                    } else if (name != null && t != null) {
                        Eyebrow(text = "Live · colour · ${name.lowercase().replaceFirstChar { it.uppercase() }}")
                        LiveReadingFigures(a)
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            GamutChart(dots = listOf(GamutDot(id = name, uv = a.uv, target = t)), hdr = hdr,
                                modifier = Modifier.widthIn(max = 320.dp).heightIn(max = 225.dp))
                        }
                        Card {
                            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
                                Figure("Hue", "${signed(a.hue)}°", Modifier.weight(1f))
                                Figure("Saturation", "${signed(a.sat)}%", Modifier.weight(1f))
                            }
                        }
                    } else {
                        LiveReadingFigures(a)
                    }
                } else if (blocker == null) {
                    Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = Theme.tealBright, strokeWidth = 2.dp)
                        Text("Reading…", style = Type.bodyBrand, color = Theme.textSecondary)
                    }
                }
                Text("Point the measuring box at a solid patch. Readings stay on this phone.", style = Type.small,
                    color = Theme.textSecondary)
            }
        }
    }
}

/** The light level, big enough to read from across the room, and its chromaticity. */
@Composable
private fun LiveReadingFigures(a: LiveAnalysis) {
    Column(Modifier.semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        BasicText(
            fmt(a.Y, if (a.Y < 10) 2 else 1),
            style = Type.reading(76f).copy(letterSpacing = (-1).sp, color = Color.White),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = (76f * 0.5f).sp, maxFontSize = 76.sp),
        )
        Text("cd/m²", style = Type.brand(17f, FontWeight.Normal), color = Color.White)
        Text("x ${fixed(a.xy.x, 4)} · y ${fixed(a.xy.y, 4)}", style = Type.captionBrand.copy(fontFeatureSettings = "tnum"),
            color = Theme.textSecondary)
    }
}

/** A labelled figure: "Hue" over "+2.4°". */
@Composable
private fun Figure(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = Type.captionBrand, color = Theme.textSecondary)
        BasicText(
            value,
            style = Type.reading(34f).copy(letterSpacing = (-1).sp, color = Color.White, textAlign = TextAlign.Start),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = (34f * 0.6f).sp, maxFontSize = 34.sp),
        )
    }
}
