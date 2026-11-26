// Android counterpart of "TV Calibrator/Profiles/ProfileService.swift" and "PatternLinks.swift"
// (TV Calibrator by Slidebean, https://github.com/slidebean/tv-calibrator, CC BY-NC 4.0).
// Changed: no profile server; on Android the profile comes from the camera's own colour data.
package io.github.blublub.tvcal.profiles

import io.github.blublub.tvcal.ui.L10n

import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.blublub.tvcal.camera.CameraEngine
import io.github.blublub.tvcal.camera.MetadataProfile
import io.github.blublub.tvcal.engine.CameraProfile
import io.github.blublub.tvcal.engine.SignalMode
import io.github.blublub.tvcal.engine.engineJson
import kotlinx.serialization.Serializable

/**
 * The camera profile in use. Phones have no profile trained against a colorimeter yet, so this is the
 * approximate profile worked out from the camera's own colour matrices (see MetadataProfile).
 */
class ProfileService {
    enum class Status { CHECKING, READY, UNAVAILABLE }

    var status by mutableStateOf(Status.CHECKING); private set
    var profile by mutableStateOf<CameraProfile?>(null); private set
    var summary by mutableStateOf(L10n.tr("Finding the camera's colour data.", "Farbdaten der Kamera werden gelesen.")); private set

    /** True while the profile is the approximate one (always, until trained profiles exist for phones). */
    val isStandard: Boolean get() = true

    /** Phone model name for profiles and exports, e.g. "OPPO CPH2651". */
    val phoneModel: String get() = "${Build.MANUFACTURER} ${Build.MODEL}"

    /** Reads the camera's colour data (works before the camera is opened on most phones). */
    fun refresh(engine: CameraEngine) {
        val info = engine.inspect()
        if (info == null) {
            status = Status.UNAVAILABLE
            summary = engine.inspectProblem ?: L10n.tr("This phone's camera doesn't report the colour data the meter needs.", "Die Kamera dieses Handys liefert nicht die Farbdaten, die das Messgerät braucht.")
            profile = null
            return
        }
        val p = MetadataProfile.profile(info, phoneModel, phoneModel, engine.settings)
        if (p == null) {
            status = Status.UNAVAILABLE
            summary = L10n.tr("This phone's camera doesn't report the colour data the meter needs.", "Die Kamera dieses Handys liefert nicht die Farbdaten, die das Messgerät braucht.")
        } else {
            status = Status.READY
            summary = L10n.tr(
                "Approximate profile from this phone's own colour data. Readings show the direction and " +
                    "rough size of errors; they aren't checked against a colorimeter.",
                "Näherungsprofil aus den eigenen Farbdaten dieses Handys. Die Messwerte zeigen Richtung und " +
                    "ungefähre Größe von Abweichungen; sie sind nicht mit einem Colorimeter abgeglichen.",
            )
        }
        profile = p
    }

    /** The accuracy word to show for the profile in use. */
    val accuracy: String get() = if (profile == null) L10n.tr("Not available", "Nicht verfügbar") else L10n.tr("Approximate", "Näherung")
}

/**
 * Where users get the pattern videos. SDR and HDR10: the TV Calibrator playlists on YouTube, played in the
 * TV's own YouTube app. HDR10+ and Dolby Vision: downloads, played from a USB stick or a media app.
 */
@Serializable
data class PatternLinks(
    val format: Int = 1,
    val channel: String? = null,
    val help: String? = null,
    val newsletter: String? = null,
    val explainer: String? = null,
    val instagram: String? = null,
    val playlists: Map<String, String> = emptyMap(),
    val downloads: Map<String, String> = emptyMap(),
    val videos: Map<String, String> = emptyMap(),
) {
    private fun url(s: String?): String? = s?.takeIf { it.startsWith("https://") }

    fun playlist(mode: SignalMode): String? = url(playlists[mode.raw])
    fun download(mode: SignalMode): String? = url(downloads[mode.raw])
    fun video(file: String): String? = url(videos[file])
    val helpURL: String? get() = url(help) ?: url(channel)
    val explainerURL: String? get() = url(explainer)
    val channelURL: String? get() = url(channel)
}

/** SDR and HDR10 patterns are on YouTube; HDR10+ and Dolby Vision are downloads. */
val SignalMode.onYouTube: Boolean get() = this == SignalMode.SDR || this == SignalMode.HDR10

/** Where a pattern file comes from, in words. */
val SignalMode.patternSource: String
    get() = if (onYouTube) "in the $label playlist on YouTube" else "from the $label download (USB stick or a media app)"

/** The pattern links shipped with the app (patterns.json from the original repo). */
class LinksService(context: Context) {
    val links: PatternLinks = runCatching {
        context.assets.open("patterns.json").use { engineJson.decodeFromString(PatternLinks.serializer(), it.reader().readText()) }
    }.getOrElse { PatternLinks() }
}
