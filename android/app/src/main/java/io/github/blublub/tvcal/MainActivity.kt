// Android counterpart of "TV Calibrator/App/TVCalibratorApp.swift" and RootView's navigation
// (TV Calibrator by Slidebean, https://github.com/slidebean/tv-calibrator, CC BY-NC 4.0).
package io.github.blublub.tvcal

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.blublub.tvcal.ui.CameraPermission
import io.github.blublub.tvcal.ui.LocalApp
import io.github.blublub.tvcal.ui.LocalCameraPermission
import io.github.blublub.tvcal.ui.LocalNav
import io.github.blublub.tvcal.ui.Nav
import io.github.blublub.tvcal.ui.Route
import io.github.blublub.tvcal.ui.screens.CameraSetupView
import io.github.blublub.tvcal.ui.screens.IntroView
import io.github.blublub.tvcal.ui.screens.LiveMeterView
import io.github.blublub.tvcal.ui.screens.SettingsView
import io.github.blublub.tvcal.ui.screens.TVHomeView
import io.github.blublub.tvcal.ui.screens.TVListView
import io.github.blublub.tvcal.ui.theme.TVCalibratorTheme
import io.github.blublub.tvcal.ui.theme.Theme
import io.github.blublub.tvcal.ui.theme.screenBackground
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val app by lazy { AppModel(applicationContext) }
    private val nav = Nav()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The meter reads for minutes at a time: keep the screen on while the app is open.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            var granted by remember {
                mutableStateOf(checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            }
            val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
            TVCalibratorTheme {
                CompositionLocalProvider(
                    LocalApp provides app,
                    LocalNav provides nav,
                    LocalCameraPermission provides CameraPermission(granted) { ask.launch(Manifest.permission.CAMERA) },
                ) {
                    AppRoot(app, nav)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Free the camera for other apps while in the background; screens start it again when needed.
        if (!isChangingConfigurations) app.camera.stop()
    }
}

@Composable
private fun AppRoot(app: AppModel, nav: Nav) {
    var intro by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(1700)
        intro = false
    }
    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }
    Box(Modifier.fillMaxSize().screenBackground()) {
        when (val r = nav.current) {
            Route.Home -> TVListView()
            is Route.TV -> {
                val tv = app.tv(r.id)
                if (tv != null) {
                    TVHomeView(app, tv)
                } else {
                    Text("This screen was removed.", color = Theme.textSecondary,
                        modifier = Modifier.align(Alignment.Center).padding(20.dp))
                }
            }
            Route.Camera -> CameraSetupView(app.camera, app.profiles)
            Route.Live -> LiveMeterView()
            Route.Settings -> SettingsView(app.profiles)
        }
        AnimatedVisibility(visible = intro, exit = fadeOut(tween(600))) { IntroView() }
    }
}
