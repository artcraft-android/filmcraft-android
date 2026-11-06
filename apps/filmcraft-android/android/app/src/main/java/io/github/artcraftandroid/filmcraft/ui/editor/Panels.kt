package io.github.artcraftandroid.filmcraft.ui.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.artcraftandroid.filmcraft.R
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.artcraftandroid.filmcraft.core.ClipInfo
import io.github.artcraftandroid.filmcraft.core.PresetInfo
import io.github.artcraftandroid.filmcraft.core.effectPresets
import io.github.artcraftandroid.filmcraft.core.effectSample
import io.github.artcraftandroid.filmcraft.core.transitionSample
import io.github.artcraftandroid.filmcraft.core.filterPresets
import io.github.artcraftandroid.filmcraft.core.transitionPresets
import io.github.artcraftandroid.filmcraft.engine.toBitmap
import io.github.artcraftandroid.filmcraft.ui.Fc
import io.github.artcraftandroid.filmcraft.ui.FcSize
import io.github.artcraftandroid.filmcraft.ui.FcSlider
import io.github.artcraftandroid.filmcraft.ui.FcType
import io.github.artcraftandroid.filmcraft.ui.GlyphIcon
import io.github.artcraftandroid.filmcraft.ui.LucideIcons
import io.github.artcraftandroid.filmcraft.ui.PanelAction
import io.github.artcraftandroid.filmcraft.ui.PresetTile
import io.github.artcraftandroid.filmcraft.ui.SliderLook
import io.github.artcraftandroid.filmcraft.ui.SliderRow
import io.github.artcraftandroid.filmcraft.ui.ToolPanel
import io.github.artcraftandroid.filmcraft.ui.glyph
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The open tool's panel. */
@Composable
fun EditorPanel(ed: EditorController, panel: Panel, modifier: Modifier = Modifier) {
    val clip = ed.selectedClip
    val close = { ed.panel = null }
    when (panel) {
        Panel.Speed -> clip?.let { SpeedPanel(ed, it, close, modifier) }
        Panel.Volume -> clip?.let { VolumePanel(ed, it, close, modifier) }
        Panel.Fade -> clip?.let { FadePanel(ed, it, close, modifier) }
        Panel.Opacity -> clip?.let { OpacityPanel(ed, it, close, modifier) }
        Panel.Filters -> clip?.let { LookPanel(ed, it, filters = true, close, modifier) }
        Panel.Effects -> clip?.let { LookPanel(ed, it, filters = false, close, modifier) }
        is Panel.Transitions -> TransitionPanel(ed, panel, close, modifier)
        Panel.AspectRatio -> AspectPanel(ed, close, modifier)
        Panel.TextStyle -> clip?.let { TextStylePanel(ed, it, close, modifier) }
        Panel.Voiceover -> VoiceoverPanel(ed, close, modifier)
        Panel.Captions -> CaptionsPanel(ed, close, modifier)
        is Panel.Speech -> SpeechPanel(ed, panel, close, modifier)
        is Panel.TextInput -> {}
    }
    // a panel whose clip went away closes
    val standalone = panel is Panel.Transitions || panel == Panel.AspectRatio || panel == Panel.Voiceover || panel is Panel.TextInput || panel == Panel.Captions || panel is Panel.Speech
    if (clip == null && !standalone) {
        LaunchedEffect(Unit) { ed.panel = null }
    }
}

/** Speed anchors: the slider's five evenly spaced marks. */
private val SPEEDS = doubleArrayOf(0.1, 1.0, 2.0, 5.0, 10.0)

@Composable
private fun SpeedPanel(ed: EditorController, c: ClipInfo, close: () -> Unit, modifier: Modifier) {
    var speed by remember(c.id, c.speed) { mutableDoubleStateOf(c.speed) }
    ToolPanel(
        stringResource(R.string.panels_speed), close, modifier, tag = "speed_panel",
        actions = { PanelAction(LucideIcons.Undo2.glyph(), stringResource(R.string.panels_reset_speed), { speed = 1.0; ed.setSpeed(1.0, true) }) },
    ) {
        Spacer(Modifier.height(4.dp))
        Text(TimelineMath.speedLabel(speed), style = FcType.Value, color = Fc.Secondary, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth().padding(horizontal = FcSize.PanelPadding))
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = FcSize.PanelPadding + 4.dp)) {
            SPEEDS.forEachIndexed { i, v ->
                Text(
                    TimelineMath.speedLabel(v),
                    style = FcType.Caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal),
                    color = Fc.Detail,
                    textAlign = when (i) { 0 -> TextAlign.Start; SPEEDS.size - 1 -> TextAlign.End; else -> TextAlign.Center },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        FcSlider(
            fraction = TimelineMath.piecewiseFraction(speed, SPEEDS),
            onChange = { f -> speed = Math.round(TimelineMath.piecewise(f, SPEEDS) * 100) / 100.0 },
            onDone = { ed.setSpeed(speed, true) },
            ticks = listOf(0.25f, 0.5f, 0.75f),
            snapTo = 0.25f,
            description = stringResource(R.string.panels_speed),
            tag = "speed_slider",
            modifier = Modifier.padding(horizontal = FcSize.PanelPadding - 10.dp),
        )
        val sourceLen = (c.duration * c.speed).toLong()
        val newLen = if (speed > 0) (sourceLen / speed).toLong() else c.duration
        Text(
            stringResource(R.string.panels_duration_value, TimelineMath.clock(newLen), TimelineMath.frameOfSecond(newLen, ed.rateNum, ed.rateDen)),
            style = FcType.Caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal),
            color = Fc.Detail,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun VolumePanel(ed: EditorController, c: ClipInfo, close: () -> Unit, modifier: Modifier) {
    var v by remember(c.id) { mutableDoubleStateOf(c.volume) }
    ToolPanel(
        stringResource(R.string.panels_volume), close, modifier, tag = "volume_panel",
        actions = {
            PanelAction(
                (if (c.muted) LucideIcons.VolumeX else LucideIcons.Volume2).glyph(),
                if (c.muted) stringResource(R.string.panels_unmute) else stringResource(R.string.panels_mute),
                { ed.setMuted(!c.muted) },
            )
        },
    ) {
        Spacer(Modifier.height(12.dp))
        Text("${Math.round(v * 100)}%", style = FcType.Value, color = Fc.Secondary, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth().padding(horizontal = FcSize.PanelPadding))
        Spacer(Modifier.height(4.5.dp))
        FcSlider(
            fraction = (v / 2.0).toFloat(),
            onChange = { f -> v = Math.round(f * 200) / 100.0; ed.setVolume(v) },
            enabled = !c.muted,
            snapTo = 0.5f,
            description = stringResource(R.string.panels_volume),
            tag = "volume_slider",
            modifier = Modifier.padding(horizontal = FcSize.PanelPadding - 10.dp),
        )
        Spacer(Modifier.height(21.dp))
    }
}

@Composable
private fun FadePanel(ed: EditorController, c: ClipInfo, close: () -> Unit, modifier: Modifier) {
    val max = minOf(10 * TPS, c.duration).coerceAtLeast(1)
    var fadeIn by remember(c.id) { mutableStateOf(c.fadeIn) }
    var fadeOut by remember(c.id) { mutableStateOf(c.fadeOut) }
    val tenth = TPS / 10
    ToolPanel(stringResource(R.string.panels_fade), close, modifier, tag = "fade_panel") {
        Spacer(Modifier.height(5.dp))
        SliderRow(stringResource(R.string.panels_fade_in), TimelineMath.seconds1(fadeIn)) {
            FcSlider(
                fraction = fadeIn.toFloat() / max,
                onChange = { f -> fadeIn = (f * max / tenth).toLong() * tenth },
                onDone = { ed.setFades(fadeIn, fadeOut) },
                description = stringResource(R.string.panels_fade_in),
                tag = "fade_in_slider",
            )
        }
        Spacer(Modifier.height(40.dp))
        SliderRow(stringResource(R.string.panels_fade_out), TimelineMath.seconds1(fadeOut)) {
            FcSlider(
                fraction = fadeOut.toFloat() / max,
                onChange = { f -> fadeOut = (f * max / tenth).toLong() * tenth },
                onDone = { ed.setFades(fadeIn, fadeOut) },
                description = stringResource(R.string.panels_fade_out),
                tag = "fade_out_slider",
            )
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun OpacityPanel(ed: EditorController, c: ClipInfo, close: () -> Unit, modifier: Modifier) {
    var v by remember(c.id) { mutableDoubleStateOf(c.opacity) }
    ToolPanel(
        stringResource(R.string.panels_opacity), close, modifier, tag = "opacity_panel",
        actions = {
            PanelAction(LucideIcons.CopyCheck.glyph(), stringResource(R.string.panels_apply_to_all), { ed.setOpacity(v, all = true) }, tag = "opacity_apply_to_all")
            PanelAction(LucideIcons.Undo2.glyph(), stringResource(R.string.panels_revert), { v = 1.0; ed.setOpacity(1.0) })
        },
    ) {
        Row(Modifier.fillMaxWidth().padding(start = FcSize.PanelPadding - 10.dp, end = FcSize.PanelPadding, bottom = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            FcSlider(
                fraction = v.toFloat(),
                onChange = { f -> v = Math.round(f * 100) / 100.0; ed.setOpacity(v) },
                look = SliderLook.Opacity,
                description = stringResource(R.string.panels_opacity),
                tag = "opacity_slider",
                modifier = Modifier.weight(1f),
            )
            Text("${Math.round(v * 100)}%", style = FcType.Value, color = Fc.Secondary, textAlign = TextAlign.End, modifier = Modifier.width(56.dp))
        }
    }
}

/** The effect and transition pickers' sample pictures: made once per app run. */
object Samples {
    private val made = java.util.concurrent.ConcurrentHashMap<String, ImageBitmap>()
    private val worker = kotlinx.coroutines.Dispatchers.Default.limitedParallelism(2)

    /** The picture for [key], made by [make] the first time; [keep] false makes it every time. */
    suspend fun get(key: String, keep: Boolean = true, make: () -> io.github.artcraftandroid.filmcraft.core.Frame): ImageBitmap? {
        made[key]?.let { return it }
        val b = withContext(worker) { runCatching { make().toBitmap()?.asImageBitmap() }.getOrNull() } ?: return null
        if (keep) made[key] = b
        return b
    }
}

/** A picture of a preset applied, made by the engine in the background. */
@Composable
private fun rememberPreview(key: Any, make: suspend () -> ImageBitmap?): ImageBitmap? {
    val v by produceState<ImageBitmap?>(null, key) { value = make() }
    return v
}

@Composable
private fun LookPanel(ed: EditorController, c: ClipInfo, filters: Boolean, close: () -> Unit, modifier: Modifier) {
    val presets: List<PresetInfo> = remember(filters) { if (filters) filterPresets() else effectPresets() }
    val current = if (filters) c.filter else c.effect
    val default = if (filters) 100.0 else 50.0
    var amount by remember(c.id, current) { mutableDoubleStateOf(if (filters) c.filterAmount.takeIf { current.isNotEmpty() } ?: 100.0 else c.effectAmount.takeIf { current.isNotEmpty() } ?: 50.0) }
    val renderer by produceState<io.github.artcraftandroid.filmcraft.core.Renderer?>(null, ed.timeline?.revision) { value = runCatching { ed.engine.command { renderer() } }.getOrNull() }
    val time = ed.playhead.coerceIn(c.start, c.start + c.duration - ed.frameTicks)
    fun apply(id: String, a: Double, merge: Boolean) = if (filters) ed.setFilter(id, a, merge) else ed.setEffect(id, a, merge)
    ToolPanel(
        if (filters) stringResource(R.string.panels_filters) else stringResource(R.string.panels_effects), close, modifier, tag = if (filters) "filters_panel" else "effects_panel",
        actions = {
            PanelAction(LucideIcons.CopyCheck.glyph(), stringResource(R.string.panels_apply_to_all), {
                if (filters) ed.setFilter(current, amount, false, all = true) else ed.setEffect(current, amount, false, all = true)
            }, tag = if (filters) "filters_apply_to_all_button" else "effects_apply_to_all_button")
            PanelAction(LucideIcons.Undo2.glyph(), stringResource(R.string.panels_revert), { apply("", 0.0, false) }, tag = if (filters) "filters_revert_button" else "effects_revert_button")
        },
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(84.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = if (current.isEmpty()) 252.dp else 172.dp).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                PresetTile(stringResource(R.string.panels_none), current.isEmpty(), { apply("", 0.0, false) }, tag = if (filters) "filter_item_none" else "effect_item_none") {
                    GlyphIcon(LucideIcons.Ban.glyph(), Fc.Body, size = 32.dp)
                }
            }
            items(presets, key = { it.id }) { p ->
                PresetTile(p.name, current == p.id, { amount = default; apply(p.id, default, false) }, tag = if (filters) "filter_item_${p.id}" else "effect_item_${p.id}") {
                    val img = if (filters) {
                        rememberPreview(listOf(renderer?.revision(), p.id, time / (TPS / 2))) {
                            val r = renderer ?: return@rememberPreview null
                            withContext(ed.engine.thumbnails) {
                                runCatching { r.lookPreview(c.id, p.id, 100.0, c.effect, c.effectAmount, time, 160u).toBitmap()?.asImageBitmap() }.getOrNull()
                            }
                        }
                    } else {
                        rememberPreview("effect:" + p.id) { Samples.get("effect:" + p.id) { effectSample(p.id, 60.0, 192u) } }
                    }
                    if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
        }
        if (current.isNotEmpty()) {
            HorizontalDivider(color = Fc.Divider, thickness = 1.dp)
            Spacer(Modifier.height(12.dp))
            val max = if (filters) 200.0 else 100.0
            SliderRow(stringResource(R.string.panels_intensity), "${Math.round(amount)}") {
                FcSlider(
                    fraction = (amount / max).toFloat(),
                    onChange = { f -> amount = Math.round(f * max).toDouble(); apply(current, amount, true) },
                    snapTo = if (filters) 0.5f else null,
                    description = stringResource(R.string.panels_intensity),
                    tag = if (filters) "filters_intensity_slider" else "effects_intensity_slider",
                )
            }
            Spacer(Modifier.height(12.dp))
        } else {
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Each transition's tile frames, kept for the tiles seen most recently (a few hundred KB each). */
private object TileFrames {
    const val STEPS = 8
    val cache = android.util.LruCache<String, List<ImageBitmap>>(28)
}

/** A transition tile that plays its transition over and over (the still picture until it's ready). */
@Composable
private fun playingSample(id: String): ImageBitmap? {
    var frames by remember(id) { mutableStateOf(TileFrames.cache.get(id).orEmpty()) }
    var shown by remember(id) { mutableIntStateOf(0) }
    LaunchedEffect(id) {
        if (frames.isEmpty()) {
            val made = ArrayList<ImageBitmap>()
            for (k in 0..TileFrames.STEPS) {
                val p = k.toDouble() / TileFrames.STEPS
                made += Samples.get("transition:$id@$k", keep = false) { transitionSample(id, p, 112u) } ?: continue
            }
            if (made.isNotEmpty()) TileFrames.cache.put(id, made)
            frames = made
        }
        while (frames.isNotEmpty()) {
            for (k in frames.indices) {
                shown = k
                delay(if (k == frames.lastIndex) 500 else 80)
            }
        }
    }
    return frames.getOrNull(shown) ?: rememberPreview("transition:$id") { Samples.get("transition:$id") { transitionSample(id, 0.4, 192u) } }
}

@Composable
private fun TransitionPanel(ed: EditorController, panel: Panel.Transitions, close: () -> Unit, modifier: Modifier) {
    val presets = remember { transitionPresets() }
    val t = ed.timeline
    val current = t?.transitions?.firstOrNull { it.from == panel.from && it.to == panel.to }
    val title = when {
        panel.from == null -> stringResource(R.string.panels_intro_transition)
        panel.to == null -> stringResource(R.string.panels_outro_transition)
        else -> stringResource(R.string.panels_transitions)
    }
    var seconds by remember(current?.id) { mutableDoubleStateOf(current?.let { it.duration.toDouble() / TPS } ?: 0.4) }
    val max by produceState(5.0, panel, t?.revision) { value = ed.maxTransition(panel.from, panel.to).coerceAtLeast(0.1) }
    val renderer by produceState<io.github.artcraftandroid.filmcraft.core.Renderer?>(null, t?.revision) { value = runCatching { ed.engine.command { renderer() } }.getOrNull() }
    ToolPanel(
        title, close, modifier, tag = "transitions_panel_root",
        actions = {
            if (panel.from != null && panel.to != null) {
                PanelAction(LucideIcons.CopyCheck.glyph(), stringResource(R.string.panels_apply_to_all), { ed.setTransition(panel.from, panel.to, current?.preset.orEmpty(), seconds, all = true) })
            }
            PanelAction(LucideIcons.Undo2.glyph(), stringResource(R.string.panels_revert), { ed.setTransition(panel.from, panel.to, "", 0.0) }, enabled = current != null, tag = "transitions_revert_button")
        },
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(84.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = if (current == null) 252.dp else 172.dp).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                PresetTile(stringResource(R.string.panels_none), current == null, { ed.setTransition(panel.from, panel.to, "", 0.0) }, tag = "transition_item_none") {
                    GlyphIcon(LucideIcons.Ban.glyph(), Fc.Body, size = 32.dp)
                }
            }
            items(presets, key = { it.id }) { p ->
                PresetTile(p.name, current?.preset == p.id, { ed.setTransition(panel.from, panel.to, p.id, seconds.coerceAtMost(max)) }, tag = "transition_item_${p.id}") {
                    // every tile plays its transition
                    val img = playingSample(p.id)
                    if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
        }
        if (current != null) {
            HorizontalDivider(color = Fc.Divider, thickness = 1.dp)
            Spacer(Modifier.height(12.dp))
            SliderRow(stringResource(R.string.panels_duration), "%.1fs".format(seconds)) {
                FcSlider(
                    fraction = ((seconds - 0.1) / (max - 0.1).coerceAtLeast(0.01)).toFloat(),
                    onChange = { f -> seconds = Math.round((0.1 + f * (max - 0.1)) * 10) / 10.0 },
                    onDone = { ed.setTransitionDuration(current.id, seconds) },
                    description = stringResource(R.string.panels_duration),
                    tag = "transitions_duration_slider",
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** The aspect ratios offered, in order. */
private val ASPECTS = listOf(16 to 9, 9 to 16, 4 to 3, 3 to 4, 1 to 1, 4 to 5, 3 to 2, 2 to 3, 5 to 4, 21 to 9, 2 to 1)

@Composable
private fun AspectPanel(ed: EditorController, close: () -> Unit, modifier: Modifier) {
    val t = ed.timeline ?: return
    val now = t.width.toDouble() / t.height.toDouble().coerceAtLeast(1.0)
    ToolPanel(stringResource(R.string.panels_aspect_ratio), close, modifier, tag = "aspect_ratio_panel") {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 2.dp, top = 1.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            for ((n, d) in ASPECTS) {
                val selected = kotlin.math.abs(now - n.toDouble() / d) < 0.01
                Column(
                    Modifier
                        .clickable(role = Role.RadioButton) { ed.setAspect(n, d) }
                        .testTag("aspect_ratio_panel_option_${n}_$d")
                        .semantics { this.selected = selected },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // the short side is 45 dp and the long side follows the ratio, up to 80 dp
                    val ratio = maxOf(n, d).toFloat() / minOf(n, d)
                    val long = minOf(80f, 45f * ratio).dp
                    val short = long / ratio
                    val w = if (n >= d) long else short
                    val h = if (n >= d) short else long
                    Box(
                        Modifier
                            .size(w, h)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Fc.Raised)
                            .then(if (selected) Modifier.border(2.dp, androidx.compose.ui.graphics.Color.White, RoundedCornerShape(10.dp)) else Modifier),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("$n:$d", style = FcType.Header, color = if (selected) Fc.Title else Fc.Body)
                }
            }
        }
    }
}
