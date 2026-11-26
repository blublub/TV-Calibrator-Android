package io.github.blublub.tvcal.ui

import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.blublub.tvcal.R
import io.github.blublub.tvcal.camera.CameraMeter
import io.github.blublub.tvcal.camera.MetadataProfile
import io.github.blublub.tvcal.engine.CameraProfile
import io.github.blublub.tvcal.engine.ColourMath
import io.github.blublub.tvcal.engine.LiveAnalysis
import io.github.blublub.tvcal.engine.Vec3
import io.github.blublub.tvcal.engine.fixed
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Correlated colour temperature from xy (McCamy's approximation), for a quick plausibility check. */
fun cct(x: Double, y: Double): Double {
    val n = (x - 0.3320) / (0.1858 - y)
    return 449 * n * n * n + 3525 * n * n + 6823.3 * n + 5520.33
}

/** The camera preview with the yellow 10% measuring box in the middle. */
@Composable
fun CameraViewfinder(meter: CameraMeter, running: Boolean, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    Box(
        modifier
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF30383A)),
    ) {
        if (running) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            var surface: Surface? = null
                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                val size = meter.engine.previewSize()
                                st.setDefaultBufferSize(size.width, size.height)
                                // The sensor is landscape; the phone is held upright.
                                setTransform(android.graphics.Matrix().apply {
                                    val cx = w / 2f; val cy = h / 2f
                                    postRotate(90f, cx, cy)
                                    postScale(w.toFloat() / h, h.toFloat() / w, cx, cy)
                                })
                                val s = Surface(st).also { surface = it }
                                scope.launch { meter.engine.setPreview(s) }
                            }
                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                scope.launch { meter.engine.setPreview(null) }
                                surface?.release()
                                return true
                            }
                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                        }
                    }
                },
            )
        }
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.1f)
                .aspectRatio(3f / 4f)
                .border(1.5.dp, Color(0xFFFFD60A)),
        )
    }
}

/**
 * Camera check without a TV: preview, darkness measurement and live readings with the approximate
 * profile built from the camera's own colour data. Every reading is also logged (logcat tag TVCAL).
 */
@Composable
fun CameraTestScreen(meter: CameraMeter, hasPermission: Boolean, requestPermission: () -> Unit) {
    val scope = rememberCoroutineScope()
    val running by meter.running.collectAsState()
    val busy by meter.busy.collectAsState()
    val message by meter.message.collectAsState()
    val dark by meter.dark.collectAsState()
    val reading by meter.lastReading.collectAsState()
    val logLines by meter.logLines.collectAsState()
    var live by remember { mutableStateOf(false) }
    var profile by remember { mutableStateOf<CameraProfile?>(null) }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            meter.start()
            profile = meter.engine.colorInfo?.let {
                MetadataProfile.profile(it, "android", "this phone", meter.settings)
            }
        }
    }
    LaunchedEffect(live) {
        while (live && isActive) {
            val (r, problem) = meter.measure()
            if (r == null) {
                problem?.let { meter.log(it) }
                delay(500)
                continue
            }
            val p = profile ?: continue
            val xyz = p.xyz(Vec3(r.rgb[0], r.rgb[1], r.rgb[2]))
            val xy = ColourMath.xyzToXY(xyz)
            meter.log("t=${fixed(r.duration * 1000, 2)}ms peak=${fixed(r.peak, 3)} uneven=${fixed(r.uneven, 3)} " +
                "rgb/s=${r.rgb.joinToString(",") { fixed(it, 1) }} Y=${fixed(xyz.y, 2)} xy=${fixed(xy.x, 4)},${fixed(xy.y, 4)} " +
                "CCT=${fixed(cct(xy.x, xy.y), 0)}K")
        }
    }
    DisposableEffect(Unit) { onDispose { meter.stop() } }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.live_camtest_title), style = MaterialTheme.typography.headlineSmall)
        if (!hasPermission) {
            Text(stringResource(R.string.live_camtest_permission))
            Button(onClick = requestPermission) { Text(stringResource(R.string.live_camtest_allow)) }
            return@Column
        }
        CameraViewfinder(meter, running, Modifier.fillMaxWidth())
        Text(message)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = running && !busy && !live, onClick = { scope.launch { meter.measureDark() } }) {
                Text(stringResource(if (dark == null) R.string.live_camtest_dark else R.string.live_camtest_dark_again))
            }
            Button(enabled = running && dark != null, onClick = { live = !live }) { Text(stringResource(if (live) R.string.live_camtest_stop else R.string.live_camtest_live)) }
        }
        val r = reading
        val p = profile
        if (r != null && p != null) {
            val xyz = p.xyz(Vec3(r.rgb[0], r.rgb[1], r.rgb[2]))
            val xy = ColourMath.xyzToXY(xyz)
            val la = LiveAnalysis.of(xyz, hdr = false)
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(12.dp)) {
                Mono("Brightness  ${fixed(xyz.y, 1)} cd/m² (approximate)")
                Mono("xy          ${fixed(xy.x, 4)}, ${fixed(xy.y, 4)}")
                Mono("Colour temp ${fixed(cct(xy.x, xy.y), 0)} K")
                Mono("Shutter     ${fixed(r.duration * 1000, 2)} ms, peak ${fixed(r.peak * 100, 0)}%, uneven ${fixed(r.uneven * 100, 1)}%")
                la.bal?.let { b -> Mono("R/G ${fixed((b.x / b.y - 1) * 100, 1)}%  B/G ${fixed((b.z / b.y - 1) * 100, 1)}%") }
                la.advice?.let { Mono(it) }
                la.name?.let { Mono("Looks like $it: hue ${fixed(la.hue ?: 0.0, 1)}°, saturation ${fixed(la.sat ?: 0.0, 1)}%") }
            }
        }
        Text(
            stringResource(R.string.live_camtest_approx),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.live_camtest_log), style = MaterialTheme.typography.titleSmall)
        for (line in logLines.takeLast(12).reversed()) Mono(line, 10)
    }
}

@Composable
private fun Mono(s: String, size: Int = 13) = Text(s, fontFamily = FontFamily.Monospace, fontSize = size.sp)
