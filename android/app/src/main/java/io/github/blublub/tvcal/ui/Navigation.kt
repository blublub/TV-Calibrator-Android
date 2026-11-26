// The Android counterpart of the iPhone app's NavigationStack, sheets and environment objects.
package io.github.blublub.tvcal.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.blublub.tvcal.AppModel
import java.util.UUID

/** Where the app can go (the iPhone app's `Route`). */
sealed class Route {
    object Home : Route()
    data class TV(val id: UUID) : Route()
    object Camera : Route()
    object Live : Route()
    object Settings : Route()
}

/** The navigation stack. `push` opens a screen, `pop` goes back (also the system back gesture). */
class Nav {
    val stack = mutableStateListOf<Route>(Route.Home)
    val current: Route get() = stack.last()
    fun push(r: Route) { stack.add(r) }
    fun pop(): Boolean = if (stack.size > 1) { stack.removeAt(stack.size - 1); true } else false
    fun popToHome() { while (stack.size > 1) stack.removeAt(stack.size - 1) }
}

/** The shared app model (the iPhone app's @EnvironmentObject AppModel). */
val LocalApp = staticCompositionLocalOf<AppModel> { error("No AppModel provided") }

/** The navigation stack, for screens that open other screens. */
val LocalNav = compositionLocalOf<Nav> { error("No Nav provided") }

/** Asks for the camera permission; true once granted (provided by MainActivity). */
val LocalCameraPermission = compositionLocalOf<CameraPermission> { error("No CameraPermission provided") }

class CameraPermission(val granted: Boolean, val request: () -> Unit)

/**
 * A full-screen sheet (the iPhone app's `.sheet`): covers the screen, dismissed by the system back
 * gesture or by the content calling `onDismiss`.
 */
@Composable
fun FullSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize()) { content() }
    }
}

/** Opens a web link (YouTube, help pages) in the browser or the YouTube app. */
fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}

/** Shares a file (the iPhone app's ShareSheet), e.g. a TV's export. */
fun shareFile(context: Context, file: java.io.File, mime: String = "application/json") {
    val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Shares plain text (a calibration summary). */
fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
