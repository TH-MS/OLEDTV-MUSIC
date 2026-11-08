@file:OptIn(UnstableApi::class, ExperimentalTvMaterial3Api::class)

package com.oledtv.music.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.oledtv.music.data.AlbumDto
import com.oledtv.music.data.ArtistDto
import com.oledtv.music.data.LyricLine
import com.oledtv.music.data.Lyrics
import com.oledtv.music.data.NavidromeRepository
import com.oledtv.music.data.SongDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

// ───────────────────────── Playback manager (app-wide singleton) ─────────────────────────

/**
 * Owns the single ExoPlayer. Keeps playing while the user browses.
 * - Auto-advances through the album queue.
 * - When the last track of an album starts, the next album by the same Album Artist is appended (gapless).
 * - Network drops: load-error retry policy + manual re-prepare with back-off.
 */
class PlaybackManager private constructor(context: Context) {

    companion object {
        const val KEY_COVER = "cover"
        const val KEY_TRACK_NO = "trackNo"
        const val KEY_ALBUM_INDEX = "albumIndex"
        const val KEY_TRACK_TOTAL = "trackTotal"
        @Volatile private var instance: PlaybackManager? = null
        fun get(ctx: Context): PlaybackManager =
            instance ?: synchronized(this) { instance ?: PlaybackManager(ctx.applicationContext).also { instance = it } }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var repo: NavidromeRepository? = null
    private var albums: List<AlbumDto> = emptyList()
    private var loadedAlbumIndex = -1
    private var appending = false
    private var retryCount = 0

    val player: ExoPlayer

    init {
        // Small buffers: 1.5 GB RAM device. ~6 MB cap, time-based thresholds off so size cap wins.
        val loadControl = DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE))
            .setBufferDurationsMs(20_000, 40_000, 2_000, 4_000)
            .setTargetBufferBytes(6 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)

        val mediaSourceFactory = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(10)) // 10 retries with back-off

        player = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) = updateNowPlaying()
        })

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) retryCount = 0
            }

            override fun onPlayerError(error: PlaybackException) {
                // 2xxx = I/O errors (network down, timeouts, bad HTTP status). Retry with back-off.
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

    val hasQueue: Boolean get() = player.mediaItemCount > 0

    /** True while the remaining tracks of the current album are shuffled (UI highlights the button). */
    var shuffled by mutableStateOf(false)

    /** Ids of what is playing right now (null when nothing is playing) - used for the pulsating borders. */
    var nowSongId by mutableStateOf<String?>(null)
    var nowAlbumId by mutableStateOf<String?>(null)
    var nowArtistId by mutableStateOf<String?>(null)

    /** 0 = Fast, 1 = Medium, 2 = Slow: speed of the drifting cover. */
    var driftSpeed by mutableStateOf(0)

    private fun updateNowPlaying() {
        val item = player.currentMediaItem
        val active = item != null && player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE
        nowSongId = if (active) item?.mediaId else null
        nowAlbumId = if (active) albums.getOrNull(currentAlbumIndex())?.id else null
        nowArtistId = if (active) artist?.id else null
    }

    /** Album Artist whose albums are queued (used to rebuild navigation for "Now Playing"). */
    var artist: ArtistDto? = null
    val queueAlbums: List<AlbumDto> get() = albums
    /** Index (in [queueAlbums]) of the album the current track belongs to, or -1. */
    fun currentAlbumIndex(): Int = player.currentMediaItem?.mediaMetadata?.extras?.getInt(KEY_ALBUM_INDEX, -1) ?: -1

    fun play(repo: NavidromeRepository, albums: List<AlbumDto>, albumIndex: Int, songs: List<SongDto>, startIndex: Int) {
        this.repo = repo
        this.albums = albums
        loadedAlbumIndex = albumIndex
        appending = false
        retryCount = 0
        player.setMediaItems(toItems(repo, albums[albumIndex], songs, albumIndex), startIndex, 0L)
        player.prepare()
        player.play()
        shuffled = false
        updateNowPlaying()
    }

    private fun toItems(repo: NavidromeRepository, album: AlbumDto, songs: List<SongDto>, albumIdx: Int): List<MediaItem> =
        songs.mapIndexed { idx, s ->
            MediaItem.Builder()
                .setMediaId(s.id)
                .setUri(repo.streamUrl(s.id))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(s.title)
                        .setArtist(s.artist ?: album.artist)
                        .setAlbumTitle(album.name)
                        .setExtras(Bundle().apply {
                            putString(KEY_COVER, s.coverArt ?: album.coverArt)
                            putInt(KEY_TRACK_NO, idx + 1)
                            putInt(KEY_TRACK_TOTAL, songs.size)
                            putInt(KEY_ALBUM_INDEX, albumIdx)
                        })
                        .build(),
                )
                .build()
        }

    /**
     * Shuffle toggle for the album that is playing.
     * ON : shuffles only the tracks still to come (current track keeps playing).
     * OFF: restores album order around the current track, so Back/Forward continue from where you are
     *      (shuffled to track 10, then off -> Back = track 9, Forward = track 11).
     * Nothing else is queued, so the next album chosen plays in normal order.
     */
    fun toggleShuffleCurrentAlbum() {
        val cur = player.currentMediaItemIndex
        val albumIdx = currentAlbumIndex()
        if (cur < 0 || albumIdx < 0) return
        fun albumOf(i: Int) = player.getMediaItemAt(i).mediaMetadata.extras?.getInt(KEY_ALBUM_INDEX, -1) ?: -1
        fun orderOf(m: MediaItem) = m.mediaMetadata.extras?.getInt(KEY_TRACK_NO, 0) ?: 0
        var start = cur
        while (start > 0 && albumOf(start - 1) == albumIdx) start--
        var end = cur
        while (end + 1 < player.mediaItemCount && albumOf(end + 1) == albumIdx) end++

        if (!shuffled) {
            if (end <= cur) return
            val rest = (cur + 1..end).map { player.getMediaItemAt(it) }
            player.replaceMediaItems(cur + 1, end + 1, rest.shuffled())
        } else {
            val curOrder = orderOf(player.getMediaItemAt(cur))
            val others = (start..end).filter { it != cur }.map { player.getMediaItemAt(it) }
            val after = others.filter { orderOf(it) > curOrder }.sortedBy { orderOf(it) }
            val before = others.filter { orderOf(it) < curOrder }.sortedBy { orderOf(it) }
            if (end > cur || after.isNotEmpty()) player.replaceMediaItems(cur + 1, end + 1, after)
            if (cur > start || before.isNotEmpty()) player.replaceMediaItems(start, cur, before)
        }
        shuffled = !shuffled
        updateNowPlaying()
    }
}

// ───────────────────────── Burn-in protection ─────────────────────────

/** Shifts content 2–5 px on every [shiftKey] change (track change) and every 60 s of idle time. */
@Composable
fun PixelShiftBox(shiftKey: Any?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var dx by remember { mutableIntStateOf(0) }
    var dy by remember { mutableIntStateOf(0) }

    fun nextOffset(prev: Int): Int {
        var v: Int
        do { v = Random.nextInt(2, 6) * (if (Random.nextBoolean()) 1 else -1) } while (v == prev)
        return v
    }

    LaunchedEffect(shiftKey) { dx = nextOffset(dx); dy = nextOffset(dy) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            dx = nextOffset(dx); dy = nextOffset(dy)
        }
    }
    Box(modifier.graphicsLayer { translationX = dx.toFloat(); translationY = dy.toFloat() }) { content() }
}

// ───────────────────────── Player screen ─────────────────────────

private val OffWhite = Color(0xFFE6E6E6)

@Composable
fun PlayerScreen(repo: NavidromeRepository, manager: PlaybackManager) {
    val player = manager.player

    var item by remember { mutableStateOf(player.currentMediaItem) }
    var playWhenReady by remember { mutableStateOf(player.playWhenReady) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                item = p.currentMediaItem
                playWhenReady = p.playWhenReady
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }

    // Position/duration live in State objects that only leaf composables read -> no full-screen recomposition.
    val position = remember { mutableLongStateOf(0L) }
    val duration = remember { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        while (true) {
            position.longValue = player.currentPosition
            duration.longValue = player.duration.coerceAtLeast(0L)
            delay(250)
        }
    }

    val meta = item?.mediaMetadata
    val mediaId = item?.mediaId.orEmpty()
    val title = meta?.title?.toString().orEmpty()
    val artist = meta?.artist?.toString().orEmpty()
    val album = meta?.albumTitle?.toString().orEmpty()
    val coverId = meta?.extras?.getString(PlaybackManager.KEY_COVER)
    val trackNo = meta?.extras?.getInt(PlaybackManager.KEY_TRACK_NO, 0) ?: 0
    val trackTotal = meta?.extras?.getInt(PlaybackManager.KEY_TRACK_TOTAL, 0) ?: 0
    val trackLabel = if (trackNo > 0 && trackTotal > 0) "Track $trackNo of $trackTotal" else ""

    val lyrics by produceState<Lyrics?>(initialValue = null, mediaId) {
        value = null
        value = if (mediaId.isEmpty()) Lyrics.EMPTY else try { repo.lyrics(mediaId, artist, title) } catch (e: CancellationException) { throw e } catch (e: Exception) { Lyrics.EMPTY }
    }

    // OLED: the cover toggles the rest of the UI off/on. The cover itself always stays (and keeps pixel-shifting).
    var uiVisible by remember { mutableStateOf(true) }
    var noLyricsHidden by remember { mutableStateOf(false) }
    BackHandler(enabled = !uiVisible) { uiVisible = true }

    // Cover drift speed (click the cover to cycle) + a short label shown above the cover.
    val speedNames = remember { listOf("Fast", "Medium", "Slow") }
    val speedFactors = remember { floatArrayOf(1f, 0.5f, 0.25f) }
    var speedLabel by remember { mutableStateOf<String?>(null) }
    var speedTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(speedTick) {
        if (speedTick > 0) {
            speedLabel = speedNames[manager.driftSpeed]
            delay(1_500)
            speedLabel = null
        }
    }

    // Holding SELECT on any element for 5 s hides the whole UI (hold again, press a direction or Back to restore).
    val scope = rememberCoroutineScope()
    val hold = remember { object { var job: Job? = null; var fired = false } }
    val onRootKey: (KeyEvent) -> Boolean = { ev ->
        val isSelect = ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter
        val isDirection = ev.key == Key.DirectionUp || ev.key == Key.DirectionDown ||
            ev.key == Key.DirectionLeft || ev.key == Key.DirectionRight
        when {
            !uiVisible && isDirection && ev.type == KeyEventType.KeyDown -> { uiVisible = true; true }
            isSelect && ev.type == KeyEventType.KeyDown -> {
                if (ev.nativeKeyEvent.repeatCount == 0 && hold.job == null) {
                    hold.fired = false
                    hold.job = scope.launch {
                        delay(5_000)
                        hold.fired = true
                        uiVisible = !uiVisible
                    }
                }
                false
            }
            isSelect && ev.type == KeyEventType.KeyUp -> {
                hold.job?.cancel(); hold.job = null
                if (hold.fired) { hold.fired = false; true } else false // swallow the release that ends a long hold
            }
            else -> false
        }
    }

    val playFocus = remember { FocusRequester() }
    val coverFocus = remember { FocusRequester() }
    LaunchedEffect(uiVisible) {
        delay(50)
        if (uiVisible) playFocus.requestSafely() else coverFocus.requestSafely()
    }

    val togglePlay: () -> Unit = {
        if (player.playbackState == Player.STATE_ENDED) { player.seekTo(0, 0L); player.play() }
        else if (player.playWhenReady) player.pause() else player.play()
    }

    // The whole player shifts 2-5 px on track change and every 60 s (cover included, even when the UI is hidden).
    PixelShiftBox(shiftKey = mediaId, modifier = Modifier.fillMaxSize().background(Color.Black).onPreviewKeyEvent(onRootKey)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp)) {
            // ── Left ~45%
            Column(Modifier.weight(0.45f).fillMaxHeight()) {
                // Text, progress bar and buttons are measured first; the cover takes whatever height is left,
                // so long titles shrink the cover instead of pushing the controls off-screen (any 16:9 size).
                DriftingCover(Modifier.weight(1f).fillMaxWidth(), speedFactor = speedFactors[manager.driftSpeed], label = speedLabel) { coverSize ->
                    FocusSurface(
                        onClick = { manager.driftSpeed = (manager.driftSpeed + 1) % 3; speedTick++ },
                        modifier = Modifier.size(coverSize).focusRequester(coverFocus),
                        shape = RoundedCornerShape(8.dp),
                        containerColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        showFocus = uiVisible, // no static focus ring left on screen while the UI is hidden
                    ) {
                        CoverImage(
                            url = repo.coverArtUrl(coverId, 800),
                            cacheKey = "al-cover-${coverId.orEmpty()}",
                            sizePx = 800,
                            fallbackLetter = album,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                if (uiVisible) {
                    Spacer(Modifier.height(12.dp))
                    Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(artist, fontSize = 20.sp, color = Color(0xFFCCCCCC), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(album, fontSize = 16.sp, color = Color(0xFF888888), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(12.dp))
                    ProgressRow(
                        position = position,
                        duration = duration,
                        trackLabel = trackLabel,
                        onSeek = { player.seekTo(it) },
                        onTogglePlay = togglePlay,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        ControlButton(Glyph.Prev) {
                            if (player.currentPosition > 3_000 || !player.hasPreviousMediaItem()) player.seekTo(0) else player.seekToPreviousMediaItem()
                        }
                        ControlButton(if (playWhenReady) Glyph.Pause else Glyph.Play, Modifier.focusRequester(playFocus), onClick = togglePlay)
                        ControlButton(Glyph.Next) { if (player.hasNextMediaItem()) player.seekToNextMediaItem() }
                        ControlButton(Glyph.Shuffle, active = manager.shuffled) { manager.toggleShuffleCurrentAlbum() }
                    }
                }
            }
            if (uiVisible) {
                Spacer(Modifier.width(32.dp))
                // ── Right ~55%
                LyricsPane(
                    lyrics, position, duration,
                    noLyricsHidden = noLyricsHidden,
                    onToggleNoLyrics = { noLyricsHidden = !noLyricsHidden },
                    modifier = Modifier.weight(0.55f).fillMaxHeight(),
                )
            }
        }
    }
}

// Icons are drawn directly (explicit colours) so they can never end up invisible against the button background.
private enum class Glyph { Prev, Play, Pause, Next, Shuffle }

@Composable
private fun GlyphIcon(glyph: Glyph, modifier: Modifier = Modifier.size(30.dp), tint: Color = OffWhite) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val c = tint
        fun tri(x0: Float, x1: Float) = Path().apply {
            if (x1 >= x0) { moveTo(x0, 0f); lineTo(x1, h / 2f); lineTo(x0, h) }
            else { moveTo(x0, 0f); lineTo(x1, h / 2f); lineTo(x0, h) }
            close()
        }
        when (glyph) {
            Glyph.Play -> drawPath(tri(w * 0.15f, w * 0.95f), c)
            Glyph.Pause -> {
                drawRect(c, Offset(w * 0.12f, 0f), Size(w * 0.28f, h))
                drawRect(c, Offset(w * 0.60f, 0f), Size(w * 0.28f, h))
            }
            Glyph.Next -> {
                drawPath(tri(w * 0.05f, w * 0.72f), c)
                drawRect(c, Offset(w * 0.78f, 0f), Size(w * 0.17f, h))
            }
            Glyph.Shuffle -> {
                val sw = h * 0.13f
                fun arrow(yc: Float) = Path().apply {
                    moveTo(w * 0.70f, yc - h * 0.22f); lineTo(w, yc); lineTo(w * 0.70f, yc + h * 0.22f); close()
                }
                drawLine(c, Offset(0f, h * 0.22f), Offset(w * 0.74f, h * 0.78f), sw)
                drawLine(c, Offset(0f, h * 0.78f), Offset(w * 0.74f, h * 0.22f), sw)
                drawPath(arrow(h * 0.22f), c)
                drawPath(arrow(h * 0.78f), c)
            }
            Glyph.Prev -> {
                drawPath(tri(w * 0.95f, w * 0.28f), c)
                drawRect(c, Offset(w * 0.05f, 0f), Size(w * 0.17f, h))
            }
        }
    }
}

@Composable
private fun ControlButton(glyph: Glyph, modifier: Modifier = Modifier, active: Boolean = true, onClick: () -> Unit) {
    // Pure black background (OLED); focus is shown by the white border + slight scale. ~40% shorter than before.
    FocusSurface(
        onClick, modifier,
        containerColor = Color.Black,
        focusedContainerColor = Color(0xFF161616),
    ) {
        Box(Modifier.padding(horizontal = 30.dp, vertical = 6.dp)) {
            GlyphIcon(glyph, Modifier.size(18.dp), tint = if (active) OffWhite else Color(0xFF7A7A7A))
        }
    }
}

/**
 * OLED burn-in protection for the cover: it drifts slowly and continuously inside its window, bouncing off the
 * edges like a DVD logo. Position is integrated per frame (sub-pixel, draw-phase only) so it is smooth, and a
 * speed change only alters the velocity, never the position. [speedFactor] 1 = Fast, 0.5 = Medium, 0.25 = Slow.
 * [label] (e.g. "Medium") is shown above the cover and moves with it.
 */
@Composable
private fun DriftingCover(modifier: Modifier, speedFactor: Float, label: String?, content: @Composable (Dp) -> Unit) {
    BoxWithConstraints(modifier) {
        val labelH = 24.dp
        val cover = minOf(maxWidth, maxHeight - labelH) * 0.9f
        val density = LocalDensity.current.density
        val slackX = ((maxWidth - cover).value * density).coerceAtLeast(0f)
        val slackY = ((maxHeight - cover - labelH).value * density).coerceAtLeast(0f)
        val px = remember { mutableFloatStateOf(0f) }
        val py = remember { mutableFloatStateOf(0f) }
        val factor = rememberUpdatedState(speedFactor)

        LaunchedEffect(slackX, slackY) {
            var dirX = 1f
            var dirY = 1f
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val dt = ((now - last) / 1_000_000_000f).coerceAtMost(0.1f)
                last = now
                val step = factor.value * density * dt
                var x = px.floatValue + dirX * 4f * step      // 4 dp/s at Fast
                var y = py.floatValue + dirY * 3f * step      // 3 dp/s at Fast
                if (x > slackX) { x = slackX - (x - slackX); dirX = -1f } else if (x < 0f) { x = -x; dirX = 1f }
                if (y > slackY) { y = slackY - (y - slackY); dirY = -1f } else if (y < 0f) { y = -y; dirY = 1f }
                px.floatValue = x.coerceIn(0f, slackX)
                py.floatValue = y.coerceIn(0f, slackY)
            }
        }

        Column(
            Modifier
                .width(cover)
                .graphicsLayer { translationX = px.floatValue; translationY = py.floatValue },
        ) {
            Box(Modifier.height(labelH).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                if (label != null) Text(label, fontSize = 16.sp, color = OffWhite)
            }
            content(cover)
        }
    }
}

/**
 * Selectable scrubber (Apple TV / Spotify style):
 *  - focus the bar (D-pad DOWN from the title area); it thickens and shows a thumb
 *  - LEFT / RIGHT = -10 s / +10 s per press; holding the key accelerates (up to 60 s per step)
 *  - the seek is committed shortly after you stop pressing; the time label previews the target
 *  - CENTER = play / pause, UP/DOWN leave the bar as usual
 * "Track N of M" sits centred between the elapsed and total time.
 */
@Composable
private fun ProgressRow(
    position: State<Long>,
    duration: State<Long>,
    trackLabel: String,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var scrub by remember { mutableStateOf<Long?>(null) }
    var commitJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    val dur = duration.value
    val pos = scrub ?: position.value
    val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    val barHeight = if (focused) 8.dp else 4.dp

    Column(
        Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyUp && (ev.key == Key.DirectionCenter || ev.key == Key.Enter)) {
                    onTogglePlay(); return@onPreviewKeyEvent true
                }
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.DirectionLeft, Key.DirectionRight -> {
                        if (dur > 0) {
                            val dir = if (ev.key == Key.DirectionRight) 1 else -1
                            val repeat = ev.nativeKeyEvent.repeatCount
                            val step = if (repeat == 0) 10_000L else (10_000L * (1 + repeat / 4)).coerceAtMost(60_000L)
                            val target = ((scrub ?: position.value) + dir * step).coerceIn(0L, dur)
                            scrub = target
                            commitJob?.cancel()
                            commitJob = scope.launch {
                                delay(500)
                                onSeek(target)
                                delay(350)
                                scrub = null
                            }
                        }
                        true
                    }
                    Key.DirectionCenter, Key.Enter -> true
                    else -> false
                }
            }
            .border(2.dp, if (focused) OffWhite else Color.Transparent, RoundedCornerShape(10.dp))
            .focusable()
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(16.dp), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.fillMaxWidth().height(barHeight).background(Color(0xFF2A2A2A), RoundedCornerShape(4.dp)))
            Box(Modifier.fillMaxWidth(frac).height(barHeight).background(OffWhite, RoundedCornerShape(4.dp)))
            if (focused) {
                Box(Modifier.fillMaxWidth(frac), contentAlignment = Alignment.CenterEnd) {
                    Box(Modifier.size(16.dp).background(OffWhite, CircleShape))
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(formatMs(pos), Modifier.align(Alignment.CenterStart), fontSize = 14.sp, color = Color(0xFFAAAAAA))
            Text(trackLabel, Modifier.align(Alignment.Center), fontSize = 14.sp, color = Color(0xFFAAAAAA))
            Text(formatMs(dur), Modifier.align(Alignment.CenterEnd), fontSize = 14.sp, color = Color(0xFFAAAAAA))
        }
    }
}

// ───────────────────────── Lyric engine ─────────────────────────

private val LyricDim = Color(0xFF6E6E6E)

@Composable
private fun LyricsPane(
    lyrics: Lyrics?, position: State<Long>, duration: State<Long>,
    noLyricsHidden: Boolean, onToggleNoLyrics: () -> Unit, modifier: Modifier,
) {
    when {
        lyrics == null -> CenterNote("Loading lyrics…", modifier)
        lyrics.isEmpty -> NoLyricsBox(noLyricsHidden, onToggleNoLyrics, modifier)
        lyrics.synced.isNotEmpty() -> SyncedLyrics(lyrics.synced, position, modifier)
        else -> PlainLyrics(lyrics.plain.orEmpty(), position, duration, modifier)
    }
}

@Composable
private fun CenterNote(text: String, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) { Text(text, fontSize = 20.sp, color = LyricDim) }
}

/** Selectable "No lyrics available" box: click to hide the message (OLED), click again to bring it back. */
@Composable
private fun NoLyricsBox(hidden: Boolean, onToggle: () -> Unit, modifier: Modifier) {
    // Fully visible for 5 s, then fades linearly to nothing by 10 s (restarts for every track).
    val fade = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        fade.snapTo(1f)
        delay(5_000)
        fade.animateTo(0f, tween(5_000, easing = LinearEasing))
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        FocusSurface(
            onClick = onToggle,
            modifier = Modifier.graphicsLayer { alpha = fade.value },
            containerColor = Color.Transparent,
            focusedContainerColor = if (hidden) Color.Transparent else Color(0xFF1E1E1E),
            showFocus = !hidden,
        ) {
            Text(
                "No lyrics available",
                Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
                fontSize = 20.sp,
                color = if (hidden) Color.Transparent else LyricDim,
            )
        }
    }
}

/** Index of the last line whose timestamp <= position, or -1 before the first line. */
private fun activeIndex(lines: List<LyricLine>, posMs: Long): Int {
    var lo = 0; var hi = lines.lastIndex; var ans = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (lines[mid].timeMs <= posMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
    }
    return ans
}

@Composable
private fun SyncedLyrics(lines: List<LyricLine>, position: State<Long>, modifier: Modifier) {
    val listState = rememberLazyListState()
    // Reads `position` inside derivedStateOf: recomposes only when the active line actually changes.
    val active by remember(lines) { derivedStateOf { activeIndex(lines, position.value) } }

    LaunchedEffect(lines, active) {
        val vp = listState.layoutInfo.viewportSize.height
        if (active < 0) listState.animateScrollToItem(0)
        else listState.animateScrollToItem(active, -(vp / 3)) // keep active line ~1/3 from the top
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 220.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        itemsIndexed(lines) { i, line ->
            Text(
                line.text,
                fontSize = 28.sp,
                fontWeight = if (i == active) FontWeight.Bold else FontWeight.Normal,
                color = if (i == active) Color(0xFFF0F0F0) else LyricDim,
            )
        }
    }
}

/**
 * Unsynchronised lyrics: auto-scroll proportionally to playback progress;
 * focus the pane (D-pad RIGHT) and use UP/DOWN to scroll manually (auto-scroll pauses for 10 s).
 */
@Composable
private fun PlainLyrics(text: String, position: State<Long>, duration: State<Long>, modifier: Modifier) {
    val lines = remember(text) { text.lines() }
    val listState: LazyListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var manualUntil by remember { mutableLongStateOf(0L) }
    var focused by remember { mutableStateOf(false) }

    LaunchedEffect(lines) {
        while (true) {
            delay(400)
            if (System.currentTimeMillis() < manualUntil) continue
            val dur = duration.value
            if (dur <= 0 || lines.size < 2) continue
            val target = (position.value.toFloat() / dur).coerceIn(0f, 1f) * (lines.size - 1)
            val idx = target.toInt()
            val rem = target - idx
            val h = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == idx }?.size ?: 48
            listState.scrollToItem(idx, (rem * h).toInt())
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val delta = when (ev.key) { Key.DirectionDown -> 260f; Key.DirectionUp -> -260f; else -> return@onPreviewKeyEvent false }
                manualUntil = System.currentTimeMillis() + 10_000
                scope.launch { listState.animateScrollBy(delta) }
                true
            }
            .onFocusChanged { focused = it.isFocused }
            .border(2.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(12.dp))
            .focusable(),
        contentPadding = PaddingValues(vertical = 40.dp, horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(lines) { _, line ->
            Text(line.ifBlank { " " }, fontSize = 24.sp, color = Color(0xFFDDDDDD))
        }
    }
}
