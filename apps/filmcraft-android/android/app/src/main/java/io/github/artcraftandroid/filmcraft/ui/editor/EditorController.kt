package io.github.artcraftandroid.filmcraft.ui.editor

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.artcraftandroid.filmcraft.R
import io.github.artcraftandroid.filmcraft.core.CaptionInfo
import io.github.artcraftandroid.filmcraft.core.CaptionLook
import io.github.artcraftandroid.filmcraft.core.CaptionStatus
import io.github.artcraftandroid.filmcraft.core.ClipInfo
import io.github.artcraftandroid.filmcraft.core.ClipKind
import io.github.artcraftandroid.filmcraft.core.LaneKind
import io.github.artcraftandroid.filmcraft.core.MobileSession
import io.github.artcraftandroid.filmcraft.core.Renderer
import io.github.artcraftandroid.filmcraft.core.SessionStatus
import io.github.artcraftandroid.filmcraft.core.SpeechAudio
import io.github.artcraftandroid.filmcraft.core.SpeechSettings
import io.github.artcraftandroid.filmcraft.core.TextStyle
import io.github.artcraftandroid.filmcraft.core.TimelineInfo
import io.github.artcraftandroid.filmcraft.core.TransitionInfo
import io.github.artcraftandroid.filmcraft.engine.Engine
import io.github.artcraftandroid.filmcraft.engine.ProjectEntry
import io.github.artcraftandroid.filmcraft.engine.Projects
import io.github.artcraftandroid.filmcraft.engine.intoBitmap
import io.github.artcraftandroid.filmcraft.engine.userMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A tool's panel. */
sealed interface Panel {
    data object Speed : Panel
    data object Volume : Panel
    data object Fade : Panel
    data object Opacity : Panel
    data object Filters : Panel
    data object Effects : Panel
    /** The transition between [from] and [to] (either empty at the ends). */
    data class Transitions(val from: ULong?, val to: ULong?) : Panel
    /** Typing text: a new clip ([clip] empty), editing one, or a caption's words ([caption]). */
    data class TextInput(val clip: ULong?, val caption: ULong? = null) : Panel
    data object TextStyle : Panel
    data object AspectRatio : Panel
    data object Voiceover : Panel
    /** Captions: make them, and their look. */
    data object Captions : Panel
    /** Text to speech: new speech ([clip] empty) or saying a speech clip again. */
    data class Speech(val clip: ULong?) : Panel
}

/** The tool bar's submenus. */
enum class Submenu { None, Retime, ClipAudio, AddAudio, Files }

/** What the editor needs from the rest of the app. */
interface EditorHost {
    fun tell(message: String)
    /** Ask the user for media: [kind] picks the picker; [onPicked] gets what they chose. */
    fun pick(kind: PickKind, onPicked: (List<Uri>) -> Unit)
    fun close()
    fun export()
    /** Ask for the microphone; [onGranted] runs once it may be used. */
    fun requestMicrophone(onGranted: () -> Unit)
}

enum class PickKind { PhotosAndVideos, FilesVisual, FilesAudio, ReplaceVisual, ExtractAudioVideo }

/**
 * The open project in the editor: the timeline as the engine last reported it, the playhead,
 * the picture at it, playback, the selection and the open tool. Every edit goes through [edit],
 * which runs it on the engine's command thread, re-reads the timeline and saves the project.
 */
@Stable
class EditorController(
    val engine: Engine,
    project: ProjectEntry,
    private val projects: Projects,
    private val scope: CoroutineScope,
    private val host: EditorHost,
    private val context: android.content.Context,
) {
    private val session: MobileSession get() = engine.session

    var project by mutableStateOf(project)
        private set
    var timeline by mutableStateOf<TimelineInfo?>(null)
        private set
    var status by mutableStateOf<SessionStatus?>(null)
        private set
    var picture by mutableStateOf<ImageBitmap?>(null)
        private set
    var playhead by mutableLongStateOf(0L)
        private set
    var playing by mutableStateOf(false)
        private set
    var panel by mutableStateOf<Panel?>(null)
    var submenu by mutableStateOf(Submenu.None)
    /** Timeline zoom; 0 until the timeline knows its width. */
    var pxPerSecond by mutableFloatStateOf(0f)
    /** The player's height (px) when the user has resized it, else 0 (55 % of the room). */
    var playerHeight by mutableFloatStateOf(0f)
    /** The player's height (px) last laid out without a panel: kept while one is open. */
    var basePlayerPx = 0f
    /** The side-by-side layout's player width fraction. */
    var sideFraction by mutableFloatStateOf(0f)
    /** Busy with something the user waits for (importing media). */
    var busy by mutableStateOf<String?>(null)
        private set
    /** Text being typed for a new text clip (shown on the preview until it is added). */
    var typing by mutableStateOf("")
    /** A clip is being dragged or trimmed: tools wait. */
    var gesture by mutableStateOf(false)

    val media = MediaCache(engine, scope)
    private var renderer: Renderer? = null
    private val player = Player()
    private var playJob: Job? = null
    private var saveJob: Job? = null
    private var lastSaved = -1L

    /** The preview's longest side in pixels (set by the preview from its size). */
    var previewSide = 720
        set(v) {
            field = v.coerceIn(160, 1280)
        }

    private data class FrameRequest(val time: Long, val draft: Boolean, val seq: Long)
    private val frameRequests = MutableStateFlow<FrameRequest?>(null)
    private var requestSeq = 0L
    private val bitmaps = arrayOfNulls<Bitmap>(2)
    private var nextBitmap = 0

    init {
        scope.launch { renderFrames() }
    }

    // ---- reading ----

    val rateNum: Long get() = timeline?.rateNum ?: 30L
    val rateDen: Long get() = timeline?.rateDen ?: 1L
    val duration: Long get() = timeline?.duration ?: 0L
    val frameTicks: Long get() = TimelineMath.tickOfFrame(1, rateNum, rateDen)

    val selectedClip: ClipInfo?
        get() {
            val id = timeline?.selected ?: return null
            return timeline?.lanes?.firstNotNullOfOrNull { l -> l.clips.firstOrNull { it.id == id } }
        }

    /** The selected caption (picked here, not in the engine: captions aren't clips). */
    var selectedCaption by mutableStateOf<ULong?>(null)

    val selectedCaptionInfo: CaptionInfo?
        get() {
            val id = selectedCaption ?: return null
            return timeline?.captions?.firstOrNull { it.id == id }
        }

    val selectedTransition: TransitionInfo?
        get() {
            val id = timeline?.selectedTransition ?: return null
            return timeline?.transitions?.firstOrNull { it.id == id }
        }

    fun laneOf(clip: ULong): io.github.artcraftandroid.filmcraft.core.LaneInfo? = timeline?.lanes?.firstOrNull { l -> l.clips.any { it.id == clip } }

    fun isMain(clip: ULong): Boolean = laneOf(clip)?.kind == LaneKind.MAIN

    val mainClips: List<ClipInfo> get() = timeline?.lanes?.firstOrNull { it.kind == LaneKind.MAIN }?.clips.orEmpty()

    // ---- loading and saving ----

    /** Open the project: load or create its engine project, then show it. */
    suspend fun load(fresh: Boolean) {
        val t = engine.command {
            if (fresh || !project.projectFile.isFile) newProject(project.name, project.width.toUInt(), project.height.toUInt(), 30u)
            else openProject(project.projectFile.absolutePath)
        }
        timeline = t
        playhead = t.playhead
        refreshStatus()
        newRenderer()
        if (fresh) save()
        lastSaved = t.revision.toLong()
        requestFrame(playhead, draft = false)
    }

    private suspend fun refreshStatus() {
        status = engine.command { poll() }
    }

    private suspend fun newRenderer() {
        val r = runCatching { engine.command { renderer() } }.getOrNull() ?: return
        renderer = r
        media.renderer = r
    }

    /** Re-read the timeline after an edit; a new revision gets a new renderer and a save. */
    suspend fun refresh() {
        val t = runCatching { engine.command { timeline() } }.getOrNull() ?: return
        val changed = t.revision != timeline?.revision
        timeline = t
        // an edit that shortens the video past the playhead brings it back to the end
        playhead = if (t.playhead > t.duration) engine.command { setPlayhead(t.duration) } else t.playhead
        refreshStatus()
        if (changed) {
            newRenderer()
            scheduleSave()
        }
        requestFrame(playhead, draft = false)
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400)
            save()
        }
    }

    /** Save the project file and its listing now. */
    suspend fun save() {
        val t = timeline ?: return
        if (t.revision.toLong() == lastSaved) return
        val path = project.projectFile.absolutePath
        runCatching { engine.command { saveProject(path) } }.onFailure { host.tell(it.userMessage(context)) }.onSuccess {
            lastSaved = t.revision.toLong()
            val p = project.copy(duration = t.duration, width = t.width.toInt(), height = t.height.toInt(), opened = System.currentTimeMillis())
            project = p
            withContext(Dispatchers.IO) { projects.write(p) }
            val now = System.currentTimeMillis()
            if (now - lastPoster > 20_000 || !p.poster.isFile) {
                lastPoster = now
                savePoster()
            }
        }
    }

    private var lastPoster = 0L

    /** The first frame, small, for the project's card on Home. */
    private suspend fun savePoster() {
        val r = renderer ?: return
        val bmp = withContext(engine.thumbnails) { runCatching { r.frame(0, 360u, false, null).intoBitmap(null) }.getOrNull() } ?: return
        withContext(Dispatchers.IO) { projects.savePoster(project, bmp) }
    }

    /** Save and make the poster for Home (the first frame). */
    suspend fun close() {
        stop()
        captionJob?.let { job -> runCatching { engine.command { cancelCaptions(job) } } }
        saveJob?.cancel()
        save()
        savePoster()
        media.release()
        // the natural voices' model and export buffers aren't needed on Home
        withContext(Dispatchers.IO) { runCatching { io.github.artcraftandroid.filmcraft.core.trimMemory() } }
    }

    // ---- editing ----

    /**
     * Run an edit on the engine's command thread; then show the result. Playback stops first.
     * Errors become a message.
     */
    fun edit(block: MobileSession.() -> Unit) {
        stop()
        scope.launch {
            try {
                engine.command { block() }
            } catch (e: Exception) {
                host.tell(e.userMessage(context))
            }
            refresh()
        }
    }

    /** An edit that runs in the background without stopping playback or saving (slider drags). */
    fun quickEdit(block: MobileSession.() -> Unit) {
        scope.launch {
            try {
                engine.command { block() }
            } catch (e: Exception) {
                host.tell(e.userMessage(context))
            }
            refresh()
        }
    }

    fun undo() = edit { undo() }
    fun redo() = edit { redo() }

    fun select(clip: ULong?) {
        submenu = Submenu.None
        if (panel !is Panel.TextInput) panel = null
        selectedCaption = null
        quickEdit { select(clip) }
    }

    fun selectCaption(id: ULong) {
        submenu = Submenu.None
        if (panel !is Panel.TextInput && panel != Panel.Captions) panel = null
        selectedCaption = id
        if (timeline?.selected != null || timeline?.selectedTransition != null) quickEdit { select(null) }
    }

    fun selectTransition(t: TransitionInfo) {
        submenu = Submenu.None
        quickEdit { selectTransition(t.id) }
    }

    // ---- playhead and playback ----

    /** Move the playhead (the timeline scrolls under it); [draft] while the finger moves. */
    fun seek(t: Long, draft: Boolean = true) {
        if (playing) stop()
        val c = t.coerceIn(0L, duration.coerceAtLeast(0L))
        playhead = c
        requestFrame(c, draft)
        scope.launch { engine.command { setPlayhead(c) } }
    }

    /** The finger left the timeline: draw the frame at full quality. */
    fun settle() {
        requestFrame(playhead, draft = false)
    }

    fun step(frames: Int) {
        val f = TimelineMath.frames(playhead, rateNum, rateDen) + frames
        seek(TimelineMath.tickOfFrame(f.coerceAtLeast(0), rateNum, rateDen), draft = false)
    }

    fun togglePlay() = if (playing) stop() else play()

    fun play() {
        val end = duration
        if (end <= 0) return
        if (playhead >= end - frameTicks) playhead = 0
        playRange(playhead, end, back = null)
    }

    /** Play from [from] to [to], then stop there, or go back to [back] if given. */
    private fun playRange(from: Long, to: Long, back: Long?) {
        val r = renderer ?: return
        stop()
        val end = to.coerceIn(0L, duration)
        val start = from.coerceIn(0L, end)
        if (end - start < frameTicks) return
        playhead = start
        playing = true
        player.start(r, start, end)
        // the UI thread's dispatcher brings the display's frame clock (the view model's scope has none)
        playJob = scope.launch(androidx.compose.ui.platform.AndroidUiDispatcher.Main) {
            while (isActive && playing) {
                withFrameNanos { }
                val t = player.position().coerceAtMost(end)
                playhead = t
                requestFrame(t, draft = true)
                if (t >= end) break
            }
            if (playing) {
                playing = false
                player.stop()
                if (back != null) playhead = back.coerceIn(0L, duration)
                engine.command { setPlayhead(playhead) }
                requestFrame(playhead, draft = false)
            }
        }
    }

    /** Play the transition between [from] and [to], start to end; the playhead stays at its end. */
    private fun previewTransition(from: ULong?, to: ULong?) {
        val tr = timeline?.transitions?.firstOrNull { it.from == from && it.to == to } ?: return
        playRange(tr.start, tr.start + tr.duration, back = null)
    }

    fun stop() {
        if (!playing) return
        playing = false
        playJob?.cancel()
        playJob = null
        player.stop()
        val t = playhead
        scope.launch {
            engine.command { setPlayhead(t) }
            requestFrame(t, draft = false)
        }
    }

    private fun requestFrame(t: Long, draft: Boolean) {
        frameRequests.value = FrameRequest(t, draft, ++requestSeq)
    }

    private suspend fun renderFrames() {
        frameRequests.filterNotNull().collect { req ->
            val r = renderer ?: return@collect
            val side = if (req.draft && playing) (previewSide * 3 / 4).coerceAtLeast(160) else previewSide
            val frame = withContext(engine.preview) { runCatching { r.frame(req.time, side.toUInt(), req.draft, null) }.getOrNull() } ?: return@collect
            val bmp = frame.intoBitmap(bitmaps[nextBitmap]) ?: return@collect
            bitmaps[nextBitmap] = bmp
            nextBitmap = 1 - nextBitmap
            picture = bmp.asImageBitmap()
            // a draft that is still the newest wish is redrawn properly once things settle
            if (req.draft && !playing && frameRequests.value?.seq == req.seq) {
                delay(120)
                if (frameRequests.value?.seq == req.seq && !playing) requestFrame(req.time, draft = false)
            }
        }
    }

    // ---- adding media ----

    /** Add pictures and videos from the phone's gallery, files, or sounds from files. */
    fun addMedia(kind: PickKind) {
        submenu = Submenu.None
        host.pick(kind) { uris -> if (uris.isNotEmpty()) importAndAdd(uris, audio = kind == PickKind.FilesAudio) }
    }

    /** Add picked media; [first] is a new project's starting media, which can't be undone away. */
    fun importAndAdd(uris: List<Uri>, audio: Boolean, first: Boolean = false) {
        stop()
        scope.launch {
            busy = context.getString(R.string.core_adding_media)
            try {
                val paths = withContext(Dispatchers.IO) { uris.mapNotNull { u -> runCatching { projects.importUri(project, u).absolutePath }.getOrNull() } }
                if (paths.isEmpty()) {
                    host.tell(context.getString(R.string.core_file_cannot_add))
                } else {
                    engine.command {
                        if (audio) addAudio(paths) else addMedia(paths)
                        if (first) forgetHistory()
                    }
                    if (paths.size < uris.size) host.tell(context.getString(R.string.core_some_files_cannot_add))
                }
            } catch (e: Exception) {
                host.tell(e.userMessage(context))
            } finally {
                busy = null
            }
            refresh()
        }
    }

    fun replaceSelected() {
        val c = selectedClip ?: return
        host.pick(PickKind.ReplaceVisual) { uris ->
            val u = uris.firstOrNull() ?: return@pick
            scope.launch {
                busy = context.getString(R.string.core_replacing)
                try {
                    val path = withContext(Dispatchers.IO) { projects.importUri(project, u).absolutePath }
                    engine.command { replace(c.id, path) }
                } catch (e: Exception) {
                    host.tell(e.userMessage(context))
                } finally {
                    busy = null
                }
                refresh()
            }
        }
    }

    /** Extract the sound of a video the user picks, onto an audio lane at the playhead. */
    fun extractAudioFromFile() {
        submenu = Submenu.None
        host.pick(PickKind.ExtractAudioVideo) { uris -> if (uris.isNotEmpty()) importAndAdd(uris, audio = true) }
    }

    // ---- clip tools ----

    private inline fun withSelected(block: (ClipInfo) -> Unit) {
        selectedClip?.let(block)
    }

    fun split() = withSelected { c -> edit { split(c.id) } }
    fun delete() = withSelected { c -> panel = null; submenu = Submenu.None; edit { deleteClip(c.id) } }
    fun duplicate() = withSelected { c -> edit { duplicate(c.id) } }
    fun freeze() = withSelected { c -> submenu = Submenu.None; edit { freeze(c.id) } }
    fun extractAudio() = withSelected { c -> submenu = Submenu.None; edit { extractAudio(c.id) } }
    fun toggleMirror() = withSelected { c -> edit { setFlip(c.id, true, !c.mirrored) } }
    fun toggleFlip() = withSelected { c -> edit { setFlip(c.id, false, !c.flipped) } }
    fun addBlank() = edit { addBlank() }

    fun setSpeed(speed: Double, done: Boolean) = withSelected { c ->
        if (done) edit { setSpeed(c.id, speed) }
    }

    fun setVolume(v: Double) = withSelected { c -> quickEdit { setVolume(c.id, v, true) } }
    fun setMuted(m: Boolean) = withSelected { c -> edit { setMuted(c.id, m) } }
    fun setFades(fadeIn: Long, fadeOut: Long) = withSelected { c -> edit { setFades(c.id, fadeIn, fadeOut) } }
    fun setOpacity(v: Double, all: Boolean = false) = withSelected { c -> quickEdit { setOpacity(c.id, v, !all, all) } }
    fun setFilter(id: String, amount: Double, merge: Boolean, all: Boolean = false) = withSelected { c -> quickEdit { setFilter(c.id, id, amount, merge, all) } }
    fun setEffect(id: String, amount: Double, merge: Boolean, all: Boolean = false) = withSelected { c -> quickEdit { setEffect(c.id, id, amount, merge, all) } }

    /** Put a transition on a cut (none for a blank [preset]) and play it. */
    fun setTransition(from: ULong?, to: ULong?, preset: String, seconds: Double, all: Boolean = false) {
        stop()
        scope.launch {
            try {
                engine.command { setTransition(from, to, preset, seconds, all) }
            } catch (e: Exception) {
                host.tell(e.userMessage(context))
            }
            refresh()
            if (preset.isNotEmpty()) previewTransition(from, to)
        }
    }

    fun setTransitionDuration(id: ULong, seconds: Double) {
        stop()
        scope.launch {
            try {
                engine.command { setTransitionDuration(id, seconds) }
            } catch (e: Exception) {
                host.tell(e.userMessage(context))
            }
            refresh()
            val tr = timeline?.transitions?.firstOrNull { it.id == id } ?: return@launch
            previewTransition(tr.from, tr.to)
        }
    }

    suspend fun maxTransition(from: ULong?, to: ULong?): Double = runCatching { engine.command { maxTransition(from, to) } }.getOrDefault(5.0)

    fun trim(clip: ULong, head: Boolean, delta: Long) = edit { trim(clip, head, delta) }
    fun reorder(clip: ULong, index: Int) = edit { reorder(clip, index.toUInt()) }
    fun move(clip: ULong, lane: Int, start: Long) = edit { moveClip(clip, lane, start) }

    fun setAspect(num: Int, den: Int) = edit { setAspect(num.toUInt(), den.toUInt()) }

    // ---- text ----

    fun addText(text: String) = edit { addText(text) }
    fun setText(clip: ULong, text: String) = edit { setText(clip, text) }

    suspend fun textStyle(clip: ULong): TextStyle? = runCatching { engine.command { textStyle(clip) } }.getOrNull()

    fun setTextStyle(clip: ULong, style: TextStyle, merge: Boolean, all: Boolean = false) = quickEdit { setTextStyle(clip, style, merge, all) }

    // ---- speech ----

    /** Speech of [st] to hear before adding it (null when it can't be made; the user is told). */
    suspend fun previewSpeech(st: SpeechSettings): SpeechAudio? =
        runCatching { engine.command { previewSpeech(st) } }.onFailure { host.tell(it.userMessage(context)) }.getOrNull()

    suspend fun speechSettings(clip: ULong): SpeechSettings? = runCatching { engine.command { speechSettings(clip) } }.getOrNull()

    /** Add speech at the playhead, or say speech clip [clip] again. */
    fun saySpeech(clip: ULong?, st: SpeechSettings) {
        stop()
        scope.launch {
            busy = context.getString(R.string.core_making_speech)
            try {
                val dir = project.mediaDir.apply { mkdirs() }.absolutePath
                engine.command { if (clip == null) addSpeech(st, dir) else editSpeech(clip, st, dir) }
            } catch (e: Exception) {
                host.tell(e.userMessage(context))
            } finally {
                busy = null
            }
            refresh()
        }
    }

    // ---- captions ----

    /** Captions being made: how far along (null when nothing is running). */
    var captionProgress by mutableStateOf<CaptionStatus?>(null)
        private set
    private var captionJob: ULong? = null

    /** Make captions from what is said, with speech model [model] in [language] ("auto" detects it). */
    fun makeCaptions(model: String, language: String) {
        if (captionProgress != null) return
        stop()
        captionProgress = CaptionStatus(0.0, context.getString(R.string.core_getting_ready), false, null, 0u)
        scope.launch {
            try {
                val job = engine.command { startCaptions(model, language) }
                captionJob = job
                while (true) {
                    val st = engine.command { captionStatus(job) }
                    captionProgress = st
                    if (st.finished) {
                        when {
                            st.error == "cancelled" -> {}
                            st.error != null -> host.tell(st.error ?: "")
                            st.captions == 0u -> host.tell(context.getString(R.string.core_no_speech_heard))
                        }
                        break
                    }
                    delay(300)
                }
            } catch (e: Exception) {
                host.tell(e.userMessage(context))
            } finally {
                captionJob = null
                captionProgress = null
            }
            refresh()
        }
    }

    fun cancelCaptions() {
        val job = captionJob ?: return
        scope.launch { runCatching { engine.command { cancelCaptions(job) } } }
    }

    suspend fun captionLook(): CaptionLook? = runCatching { engine.command { captionLook() } }.getOrNull()

    fun setCaptionLook(look: CaptionLook, merge: Boolean) = quickEdit { setCaptionLook(look, merge) }

    fun setCaptionText(id: ULong, text: String) = edit { setCaptionText(id, text) }

    fun deleteCaption(id: ULong) {
        selectedCaption = null
        edit { deleteCaption(id) }
    }

    fun deleteCaptions() {
        selectedCaption = null
        edit { deleteCaptions() }
    }

    // ---- placement on the frame ----

    suspend fun placementOf(clip: ULong): io.github.artcraftandroid.filmcraft.core.Placement? =
        runCatching { engine.command { placement(clip) } }.getOrNull()

    /** Move/scale/turn a clip while a finger does it: one undo step per gesture. */
    fun setPlacement(clip: ULong, p: io.github.artcraftandroid.filmcraft.core.Placement, merge: Boolean) {
        placementJob = scope.launch {
            runCatching { engine.command { setPlacement(clip, p.x, p.y, p.scale, p.rotation, merge) } }
            refreshSoon()
        }
    }

    private var placementJob: Job? = null
    private var refreshJob: Job? = null

    /** Re-read the timeline once things calm down (a gesture sends many edits). */
    private fun refreshSoon() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            delay(60)
            refresh()
        }
    }

    // ---- voice-over ----

    private var voiceStart = 0L

    fun requestMicrophone(onGranted: () -> Unit) = host.requestMicrophone(onGranted)

    fun tellUser(message: String) = host.tell(message)

    /** A recorder for a new take at the playhead, writing "Voiceover N.wav" into the project. */
    fun newVoiceRecorder(): VoiceRecorder? {
        stop()
        voiceStart = playhead
        val dir = project.mediaDir.apply { mkdirs() }
        var n = 1
        while (java.io.File(dir, "Voiceover $n.wav").exists()) n++
        return VoiceRecorder(java.io.File(dir, "Voiceover $n.wav"))
    }

    /** Put a finished take on an audio lane where it started. */
    fun finishVoiceover(r: VoiceRecorder, onAdded: (ULong) -> Unit) {
        val path = r.file.absolutePath
        val at = voiceStart
        scope.launch {
            try {
                if (r.frames < r.rate / 10) {
                    java.io.File(path).delete()
                } else {
                    val ids = engine.command {
                        setPlayhead(at)
                        addAudio(listOf(path))
                    }
                    ids.firstOrNull()?.let(onAdded)
                }
            } catch (e: Exception) {
                host.tell(e.userMessage(context))
            }
            refresh()
        }
    }

    fun discardTakes(ids: List<ULong>) = edit { ids.forEach { runCatching { deleteClip(it) } } }

    fun closeEditor() = host.close()

    // ---- export ----

    fun export() {
        stop()
        val d = duration
        if (d < frameTicks * 5) {
            host.tell(context.getString(R.string.core_export_needs_frames))
            return
        }
        host.export()
    }

    fun clipKindHasPicture(c: ClipInfo) = c.kind != ClipKind.AUDIO && c.kind != ClipKind.TEXT
}
