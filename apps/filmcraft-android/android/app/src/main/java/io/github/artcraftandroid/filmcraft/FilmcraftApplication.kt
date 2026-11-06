package io.github.artcraftandroid.filmcraft

import android.app.Application
import android.content.ComponentCallbacks2
import android.system.Os
import io.github.artcraftandroid.filmcraft.core.MobileSession
import io.github.artcraftandroid.filmcraft.core.initLogging
import io.github.artcraftandroid.filmcraft.core.trimMemory
import io.github.artcraftandroid.filmcraft.engine.Engine
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class FilmcraftApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The engine, loaded once per process on a background thread and shared by the screens. */
    val engine: Deferred<Result<Engine>> by lazy {
        scope.async {
            runCatching {
                // Rust's temporary folder and home on Android are not the app's own: point them
                // at it before the library first reads them (it can't set them itself).
                val data = File(filesDir, "engine").apply { mkdirs() }
                Os.setenv("TMPDIR", cacheDir.absolutePath, true)
                Os.setenv("HOME", filesDir.absolutePath, true)
                Os.setenv("FILMCRAFT_DATA_DIR", data.absolutePath, true)
                initLogging()
                val session = MobileSession()
                session.start(data.absolutePath)
                engineLoaded = true
                Engine(session)
            }
        }
    }

    @Volatile
    private var engineLoaded = false

    override fun onCreate() {
        super.onCreate()
        io.github.artcraftandroid.filmcraft.engine.ModelDownloads.appContext = this
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            // the engine's caches refill as they are needed
            if (engineLoaded) scope.launch { runCatching { trimMemory() } }
            System.gc()
        }
    }
}
