package io.github.artcraftandroid.filmcraft.ui.editor

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.filmcraft.R
import io.github.artcraftandroid.filmcraft.ui.Fc
import io.github.artcraftandroid.filmcraft.ui.FcSize
import io.github.artcraftandroid.filmcraft.ui.FcType
import io.github.artcraftandroid.filmcraft.ui.GlyphIcon
import io.github.artcraftandroid.filmcraft.ui.Glyphs
import io.github.artcraftandroid.filmcraft.ui.LucideIcons
import io.github.artcraftandroid.filmcraft.ui.PanelAction
import io.github.artcraftandroid.filmcraft.ui.ToolPanel
import io.github.artcraftandroid.filmcraft.ui.glyph
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Records the microphone to a mono 16-bit WAV file, reporting the input level and the elapsed
 * time as it goes.
 */
class VoiceRecorder(val file: File) {
    @Volatile var level = 0f
        private set
    @Volatile var frames = 0L
        private set
    @Volatile private var running = false
    private var thread: Thread? = null
    val rate = 48_000

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val rec = runCatching { AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate / 5 * 2)) }.getOrNull() ?: return false
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        file.parentFile?.mkdirs()
        val raf = RandomAccessFile(file, "rw")
        raf.setLength(0)
        raf.write(ByteArray(44))
        running = true
        thread = Thread({
            val buf = ShortArray(2048)
            val bytes = ByteArray(buf.size * 2)
            runCatching { rec.startRecording() }
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                var peak = 0
                for (i in 0 until n) {
                    val v = buf[i].toInt()
                    if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v)
                    bytes[i * 2] = (v and 0xff).toByte()
                    bytes[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
                }
                level = peak / 32768f
                runCatching { raf.write(bytes, 0, n * 2) }
                frames += n
            }
            runCatching { rec.stop() }
            rec.release()
            writeHeader(raf, frames)
            runCatching { raf.close() }
        }, "filmcraft-voiceover").apply { start() }
        return true
    }

    fun stop() {
        running = false
        runCatching { thread?.join(2000) }
        thread = null
    }

    private fun writeHeader(f: RandomAccessFile, frames: Long) {
        val data = (frames * 2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray()).put("fmt ".toByteArray())
        h.putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(data)
        runCatching {
            f.seek(0)
            f.write(h.array())
        }
    }
}

/** Audio ▸ Voiceover: record from the microphone at the playhead. */
@Composable
fun VoiceoverPanel(ed: EditorController, close: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var countdown by remember { mutableStateOf(true) }
    var counting by remember { mutableIntStateOf(0) }
    var recorder by remember { mutableStateOf<VoiceRecorder?>(null) }
    var level by remember { mutableFloatStateOf(0f) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var job by remember { mutableStateOf<Job?>(null) }
    val takes = remember { mutableListOf<ULong>() }
    DisposableEffect(Unit) { onDispose { recorder?.stop() } }
    fun stop() {
        val r = recorder ?: return
        recorder = null
        job?.cancel()
        r.stop()
        ed.finishVoiceover(r) { id -> takes += id }
    }
    fun begin() {
        ed.requestMicrophone {
            job = scope.launch {
                if (countdown) {
                    for (i in 3 downTo 1) {
                        counting = i
                        delay(1000)
                    }
                    counting = 0
                }
                val r = ed.newVoiceRecorder() ?: return@launch
                if (!r.start()) {
                    ed.tellUser(ctx.getString(R.string.speech_mic_error))
                    return@launch
                }
                recorder = r
                while (true) {
                    level = r.level
                    elapsed = r.frames * TPS / r.rate
                    delay(50)
                }
            }
        }
    }
    ToolPanel(
        stringResource(R.string.speech_voiceover_title), { if (recorder == null) close() }, modifier, tag = "voiceover_panel",
        actions = {
            PanelAction(LucideIcons.Trash2.glyph(), stringResource(R.string.speech_discard_recording), {
                val ids = takes.toList()
                takes.clear()
                ed.discardTakes(ids)
            }, enabled = takes.isNotEmpty() && recorder == null)
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = FcSize.PanelPadding), horizontalAlignment = Alignment.CenterHorizontally) {
            // input level
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Fc.SliderOff)) {
                Box(Modifier.fillMaxWidth(level.coerceIn(0f, 1f)).height(6.dp).clip(CircleShape).background(if (level > 0.9f) Fc.Negative else Fc.SliderOn))
            }
            Spacer(Modifier.height(12.dp))
            Text(
                if (counting > 0) "$counting" else TimelineMath.clock(elapsed) + "." + (elapsed % TPS * 10 / TPS),
                style = FcType.PanelTitle.copy(fontSize = 34.sp),
                color = Fc.Title,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlyphIcon(LucideIcons.Timer.glyph(), Fc.Body, size = 20.dp)
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = countdown,
                        onCheckedChange = { countdown = it },
                        enabled = recorder == null && counting == 0,
                        colors = SwitchDefaults.colors(checkedTrackColor = Fc.Accent, checkedThumbColor = Color.White, uncheckedTrackColor = Fc.Chip, uncheckedThumbColor = Fc.Detail),
                        modifier = Modifier.semantics { contentDescription = ctx.getString(R.string.speech_countdown_desc) },
                    )
                }
                val recording = recorder != null
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Fc.Raised)
                        .clickable(role = Role.Button) {
                            when {
                                recording -> stop()
                                counting > 0 -> { job?.cancel(); counting = 0 }
                                else -> begin()
                            }
                        }
                        .testTag("voiceover_record")
                        .semantics { contentDescription = if (recording) ctx.getString(R.string.speech_stop_recording) else if (counting > 0) ctx.getString(R.string.speech_cancel_countdown) else ctx.getString(R.string.speech_record) },
                    contentAlignment = Alignment.Center,
                ) {
                    GlyphIcon(if (recording) Glyphs.Stop else Glyphs.Record, if (recording) Color.White else Fc.Recording, size = 40.dp)
                }
                Spacer(Modifier.width(72.dp))
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Suppress("unused")
private val shape = RoundedCornerShape(4.dp)
