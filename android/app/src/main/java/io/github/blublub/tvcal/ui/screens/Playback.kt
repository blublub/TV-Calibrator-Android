// Port of TV Calibrator/Views/Playback.swift (TV Calibrator by Slidebean, https://github.com/slidebean/tv-calibrator, CC BY-NC 4.0). Changed: translated to Kotlin/Compose.
package io.github.blublub.tvcal.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.blublub.tvcal.engine.Patch
import io.github.blublub.tvcal.engine.PatchKind
import io.github.blublub.tvcal.engine.Patterns
import io.github.blublub.tvcal.engine.SignalMode
import io.github.blublub.tvcal.measure.SweepRunner
import io.github.blublub.tvcal.profiles.onYouTube
import io.github.blublub.tvcal.ui.LocalApp
import io.github.blublub.tvcal.ui.openUrl
import io.github.blublub.tvcal.ui.theme.Card
import io.github.blublub.tvcal.ui.theme.ModeBadge
import io.github.blublub.tvcal.ui.theme.SecondaryButton
import io.github.blublub.tvcal.ui.theme.Theme
import io.github.blublub.tvcal.ui.theme.Type
import io.github.blublub.tvcal.ui.theme.fmt
import io.github.blublub.tvcal.ui.theme.symbol
import io.github.blublub.tvcal.ui.theme.tint
import kotlin.math.log10
import kotlin.math.max

// Playing pattern videos and measuring them: the "play this on the TV" card and the progress while a
// video is measured (Figma: "Play on TV card", "08 … · waiting / measuring").

private val playbackCardShape = RoundedCornerShape(18.dp)

/**
 * "Play on the TV": the format's badge and where the video comes from, the video's title, and a link to
 * open it on YouTube (or get the USB file).
 * `compact`: the small card beside a live reading ("Play on the TV · SDR · YouTube", the title and, with
 * `patch`, a swatch of the pattern: "100% white").
 */
@Composable
fun PlayFileCard(
    file: String,
    mode: SignalMode,
    fromStart: Boolean = false,
    compact: Boolean = false,
    /** The pattern on screen, shown as a swatch and its name in the compact card. */
    patch: Patch? = null,
    modifier: Modifier = Modifier,
) {
    val links = LocalApp.current.links
    val context = LocalContext.current
    val sourceLabel = if (mode.onYouTube) "YouTube" else "USB file"
    val playLabel = if (fromStart) "Play on the TV, from the start" else "Play on the TV"
    // The video itself on YouTube (or its playlist), or the pattern download.
    val sourceURL: String? =
        if (mode.onYouTube) (links.links.video(file) ?: links.links.playlist(mode)) else links.links.download(mode)

    if (compact) {
        Column(
            modifier
                .fillMaxWidth()
                .background(Theme.card, playbackCardShape)
                .border(1.dp, Theme.hairline, playbackCardShape)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("$playLabel · ${mode.label} · $sourceLabel", style = Type.tiny, color = Theme.tealBright)
            Text(file, style = Type.brand(14f, FontWeight.SemiBold), color = Color.White)
            if (patch != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(24.dp, 18.dp)
                            .background(playbackPatchColour(patch.kind, patch.id, patch.level), RoundedCornerShape(2.dp)),
                    )
                    Text(playbackPatchLabel(patch.name), style = Type.tiny, color = Theme.textSecondary)
                }
            }
        }
    } else {
        // The video as a row: a little TV with a play button, the video's title, and where to open it.
        // The whole row is the link when there is one.
        val linked = sourceURL != null
        var m = modifier
            .fillMaxWidth()
            .clip(playbackCardShape)
            .background(Theme.card, playbackCardShape)
            .border(1.dp, Theme.hairline, playbackCardShape)
        if (sourceURL != null) m = m.clickable(role = Role.Button) { openUrl(context, sourceURL) }
        Row(
            m.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TVPlayArt(tint = mode.tint, modifier = Modifier.size(92.dp, 64.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(playLabel.uppercase(), style = Type.micro.copy(fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.6.sp),
                    color = mode.tint)
                Text(file, style = Type.bodyStrong, color = Color.White)
                if (linked) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (mode.onYouTube) "Open on YouTube" else "Get the USB file", style = Type.small,
                            color = Theme.tealBright)
                        Icon(symbol(if (mode.onYouTube) "arrow.up.right" else "arrow.down"), contentDescription = null,
                            tint = Theme.tealBright, modifier = Modifier.size(12.dp))
                    }
                } else {
                    Text(if (mode.onYouTube) "YouTube · on your TV" else "USB file", style = Type.small,
                        color = Theme.textSecondary)
                }
            }
        }
    }
}

/**
 * Progress while a video is measured: waiting for its flashes, then each patch in turn.
 * Shows the screen's headline ("Waiting for the video's flashes…", "Measuring 50% grey") unless
 * `showsTitle` is false. The Cancel button is pinned by the hosting screen (GuidedScreen); pass
 * `showsCancel = true` to draw it in the card instead.
 */
@Composable
fun MeasuringCard(
    runner: SweepRunner,
    file: String,
    mode: SignalMode,
    showsTitle: Boolean = true,
    showsCancel: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (runner.phase) {
            SweepRunner.Phase.Waiting -> MeasuringWaiting(file, mode, showsTitle)
            SweepRunner.Phase.Measuring -> MeasuringPatches(runner, mode, showsTitle)
            else -> {}
        }
        if (showsCancel && runner.running) {
            SecondaryButton("Cancel", onClick = { runner.cancel() })
        }
    }
}

// MARK: Waiting for the flashes

@Composable
private fun ColumnScope.MeasuringWaiting(file: String, mode: SignalMode, showsTitle: Boolean) {
    if (showsTitle) {
        Text("Start the video on the TV", style = Type.stepTitleStyle)
    }
    val pulse = rememberInfiniteTransition(label = "pulse")
    val a by pulse.animateFloat(1f, 0.35f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulseAlpha")
    Card(spacing = 12.dp, hero = true) {
        Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
            Icon(symbol("viewfinder"), contentDescription = null, tint = Theme.tealBright,
                modifier = Modifier.size(54.dp).alpha(a))
        }
        Text("Waiting for its flashes…", style = Type.bodyStrong, color = Color.White,
            modifier = Modifier.align(Alignment.CenterHorizontally))
    }
    WaitingFileCard(file, mode)
    Text(
        if (mode.onYouTube) "Its title starts with ${mode.packPrefix}. Ads are fine: the app keeps waiting."
        else "Its file name starts with ${mode.packPrefix}.",
        style = Type.captionBrand, color = Theme.textSecondary,
    )
}

// MARK: Measuring each patch

@Composable
private fun ColumnScope.MeasuringPatches(runner: SweepRunner, mode: SignalMode, showsTitle: Boolean) {
    val current = if (runner.index >= 0 && runner.index < runner.patches.size) runner.patches[runner.index] else null
    val doneCount = runner.patches.count { it.done }
    fun colour(p: SweepRunner.PatchProgress): Color =
        playbackPatchColour(p.kind, p.id, Patterns.patch(mode.isHDR, p.id)?.level)

    if (showsTitle) {
        Text(current?.let { "Measuring ${playbackPatchLabel(it.name)}" } ?: "Measuring…", style = Type.stepTitleStyle)
    }
    val swatch by animateColorAsState(current?.let { colour(it) } ?: Theme.card, tween(300), label = "swatch")
    Card(spacing = 12.dp, hero = true) {
        Box(Modifier.fillMaxWidth().height(130.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(110.dp).background(swatch, RoundedCornerShape(9.dp)))
        }
        Text("$doneCount of ${runner.patches.size} patches",
            style = Type.brand(19f, FontWeight.SemiBold).copy(fontFeatureSettings = "tnum"), color = Color.White,
            modifier = Modifier.align(Alignment.CenterHorizontally))
    }
    Card(spacing = 16.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .clearAndSetSemantics { contentDescription = "$doneCount of ${runner.patches.size} patches measured" },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            runner.patches.forEachIndexed { i, p ->
                val shape = RoundedCornerShape(3.dp)
                Box(
                    Modifier
                        .weight(1f)
                        .height(28.dp)
                        .background(if (p.done) (if (p.Y == null) Theme.limit else colour(p)) else Theme.card, shape)
                        .border(1.dp, if (!p.done && i == runner.index) Theme.accent else Theme.hairline, shape),
                )
            }
        }
        Sparkline(runner.recent.toList(), modifier = Modifier.fillMaxWidth().height(62.dp))
        val reading = "${fmt(runner.latestY, if ((runner.latestY ?: 0.0) < 10) 2 else 1)} cd/m²"
        Text(
            buildAnnotatedString {
                append("Camera sees ")
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color.White)) { append(reading) }
            },
            style = Type.captionBrand, color = Theme.textSecondary,
        )
    }
    Text("Keep the TV menu closed and the phone still.", style = Type.captionBrand, color = Theme.textSecondary)
}

/** The video to play while waiting: its format, where it comes from and its title. */
@Composable
private fun WaitingFileCard(file: String, mode: SignalMode) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Theme.card, playbackCardShape)
            .border(1.dp, Theme.hairline, playbackCardShape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ModeBadge(mode)
            Spacer(Modifier.weight(1f).width(8.dp))
            Text(if (mode.onYouTube) "YouTube · on your TV" else "USB file", style = Type.small,
                color = Theme.textSecondary, maxLines = 1)
        }
        Text(file, style = Type.brand(14f, FontWeight.SemiBold), color = Color.White)
    }
}

/** How a pattern looks on screen: its colour, or its grey level. */
private fun playbackPatchColour(kind: PatchKind, id: String, level: Double?): Color {
    if (kind == PatchKind.colour) return Theme.swatch(id)
    val l = (level ?: 1.0).coerceIn(0.0, 1.0).toFloat()
    return Color(l, l, l)
}

/** "100% WHITE" -> "100% white", "PQ 50% GREY (92 nits)" -> "50% grey (92 nits)". */
private fun playbackPatchLabel(name: String): String = name.replace("PQ ", "").lowercase()

/** The camera's recent brightness readings, as a line. */
@Composable
fun Sparkline(values: List<Double>, colour: Color = Theme.tealBright, modifier: Modifier = Modifier) {
    Canvas(modifier.clearAndSetSemantics {}) {
        if (values.size < 2) return@Canvas
        val logs = values.map { log10(max(it, 0.01)) }
        val lo = logs.minOrNull() ?: 0.0
        val hi = max(logs.maxOrNull() ?: 1.0, lo + 0.5)
        val path = Path()
        logs.forEachIndexed { i, v ->
            val pt = Offset(
                size.width * i / (logs.size - 1),
                (1 + (size.height - 2) * (1 - (v - lo) / (hi - lo))).toFloat(),
            )
            if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
        }
        drawPath(path, colour, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
