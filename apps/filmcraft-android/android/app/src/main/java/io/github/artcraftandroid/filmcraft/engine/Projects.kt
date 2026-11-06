package io.github.artcraftandroid.filmcraft.engine

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import io.github.artcraftandroid.filmcraft.R
import org.json.JSONObject

/** One project on the Home screen. */
data class ProjectEntry(
    val id: String,
    val name: String,
    /** Last opened, ms since the epoch: Home lists the newest first. */
    val opened: Long,
    val created: Long,
    /** Length in ticks. */
    val duration: Long,
    val width: Int,
    val height: Int,
    val dir: File,
) {
    val projectFile: File get() = File(dir, PROJECT_FILE)
    val poster: File get() = File(dir, POSTER_FILE)
    val mediaDir: File get() = File(dir, MEDIA_DIR)

    companion object {
        const val PROJECT_FILE = "project.fcproj"
        const val POSTER_FILE = "poster.jpg"
        const val MEDIA_DIR = "media"
        const val META_FILE = "meta.json"
    }
}

/**
 * The projects in the app's storage: each a folder `projects/<id>/` holding the engine's project
 * file, `meta.json` (name and dates), a poster frame and a `media/` folder with copies of the
 * files it uses (so a project keeps working when the originals move).
 */
class Projects(private val context: Context) {
    private val root = File(context.filesDir, "projects").apply { mkdirs() }

    /** Every project, last opened first. */
    @Synchronized
    fun list(): List<ProjectEntry> =
        root.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { read(it) }.sortedByDescending { it.opened }

    fun find(id: String): ProjectEntry? = read(File(root, id))

    private fun read(dir: File): ProjectEntry? {
        val meta = File(dir, ProjectEntry.META_FILE)
        if (!meta.isFile) return null
        return runCatching {
            val j = JSONObject(meta.readText())
            ProjectEntry(
                id = dir.name,
                name = j.optString("name", context.getString(R.string.core_untitled_project)),
                opened = j.optLong("opened", 0L),
                created = j.optLong("created", 0L),
                duration = j.optLong("duration", 0L),
                width = j.optInt("width", 1080),
                height = j.optInt("height", 1920),
                dir = dir,
            )
        }.getOrNull()
    }

    @Synchronized
    fun write(p: ProjectEntry) {
        val j = JSONObject()
            .put("name", p.name)
            .put("opened", p.opened)
            .put("created", p.created)
            .put("duration", p.duration)
            .put("width", p.width)
            .put("height", p.height)
        val f = File(p.dir, ProjectEntry.META_FILE)
        val tmp = File(p.dir, ProjectEntry.META_FILE + ".tmp")
        tmp.writeText(j.toString())
        if (!tmp.renameTo(f)) {
            f.writeText(j.toString())
            tmp.delete()
        }
    }

    /** A new, empty project folder named [name] (made unique among the others). */
    @Synchronized
    fun create(name: String, width: Int, height: Int): ProjectEntry {
        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        File(dir, ProjectEntry.MEDIA_DIR).mkdirs()
        val now = System.currentTimeMillis()
        val p = ProjectEntry(id, uniqueName(name), now, now, 0L, width, height, dir)
        write(p)
        return p
    }

    /** Today's date in the medium style ("Oct 13, 2026"): what a new project is called. */
    fun todaysName(): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date())

    /** [base], or "[base] (1)", "[base] (2)"… whichever no project has. */
    fun uniqueName(base: String, except: String? = null): String {
        val taken = list().filter { it.id != except }.map { it.name }.toSet()
        if (base !in taken) return base
        var i = 1
        while ("$base ($i)" in taken) i++
        return "$base ($i)"
    }

    fun rename(p: ProjectEntry, name: String): ProjectEntry {
        val r = p.copy(name = name.trim())
        write(r)
        return r
    }

    /** A copy of [p] named "<name> copy", with its own media. */
    @Synchronized
    fun duplicate(p: ProjectEntry): ProjectEntry {
        val id = UUID.randomUUID().toString()
        val dir = File(root, id)
        p.dir.copyRecursively(dir, overwrite = true)
        // the project file names its media by absolute path: point it at the copies
        val file = File(dir, ProjectEntry.PROJECT_FILE)
        if (file.isFile) {
            val old = p.dir.absolutePath.replace("\\", "\\\\")
            val new = dir.absolutePath.replace("\\", "\\\\")
            file.writeText(file.readText().replace(old, new))
        }
        val now = System.currentTimeMillis()
        val r = p.copy(id = id, name = uniqueName(context.getString(R.string.core_project_copy, p.name)), opened = now, created = now, dir = dir)
        write(r)
        return r
    }

    @Synchronized
    fun delete(p: ProjectEntry) {
        p.dir.deleteRecursively()
    }

    fun touch(p: ProjectEntry): ProjectEntry {
        val r = p.copy(opened = System.currentTimeMillis())
        write(r)
        return r
    }

    fun savePoster(p: ProjectEntry, bitmap: Bitmap) {
        runCatching {
            val tmp = File(p.dir, ProjectEntry.POSTER_FILE + ".tmp")
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            tmp.renameTo(p.poster)
        }
    }

    /**
     * Copy a picked file into [p]'s media folder and return its path. Pictures are decoded (which
     * applies their orientation and reads HEIC), scaled down to at most [MAX_STILL] pixels on the
     * long side and stored as JPEG (PNG when transparent); videos and sounds are copied as they are.
     */
    fun importUri(p: ProjectEntry, uri: Uri): File {
        val resolver = context.contentResolver
        val name = displayName(resolver, uri)
        val type = resolver.getType(uri) ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: ""
        val dir = p.mediaDir.apply { mkdirs() }
        if (type.startsWith("image/")) {
            val bitmap = decodeStill(resolver, uri)
            val png = bitmap.hasAlpha()
            val out = uniqueFile(dir, safeName(name).substringBeforeLast('.') + if (png) ".png" else ".jpg")
            out.outputStream().use { bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, it) }
            bitmap.recycle()
            return out
        }
        val out = uniqueFile(dir, safeName(name))
        resolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
            ?: throw java.io.FileNotFoundException(context.getString(R.string.core_file_could_not_read))
        return out
    }

    private fun decodeStill(resolver: ContentResolver, uri: Uri): Bitmap {
        val src = ImageDecoder.createSource(resolver, uri)
        return ImageDecoder.decodeBitmap(src) { d, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val long = maxOf(w, h)
            if (long > MAX_STILL) {
                val k = MAX_STILL.toFloat() / long
                d.setTargetSize((w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1))
            }
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    companion object {
        /** The longest side a picture is kept at. */
        const val MAX_STILL = 4096

        fun displayName(resolver: ContentResolver, uri: Uri): String {
            val n = runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()
            return n ?: uri.lastPathSegment?.substringAfterLast('/') ?: "media"
        }

        fun safeName(name: String): String = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifBlank { "media" }.take(80)

        fun uniqueFile(dir: File, name: String): File {
            var f = File(dir, name)
            var i = 1
            val stem = name.substringBeforeLast('.')
            val ext = name.substringAfterLast('.', "")
            while (f.exists()) {
                f = File(dir, if (ext.isEmpty()) "$stem ($i)" else "$stem ($i).$ext")
                i++
            }
            return f
        }
    }
}
