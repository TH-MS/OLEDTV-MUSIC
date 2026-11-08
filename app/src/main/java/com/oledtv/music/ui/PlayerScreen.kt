@file:OptIn(UnstableApi::class, ExperimentalTvMaterial3Api::class)

package com.oledtv.music.ui

import android.content.Context
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.oledtv.music.data.LyricLine
import com.oledtv.music.data.Lyrics
import com.oledtv.music.data.NavidromeRepository
import com.oledtv.music.data.SongDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = maybeAppendNextAlbum()

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

    fun play(repo: NavidromeRepository, albums: List<AlbumDto>, albumIndex: Int, songs: List<SongDto>, startIndex: Int) {
        this.repo = repo
        this.albums = albums
        loadedAlbumIndex = albumIndex
        appending = false
        retryCount = 0
        player.setMediaItems(toItems(repo, albums[albumIndex], songs), startIndex, 0L)
        player.prepare()
        player.play()
        maybeAppendNextAlbum()
    }

    private fun toItems(repo: NavidromeRepository, album: AlbumDto, songs: List<SongDto>): List<MediaItem> =
        songs.map { s ->
            MediaItem.Builder()
                .setMediaId(s.id)
                .setUri(repo.streamUrl(s.id))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(s.title)
                        .setArtist(s.artist ?: album.artist)
                        .setAlbumTitle(album.name)
                        .setExtras(Bundle().apply { putString(KEY_COVER, s.coverArt ?: album.coverArt) })
                        .build(),
                )
                .build()
        }

    private fun maybeAppendNextAlbum() {
        val r = repo ?: return
        if (appending || player.mediaItemCount == 0) return
        if (player.currentMediaItemIndex != player.mediaItemCount - 1) return
        appending = true
        scope.launch {
            try {
                var next = loadedAlbumIndex + 1
                while (next < albums.size) {
                    val songs = r.songsOf(albums[next].id)
                    if (songs.isNotEmpty()) {
                        val firstNewIndex = player.mediaItemCount
                        player.addMediaItems(toItems(r, albums[next], songs))
                        loadedAlbumIndex = next
                        if (player.playbackState == Player.STATE_ENDED) { // queue ran dry while we were fetching
                            player.seekTo(firstNewIndex, 0L); player.play()
                        }
                        break
                    }
                    next++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Network hiccup: allow another attempt on the next transition.
            } finally {
                appending = false
            }
        }
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

    val lyrics by produceState<Lyrics?>(initialValue = null, mediaId) {
        value = null
        value = if (mediaId.isEmpty()) Lyrics.EMPTY else try { repo.lyrics(mediaId, artist, title) } catch (e: CancellationException) { throw e } catch (e: Exception) { Lyrics.EMPTY }
    }

    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(50); playFocus.requestSafely() }

    Row(Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 48.dp, vertical = 30.dp)) {
        // ── Left ~45%
        Column(Modifier.weight(0.45f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            PixelShiftBox(shiftKey = mediaId) {
                CoverImage(
                    url = repo.coverArtUrl(coverId, 600),
                    cacheKey = "al-cover-${coverId.orEmpty()}",
                    sizePx = 600,
                    fallbackLetter = album,
                    modifier = Modifier.size(300.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(artist, fontSize = 20.sp, color = Color(0xFFCCCCCC), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(album, fontSize = 16.sp, color = Color(0xFF888888), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(16.dp))
            ProgressRow(position, duration)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ControlButton("Prev") { if (player.currentPosition > 3_000 || !player.hasPreviousMediaItem()) player.seekTo(0) else player.seekToPreviousMediaItem() }
                ControlButton(if (playWhenReady) "Pause" else "Play", Modifier.focusRequester(playFocus)) {
                    if (player.playbackState == Player.STATE_ENDED) { player.seekTo(0, 0L); player.play() }
                    else if (player.playWhenReady) player.pause() else player.play()
                }
                ControlButton("Next") { if (player.hasNextMediaItem()) player.seekToNextMediaItem() }
            }
        }
        Spacer(Modifier.width(32.dp))
        // ── Right ~55%
        LyricsPane(lyrics, position, duration, Modifier.weight(0.55f).fillMaxHeight())
    }
}

@Composable
private fun ControlButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusSurface(onClick, modifier) {
        Text(label, Modifier.padding(horizontal = 26.dp, vertical = 14.dp), fontSize = 20.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ProgressRow(position: State<Long>, duration: State<Long>) {
    val pos = position.value
    val dur = duration.value
    Column {
        Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0xFF2A2A2A), RoundedCornerShape(2.dp))) {
            val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Color.White, RoundedCornerShape(2.dp)))
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatMs(pos), fontSize = 14.sp, color = Color(0xFF888888))
            Text(formatMs(dur), fontSize = 14.sp, color = Color(0xFF888888))
        }
    }
}

// ───────────────────────── Lyric engine ─────────────────────────

private val LyricDim = Color(0xFF6E6E6E)

@Composable
private fun LyricsPane(lyrics: Lyrics?, position: State<Long>, duration: State<Long>, modifier: Modifier) {
    when {
        lyrics == null -> CenterNote("Loading lyrics…", modifier)
        lyrics.isEmpty -> CenterNote("No lyrics available", modifier)
        lyrics.synced.isNotEmpty() -> SyncedLyrics(lyrics.synced, position, modifier)
        else -> PlainLyrics(lyrics.plain.orEmpty(), position, duration, modifier)
    }
}

@Composable
private fun CenterNote(text: String, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) { Text(text, fontSize = 20.sp, color = LyricDim) }
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
                color = if (i == active) Color.White else LyricDim,
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
