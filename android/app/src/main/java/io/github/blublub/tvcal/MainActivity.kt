package io.github.blublub.tvcal

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.blublub.tvcal.camera.CameraMeter
import io.github.blublub.tvcal.ui.CameraTestScreen

class MainActivity : ComponentActivity() {
    private val meter by lazy { CameraMeter(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            var granted by remember {
                mutableStateOf(checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            }
            val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
            MaterialTheme {
                Surface {
                    CameraTestScreen(meter, granted) { ask.launch(Manifest.permission.CAMERA) }
                }
            }
        }
    }
}
