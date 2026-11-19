package com.oledtv.music.phone

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oledtv.music.data.LyricLine
import com.oledtv.music.data.Lyrics
import com.oledtv.music.data.LyricsLang
import com.oledtv.music.data.NavidromeRepository
import com.oledtv.music.data.OnlineLyrics
import com.oledtv.music.data.OnlineResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

private enum class Online { Idle, Searching, NotFound, Failed }

@Composable
fun NowPlayingScreen(repo: NavidromeRepository, pp: PhonePlayer, onBack: () -> Unit) {
    val player = pp.player
    val item = pp.item
    if (item == null) { LaunchedEffect(Unit) { onBack() }; return }
    val md = item.mediaMetadata
    val mediaId = item.mediaId
    val title = md.title?.toString().orEmpty()
    val artist = md.artist?.toString().orEmpty()
    val album = md.albumTitle?.toString().orEmpty()
    val coverId = md.extras?.getString(PhonePlayer.KEY_COVER)
    val trackNo = md.extras?.getInt(PhonePlayer.KEY_TRACK_NO, 0) ?: 0
    val trackTotal = md.extras?.getInt(PhonePlayer.KEY_TRACK_TOTAL, 0) ?: 0

    val position = remember { mutableLongStateOf(0L) }
    val duration = remember { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        while (true) {
            position.longValue = player.currentPosition
            duration.longValue = player.duration.coerceAtLeast(0L)
            delay(250)
        }
    }

    // ── Lyrics: server first (instant); if not time-synced an online search quietly swaps in synced lyrics. ──
    val appContext = LocalContext.current.applicationContext
    var lyricsHidden by remember { mutableStateOf(false) }  // sticks between songs until "Show Lyrics"
    var showLyrics by remember { mutableStateOf(false) }    // tapping the cover flips to the lyrics
    var online by remember(mediaId) { mutableStateOf(Online.Idle) }
    var refreshTick by remember(mediaId) { mutableIntStateOf(0) }
    var refreshing by remember(mediaId) { mutableStateOf(false) }
    var refreshNote by remember(mediaId) { mutableStateOf<String?>(null) }
    var swapped by remember(mediaId) { mutableStateOf<Lyrics?>(null) }
    val rejected = remember(mediaId) { HashSet<Int>() }
    val fetched by produceState<Lyrics?>(initialValue = null, mediaId, lyricsHidden) {
        value = null
        online = Online.Idle
        if (lyricsHidden) return@produceState
        if (mediaId.isEmpty()) { value = Lyrics.EMPTY; return@produceState }
        val base = try { repo.lyrics(mediaId, artist, title) } catch (e: CancellationException) { throw e } catch (e: Exception) { Lyrics.EMPTY }
        value = base
        val baseEnglishOk = base.synced.isNotEmpty() && !LyricsLang.clearlyNonEnglish(base.synced.joinToString("\n") { it.text })
        if (baseEnglishOk) return@produceState
        online = Online.Searching
        var dur = (md.extras?.getInt(PhonePlayer.KEY_DURATION_SEC, 0) ?: 0) * 1000L
        if (dur <= 0) dur = withTimeoutOrNull(4_000) {
            var d = 0L
            while (d <= 0) {
                if (player.currentMediaItem?.mediaId == mediaId) d = player.duration.coerceAtLeast(0L)
                if (d <= 0) delay(200)
            }
            d
        } ?: 0L
        when (val r = OnlineLyrics.get(appContext).find(artist, title, album, dur)) {
            is OnlineResult.Found -> {
                val baseEnglish = base.plain?.let { LyricsLang.looksEnglish(it) } == true
                val baseSyncedNonEnglish = base.synced.isNotEmpty()
                if (r.english || (!baseEnglish && !baseSyncedNonEnglish)) {
                    value = Lyrics(synced = r.lines, plain = base.plain); online = Online.Idle
                } else online = Online.Idle
            }
            OnlineResult.NotFound -> online = if (base.isEmpty) Online.NotFound else Online.Idle
            OnlineResult.Failed -> online = if (base.isEmpty) Online.Failed else Online.Idle
        }
    }
    val lyrics = swapped ?: fetched
    LaunchedEffect(refreshTick) {
        if (refreshTick == 0) return@LaunchedEffect
        val prev = lyrics
        prev?.synced?.takeIf { it.isNotEmpty() }?.let { rejected += OnlineLyrics.get(appContext).signature(it) }
        refreshing = true
        var dur = (md.extras?.getInt(PhonePlayer.KEY_DURATION_SEC, 0) ?: 0) * 1000L
        if (dur <= 0) dur = player.duration.coerceAtLeast(0L)
        when (val r = OnlineLyrics.get(appContext).refresh(artist, title, album, dur, rejected)) {
            is OnlineResult.Found -> {
                rejected += OnlineLyrics.get(appContext).signature(r.lines)
                swapped = Lyrics(synced = r.lines, plain = prev?.plain)
            }
            OnlineResult.NotFound -> refreshNote = "No other lyrics found"
            OnlineResult.Failed -> refreshNote = "Server offline / unreachable"
        }
        refreshing = false
        if (refreshNote != null) { delay(3_000); refreshNote = null }
    }

    val message = when {
        lyricsHidden -> "Lyrics hidden"
        refreshing -> "Searching for lyrics…"
        lyrics == null -> "Loading lyrics…"
        online == Online.Searching -> "Searching for lyrics…"
        online == Online.Failed -> "Server offline / unreachable"
        else -> "No lyrics found"
    }
    val lyricStart = lyrics?.synced?.firstOrNull()?.timeMs?.takeIf { it >= 15_000L }

    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // top bar
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable { onBack() }, contentAlignment = Alignment.Center) {
                Glyph(GlyphKind.Back, OffWhite, Modifier.size(22.dp))
            }
            Text("Now Playing", Modifier.weight(1f), color = Dim, fontSize = 15.sp, textAlign = TextAlign.Center)
            Box {
                Box(Modifier.size(44.dp).clip(CircleShape).clickable { menu = true }, contentAlignment = Alignment.Center) {
                    Glyph(GlyphKind.More, OffWhite, Modifier.size(22.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(if (lyricsHidden) "Show Lyrics" else "Hide Lyrics") },
                        onClick = { menu = false; lyricsHidden = !lyricsHidden })
                    DropdownMenuItem(text = { Text("Refresh lyrics") },
                        enabled = !lyricsHidden && !refreshing && lyrics != null,
                        onClick = { menu = false; refreshTick++ })
                    DropdownMenuItem(text = { Text(if (showLyrics) "Show cover" else "Show lyrics view") },
                        onClick = { menu = false; showLyrics = !showLyrics })
                }
            }
        }

        // cover <-> lyrics (tap the cover to show lyrics)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
            val side = if (maxWidth < maxHeight) maxWidth else maxHeight
            if (!showLyrics) {
                Cover(repo.coverArtUrl(coverId, 800), album, Modifier.size(side).clickable { showLyrics = true }, 10.dp)
            } else {
                Box(Modifier.fillMaxSize()) {
                    LyricsPanel(lyrics, message, position, { ms -> player.seekTo(ms); if (!player.playWhenReady) player.play() }, Modifier.fillMaxSize())
                    refreshNote?.let {
                        Text(it, Modifier.align(Alignment.TopCenter).background(Color(0xFF0B0B0B), androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 6.dp),
                            fontSize = 14.sp, color = Dim)
                    }
                    // small cover button: back to the cover
                    Cover(repo.coverArtUrl(coverId, 120), album, Modifier.align(Alignment.TopEnd).size(44.dp).clickable { showLyrics = false }, 8.dp)
                }
            }
        }

        // titles
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(title, color = OffWhite, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(artist, color = Color(0xFFCCCCCC), fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(album, color = Dim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (trackNo > 0 && trackTotal > 0) Text("Track $trackNo of $trackTotal", color = Dim, fontSize = 12.sp)
        }

        Box(Modifier.padding(horizontal = 24.dp)) {
            SeekBar(position, duration, lyricStart) { ms -> player.seekTo(ms) }
        }

        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            RoundButton(52, { pp.toggleShuffle() }) { Glyph(GlyphKind.Shuffle, if (pp.shuffled) Accent else Dim, Modifier.size(26.dp)) }
            RoundButton(60, { pp.previous() }) { Glyph(GlyphKind.Prev, OffWhite, Modifier.size(32.dp)) }
            Box(Modifier.size(76.dp).clip(CircleShape).background(OffWhite).clickable { pp.togglePlay() }, contentAlignment = Alignment.Center) {
                Glyph(if (pp.playWhenReady) GlyphKind.Pause else GlyphKind.Play, Color.Black, Modifier.size(38.dp))
            }
            RoundButton(60, { pp.next() }) { Glyph(GlyphKind.Next, OffWhite, Modifier.size(32.dp)) }
            RoundButton(52, { showLyrics = !showLyrics }) {
                Text("Lyrics", color = if (showLyrics) Accent else Dim, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun RoundButton(sizeDp: Int, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.size(sizeDp.dp).clip(CircleShape).clickable { onClick() }, contentAlignment = Alignment.Center) { content() }
}

// ───────────────────────── Seek bar ─────────────────────────

@Composable
private fun SeekBar(position: State<Long>, duration: State<Long>, lyricStartMs: Long?, onSeek: (Long) -> Unit) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val dur = duration.value
    val frac = drag ?: if (dur > 0) (position.value.toFloat() / dur).coerceIn(0f, 1f) else 0f
    // Thin marker where the synced lyrics begin (only when that is 15 s or more into the song).
    val markerAlpha by animateFloatAsState(if (lyricStartMs != null && dur > 0) 1f else 0f, tween(700), label = "lyricMarker")
    Column {
        Box(
            Modifier.fillMaxWidth().height(36.dp)
                .pointerInput(dur) { detectTapGestures { o -> if (dur > 0) onSeek((o.x / size.width * dur).toLong().coerceIn(0L, dur)) } }
                .pointerInput(dur) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = { drag?.let { if (dur > 0) onSeek((it * dur).toLong()) }; drag = null },
                        onDragCancel = { drag = null },
                        onHorizontalDrag = { change, _ -> drag = (change.position.x / size.width).coerceIn(0f, 1f) },
                    )
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val cy = size.height / 2
                val th = 4.dp.toPx()
                drawRoundRect(Color(0xFF333333), Offset(0f, cy - th / 2), Size(size.width, th), CornerRadius(th / 2))
                if (lyricStartMs != null && dur > 0) {
                    val x = size.width * (lyricStartMs.toFloat() / dur).coerceIn(0f, 1f)
                    drawRect(OffWhite.copy(alpha = markerAlpha), Offset(x, cy - th / 2), Size(2.dp.toPx(), th))
                }
                drawRoundRect(OffWhite, Offset(0f, cy - th / 2), Size(size.width * frac, th), CornerRadius(th / 2))
                drawCircle(OffWhite, 7.dp.toPx(), Offset(size.width * frac, cy))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(fmt(if (drag != null) (drag!! * dur).toLong() else position.value), color = Dim, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(fmt(dur), color = Dim, fontSize = 12.sp)
        }
    }
}

// ───────────────────────── Lyrics ─────────────────────────

@Composable
private fun LyricsPanel(lyrics: Lyrics?, message: String, position: State<Long>, onSeek: (Long) -> Unit, modifier: Modifier) {
    when {
        lyrics != null && lyrics.synced.isNotEmpty() -> SyncedLyrics(lyrics.synced, position, onSeek, modifier)
        lyrics != null && !lyrics.plain.isNullOrBlank() ->
            Column(modifier.verticalScroll(rememberScrollState()).padding(vertical = 48.dp, horizontal = 8.dp)) {
                Text(lyrics.plain!!, color = Color(0xFFBBBBBB), fontSize = 18.sp, textAlign = TextAlign.Center, lineHeight = 28.sp, modifier = Modifier.fillMaxWidth())
            }
        else -> Box(modifier, contentAlignment = Alignment.Center) { Text(message, color = Dim, fontSize = 18.sp) }
    }
}

@Composable
private fun SyncedLyrics(lines: List<LyricLine>, position: State<Long>, onSeek: (Long) -> Unit, modifier: Modifier) {
    val cur by remember(lines) { derivedStateOf { lines.indexOfLast { it.timeMs <= position.value + 300 } } }
    val listState = rememberLazyListState()
    LaunchedEffect(cur) {
        if (cur >= 0) listState.animateScrollToItem(cur, -(listState.layoutInfo.viewportSize.height / 3))
    }
    val none = remember { MutableInteractionSource() }
    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(vertical = 120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        itemsIndexed(lines) { i, line ->
            val active = i == cur
            Text(
                line.text,
                Modifier.fillMaxWidth().clickable(interactionSource = none, indication = null) { onSeek(line.timeMs) }.padding(vertical = 8.dp, horizontal = 8.dp),
                color = if (active) OffWhite else Color(0xFF6A6A6A),
                fontSize = if (active) 24.sp else 20.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
            )
        }
    }
}
