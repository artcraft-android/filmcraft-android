package io.github.artcraftandroid.filmcraft.ui.export

import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.res.stringResource
import io.github.artcraftandroid.filmcraft.R
import io.github.artcraftandroid.filmcraft.core.ExportQuality
import io.github.artcraftandroid.filmcraft.engine.toBitmap
import io.github.artcraftandroid.filmcraft.ui.AppViewModel
import io.github.artcraftandroid.filmcraft.ui.ExportPhase
import io.github.artcraftandroid.filmcraft.ui.Fc
import io.github.artcraftandroid.filmcraft.ui.FcIconButton
import io.github.artcraftandroid.filmcraft.ui.FcType
import io.github.artcraftandroid.filmcraft.ui.GlyphIcon
import io.github.artcraftandroid.filmcraft.ui.LucideIcons
import io.github.artcraftandroid.filmcraft.ui.PrimaryButton
import io.github.artcraftandroid.filmcraft.ui.SelectChip
import io.github.artcraftandroid.filmcraft.ui.ToolPanel
import io.github.artcraftandroid.filmcraft.ui.glyph
import kotlinx.coroutines.withContext

/** Export: the poster, settings and the button; then progress, then the finished video. */
@Composable
fun ExportScreen(vm: AppViewModel) {
    val ed = vm.editor ?: return
    val phase = vm.exportPhase
    BackHandler {
        when {
            vm.exportSheet -> vm.exportSheet = false
            phase is ExportPhase.Running -> vm.cancelExport()
            phase is ExportPhase.Done -> vm.finishExport()
            else -> vm.back()
        }
    }
    // the screen stays on while the video is made
    val view = LocalView.current
    DisposableEffect(phase is ExportPhase.Running) {
        view.keepScreenOn = phase is ExportPhase.Running
        onDispose { view.keepScreenOn = false }
    }
    val t = ed.timeline
    val aspect = if (t != null && t.height.toInt() > 0) t.width.toFloat() / t.height.toFloat() else 9f / 16f
    val poster by produceState<ImageBitmap?>(null, t?.revision) {
        value = withContext(ed.engine.thumbnails) { runCatching { ed.engine.session.renderer().frame(0, 720u, false, null).toBitmap()?.asImageBitmap() }.getOrNull() }
    }
    Box(Modifier.fillMaxSize().background(Fc.Layer1).testTag("screen_export")) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                FcIconButton(LucideIcons.X.glyph(), if (phase is ExportPhase.Running) stringResource(R.string.home_cancel_export) else stringResource(R.string.home_close), {
                    when (phase) {
                        is ExportPhase.Running -> vm.cancelExport()
                        is ExportPhase.Done -> vm.finishExport()
                        else -> vm.back()
                    }
                }, modifier = Modifier.testTag("export_close_button"))
                Text(
                    when (phase) {
                        is ExportPhase.Running -> stringResource(R.string.home_exporting)
                        is ExportPhase.Done -> stringResource(R.string.home_ready_to_share)
                        else -> stringResource(R.string.home_export)
                    },
                    style = FcType.PanelTitle,
                    color = Fc.Title,
                    modifier = Modifier.testTag("export_title"),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 4.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.aspectRatio(aspect, matchHeightConstraintsFirst = aspect < 1f).clip(RoundedCornerShape(12.dp)).background(Fc.Black)) {
                    if (phase is ExportPhase.Done) {
                        AndroidView(
                            factory = { ctx ->
                                VideoView(ctx).apply {
                                    setVideoPath(phase.path)
                                    setOnPreparedListener { it.isLooping = true; start() }
                                    setOnClickListener { if (isPlaying) pause() else start() }
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        poster?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (phase) {
                    ExportPhase.Settings -> {
                        Row(
                            Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp)).background(Fc.Raised).clickable(role = Role.Button) { vm.exportSheet = true }.padding(horizontal = 16.dp).testTag("export_settings_row"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.home_export_settings), style = FcType.Label, color = Fc.Body, modifier = Modifier.weight(1f))
                            GlyphIcon(LucideIcons.ChevronRight.glyph(), Fc.Body, size = 20.dp)
                        }
                        PrimaryButton(stringResource(R.string.home_export_video), { vm.startExport() }, tag = "export_button")
                    }
                    is ExportPhase.Running -> {
                        Text(stringResource(R.string.home_percent, Math.round(phase.progress * 100)), style = FcType.Header, color = Fc.Title, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        LinearProgressIndicator(progress = { phase.progress }, color = Fc.Accent, trackColor = Fc.SliderOff, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape))
                        Text(stringResource(R.string.home_keep_open), style = FcType.BodySmall, color = Fc.Detail, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                    is ExportPhase.Done -> {
                        Text(stringResource(R.string.home_saved_movies), style = FcType.BodySmall, color = Fc.Detail, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        PrimaryButton(stringResource(R.string.home_share), { vm.share(phase.uri) }, tag = "export_share_button")
                    }
                    is ExportPhase.Failed -> {
                        Text(phase.message, style = FcType.Body, color = Fc.Negative, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        PrimaryButton(stringResource(R.string.home_try_again), { vm.startExport() })
                    }
                }
            }
        }
        if (vm.exportSheet && phase == ExportPhase.Settings) {
            Box(Modifier.fillMaxSize().background(Fc.SheetScrim).clickable { vm.exportSheet = false }, contentAlignment = Alignment.BottomCenter) {
                Box(Modifier.clickable(enabled = false) {}) { SettingsSheet(vm) }
            }
        }
    }
}

@Composable
private fun SettingsSheet(vm: AppViewModel) {
    val s = vm.exportSettings
    val plan = vm.exportPlan()
    ToolPanel(stringResource(R.string.home_export_settings), { vm.exportSheet = false }, tag = "export_settings_sheet") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Text(stringResource(R.string.home_resolution), style = FcType.Label, color = Fc.Body)
            Spacer(Modifier.height(13.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, v) in listOf("720" to 720u, "1080" to 1080u, "4K" to 2160u)) {
                    SelectChip(label, s.shortSide == v, { vm.exportSettings = s.copy(shortSide = v) }, tag = "export_resolution_$label")
                }
            }
            Spacer(Modifier.height(39.dp))
            Text(stringResource(R.string.home_frame_rate), style = FcType.Label, color = Fc.Body)
            Spacer(Modifier.height(13.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (f in listOf(24u, 25u, 30u, 50u, 60u)) SelectChip("$f", s.fps == f, { vm.exportSettings = s.copy(fps = f) }, tag = "export_fps_$f")
            }
            Spacer(Modifier.height(39.dp))
            Text(stringResource(R.string.home_quality), style = FcType.Label, color = Fc.Body)
            Spacer(Modifier.height(13.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((labelRes, q, key) in listOf(Triple(R.string.home_quality_low, ExportQuality.LOW, "Low"), Triple(R.string.home_quality_medium, ExportQuality.MEDIUM, "Medium"), Triple(R.string.home_quality_high, ExportQuality.HIGH, "High"))) {
                    SelectChip(stringResource(labelRes), s.quality == q, { vm.exportSettings = s.copy(quality = q) }, tag = "export_quality_$key")
                }
            }
            Spacer(Modifier.height(27.dp))
            Text(
                stringResource(R.string.home_estimated_size, plan?.let { "%.2f MB".format(it.estimatedBytes.toDouble() / 1_000_000) } ?: "–"),
                style = FcType.Label,
                color = Fc.Body,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            )
        }
    }
}

@Suppress("unused")
private val w = Modifier.width(1.dp)
