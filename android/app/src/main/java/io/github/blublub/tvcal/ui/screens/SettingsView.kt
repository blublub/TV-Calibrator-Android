// Port of TV Calibrator/Views/SettingsView.swift (TV Calibrator by Slidebean, https://github.com/slidebean/tv-calibrator, CC BY-NC 4.0). Changed: translated to Kotlin/Compose.
// No profile server on Android: the profile card shows the approximate profile's summary instead of the
// profile-update controls. Notifications are not ported yet; the Lab entry is left out.
package io.github.blublub.tvcal.ui.screens

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import io.github.blublub.tvcal.R
import io.github.blublub.tvcal.engine.SignalMode
import io.github.blublub.tvcal.profiles.ProfileService
import io.github.blublub.tvcal.ui.LocalApp
import io.github.blublub.tvcal.ui.LocalNav
import io.github.blublub.tvcal.ui.openUrl
import io.github.blublub.tvcal.ui.theme.Card
import io.github.blublub.tvcal.ui.theme.Eyebrow
import io.github.blublub.tvcal.ui.theme.Theme
import io.github.blublub.tvcal.ui.theme.Type
import io.github.blublub.tvcal.ui.theme.fmt
import io.github.blublub.tvcal.ui.theme.screenBackground
import io.github.blublub.tvcal.ui.theme.symbol

/** The Swift Chime.enabledKey. Nothing reads it yet on Android: AppModel.chime() always plays. */
private const val SOUNDS_KEY = "feedback.sounds"

private fun appVersion(context: Context): String = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    "${info.versionName ?: "?"} (${PackageInfoCompat.getLongVersionCode(info)})"
}.getOrDefault("? (?)")

/** Settings (Figma "14 Settings"): the camera profile, the pattern videos and about, each on a card. */
@Composable
fun SettingsView(profiles: ProfileService) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val links = app.links.links
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("app", Context.MODE_PRIVATE) }
    var sounds by remember { mutableStateOf(prefs.getBoolean(SOUNDS_KEY, true)) }
    val version = remember { appVersion(context) }
    val appName = stringResource(R.string.app_name)

    Column(
        Modifier
            .fillMaxSize()
            .screenBackground()
            .statusBarsPadding(),
    ) {
        SettingsTitleBar(title = stringResource(R.string.home_settings), onBack = { nav.pop() })
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // MARK: Camera profile
            Card(spacing = 12.dp) {
                Eyebrow(text = stringResource(R.string.home_settings_camera_profile))
                val p = profiles.profile
                SettingsRow(
                    title = stringResource(R.string.home_settings_this_phone),
                    detail = if (p == null) profiles.phoneModel else "${profiles.phoneModel} · ${profiles.accuracy}",
                    symbol = "camera",
                )
                if (p != null) {
                    if (profiles.isStandard) {
                        Text(profiles.summary, style = Type.captionBrand, color = Theme.limit)
                    }
                    if (p.stats.tvs.isNotEmpty()) {
                        val n = p.stats.tvs.size
                        SettingsRow(title = stringResource(R.string.home_settings_tvs_matched),
                            detail = pluralStringResource(R.plurals.home_settings_matched_on, n, n.toString()),
                            symbol = "checkmark")
                    }
                    val u = p.stats.unseen
                    val a = p.stats.deAvg
                    if (u != null) {
                        SettingsRow(title = stringResource(R.string.home_settings_accuracy, p.accuracyWord),
                            detail = stringResource(R.string.home_settings_de_new, fmt(u, 2)),
                            symbol = "info.circle")
                    } else if (a != null) {
                        SettingsRow(title = stringResource(R.string.home_settings_accuracy, p.accuracyWord),
                            detail = stringResource(R.string.home_settings_de_training, fmt(a, 2)),
                            symbol = "info.circle")
                    }
                    p.camera.aperture?.let { ap ->
                        SettingsRow(title = stringResource(R.string.home_settings_aperture), detail = "f/${fmt(ap, 1)}", symbol = "camera.aperture")
                    }
                } else {
                    Text(profiles.summary, style = Type.captionBrand, color = Theme.textSecondary)
                }
            }

            // MARK: Pattern videos
            Card(spacing = 12.dp) {
                Eyebrow(text = stringResource(R.string.home_settings_pattern_videos))
                for (m in listOf(SignalMode.SDR, SignalMode.HDR10)) {
                    links.playlist(m)?.let { url ->
                        Box(Modifier.clickable { openUrl(context, url) }) {
                            SettingsRow(title = stringResource(R.string.home_settings_playlist, m.label), detail = "YouTube", symbol = "arrow.up.right.square")
                        }
                    }
                }
                for (m in listOf(SignalMode.HDR10P, SignalMode.DV)) {
                    links.download(m)?.let { url ->
                        Box(Modifier.clickable { openUrl(context, url) }) {
                            SettingsRow(title = stringResource(R.string.home_settings_patterns, m.label),
                                detail = stringResource(R.string.home_settings_usb), symbol = "arrow.down.circle")
                        }
                    }
                }
                links.explainerURL?.let { url ->
                    Box(Modifier.clickable { openUrl(context, url) }) {
                        SettingsRow(title = stringResource(R.string.home_settings_how_calibration),
                            detail = stringResource(R.string.home_settings_slidebean_video), symbol = "play.rectangle")
                    }
                }
                links.helpURL?.let { help ->
                    Box(Modifier.clickable { openUrl(context, help) }) {
                        SettingsRow(title = stringResource(R.string.home_settings_help), symbol = "questionmark.circle")
                    }
                }
                Text(
                    stringResource(R.string.home_settings_pattern_note),
                    style = Type.captionBrand, color = Theme.textSecondary,
                )
            }

            // MARK: Notifications and sounds
            Card(spacing = 12.dp) {
                Eyebrow(text = stringResource(R.string.home_settings_notifications))
                // TODO: re-check reminders and new-video notifications (Swift Notifications.shared) once
                // notifications are ported: the permission states, the two toggles and "Open Settings".
                SettingsToggle(
                    checked = sounds,
                    onCheckedChange = {
                        sounds = it
                        prefs.edit().putBoolean(SOUNDS_KEY, it).apply()
                    },
                ) {
                    SettingsRow(title = stringResource(R.string.home_settings_sounds),
                        detail = stringResource(R.string.home_settings_sounds_detail))
                }
            }

            // MARK: About
            Card(spacing = 12.dp) {
                SettingsRow(title = appName, detail = stringResource(R.string.home_settings_version, version), symbol = "info.circle")
                Box(Modifier.clickable { openUrl(context, "https://github.com/slidebean/tv-calibrator") }) {
                    SettingsRow(title = stringResource(R.string.home_settings_based_on),
                        detail = stringResource(R.string.home_settings_license), symbol = "arrow.up.right.square")
                }
                Text(stringResource(R.string.home_settings_privacy), style = Type.captionBrand, color = Theme.textSecondary)
            }
        }
    }
}

/** A pushed screen's bar: back on the left, the title centred (the Swift inline navigation title). */
@Composable
private fun SettingsTitleBar(title: String, onBack: () -> Unit) {
    val backLabel = stringResource(R.string.home_back)
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
                .clickable(onClick = onBack)
                .semantics { contentDescription = backLabel },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Theme.tealBright, modifier = Modifier.size(24.dp))
        }
        Text(
            title, style = Type.headlineBrand, color = Color.White, maxLines = 1,
            modifier = Modifier
                .align(Alignment.Center)
                .semantics { heading() },
        )
    }
}

/** A row with a switch on the right (the Swift Toggle with a SettingsRow label). */
@Composable
private fun SettingsToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) { label() }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Theme.green,
                uncheckedTrackColor = Theme.card,
                uncheckedBorderColor = Theme.hairline,
            ),
        )
    }
}

/** A settings row: a label over a secondary line, with a teal icon on the right (Figma "Settings row"). */
@Composable
private fun SettingsRow(title: String, detail: String? = null, symbol: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = Type.bodyBrand, color = Color.White)
            if (detail != null) Text(detail, style = Type.captionBrand, color = Theme.textSecondary)
        }
        if (symbol != null) {
            Icon(io.github.blublub.tvcal.ui.theme.symbol(symbol), contentDescription = null, tint = Theme.tealBright, modifier = Modifier.size(22.dp))
        }
    }
}
