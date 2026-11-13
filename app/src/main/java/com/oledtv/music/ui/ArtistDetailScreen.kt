@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.oledtv.music.ui

import androidx.activity.compose.BackHandler
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.tv.material3.LocalTextStyle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import com.oledtv.music.data.friendlyError
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.oledtv.music.data.AlbumDto
import com.oledtv.music.data.ArtistDto
import com.oledtv.music.data.NavidromeRepository
import com.oledtv.music.data.SongDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

// ───────────────────────── Artist detail: albums by release year ascending ─────────────────────────

@Composable
fun ArtistDetailScreen(
    repo: NavidromeRepository,
    artist: ArtistDto,
    gridState: LazyGridState,
    lastIndex: MutableIntState,
    columns: Int,
    playingAlbumId: String?,
    onAlbumClick: (albums: List<AlbumDto>, index: Int) -> Unit,
) {
    var state by remember { mutableStateOf<Load<List<AlbumDto>>>(repo.cachedAlbums(artist.id)?.let { Load.Ok(it) } ?: Load.Loading) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(artist.id, reload) {
        if (state is Load.Err) state = Load.Loading
        // Repository sorts by year ASC (unknown last), then name.
        state = try { Load.Ok(repo.albumsOf(artist.id)) } catch (e: CancellationException) { throw e } catch (e: Exception) { Load.Err(friendlyError(e)) }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp)) {
        Text(artist.name, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.padding(8.dp))
        when (val s = state) {
            is Load.Loading -> LoadingPane("Loading albums…")
            is Load.Err -> StatusPane(s.message) { reload++ }
            is Load.Ok -> AlbumGrid(repo, s.value, gridState, lastIndex, onAlbumClick, columns, playingAlbumId)
        }
    }
}

@Composable
private fun AlbumGrid(
    repo: NavidromeRepository,
    albums: List<AlbumDto>,
    gridState: LazyGridState,
    lastIndex: MutableIntState,
    onAlbumClick: (List<AlbumDto>, Int) -> Unit,
    columns: Int,
    playingAlbumId: String?,
) {
    if (albums.isEmpty()) { StatusPane("No albums found for this artist."); return }
    val startIndex = remember { lastIndex.intValue.coerceIn(0, albums.lastIndex) }
    val startFocus = remember { FocusRequester() }

    LazyVerticalGrid(
        columns = if (columns > 0) GridCells.Fixed(columns) else GridCells.Adaptive(190.dp),
        state = gridState,
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(albums, key = { _, a -> a.id }) { i, album ->
            FocusSurface(
                onClick = { onAlbumClick(albums, i) },
                playing = album.id == playingAlbumId,
                modifier = Modifier
                    .onFocusChanged { if (it.hasFocus) lastIndex.intValue = i }
                    .then(if (i == startIndex) Modifier.focusRequester(startFocus) else Modifier),
            ) {
                Column {
                    CoverImage(
                        url = repo.coverArtUrl(album.coverArt, 480),
                        cacheKey = "al-${album.id}",
                        sizePx = 480,
                        fallbackLetter = album.name,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    )
                    Text(album.name, Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp), fontSize = 16.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text(album.year?.toString() ?: "Unknown year", Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp, top = 2.dp),
                        fontSize = 14.sp, color = Color(0xFF9A9A9A))
                }
            }
        }
    }

    LaunchedEffect(albums) {
        gridState.scrollToItem(startIndex)
        startFocus.requestWhenReady()
    }

    // Quietly pre-load the tracks of the album you are resting on, so opening it is instant.
    LaunchedEffect(albums) {
        snapshotFlow { lastIndex.intValue }.collectLatest { idx ->
            delay(250)
            val a = albums.getOrNull(idx) ?: return@collectLatest
            try { repo.songsOf(a.id) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }
}

// ───────────────────────── Album tracklist ─────────────────────────

@Composable
fun AlbumTracklistScreen(
    repo: NavidromeRepository,
    albums: List<AlbumDto>,
    albumIndex: Int,
    listState: LazyListState,
    lastIndex: MutableIntState,
    playingSongId: String?,
    showArtist: Boolean,
    onShowArtist: (Boolean) -> Unit,
    onPlay: (songs: List<SongDto>, startIndex: Int) -> Unit,
) {
    val album = albums[albumIndex]
    var state by remember { mutableStateOf<Load<List<SongDto>>>(repo.cachedSongs(album.id)?.let { Load.Ok(it) } ?: Load.Loading) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(album.id, reload) {
        if (state is Load.Err) state = Load.Loading
        state = try { Load.Ok(repo.songsOf(album.id)) } catch (e: CancellationException) { throw e } catch (e: Exception) { Load.Err(friendlyError(e)) }
    }

    Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp)) {
        BoxWithConstraints(Modifier.width(340.dp).fillMaxHeight()) {
            // Uniform cover: depends only on the screen, never on how many lines the title needs
            // (room for a two-line title is always reserved below it).
            val cover = minOf(maxWidth, maxHeight - 130.dp)
            Column(Modifier.fillMaxSize()) {
                CoverImage(
                    url = repo.coverArtUrl(album.coverArt, 640),
                    cacheKey = "al-${album.id}",
                    sizePx = 640,
                    fallbackLetter = album.name,
                    modifier = Modifier.size(cover),
                )
                Spacer(Modifier.height(12.dp))
                Text(album.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(album.artist.orEmpty(), fontSize = 18.sp, color = Color(0xFFBBBBBB), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(album.year?.toString().orEmpty(), fontSize = 16.sp, color = Color(0xFF888888))
            }
        }
        Spacer(Modifier.width(36.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            when (val s = state) {
                is Load.Loading -> LoadingPane("Loading tracks…")
                is Load.Err -> StatusPane(s.message) { reload++ }
                is Load.Ok -> TrackList(s.value, listState, lastIndex, onPlay, playingSongId, showArtist, onShowArtist)
            }
        }
    }
}

@Composable
private fun TrackList(
    songs: List<SongDto>,
    listState: LazyListState,
    lastIndex: MutableIntState,
    onPlay: (List<SongDto>, Int) -> Unit,
    playingSongId: String?,
    showArtist: Boolean,
    onShowArtist: (Boolean) -> Unit,
) {
    if (songs.isEmpty()) { StatusPane("This album has no tracks."); return }
    val startIndex = remember { lastIndex.intValue.coerceIn(0, songs.lastIndex) }
    // Disc labels appear only if the album really has 2+ discs (a lone "Disc 1" is never shown).
    val multiDisc = remember(songs) { songs.map { it.discNumber ?: 1 }.distinct().size > 1 }

    // One focus requester per row (so focus can come back to the row you were on), plus the 3-bar button / menu.
    val rowReqs = remember(songs) { HashMap<Int, FocusRequester>() }
    fun reqFor(i: Int) = rowReqs.getOrPut(i) { FocusRequester() }
    val barsFocus = remember { FocusRequester() }
    val itemFocus = remember { FocusRequester() }
    var menuOpen by remember { mutableStateOf(false) }
    var openedAt by remember { mutableLongStateOf(0L) }
    var hasFocus by remember { mutableStateOf(false) }
    val barsHasFocus = remember { mutableStateOf(false) }

    // Show/hide transition: one 0..1 progress, read only in layout/draw (no recomposition per frame).
    val p = animateFloatAsState(if (showArtist) 1f else 0f, tween(170, easing = FastOutSlowInEasing), label = "artistP")

    // Row height is fixed to exactly what a single-line row is today, so both states occupy identical boxes.
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val baseStyle = LocalTextStyle.current
    val rowH = remember(density) {
        with(density) { measurer.measure("Ag", baseStyle.merge(TextStyle(fontSize = 20.sp))).size.height.toDp() } + 24.dp
    }

    val closeMenu: () -> Unit = {
        try { barsFocus.requestFocus() } catch (_: IllegalStateException) { }
        menuOpen = false
    }
    fun armed() = android.os.SystemClock.uptimeMillis() - openedAt > 400
    LaunchedEffect(menuOpen) {
        if (menuOpen) {
            withFrameNanos { }
            delay(30)
            try { itemFocus.requestFocus() } catch (_: IllegalStateException) { }
        }
    }
    BackHandler(enabled = menuOpen) { closeMenu() }

    Box(
        Modifier.fillMaxSize()
            .onFocusChanged { hasFocus = it.hasFocus; if (!it.hasFocus) menuOpen = false }
            .onPreviewKeyEvent { ev ->
                // From a row, RIGHT goes straight to the 3-bar button (it sits outside the list).
                if (!menuOpen && ev.key == Key.DirectionRight && ev.type == KeyEventType.KeyDown) {
                    if (!barsHasFocus.value) { try { barsFocus.requestFocus() } catch (_: IllegalStateException) { }; return@onPreviewKeyEvent true }
                }
                false
            },
    ) {
        LazyColumn(
            state = listState,
            // 28 dp side padding: a focused row scales up ~4% and must stay clear of the cover.
            contentPadding = PaddingValues(vertical = 12.dp, horizontal = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(songs, key = { _, s -> s.id }) { i, song ->
                val disc = song.discNumber ?: 1
                val firstOfDisc = multiDisc && (i == 0 || disc != (songs[i - 1].discNumber ?: 1))
                val hasArtist = !song.artist.isNullOrBlank()
                val rowP: () -> Float = { if (hasArtist) p.value else 0f }
                Column {
                    if (firstOfDisc) {
                        Text(
                            "Disc $disc",
                            Modifier.padding(start = 20.dp, top = if (i == 0) 0.dp else 14.dp, bottom = 6.dp),
                            fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFF9A9A9A),
                        )
                    }
                    FocusSurface(
                        onClick = { onPlay(songs, i) },
                        playing = song.id == playingSongId,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(reqFor(i))
                            .onFocusChanged { if (it.hasFocus) lastIndex.intValue = i },
                    ) {
                        Row(Modifier.fillMaxWidth().height(rowH).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text((song.track ?: (i + 1)).toString().padStart(2, '0'), Modifier.width(44.dp), fontSize = 18.sp, color = Color(0xFF8A8A8A))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                                // Title: 20 sp -> 18 sp (scaled in the draw phase, layout height follows) ...
                                Text(
                                    song.title,
                                    Modifier
                                        .layout { m, c ->
                                            val pl = m.measure(c)
                                            val h = (pl.height * (1f - 0.1f * rowP())).roundToInt()
                                            layout(pl.width, h) { pl.placeRelative(0, (h - pl.height) / 2) }
                                        }
                                        .graphicsLayer {
                                            val sc = 1f - 0.1f * rowP()
                                            scaleX = sc; scaleY = sc; transformOrigin = TransformOrigin(0f, 0.5f)
                                        },
                                    fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                // ... artist: small and dim, sliding/fading in underneath.
                                if (hasArtist) {
                                    Text(
                                        song.artist.orEmpty(),
                                        Modifier
                                            .layout { m, c ->
                                                val pl = m.measure(c)
                                                val h = (pl.height * rowP()).roundToInt()
                                                layout(pl.width, h) { pl.placeRelative(0, 0) }
                                            }
                                            .graphicsLayer { alpha = rowP() },
                                        fontSize = 12.sp, color = Color(0xFF7E7E7E), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Text(formatMs((song.duration ?: 0) * 1000L), fontSize = 18.sp, color = Color(0xFF8A8A8A))
                        }
                    }
                }
            }
        }

        // ── 3-bar button: in the page margin, completely clear of the widest (focused) row. ──
        Box(
            Modifier.align(Alignment.CenterEnd).offset(x = 42.dp).size(width = 50.dp, height = 62.dp)
                .graphicsLayer { alpha = if (hasFocus) 1f else 0f },
            contentAlignment = Alignment.Center,
        ) {
            FocusSurface(
                onClick = { openedAt = android.os.SystemClock.uptimeMillis(); menuOpen = true },
                modifier = Modifier.size(width = 34.dp, height = 44.dp).focusRequester(barsFocus)
                    .onFocusChanged { barsHasFocus.value = it.isFocused }
                    .onPreviewKeyEvent { ev ->
                        if (ev.key == Key.DirectionLeft && ev.type == KeyEventType.KeyDown) {
                            try { reqFor(lastIndex.intValue.coerceIn(0, songs.lastIndex)).requestFocus() } catch (_: IllegalStateException) { }
                            true
                        } else false
                    },
                shape = RoundedCornerShape(8.dp),
                containerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(width = 16.dp, height = 12.dp)) {
                        val sw = size.height * 0.18f
                        for (i in 0..2) drawLine(Color(0xFFF0F0F0), Offset(0f, size.height * i / 2f), Offset(size.width, size.height * i / 2f), sw)
                    }
                }
            }
        }

        // ── The menu (always composed; only visible/focusable while open). ──
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 38.dp)
                .graphicsLayer { alpha = if (menuOpen) 1f else 0f }
                .background(Color(0xFF0B0B0B), RoundedCornerShape(14.dp))
                .padding(8.dp)
                .onPreviewKeyEvent { ev ->
                    if (!menuOpen) return@onPreviewKeyEvent false
                    when (ev.key) {
                        Key.DirectionLeft -> { if (ev.type == KeyEventType.KeyDown) closeMenu(); true }
                        Key.DirectionRight, Key.DirectionUp, Key.DirectionDown -> true
                        else -> false
                    }
                },
        ) {
            FocusSurface(
                onClick = { if (menuOpen && armed()) { onShowArtist(!showArtist); closeMenu() } },
                Modifier.width(140.dp).focusProperties { canFocus = menuOpen }.focusRequester(itemFocus),
            ) {
                Text(if (showArtist) "Hide Artist" else "Show Artist", Modifier.padding(horizontal = 14.dp, vertical = 10.dp), fontSize = 18.sp)
            }
        }
    }

    LaunchedEffect(songs) {
        listState.scrollToItem(startIndex)
        reqFor(startIndex).requestWhenReady()
    }
}
