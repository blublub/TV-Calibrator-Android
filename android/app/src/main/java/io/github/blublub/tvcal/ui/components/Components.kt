// Port of TV Calibrator/Views/Components.swift (TV Calibrator by Slidebean, https://github.com/slidebean/tv-calibrator, CC BY-NC 4.0). Changed: translated to Kotlin/Compose.
package io.github.blublub.tvcal.ui.components

import android.graphics.BlurMaskFilter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.blublub.tvcal.engine.Analysis
import io.github.blublub.tvcal.engine.ColourMath
import io.github.blublub.tvcal.engine.ColourResult
import io.github.blublub.tvcal.engine.GreyResult
import io.github.blublub.tvcal.engine.Point2
import io.github.blublub.tvcal.engine.Report
import io.github.blublub.tvcal.engine.Vec3
import io.github.blublub.tvcal.engine.signedFixed
import io.github.blublub.tvcal.ui.theme.Card
import io.github.blublub.tvcal.ui.theme.TVCalibratorTheme
import io.github.blublub.tvcal.ui.theme.Theme
import io.github.blublub.tvcal.ui.theme.Type
import io.github.blublub.tvcal.ui.theme.fmt
import io.github.blublub.tvcal.ui.theme.screenBackground
import io.github.blublub.tvcal.ui.theme.symbol
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// The measurement visuals, from the Figma file (pages 02 Components and 04 Measurement visuals):
// score tiles, centred balance bars, the white-point target, the colour chart and the greyscale chart.

// MARK: - Score tile

/** Greys and colours. Colours get wider bands: a good TV rarely matches every colour exactly, and
 *  a phone isn't a professional meter. */
enum class ScoreKind { grey, colour }

/** The score tile's word and colour for a dE (ScoreTile.word / ScoreTile.tint in the original). */
object Score {
    /** Upper limits for Perfect, Excellent, Very good and Good; above the last is "Noticeably off". */
    fun bands(kind: ScoreKind): List<Double> =
        if (kind == ScoreKind.grey) listOf(1.0, 2.0, 4.0, 7.0) else listOf(1.0, 3.0, 6.0, 10.0)

    /** The word for a dE. */
    fun word(de: Double?, kind: ScoreKind = ScoreKind.grey): String {
        if (de == null || !de.isFinite()) return "–"
        val b = bands(kind)
        return if (de < b[0]) "Perfect" else if (de < b[1]) "Excellent" else if (de < b[2]) "Very good"
        else if (de < b[3]) "Good" else "Noticeably off"
    }

    /** Green up to Excellent for greys and Very good for colours, white for Good, orange beyond. */
    fun tint(de: Double?, kind: ScoreKind = ScoreKind.grey): Color {
        if (de == null || !de.isFinite()) return Theme.textSecondary
        val b = bands(kind)
        return if (de < b[if (kind == ScoreKind.grey) 1 else 2]) Theme.green else if (de < b[3]) Color.White else Theme.limit
    }
}

private fun Double?.finite(): Double? = this?.takeIf { it.isFinite() }

/**
 * A result tile. dE scores (`digits` 1) show a word ("Very good", green "Invisible") with the dE beside it
 * and "was 4.8" at the top right; other numbers (gamma, HDR brightness) show the number big with
 * "target 2.40 · was 2.18" beside it.
 *
 * `caption`: dE tiles: replaces the score word. Number tiles: the line beside the number ("target 2.40").
 * `unit`: number tiles: appended to the number and to "was", e.g. "%".
 */
@Composable
fun ScoreTile(
    label: String,
    value: Double?,
    modifier: Modifier = Modifier,
    was: Double? = null,
    digits: Int = 1,
    caption: String? = null,
    unit: String = "",
    kind: ScoreKind = ScoreKind.grey,
) {
    val hasValue = value.finite() != null
    val hasWas = was.finite() != null
    Card(modifier.semantics(mergeDescendants = true) {}, spacing = 12.dp) {
        if (digits == 1) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, style = Type.captionBrand, color = Theme.textSecondary,
                    modifier = Modifier.weight(1f).alignByBaseline())
                if (hasWas) {
                    Text("was ${fmt(was, digits)}", style = Type.small, color = Theme.textSecondary,
                        modifier = Modifier.alignByBaseline())
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BasicText(
                    caption ?: Score.word(value, kind),
                    style = Type.brand(27f).copy(letterSpacing = (-0.7).sp, color = Score.tint(value, kind)),
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = (27f * 0.7f).sp, maxFontSize = 27.sp),
                    modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
                )
                if (hasValue) {
                    Text("${fmt(value, digits)} dE", style = Type.callout14, color = Theme.textSecondary,
                        modifier = Modifier.alignByBaseline())
                }
            }
        } else {
            val detail = buildList {
                if (caption != null) add(caption)
                if (hasWas) add("was " + fmt(was, digits) + unit)
            }.joinToString(" · ")
            Text(label, style = Type.captionBrand, color = Theme.textSecondary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText(
                    fmt(value, digits) + (if (hasValue) unit else ""),
                    style = Type.reading(34f).copy(color = Color.White),
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = (34f * 0.7f).sp, maxFontSize = 34.sp),
                    modifier = Modifier.alignByBaseline(),
                )
                if (detail.isNotEmpty()) {
                    Text(detail, style = Type.captionBrand, color = Theme.textSecondary, textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f).alignByBaseline())
                }
            }
        }
    }
}

// MARK: - Balance bar

/**
 * One channel: its name and a big reading, over a centred track. Left of the white target line is
 * "too little", right is "too much"; the green zone is the tolerance. Within tolerance the reading
 * turns green with a tick and the bar shrinks to a green pip on the line.
 *
 * `value`: a fraction, e.g. 0.03 = 3% high. `fullScale`: this much fills half the bar.
 * `readingSize`: the reading's size in sp (the design uses 34 and 38).
 */
@Composable
fun BalanceBar(
    label: String,
    value: Double?,
    colour: Color,
    modifier: Modifier = Modifier,
    fullScale: Double = 0.05,
    tolerance: Double = 0.012,
    valueText: String? = null,
    readingSize: Float = 34f,
) {
    val v = value.finite()
    val onTarget = v?.let { abs(it) <= tolerance } ?: false
    val reading = valueText ?: v?.let { (signedFixed(it * 100, 1) + "%").replace("-", "−") } ?: "–"
    val readingColour = if (onTarget) Theme.green else Color.White

    Column(
        modifier.clearAndSetSemantics {
            contentDescription = "$label: $reading${if (onTarget) ", on target" else ""}"
        },
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = Type.headlineBrand, color = Color.White, maxLines = 1)
            Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onTarget) {
                    Icon(symbol("checkmark"), contentDescription = null, tint = readingColour,
                        modifier = Modifier.size((readingSize * 0.62f).dp))
                }
                BasicText(
                    reading,
                    style = Type.reading(readingSize).copy(letterSpacing = (-1).sp, color = readingColour),
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = (readingSize * 0.5f).sp, maxFontSize = readingSize.sp),
                )
            }
        }
        Canvas(Modifier.fillMaxWidth().height(20.dp).clip(RoundedCornerShape(6.dp))) {
            val p = 1.dp.toPx()
            val w = size.width
            val h = size.height
            val mid = w / 2
            val off = (((v ?: 0.0) / fullScale).coerceIn(-1.0, 1.0)).toFloat() * mid
            val tol = ((tolerance / fullScale).toFloat() * mid).coerceAtLeast(6 * p).coerceAtMost(mid)
            val len = if (onTarget) 8 * p else max(abs(off), 4 * p)
            drawRoundRect(Color.White.copy(alpha = 0.07f), cornerRadius = CornerRadius(6 * p))
            drawRect(Theme.green.copy(alpha = 0.16f), topLeft = Offset(mid - tol, 0f), size = Size(tol * 2, h))
            if (v != null) {
                val x = if (onTarget) mid - len / 2 else if (off < 0) mid - len else mid
                drawRoundRect(
                    if (onTarget) Theme.green else colour,
                    topLeft = Offset(x, (h - 10 * p) / 2),
                    size = Size(len, 10 * p),
                    cornerRadius = CornerRadius(4 * p),
                )
            }
            drawRect(Color.White, topLeft = Offset(mid - p, 0f), size = Size(2 * p, h))
        }
    }
}

/** The labels under a set of balance bars: "Too little · On target · Too much". */
@Composable
fun BalanceScaleLabels(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clearAndSetSemantics {}) {
        Text("Too little", style = Type.tiny, color = Theme.textSecondary)
        Spacer(Modifier.weight(1f))
        Text("On target", style = Type.tiny, color = Theme.green)
        Spacer(Modifier.weight(1f))
        Text("Too much", style = Type.tiny, color = Theme.textSecondary)
    }
}

// MARK: - Hue wheel

/**
 * The app icon's colours as a wheel around white: each hue lies the way that colour lies from the white
 * point (red toward the red primary, cyan directly opposite it, and so on). It tints the inside of the
 * colour triangle and the ring around the D65 target, so a position reads as a colour at a glance.
 */
object HueWheel {
    private val red = Color(1f, 0.10f, 0.10f)
    private val green = Color(0.13f, 1f, 0.24f)
    private val blue = Color(0.23f, 0.17f, 1f)
    private val cyan = Color(0f, 0.88f, 1f)
    private val magenta = Color(1f, 0.06f, 0.48f)
    private val yellow = Color(1f, 0.93f, 0f)

    /**
     * `corners`: the red, green and blue primaries on screen; `white`: the white point on screen.
     * A primary's opposite colour (cyan for red) lies straight across white from it.
     */
    fun shading(corners: List<Offset>, white: Offset): Brush {
        if (corners.size != 3) return Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
        val twoPi = 2 * PI
        fun angle(p: Offset): Double {
            val a = atan2((p.y - white.y).toDouble(), (p.x - white.x).toDouble())
            return if (a < 0) a + twoPi else a
        }
        fun across(a: Double) = if (a + PI >= twoPi) a - PI else a + PI
        val r = angle(corners[0])
        val g = angle(corners[1])
        val b = angle(corners[2])
        val hues = listOf(r to red, across(r) to cyan, g to green, across(g) to magenta, b to blue, across(b) to yellow)
            .sortedBy { it.first }
        // A sweep gradient starts at 0 rad (3 o'clock, clockwise on screen); the colour there lies between
        // the last hue and the first one, coming back round.
        val first = hues.first()
        val last = hues.last()
        val gap = (first.first + twoPi) - last.first
        val t = if (gap > 0) ((twoPi - last.first) / gap).toFloat() else 0f
        val wrap = lerp(last.second, first.second, t)
        val stops = buildList {
            add(0f to wrap)
            hues.forEach { add((it.first / twoPi).toFloat() to it.second) }
            add(1f to wrap)
        }
        return Brush.sweepGradient(*stops.toTypedArray(), center = white)
    }
}

private fun circlePath(c: Offset, r: Float) = Path().apply { addOval(Rect(c, r)) }

/** Fills with a Gaussian blur of `radius` px, like SwiftUI's `.blur` on a layer. */
private fun DrawScope.fillBlurred(path: Path, radius: Float, brush: Brush? = null, colour: Color? = null, alpha: Float = 1f) {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    if (brush is ShaderBrush) {
        paint.shader = brush.createShader(size)
        paint.alpha = (alpha * 255).roundToInt().coerceIn(0, 255)
    } else {
        val c = colour ?: Color.White
        paint.color = c.copy(alpha = c.alpha * alpha).toArgb()
    }
    if (radius > 0) paint.maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
    drawIntoCanvas { it.nativeCanvas.drawPath(path.asAndroidPath(), paint) }
}

private fun DrawScope.tick(k: Offset, a: Offset, b: Offset, width: Float) {
    val path = Path().apply {
        moveTo(k.x, k.y)
        lineTo(k.x + a.x, k.y + a.y)
        lineTo(k.x + b.x, k.y + b.y)
    }
    drawPath(path, Theme.green, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

// MARK: - White-point target

/**
 * The white point against D65, in u'v'. The teal inner ring is the target zone; the dot is the TV's
 * white: yellow while off, green with a tick once inside. `was`: where the white was before calibrating,
 * a hollow dot joined to where it is now.
 */
@Composable
fun WhiteTarget(uv: Point2, d65: Point2, ok: Boolean, modifier: Modifier = Modifier, was: Point2? = null) {
    // Inner ring radius in u'v' (about dE 2); the outer ring is the design's 2x.
    val inner = 0.0045
    val outer = inner * 130 / 64.24
    Box(
        modifier
            .aspectRatio(1f)
            .clearAndSetSemantics { contentDescription = if (ok) "White is on target" else "White is off target" },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Canvas(Modifier.matchParentSize()) {
            val p = 1.dp.toPx()
            val side = min(size.width, size.height)
            val c = Offset(size.width / 2, size.height / 2)
            val outerR = side / 2 * (130f / 146f)
            val sc = outerR / outer.toFloat()
            val innerR = inner.toFloat() * sc

            // Between the rings, a faint tint of the colour that lies each way from white; at the centre,
            // the icon's white hot spot.
            val primaries = ColourMath.Gamut.rec709.primaries.map { ColourMath.upvp(it) }.map {
                Offset(c.x + (it.x - d65.x).toFloat() * sc, c.y - (it.y - d65.y).toFloat() * sc)
            }
            val band = Path().apply {
                fillType = PathFillType.EvenOdd
                addOval(Rect(c, outerR - 2 * p))
                addOval(Rect(c, innerR + 2 * p))
            }
            fillBlurred(band, max(3 * p, side * 0.04f), brush = HueWheel.shading(primaries, c), alpha = 0.24f)
            drawCircle(
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.6f), Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0f)),
                    center = c, radius = max(innerR * 1.5f, 0.01f),
                ),
                radius = innerR * 1.5f, center = c,
            )

            drawCircle(Color.White.copy(alpha = 0.2f), outerR, c, style = Stroke(1 * p))
            drawCircle(Theme.tealBright, innerR, c, style = Stroke(1.5f * p))

            val crossColour = Color.White.copy(alpha = 0.6f)
            drawLine(crossColour, Offset(c.x - 10 * p, c.y), Offset(c.x + 10 * p, c.y), 1 * p)
            drawLine(crossColour, Offset(c.x, c.y - 10 * p), Offset(c.x, c.y + 10 * p), 1 * p)

            // a white, kept inside the outer ring so it never leaves the picture
            fun place(pt: Point2): Offset {
                var dx = (pt.x - d65.x).toFloat() * sc
                var dy = -(pt.y - d65.y).toFloat() * sc
                val dist = sqrt(dx * dx + dy * dy)
                if (dist > outerR && dist > 0) {
                    dx *= outerR / dist
                    dy *= outerR / dist
                }
                return Offset(c.x + dx, c.y + dy)
            }
            val m = place(uv)
            val dotR = max(5 * p, side * 5 / 146)
            if (was != null) {
                val b = place(was)
                drawLine(Color.White.copy(alpha = 0.45f), b, m, 1.2f * p)
                drawCircle(Theme.background, dotR * 0.8f, b)
                drawCircle(Color.White.copy(alpha = 0.75f), dotR * 0.8f, b, style = Stroke(1.3f * p))
            }
            if (ok) {
                // on target: the dot blooms
                fillBlurred(circlePath(m, dotR * 1.8f), dotR, colour = Theme.green.copy(alpha = 0.85f))
            }
            drawCircle(if (ok) Theme.green else Theme.accent, dotR, m)

            if (ok) {
                tick(Offset(m.x + dotR + 4 * p, m.y - 2 * p), Offset(3 * p, 3 * p), Offset(9 * p, -4 * p), 1.6f * p)
            }
        }
        Text("D65", style = Type.micro, color = Theme.textSecondary, modifier = Modifier.padding(bottom = 1.dp))
    }
}


// MARK: - Colour chart

/** One colour on the [GamutChart]: where it is (`uv`) and its target, in u'v'. */
data class GamutDot(
    val id: String,
    val uv: Point2,
    val target: Point2,
    /** How far off (dE), when known: under 3 gets the on-target tick. */
    val de: Double? = null,
) {
    val onTarget: Boolean
        get() {
            if (de != null && de.isFinite()) return de < 3
            val du = uv.x - target.x
            val dv = uv.y - target.y
            return sqrt(du * du + dv * dv) < 0.004
        }
}

/** The design's colour for each patch. */
fun gamutColour(id: String): Color = when (id.uppercase()) {
    "RED" -> Theme.red
    "GREEN" -> Theme.green
    "BLUE" -> Theme.blue
    "YELLOW" -> Theme.accent
    "MAGENTA" -> Theme.dolbyVision
    else -> Theme.swatch(id.uppercase())
}

/** A report's colours as chart dots. */
fun gamutDots(rep: Report): List<GamutDot> =
    rep.colours.map { GamutDot(it.name, ColourMath.upvp(it.xy), ColourMath.upvp(it.txy), it.de) }

private fun capitalized(s: String) = s.lowercase().replaceFirstChar { it.uppercase() }

/**
 * The colours in CIE 1976 u'v', over the real Rec.709 (SDR) or P3 (HDR) gamut triangle: rings are the
 * targets, dots are the TV, a line joins each pair, and a green tick marks a colour that's on target.
 * `highlight` (a colour id, e.g. "RED") brings the current colour forward and dims the rest.
 * `before`: where each colour was before calibrating, a small hollow dot joined to where it is now.
 */
@Composable
fun GamutChart(
    dots: List<GamutDot>,
    hdr: Boolean,
    modifier: Modifier = Modifier,
    highlight: String? = null,
    before: List<GamutDot> = emptyList(),
) {
    val active = highlight ?: if (dots.size == 1) dots.first().id else null
    val summary = when {
        dots.isEmpty() -> "Colour chart"
        dots.size == 1 -> "Colour chart: ${capitalized(dots[0].id)} is ${if (dots[0].onTarget) "on target" else "off target"}"
        else -> "Colour chart: ${dots.count { it.onTarget }} of ${dots.size} colours on target"
    }
    val measurer = rememberTextMeasurer()
    val label = if (hdr) "P3 (HDR)" else "Rec. 709 (SDR)"

    Canvas(modifier.aspectRatio(1.4f).clearAndSetSemantics { contentDescription = summary }) {
        val p = 1.dp.toPx()
        val w = size.width
        val h = size.height
        val gamut = (if (hdr) ColourMath.Gamut.p3 else ColourMath.Gamut.rec709).primaries.map { ColourMath.upvp(it) }
        val fit = gamut + dots.map { it.target }
        val uMin = fit.minOf { it.x }
        val uMax = fit.maxOf { it.x }
        val vMin = fit.minOf { it.y }
        val vMax = fit.maxOf { it.y }
        val pad = 16 * p
        // one scale for both axes, so the triangle keeps its true shape
        val sc = min((w - pad * 2) / max(uMax - uMin, 0.01).toFloat(), (h - pad * 2) / max(vMax - vMin, 0.01).toFloat())
        val ox = (w - (uMax - uMin).toFloat() * sc) / 2
        val oy = (h - (vMax - vMin).toFloat() * sc) / 2

        fun P(pt: Point2) = Offset(ox + (pt.x - uMin).toFloat() * sc, h - oy - (pt.y - vMin).toFloat() * sc)
        fun inside(pt: Offset) = Offset(pt.x.coerceIn(6 * p, w - 6 * p), pt.y.coerceIn(6 * p, h - 6 * p))
        // Where the line from `v` through `w2` meets the line through `a` and `b`.
        fun hit(v: Offset, w2: Offset, a: Offset, b: Offset): Offset {
            val d1 = w2 - v
            val d2 = b - a
            val den = d1.x * d2.y - d1.y * d2.x
            if (abs(den) <= 0.000001f) return w2
            val t = ((a.x - v.x) * d2.y - (a.y - v.y) * d2.x) / den
            return Offset(v.x + d1.x * t, v.y + d1.y * t)
        }

        // the gamut triangle, and a line from each primary through white to the opposite side
        val corners = gamut.map { P(it) }
        val tri = Path().apply {
            moveTo(corners[0].x, corners[0].y)
            corners.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
        val white = P(ColourMath.upvp(ColourMath.d65))
        // Inside the triangle, the icon's colours: each hue toward its own corner, white in the middle.
        if (corners.size == 3) {
            val alpha = if (active == null) 0.3f else 0.2f
            clipPath(tri) {
                drawPath(tri, HueWheel.shading(corners, white), alpha = alpha)
                drawPath(
                    tri,
                    Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f), Color.White.copy(alpha = 0f)),
                        center = white, radius = min(w, h) * 0.3f),
                    alpha = alpha,
                )
            }
        }
        // which triangle this is
        val text = measurer.measure(label, Type.micro.copy(color = Theme.textSecondary))
        drawText(text, topLeft = Offset(w - 2 * p - text.size.width, h - 2 * p - text.size.height))

        if (corners.size == 3) {
            val medians = Path()
            for (i in 0 until 3) {
                val e = hit(corners[i], white, corners[(i + 1) % 3], corners[(i + 2) % 3])
                medians.moveTo(corners[i].x, corners[i].y)
                medians.lineTo(e.x, e.y)
            }
            drawPath(medians, Color.White.copy(alpha = 0.13f), style = Stroke(1 * p))
        }
        drawPath(tri, Color.White.copy(alpha = 0.45f), style = Stroke(1 * p))

        // the current colour last, on top
        val order = dots.filter { it.id != active } + dots.filter { it.id == active }
        for (d in order) {
            val col = gamutColour(d.id)
            val isActive = d.id == active
            val dim = active != null && !isActive
            val t = P(d.target)
            val m = inside(P(d.uv))
            val ringR = (if (isActive) 9 else 8) * p
            if (d.onTarget && !dim) {
                // on target: the ring blooms in its colour
                fillBlurred(circlePath(t, ringR + 3 * p), 6 * p, colour = col.copy(alpha = 0.8f))
                drawCircle(col.copy(alpha = 0.2f), ringR, t)
            }
            drawCircle(col.copy(alpha = if (dim) 0.45f else 0.9f), ringR, t, style = Stroke((if (isActive) 2f else 1.2f) * p))
            drawLine(col.copy(alpha = if (dim) 0.5f else 1f), t, m, (if (isActive) 1.5f else 1f) * p)
            val was = before.firstOrNull { it.id == d.id }
            if (was != null) {
                val b = inside(P(was.uv))
                drawLine(col.copy(alpha = 0.45f), b, m, 1 * p,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3 * p, 3 * p)))
                drawCircle(Theme.background, 3 * p, b)
                drawCircle(col.copy(alpha = 0.8f), 3 * p, b, style = Stroke(1.2f * p))
            }
            drawCircle(col.copy(alpha = if (dim) 0.65f else 1f), (if (isActive) 5 else 4) * p, m)
            if (d.onTarget) {
                tick(Offset(t.x + ringR + 3 * p, t.y - 3 * p), Offset(2.5f * p, 2.5f * p), Offset(7.5f * p, -3 * p), 1.4f * p)
            }
        }
    }
}

// MARK: - Greyscale chart

private class ChartPoint(val level: Double, val value: Double, val channel: String, val series: String)

/** A monotone cubic through the points (x ascending), like Swift Charts' `.monotone`. */
private fun monotonePath(pts: List<Offset>): Path {
    val path = Path()
    if (pts.isEmpty()) return path
    path.moveTo(pts[0].x, pts[0].y)
    if (pts.size == 1) return path
    val n = pts.size
    val dx = FloatArray(n - 1) { pts[it + 1].x - pts[it].x }
    val slope = FloatArray(n - 1) { if (dx[it] != 0f) (pts[it + 1].y - pts[it].y) / dx[it] else 0f }
    val m = FloatArray(n)
    m[0] = slope[0]
    m[n - 1] = slope[n - 2]
    for (i in 1 until n - 1) {
        m[i] = if (slope[i - 1] * slope[i] <= 0) 0f else {
            // Fritsch–Carlson weighted harmonic mean
            val w1 = 2 * dx[i] + dx[i - 1]
            val w2 = dx[i] + 2 * dx[i - 1]
            (w1 + w2) / (w1 / slope[i - 1] + w2 / slope[i])
        }
    }
    for (i in 0 until n - 1) {
        val h = dx[i] / 3
        path.cubicTo(pts[i].x + h, pts[i].y + m[i] * h, pts[i + 1].x - h, pts[i + 1].y - m[i + 1] * h,
            pts[i + 1].x, pts[i + 1].y)
    }
    return path
}

/**
 * Red, green and blue in each grey (100 = on target, the white line). With a `before`, its traces sit
 * faded behind the solid after traces.
 */
@Composable
fun GreyBalanceChart(report: Report, hdr: Boolean, modifier: Modifier = Modifier, before: Report? = null) {
    fun pts(rep: Report, series: String): List<ChartPoint> =
        rep.greys.filter { hdr || it.level >= 0.099 }.flatMap { g ->
            listOf(
                ChartPoint(g.level * 100, g.bal.x * 100, "R", series + "R"),
                ChartPoint(g.level * 100, g.bal.y * 100, "G", series + "G"),
                ChartPoint(g.level * 100, g.bal.z * 100, "B", series + "B"),
            )
        }
    // before first, so the after traces draw on top
    val points = ((before?.let { pts(it, "was") } ?: emptyList()) + pts(report, "now"))
        .filter { it.value.isFinite() && it.level.isFinite() }
    val dev = points.maxOfOrNull { abs(it.value - 100) } ?: 0.0
    val grid = max(5.0, ceil(dev / 5) * 5)          // the faint lines: ±grid %
    val span = grid * 1.45
    val lo = points.minOfOrNull { it.level } ?: 0.0
    val hi = max(points.maxOfOrNull { it.level } ?: 100.0, lo + 1)
    val ticks = (if (hdr) listOf(0.0, 25.0, 50.0, 75.0, 100.0) else listOf(10.0, 30.0, 50.0, 70.0, 100.0))
        .filter { it >= lo - 0.5 && it <= hi + 0.5 }
    val measurer = rememberTextMeasurer()
    val series = points.groupBy { it.series }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(160.dp)
            .clearAndSetSemantics { contentDescription = "Greyscale chart: red, green and blue against the target line" },
    ) {
        val p = 1.dp.toPx()
        val labels = ticks.map { measurer.measure("${it.roundToInt()}%", Type.tiny.copy(color = Theme.textSecondary)) }
        val labelH = (labels.maxOfOrNull { it.size.height } ?: 0).toFloat()
        val plotH = size.height - labelH - 4 * p
        val w = size.width
        fun x(level: Double) = ((level - lo) / (hi - lo)).toFloat() * w
        fun y(value: Double) = ((100 + span - value) / (2 * span)).toFloat() * plotH

        drawLine(Theme.hairline, Offset(0f, y(100 + grid)), Offset(w, y(100 + grid)), 1 * p)
        drawLine(Theme.hairline, Offset(0f, y(100 - grid)), Offset(w, y(100 - grid)), 1 * p)
        for ((name, s) in series) {
            val faded = name.startsWith("was")
            val path = monotonePath(s.sortedBy { it.level }.map { Offset(x(it.level), y(it.value)) })
            drawPath(
                path, Theme.channel(s.first().channel), alpha = if (faded) 0.3f else 1f,
                style = Stroke((if (faded) 1.5f else 2f) * p, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        drawLine(Theme.textSecondary, Offset(0f, y(100.0)), Offset(w, y(100.0)), 1 * p)

        ticks.forEachIndexed { i, t ->
            val l = labels[i]
            val lx = (x(t) - l.size.width / 2f).coerceIn(0f, max(0f, w - l.size.width))
            drawText(l, topLeft = Offset(lx, plotH + 4 * p))
        }
    }
}

// MARK: - Results

/** The score tiles for one measurement: greyscale, colours, and gamma (SDR) or brightness vs the HDR target. */
@Composable
fun ResultScores(report: Report, hdr: Boolean, modifier: Modifier = Modifier, before: Report? = null) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScoreTile("Greyscale", Analysis.greyAverage(report, hdr), was = before?.let { Analysis.greyAverage(it, hdr) })
        if (report.colours.isNotEmpty()) {
            ScoreTile("Colours", Analysis.colourAverage(report),
                was = before?.let { b -> if (b.colours.isEmpty()) null else Analysis.colourAverage(b) },
                kind = ScoreKind.colour)
        }
        // HDR brightness isn't scored: no step adjusts it, and TVs differ in how bright they show HDR.
        if (!hdr) {
            ScoreTile("Gamma", Analysis.gamma(report), was = before?.let { Analysis.gamma(it) }, digits = 2,
                caption = "target 2.40")
        }
    }
}

/**
 * The measurement's warnings, in the limit colour. Emits one Text per warning straight into the parent
 * layout (like the original's ForEach), so the parent's spacing applies.
 */
@Composable
fun ResultWarnings(warnings: List<String>) {
    for (w in warnings) {
        Text(w, style = Type.small, color = Theme.limit, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * The greyscale and colour detail cards ("12 Results · charts").
 * `showsColours`: false where the colour chart is already on screen (in the report panel).
 */
@Composable
fun ResultCharts(
    report: Report,
    hdr: Boolean,
    modifier: Modifier = Modifier,
    before: Report? = null,
    showsColours: Boolean = true,
) {
    val greyTitle = run {
        val lv = report.greys.filter { hdr || it.level >= 0.099 }.map { it.level * 100 }
        if (lv.isEmpty()) "Greyscale"
        else "Greyscale · ${lv.min().roundToInt()}–${lv.max().roundToInt()}%"
    }
    val colourTitle = run {
        val total = report.colours.size
        val ok = report.colours.count { it.de < 3 }
        if (ok == total) (if (total == 6) "Colours · all six on target" else "Colours · all on target")
        else "Colours · $ok of $total on target"
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (report.greys.isNotEmpty()) {
            Card {
                Text(greyTitle, style = Type.bodyStrong, color = Color.White)
                GreyBalanceChart(report, hdr, before = before)
                Text(if (before != null) "Before faded · after solid · flat target line" else "Flat line is the target",
                    style = Type.small, color = Theme.textSecondary)
            }
        }
        if (showsColours && report.colours.isNotEmpty()) {
            Card {
                Text(colourTitle, style = Type.bodyStrong, color = Color.White)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    GamutChart(gamutDots(report), hdr, Modifier.widthIn(max = 340.dp).fillMaxWidth(),
                        before = before?.let { gamutDots(it) } ?: emptyList())
                }
                Text(if (before != null) "Rings: target · hollow: before · dots: after" else "Rings: target · dots: your TV",
                    style = Type.small, color = Theme.textSecondary)
            }
        }
    }
}

/** The dE explanation under the score tiles. */
const val DE_EXPLAINER =
    "How far off (dE): under 1 is perfect. Greys under 2 and colours under 3 are excellent; TVs rarely match every colour exactly."

private val panelShape = RoundedCornerShape(22.dp)

/** The teal panel a report sits on (from ReportVisuals.swift). */
@Composable
fun ReportPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Theme.tealGradient, panelShape)
            .border(1.dp, Theme.hairline, panelShape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

/**
 * "What the numbers mean" (from ReportVisuals.swift): closed by default, so the explanation is there
 * without being in the way. `marginNote`: the phone's margin-of-error text ([MarginNote]); null leaves it out.
 */
@Composable
fun ScoreInfo(modifier: Modifier = Modifier, marginNote: String? = null) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .heightIn(min = 36.dp)
                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { open = !open },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(symbol("info.circle"), contentDescription = null, tint = Theme.tealBright, modifier = Modifier.size(14.dp))
            Text("What the numbers mean", style = Type.small, color = Theme.tealBright)
            Icon(symbol(if (open) "chevron.up" else "chevron.down"), contentDescription = null, tint = Theme.tealBright,
                modifier = Modifier.size(12.dp))
        }
        AnimatedVisibility(open, enter = fadeIn(tween(200)) + expandVertically(tween(200)),
            exit = fadeOut(tween(200)) + shrinkVertically(tween(200))) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(DE_EXPLAINER, style = Type.small, color = Theme.textSecondary)
                MarginNote(marginNote)
            }
        }
    }
}

/**
 * Scores, warnings and charts for one measurement.
 * `hero`: the report at a glance inside the teal panel (ReportHero, from ReportVisuals.swift; pass the
 * phone's margin of error to it there). `marginNote`: the phone's margin-of-error text for the kind of
 * screen (ProfileService.marginNote); null hides it (the demo's readings are simulated).
 */
@Composable
fun ReportSummary(
    report: Report,
    hdr: Boolean,
    modifier: Modifier = Modifier,
    before: Report? = null,
    marginNote: String? = null,
    hero: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ReportPanel(content = hero)
        ResultWarnings(report.warnings)
        ResultCharts(report, hdr, before = before, showsColours = false)
        ScoreInfo(marginNote = marginNote)
    }
}

/** The phone's margin of error under the scores: how close its camera profile got to a professional meter.
 *  `text` is ProfileService.marginNote(panel); nothing is shown when it's null. */
@Composable
fun MarginNote(text: String?, modifier: Modifier = Modifier) {
    if (text == null) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(symbol("info.circle"), contentDescription = null, tint = Theme.tealBright,
            modifier = Modifier.padding(top = 1.dp).size(13.dp))
        Text(text, style = Type.small, color = Theme.textSecondary)
    }
}

// MARK: - Previews

private fun previewReport(): Report {
    val greys = listOf(0.1, 0.3, 0.5, 0.7, 1.0).mapIndexed { i, l ->
        GreyResult(
            id = "G$i", level = l, name = "Grey ${(l * 100).roundToInt()}",
            xyz = Vec3(0.95, 1.0, 1.09), Y = l, targetY = l,
            bal = Vec3(1.02 - i * 0.004, 1.0, 0.97 + i * 0.006), rgb = Vec3(1.0, 1.0, 1.0),
            de = 2.5 - i * 0.3, clipped = false,
        )
    }
    val colours = listOf("RED" to Point2(0.63, 0.335), "GREEN" to Point2(0.31, 0.59), "BLUE" to Point2(0.152, 0.065))
        .zip(ColourMath.Gamut.rec709.primaries) { (n, xy), t ->
            ColourResult(name = n, xyz = Vec3(0.4, 0.2, 0.0), target = Vec3(0.4, 0.2, 0.0), xy = xy, txy = t,
                sat = 0.0, hue = 0.0, lum = 0.0, de = if (n == "GREEN") 4.2 else 1.5)
        }
    return Report(greys = greys.toMutableList(), colours = colours.toMutableList(),
        warnings = mutableListOf("The picture got 3% brighter during the run."))
}

@Preview(backgroundColor = 0xFF05171A, showBackground = true, heightDp = 1400)
@Composable
private fun ComponentsPreview() {
    TVCalibratorTheme {
        val rep = remember { previewReport() }
        Column(Modifier.screenBackground().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BalanceBar("Red", 0.03, Theme.red)
            BalanceBar("Blue", -0.005, Theme.blue)
            BalanceScaleLabels()
            WhiteTarget(Point2(0.199, 0.470), ColourMath.upvp(ColourMath.d65), ok = false,
                modifier = Modifier.width(132.dp))
            ResultScores(rep, hdr = false)
            ReportSummary(rep, hdr = false, marginNote = "A phone isn't a professional meter.") {
                Text("Hero", color = Color.White)
            }
        }
    }
}
