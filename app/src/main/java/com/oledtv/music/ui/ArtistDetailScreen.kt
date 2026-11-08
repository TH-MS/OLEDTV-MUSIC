@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.oledtv.music.ui

import androidx.compose.foundation.layout.Arrangement
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
    onAlbumClick: (albums: List<AlbumDto>, index: Int) -> Unit,
) {
    var state by remember { mutableStateOf<Load<List<AlbumDto>>>(Load.Loading) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(artist.id, reload) {
        state = Load.Loading
        // Repository sorts by year ASC (unknown last), then name.
        state = try { Load.Ok(repo.albumsOf(artist.id)) } catch (e: CancellationException) { throw e } catch (e: Exception) { Load.Err(e.message ?: "Network error") }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp)) {
        Text(artist.name, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.padding(8.dp))
        when (val s = state) {
            is Load.Loading -> StatusPane("Loading albums…")
            is Load.Err -> StatusPane(s.message) { reload++ }
            is Load.Ok -> AlbumGrid(repo, s.value, gridState, lastIndex, onAlbumClick)
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
) {
    if (albums.isEmpty()) { StatusPane("No albums found for this artist."); return }
    val startIndex = remember { lastIndex.intValue.coerceIn(0, albums.lastIndex) }
    val startFocus = remember { FocusRequester() }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(190.dp),
        state = gridState,
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(albums, key = { _, a -> a.id }) { i, album ->
            FocusSurface(
                onClick = { onAlbumClick(albums, i) },
                modifier = Modifier
                    .onFocusChanged { if (it.hasFocus) lastIndex.intValue = i }
                    .then(if (i == startIndex) Modifier.focusRequester(startFocus) else Modifier),
            ) {
                Column {
                    CoverImage(
                        url = repo.coverArtUrl(album.coverArt, 320),
                        cacheKey = "al-${album.id}",
                        sizePx = 320,
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
        delay(80)
        startFocus.requestSafely()
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
    onPlay: (songs: List<SongDto>, startIndex: Int) -> Unit,
) {
    val album = albums[albumIndex]
    var state by remember { mutableStateOf<Load<List<SongDto>>>(Load.Loading) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(album.id, reload) {
        state = Load.Loading
        state = try { Load.Ok(repo.songsOf(album.id)) } catch (e: CancellationException) { throw e } catch (e: Exception) { Load.Err(e.message ?: "Network error") }
    }

    Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp)) {
        Column(Modifier.width(300.dp).fillMaxHeight()) {
            CoverImage(
                url = repo.coverArtUrl(album.coverArt, 480),
                cacheKey = "al-${album.id}",
                sizePx = 480,
                fallbackLetter = album.name,
                modifier = Modifier.size(280.dp),
            )
            Spacer(Modifier.padding(8.dp))
            Text(album.name, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(album.artist.orEmpty(), fontSize = 18.sp, color = Color(0xFFBBBBBB), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(album.year?.toString().orEmpty(), fontSize = 16.sp, color = Color(0xFF888888))
        }
        Spacer(Modifier.width(40.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            when (val s = state) {
                is Load.Loading -> StatusPane("Loading tracks…")
                is Load.Err -> StatusPane(s.message) { reload++ }
                is Load.Ok -> TrackList(s.value, listState, lastIndex, onPlay)
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
) {
    if (songs.isEmpty()) { StatusPane("This album has no tracks."); return }
    val startIndex = remember { lastIndex.intValue.coerceIn(0, songs.lastIndex) }
    val startFocus = remember { FocusRequester() }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(songs, key = { _, s -> s.id }) { i, song ->
            FocusSurface(
                onClick = { onPlay(songs, i) },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (it.hasFocus) lastIndex.intValue = i }
                    .then(if (i == startIndex) Modifier.focusRequester(startFocus) else Modifier),
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text((song.track ?: (i + 1)).toString().padStart(2, '0'), Modifier.width(44.dp), fontSize = 18.sp, color = Color(0xFF8A8A8A))
                    Text(song.title, Modifier.weight(1f), fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(formatMs((song.duration ?: 0) * 1000L), fontSize = 18.sp, color = Color(0xFF8A8A8A))
                }
            }
        }
    }

    LaunchedEffect(songs) {
        listState.scrollToItem(startIndex)
        delay(80)
        startFocus.requestSafely()
    }
}
