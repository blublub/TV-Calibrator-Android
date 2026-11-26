// Port of TV Calibrator/Views/DesignSystem.swift (TV Calibrator by Slidebean, https://github.com/slidebean/tv-calibrator, CC BY-NC 4.0). Changed: translated to Kotlin/Compose.
package io.github.blublub.tvcal.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Camera
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.ToggleOn
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.blublub.tvcal.R
import io.github.blublub.tvcal.engine.SignalMode
import io.github.blublub.tvcal.engine.fixed
import io.github.blublub.tvcal.engine.signedFixed
import io.github.blublub.tvcal.storage.colourSwatch
import io.github.blublub.tvcal.ui.L10n

// The design system, from the Figma file "Calibrator" (pages 02 Components, 03 Colour and type styles).
// Every screen is built from these tokens and parts. Type is the system font (SF Pro on iOS, the default
// sans-serif here).

// MARK: - Colour

private fun hex(rgb: Long) = Color(0xFF000000 or rgb)

object Theme {
    /** Screens: near-black with a teal cast. */
    val background = hex(0x05171A)
    /** Hero cards and illustration panels. */
    val surface = hex(0x0D3B42)
    val teal = surface
    /** Links, back arrows, icons, eyebrows, the SDR format. */
    val tealBright = hex(0x40B8B0)
    /** The one main action per screen, the current step, the HDR10 format. */
    val accent = hex(0xFFD133)
    val accentPressed = hex(0xDAB025)
    /** On target, done. */
    val green = hex(0x4DCC73)
    /** At the TV's limit, warnings. */
    val limit = hex(0xFFB36B)
    val red = hex(0xF2544D)
    val blue = hex(0x548CFA)
    val hdr10Plus = hex(0x9E85FA)
    val dolbyVision = hex(0xED73BF)
    /** Card fill: white 6%. */
    val card = Color.White.copy(alpha = 0.06f)
    /** Hairline borders and empty tracks: white 9%. */
    val hairline = Color.White.copy(alpha = 0.09f)
    val textSecondary = Color.White.copy(alpha = 0.6f)
    /** The camera preview's empty fill. */
    val previewFill = hex(0x30383A)
    /** Top-leading to bottom-trailing. */
    val tealGradient: Brush = Brush.linearGradient(listOf(Color(0.07f, 0.30f, 0.33f), surface))

    fun channel(c: String): Color = if (c == "R") red else if (c == "G") green else blue

    fun swatch(name: String): Color {
        val s = colourSwatch[name] ?: Triple(0.8, 0.8, 0.8)
        return Color(s.first.toFloat(), s.second.toFloat(), s.third.toFloat())
    }

    fun deColour(de: Double?): Color {
        if (de == null || de.isNaN()) return textSecondary
        return if (de < 1) green else if (de < 3) Color.White else limit
    }
}

/** Each format has its own colour, so it's always clear which videos belong to which. */
val SignalMode.tint: Color
    get() = when (this) {
        SignalMode.SDR -> Theme.tealBright
        SignalMode.HDR10 -> Theme.accent
        SignalMode.HDR10P -> Theme.hdr10Plus
        SignalMode.DV -> Theme.dolbyVision
    }

/** SDR and HDR10 come first (YouTube); HDR10+ and Dolby Vision are advanced (files played on the TV). */
val SignalMode.isAdvanced: Boolean get() = !(this == SignalMode.SDR || this == SignalMode.HDR10)

/** Recommended order, from 1. */
val SignalMode.order: Int get() = SignalMode.entries.indexOf(this) + 1

val SignalMode.blurb: String
    get() = when (this) {
        SignalMode.SDR -> L10n.tr("Everyday TV. Start here.", "Normales Fernsehen. Fang hier an.")
        SignalMode.HDR10 -> L10n.tr("HDR films. After SDR.", "HDR-Filme. Nach SDR.")
        SignalMode.HDR10P, SignalMode.DV ->
            L10n.tr("Pattern files on a USB stick. After HDR10.", "Testbild-Dateien auf einem USB-Stick. Nach HDR10.")
    }

// MARK: - Type

/** The type scale from the Figma file (03 Colour and type styles), in sp so it follows the user's text size. */
object Type {
    /** The app's typeface (the system font) at a design size. */
    fun brand(size: Float, weight: FontWeight = FontWeight.Normal): TextStyle =
        TextStyle(fontFamily = FontFamily.Default, fontSize = size.sp, fontWeight = weight)

    /** 28 regular: screen headlines ("See it the way it was made."). */
    val display = brand(28f)
    /** 25 regular: walkthrough page titles. */
    val pageTitle = brand(25f)
    /** 22 semibold: a step's title ("Measure your starting point"). */
    val stepTitle = brand(22f, FontWeight.SemiBold)
    /** 23 regular: card headings ("1 SDR"). */
    val cardTitle = brand(23f)
    /** 18 semibold: section headings ("Your screens"). */
    val section = brand(18f, FontWeight.SemiBold)
    /** 17 semibold: emphasised titles, video names. */
    val headlineBrand = brand(17f, FontWeight.SemiBold)
    /** 15: body text; semibold for names and links. */
    val bodyBrand = brand(15f)
    val bodyStrong = brand(15f, FontWeight.SemiBold)
    /** 14: format card descriptions. */
    val callout14 = brand(14f)
    /** 13: secondary text; semibold for eyebrows and small links. */
    val captionBrand = brand(13f)
    val captionStrong = brand(13f, FontWeight.SemiBold)
    /** 12: small labels; bold for format badges. */
    val small = brand(12f)
    val badge = brand(12f, FontWeight.Bold)
    /** 11 and 10: step numbers, format progress labels. */
    val tiny = brand(11f)
    val micro = brand(10f)

    /** Live readings, readable from across the room (numeric scale: 56 / 40 / 30), with tabular digits. */
    fun reading(size: Float = 40f): TextStyle = brand(size, FontWeight.SemiBold).copy(fontFeatureSettings = "tnum")

    /** A 28 pt display headline with the design's tight tracking, in white. */
    val displayStyle = display.copy(letterSpacing = (-0.6).sp, lineHeight = 32.sp, color = Color.White)
    /** A 25 pt page title, in white. */
    val pageTitleStyle = pageTitle.copy(letterSpacing = (-0.6).sp, color = Color.White)
    /** A 22 pt semibold step title, in white. */
    val stepTitleStyle = stepTitle.copy(letterSpacing = (-0.4).sp, color = Color.White)
}

/** A number with `digits` decimals ("%.1f"), or "–" when there's none. */
fun fmt(v: Double?, digits: Int = 1): String {
    if (v == null || !v.isFinite()) return "–"
    return io.github.blublub.tvcal.engine.EngineTexts.num(v, digits)
}

/** A number with its sign ("%+.1f"), or "–" when there's none. */
fun signed(v: Double?, digits: Int = 1): String {
    if (v == null || !v.isFinite()) return "–"
    return io.github.blublub.tvcal.engine.EngineTexts.signedNum(v, digits)
}

/**
 * The little Markdown the original's tips use (SwiftUI `Text(.init(...))`): **bold**.
 * Anything else is left as written.
 */
fun markdown(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        if (text.startsWith("**", i)) {
            val end = text.indexOf("**", i + 2)
            if (end > i + 2) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                i = end + 2
                continue
            }
        }
        append(text[i])
        i++
    }
}

// MARK: - Theme

private val darkScheme = darkColorScheme(
    primary = Theme.accent,
    onPrimary = Theme.background,
    secondary = Theme.tealBright,
    onSecondary = Theme.background,
    tertiary = Theme.green,
    background = Theme.background,
    onBackground = Color.White,
    surface = Theme.background,
    onSurface = Color.White,
    surfaceVariant = Theme.surface,
    onSurfaceVariant = Theme.textSecondary,
    surfaceContainer = Theme.surface,
    surfaceContainerHigh = Theme.surface,
    outline = Theme.hairline,
    outlineVariant = Theme.hairline,
    error = Theme.red,
    onError = Color.White,
)

private val typography = Typography(
    displaySmall = Type.display,
    headlineSmall = Type.pageTitle,
    titleLarge = Type.stepTitle,
    titleMedium = Type.headlineBrand,
    titleSmall = Type.bodyStrong,
    bodyLarge = Type.bodyBrand,
    bodyMedium = Type.captionBrand,
    bodySmall = Type.small,
    labelLarge = Type.bodyBrand,
    labelMedium = Type.small,
    labelSmall = Type.tiny,
)

/** The app's theme: dark only, like the original. White text on the near-black teal background. */
@Composable
fun TVCalibratorTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkScheme, typography = typography) {
        CompositionLocalProvider(
            LocalContentColor provides Color.White,
            LocalTextStyle provides Type.bodyBrand,
            content = content,
        )
    }
}

// MARK: - Screens

/** Screen background: near-black with the brand teal cast. */
fun Modifier.screenBackground(): Modifier = background(Theme.background)

/**
 * A screen whose one main action is pinned above the navigation bar while the content scrolls.
 * `content` gets the padding to keep clear of the bar (apply it to the scrolling container).
 */
@Composable
fun ScreenWithBottomAction(
    modifier: Modifier = Modifier,
    bottom: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        containerColor = Theme.background,
        contentColor = Color.White,
        bottomBar = {
            if (bottom != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Theme.background.copy(alpha = 0.96f))
                        .navigationBarsPadding()
                        .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
                ) { bottom() }
            }
        },
        content = content,
    )
}

/** The same, with the usual pinned yellow button; a null `actionTitle` shows none (while measuring, say). */
@Composable
fun ScreenWithBottomAction(
    actionTitle: String?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    actionEnabled: Boolean = true,
    content: @Composable (PaddingValues) -> Unit,
) {
    ScreenWithBottomAction(
        modifier = modifier,
        bottom = actionTitle?.let { t -> { PrimaryButton(t, onAction, enabled = actionEnabled) } },
        content = content,
    )
}

// MARK: - Buttons

private val capsule = RoundedCornerShape(percent = 50)

/** The main action: a yellow capsule, dark label, at least 52 dp tall. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = PrimaryButton(onClick, modifier, enabled) { Text(text, textAlign = TextAlign.Center) }

@Composable
fun PrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val fill = if (!enabled) Theme.accent.copy(alpha = 0.2f) else if (pressed) Theme.accentPressed else Theme.accent
    CapsuleButton(onClick, modifier, enabled, source, fill, null,
        if (enabled) Theme.background else Theme.textSecondary, content)
}

/** A secondary action: a teal-outlined capsule. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = SecondaryButton(onClick, modifier, enabled) { Text(text, textAlign = TextAlign.Center) }

@Composable
fun SecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    CapsuleButton(onClick, modifier, enabled, source, if (pressed) Theme.card else Color.Transparent,
        BorderStroke(1.dp, if (enabled) Theme.tealBright else Theme.hairline),
        if (enabled) Color.White else Theme.textSecondary, content)
}

@Composable
private fun CapsuleButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    source: MutableInteractionSource,
    fill: Color,
    border: BorderStroke?,
    textColour: Color,
    content: @Composable RowScope.() -> Unit,
) {
    var m = modifier
        .fillMaxWidth()
        .heightIn(min = 52.dp)
        .clip(capsule)
        .background(fill, capsule)
    if (border != null) m = m.border(border, capsule)
    Row(
        m.clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides textColour,
            LocalTextStyle provides Type.bodyBrand.copy(color = textColour, textAlign = TextAlign.Center),
        ) { content() }
    }
}

/** A text link: teal, semibold. */
@Composable
fun LinkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = Type.bodyStrong,
    enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        modifier
            .heightIn(min = 44.dp)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = style, color = Theme.tealBright.copy(alpha = if (pressed) 0.6f else 1f))
    }
}

// MARK: - Surfaces

private val cardShape = RoundedCornerShape(18.dp)

/** A card: white 6%, hairline border, 18 dp corners, 16 dp padding. */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    spacing: Dp = 12.dp,
    hero: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(if (hero) Theme.surface else Theme.card, cardShape)
            .border(1.dp, Theme.hairline, cardShape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(spacing),
        horizontalAlignment = Alignment.Start,
        content = content,
    )
}

/** A symbol in a round bubble (36, 44 or 64 dp). `symbol` is an SF Symbol name, see [symbol]. */
@Composable
fun IconBubble(symbol: String, modifier: Modifier = Modifier, tint: Color = Theme.tealBright, size: Dp = 44.dp) =
    IconBubble(symbol(symbol), modifier, tint, size)

@Composable
fun IconBubble(icon: ImageVector, modifier: Modifier = Modifier, tint: Color = Theme.tealBright, size: Dp = 44.dp) {
    Box(
        modifier.size(size).background(tint.copy(alpha = 0.1f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** "SDR" / "HDR10" ... in the format's colour on a tint of it. */
@Composable
fun ModeBadge(mode: SignalMode, modifier: Modifier = Modifier, big: Boolean = false) {
    Text(
        mode.label,
        style = Type.badge,
        color = mode.tint,
        modifier = modifier
            .background(mode.tint.copy(alpha = 0.13f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** Another name for [ModeBadge]. */
@Composable
fun FormatBadge(mode: SignalMode, modifier: Modifier = Modifier, big: Boolean = false) = ModeBadge(mode, modifier, big)

/** A small eyebrow line above a title: "Set up · 5 of 6", "Before → after". */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, colour: Color = Theme.tealBright) {
    Text(text, style = Type.small, color = colour, modifier = modifier)
}

/** One tip: to do (teal, lightbulb), on target (green, check) or at the TV's limit (orange, minus). */
enum class TipTone { todo, ok, limit }

/** `text` may use **bold**, as in the original. */
@Composable
fun TipRow(tone: TipTone, text: String, modifier: Modifier = Modifier) {
    val colour = when (tone) {
        TipTone.ok -> Theme.green
        TipTone.limit -> Theme.limit
        TipTone.todo -> Theme.tealBright
    }
    val icon = symbol(
        when (tone) {
            TipTone.ok -> "checkmark.circle"
            TipTone.limit -> "minus.circle"
            TipTone.todo -> "lightbulb"
        },
    )
    Row(
        modifier
            .fillMaxWidth()
            .background(colour.copy(alpha = 0.07f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = colour, modifier = Modifier.size(20.dp))
        Text(
            markdown(text),
            style = Type.captionBrand,
            color = if (tone == TipTone.todo) Color.White else colour,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Page dots: 6 dp, the current one 8 dp and yellow. */
@Composable
fun PageDots(count: Int, index: Int, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.report_page_of, (index + 1).toString(), count.toString())
    Row(
        modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until count) {
            val current = i == index
            val d by animateDpAsState(if (current) 8.dp else 6.dp, tween(200), label = "dot")
            val c by animateColorAsState(if (current) Theme.accent else Color.White.copy(alpha = 0.25f), tween(200),
                label = "dotColour")
            Box(Modifier.size(d).background(c, CircleShape))
        }
    }
}

/** A thin progress track (4 dp) in a colour. `value` is 0 ... 1. */
@Composable
fun ProgressTrack(value: Double, modifier: Modifier = Modifier, colour: Color = Theme.tealBright) {
    BoxWithConstraints(modifier.fillMaxWidth().height(4.dp).background(Theme.hairline, capsule)) {
        val f = value.coerceIn(0.0, 1.0).toFloat().takeIf { !it.isNaN() } ?: 0f
        Box(Modifier.width(maxWidth * f).height(4.dp).background(colour, capsule))
    }
}

// MARK: - Symbols

/**
 * The Material icon closest to an SF Symbol name used by the original app ("tv", "checkmark.circle" ...).
 * Unknown names get a plain circle.
 */
fun symbol(name: String): ImageVector = when (name) {
    "arrow.down" -> Icons.Filled.ArrowDownward
    "arrow.down.circle" -> Icons.Outlined.ArrowCircleDown
    "arrow.right" -> Icons.AutoMirrored.Filled.ArrowForward
    "arrow.triangle.2.circlepath" -> Icons.Outlined.Sync
    "arrow.up.right" -> Icons.Outlined.NorthEast
    "arrow.up.right.square" -> Icons.AutoMirrored.Outlined.OpenInNew
    "bell" -> Icons.Outlined.Notifications
    "bell.slash" -> Icons.Outlined.NotificationsOff
    "bubble.left" -> Icons.Outlined.ChatBubbleOutline
    "camera" -> Icons.Outlined.PhotoCamera
    "camera.aperture" -> Icons.Outlined.Camera
    "camera.viewfinder" -> Icons.Outlined.CenterFocusStrong
    "chart.xyaxis.line" -> Icons.AutoMirrored.Outlined.ShowChart
    "checkmark" -> Icons.Filled.Check
    "checkmark.circle" -> Icons.Outlined.CheckCircle
    "checkmark.circle.fill" -> Icons.Filled.CheckCircle
    "checkmark.square" -> Icons.Outlined.CheckBox
    "chevron.down" -> Icons.Filled.KeyboardArrowDown
    "chevron.up" -> Icons.Filled.KeyboardArrowUp
    "chevron.right" -> Icons.AutoMirrored.Filled.KeyboardArrowRight
    "circle" -> Icons.Outlined.Circle
    "clock" -> Icons.Outlined.Schedule
    "ellipsis.circle" -> Icons.Outlined.MoreHoriz
    "envelope.fill" -> Icons.Filled.Email
    "exclamationmark.triangle" -> Icons.Outlined.WarningAmber
    "externaldrive" -> Icons.Outlined.Usb
    "flask" -> Icons.Outlined.Science
    "info.circle" -> Icons.Outlined.Info
    "iphone" -> Icons.Outlined.Smartphone
    "largecircle.fill.circle" -> Icons.Outlined.RadioButtonChecked
    "lightbulb", "lightbulb.slash" -> Icons.Outlined.Lightbulb
    "list.number" -> Icons.Outlined.FormatListNumbered
    "minus" -> Icons.Filled.Remove
    "minus.circle" -> Icons.Outlined.RemoveCircleOutline
    "moon" -> Icons.Outlined.DarkMode
    "pencil" -> Icons.Outlined.Edit
    "play.circle" -> Icons.Outlined.PlayCircle
    "play.fill" -> Icons.Filled.PlayArrow
    "play.rectangle" -> Icons.Outlined.SmartDisplay
    "play.rectangle.fill" -> Icons.Filled.SmartDisplay
    "questionmark.circle" -> Icons.AutoMirrored.Outlined.HelpOutline
    "rectangle.stack" -> Icons.Outlined.Layers
    "slider.horizontal.3" -> Icons.Outlined.Tune
    "slider.vertical.3" -> Icons.Outlined.Equalizer
    "sparkles" -> Icons.Outlined.AutoAwesome
    "sparkles.tv", "tv" -> Icons.Outlined.Tv
    "square.and.arrow.down" -> Icons.Outlined.Download
    "square.and.arrow.up" -> Icons.Outlined.Share
    "switch.2" -> Icons.Outlined.ToggleOn
    "timer" -> Icons.Outlined.Timer
    "trash" -> Icons.Outlined.Delete
    "video.slash" -> Icons.Outlined.VideocamOff
    "viewfinder" -> Icons.Outlined.CropFree
    "xmark" -> Icons.Filled.Close
    else -> Icons.Outlined.Circle
}

// MARK: - Previews

@Preview(backgroundColor = 0xFF05171A, showBackground = true)
@Composable
private fun DesignSystemPreview() {
    TVCalibratorTheme {
        Column(Modifier.screenBackground().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Eyebrow("Set up · 5 of 6")
            Text("See it the way it was made.", style = Type.displayStyle)
            Card {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeBadge(SignalMode.SDR); ModeBadge(SignalMode.HDR10)
                    ModeBadge(SignalMode.HDR10P); ModeBadge(SignalMode.DV)
                }
                IconBubble("tv")
                ProgressTrack(0.4)
            }
            TipRow(TipTone.todo, "**Now put them back.** Set those four controls back.")
            TipRow(TipTone.ok, "On target.")
            PageDots(4, 1)
            PrimaryButton("Continue", {})
            SecondaryButton("Not now", {})
            LinkButton("Learn more", {})
        }
    }
}
