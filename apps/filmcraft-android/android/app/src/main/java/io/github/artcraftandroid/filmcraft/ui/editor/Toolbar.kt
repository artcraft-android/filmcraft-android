package io.github.artcraftandroid.filmcraft.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import io.github.artcraftandroid.filmcraft.R
import androidx.compose.ui.unit.dp
import io.github.artcraftandroid.filmcraft.core.ClipInfo
import io.github.artcraftandroid.filmcraft.core.ClipKind
import io.github.artcraftandroid.filmcraft.ui.Fc
import io.github.artcraftandroid.filmcraft.ui.FcSize
import io.github.artcraftandroid.filmcraft.ui.Glyph
import io.github.artcraftandroid.filmcraft.ui.GlyphIcon
import io.github.artcraftandroid.filmcraft.ui.Glyphs
import io.github.artcraftandroid.filmcraft.ui.LucideIcons
import io.github.artcraftandroid.filmcraft.ui.ToolTile
import io.github.artcraftandroid.filmcraft.ui.glyph

/** One tool on the bar. */
data class Tool(val tag: String, val label: String, val glyph: Glyph, val enabled: Boolean = true, val active: Boolean = false, val onClick: () -> Unit)

/**
 * The tool bar under the timeline: what can be added when nothing is selected, the selected
 * clip's tools otherwise, and the submenus some of them open.
 */
@Composable
fun EditorToolbar(ed: EditorController, modifier: Modifier = Modifier) {
    val clip = ed.selectedClip
    val caption = ed.selectedCaptionInfo
    val (back, tools) = when {
        ed.submenu == Submenu.AddAudio -> Pair({ ed.submenu = Submenu.None }, addAudioTools(ed))
        ed.submenu == Submenu.Files -> Pair({ ed.submenu = Submenu.None }, filesTools(ed))
        clip != null && ed.submenu == Submenu.Retime -> Pair({ ed.submenu = Submenu.None }, retimeTools(ed, clip))
        clip != null && ed.submenu == Submenu.ClipAudio -> Pair({ ed.submenu = Submenu.None }, clipAudioTools(ed, clip))
        clip != null -> Pair({ ed.select(null) }, clipTools(ed, clip))
        caption != null -> Pair({ ed.selectedCaption = null }, captionTools(ed, caption.id))
        else -> Pair(null, addTools(ed))
    }
    // a submenu's back button goes up a level (a double chevron); a clip's clears the selection
    ToolRow(tools, back, deep = clip != null && (ed.submenu == Submenu.Retime || ed.submenu == Submenu.ClipAudio), modifier)
}

@Composable
private fun ToolRow(tools: List<Tool>, back: (() -> Unit)?, deep: Boolean, modifier: Modifier) {
    val backDesc = stringResource(R.string.editor_back)
    BoxWithConstraints(modifier.fillMaxWidth().height(FcSize.ToolTile)) {
        // with a back button the tools start clear of it and centre in what is left
        val start = if (back != null) 43.5.dp else 0.dp
        val end = if (back != null) 15.dp else 0.dp
        val fits = FcSize.ToolTile * tools.size <= maxWidth - start - end
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (fits) Modifier else Modifier.horizontalScroll(rememberScrollState()))
                .padding(start = start, end = end),
            horizontalArrangement = if (fits) Arrangement.Center else Arrangement.Start,
        ) {
            for (t in tools) ToolTile(t.glyph, t.label, t.onClick, enabled = t.enabled, active = t.active, tag = t.tag)
        }
        if (back != null) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(48.dp)
                    .height(FcSize.ToolTile)
                    .background(Brush.horizontalGradient(0.7f to Fc.Black, 1f to Color.Transparent)),
            )
            Box(
                Modifier
                    // level with the top of the tool row, not centred in it
                    .align(Alignment.TopStart)
                    .padding(start = 10.dp)
                    .size(width = 28.dp, height = 58.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Fc.Raised)
                    .clickable(role = Role.Button, onClick = back)
                    .testTag("editor_toolbar_back")
                    .semantics { contentDescription = backDesc },
                contentAlignment = Alignment.Center,
            ) {
                GlyphIcon((if (deep) LucideIcons.ChevronsLeft else LucideIcons.ChevronLeft).glyph(), Fc.Icon, size = 20.dp)
            }
        }
    }
}

@Composable
private fun addTools(ed: EditorController) = listOf(
    Tool("editor_tool_audio", stringResource(R.string.editor_tool_audio), LucideIcons.Music.glyph()) { ed.submenu = Submenu.AddAudio },
    Tool("editor_tool_text", stringResource(R.string.editor_tool_text), LucideIcons.Type.glyph()) { ed.panel = Panel.TextInput(null) },
    Tool("editor_tool_captions", stringResource(R.string.editor_tool_captions), LucideIcons.Captions.glyph(), enabled = ed.duration > 0) { ed.panel = Panel.Captions },
    Tool("editor_tool_files", stringResource(R.string.editor_tool_files), LucideIcons.File.glyph()) { ed.submenu = Submenu.Files },
    Tool("editor_tool_blank", stringResource(R.string.editor_tool_blank), LucideIcons.RectangleHorizontal.glyph()) { ed.addBlank() },
    Tool("editor_tool_aspect_ratio", stringResource(R.string.editor_tool_aspect_ratio), LucideIcons.Ratio.glyph()) { ed.panel = Panel.AspectRatio },
)

@Composable
private fun addAudioTools(ed: EditorController) = listOf(
    Tool("editor_tool_voiceover", stringResource(R.string.editor_tool_voiceover), LucideIcons.Mic.glyph()) { ed.submenu = Submenu.None; ed.panel = Panel.Voiceover },
    Tool("editor_tool_text_to_speech", stringResource(R.string.editor_tool_text_to_speech), LucideIcons.Speech.glyph()) { ed.submenu = Submenu.None; ed.panel = Panel.Speech(null) },
    Tool("editor_tool_extract_audio_file", stringResource(R.string.editor_tool_extract_audio), LucideIcons.AudioLines.glyph()) { ed.extractAudioFromFile() },
)

@Composable
private fun filesTools(ed: EditorController) = listOf(
    Tool("editor_tool_files_visual", stringResource(R.string.editor_tool_files_visual), LucideIcons.Images.glyph()) { ed.addMedia(PickKind.FilesVisual) },
    Tool("editor_tool_files_audio", stringResource(R.string.editor_tool_audio), LucideIcons.Music.glyph()) { ed.addMedia(PickKind.FilesAudio) },
)

/** Whether the playhead is far enough inside [c] to split it (two frames from each end). */
private fun canSplit(ed: EditorController, c: ClipInfo): Boolean {
    val margin = ed.frameTicks * 2
    return ed.playhead >= c.start + margin && ed.playhead <= c.start + c.duration - margin
}

@Composable
private fun clipTools(ed: EditorController, c: ClipInfo): List<Tool> {
    val split = Tool("editor_tool_split", stringResource(R.string.editor_tool_split), LucideIcons.Scissors.glyph(), enabled = canSplit(ed, c)) { ed.split() }
    val delete = Tool("editor_tool_delete", stringResource(R.string.editor_tool_delete), LucideIcons.Trash2.glyph()) { ed.delete() }
    val duplicate = Tool("editor_tool_duplicate", stringResource(R.string.editor_tool_duplicate), LucideIcons.CopyPlus.glyph()) { ed.duplicate() }
    val replace = Tool("editor_tool_replace", stringResource(R.string.editor_tool_replace), LucideIcons.Replace.glyph()) { ed.replaceSelected() }
    val onMain = ed.isMain(c.id)
    val picture = listOfNotNull(
        Tool("editor_tool_filters", stringResource(R.string.editor_tool_filters), LucideIcons.SwatchBook.glyph()) { ed.panel = Panel.Filters },
        if (onMain) Tool("editor_tool_effects", stringResource(R.string.editor_tool_effects), LucideIcons.WandSparkles.glyph()) { ed.panel = Panel.Effects } else null,
        Tool("editor_tool_opacity", stringResource(R.string.editor_tool_opacity), Glyphs.Checker) { ed.panel = Panel.Opacity },
        // Lucide names these by their axis line: "flip-vertical-2" has left/right arrows (a mirror)
        // one-tap toggles with no on/off look: the preview shows which way the picture faces
        Tool("editor_tool_flip_h", stringResource(R.string.editor_tool_mirror), LucideIcons.FlipVertical2.glyph()) { ed.toggleMirror() },
        Tool("editor_tool_flip_v", stringResource(R.string.editor_tool_flip), LucideIcons.FlipHorizontal2.glyph()) { ed.toggleFlip() },
    )
    return when (c.kind) {
        ClipKind.MISSING -> listOf(delete, replace)
        ClipKind.TEXT -> listOf(
            Tool("editor_tool_edit_text", stringResource(R.string.editor_tool_edit_text), LucideIcons.Keyboard.glyph()) { ed.panel = Panel.TextInput(c.id) },
            Tool("editor_tool_style", stringResource(R.string.editor_tool_style), LucideIcons.Paintbrush.glyph()) { ed.panel = Panel.TextStyle },
            split, delete, duplicate,
        )
        ClipKind.AUDIO -> listOfNotNull(
            if (c.speech) Tool("editor_tool_edit_speech", stringResource(R.string.editor_tool_edit_speech), LucideIcons.Speech.glyph()) { ed.panel = Panel.Speech(c.id) } else null,
            split, delete,
            Tool("editor_tool_volume", stringResource(R.string.editor_tool_volume), (if (c.muted) LucideIcons.VolumeX else LucideIcons.Volume2).glyph()) { ed.panel = Panel.Volume },
            Tool("editor_tool_fade", stringResource(R.string.editor_tool_fade), LucideIcons.ChartSpline.glyph()) { ed.panel = Panel.Fade },
            duplicate, replace,
        )
        ClipKind.BLANK -> listOf(split, delete, duplicate, replace)
        ClipKind.IMAGE -> listOf(split, delete) + picture + listOf(duplicate, replace)
        ClipKind.VIDEO -> listOfNotNull(
            split, delete,
            Tool("editor_tool_retime_submenu", stringResource(R.string.editor_tool_retime), LucideIcons.Timer.glyph()) { ed.submenu = Submenu.Retime },
            if (c.hasAudio) Tool("editor_tool_audio_submenu", stringResource(R.string.editor_tool_audio), (if (c.muted) LucideIcons.VolumeX else LucideIcons.Volume2).glyph()) { ed.submenu = Submenu.ClipAudio } else null,
        ) + picture + listOf(duplicate, replace)
    }
}

@Composable
private fun captionTools(ed: EditorController, id: ULong) = listOf(
    Tool("editor_tool_edit_caption", stringResource(R.string.editor_tool_edit_text), LucideIcons.Keyboard.glyph()) { ed.panel = Panel.TextInput(null, caption = id) },
    Tool("editor_tool_caption_style", stringResource(R.string.editor_tool_style), LucideIcons.Paintbrush.glyph()) { ed.panel = Panel.Captions },
    Tool("editor_tool_delete_caption", stringResource(R.string.editor_tool_delete), LucideIcons.Trash2.glyph()) { ed.deleteCaption(id) },
)

@Composable
private fun retimeTools(ed: EditorController, c: ClipInfo) = listOf(
    Tool("editor_tool_speed", stringResource(R.string.editor_tool_speed), LucideIcons.Gauge.glyph(), active = ed.panel == Panel.Speed) { ed.panel = Panel.Speed },
    Tool("editor_tool_freeze", stringResource(R.string.editor_tool_freeze), LucideIcons.Snowflake.glyph(), enabled = ed.playhead >= c.start && ed.playhead < c.start + c.duration - ed.frameTicks) { ed.freeze() },
)

@Composable
private fun clipAudioTools(ed: EditorController, c: ClipInfo) = listOf(
    Tool("editor_tool_volume", stringResource(R.string.editor_tool_volume), (if (c.muted) LucideIcons.VolumeX else LucideIcons.Volume2).glyph(), active = ed.panel == Panel.Volume) { ed.panel = Panel.Volume },
    Tool("editor_tool_fade", stringResource(R.string.editor_tool_fade), LucideIcons.ChartSpline.glyph(), active = ed.panel == Panel.Fade) { ed.panel = Panel.Fade },
    Tool("editor_tool_extract_audio", stringResource(R.string.editor_tool_extract_audio), LucideIcons.AudioLines.glyph(), enabled = c.speed < 5.0) { ed.extractAudio() },
)

@Suppress("unused")
private val minTile = Modifier.widthIn(min = FcSize.ToolTile)
