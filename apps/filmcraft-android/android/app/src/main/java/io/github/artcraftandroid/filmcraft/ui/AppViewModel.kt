package io.github.artcraftandroid.filmcraft.ui

import android.app.Application
import android.content.ContentValues
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.filmcraft.R
import io.github.artcraftandroid.filmcraft.FilmcraftApplication
import io.github.artcraftandroid.filmcraft.core.ExportQuality
import io.github.artcraftandroid.filmcraft.core.ExportSettings
import io.github.artcraftandroid.filmcraft.core.frameSizeFor
import io.github.artcraftandroid.filmcraft.core.trimMemory
import io.github.artcraftandroid.filmcraft.engine.Engine
import io.github.artcraftandroid.filmcraft.engine.ProjectEntry
import io.github.artcraftandroid.filmcraft.engine.Projects
import io.github.artcraftandroid.filmcraft.engine.userMessage
import io.github.artcraftandroid.filmcraft.ui.editor.EditorController
import io.github.artcraftandroid.filmcraft.ui.editor.EditorHost
import io.github.artcraftandroid.filmcraft.ui.editor.PickKind
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The screens. */
enum class Screen { Home, Editor, Export, Settings, About, Licences }

/** How a new project starts. */
enum class NewProject { PhotoLibrary, Files, Blank, ExtractAudio }

/** A request for the activity: show a picker and hand back what was chosen. */
data class PickRequest(val kind: PickKind, val onPicked: (List<Uri>) -> Unit)

/** An export's progress. */
sealed interface ExportPhase {
    data object Settings : ExportPhase
    data class Running(val progress: Float) : ExportPhase
    data class Done(val uri: Uri, val path: String) : ExportPhase
    data class Failed(val message: String) : ExportPhase
}

/**
 * All of the app's state: the screen, the projects on Home, the open editor and the export. The
 * activity drains the request channels (pickers, permissions, shares) and shows the messages.
 */
class AppViewModel(app: Application) : AndroidViewModel(app), EditorHost {
    var engine: Engine? by mutableStateOf(null)
        private set
    var engineProblem by mutableStateOf<String?>(null)
        private set
    val projects = Projects(app)

    var screen by mutableStateOf(Screen.Home)
        private set
    var projectList by mutableStateOf<List<ProjectEntry>>(emptyList())
        private set
    var editor by mutableStateOf<EditorController?>(null)
        private set
    var opening by mutableStateOf(false)
        private set

    // home dialogs
    var renaming by mutableStateOf<ProjectEntry?>(null)
    var deleting by mutableStateOf<ProjectEntry?>(null)

    // export
    var exportSettings by mutableStateOf(ExportSettings(1080u, 30u, ExportQuality.HIGH))
    var exportPhase by mutableStateOf<ExportPhase>(ExportPhase.Settings)
        private set
    var exportSheet by mutableStateOf(false)
    private var exportJob: Job? = null
    private var exportEngineJob: ULong? = null

    val messages = Channel<String>(Channel.BUFFERED)
    val pickRequests = Channel<PickRequest>(Channel.BUFFERED)
    val micRequests = Channel<() -> Unit>(Channel.BUFFERED)
    val shareRequests = Channel<Uri>(Channel.BUFFERED)

    val appVersion: String = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: ""

    init {
        viewModelScope.launch {
            getApplication<FilmcraftApplication>().engine.await()
                .onSuccess { engine = it }
                .onFailure { engineProblem = it.userMessage(appCtx) }
            refreshProjects()
        }
    }

    fun refreshProjects() {
        viewModelScope.launch { projectList = withContext(Dispatchers.IO) { projects.list() } }
    }

    // ---- EditorHost ----

    override fun tell(message: String) {
        messages.trySend(message)
    }

    override fun pick(kind: PickKind, onPicked: (List<Uri>) -> Unit) {
        pickRequests.trySend(PickRequest(kind, onPicked))
    }

    override fun close() = closeEditor()

    override fun export() {
        exportPhase = ExportPhase.Settings
        val t = editor?.timeline
        val short = t?.let { minOf(it.width, it.height).toInt() } ?: 1080
        exportSettings = ExportSettings(if (short == 720 || short == 1080 || short == 2160) short.toUInt() else 1080u, 30u, ExportQuality.HIGH)
        screen = Screen.Export
    }

    override fun requestMicrophone(onGranted: () -> Unit) {
        micRequests.trySend(onGranted)
    }

    // ---- navigation ----

    fun show(s: Screen) {
        screen = s
    }

    fun back() {
        screen = when (screen) {
            Screen.About -> Screen.Settings
            Screen.Licences -> Screen.About
            Screen.Settings -> Screen.Home
            Screen.Export -> if (editor != null) Screen.Editor else Screen.Home
            else -> Screen.Home
        }
    }

    /** Application context for resolving strings. */
    private val appCtx: Application get() = getApplication()

    // ---- projects ----

    /** Start a project the way a Home tile (or the + button) says. */
    fun newProject(how: NewProject) {
        val e = engine ?: return tell(engineProblem ?: appCtx.getString(R.string.core_editor_still_starting))
        when (how) {
            NewProject.Blank -> viewModelScope.launch {
                val (w, h) = 1080 to 1920
                val p = withContext(Dispatchers.IO) { projects.create(projects.todaysName(), w, h) }
                open(e, p, fresh = true)
            }
            NewProject.PhotoLibrary, NewProject.Files -> pick(if (how == NewProject.PhotoLibrary) PickKind.PhotosAndVideos else PickKind.FilesVisual) { uris ->
                if (uris.isEmpty()) return@pick
                viewModelScope.launch {
                    val (w, h) = withContext(Dispatchers.IO) { frameFor(uris.first()) }
                    val p = withContext(Dispatchers.IO) { projects.create(projects.todaysName(), w, h) }
                    open(e, p, fresh = true)
                    editor?.importAndAdd(uris, audio = false, first = true)
                }
            }
            NewProject.ExtractAudio -> pick(PickKind.ExtractAudioVideo) { uris ->
                val u = uris.firstOrNull() ?: return@pick
                viewModelScope.launch {
                    val p = withContext(Dispatchers.IO) { projects.create(projects.todaysName(), 1080, 1920) }
                    open(e, p, fresh = true)
                    editor?.importAndAdd(listOf(u), audio = true, first = true)
                }
            }
        }
    }

    /**
     * The frame for a project made from [uri]: the standard 1080-based size nearest the media's
     * shape (9:16, 2:3, 3:4, 4:5, 1:1, 5:4, 4:3, 16:9).
     */
    private fun frameFor(uri: Uri): Pair<Int, Int> {
        val ctx = getApplication<Application>()
        var w = 0
        var h = 0
        val type = ctx.contentResolver.getType(uri).orEmpty()
        runCatching {
            if (type.startsWith("image/")) {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                w = o.outWidth
                h = o.outHeight
                // pictures turned by their orientation tag swap sides
                ctx.contentResolver.openInputStream(uri)?.use {
                    val rot = androidx.exifinterface.media.ExifInterface(it).rotationDegrees
                    if (rot == 90 || rot == 270) w = h.also { h = w }
                }
            } else {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(ctx, uri)
                    w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (rot == 90 || rot == 270) w = h.also { h = w }
                }
            }
        }
        if (w <= 0 || h <= 0) return 1080 to 1920
        val shapes = listOf(9 to 16, 2 to 3, 3 to 4, 4 to 5, 1 to 1, 5 to 4, 4 to 3, 16 to 9)
        val ratio = w.toDouble() / h
        val (n, d) = shapes.minBy { (a, b) -> kotlin.math.abs(kotlin.math.ln(ratio / (a.toDouble() / b))) }
        val size = frameSizeFor(n.toUInt(), d.toUInt())
        return size[0].toInt() to size[1].toInt()
    }

    fun openProject(p: ProjectEntry) {
        val e = engine ?: return tell(engineProblem ?: appCtx.getString(R.string.core_editor_still_starting))
        viewModelScope.launch { open(e, p, fresh = false) }
    }

    private suspend fun open(e: Engine, p: ProjectEntry, fresh: Boolean) {
        opening = true
        try {
            val entry = withContext(Dispatchers.IO) { projects.touch(p) }
            val ed = EditorController(e, entry, projects, viewModelScope, this, appCtx)
            ed.load(fresh)
            editor = ed
            screen = Screen.Editor
        } catch (ex: Exception) {
            tell(ex.userMessage(appCtx))
        } finally {
            opening = false
        }
        refreshProjects()
    }

    fun closeEditor() {
        val ed = editor ?: run { screen = Screen.Home; return }
        screen = Screen.Home
        viewModelScope.launch {
            ed.close()
            editor = null
            refreshProjects()
        }
    }

    fun rename(p: ProjectEntry, name: String) {
        renaming = null
        viewModelScope.launch {
            withContext(Dispatchers.IO) { projects.rename(p, name) }
            refreshProjects()
        }
    }

    fun duplicate(p: ProjectEntry) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { projects.duplicate(p) } }.onFailure { tell(it.userMessage(appCtx)) }
            refreshProjects()
        }
    }

    fun delete(p: ProjectEntry) {
        deleting = null
        viewModelScope.launch {
            withContext(Dispatchers.IO) { projects.delete(p) }
            refreshProjects()
        }
    }

    // ---- export ----

    fun exportPlan() = runCatching { editor?.engine?.session?.exportPlan(exportSettings) }.getOrNull()

    fun startExport() {
        val ed = editor ?: return
        val plan = exportPlan()
        val ctx = getApplication<Application>()
        val dir = File(ctx.cacheDir, "export").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        if (plan != null && dir.usableSpace < plan.estimatedBytes.toLong() * 3 / 2 + 50L * 1024 * 1024) {
            tell(appCtx.getString(R.string.core_not_enough_space))
            return
        }
        // the project's name, with only the characters a file name can't hold replaced
        val name = ed.project.name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(100).ifBlank { "FilmCraft" }
        val out = File(dir, "$name.mp4")
        exportPhase = ExportPhase.Running(0f)
        exportJob = viewModelScope.launch {
            try {
                ed.save()
                val job = ed.engine.command { startExport(out.absolutePath, exportSettings) }
                exportEngineJob = job
                while (isActive) {
                    val st = ed.engine.command { exportStatus(job) }
                    if (st.finished) {
                        if (st.error != null) {
                            out.delete()
                            exportPhase = if (st.error == "cancelled") ExportPhase.Settings else ExportPhase.Failed(st.error ?: appCtx.getString(R.string.core_export_failed))
                        } else {
                            val uri = withContext(Dispatchers.IO) { saveToMovies(out, name) }
                            exportPhase = ExportPhase.Done(uri, out.absolutePath)
                        }
                        break
                    }
                    // keep the last stretch for saving to the gallery
                    exportPhase = ExportPhase.Running((st.progress * 0.95).toFloat())
                    delay(250)
                }
            } catch (e: Exception) {
                out.delete()
                exportPhase = ExportPhase.Failed(e.userMessage(appCtx))
            } finally {
                exportEngineJob = null
                // the export's frame buffers are kept for another export; give them back now
                viewModelScope.launch(Dispatchers.IO) { runCatching { trimMemory() } }
            }
        }
    }

    fun cancelExport() {
        val ed = editor
        val job = exportEngineJob
        if (ed != null && job != null) viewModelScope.launch { runCatching { ed.engine.command { cancelExport(job) } } }
        exportJob?.cancel()
        exportPhase = ExportPhase.Settings
    }

    /** Copy the finished video into the phone's Movies folder; returns its address there. */
    private fun saveToMovies(file: File, name: String): Uri {
        val resolver = getApplication<Application>().contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$name.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: throw java.io.IOException(appCtx.getString(R.string.core_video_could_not_save))
        try {
            resolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } } ?: throw java.io.IOException(appCtx.getString(R.string.core_video_could_not_save))
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    /** Files another app opened or shared with FilmCraft: into the open project, else a new one. */
    fun openShared(uris: List<Uri>) {
        val ed = editor
        if (ed != null && screen == Screen.Editor) {
            ed.importAndAdd(uris, audio = false)
            return
        }
        viewModelScope.launch {
            val e = getApplication<FilmcraftApplication>().engine.await().getOrNull() ?: return@launch tell(engineProblem ?: appCtx.getString(R.string.core_editor_could_not_start))
            val (w, h) = withContext(Dispatchers.IO) { frameFor(uris.first()) }
            val p = withContext(Dispatchers.IO) { projects.create(projects.todaysName(), w, h) }
            open(e, p, fresh = true)
            editor?.importAndAdd(uris, audio = false, first = true)
        }
    }

    fun share(uri: Uri) {
        shareRequests.trySend(uri)
    }

    fun finishExport() {
        exportPhase = ExportPhase.Settings
        screen = if (editor != null) Screen.Editor else Screen.Home
    }
}
