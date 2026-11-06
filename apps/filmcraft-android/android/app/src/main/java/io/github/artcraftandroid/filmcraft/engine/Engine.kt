package io.github.artcraftandroid.filmcraft.engine

import android.graphics.Bitmap
import io.github.artcraftandroid.filmcraft.core.Frame
import io.github.artcraftandroid.filmcraft.core.MobileException
import io.github.artcraftandroid.filmcraft.core.MobileSession
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The FilmCraft engine (Rust: `apps/filmcraft-android`) as the app uses it: one [MobileSession]
 * and the threads its calls run on. Never call the session from the main thread.
 */
class Engine(val session: MobileSession) {
    /** Edits run one at a time, in the order the UI issued them. */
    val commands: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** The preview: one picture at a time, the newest wins. */
    val preview: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    /** Timeline filmstrips, waveforms and picker thumbnails. */
    val thumbnails: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(2)

    /** Run [block] on the session after the edits issued before it. */
    suspend fun <T> command(block: MobileSession.() -> T): T = withContext(commands) { session.block() }
}

/** A message for the user from anything the engine (or the system) threw. */
fun Throwable.userMessage(context: android.content.Context): String =
    (this as? MobileException)?.message?.takeIf { it.isNotBlank() }
        ?: message?.takeIf { it.isNotBlank() && !it.contains("Exception") }
        ?: context.getString(io.github.artcraftandroid.filmcraft.R.string.core_something_went_wrong)

/** A frame's pixels as a new bitmap. */
fun Frame.toBitmap(): Bitmap? {
    val w = width.toInt()
    val h = height.toInt()
    if (w <= 0 || h <= 0 || rgba.size < w * h * 4) return null
    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    // ARGB_8888 keeps its bytes in memory as R, G, B, A: the engine's order
    b.copyPixelsFromBuffer(ByteBuffer.wrap(rgba, 0, w * h * 4))
    return b
}

/** A frame's pixels into [into] when it has the same size (no new allocation), else a new bitmap. */
fun Frame.intoBitmap(into: Bitmap?): Bitmap? {
    val w = width.toInt()
    val h = height.toInt()
    if (w <= 0 || h <= 0 || rgba.size < w * h * 4) return null
    val b = if (into != null && !into.isRecycled && into.width == w && into.height == h) into else Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    b.copyPixelsFromBuffer(ByteBuffer.wrap(rgba, 0, w * h * 4))
    return b
}
