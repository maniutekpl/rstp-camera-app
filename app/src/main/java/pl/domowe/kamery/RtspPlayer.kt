// Video view that plays one RTSP stream with LibVLC, reports its status as text
// and reconnects by itself when the stream drops or freezes.
package pl.domowe.kamery

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

@Composable
fun RtspPlayer(
    url: String,
    onStatus: (String) -> Unit,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
    // Used instead of url when url never played (e.g. a preview stream disabled in the camera).
    fallbackUrl: String? = null,
) {
    val context = LocalContext.current
    val currentOnStatus by rememberUpdatedState(onStatus)
    val scope = rememberCoroutineScope()
    val session = remember {
        StreamSession(context, url, fallbackUrl, muted, scope) { currentOnStatus(it) }
    }

    AndroidView(
        factory = { ctx ->
            VLCVideoLayout(ctx).also { layout ->
                session.player.attachViews(layout, null, false, false)
                session.start()
            }
        },
        modifier = modifier,
    )

    DisposableEffect(Unit) {
        onDispose { session.release() }
    }
}

// Waits before each retry; after the last one it keeps retrying every 30 s.
private val RETRY_DELAYS_SECONDS = listOf(2, 5, 10, 30)

// No new video frame time for this long while playing means the stream froze.
private const val STALL_MS = 15_000L

// Owns one LibVLC player. All calls happen on the main thread
// (VLC events and the Compose coroutine scope both use it), so no locking is needed.
private class StreamSession(
    context: Context,
    url: String,
    private val fallbackUrl: String?,
    private val muted: Boolean,
    private val scope: CoroutineScope,
    private val onStatus: (String) -> Unit,
) {
    // --rtsp-tcp: many cameras drop video over UDP; TCP is more reliable in home Wi-Fi.
    private val libVlc = LibVLC(context, arrayListOf("--rtsp-tcp", "--network-caching=500"))
    val player = MediaPlayer(libVlc)

    private var currentUrl = url
    private var played = false
    private var retryJob: Job? = null
    private var failures = 0
    // When the playback time last moved; 0 = not seen yet, so the stall check stays off
    // for cameras that never report time.
    private var lastTimeChange = 0L

    private val watchdogJob = scope.launch {
        while (true) {
            delay(5_000)
            val last = lastTimeChange
            if (last != 0L && SystemClock.elapsedRealtime() - last > STALL_MS) {
                lastTimeChange = 0L
                scheduleReconnect()
            }
        }
    }

    init {
        player.setEventListener { event -> onEvent(event) }
    }

    fun start() {
        val media = Media(libVlc, Uri.parse(currentUrl))
        media.setHWDecoderEnabled(true, false)
        // Skips the audio track entirely, which also saves decoding work.
        if (muted) media.addOption(":no-audio")
        player.media = media
        media.release()
        player.play()
    }

    fun release() {
        retryJob?.cancel()
        watchdogJob.cancel()
        player.setEventListener(null)
        player.stop()
        player.detachViews()
        player.release()
        libVlc.release()
    }

    private fun onEvent(event: MediaPlayer.Event) {
        // While waiting for a retry, keep the countdown on screen.
        if (retryJob?.isActive == true) return
        when (event.type) {
            MediaPlayer.Event.Opening -> onStatus("Łączenie…")
            MediaPlayer.Event.Buffering ->
                onStatus(
                    if (event.buffering >= 100f) "Odtwarzanie"
                    else "Buforowanie… ${event.buffering.toInt()}%"
                )
            MediaPlayer.Event.Playing -> onStatus("Odtwarzanie")
            MediaPlayer.Event.TimeChanged -> {
                played = true
                failures = 0
                lastTimeChange = SystemClock.elapsedRealtime()
            }
            MediaPlayer.Event.EncounteredError,
            MediaPlayer.Event.EndReached -> scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (retryJob?.isActive == true) return
        // Two failures without a single frame: give up on this address and use the fallback.
        if (!played && fallbackUrl != null && currentUrl != fallbackUrl && failures >= 1) {
            currentUrl = fallbackUrl
            failures = 0
        }
        val waitSeconds = RETRY_DELAYS_SECONDS.getOrElse(failures) { RETRY_DELAYS_SECONDS.last() }
        failures++
        retryJob = scope.launch {
            player.stop()
            for (left in waitSeconds downTo 1) {
                onStatus("Brak połączenia, ponawiam za $left s…")
                delay(1_000)
            }
            onStatus("Łączenie…")
            start()
        }
    }
}
