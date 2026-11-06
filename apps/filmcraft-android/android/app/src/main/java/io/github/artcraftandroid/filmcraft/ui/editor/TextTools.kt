package io.github.artcraftandroid.filmcraft.ui.editor

import android.content.ClipData
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.artcraftandroid.filmcraft.R
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle as UiTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.filmcraft.core.ClipInfo
import io.github.artcraftandroid.filmcraft.core.TextAlign
import io.github.artcraftandroid.filmcraft.core.TextStyle
import io.github.artcraftandroid.filmcraft.core.textPresets
import io.github.artcraftandroid.filmcraft.ui.Fc
import io.github.artcraftandroid.filmcraft.ui.FcDialog
import io.github.artcraftandroid.filmcraft.ui.FcIconButton
import io.github.artcraftandroid.filmcraft.ui.FcSize
import io.github.artcraftandroid.filmcraft.ui.FcSlider
import io.github.artcraftandroid.filmcraft.ui.FcType
import io.github.artcraftandroid.filmcraft.ui.GlyphIcon
import io.github.artcraftandroid.filmcraft.ui.Glyphs
import io.github.artcraftandroid.filmcraft.ui.Inter
import io.github.artcraftandroid.filmcraft.ui.LucideIcons
import io.github.artcraftandroid.filmcraft.ui.PanelAction
import io.github.artcraftandroid.filmcraft.ui.SelectChip
import io.github.artcraftandroid.filmcraft.ui.SliderRow
import io.github.artcraftandroid.filmcraft.ui.ToolPanel
import io.github.artcraftandroid.filmcraft.ui.glyph

/** The strip on top of the keyboard where text is typed: a field and a round check. */
@Composable
fun TextInputStrip(ed: EditorController, panel: Panel.TextInput, modifier: Modifier = Modifier) {
    val initial = remember(panel) {
        when {
            panel.caption != null -> ed.timeline?.captions?.firstOrNull { it.id == panel.caption }?.text?.replace('\n', ' ')
            else -> panel.clip?.let { id -> ed.timeline?.lanes?.flatMap { it.clips }?.firstOrNull { it.id == id }?.text }
        }.orEmpty()
    }
    var text by remember(panel) { mutableStateOf(initial) }
    // new text shows on the preview while it is typed
    LaunchedEffect(text, panel) { ed.typing = if (panel.clip == null && panel.caption == null) text else "" }
    androidx.compose.runtime.DisposableEffect(panel) { onDispose { ed.typing = "" } }
    val focus = remember { FocusRequester() }
    val done = {
        val c = panel.clip
        val cap = panel.caption
        when {
            cap != null -> if (text != initial) ed.setCaptionText(cap, text)
            c == null -> ed.addText(text)
            text != initial -> ed.setText(c, text)
        }
        ed.panel = null
    }
    Row(
        modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = FcSize.PanelInset)
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(Fc.Panel)
            .padding(12.dp)
            .testTag("text_input_field"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = UiTextStyle(fontFamily = Inter, fontSize = 17.sp, color = Fc.Title),
            cursorBrush = SolidColor(Fc.Title),
            // Return starts a new line; the check button adds the text
            maxLines = 4,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 36.dp)
                .border(1.dp, Fc.Divider, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 7.dp)
                .focusRequester(focus),
        )
        // a 32 dp button in a 48 dp touch area
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button) { done() }
                .semantics { contentDescription = stringResource(R.string.panels_done) },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(Fc.Accent), contentAlignment = Alignment.Center) {
                GlyphIcon(LucideIcons.Check.glyph(), Color.White, size = 18.dp)
            }
        }
    }
    LaunchedEffect(panel) { runCatching { focus.requestFocus() } }
}

private enum class StyleTab(val labelRes: Int) { Presets(R.string.panels_tab_presets), Font(R.string.panels_tab_font), Color(R.string.panels_tab_color), Layout(R.string.panels_tab_layout) }

/** Text ▸ Style: presets, fonts, colours, alignment and width. */
@Composable
fun TextStylePanel(ed: EditorController, c: ClipInfo, close: () -> Unit, modifier: Modifier = Modifier) {
    var tab by remember { mutableStateOf(StyleTab.Presets) }
    val loaded by produceState<TextStyle?>(null, c.id, ed.timeline?.revision) { value = ed.textStyle(c.id) }
    var style by remember(c.id) { mutableStateOf<TextStyle?>(null) }
    LaunchedEffect(loaded) { if (loaded != null) style = loaded }
    val st = style ?: return
    fun update(s: TextStyle, merge: Boolean = false) {
        style = s
        ed.setTextStyle(c.id, s, merge)
    }
    ToolPanel(
        stringResource(R.string.panels_style), close, modifier, tag = "text_style_panel",
        actions = { PanelAction(LucideIcons.CopyCheck.glyph(), stringResource(R.string.panels_apply_to_all), { ed.setTextStyle(c.id, st, false, all = true) }, tag = "text_style_action_apply_to_all") },
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            for (t in StyleTab.entries) {
                Column(
                    Modifier
                        .weight(1f)
                        .clickable(role = Role.Tab) { tab = t }
                        .testTag("text_style_tab_${t.name.lowercase()}")
                        .semantics { selected = tab == t },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(t.labelRes), style = FcType.Label, color = if (tab == t) Fc.Title else Fc.Detail, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp))
                    Box(Modifier.width(48.dp).height(3.dp).clip(CircleShape).background(if (tab == t) Fc.Title else Color.Transparent))
                }
            }
        }
        HorizontalDivider(color = Fc.Divider, thickness = 1.dp)
        // the same height on every tab
        Box(Modifier.fillMaxWidth().height(252.dp)) {
            when (tab) {
                StyleTab.Presets -> PresetsTab(ed, c, st) { update(it) }
                StyleTab.Font -> FontTab(ed, st) { update(it) }
                StyleTab.Color -> ColorTab(st) { s, merge -> update(s, merge) }
                StyleTab.Layout -> LayoutTab(st) { s, merge -> update(s, merge) }
            }
        }
    }
}

@Composable
private fun PresetsTab(ed: EditorController, c: ClipInfo, st: TextStyle, apply: (TextStyle) -> Unit) {
    val presets = remember { textPresets() }
    val styles by produceState<List<Pair<String, TextStyle>>>(emptyList(), c.id) {
        value = (listOf("") + presets.map { it.id }).mapNotNull { id ->
            runCatching { ed.engine.command { textPresetStyle(c.id, id) } }.getOrNull()?.let { id to it }
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(styles, key = { it.first }) { (id, s) ->
            val selected = s.font == st.font && s.color == st.color && s.background == st.background && s.stroke == st.stroke && s.shadow == st.shadow
            Box(
                Modifier
                    .height(64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Fc.SliderOff)
                    .then(if (selected) Modifier.border(2.dp, Color.White, RoundedCornerShape(12.dp)) else Modifier)
                    .clickable(role = Role.RadioButton) { apply(s) }
                    .testTag("text_preset_${id.ifEmpty { "none" }}"),
                contentAlignment = Alignment.Center,
            ) {
                if (id.isEmpty()) {
                    GlyphIcon(LucideIcons.Ban.glyph(), Fc.Body, size = 22.dp)
                } else {
                    val bg = s.background?.let { Color(it.toInt()) }
                    Text(
                        c.text.lines().firstOrNull().orEmpty().ifBlank { stringResource(R.string.panels_title_fallback) },
                        style = UiTextStyle(fontFamily = familyFor(s.font), fontSize = 20.sp, fontWeight = weightFor(s.fontStyle), color = Color(s.color.toInt())),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .then(if (bg != null) Modifier.background(bg) else Modifier)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/** A Compose family for an engine font name: the app's Inter, else the phone's matching face. */
fun familyFor(name: String): FontFamily = when {
    name.equals("Inter", ignoreCase = true) -> Inter
    name.contains("Mono", ignoreCase = true) -> FontFamily.Monospace
    name.contains("Serif", ignoreCase = true) && !name.contains("Sans", ignoreCase = true) -> FontFamily.Serif
    name.contains("Cursive", ignoreCase = true) || name.contains("Script", ignoreCase = true) -> FontFamily.Cursive
    else -> runCatching { FontFamily(android.graphics.Typeface.create(name, android.graphics.Typeface.NORMAL)) }.getOrDefault(FontFamily.SansSerif)
}

fun weightFor(style: String): FontWeight = when {
    style.contains("Black", true) -> FontWeight.Black
    style.contains("ExtraBold", true) -> FontWeight.ExtraBold
    style.contains("SemiBold", true) -> FontWeight.SemiBold
    style.contains("Bold", true) -> FontWeight.Bold
    style.contains("Medium", true) -> FontWeight.Medium
    style.contains("Light", true) -> FontWeight.Light
    else -> FontWeight.Normal
}

@Composable
private fun FontTab(ed: EditorController, st: TextStyle, apply: (TextStyle) -> Unit) {
    val fonts by produceState<List<String>>(emptyList()) { value = runCatching { ed.engine.command { fonts() } }.getOrDefault(listOf("Inter")) }
    var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp).height(44.dp).clip(CircleShape).border(1.dp, Fc.Divider, CircleShape).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphIcon(LucideIcons.Search.glyph(), Fc.Detail, size = 20.dp)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text(stringResource(R.string.panels_search_fonts), style = FcType.Label, color = Fc.Detail)
                BasicTextField(query, { query = it }, singleLine = true, textStyle = UiTextStyle(fontFamily = Inter, fontSize = 16.sp, color = Fc.Title), cursorBrush = SolidColor(Fc.Title), modifier = Modifier.fillMaxWidth())
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(fonts.filter { query.isBlank() || it.contains(query.trim(), ignoreCase = true) }) { f ->
                val selected = f == st.font
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) Fc.PillButton else Fc.Raised)
                        .clickable(role = Role.RadioButton) { apply(st.copy(font = f, fontStyle = "Regular")) }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(f, style = UiTextStyle(fontFamily = familyFor(f), fontSize = 20.sp, color = if (selected) Fc.Title else Fc.Body), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The colours offered first. */
private val SWATCHES = longArrayOf(0xFFFFFFFF, 0xFF000000, 0xFFFF3B30, 0xFFFF8A00, 0xFFFFD60A, 0xFF34C759, 0xFF32D7F0)

/** Colours the user saved, kept across projects. */
private fun savedColors(ctx: Context): MutableList<Long> =
    ctx.getSharedPreferences("text", Context.MODE_PRIVATE).getString("colors", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }.toMutableList()

private fun storeColors(ctx: Context, colors: List<Long>) =
    ctx.getSharedPreferences("text", Context.MODE_PRIVATE).edit().putString("colors", colors.joinToString(",")).apply()

@Composable
private fun ColorTab(st: TextStyle, apply: (TextStyle, Boolean) -> Unit) {
    val ctx = LocalContext.current
    var background by remember { mutableStateOf(false) }
    val saved = remember { mutableStateListOf<Long>().apply { addAll(savedColors(ctx)) } }
    var picker by remember { mutableStateOf(false) }
    val current: Long? = if (background) st.background?.toLong()?.and(0xFFFFFFFFL) else st.color.toLong() and 0xFFFFFFFFL
    fun pick(argb: Long?) {
        val s = if (background) st.copy(background = argb?.let { (it and 0xFFFFFFFFL).toUInt() }) else st.copy(color = ((argb ?: 0xFFFFFFFFL) and 0xFFFFFFFFL).toUInt())
        apply(s, false)
    }
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectChip(stringResource(R.string.panels_text), !background, { background = false }, tag = "text_style_color_target_chip_text")
            SelectChip(stringResource(R.string.panels_background), background, { background = true }, tag = "text_style_color_target_chip_background")
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 6.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Swatch(null, false, glyphPicker = true) { picker = true }
            if (background) Swatch(null, current == null, none = true) { pick(null) }
            for (c in SWATCHES) Swatch(c, current == c) { pick(c) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(Fc.PillButton).clickable {
                    val c = current ?: return@clickable
                    if (c !in saved) {
                        saved.add(0, c)
                        storeColors(ctx, saved)
                    }
                }.semantics { contentDescription = stringResource(R.string.panels_save_color) },
                contentAlignment = Alignment.Center,
            ) { GlyphIcon(LucideIcons.Plus.glyph(), Fc.Body, size = 20.dp) }
            for (c in saved) Swatch(c, current == c, onLongClick = { saved.remove(c); storeColors(ctx, saved) }) { pick(c) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // plain icons, no pills
            FcIconButton(LucideIcons.Copy.glyph(), stringResource(R.string.panels_copy_hex), { current?.let { clipboard.setText(AnnotatedString(hex(it))) } }, size = 40.dp, iconSize = 20.dp)
            FcIconButton(LucideIcons.ClipboardPaste.glyph(), stringResource(R.string.panels_paste_hex), {
                parseHex(clipboard.getText()?.text.orEmpty())?.let { pick(it) }
            }, size = 40.dp, iconSize = 20.dp)
            Text(stringResource(R.string.panels_hex), style = FcType.Label, color = Fc.Detail)
            Box(Modifier.border(1.dp, Fc.Divider, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(current?.let { hex(it) } ?: stringResource(R.string.panels_none), style = FcType.Label, color = Fc.Title)
            }
        }
    }
    if (picker) {
        ColorPickerDialog(current ?: 0xFFFFFFFFL, onDismiss = { picker = false }) { c ->
            picker = false
            pick(c)
        }
    }
}

private fun hex(argb: Long) = "#%06X".format(argb and 0xFFFFFF)

private fun parseHex(s: String): Long? {
    val t = s.trim().removePrefix("#")
    if (t.length != 6) return null
    return t.toLongOrNull(16)?.let { 0xFF000000L or it }
}

@Composable
private fun Swatch(color: Long?, selected: Boolean, glyphPicker: Boolean = false, none: Boolean = false, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .then(if (selected) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
            .padding(4.dp)
            .clip(CircleShape)
            .then(if (color != null) Modifier.background(Color(color)) else Modifier)
            .pointerInput(onClick, onLongClick) { detectTapGestures(onTap = { onClick() }, onLongPress = { onLongClick?.invoke() }) }
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        when {
            glyphPicker -> GlyphIcon(Glyphs.ColorWheel, Color.White, size = 28.dp)
            none -> GlyphIcon(LucideIcons.Ban.glyph(), Fc.Body, size = 22.dp)
        }
    }
}


/** A simple colour picker: hue along a bar, saturation and brightness in a square. */
@Composable
private fun ColorPickerDialog(initial: Long, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toInt(), it) } }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    var version by remember { mutableIntStateOf(0) }
    val color = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)).toLong() and 0xFFFFFFFFL
    FcDialog(stringResource(R.string.panels_colors), stringResource(R.string.panels_use), { onPick(color) }, onDismiss) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.6f)
                .clip(RoundedCornerShape(12.dp))
                .pointerInput(Unit) { detectTapGestures { o -> sat = (o.x / size.width).coerceIn(0f, 1f); value = 1f - (o.y / size.height).coerceIn(0f, 1f); version++ } }
                .pointerInput(Unit) { detectDragGestures { c, _ -> sat = (c.position.x / size.width).coerceIn(0f, 1f); value = 1f - (c.position.y / size.height).coerceIn(0f, 1f); version++ } },
        ) {
            val base = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
            drawRect(Brush.horizontalGradient(listOf(Color.White, base)))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            drawCircle(Color.White, 9.dp.toPx(), Offset(sat * size.width, (1 - value) * size.height), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        }
        Spacer(Modifier.height(16.dp))
        FcSlider(fraction = hue / 360f, onChange = { hue = it * 360f }, description = stringResource(R.string.panels_hue))
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(Color(color)))
            Spacer(Modifier.width(12.dp))
            Text(hex(color), style = FcType.Label, color = Fc.Title)
        }
    }
    @Suppress("UNUSED_EXPRESSION") version
}


@Composable
private fun LayoutTab(st: TextStyle, apply: (TextStyle, Boolean) -> Unit) {
    var width by remember(st.width) { mutableFloatStateOf(st.width.toFloat()) }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(stringResource(R.string.panels_text_alignment), style = FcType.Label, color = Fc.Secondary, modifier = Modifier.padding(horizontal = FcSize.PanelPadding))
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = FcSize.PanelPadding), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((align, glyph, label) in listOf(
                Triple(TextAlign.LEFT, LucideIcons.AlignLeft, stringResource(R.string.panels_align_left)),
                Triple(TextAlign.CENTER, LucideIcons.AlignCenter, stringResource(R.string.panels_align_center)),
                Triple(TextAlign.RIGHT, LucideIcons.AlignRight, stringResource(R.string.panels_align_right)),
            )) {
                val selected = st.align == align
                Box(
                    Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) Fc.PillButton else Fc.Raised)
                        // the chosen one gets a white ring
                        .then(if (selected) Modifier.border(2.dp, Color.White, RoundedCornerShape(12.dp)) else Modifier)
                        .clickable(role = Role.RadioButton) { apply(st.copy(align = align), false) }
                        .semantics { contentDescription = label; this.selected = selected },
                    contentAlignment = Alignment.Center,
                ) { GlyphIcon(glyph.glyph(), if (selected) Fc.Title else Fc.Body) }
            }
        }
        Spacer(Modifier.height(32.dp))
        SliderRow(stringResource(R.string.panels_text_width), "") {
            Spacer(Modifier.height(8.dp))
            FcSlider(
                fraction = (width - 0.1f) / 0.9f,
                onChange = { f -> width = Math.round((0.1f + f * 0.9f) * 100) / 100f; apply(st.copy(width = width.toDouble()), true) },
                description = stringResource(R.string.panels_text_width),
                tag = "text_style_layout_text_width_slider",
            )
        }
    }
}

@Suppress("unused")
private val unusedArgb = Color.White.toArgb()
@Suppress("unused")
private val unusedClip: ClipData? = null
