// Port of TV Calibrator/Views/ReportVisuals.swift (TV Calibrator by Slidebean, https://github.com/slidebean/tv-calibrator, CC BY-NC 4.0). Changed: translated to Kotlin/Compose.
package io.github.blublub.tvcal.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.blublub.tvcal.engine.Analysis
import io.github.blublub.tvcal.engine.ColourMath
import io.github.blublub.tvcal.engine.GreyResult
import io.github.blublub.tvcal.engine.Point2
import io.github.blublub.tvcal.engine.Report
import io.github.blublub.tvcal.ui.components.GamutChart
import io.github.blublub.tvcal.ui.components.Score
import io.github.blublub.tvcal.ui.components.ScoreKind
import io.github.blublub.tvcal.ui.components.WhiteTarget
import io.github.blublub.tvcal.ui.components.gamutDots
import io.github.blublub.tvcal.ui.theme.Theme
import io.github.blublub.tvcal.ui.theme.Type
import io.github.blublub.tvcal.ui.theme.fmt
import io.github.blublub.tvcal.ui.theme.symbol
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// A measurement as a picture rather than a page of text: each score on a gauge that shows before and
// after, the white point against D65 and the colours against their targets. Used by the results, the
// starting point, the screen home and the image people share.

// MARK: - The dE gauge

private fun Double?.gaugeFinite(): Double? = this?.takeIf { it.isFinite() }

/** Fills a circle with a Gaussian blur of `radius` px (the marker's bloom, SwiftUI's `.shadow`). */
private fun DrawScope.gaugeBloom(centre: Offset, r: Float, blur: Float, colour: Color) {
    if (colour.alpha <= 0f) return
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = colour.toArgb()
        if (blur > 0) maskFilter = android.graphics.BlurMaskFilter(blur, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    drawIntoCanvas { it.nativeCanvas.drawCircle(centre.x, centre.y, r, paint) }
}

/**
 * One score on a scale from "off" (left) to "perfect" (right): orange, white and green zones, a hollow
 * marker where it was, a solid one where it is now, and a line between them. The soft band around the
 * marker is the phone's margin of error.
 * `margin`: the phone's margin of error, in dE. `animated`: false for the shared image, which is drawn once.
 */
@Composable
fun DeGauge(
    value: Double?,
    was: Double? = null,
    kind: ScoreKind = ScoreKind.grey,
    margin: Double? = null,
    animated: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val progress = remember { Animatable(if (animated) 0f else 1f) }
    LaunchedEffect(animated) {
        if (!animated || progress.value != 0f) return@LaunchedEffect
        delay(250)
        // SwiftUI spring(response: 0.9, dampingFraction: 0.82)
        progress.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = 48.7f))
    }
    val bands = Score.bands(kind)
    // Where the scale ends (its left edge), in dE: a little past "Good".
    val top = bands[3] * 1.3
    fun fraction(de: Double): Float = (1 - min(max(de / top, 0.0), 1.0)).toFloat()
    val greenEdge = bands[if (kind == ScoreKind.grey) 1 else 2]
    val now = value.gaugeFinite()
    val before = was.gaugeFinite()
    val markerTint = Score.tint(value, kind)

    Canvas(modifier.fillMaxWidth().height(22.dp).clearAndSetSemantics {}) {
        val t = if (animated) progress.value.toDouble() else 1.0
        // The marker travels from where it was to where it is now.
        val shown: Double? = now?.let { n -> (before ?: n) + (n - (before ?: n)) * t }
        val p = 1.dp.toPx()
        val r = 9 * p
        val w = size.width
        val cy = size.height / 2
        val span = max(w - 2 * r, 1f)

        // the zones
        val good = fraction(bands[3])
        val green = fraction(greenEdge)
        val zones = Brush.horizontalGradient(
            0f to Theme.limit.copy(alpha = 0.6f),
            good to Theme.limit.copy(alpha = 0.6f),
            good to Color.White.copy(alpha = 0.32f),
            green to Color.White.copy(alpha = 0.32f),
            green to Theme.green.copy(alpha = 0.75f),
            1f to Theme.green.copy(alpha = 0.75f),
            startX = 0f, endX = w,
        )
        drawRoundRect(zones, topLeft = Offset(0f, cy - 3 * p), size = Size(w, 6 * p), cornerRadius = CornerRadius(3 * p))

        if (shown != null && margin != null && margin > 0) {
            val half = max(span * (margin / top).toFloat(), r)
            drawRoundRect(
                Color.White.copy(alpha = 0.13f),
                topLeft = Offset(r + span * fraction(shown) - half, cy - 9 * p),
                size = Size(half * 2, 18 * p),
                cornerRadius = CornerRadius(9 * p),
            )
        }
        if (before != null && shown != null) {
            val a = r + span * fraction(before)
            val b = r + span * fraction(shown)
            drawRoundRect(
                Color.White.copy(alpha = 0.55f),
                topLeft = Offset(min(a, b), cy - 1 * p),
                size = Size(abs(b - a), 2 * p),
                cornerRadius = CornerRadius(1 * p),
            )
            drawCircle(Theme.background, 5 * p, Offset(a, cy))
            drawCircle(Color.White.copy(alpha = 0.75f), 5 * p - 0.75f * p, Offset(a, cy), style = Stroke(1.5f * p))
        }
        if (shown != null) {
            // In the green zone, the marker blooms.
            val landed = shown < greenEdge
            val c = Offset(r + span * fraction(shown), cy)
            if (landed) gaugeBloom(c, r, 7 * p, Theme.green.copy(alpha = 0.9f))
            drawCircle(markerTint, r, c)
            drawCircle(Theme.background, r - 1 * p, c, style = Stroke(2 * p))
        }
    }
}

/** "Greyscale" and "4.8 → 1.2 dE", the word for it ("Excellent") in its colour, and the gauge. */
@Composable
fun ScoreGaugeRow(
    label: String,
    value: Double?,
    was: Double? = null,
    kind: ScoreKind = ScoreKind.grey,
    margin: Double? = null,
    animated: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val hasWas = was?.isFinite() ?: false
    val numbers = if (hasWas) "${fmt(was, 1)} → ${fmt(value, 1)} dE" else "${fmt(value, 1)} dE"
    val word = Score.word(value, kind)
    Column(
        modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "$label: $word, $numbers" },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = Type.captionBrand, color = Theme.textSecondary,
                modifier = Modifier.weight(1f).alignByBaseline())
            Text(numbers, style = Type.small.copy(fontFeatureSettings = "tnum"), color = Theme.textSecondary,
                modifier = Modifier.alignByBaseline())
        }
        BasicText(
            word,
            style = Type.brand(26f, FontWeight.SemiBold).copy(letterSpacing = (-0.5).sp, color = Score.tint(value, kind)),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = (26f * 0.7f).sp, maxFontSize = 26.sp),
        )
        DeGauge(value, was = was, kind = kind, margin = margin, animated = animated)
    }
}

/** Under the gauges: which end is which, and the before / after markers. */
@Composable
private fun GaugeLegend(hasBefore: Boolean) {
    val style = Type.micro
    Row(
        Modifier.fillMaxWidth().clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Off", style = style, color = Theme.textSecondary)
        Spacer(Modifier.weight(1f))
        if (hasBefore) {
            Box(Modifier.size(7.dp).border(1.2.dp, Color.White.copy(alpha = 0.75f), CircleShape))
            Text("before", style = style, color = Theme.textSecondary)
            Box(Modifier.padding(start = 6.dp).size(7.dp).background(Color.White, CircleShape))
            Text("after", style = style, color = Theme.textSecondary)
            Spacer(Modifier.weight(1f))
        }
        Text("Perfect", style = style, color = Theme.textSecondary)
    }
}

// MARK: - The report

private fun heroBrightest(r: Report?): GreyResult? = r?.greys?.maxByOrNull { it.level }

private fun heroUV(g: GreyResult): Point2 = ColourMath.upvp(ColourMath.xyzToXY(g.xyz))

/**
 * A measurement at a glance: the greyscale and colour scores on their gauges, then the white point
 * against D65 and the colours against their targets, with where each started when there's a `before`.
 * `margin`: the phone's margin of error, in dE; null hides it.
 */
@Composable
fun ReportHero(
    report: Report,
    before: Report? = null,
    hdr: Boolean,
    margin: Double? = null,
    animated: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colourWas: Double? = if (before == null || before.colours.isEmpty()) null else Analysis.colourAverage(before)
    val coloursCaption = "${report.colours.count { it.de < 3 }} of ${report.colours.size} colours on target"

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScoreGaugeRow("Greyscale", Analysis.greyAverage(report, hdr),
            was = before?.let { Analysis.greyAverage(it, hdr) },
            kind = ScoreKind.grey, margin = margin, animated = animated)
        if (report.colours.isNotEmpty()) {
            ScoreGaugeRow("Colours", Analysis.colourAverage(report), was = colourWas,
                kind = ScoreKind.colour, margin = margin, animated = animated)
        }
        GaugeLegend(hasBefore = before != null)

        // the charts
        val white = heroBrightest(report)
        if (white != null || report.colours.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (white != null) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        WhiteTarget(heroUV(white), ColourMath.upvp(ColourMath.d65), ok = white.de < 2,
                            modifier = Modifier.widthIn(max = 132.dp).fillMaxWidth(),
                            was = heroBrightest(before)?.let { heroUV(it) })
                        Text("White point", style = Type.tiny, color = Theme.textSecondary)
                    }
                }
                if (report.colours.isNotEmpty()) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        GamutChart(gamutDots(report), hdr, Modifier.fillMaxWidth(),
                            before = before?.let { gamutDots(it) } ?: emptyList())
                        Text(coloursCaption, style = Type.tiny, color = Theme.textSecondary)
                    }
                }
            }
        }
        if (!hdr) {
            val gamma = Analysis.gamma(report)
            if (gamma != null) {
                Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Gamma", style = Type.small, color = Theme.textSecondary)
                    Text(fmt(gamma, 2), style = Type.small.copy(fontFeatureSettings = "tnum"), color = Color.White)
                    Text("· target 2.40", style = Type.small, color = Theme.textSecondary)
                }
            }
        }
    }
}

// ReportPanel and ScoreInfo live in ui/components/Components.kt.

// MARK: - Small pictures for the steps

/** A little TV with a play button, in a format's colour: "play this on the TV". */
@Composable
fun TVPlayArt(tint: Color = Theme.tealBright, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(Theme.surface, RoundedCornerShape(12.dp))
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Box(
                Modifier
                    .size(64.dp, 38.dp)
                    .background(Theme.background, RoundedCornerShape(5.dp))
                    .border(1.5.dp, tint, RoundedCornerShape(5.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(symbol("play.fill"), contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
            }
            Box(Modifier.size(20.dp, 2.dp).background(tint, RoundedCornerShape(1.dp)))
        }
    }
}

/** What to do, in order, as numbered tiles: "1 Tap Measure → 2 Start the video". */
@Composable
fun OrderStrip(steps: List<String>, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        steps.forEachIndexed { i, text ->
            if (i > 0) {
                Icon(symbol("arrow.right"), contentDescription = null, tint = Theme.textSecondary,
                    modifier = Modifier.size(13.dp))
            }
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .heightIn(min = 52.dp)
                    .background(Theme.card, RoundedCornerShape(14.dp))
                    .padding(10.dp)
                    .semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(22.dp).background(Theme.accent, CircleShape), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", style = Type.badge, color = Theme.background)
                }
                Text(text, style = Type.captionStrong, color = Color.White)
            }
        }
    }
}
