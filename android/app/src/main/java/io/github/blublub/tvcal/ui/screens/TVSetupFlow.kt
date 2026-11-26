// Port of TV Calibrator/Views/TVSetupFlow.swift (TV Calibrator by Slidebean, https://github.com/slidebean/tv-calibrator, CC BY-NC 4.0). Changed: translated to Kotlin/Compose.
package io.github.blublub.tvcal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.blublub.tvcal.R
import io.github.blublub.tvcal.storage.Brand
import io.github.blublub.tvcal.storage.ColourMenu
import io.github.blublub.tvcal.storage.Multipoint
import io.github.blublub.tvcal.storage.PanelType
import io.github.blublub.tvcal.storage.TVRecord
import io.github.blublub.tvcal.ui.LocalApp
import io.github.blublub.tvcal.ui.theme.Theme
import io.github.blublub.tvcal.ui.theme.Type
import io.github.blublub.tvcal.ui.theme.symbol

/**
 * Add (or edit) a TV, one page at a time: name and brand, then what its white-balance and colour menus
 * look like. Either menu can be "not sure": the app asks again at the step that needs it.
 * (Figma: 03 Add a Screen · empty / selected / white / colour / edit.)
 */
@Composable
fun TVSetupFlow(
    tv: TVRecord?,
    /** Called with the new TV after adding it. */
    onAdded: (TVRecord) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val app = LocalApp.current
    // load(): from the TV being edited, or the brand's usual menus for a new one.
    var name by remember { mutableStateOf(tv?.name ?: "") }
    var brand by remember { mutableStateOf(tv?.brand ?: Brand.LG) }
    var multipoint by remember { mutableStateOf(tv?.multipoint ?: Brand.LG.multipoint) }
    var multipointKnown by remember { mutableStateOf(tv?.multipointKnown ?: false) }
    var colourMenu by remember { mutableStateOf(tv?.colourMenu ?: Brand.LG.colourMenu) }
    var colourMenuKnown by remember { mutableStateOf(tv?.colourMenuKnown ?: false) }
    var panel by remember { mutableStateOf(tv?.panel ?: PanelType.UNKNOWN) }

    val trimmedName = name.trim()

    fun save() {
        val t = (tv ?: TVRecord(name = "")).copy(
            name = trimmedName,
            brand = brand,
            multipoint = multipoint,
            multipointKnown = multipointKnown,
            colourMenu = colourMenu,
            colourMenuKnown = colourMenuKnown,
            panel = panel,
        )
        if (tv == null) {
            app.add(t)
            onDismiss()
            onAdded(t)
        } else {
            app.update(t)
            onDismiss()
        }
    }

    val pages = listOf(
        WalkPage(
            symbol = "", title = stringResource(R.string.home_setup_which_screen),
            text = stringResource(R.string.home_setup_which_screen_text),
            extra = {
                NameAndBrand(
                    name = name, onNameChange = { name = it },
                    brand = brand,
                    onBrandChange = { b ->
                        brand = b
                        if (!multipointKnown) multipoint = b.multipoint
                        if (!colourMenuKnown) colourMenu = b.colourMenu
                    },
                )
            },
        ),
        WalkPage(
            symbol = "", title = stringResource(R.string.home_setup_kind),
            text = stringResource(R.string.home_setup_kind_text),
            extra = { PanelChoice(selection = panel, onSelectionChange = { panel = it }) },
        ),
        WalkPage(
            symbol = "", title = stringResource(R.string.home_setup_wb_title),
            text = stringResource(R.string.home_setup_wb_text, brand.whiteBalancePath),
            extra = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    WalkArtPanel(height = 130.dp) { WhiteBalanceArt(points = multipoint.artPoints) }
                    MultipointChoice(
                        brand = brand,
                        selection = multipoint, onSelectionChange = { multipoint = it },
                        known = multipointKnown, onKnownChange = { multipointKnown = it },
                    )
                }
            },
        ),
        WalkPage(
            symbol = "", title = stringResource(R.string.home_setup_colour_title),
            text = stringResource(R.string.home_setup_colour_text, brand.colourMenuPath),
            extra = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    WalkArtPanel(height = 130.dp) { ColourMenuArt(menu = colourMenu) }
                    ColourMenuChoice(
                        brand = brand,
                        selection = colourMenu, onSelectionChange = { colourMenu = it },
                        known = colourMenuKnown, onKnownChange = { colourMenuKnown = it },
                    )
                }
            },
        ),
    )

    SetupSheetHost(
        title = stringResource(if (tv == null) R.string.home_add_screen else R.string.home_setup_edit_title),
        closeLabel = stringResource(R.string.home_cancel),
        onClose = onDismiss,
    ) {
        Walkthrough(
            pages = pages,
            finishTitle = stringResource(if (tv == null) R.string.home_setup_add_finish else R.string.home_save),
            section = stringResource(R.string.home_setup_section),
            canAdvance = { i -> i != 0 || trimmedName.isNotEmpty() },
            onFinish = { save() },
        )
    }
}

/**
 * The sheet around a walkthrough (the Swift NavigationStack + ScrollView + pinnedActionHost): a title bar
 * with the close X, then the scrolling content. FullSheet is edge to edge, so this pads for the system bars
 * and the keyboard.
 */
@Composable
private fun SetupSheetHost(
    title: String,
    closeLabel: String,
    onClose: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Theme.background)
            .systemBarsPadding()
            .imePadding(),
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
                    .clickable(onClick = onClose)
                    .semantics { contentDescription = closeLabel },
                contentAlignment = Alignment.Center,
            ) {
                Icon(symbol("xmark"), contentDescription = null, tint = Theme.tealBright, modifier = Modifier.size(22.dp))
            }
            Text(
                title, style = Type.headlineBrand, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.Center)
                    .widthIn(max = 260.dp)
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
                content = content,
            )
        }
        Box(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp)) {
            PinnedActionBar(pinned)
        }
    }
}

@Composable
private fun NameAndBrand(
    name: String,
    onNameChange: (String) -> Unit,
    brand: Brand,
    onBrandChange: (Brand) -> Unit,
) {
    var nameFocused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val fieldShape = RoundedCornerShape(14.dp)
    val nameLabel = stringResource(R.string.home_setup_name_label)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(nameLabel, style = Type.captionBrand, color = Color.White)
            BasicTextField(
                value = name,
                onValueChange = onNameChange,
                singleLine = true,
                textStyle = Type.bodyBrand.copy(fontSize = 16.sp, color = Color.White),
                cursorBrush = SolidColor(Theme.tealBright),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { nameFocused = it.isFocused }
                    .semantics { contentDescription = nameLabel },
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 54.dp)
                            .background(Theme.card, fieldShape)
                            .border(1.dp, if (nameFocused) Theme.tealBright else Theme.hairline, fieldShape)
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (name.isEmpty()) {
                            Text(stringResource(R.string.home_setup_name_hint), style = Type.bodyBrand.copy(fontSize = 16.sp),
                                color = Theme.textSecondary)
                        }
                        inner()
                    }
                },
            )
        }
        Text(stringResource(R.string.home_setup_brand), style = Type.captionBrand, color = Color.White)
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            for (row in Brand.entries.chunked(3)) {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    for (b in row) {
                        BrandLabel(b, selected = b == brand, onClick = { onBrandChange(b) }, modifier = Modifier.weight(1f))
                    }
                    repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
        Text(stringResource(R.string.home_setup_name_tip), style = Type.captionBrand, color = Theme.textSecondary)
    }
}

@Composable
private fun BrandLabel(b: Brand, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(13.dp)
    Box(
        modifier
            .heightIn(min = 49.dp)
            .background(if (selected) Theme.accent.copy(alpha = 0.1f) else Theme.card, shape)
            .border(1.dp, if (selected) Theme.accent else Theme.hairline, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            b.label,
            style = if (selected) Type.callout14.copy(fontWeight = FontWeight.SemiBold) else Type.callout14,
            color = if (selected) Theme.accent else Color.White,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** How many sliders the white-balance illustration shows. */
private val Multipoint.artPoints: Int
    get() = when (this) {
        Multipoint.NONE -> 2
        Multipoint.TEN -> 10
        Multipoint.TWENTY -> 20
    }

/**
 * A big tappable option: an icon, a title (with optional detail and note) and a radio.
 * Selected: yellow 1.5 dp border on a yellow tint, the icon and radio in yellow.
 */
@Composable
fun ChoiceTile(
    title: String,
    /** A secondary line under the title ("Gain and Offset"). */
    detail: String? = null,
    /** A teal line under it ("Usual for LG"). */
    note: String? = null,
    selected: Boolean,
    /** The icon, drawn in teal, yellow when selected (it gets the colour as LocalContentColor). */
    art: @Composable () -> Unit,
    action: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(if (selected) Theme.accent.copy(alpha = 0.05f) else Theme.card, shape)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Theme.accent else Theme.hairline, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = action)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalContentColor provides if (selected) Theme.accent else Theme.tealBright) { art() }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = Type.bodyBrand, color = Color.White)
            if (detail != null) Text(detail, style = Type.small, color = Theme.textSecondary)
            if (note != null) Text(note, style = Type.small, color = Theme.tealBright)
        }
        if (selected) {
            Box(Modifier.size(20.dp).background(Theme.accent, CircleShape))
        } else {
            Box(Modifier.size(20.dp).border(1.5.dp, Color.White.copy(alpha = 0.35f), CircleShape))
        }
    }
}

/** The kind of screen: OLED, QD-OLED, quantum dot or mini-LED, LED, RGB backlight, plasma, or not sure. */
@Composable
fun PanelChoice(selection: PanelType, onSelectionChange: (PanelType) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (p in PanelType.entries) {
            ChoiceTile(
                title = p.label, detail = p.detail, selected = selection == p,
                art = { Icon(symbol(if (p == PanelType.UNKNOWN) "questionmark.circle" else "tv"), contentDescription = null) },
                action = { onSelectionChange(p) },
            )
        }
    }
}

/** 2-point only, 10 points or 20 points, or not sure. */
@Composable
fun MultipointChoice(
    brand: Brand,
    selection: Multipoint,
    onSelectionChange: (Multipoint) -> Unit,
    known: Boolean,
    onKnownChange: (Boolean) -> Unit,
) {
    @Composable
    fun title(m: Multipoint): String = when (m) {
        Multipoint.NONE -> stringResource(R.string.home_mp_two)
        Multipoint.TEN -> stringResource(R.string.home_mp_ten)
        Multipoint.TWENTY -> stringResource(R.string.home_mp_twenty)
    }

    @Composable
    fun detail(m: Multipoint): String = when (m) {
        Multipoint.NONE -> stringResource(R.string.home_mp_two_detail, brand.offsetName)
        Multipoint.TEN -> stringResource(R.string.home_mp_ten_detail)
        Multipoint.TWENTY -> stringResource(R.string.home_mp_twenty_detail)
    }

    val art: @Composable () -> Unit = { Icon(symbol("slider.vertical.3"), contentDescription = null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (m in listOf(Multipoint.NONE, Multipoint.TEN, Multipoint.TWENTY)) {
            ChoiceTile(
                title = title(m), detail = detail(m),
                note = if (m == brand.multipoint) stringResource(R.string.home_usual_for, brand.label) else null,
                selected = known && selection == m,
                art = art,
                action = {
                    onSelectionChange(m)
                    onKnownChange(true)
                },
            )
        }
        ChoiceTile(
            title = stringResource(R.string.home_mp_not_sure), selected = !known, art = art,
            action = {
                onKnownChange(false)
                onSelectionChange(brand.multipoint)
            },
        )
    }
}

/** Hue / Saturation / Brightness, Red / Green / Blue, none, or not sure. */
@Composable
fun ColourMenuChoice(
    brand: Brand,
    selection: ColourMenu,
    onSelectionChange: (ColourMenu) -> Unit,
    known: Boolean,
    onKnownChange: (Boolean) -> Unit,
) {
    @Composable
    fun title(c: ColourMenu): String = when (c) {
        ColourMenu.HSL -> brand.cmsMenuLabel
        ColourMenu.RGB_SLIDERS -> stringResource(R.string.home_cm_rgb)
        ColourMenu.OFF -> stringResource(R.string.home_cm_off)
    }

    val art: @Composable () -> Unit = { Icon(symbol("slider.horizontal.3"), contentDescription = null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (c in listOf(ColourMenu.HSL, ColourMenu.RGB_SLIDERS, ColourMenu.OFF)) {
            ChoiceTile(
                title = title(c), note = if (c == brand.colourMenu) stringResource(R.string.home_usual_for, brand.label) else null,
                selected = known && selection == c,
                art = art,
                action = {
                    onSelectionChange(c)
                    onKnownChange(true)
                },
            )
        }
        ChoiceTile(
            title = stringResource(R.string.home_not_sure), selected = !known, art = art,
            action = {
                onKnownChange(false)
                onSelectionChange(brand.colourMenu)
            },
        )
    }
}

/**
 * Asked at the step that needs it, when the user said "not sure" while adding the TV.
 * Its Continue is the screen's pinned main action (PinnedActionOrInline; GuidedScreen pins it).
 */
@Composable
fun MenuQuestionCard(tv: TVRecord, colours: Boolean) {
    val app = LocalApp.current
    var multipoint by remember(tv.id) { mutableStateOf(tv.multipoint) }
    var colourMenu by remember(tv.id) { mutableStateOf(tv.colourMenu) }
    var known by remember(tv.id) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(if (colours) R.string.home_mq_colour_title else R.string.home_mq_wb_title),
            style = Type.stepTitleStyle,
        )
        Text(
            if (colours) stringResource(R.string.home_mq_colour_text, tv.brand.colourMenuPath)
            else stringResource(R.string.home_mq_wb_text, tv.brand.whiteBalancePath),
            style = Type.bodyBrand.copy(lineHeight = 22.sp), color = Theme.textSecondary,
        )
        if (colours) {
            WalkArtPanel(height = 130.dp) { ColourMenuArt(menu = colourMenu) }
            ColourMenuChoice(
                brand = tv.brand, selection = colourMenu, onSelectionChange = { colourMenu = it },
                known = known, onKnownChange = { known = it },
            )
        } else {
            WalkArtPanel(height = 130.dp) { WhiteBalanceArt(points = multipoint.artPoints) }
            MultipointChoice(
                brand = tv.brand, selection = multipoint, onSelectionChange = { multipoint = it },
                known = known, onKnownChange = { known = it },
            )
        }
        PinnedActionOrInline(stringResource(R.string.home_continue), enabled = known) {
            val t = if (colours) tv.copy(colourMenu = colourMenu, colourMenuKnown = true)
            else tv.copy(multipoint = multipoint, multipointKnown = true)
            app.update(t)
        }
    }
}
