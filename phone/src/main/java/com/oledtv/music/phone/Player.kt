@file:OptIn(UnstableApi::class)

package com.oledtv.music.phone

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.oledtv.music.data.AlbumDto
import com.oledtv.music.data.NavidromeRepository
import com.oledtv.music.data.SongDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** App-wide singleton that owns the one ExoPlayer; plays one album queue at a time. */
class PhonePlayer private constructor(private val ctx: Context) {

    companion object {
        const val KEY_COVER = "cover"
        const val KEY_TRACK_NO = "trackNo"
        const val KEY_TRACK_TOTAL = "trackTotal"
        const val KEY_DURATION_SEC = "durSec"
        @Volatile private var inst: PhonePlayer? = null
        fun get(c: Context): PhonePlayer =
            inst ?: synchronized(this) { inst ?: PhonePlayer(c.applicationContext).also { inst = it } }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var retryCount = 0
    val player: ExoPlayer

    /** Snapshot state the UI reads. */
    var item by mutableStateOf<MediaItem?>(null)
        private set
    var playWhenReady by mutableStateOf(false)
        private set
    var shuffled by mutableStateOf(false)
        private set
    var nowAlbumId by mutableStateOf<String?>(null)
        private set

    val hasQueue: Boolean get() = player.mediaItemCount > 0
    val nowSongId: String? get() = item?.mediaId

    init {
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)
        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, http))
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(10))
        player = ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(sources)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                item = p.currentMediaItem
                playWhenReady = p.playWhenReady
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) retryCount = 0
            }

            override fun onPlayerError(error: PlaybackException) {
                // I/O errors (network drop, timeouts): retry with back-off.
                if (error.errorCode in 2000..2999 && retryCount < 30) {
                    retryCount++
                    scope.launch {
                        delay((retryCount * 2_000L).coerceAtMost(10_000L))
                        player.prepare()
                        player.play()
                    }
                }
            }
        })
    }

    fun play(repo: NavidromeRepository, album: AlbumDto, songs: List<SongDto>, startIndex: Int) {
        val items = songs.mapIndexed { idx, s ->
            val cover = s.coverArt ?: album.coverArt
            MediaItem.Builder()
                .setMediaId(s.id)
                .setUri(repo.streamUrl(s.id))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(s.title)
                        .setArtist(s.artist?.takeIf { it.isNotBlank() } ?: album.artist)
                        .setAlbumTitle(album.name)
                        .setArtworkUri(repo.coverArtUrl(cover, 500)?.let { Uri.parse(it) })
                        .setExtras(Bundle().apply {
                            putString(KEY_COVER, cover)
                            putInt(KEY_TRACK_NO, idx + 1)
                            putInt(KEY_TRACK_TOTAL, songs.size)
                            putInt(KEY_DURATION_SEC, s.duration ?: 0)
                        })
                        .build(),
                )
                .build()
        }
        retryCount = 0
        player.setMediaItems(items, startIndex.coerceIn(0, items.lastIndex), 0L)
        player.prepare()
        player.play()
        shuffled = false
        nowAlbumId = album.id
        item = player.currentMediaItem
        try { ctx.startService(Intent(ctx, PlaybackService::class.java)) } catch (_: Exception) { }
    }

    fun togglePlay() {
        if (!hasQueue) return
        if (player.playbackState == Player.STATE_ENDED) { player.seekTo(0, 0L); player.play() }
        else if (player.playWhenReady) player.pause() else player.play()
    }

    fun next() { if (player.hasNextMediaItem()) player.seekToNextMediaItem() }

    /** Spotify / Apple behaviour: more than 3 s in (or first track) restarts the song, otherwise previous track. */
    fun previous() {
        if (player.currentPosition > 3_000 || !player.hasPreviousMediaItem()) player.seekTo(0)
        else player.seekToPreviousMediaItem()
    }

    /**
     * ON : shuffles only the tracks still to come (the current one keeps playing).
     * OFF: restores album order around the current track.
     */
    fun toggleShuffle() {
        val cur = player.currentMediaItemIndex
        val last = player.mediaItemCount - 1
        if (cur < 0) return
        fun orderOf(m: MediaItem) = m.mediaMetadata.extras?.getInt(KEY_TRACK_NO, 0) ?: 0
        if (!shuffled) {
            if (last <= cur) return
            val rest = (cur + 1..last).map { player.getMediaItemAt(it) }
            player.replaceMediaItems(cur + 1, last + 1, rest.shuffled())
        } else {
            val curOrder = orderOf(player.getMediaItemAt(cur))
            val others = (0..last).filter { it != cur }.map { player.getMediaItemAt(it) }
            val after = others.filter { orderOf(it) > curOrder }.sortedBy { orderOf(it) }
            val before = others.filter { orderOf(it) < curOrder }.sortedBy { orderOf(it) }
            if (last > cur || after.isNotEmpty()) player.replaceMediaItems(cur + 1, last + 1, after)
            if (cur > 0 || before.isNotEmpty()) player.replaceMediaItems(0, cur, before)
        }
        shuffled = !shuffled
    }
}

/** Foreground media service: keeps playing with the screen off, shows notification + lock-screen controls. */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, PhonePlayer.get(this).player).setSessionActivity(open).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        PhonePlayer.get(this).player.pause()
        stopSelf()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }
}
