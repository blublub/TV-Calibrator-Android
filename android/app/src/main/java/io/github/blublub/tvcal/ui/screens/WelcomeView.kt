// Port of TV Calibrator/Views/WelcomeView.swift (TV Calibrator by Slidebean, https://github.com/slidebean/tv-calibrator, CC BY-NC 4.0). Changed: translated to Kotlin/Compose.
// The video preview opens the link in the browser or YouTube app; it draws no logo or brand chart.
package io.github.blublub.tvcal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.blublub.tvcal.R
import io.github.blublub.tvcal.ui.LocalApp
import io.github.blublub.tvcal.ui.openUrl
import io.github.blublub.tvcal.ui.theme.Theme
import io.github.blublub.tvcal.ui.theme.Type
import io.github.blublub.tvcal.ui.theme.symbol

/**
 * For people who found the app without seeing the Slidebean video: what calibration is, how the app
 * does it, what you need, and the video that explains it. Shown once on first launch, and again from
 * "How it works" on the home screen.
 */
@Composable
fun WelcomeView(onFinish: () -> Unit, onDismiss: () -> Unit) {
    val links = LocalApp.current.links
    val appName = stringResource(R.string.app_name)
    val videoTitle = stringResource(R.string.home_welcome_video_title)
    val videoText = stringResource(R.string.home_welcome_video_text)
    val tvTitle = stringResource(R.string.home_welcome_tv_title)
    val tvText = stringResource(R.string.home_welcome_tv_text, appName)
    val meterTitle = stringResource(R.string.home_welcome_meter_title)
    val meterText = stringResource(R.string.home_welcome_meter_text)
    val meterBullets = listOf(
        stringResource(R.string.home_welcome_meter_bullet1),
        stringResource(R.string.home_welcome_meter_bullet2),
        stringResource(R.string.home_welcome_meter_bullet3),
    )
    val needTitle = stringResource(R.string.home_welcome_need_title)
    val needText = stringResource(R.string.home_welcome_need_text)
    val needBullets = listOf(
        stringResource(R.string.home_welcome_need_bullet1),
        stringResource(R.string.home_welcome_need_bullet2),
        stringResource(R.string.home_welcome_need_bullet3),
        stringResource(R.string.home_welcome_need_bullet4),
    )
    val closeLabel = stringResource(R.string.home_close)

    val pages = buildList {
        links.links.explainerURL?.let { url ->
            add(
                WalkPage(
                    symbol = "",
                    title = videoTitle,
                    text = videoText,
                    extra = { VideoPreview(url = url) },
                ),
            )
        }
        add(
            WalkPage(
                symbol = "tv",
                title = tvTitle,
                text = tvText,
            ),
        )
        add(
            WalkPage(
                symbol = "camera.viewfinder",
                title = meterTitle,
                text = meterText,
                bullets = meterBullets,
            ),
        )
        add(
            WalkPage(
                symbol = "", title = needTitle,
                text = needText,
                bullets = needBullets,
                art = { PlacementArt() },
            ),
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Theme.background)
            .systemBarsPadding(),
    ) {
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
                    .clickable {
                        onFinish()
                        onDismiss()
                    }
                    .semantics { contentDescription = closeLabel },
                contentAlignment = Alignment.Center,
            ) {
                Icon(symbol("xmark"), contentDescription = null, tint = Theme.tealBright, modifier = Modifier.size(22.dp))
            }
            Text(
                stringResource(R.string.home_welcome), style = Type.headlineBrand, color = Color.White, maxLines = 1,
                modifier = Modifier
                    .align(Alignment.Center)
                    .semantics { heading() },
            )
        }
        val pinned = remember { PinnedActionHostState() }
        CompositionLocalProvider(LocalPinnedActionHost provides pinned) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Walkthrough(
                    pages = pages, finishTitle = stringResource(R.string.home_get_started),
                    section = stringResource(R.string.home_how_it_works),
                    onFinish = {
                        onFinish()
                        onDismiss()
                    },
                )
            }
        }
        Box(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp)) {
            PinnedActionBar(pinned)
        }
    }
}

private val frameTop = Color(0xFF0A3A4A)
private val frameBottom = Color(0xFF010D12)

/**
 * The explainer video as something that plainly plays on YouTube: a 16:9 frame with a play button and
 * "YouTube". `compact` is the small thumbnail-and-text row for the home screen. Tapping opens the link.
 */
@Composable
fun VideoPreview(url: String, compact: Boolean = false) {
    val context = LocalContext.current
    val description = stringResource(R.string.home_video_desc)
    val modifier = Modifier
        .fillMaxWidth()
        .clickable { openUrl(context, url) }
        .clearAndSetSemantics {
            contentDescription = description
            role = Role.Button
        }
    if (compact) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            VideoFrame(play = 30.dp, compact = true, modifier = Modifier.width(112.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(R.string.home_video_compact), style = Type.bodyStrong, color = Color.White)
                VideoSource()
            }
        }
    } else {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            VideoFrame(play = 60.dp, compact = false, modifier = Modifier.fillMaxWidth())
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(R.string.home_video_title), style = Type.bodyStrong, color = Color.White)
                VideoSource()
            }
        }
    }
}

@Composable
private fun VideoSource() {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.home_video_source), style = Type.small, color = Theme.tealBright)
        Icon(symbol("arrow.up.right"), contentDescription = null, tint = Theme.tealBright, modifier = Modifier.size(12.dp))
    }
}

/** The 16:9 picture: dark teal glow, a white play button in front. */
@Composable
private fun VideoFrame(play: Dp, compact: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(if (compact) 10.dp else 16.dp)
    val radius = with(LocalDensity.current) { 260.dp.toPx() }
    BoxWithConstraints(
        modifier
            .aspectRatio(16f / 9f)
            .clip(shape)
            .border(1.dp, Theme.hairline, shape),
    ) {
        val centre = with(LocalDensity.current) {
            androidx.compose.ui.geometry.Offset(maxWidth.toPx() * 0.3f, maxHeight.toPx() * 0.25f)
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.radialGradient(listOf(frameTop, frameBottom), center = centre, radius = radius)),
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
        Box(
            Modifier
                .align(Alignment.Center)
                .size(play)
                .shadow(8.dp, CircleShape)
                .background(Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                symbol("play.fill"), contentDescription = null, tint = frameBottom,
                modifier = Modifier
                    .offset(x = play * 0.04f)
                    .size(play * 0.5f),
            )
        }
        if (!compact) {
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(percent = 50))
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(symbol("play.rectangle.fill"), contentDescription = null, tint = Color.White,
                    modifier = Modifier.size(14.dp))
                Text("YouTube", style = Type.captionStrong, color = Color.White, maxLines = 1,
                    overflow = TextOverflow.Clip, modifier = Modifier.widthIn(max = 120.dp))
            }
        }
    }
}
