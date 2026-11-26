// Port of TV Calibrator/Views/Walkthrough.swift (TV Calibrator by Slidebean, https://github.com/slidebean/tv-calibrator, CC BY-NC 4.0). Changed: translated to Kotlin/Compose.
package io.github.blublub.tvcal.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.blublub.tvcal.engine.SignalMode
import io.github.blublub.tvcal.profiles.onYouTube
import io.github.blublub.tvcal.storage.ColourMenu
import io.github.blublub.tvcal.ui.theme.Card
import io.github.blublub.tvcal.ui.theme.Eyebrow
import io.github.blublub.tvcal.ui.theme.LinkButton
import io.github.blublub.tvcal.ui.theme.PageDots
import io.github.blublub.tvcal.ui.theme.PrimaryButton
import io.github.blublub.tvcal.ui.theme.symbol
import io.github.blublub.tvcal.ui.theme.Theme
import io.github.blublub.tvcal.ui.theme.Type
import io.github.blublub.tvcal.ui.theme.tint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// A swipeable, one-idea-per-page walkthrough, and the small illustrations it uses
// (Figma: "Walkthrough page" component, 07 Set up and 03 Add a Screen).

data class WalkPage(
    val symbol: String,
    val tint: Color = Theme.tealBright,
    val title: String,
    val text: String,
    val bullets: List<String> = emptyList(),
    /** A picture above the text, drawn on the teal illustration panel (replaces the symbol). */
    val art: (@Composable () -> Unit)? = null,
    /** Controls under the text (buttons, choices). */
    val extra: (@Composable () -> Unit)? = null,
    /** The small teal line above the title. When null the walkthrough shows "<section> · 2 of 6". */
    val eyebrow: String? = null,
    /** A larger white sentence before `text` (17 sp), for the one thing to do on this page. */
    val lead: String? = null,
)

// MARK: - The pinned main action
// The Swift app's `pinnedAction` / `pinnedActionHost()`: a view deep inside a screen declares the screen's
// main (yellow) action, and the hosting screen draws it pinned at the bottom. Here the host provides a
// [PinnedActionHostState] through [LocalPinnedActionHost] and draws [PinnedActionBar]; a view declares its
// action with [PinnedActionOrInline], which draws the button in place when no host is provided.

/** One declared action. `run` always calls the declaring view's newest closure. */
data class PinnedActionEntry(val token: Any, val title: String?, val enabled: Boolean, val run: () -> Unit)

/** The actions declared inside a host; the last one declared with a title wins (like the Swift preference). */
@Stable
class PinnedActionHostState {
    private val entries = mutableStateListOf<PinnedActionEntry>()

    val current: PinnedActionEntry? get() = entries.lastOrNull { it.title != null }

    fun put(e: PinnedActionEntry) {
        val i = entries.indexOfFirst { it.token === e.token }
        if (i < 0) entries.add(e) else if (entries[i] != e) entries[i] = e
    }

    fun remove(token: Any) {
        entries.removeAll { it.token === token }
    }
}

/** Provided by a screen that pins its main action at the bottom (GuidedScreen). */
val LocalPinnedActionHost = compositionLocalOf<PinnedActionHostState?> { null }

/** Declares this screen's main action with the host; false when there is no host. A null title declares none. */
@Composable
fun DeclarePinnedAction(title: String?, enabled: Boolean = true, action: () -> Unit): Boolean {
    val host = LocalPinnedActionHost.current ?: return false
    val token = remember { Any() }
    val latest = rememberUpdatedState(action)
    val run = remember { { latest.value() } }
    SideEffect { host.put(PinnedActionEntry(token, title, enabled, run)) }
    DisposableEffect(host) { onDispose { host.remove(token) } }
    return true
}

/** The Swift `.pinnedAction(...)`: pinned by the host when there is one, otherwise a yellow button right here. */
@Composable
fun PinnedActionOrInline(title: String?, enabled: Boolean = true, action: () -> Unit) {
    if (!DeclarePinnedAction(title, enabled, action) && title != null) {
        PrimaryButton(title, onClick = action, enabled = enabled)
    }
}

/** The host's pinned button (put it in ScreenWithBottomAction's bottom slot). */
@Composable
fun PinnedActionBar(state: PinnedActionHostState) {
    val c = state.current ?: return
    PrimaryButton(c.title ?: "", onClick = c.run, enabled = c.enabled)
}

// MARK: - Walkthrough

/**
 * Pages you swipe (or tap Next) through. The last page's button finishes.
 * The yellow Next / finish button is pinned by the hosting screen when it provides [LocalPinnedActionHost],
 * and drawn under the page otherwise; the page dots and Back sit under the page.
 */
@Composable
fun Walkthrough(
    pages: List<WalkPage>,
    finishTitle: String = "Done",
    /** The eyebrow's prefix: "Set up" gives "Set up · 5 of 6". */
    section: String? = null,
    /** Page titles: 22 sp semibold (`Type.stepTitle`), the same as a step's title. */
    titleFont: TextStyle = Type.stepTitle,
    /** Height of the illustration panel for pages with `art`. */
    artHeight: Dp = 170.dp,
    /** Whether the user may leave page i (e.g. a name is filled in). */
    canAdvance: (Int) -> Boolean = { true },
    onFinish: () -> Unit,
) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    var forward by remember { mutableStateOf(true) }
    val current = min(index, max(pages.size - 1, 0))
    val onLastPage = index >= pages.size - 1
    val canAdvanceNow by rememberUpdatedState(canAdvance)
    val pageCount by rememberUpdatedState(pages.size)

    fun next() {
        if (index >= pageCount - 1 || !canAdvanceNow(index)) return
        forward = true
        index += 1
    }

    fun back() {
        if (index <= 0) return
        forward = false
        index -= 1
    }

    fun eyebrow(p: WalkPage, i: Int): String? {
        if (p.eyebrow != null) return p.eyebrow
        if (pages.size <= 1) return section
        val count = "${i + 1} of ${pages.size}"
        return if (section != null) "$section · $count" else count
    }

    val swipe = with(LocalDensity.current) { 50.dp.toPx() }

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (pages.isNotEmpty()) {
            AnimatedContent(
                targetState = current,
                transitionSpec = {
                    val dir = if (forward) 1 else -1
                    (slideInHorizontally(tween(280)) { w -> dir * w } + fadeIn(tween(280)))
                        .togetherWith(slideOutHorizontally(tween(280)) { w -> -dir * w } + fadeOut(tween(280)))
                        .using(SizeTransform(clip = true))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clipToBounds()
                    .pointerInput(Unit) {
                        var total = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = {
                                if (total < -swipe) next() else if (total > swipe) back()
                            },
                        ) { change, amount ->
                            total += amount
                            change.consume()
                        }
                    },
                label = "walkthrough",
            ) { i ->
                val p = pages.getOrNull(i)
                if (p != null) PageView(p, eyebrow(p, i), titleFont, artHeight)
            }

            // Back (a teal link) on the left, the page dots in the middle.
            Box(Modifier.fillMaxWidth().heightIn(min = 44.dp), contentAlignment = Alignment.Center) {
                if (pages.size > 1) PageDots(count = pages.size, index = current)
                if (current > 0) {
                    LinkButton("Back", onClick = { back() }, modifier = Modifier.align(Alignment.CenterStart),
                        style = Type.bodyBrand)
                }
            }
        }
        // Read the page at tap time, like the original.
        PinnedActionOrInline(if (onLastPage) finishTitle else "Next", enabled = canAdvance(current)) {
            if (index >= pages.size - 1) onFinish() else next()
        }
    }
}

@Composable
private fun PageView(p: WalkPage, eyebrow: String?, titleFont: TextStyle, artHeight: Dp) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (eyebrow != null) Eyebrow(eyebrow)
        Text(p.title, style = titleFont.copy(letterSpacing = (-0.4).sp, color = Color.White))
        val art = p.art
        if (art != null) {
            WalkArtPanel(height = artHeight) { art() }
        } else if (p.symbol.isNotEmpty()) {
            WalkArtPanel(height = 120.dp) {
                Icon(symbol(p.symbol), contentDescription = null, tint = p.tint, modifier = Modifier.size(44.dp))
            }
        }
        if (p.lead != null) {
            Text(p.lead, style = Type.brand(17f).copy(lineHeight = 24.sp), color = Color.White)
        }
        if (p.text.isNotEmpty()) {
            Text(p.text, style = Type.bodyBrand.copy(lineHeight = 22.sp), color = Theme.textSecondary)
        }
        if (p.bullets.isNotEmpty()) {
            Card(spacing = 10.dp) {
                p.bullets.forEach { b ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                        Icon(symbol("checkmark.circle"), contentDescription = null, tint = p.tint,
                            modifier = Modifier.padding(top = 2.dp).size(15.dp))
                        Text(b, style = Type.bodyBrand, color = Color.White)
                    }
                }
            }
        }
        p.extra?.invoke()
    }
}

/** The illustration panel: the brand teal surface, 18 dp corners, the picture centred. */
@Composable
fun WalkArtPanel(height: Dp = 170.dp, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(Theme.surface, shape),
        contentAlignment = Alignment.Center,
    ) { content() }
}

// MARK: - Illustrations
// Flat line drawings: teal outlines on the panel, the screen in the background colour,
// the grey centre patch and the yellow measuring box.

private object ArtColour {
    val line = Theme.tealBright
    val screen = Theme.background
    val patch = Color(0xFF777D7E)
    val track = Color.White.copy(alpha = 0.14f)
}

/** Text measured in drawing units (1 unit = 1 font point), so it scales with the drawing. */
private fun TextMeasurer.unitText(text: String, style: TextStyle) =
    measure(text, style, overflow = TextOverflow.Visible, softWrap = false, maxLines = 1, density = Density(1f, 1f))

/** The TV with a grey patch in the middle, and the phone on its stand facing it. */
@Composable
fun PlacementArt() {
    val measurer = rememberTextMeasurer()
    val label = "15–30 cm · facing the centre"
    Canvas(
        Modifier
            .fillMaxSize()
            .clearAndSetSemantics {
                contentDescription =
                    "A phone on a stand, 15 to 30 centimetres from the TV, facing the grey patch in the middle"
            },
    ) {
        // Drawn on a 325 × 160 canvas (the Figma frame), scaled to fit.
        val cw = 325f
        val ch = 160f
        val s = max(0.01f, min(size.width / cw, size.height / ch))
        translate((size.width - cw * s) / 2, (size.height - ch * s) / 2) {
            scale(s, pivot = Offset.Zero) {
                // TV and its stand
                drawRoundRect(ArtColour.screen, Offset(28f, 20f), Size(228f, 86f), CornerRadius(7f))
                drawRoundRect(ArtColour.line, Offset(28f, 20f), Size(228f, 86f), CornerRadius(7f), Stroke(2f))
                drawRoundRect(ArtColour.patch, Offset(28f + (228 - 76) / 2f, 20f + (86 - 60) / 2f), Size(76f, 60f),
                    CornerRadius(1f))
                drawRect(ArtColour.line, Offset(28f + (228 - 40) / 2f, 111f), Size(40f, 3f))
                // Phone with the measuring box
                drawRoundRect(ArtColour.screen, Offset(270f, 60f), Size(35f, 62f), CornerRadius(6f))
                drawRoundRect(ArtColour.line, Offset(270f, 60f), Size(35f, 62f), CornerRadius(6f), Stroke(2f))
                drawRoundRect(Theme.accent, Offset(270f + 12.5f, 60f + 26f), Size(10f, 10f), CornerRadius(1f),
                    Stroke(1.5f))
                // Its stand: a stem and two legs
                val x = 287.5f
                val stand = Path().apply {
                    moveTo(x, 122f); lineTo(x, 136f)
                    moveTo(x - 21, 147f); lineTo(x, 136f); lineTo(x + 21, 147f)
                }
                drawPath(stand, ArtColour.line, style = Stroke(1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawText(measurer.unitText(label, Type.tiny.copy(color = Theme.textSecondary)), topLeft = Offset(45f, 135f))
            }
        }
    }
}

/** Lights off, TV warming up. */
@Composable
fun DarkRoomArt() {
    @Composable
    fun item(symbolName: String, label: String) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.height(44.dp), contentAlignment = Alignment.Center) {
                Icon(symbol(symbolName), contentDescription = null, tint = ArtColour.line, modifier = Modifier.size(36.dp))
            }
            Text(label, style = Type.small, color = Theme.textSecondary)
        }
    }
    Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(30.dp),
        verticalAlignment = Alignment.Top) {
        item("lightbulb.slash", "Lights off")
        item("tv", "TV on")
        item("timer", "20–30 min")
    }
}

/** The four formats, in the order to calibrate them, with the current one highlighted. */
@Composable
fun FormatOrderArt(current: SignalMode) {
    Column(
        Modifier.clearAndSetSemantics {
            contentDescription = "Calibrate in order: SDR, HDR10, HDR10+, Dolby Vision. Now: ${current.label}"
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val all = SignalMode.entries
            all.forEach { m ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val now = m == current
                    Text(
                        m.label,
                        style = Type.badge.copy(fontSize = if (m.label.length > 6) 10.sp else 12.sp),
                        color = if (now) Theme.background else m.tint,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (now) m.tint else m.tint.copy(alpha = 0.13f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                    )
                    Text(if (m.onYouTube) "YouTube" else "USB file", style = Type.micro, color = Theme.textSecondary,
                        maxLines = 1)
                }
                if (m != all.last()) {
                    Icon(symbol("chevron.right"), contentDescription = null, tint = Theme.textSecondary,
                        modifier = Modifier.padding(bottom = 16.dp).size(10.dp))
                }
            }
        }
        Text("Each format has its own picture settings on the TV", style = Type.small, color = Theme.textSecondary,
            textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp))
    }
}

/** The TV outline that the menu illustrations draw their sliders in, fitted to any size (in dp units). */
private fun artTVRect(size: Size): Rect {
    val pad = max(1f, min(size.width, size.height) * 0.12f)
    val h = max(1f, size.height - pad * 2)
    val w = max(1f, min(size.width - pad * 2, h * 2.6f))
    return Rect(Offset((size.width - w) / 2, (size.height - h) / 2), Size(w, h))
}

private fun DrawScope.drawArtTV(tv: Rect) {
    val r = CornerRadius(min(9f, tv.height * 0.1f))
    drawRoundRect(ArtColour.screen, tv.topLeft, tv.size, r)
    drawRoundRect(ArtColour.line, tv.topLeft, tv.size, r, Stroke(1.5f))
}

private fun Rect.insetBy(dx: Float, dy: Float) = Rect(left + dx, top + dy, right - dx, bottom - dy)

/** Draws in dp units: `block` gets the size in dp. */
private fun DrawScope.inDp(block: DrawScope.(Size) -> Unit) {
    val d = density
    val sz = Size(size.width / d, size.height / d)
    scale(d, pivot = Offset.Zero) { block(sz) }
}

/**
 * A white-balance menu on the TV: R, G and B sliders for 2-point, or one slider per point
 * (10 or 20). Everything is sized from the frame, so it always fits.
 */
@Composable
fun WhiteBalanceArt(points: Int) {
    Canvas(
        Modifier
            .fillMaxSize()
            .clearAndSetSemantics {
                contentDescription = if (points <= 3) "Red, green and blue sliders" else "$points sliders"
            },
    ) {
        inDp { size ->
            val tv = artTVRect(size)
            drawArtTV(tv)
            val n = if (points <= 3) 3 else points
            val inner = tv.insetBy(tv.width * 0.12f, tv.height * 0.1f)
            if (inner.width <= 0 || inner.height <= 0) return@inDp
            val step = inner.width / n
            val track = min(4f, max(1f, step * 0.3f))
            val knob = minOf(14f, max(3f, step * 0.72f), inner.height * 0.3f)
            val heights = listOf(0.15f, 0.4f, 0.5f)
            for (i in 0 until n) {
                val x = inner.left + step * (i + 0.5f)
                drawRoundRect(ArtColour.track, Offset(x - track / 2, inner.top), Size(track, inner.height),
                    CornerRadius(track / 2))
                val f = if (n == 3) heights[i] else 0.45f + 0.3f * sin(i * 1.1).toFloat()
                val span = max(0f, inner.height - knob)
                val y = inner.top + knob / 2 + span * f.coerceIn(0f, 1f)
                val colour = if (i % 3 == 0) Theme.red else if (i % 3 == 1) Theme.green else Theme.blue
                drawCircle(colour, knob / 2, Offset(x, y))
            }
        }
    }
}

/**
 * A colour menu on the TV: three slider rows (Hue / Saturation / Brightness, or Red / Green / Blue),
 * or an empty screen when the TV has none.
 */
@Composable
fun ColourMenuArt(menu: ColourMenu) {
    val labels = when (menu) {
        ColourMenu.HSL -> listOf("H", "S", "B")
        ColourMenu.RGB_SLIDERS -> listOf("R", "G", "B")
        ColourMenu.OFF -> emptyList()
    }
    val measurer = rememberTextMeasurer()
    Canvas(
        Modifier
            .fillMaxSize()
            .clearAndSetSemantics {
                contentDescription = when (menu) {
                    ColourMenu.OFF -> "No colour menu"
                    ColourMenu.HSL -> "Hue, saturation and brightness sliders"
                    ColourMenu.RGB_SLIDERS -> "Red, green and blue sliders"
                }
            },
    ) {
        inDp { size ->
            val tv = artTVRect(size)
            drawArtTV(tv)
            if (menu == ColourMenu.OFF) {
                val t = measurer.unitText("No colour sliders", Type.tiny.copy(color = Theme.textSecondary))
                drawText(t, topLeft = Offset(tv.center.x - t.size.width / 2f, tv.center.y - t.size.height / 2f))
                return@inDp
            }
            val inner = tv.insetBy(tv.width * 0.12f, tv.height * 0.16f)
            if (inner.width <= 0 || inner.height <= 0) return@inDp
            val rowStep = inner.height / 3
            val track = min(4f, max(1f, rowStep * 0.15f))
            val knob = min(14f, max(3f, rowStep * 0.55f))
            val labelW = if (inner.width > 80) 16f else 0f
            val positions = listOf(0.27f, 0.42f, 0.58f)
            val colours = listOf(Theme.red, Theme.green, Theme.blue)
            for (i in 0 until 3) {
                val y = inner.top + rowStep * (i + 0.5f)
                if (labelW > 0) {
                    val colour = if (menu == ColourMenu.RGB_SLIDERS) colours[i] else Theme.textSecondary
                    val t = measurer.unitText(labels[i], Type.micro.copy(color = colour))
                    val cx = inner.left + labelW / 2 - 4
                    drawText(t, topLeft = Offset(cx - t.size.width / 2f, y - t.size.height / 2f))
                }
                val x0 = inner.left + labelW
                val w = max(1f, inner.width - labelW)
                drawRoundRect(ArtColour.track, Offset(x0, y - track / 2), Size(w, track), CornerRadius(track / 2))
                val x = x0 + knob / 2 + max(0f, w - knob) * positions[i]
                drawCircle(colours[i], knob / 2, Offset(x, y))
            }
        }
    }
}
