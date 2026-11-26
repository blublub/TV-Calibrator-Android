// Port of TV Calibrator/Views/RootView.swift (TV Calibrator by Slidebean, https://github.com/slidebean/tv-calibrator, CC BY-NC 4.0). Changed: translated to Kotlin/Compose.
// RootView and Route are not ported (ui/Navigation.kt has the navigation); the Lab entry is left out.
package io.github.blublub.tvcal.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.blublub.tvcal.R
import io.github.blublub.tvcal.camera.CameraMeter
import io.github.blublub.tvcal.engine.Analysis
import io.github.blublub.tvcal.engine.SignalMode
import io.github.blublub.tvcal.profiles.ProfileService
import io.github.blublub.tvcal.storage.GuideStep
import io.github.blublub.tvcal.storage.TVRecord
import io.github.blublub.tvcal.ui.FullSheet
import io.github.blublub.tvcal.ui.LocalApp
import io.github.blublub.tvcal.ui.LocalNav
import io.github.blublub.tvcal.ui.Route
import io.github.blublub.tvcal.ui.components.Score
import io.github.blublub.tvcal.ui.theme.Card
import io.github.blublub.tvcal.ui.theme.IconBubble
import io.github.blublub.tvcal.ui.theme.LinkButton
import io.github.blublub.tvcal.ui.theme.PrimaryButton
import io.github.blublub.tvcal.ui.theme.ScreenWithBottomAction
import io.github.blublub.tvcal.ui.theme.SecondaryButton
import io.github.blublub.tvcal.ui.theme.Theme
import io.github.blublub.tvcal.ui.theme.Type
import io.github.blublub.tvcal.ui.theme.screenBackground
import io.github.blublub.tvcal.ui.theme.symbol
import io.github.blublub.tvcal.ui.theme.tint
import kotlinx.coroutines.delay

// MARK: - Home

/** The home screen (Figma "02 Home"): the camera's state, the saved screens and the tools. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TVListView() {
    val app = LocalApp.current
    val nav = LocalNav.current
    val links = app.links
    var welcoming by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var doomed by remember { mutableStateOf<TVRecord?>(null) }
    var managing by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .screenBackground()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
    ) {
        HomeNavigationBar { nav.push(Route.Settings) }

        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // The headline is for a first visit; with screens saved, they come first.
            if (app.tvs.isEmpty()) {
                Text(stringResource(R.string.home_headline), style = Type.displayStyle)
                Text(
                    stringResource(R.string.home_subtitle),
                    style = Type.bodyBrand, color = Theme.textSecondary,
                )
            }

            Box(Modifier.clickable { nav.push(Route.Camera) }) { CameraStatusRow() }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.home_your_screens), style = Type.section, color = Color.White, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                LinkButton(stringResource(R.string.home_manage_screens), onClick = { managing = true }, style = Type.captionStrong)
            }

            if (app.tvs.isEmpty()) {
                EmptyScreensCard(video = links.links.explainerURL) { welcoming = true }
            }
            for (tv in app.tvs.toList()) {
                var menu by remember(tv.id) { mutableStateOf(false) }
                Box {
                    Box(Modifier.combinedClickable(onClick = { nav.push(Route.TV(tv.id)) }, onLongClick = { menu = true })) {
                        TVCard(tv = tv)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.home_delete), color = Theme.red) },
                            leadingIcon = { Icon(symbol("trash"), contentDescription = null, tint = Theme.red) },
                            onClick = {
                                menu = false
                                doomed = tv
                            },
                        )
                    }
                }
            }

            PrimaryButton(stringResource(R.string.home_add_screen), onClick = { adding = true })
            if (app.tvs.none { it.isDemo }) {
                SecondaryButton(stringResource(R.string.home_try_demo), onClick = {
                    val tv = app.addDemoTV()
                    nav.push(Route.TV(tv.id))
                })
            }

            Box(Modifier.clickable { welcoming = true }) {
                ToolTile(
                    symbol = "play.rectangle", title = stringResource(R.string.home_how_it_works),
                    text = stringResource(
                        if (links.links.explainerURL == null) R.string.home_how_it_works_detail
                        else R.string.home_how_it_works_detail_video,
                    ),
                )
            }
            Box(Modifier.clickable { nav.push(Route.Live) }) {
                ToolTile(symbol = "viewfinder", title = stringResource(R.string.home_live_title),
                    text = stringResource(R.string.home_live_detail))
            }
            Box(Modifier.clickable { nav.push(Route.Settings) }) {
                ToolTile(symbol = "switch.2", title = stringResource(R.string.home_settings),
                    text = stringResource(R.string.home_settings_detail), boxed = false)
            }
        }
    }

    if (adding) {
        FullSheet(onDismiss = { adding = false }) {
            TVSetupFlow(tv = null, onAdded = { tv -> nav.push(Route.TV(tv.id)) }, onDismiss = { adding = false })
        }
    }
    if (managing) {
        FullSheet(onDismiss = { managing = false }) {
            ManageScreensView(onDismiss = { managing = false })
        }
    }
    if (welcoming) {
        FullSheet(onDismiss = { welcoming = false }) {
            WelcomeView(onFinish = { app.markWelcomeSeen() }, onDismiss = { welcoming = false })
        }
    }
    LaunchedEffect(Unit) {
        // After the opening screen has faded.
        if (app.welcomeSeen || app.tvs.isNotEmpty()) return@LaunchedEffect
        delay(2100)
        if (!app.welcomeSeen) welcoming = true
    }
    DeleteScreenAlert(doomed, onDoomedChange = { doomed = it }) { tv -> app.delete(tv) }
}

/** The home screen's own bar: the app's name on the left, settings on the right (48 dp, as in the design). */
@Composable
private fun HomeNavigationBar(openSettings: () -> Unit) {
    val settingsLabel = stringResource(R.string.home_settings)
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            stringResource(R.string.app_name),
            style = Type.headlineBrand, color = Color.White,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        Box(
            Modifier
                .size(44.dp)
                .clickable(onClick = openSettings)
                .semantics { contentDescription = settingsLabel },
            contentAlignment = Alignment.CenterEnd,
        ) {
            Icon(symbol("slider.horizontal.3"), contentDescription = null, tint = Theme.tealBright,
                modifier = Modifier.size(22.dp))
        }
    }
}

/** No screens yet: how to place the phone, and what happens next. */
@Composable
private fun EmptyScreensCard(
    /** The explainer video, when its link is set. */
    video: String?,
    howItWorks: () -> Unit,
) {
    Card(spacing = 12.dp) {
        PlacementPanel()
        if (video != null) {
            VideoPreview(url = video, compact = true)
        } else {
            LinkButton(stringResource(R.string.home_new_to_this), onClick = howItWorks, style = Type.captionStrong)
        }
    }
}

/** #777D7E, the grey centre patch. */
private val patchGrey = Color(0xFF777D7E)

/** The TV with its grey centre patch, and the phone on its stand facing it (Figma "Instruction illustration"). */
@Composable
private fun PlacementPanel() {
    val shape = RoundedCornerShape(18.dp)
    val placement = stringResource(R.string.home_placement_desc)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(100.dp)
            .clip(shape)
            .background(Theme.surface, shape)
            .clearAndSetSemantics {
                contentDescription = placement
            },
    ) {
        val w = maxWidth
        val s = w.value / 321f
        val phoneX = w - 51.dp
        val stemX = phoneX + 17.5.dp
        // TV
        Box(
            Modifier
                .offset(x = (28 * s).dp, y = 20.dp)
                .size(width = (228 * s).dp, height = 36.dp)
                .background(Theme.background, RoundedCornerShape(7.dp))
                .border(2.dp, Theme.tealBright, RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(width = (76 * s).dp, height = 18.dp)
                    .background(patchGrey, RoundedCornerShape(1.dp)),
            )
        }
        Text(
            stringResource(R.string.home_placement_label),
            style = Type.tiny, color = Theme.textSecondary, maxLines = 1, softWrap = false,
            modifier = Modifier.offset(x = (45 * s).dp, y = 75.dp),
        )
        // Stand
        Canvas(Modifier.fillMaxSize()) {
            val x = stemX.toPx()
            val c = Theme.tealBright
            val sw = 2.dp.toPx()
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
                drawLine(c, Offset(x1, y1.dp.toPx()), Offset(x2, y2.dp.toPx()), sw, StrokeCap.Round)
            val d = 20.dp.toPx()
            line(x, 66f, x, 76f)
            line(x - d, 86f, x, 76f)
            line(x, 76f, x + d, 86f)
        }
        // Phone, with the measuring box
        Box(
            Modifier
                .offset(x = phoneX, y = 5.dp)
                .size(width = 35.dp, height = 62.dp)
                .background(Theme.background, RoundedCornerShape(6.dp))
                .border(2.dp, Theme.tealBright, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(10.dp).border(1.5.dp, Theme.accent, RoundedCornerShape(1.dp)))
        }
    }
}

/** One TV on the home screen (Figma "Screen card"): name, brand and how far each format has got. */
@Composable
fun TVCard(tv: TVRecord) {
    val app = LocalApp.current

    /** Filled when a format is calibrated, half when started, an outline when not started. */
    fun stage(m: SignalMode): FormatStage {
        val done = app.store.guideState(tv, m).progress.done.size
        return if (done >= GuideStep.stepper.size - 1) FormatStage.DONE
        else if (done > 0) FormatStage.STARTED else FormatStage.NOT_STARTED
    }

    /** The greyscale score of the latest finished calibration (SDR first), shown as its word. */
    val score: Double? = run {
        for (m in SignalMode.entries) {
            val a = app.store.guideState(tv, m).sessions.lastOrNull { it.after != null }?.after ?: continue
            val de = Analysis.greyAverage(Analysis.analyse(a, hdr = m.isHDR), hdr = m.isHDR)
            if (de.isFinite()) return@run de
        }
        null
    }

    Card(spacing = 12.dp, modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBubble(symbol = if (tv.isDemo) "sparkles.tv" else "tv", size = 44.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(tv.name, style = Type.bodyStrong, color = Color.White)
                Text(
                    if (tv.isDemo) stringResource(R.string.home_demo_readings) else tv.brand.label,
                    style = Type.captionBrand, color = Theme.textSecondary,
                )
            }
            Icon(symbol("chevron.right"), contentDescription = null, tint = Theme.tealBright,
                modifier = Modifier.size(20.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for (m in SignalMode.entries) FormatChip(mode = m, stage = stage(m))
            Spacer(Modifier.weight(1f))
            if (score != null) {
                Text(Score.word(score), style = Type.captionStrong, color = Score.tint(score), maxLines = 1)
            }
        }
    }
}

private enum class FormatStage { NOT_STARTED, STARTED, DONE }

/**
 * A format on a screen's card: filled in its colour with a tick when calibrated, outlined in its
 * colour when started, faint when not started.
 */
@Composable
private fun FormatChip(mode: SignalMode, stage: FormatStage) {
    val shape = RoundedCornerShape(6.dp)
    val fg = when (stage) {
        FormatStage.DONE -> Theme.background
        FormatStage.STARTED -> mode.tint
        FormatStage.NOT_STARTED -> Theme.textSecondary
    }
    val stroke = when (stage) {
        FormatStage.DONE -> Color.Transparent
        FormatStage.STARTED -> mode.tint.copy(alpha = 0.7f)
        FormatStage.NOT_STARTED -> Theme.hairline
    }
    val word = when (stage) {
        FormatStage.DONE -> stringResource(R.string.home_format_calibrated)
        FormatStage.STARTED -> stringResource(R.string.home_format_started)
        FormatStage.NOT_STARTED -> stringResource(R.string.home_format_not_started)
    }
    val chipDescription = stringResource(R.string.home_format_desc, mode.label, word)
    Row(
        Modifier
            .background(if (stage == FormatStage.DONE) mode.tint else Color.Transparent, shape)
            .border(1.dp, stroke, shape)
            .padding(horizontal = 7.dp, vertical = 4.dp)
            .clearAndSetSemantics { contentDescription = chipDescription },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (stage == FormatStage.DONE) {
            Icon(symbol("checkmark"), contentDescription = null, tint = fg, modifier = Modifier.size(9.dp))
        }
        Text(mode.label, style = Type.micro.copy(fontWeight = FontWeight.SemiBold), color = fg, maxLines = 1,
            softWrap = false)
    }
}

/**
 * A home-screen row: a label over a secondary line, with a teal icon on the right. Boxed in a card
 * (the live meter) or bare (settings).
 */
@Composable
fun ToolTile(symbol: String, title: String, text: String, boxed: Boolean = true) {
    val shape = RoundedCornerShape(18.dp)
    var m = Modifier
        .fillMaxWidth()
        .heightIn(min = 48.dp)
    if (boxed) m = m.background(Theme.card, shape).border(1.dp, Theme.hairline, shape).padding(12.dp)
    Row(m, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = Type.bodyBrand, color = Color.White)
            Text(text, style = Type.captionBrand, color = Theme.textSecondary)
        }
        Icon(io.github.blublub.tvcal.ui.theme.symbol(symbol), contentDescription = null, tint = Theme.tealBright, modifier = Modifier.size(22.dp))
    }
}

// MARK: - Camera status

@Composable
fun CameraStatusRow() {
    val app = LocalApp.current
    CameraStatusContent(profiles = app.profiles, camera = app.camera)
}

private enum class CameraPhase { CHECKING, UNAVAILABLE, SET_UP, DARKNESS, STANDARD, READY }

/**
 * The teal camera card (Figma "Camera status card"): checking, set up, measure the darkness, ready,
 * standard profile or unavailable.
 */
@Composable
private fun CameraStatusContent(profiles: ProfileService, camera: CameraMeter) {
    val running by camera.running.collectAsState()
    val dark by camera.dark.collectAsState()

    val phase = when {
        profiles.profile == null ->
            if (profiles.status == ProfileService.Status.CHECKING) CameraPhase.CHECKING else CameraPhase.UNAVAILABLE
        !running -> CameraPhase.SET_UP
        dark == null -> CameraPhase.DARKNESS
        profiles.isStandard -> CameraPhase.STANDARD
        else -> CameraPhase.READY
    }
    val sym = when (phase) {
        CameraPhase.CHECKING -> "arrow.triangle.2.circlepath"
        CameraPhase.UNAVAILABLE -> "video.slash"
        CameraPhase.SET_UP -> "camera"
        CameraPhase.DARKNESS -> "moon"
        CameraPhase.STANDARD -> "exclamationmark.triangle"
        CameraPhase.READY -> "checkmark.circle"
    }
    val colour = when (phase) {
        CameraPhase.CHECKING, CameraPhase.SET_UP, CameraPhase.DARKNESS -> Theme.tealBright
        CameraPhase.UNAVAILABLE, CameraPhase.STANDARD -> Theme.limit
        CameraPhase.READY -> Theme.green
    }
    val title = when (phase) {
        CameraPhase.CHECKING -> stringResource(R.string.home_camera_checking)
        CameraPhase.UNAVAILABLE -> stringResource(R.string.home_camera_unavailable)
        CameraPhase.SET_UP -> stringResource(R.string.home_camera_set_up)
        CameraPhase.DARKNESS -> stringResource(R.string.home_camera_darkness)
        CameraPhase.STANDARD -> stringResource(R.string.home_camera_standard)
        CameraPhase.READY -> stringResource(R.string.home_camera_ready)
    }
    val detail = when (phase) {
        CameraPhase.CHECKING -> stringResource(R.string.home_camera_checking_detail)
        CameraPhase.UNAVAILABLE -> profiles.summary
        CameraPhase.SET_UP -> stringResource(R.string.home_camera_set_up_detail)
        CameraPhase.DARKNESS -> stringResource(R.string.home_camera_darkness_detail)
        CameraPhase.STANDARD -> stringResource(R.string.home_camera_standard_detail)
        CameraPhase.READY -> stringResource(R.string.home_camera_ready_detail)
    }
    /** Something for the user to do (set up, measure the darkness): the teal card. Otherwise one line. */
    val needsAction = phase == CameraPhase.SET_UP || phase == CameraPhase.DARKNESS || phase == CameraPhase.UNAVAILABLE

    if (needsAction) {
        Card(hero = true, modifier = Modifier.semantics(mergeDescendants = true) {}) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBubble(symbol = sym, tint = colour, size = 36.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, style = Type.headlineBrand, color = colour)
                    Text(detail, style = Type.captionBrand, color = Theme.textSecondary)
                }
                Icon(symbol("chevron.right"), contentDescription = null, tint = Theme.tealBright,
                    modifier = Modifier.size(18.dp))
            }
        }
    } else {
        val shape = RoundedCornerShape(16.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(Theme.card, shape)
                .border(1.dp, Theme.hairline, shape)
                .padding(horizontal = 14.dp)
                .semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(19.dp), contentAlignment = Alignment.Center) {
                if (phase == CameraPhase.CHECKING) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = Theme.tealBright, strokeWidth = 2.dp)
                } else {
                    Icon(symbol(sym), contentDescription = null, tint = colour, modifier = Modifier.size(19.dp))
                }
            }
            Text(title, style = Type.captionStrong, color = colour)
            Text(
                stringResource(if (phase == CameraPhase.STANDARD) R.string.home_readings_approximate else R.string.home_phone_camera),
                style = Type.small, color = Theme.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(symbol("chevron.right"), contentDescription = null, tint = Theme.tealBright,
                modifier = Modifier.size(16.dp))
        }
    }
}

// MARK: - Manage Screens

/** Every saved screen (Figma "04 Manage Screens"): edit its menus, or delete it. */
@Composable
fun ManageScreensView(onDismiss: () -> Unit) {
    val app = LocalApp.current
    var editing by remember { mutableStateOf<TVRecord?>(null) }
    var doomed by remember { mutableStateOf<TVRecord?>(null) }
    var adding by remember { mutableStateOf(false) }
    val context = LocalContext.current

    /** "LG · SDR calibrated", "Samsung · Not started". */
    fun detail(tv: TVRecord): String {
        val full = GuideStep.stepper.size - 1
        val counts = SignalMode.entries.map { m -> m to app.store.guideState(tv, m).progress.done.size }
        val calibrated = counts.filter { it.second >= full }.map { it.first.label }
        val started = counts.firstOrNull { it.second > 0 }
        val progress = when {
            calibrated.isNotEmpty() -> context.getString(R.string.home_progress_calibrated, calibrated.joinToString(", "))
            started != null -> context.getString(R.string.home_progress_in_progress, started.first.label)
            else -> context.getString(R.string.home_progress_not_started)
        }
        return (if (tv.isDemo) context.getString(R.string.home_demo) else tv.brand.label) + " · " + progress
    }

    ScreenWithBottomAction(
        modifier = Modifier.fillMaxSize(),
        bottom = { SecondaryButton(stringResource(R.string.home_done), onClick = onDismiss) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ScreensTitleBar(
                title = stringResource(R.string.home_manage_screens),
                closeLabel = stringResource(R.string.home_close), onClose = onDismiss,
            )
            Box(Modifier.weight(1f)) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    for (tv in app.tvs.toList()) {
                        ManagedScreenRow(tv = tv, detail = detail(tv), onEdit = { editing = tv }, onDelete = { doomed = tv })
                    }
                    SecondaryButton(stringResource(R.string.home_add_screen), onClick = { adding = true })
                }
                if (app.tvs.isEmpty()) {
                    Text(stringResource(R.string.home_no_screens), style = Type.bodyBrand, color = Theme.textSecondary,
                        modifier = Modifier.align(Alignment.Center))
                }
            }
        }
    }

    editing?.let { tv ->
        FullSheet(onDismiss = { editing = null }) {
            TVSetupFlow(tv = tv, onDismiss = { editing = null })
        }
    }
    if (adding) {
        FullSheet(onDismiss = { adding = false }) {
            TVSetupFlow(tv = null, onDismiss = { adding = false })
        }
    }
    DeleteScreenAlert(doomed, onDoomedChange = { doomed = it }) { tv -> app.delete(tv) }
}

/** One saved screen: name and progress, with edit (teal) and delete (red) buttons. */
@Composable
private fun ManagedScreenRow(tv: TVRecord, detail: String, onEdit: () -> Unit, onDelete: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .background(Theme.card, shape)
            .border(1.dp, Theme.hairline, shape)
            .padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(tv.name, style = Type.bodyStrong, color = Color.White)
            Text(detail, style = Type.captionBrand, color = Theme.textSecondary)
        }
        if (!tv.isDemo) {
            RowIconButton("pencil", Theme.tealBright, stringResource(R.string.home_edit_screen, tv.name), onEdit)
        }
        RowIconButton("trash", Theme.red, stringResource(R.string.home_delete_screen, tv.name), onDelete)
    }
}

@Composable
private fun RowIconButton(name: String, tint: Color, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(symbol(name), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** A sheet's title bar: a close X on the left, the title centred (the Swift inline navigation title). */
@Composable
private fun ScreensTitleBar(title: String, closeLabel: String, onClose: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 8.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .align(Alignment.CenterStart)
                .clickable(onClick = onClose)
                .semantics { contentDescription = closeLabel },
            contentAlignment = Alignment.Center,
        ) {
            Icon(symbol("xmark"), contentDescription = null, tint = Theme.tealBright, modifier = Modifier.size(22.dp))
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

/** "Delete Living room OLED?" with Cancel and Delete, shown while `doomed` holds a screen. */
@Composable
private fun DeleteScreenAlert(doomed: TVRecord?, onDoomedChange: (TVRecord?) -> Unit, delete: (TVRecord) -> Unit) {
    val tv = doomed ?: return
    AlertDialog(
        onDismissRequest = { onDoomedChange(null) },
        title = { Text(stringResource(R.string.home_delete_title, tv.name)) },
        text = {
            Text(stringResource(R.string.home_delete_text))
        },
        confirmButton = {
            TextButton(onClick = {
                delete(tv)
                onDoomedChange(null)
            }) { Text(stringResource(R.string.home_delete), color = Theme.red, fontSize = 15.sp) }
        },
        dismissButton = {
            TextButton(onClick = { onDoomedChange(null) }) { Text(stringResource(R.string.home_cancel), color = Theme.tealBright, fontSize = 15.sp) }
        },
        containerColor = Theme.surface,
        titleContentColor = Color.White,
        textContentColor = Theme.textSecondary,
    )
}
