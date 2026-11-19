package com.oledtv.music.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oledtv.music.data.AlbumDto
import com.oledtv.music.data.ArtistDto
import com.oledtv.music.data.NavidromeRepository
import com.oledtv.music.data.ServerConfig
import com.oledtv.music.data.SongDto
import com.oledtv.music.data.friendlyError
import com.oledtv.music.data.layoutTracks
import com.oledtv.music.data.sameArtist
import com.oledtv.music.data.uniformTrackArtist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable { onBack() }, contentAlignment = Alignment.Center) {
                Glyph(GlyphKind.Back, OffWhite, Modifier.size(22.dp))
            }
        } else Spacer(Modifier.width(12.dp))
        Text(title, Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = OffWhite)
        if (action != null) TextButton(onClick = onAction) { Text(action, color = Accent) }
    }
}

@Composable
fun Centered(content: @Composable () -> Unit) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }

@Composable
fun ErrorPane(message: String, onRetry: () -> Unit) = Centered {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(message, color = Dim, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
        Button(onClick = onRetry) { Text("Retry") }
    }
}

/** Slim "now playing" bar at the bottom of the browse screens. */
@Composable
fun MiniPlayer(repo: NavidromeRepository, pp: PhonePlayer, onOpen: () -> Unit) {
    val item = pp.item ?: return
    val md = item.mediaMetadata
    Row(
        Modifier.fillMaxWidth().background(Panel).clickable { onOpen() }.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val cover = md.extras?.getString(PhonePlayer.KEY_COVER)
        Cover(repo.coverArtUrl(cover, 120), md.albumTitle?.toString().orEmpty(), Modifier.size(44.dp), 6.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(md.title?.toString().orEmpty(), color = OffWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(md.artist?.toString().orEmpty(), color = Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(44.dp).clip(CircleShape).clickable { pp.togglePlay() }, contentAlignment = Alignment.Center) {
            Glyph(if (pp.playWhenReady) GlyphKind.Pause else GlyphKind.Play, OffWhite, Modifier.size(24.dp))
        }
    }
}

// ───────────────────────── Artists ─────────────────────────

@Composable
fun ArtistsScreen(repo: NavidromeRepository, onOpenSettings: () -> Unit, onOpen: (ArtistDto) -> Unit) {
    var reload by remember { mutableIntStateOf(0) }
    val load = rememberLoad("artists", repo.cachedArtists(), reload) { repo.artists() }
    var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        TopBar("Artists", null, "Settings", onOpenSettings)
        when (val l = load) {
            Load.Loading -> Centered { CircularProgressIndicator(color = Accent) }
            is Load.Err -> ErrorPane(l.message) { reload++ }
            is Load.Ok -> {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("Search artists", color = Dim) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
                val shown = if (query.isBlank()) l.value else l.value.filter { it.name.contains(query.trim(), ignoreCase = true) }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.id }) { a ->
                        Row(Modifier.fillMaxWidth().clickable { onOpen(a) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Cover(repo.coverArtUrl(a.coverArt, 120), a.name, Modifier.size(48.dp), 24.dp)
                            Column(Modifier.padding(start = 14.dp)) {
                                Text(a.name, color = OffWhite, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                a.albumCount?.let { Text(if (it == 1) "1 album" else "$it albums", color = Dim, fontSize = 13.sp) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────── Albums of one artist ─────────────────────────

@Composable
fun ArtistScreen(repo: NavidromeRepository, artist: ArtistDto, onBack: () -> Unit, onOpen: (List<AlbumDto>, Int) -> Unit) {
    var reload by remember { mutableIntStateOf(0) }
    val load = rememberLoad(artist.id, repo.cachedAlbums(artist.id), reload) { repo.albumsOf(artist.id) }
    Column(Modifier.fillMaxSize()) {
        TopBar(artist.name, onBack)
        when (val l = load) {
            Load.Loading -> Centered { CircularProgressIndicator(color = Accent) }
            is Load.Err -> ErrorPane(l.message) { reload++ }
            is Load.Ok -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(l.value.size) { i ->
                    val a = l.value[i]
                    Column(Modifier.clickable { onOpen(l.value, i) }) {
                        Cover(repo.coverArtUrl(a.coverArt, 400), a.name, Modifier.fillMaxWidth().aspectRatio(1f))
                        Text(a.name, color = OffWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                        Text(a.year?.toString() ?: "Unknown year", color = Dim, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

// ───────────────────────── Track list ─────────────────────────

@Composable
fun AlbumScreen(
    repo: NavidromeRepository, pp: PhonePlayer, albums: List<AlbumDto>, index: Int, onBack: () -> Unit,
) {
    val album = albums[index]
    var reload by remember { mutableIntStateOf(0) }
    val load = rememberLoad(album.id, repo.cachedSongs(album.id), reload) { repo.songsOf(album.id) }
    Column(Modifier.fillMaxSize()) {
        TopBar(album.name, onBack)
        when (val l = load) {
            Load.Loading -> Centered { CircularProgressIndicator(color = Accent) }
            is Load.Err -> ErrorPane(l.message) { reload++ }
            is Load.Ok -> TrackList(repo, pp, album, l.value)
        }
    }
}

@Composable
private fun TrackList(repo: NavidromeRepository, pp: PhonePlayer, album: AlbumDto, songs: List<SongDto>) {
    if (songs.isEmpty()) { Centered { Text("This album has no tracks.", color = Dim) }; return }
    val rows = remember(songs) { layoutTracks(songs) }
    val ordered = remember(rows) { rows.map { it.song } }
    val albumArtist = album.artist
    // An album artist that differs from a track artist shared by EVERY track shows that track artist instead.
    val headerArtist = remember(songs) { uniformTrackArtist(songs, albumArtist) ?: albumArtist.orEmpty() }
    val showArtist = remember(songs) {
        uniformTrackArtist(songs, albumArtist) == null &&
            !albumArtist.isNullOrBlank() && songs.any { !it.artist.isNullOrBlank() && !sameArtist(it.artist, albumArtist) }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(repo.coverArtUrl(album.coverArt, 400), album.name, Modifier.size(120.dp))
                Column(Modifier.padding(start = 16.dp)) {
                    Text(album.name, color = OffWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(headerArtist, color = Color(0xFFBBBBBB), fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(album.year?.toString().orEmpty(), color = Dim, fontSize = 14.sp)
                }
            }
        }
        itemsIndexed(rows, key = { _, r -> r.song.id }) { i, row ->
            val song = row.song
            if (row.header != null) {
                Text(row.header, color = Dim, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp))
            }
            val playing = pp.nowSongId == song.id
            val shownArtist = song.artist?.takeIf { it.isNotBlank() } ?: albumArtist.orEmpty()
            val redundant = sameArtist(shownArtist, albumArtist)
            Row(
                Modifier.fillMaxWidth().clickable { pp.play(repo, album, ordered, i) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(row.num, Modifier.width(36.dp), fontSize = 15.sp, color = if (playing) Accent else Dim, maxLines = 1, softWrap = false)
                Column(Modifier.weight(1f)) {
                    Text(song.title, color = if (playing) Accent else OffWhite, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (showArtist && shownArtist.isNotBlank()) {
                        Text(shownArtist, color = if (redundant) Dimmer else Color(0xFF7E7E7E), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text(fmt((song.duration ?: 0) * 1000L), color = Dim, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

// ───────────────────────── Settings / first-run login ─────────────────────────

@Composable
fun SettingsScreen(
    initial: ServerConfig?, repo: NavidromeRepository?, mobileOnly: Boolean,
    onMobileOnly: (Boolean) -> Unit, onSaved: (ServerConfig) -> Unit, onBack: (() -> Unit)?,
) {
    var url by remember { mutableStateOf(initial?.url.orEmpty()) }
    var user by remember { mutableStateOf(initial?.username.orEmpty()) }
    var pass by remember { mutableStateOf(initial?.password.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshNote by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        TopBar(if (initial == null) "Connect to Navidrome" else "Settings", onBack)
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(url, { url = it }, label = { Text("Server address (e.g. 192.168.1.10:4533)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Button(
                enabled = !busy && url.isNotBlank() && user.isNotBlank(),
                onClick = {
                    busy = true; error = null
                    val cfg = ServerConfig(url.trim(), user.trim(), pass)
                    scope.launch {
                        try { NavidromeRepository(cfg).ping(); onSaved(cfg) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = friendlyError(e) }
                        finally { busy = false }
                    }
                },
            ) { Text(if (busy) "Connecting…" else "Save & connect") }
            error?.let { Text(it, color = Color(0xFFFF6B6B), fontSize = 14.sp) }

            if (repo != null) {
                Spacer(Modifier.height(8.dp))
                Button(
                    enabled = !refreshing,
                    onClick = {
                        refreshing = true; refreshNote = null
                        scope.launch {
                            refreshNote = try { repo.refreshLibrary() }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { "Refresh failed: ${friendlyError(e)}" }
                            finally { refreshing = false }
                        }
                    },
                ) { Text(if (refreshing) "Checking server…" else "Refresh library") }
                refreshNote?.let { Text(it, color = Color(0xFFBBBBBB), fontSize = 14.sp) }
            }

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Mobile data only", color = OffWhite, fontSize = 17.sp)
                    Text("Never use Wi-Fi, even when connected. A server that is only reachable on your home Wi-Fi will not work.", color = Dim, fontSize = 12.sp)
                    if (mobileOnly && NetPolicy.status.isNotBlank()) Text(NetPolicy.status, color = Accent, fontSize = 12.sp)
                }
                androidx.compose.material3.Switch(checked = mobileOnly, onCheckedChange = onMobileOnly)
            }
        }
    }
}
