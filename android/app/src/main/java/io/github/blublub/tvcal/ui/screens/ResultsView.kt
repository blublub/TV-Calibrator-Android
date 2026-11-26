// Port of TV Calibrator/Views/ResultsView.swift (TV Calibrator by Slidebean, https://github.com/slidebean/tv-calibrator, CC BY-NC 4.0). Changed: translated to Kotlin/Compose.
package io.github.blublub.tvcal.ui.screens

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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.blublub.tvcal.R
import io.github.blublub.tvcal.engine.Analysis
import io.github.blublub.tvcal.engine.Report
import io.github.blublub.tvcal.engine.SignalMode
import io.github.blublub.tvcal.storage.PanelType
import io.github.blublub.tvcal.ui.FullSheet
import io.github.blublub.tvcal.ui.components.ReportPanel
import io.github.blublub.tvcal.ui.components.ResultCharts
import io.github.blublub.tvcal.ui.components.ResultWarnings
import io.github.blublub.tvcal.ui.components.Score
import io.github.blublub.tvcal.ui.components.ScoreInfo
import io.github.blublub.tvcal.ui.components.ScoreKind
import io.github.blublub.tvcal.ui.shareFile
import io.github.blublub.tvcal.ui.shareText
import io.github.blublub.tvcal.ui.theme.ModeBadge
import io.github.blublub.tvcal.ui.theme.ScreenWithBottomAction
import io.github.blublub.tvcal.ui.theme.Theme
import io.github.blublub.tvcal.ui.theme.Type
import io.github.blublub.tvcal.ui.theme.fmt
import io.github.blublub.tvcal.ui.theme.symbol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** "12 Nov 2026" in the user's locale and time zone (Swift's `.abbreviated` / `.long` date). */
internal fun resultsDate(date: Instant, style: FormatStyle = FormatStyle.MEDIUM): String =
    DateTimeFormatter.ofLocalizedDate(style).withZone(ZoneId.systemDefault()).format(date)

/**
 * The results of a calibration (Before vs After): one report panel (the headline, each score on its
 * gauge, the white point and the colours, all with where they started), then the newsletter, the
 * grey-by-grey chart (opened as a sheet) and the image to share.
 * Shown by the After step; the step screen around it supplies the title and the pinned main action.
 *
 * `date`: when the After check was measured. `margin`: the kind of screen, for the phone's margin of error;
 * null hides it (on Android there is no per-panel margin yet, so it is never shown).
 * `showsNewsletter`: the Untangled newsletter card, right under the scores.
 */
@Composable
fun ResultsView(
    report: Report,
    before: Report? = null,
    hdr: Boolean,
    mode: SignalMode,
    tvName: String,
    date: Instant = Instant.now(),
    margin: PanelType? = null,
    showsNewsletter: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var chartsOpen by remember { mutableStateOf(false) }
    // The phone's margin of error in dE for this kind of screen: not available on Android (no trained profiles).
    val marginDE: Double? = null

    val headline = run {
        if (before == null) return@run "Your picture, measured."
        val now = Analysis.greyAverage(report, hdr)
        val was = Analysis.greyAverage(before, hdr)
        if (now.isFinite() && was.isFinite() && now > was + 0.3) "Not quite there yet." else "A more natural picture."
    }
    val shareTitle = "$tvName · calibration results"

    // The shareable summary card (393 x 660), drawn off screen into a layer so it can be saved as an image.
    val cardLayer = rememberGraphicsLayer()

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // The report: the headline, each score on its gauge, the white point and the colours.
        ReportPanel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (before != null) "BEFORE → AFTER" else "MEASURED",
                    style = Type.badge.copy(letterSpacing = 0.8.sp), color = Theme.accent,
                    modifier = Modifier.alignByBaseline())
                Spacer(Modifier.weight(1f))
                // The screen's name and format are in the step's header, just above.
                Text(resultsDate(date), style = Type.small, color = Theme.textSecondary,
                    modifier = Modifier.alignByBaseline())
            }
            Text(headline, style = Type.pageTitleStyle)
            ReportHero(report, before = before, hdr = hdr, margin = marginDE)
        }

        if (showsNewsletter) NewsletterCard()
        ResultWarnings(report.warnings)

        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ResultsLinkLabel("chart.xyaxis.line", "Grey by grey") { chartsOpen = true }
            Spacer(Modifier.weight(1f))
            Box {
                ResultsLinkLabel("square.and.arrow.up", "Share") {
                    scope.launch {
                        val shared = runCatching {
                            val bitmap = cardLayer.toImageBitmap().asAndroidBitmap()
                            val file = withContext(Dispatchers.IO) {
                                File(context.cacheDir, "calibration-results.png").also { f ->
                                    f.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                                }
                            }
                            shareFile(context, file, mime = "image/png")
                        }.isSuccess
                        if (!shared) shareText(context, resultsShareText(shareTitle, report, before, hdr, date))
                    }
                }
                // Off screen: laid out at the card's size, recorded into the layer but never drawn.
                Box(
                    Modifier
                        .clearAndSetSemantics {}
                        .layout { measurable, _ ->
                            val placeable = measurable.measure(Constraints.fixed(393.dp.roundToPx(), 660.dp.roundToPx()))
                            layout(0, 0) { placeable.place(0, 0) }
                        }
                        .drawWithContent { cardLayer.record { this@drawWithContent.drawContent() } },
                ) {
                    ShareSummaryCard(report, before = before, hdr = hdr, mode = mode, tvName = tvName, date = date,
                        margin = marginDE)
                }
            }
        }
        ScoreInfo(marginNote = null)
    }

    if (chartsOpen) {
        FullSheet(onDismiss = { chartsOpen = false }) {
            ResultsChartsView(report, before = before, hdr = hdr, mode = mode, tvName = tvName,
                onDismiss = { chartsOpen = false })
        }
    }
}

/** A teal link with a symbol: "Grey by grey", "Share". */
@Composable
private fun ResultsLinkLabel(icon: String, text: String, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(symbol(icon), contentDescription = null, tint = Theme.tealBright, modifier = Modifier.size(16.dp))
        Text(text, style = Type.callout14, color = Theme.tealBright)
    }
}

/** The results as text, when the image can't be made. */
private fun resultsShareText(title: String, report: Report, before: Report?, hdr: Boolean, date: Instant): String {
    fun line(label: String, now: Double?, was: Double?, kind: ScoreKind): String {
        val w = was?.takeIf { it.isFinite() }
        val numbers = if (w != null) "${fmt(w, 1)} → ${fmt(now, 1)} dE" else "${fmt(now, 1)} dE"
        return "$label: ${Score.word(now, kind)} ($numbers)"
    }
    val lines = mutableListOf(title, resultsDate(date, FormatStyle.LONG))
    lines += line("Greyscale", Analysis.greyAverage(report, hdr), before?.let { Analysis.greyAverage(it, hdr) }, ScoreKind.grey)
    if (report.colours.isNotEmpty()) {
        lines += line("Colours", Analysis.colourAverage(report),
            before?.takeIf { it.colours.isNotEmpty() }?.let { Analysis.colourAverage(it) }, ScoreKind.colour)
    }
    return lines.joinToString("\n")
}

/** A title bar for a sheet or a pushed screen: back (or close) and the title. */
@Composable
private fun ResultsTitleBar(title: String, onBack: (() -> Unit)?) {
    Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back",
                    tint = Theme.tealBright)
            }
        }
        Text(title, style = Type.headlineBrand, color = Color.White, modifier = Modifier.semantics { heading() })
    }
}

// MARK: - Charts

/**
 * "Your picture, in detail": the greyscale and colour charts ("12 Results · charts"), opened from ResultsView.
 * `onDismiss`: goes back (shows a back button when set).
 */
@Composable
fun ResultsChartsView(
    report: Report,
    before: Report? = null,
    hdr: Boolean,
    mode: SignalMode,
    tvName: String,
    onDismiss: (() -> Unit)? = null,
) {
    ScreenWithBottomAction { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            ResultsTitleBar("Grey by grey", onDismiss)
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(tvName, style = Type.bodyStrong, color = Color.White, modifier = Modifier.weight(1f))
                    ModeBadge(mode)
                }
                ResultCharts(report, hdr, before = before)
                ResultWarnings(report.warnings)
            }
        }
    }
}

// MARK: - Shareable summary

private val shareCardShape = RoundedCornerShape(24.dp)

/**
 * The image people share: the screen, the format and the date, then the same report as the results
 * (scores on their gauges, the white point, the colours), drawn once with no animation.
 * `margin`: the phone's margin of error, in dE.
 */
@Composable
fun ShareSummaryCard(
    report: Report,
    before: Report? = null,
    hdr: Boolean,
    mode: SignalMode,
    tvName: String,
    date: Instant = Instant.now(),
    margin: Double? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(Theme.background, shareCardShape)
            .background(Theme.tealGradient, shareCardShape)
            .border(1.dp, Theme.hairline, shareCardShape)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.app_name), style = Type.bodyStrong, color = Color.White)
            Spacer(Modifier.weight(1f).width(8.dp))
            ModeBadge(mode)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText(
                tvName,
                style = Type.brand(30f).copy(letterSpacing = (-0.6).sp, color = Color.White),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = 30.sp),
            )
            Text((if (before != null) "Before → after · " else "Measured · ") + resultsDate(date, FormatStyle.LONG),
                style = Type.captionBrand, color = Theme.textSecondary)
        }
        ReportHero(report, before = before, hdr = hdr, margin = margin, animated = false)
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth()) {
            Text("based on TV Calibrator by Slidebean", style = Type.tiny, color = Theme.accent, modifier = Modifier.alignByBaseline())
            Spacer(Modifier.weight(1f))
            Text("Measured with phone", style = Type.tiny, color = Theme.textSecondary,
                modifier = Modifier.alignByBaseline())
        }
    }
}

