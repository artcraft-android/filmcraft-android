package io.github.artcraftandroid.filmcraft

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import io.github.artcraftandroid.filmcraft.ui.AppRoot
import io.github.artcraftandroid.filmcraft.ui.AppViewModel
import io.github.artcraftandroid.filmcraft.ui.FilmcraftTheme
import io.github.artcraftandroid.filmcraft.ui.NewProject
import io.github.artcraftandroid.filmcraft.ui.PickRequest
import io.github.artcraftandroid.filmcraft.ui.Screen
import io.github.artcraftandroid.filmcraft.ui.editor.PickKind
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private var pending: PickRequest? = null
    private var micGranted: (() -> Unit)? = null

    private val pickVisual = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { deliver(it) }
    private val pickOneVisual = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { deliver(listOfNotNull(it)) }
    private val openDocuments = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { deliver(it) }
    private val mic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val run = micGranted
        micGranted = null
        if (ok) run?.invoke() else vm.tell(getString(R.string.misc_mic_permission_needed))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // always dark: light bar icons, the screens paint their own bands
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        window.isNavigationBarContrastEnforced = false
        setContent { FilmcraftTheme { AppRoot(vm) } }
        lifecycleScope.launch { for (r in vm.pickRequests) launchPicker(r) }
        lifecycleScope.launch { for (r in vm.micRequests) askMicrophone(r) }
        lifecycleScope.launch {
            for (uri in vm.shareRequests) {
                val send = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                launchSafely { startActivity(Intent.createChooser(send, null)) }
            }
        }
        if (savedInstanceState == null) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStop() {
        super.onStop()
        vm.editor?.stop()
        vm.editor?.let { ed -> lifecycleScope.launch { ed.save() } }
    }

    private fun launchSafely(block: () -> Unit) {
        try {
            block()
        } catch (_: ActivityNotFoundException) {
            vm.tell(getString(R.string.misc_no_app_for_action))
        } catch (_: SecurityException) {
            vm.tell(getString(R.string.misc_not_allowed))
        }
    }

    private fun launchPicker(r: PickRequest) {
        pending = r
        launchSafely {
            when (r.kind) {
                PickKind.PhotosAndVideos -> pickVisual.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                PickKind.ReplaceVisual -> pickOneVisual.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                PickKind.ExtractAudioVideo -> pickOneVisual.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                PickKind.FilesVisual -> openDocuments.launch(arrayOf("image/*", "video/*"))
                PickKind.FilesAudio -> openDocuments.launch(arrayOf("audio/*"))
            }
        }
    }

    private fun deliver(uris: List<Uri>) {
        val r = pending ?: return
        pending = null
        r.onPicked(uris)
    }

    private fun askMicrophone(onGranted: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            onGranted()
            return
        }
        micGranted = onGranted
        mic.launch(Manifest.permission.RECORD_AUDIO)
    }

    /** Videos and pictures other apps open with or share to FilmCraft: a new project from them. */
    private fun handle(intent: Intent?) {
        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        vm.openShared(uris)
    }

    @Suppress("unused")
    private fun home() = vm.show(Screen.Home)

    @Suppress("unused")
    private val unused = NewProject.Blank
}
