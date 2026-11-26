// Port of TV Calibrator/Views/IntroView.swift (TV Calibrator by Slidebean, https://github.com/slidebean/tv-calibrator, CC BY-NC 4.0). Changed: translated to Kotlin/Compose.
// The Slidebean logo is not used: the app's name stands in its place.
package io.github.blublub.tvcal.ui.screens

import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import io.github.blublub.tvcal.R
import io.github.blublub.tvcal.ui.theme.Type

/** Near-black #030F11 (the Swift `IntroView.base`), the colour behind the opening screen. */
val IntroViewBase = Color(0xFF030F11)

/** The faintest teal (#082A2C) behind the name. */
private val introGlow = Color(0xFF082A2C)

/**
 * The opening screen: only the app's name, on a near-black with a faint teal glow, for a moment at
 * launch.
 */
@Composable
fun IntroView() {
    var shown by remember { mutableStateOf(false) }
    val progress by animateFloatAsState(if (shown) 1f else 0f, tween(900, easing = EaseOut), label = "intro")
    val radius = with(LocalDensity.current) { 320.dp.toPx() }
    val name = stringResource(R.string.app_name)

    Box(
        Modifier
            .fillMaxSize()
            .background(IntroViewBase)
            .clearAndSetSemantics { contentDescription = "$name, based on TV Calibrator by Slidebean" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .alpha(progress)
                .background(Brush.radialGradient(listOf(introGlow, IntroViewBase), radius = radius)),
        )
        Text(
            name,
            style = Type.headlineBrand,
            color = Color.White,
            modifier = Modifier
                .scale(0.92f + 0.08f * progress)
                .alpha(progress),
        )
    }
    LaunchedEffect(Unit) { shown = true }
}
