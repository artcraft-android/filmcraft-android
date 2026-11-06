package io.github.artcraftandroid.filmcraft.ui.editor

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.filmcraft.R
import io.github.artcraftandroid.filmcraft.core.CaptionLook
import io.github.artcraftandroid.filmcraft.core.CaptionPosition
import io.github.artcraftandroid.filmcraft.core.ModelPackage
import io.github.artcraftandroid.filmcraft.core.SpeechAudio
import io.github.artcraftandroid.filmcraft.core.SpeechSettings
import io.github.artcraftandroid.filmcraft.core.VoiceInfo
import io.github.artcraftandroid.filmcraft.core.speechModels
import io.github.artcraftandroid.filmcraft.core.voicePackage
import io.github.artcraftandroid.filmcraft.core.voices
import io.github.artcraftandroid.filmcraft.engine.ModelDownloads
import io.github.artcraftandroid.filmcraft.engine.megabytes
import io.github.artcraftandroid.filmcraft.ui.DialogButton
import io.github.artcraftandroid.filmcraft.ui.Fc
import io.github.artcraftandroid.filmcraft.ui.FcDialog
import io.github.artcraftandroid.filmcraft.ui.FcSize
import io.github.artcraftandroid.filmcraft.ui.FcSlider
import io.github.artcraftandroid.filmcraft.ui.FcType
import io.github.artcraftandroid.filmcraft.ui.Inter
import io.github.artcraftandroid.filmcraft.ui.LucideIcons
import io.github.artcraftandroid.filmcraft.ui.PanelAction
import io.github.artcraftandroid.filmcraft.ui.PrimaryButton
import io.github.artcraftandroid.filmcraft.ui.SelectChip
import io.github.artcraftandroid.filmcraft.ui.SliderRow
import io.github.artcraftandroid.filmcraft.ui.ToolPanel
import io.github.artcraftandroid.filmcraft.ui.glyph
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Languages captions can be made in (Whisper knows many more; these are the common ones). */
private val LANGUAGES = listOf(
    "auto" to R.string.speech_language_auto, "en" to R.string.speech_language_en,
    "es" to R.string.speech_language_es, "fr" to R.string.speech_language_fr,
    "de" to R.string.speech_language_de, "it" to R.string.speech_language_it,
    "pt" to R.string.speech_language_pt, "nl" to R.string.speech_language_nl,
    "pl" to R.string.speech_language_pl, "tr" to R.string.speech_language_tr,
    "ru" to R.string.speech_language_ru, "uk" to R.string.speech_language_uk,
    "ar" to R.string.speech_language_ar, "hi" to R.string.speech_language_hi,
    "ja" to R.string.speech_language_ja, "ko" to R.string.speech_language_ko,
    "zh" to R.string.speech_language_zh,
)

/** The speech models by what they are good at. */
@Composable
private fun modelLabel(id: String) = stringResource(if (id == "whisper-tiny") R.string.speech_model_faster else R.string.speech_model_more_accurate)

private fun prefs(ctx: Context) = ctx.getSharedPreferences("speech", Context.MODE_PRIVATE)

/** Captions: made on the phone from what is said, then their size, place and box. */
@Composable
fun CaptionsPanel(ed: EditorController, close: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var models by remember { mutableStateOf<List<ModelPackage>>(emptyList()) }
    var model by remember { mutableStateOf(prefs(ctx).getString("model", "whisper-base") ?: "whisper-base") }
    var language by remember { mutableStateOf(prefs(ctx).getString("language", "auto") ?: "auto") }
    var asking by remember { mutableStateOf<ModelPackage?>(null) }
    val download = ModelDownloads.state
    LaunchedEffect(download == null) { models = withContext(Dispatchers.IO) { speechModels() } }
    val running = ed.captionProgress
    val has = ed.timeline?.captions?.isNotEmpty() == true
    var look by remember { mutableStateOf<CaptionLook?>(null) }
    LaunchedEffect(ed.timeline?.revision, has) { look = if (has) ed.captionLook() else null }
    fun go() {
        prefs(ctx).edit().putString("model", model).putString("language", language).apply()
        val m = models.firstOrNull { it.id == model } ?: return
        if (m.installed) ed.makeCaptions(model, language) else asking = m
    }
    ToolPanel(
        stringResource(R.string.speech_panel_captions), close, modifier, tag = "captions_panel",
        actions = { PanelAction(LucideIcons.Trash2.glyph(), stringResource(R.string.speech_delete_all_captions), { ed.deleteCaptions() }, enabled = has && running == null, tag = "captions_delete_all_button") },
    ) {
        val pad = Modifier.padding(horizontal = FcSize.PanelPadding)
        Column(Modifier.fillMaxWidth()) {
            when {
                running != null -> Column(pad) { ProgressBlock(running.stage, running.progress.toFloat(), stringResource(R.string.speech_stop), tag = "captions_stop_button") { ed.cancelCaptions() } }
                download != null && models.any { it.id == download.id } -> Column(pad) { DownloadBlock(download) }
                else -> {
                    Text(stringResource(R.string.speech_spoken_language), style = FcType.Label, color = Fc.Secondary, modifier = pad)
                    Spacer(Modifier.height(8.dp))
                    ChipRow {
                        for ((id, nameRes) in LANGUAGES) SelectChip(stringResource(nameRes), language == id, { language = id }, tag = "captions_language_$id")
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.speech_speech_model), style = FcType.Label, color = Fc.Secondary, modifier = pad)
                    Spacer(Modifier.height(8.dp))
                    ChipRow {
                        for (m in models) {
                            val label = modelLabel(m.id) + if (m.installed) "" else " · " + megabytes(m.missing.toLong())
                            SelectChip(label, model == m.id, { model = m.id }, tag = "captions_model_${m.id}")
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton(stringResource(if (has) R.string.speech_make_captions_again else R.string.speech_make_captions), { go() }, modifier = pad, enabled = models.isNotEmpty(), tag = "captions_make_button")
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.speech_on_device_note), style = FcType.BodySmall, color = Fc.Detail, modifier = pad)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        val l = look
        if (has && l != null && running == null) {
            HorizontalDivider(color = Fc.Divider, thickness = 1.dp)
            Spacer(Modifier.height(12.dp))
            SliderRow(stringResource(R.string.speech_label_size), "${(l.scale * 100).roundToInt()}%") {
                FcSlider(
                    fraction = ((l.scale - 0.5) / 1.5).toFloat(),
                    onChange = { f ->
                        val v = l.copy(scale = Math.round((0.5 + f * 1.5) * 20) / 20.0)
                        look = v
                        ed.setCaptionLook(v, merge = true)
                    },
                    snapTo = (0.5f / 1.5f),
                    description = stringResource(R.string.speech_caption_size_desc),
                    tag = "captions_size_slider",
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = FcSize.PanelPadding, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((p, nameRes) in listOf(CaptionPosition.TOP to R.string.speech_position_top, CaptionPosition.MIDDLE to R.string.speech_position_middle, CaptionPosition.BOTTOM to R.string.speech_position_bottom)) {
                    SelectChip(stringResource(nameRes), l.position == p, {
                        val v = l.copy(position = p)
                        look = v
                        ed.setCaptionLook(v, merge = false)
                    }, tag = "captions_position_${p.name.lowercase()}")
                }
                SelectChip(stringResource(R.string.speech_box), l.background, {
                    val v = l.copy(background = !l.background)
                    look = v
                    ed.setCaptionLook(v, merge = false)
                }, tag = "captions_box_chip")
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    asking?.let { m ->
        DownloadDialog(
            title = stringResource(R.string.speech_download_model_title),
            text = stringResource(R.string.speech_download_model_text, megabytes(m.missing.toLong())),
            onDismiss = { asking = null },
            onConfirm = {
                asking = null
                val (mid, lang) = model to language
                ModelDownloads.start(m) { android.os.Handler(android.os.Looper.getMainLooper()).post { ed.makeCaptions(mid, lang) } }
            },
        )
    }
}

/** A row of chips that scrolls sideways under the panel's edges rather than stopping at its padding. */
@Composable
private fun ChipRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = FcSize.PanelPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun ProgressBlock(stage: String, fraction: Float, button: String, tag: String, onClick: () -> Unit) {
    Text(stage, style = FcType.Label, color = Fc.Body)
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(Fc.SliderOff)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(CircleShape).background(Fc.Accent))
        }
        Spacer(Modifier.width(12.dp))
        Text("${(fraction * 100).roundToInt()}%", style = FcType.Value, color = Fc.Secondary)
    }
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(Modifier.testTag(tag)) { DialogButton(button, onClick, filled = false) }
    }
}

@Composable
private fun DownloadBlock(d: ModelDownloads.State) {
    if (d.error != null) {
        Text(d.error, style = FcType.Body, color = Fc.Body)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { DialogButton(stringResource(R.string.speech_ok), { ModelDownloads.dismiss() }, filled = true) }
    } else {
        ProgressBlock(stringResource(R.string.speech_downloading, d.name, megabytes(d.done), megabytes(d.total)), d.fraction, stringResource(R.string.speech_cancel), tag = "model_download_cancel_button") { ModelDownloads.cancel() }
    }
}

@Composable
private fun DownloadDialog(title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    FcDialog(title, stringResource(R.string.speech_download), onConfirm, onDismiss, dismiss = stringResource(R.string.speech_cancel)) {
        Text(text, style = FcType.Body, color = Fc.Body)
    }
}

/** Plays synthesized speech once (for hearing a voice before adding it). */
private class SpeechPlayer {
    private var track: AudioTrack? = null

    fun play(a: SpeechAudio) {
        stop()
        val samples = a.pcm.size / 4
        if (samples == 0) return
        val rate = a.sampleRate.toInt().coerceIn(8000, 192_000)
        val floats = FloatArray(samples)
        ByteBuffer.wrap(a.pcm).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
        val t = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(samples * 4)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        }.getOrNull() ?: return
        t.write(floats, 0, samples, AudioTrack.WRITE_BLOCKING)
        t.play()
        track = t
    }

    fun stop() {
        val t = track ?: return
        track = null
        runCatching { t.stop() }
        runCatching { t.release() }
    }
}

/** Text to speech: what to say, the voice, its pace and pitch; hear it, then add it. */
@Composable
fun SpeechPanel(ed: EditorController, panel: Panel.Speech, close: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember(panel) { mutableStateOf("") }
    var voice by remember(panel) { mutableStateOf(prefs(ctx).getString("voice", "basic-female") ?: "basic-female") }
    var pace by remember(panel) { mutableStateOf(1.0) }
    var pitch by remember(panel) { mutableStateOf(0) }
    var all by remember { mutableStateOf<List<VoiceInfo>>(emptyList()) }
    var pkg by remember { mutableStateOf<ModelPackage?>(null) }
    var asking by remember { mutableStateOf(false) }
    var hearing by remember { mutableStateOf<Job?>(null) }
    val player = remember { SpeechPlayer() }
    DisposableEffect(Unit) { onDispose { player.stop() } }
    val download = ModelDownloads.state
    LaunchedEffect(download == null) {
        all = withContext(Dispatchers.IO) { voices() }
        pkg = withContext(Dispatchers.IO) { voicePackage() }
    }
    LaunchedEffect(panel) {
        val c = panel.clip ?: return@LaunchedEffect
        ed.speechSettings(c)?.let {
            text = it.text
            voice = it.voice
            pace = it.pace
            pitch = it.pitch
        }
    }
    fun settings() = SpeechSettings(text.trim(), voice, pace, pitch)
    val usable = all.filter { it.installed }
    ToolPanel(stringResource(if (panel.clip == null) R.string.speech_panel_tts else R.string.speech_panel_edit_speech), close, modifier.imePadding(), tag = "speech_panel") {
        val pad = Modifier.padding(horizontal = FcSize.PanelPadding)
        Column(Modifier.fillMaxWidth()) {
            BasicTextField(
                value = text,
                onValueChange = { if (it.length <= 2000) text = it },
                textStyle = TextStyle(fontFamily = Inter, fontSize = 17.sp, color = Fc.Title),
                cursorBrush = SolidColor(Fc.Title),
                minLines = 3,
                maxLines = 5,
                modifier = pad
                    .fillMaxWidth()
                    .heightIn(min = 88.dp)
                    .border(1.dp, Fc.Divider, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .testTag("speech_text_field"),
                decorationBox = { inner ->
                    if (text.isEmpty()) Text(stringResource(R.string.speech_placeholder_type), style = FcType.Label.copy(fontSize = 17.sp), color = Fc.Detail)
                    inner()
                },
            )
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.speech_label_voice), style = FcType.Label, color = Fc.Secondary, modifier = pad)
            Spacer(Modifier.height(8.dp))
            ChipRow {
                for (v in usable) SelectChip(v.name, voice == v.id, { voice = v.id }, tag = "speech_voice_${v.id}")
                val p = pkg
                if (p != null && !p.installed && download == null) {
                    SelectChip(stringResource(R.string.speech_natural_voices, megabytes(p.missing.toLong())), false, { asking = true }, tag = "speech_get_natural_voices")
                }
            }
            if (download != null && download.id == pkg?.id) {
                Spacer(Modifier.height(12.dp))
                Column(pad) { DownloadBlock(download) }
            }
        }
        Spacer(Modifier.height(8.dp))
        SliderRow(stringResource(R.string.speech_label_pace), "%.2f×".format(pace)) {
            FcSlider(
                fraction = ((pace - 0.5) / 1.5).toFloat(),
                onChange = { f -> pace = Math.round((0.5 + f * 1.5) * 20) / 20.0 },
                snapTo = 0.5f / 1.5f,
                description = stringResource(R.string.speech_desc_pace),
                tag = "speech_pace_slider",
            )
        }
        SliderRow(stringResource(R.string.speech_label_pitch), listOf(
            stringResource(R.string.speech_pitch_lowest), stringResource(R.string.speech_pitch_low),
            stringResource(R.string.speech_pitch_normal), stringResource(R.string.speech_pitch_high),
            stringResource(R.string.speech_pitch_highest),
        )[pitch + 2]) {
            FcSlider(
                fraction = (pitch + 2) / 4f,
                onChange = { f -> pitch = (f * 4).roundToInt() - 2 },
                ticks = listOf(0.25f, 0.5f, 0.75f),
                description = stringResource(R.string.speech_desc_pitch),
                tag = "speech_pitch_slider",
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = FcSize.PanelPadding, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.testTag("speech_listen_button")) {
                DialogButton(if (hearing != null) stringResource(R.string.speech_making) else stringResource(R.string.speech_listen), {
                    if (text.isBlank() || hearing != null) return@DialogButton
                    hearing = scope.launch {
                        try {
                            ed.previewSpeech(settings())?.let { player.play(it) }
                        } finally {
                            hearing = null
                        }
                    }
                }, filled = false, enabled = text.isNotBlank())
            }
            PrimaryButton(stringResource(if (panel.clip == null) R.string.speech_add else R.string.speech_save), {
                prefs(ctx).edit().putString("voice", voice).apply()
                player.stop()
                ed.saySpeech(panel.clip, settings())
                close()
            }, modifier = Modifier.weight(1f), enabled = text.isNotBlank(), tag = "speech_add_button")
        }
    }
    val p = pkg
    if (asking && p != null) {
        DownloadDialog(
            title = stringResource(R.string.speech_download_voices_title),
            text = stringResource(R.string.speech_download_voices_text, megabytes(p.missing.toLong())),
            onDismiss = { asking = false },
            onConfirm = {
                asking = false
                ModelDownloads.start(p)
            },
        )
    }
}
