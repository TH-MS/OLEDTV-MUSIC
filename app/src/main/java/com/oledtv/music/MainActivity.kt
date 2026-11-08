@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.oledtv.music

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.oledtv.music.data.AlbumDto
import com.oledtv.music.data.ArtistDto
import com.oledtv.music.data.NavidromeRepository
import com.oledtv.music.data.ServerConfig
import com.oledtv.music.ui.AlbumArtistsScreen
import com.oledtv.music.ui.AlbumTracklistScreen
import com.oledtv.music.ui.ArtistDetailScreen
import com.oledtv.music.ui.FocusSurface
import com.oledtv.music.ui.PlaybackManager
import com.oledtv.music.ui.PlayerScreen
import com.oledtv.music.ui.requestSafely
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Settings persisted in SharedPreferences so app updates keep the login. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("oledtv_music_prefs", Context.MODE_PRIVATE)
    fun load(): ServerConfig? {
        val url = sp.getString("url", null)?.takeIf { it.isNotBlank() } ?: return null
        return ServerConfig(url, sp.getString("user", "").orEmpty(), sp.getString("pass", "").orEmpty())
    }
    /** Albums per row (key kept from the previous version so the saved choice survives). 0 = automatic. */
    var gridColumns: Int
        get() = sp.getInt("grid_columns", 0)
        set(v) { sp.edit().putInt("grid_columns", v).apply() }

    /** Artists per row on the main menu. 0 = automatic. */
    var artistColumns: Int
        get() = sp.getInt("artist_columns", 0)
        set(v) { sp.edit().putInt("artist_columns", v).apply() }

    fun save(c: ServerConfig) { sp.edit().putString("url", c.url).putString("user", c.username).putString("pass", c.password).apply() }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Memory-lean image loading: RGB565 bitmaps, small memory cache, modest disk cache.
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .allowRgb565(true)
                .crossfade(false)
                .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.10).build() }
                .diskCache { DiskCache.Builder().directory(cacheDir.resolve("img")).maxSizeBytes(64L * 1024 * 1024).build() }
                .build(),
        )

        val prefs = Prefs(this)
        setContent { OledApp(prefs) }
    }
}

// ───────────────────────── Navigation ─────────────────────────

sealed interface Screen {
    data object Config : Screen
    class Artists(val grid: LazyGridState = LazyGridState(), val last: MutableIntState = mutableIntStateOf(0)) : Screen
    class Artist(val artist: ArtistDto, val grid: LazyGridState = LazyGridState(), val last: MutableIntState = mutableIntStateOf(0)) : Screen
    class Tracks(val albums: List<AlbumDto>, val index: Int, val list: LazyListState = LazyListState(), val last: MutableIntState = mutableIntStateOf(0)) : Screen
    data object Player : Screen
}

@Composable
fun OledApp(prefs: Prefs) {
    val context = LocalContext.current
    val manager = remember { PlaybackManager.get(context) }
    var config by remember { mutableStateOf(prefs.load()) }
    var albumColumns by remember { mutableIntStateOf(prefs.gridColumns) }
    var artistColumns by remember { mutableIntStateOf(prefs.artistColumns) }
    val repo = remember(config) { config?.let { NavidromeRepository(it) } }
    val stack = remember { mutableStateListOf<Screen>(if (prefs.load() == null) Screen.Config else Screen.Artists()) }

    BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }

    // Rebuild the back stack so Back from Now Playing lands on the tracklist of the album that is playing.
    fun nowPlayingStack(includePlayer: Boolean) {
        val artist = manager.artist
        val albums = manager.queueAlbums
        val idx = manager.currentAlbumIndex()
        if (artist != null && idx in albums.indices) {
            val base = stack.firstOrNull { it is Screen.Artists } ?: Screen.Artists()
            val artistScreen = stack.filterIsInstance<Screen.Artist>().lastOrNull { it.artist.id == artist.id } ?: Screen.Artist(artist)
            stack.clear()
            stack.add(base); stack.add(artistScreen); stack.add(Screen.Tracks(albums, idx))
            if (includePlayer) stack.add(Screen.Player)
        } else if (includePlayer) {
            stack.add(Screen.Player)
        } else if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
        }
    }

    // Declared after the generic handler so it takes priority while the player is showing.
    BackHandler(enabled = stack.last() is Screen.Player) {
        val below = stack.getOrNull(stack.lastIndex - 1)
        if (below is Screen.Tracks && below.index == manager.currentAlbumIndex()) stack.removeAt(stack.lastIndex)
        else nowPlayingStack(false)
    }

    CompositionLocalProvider(LocalContentColor provides Color(0xFFE6E6E6)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) { // true OLED black
            val screen = stack.last()
            if (screen is Screen.Config || repo == null) {
                ConfigScreen(
                    initial = config,
                    artistColumns = artistColumns,
                    onArtistColumns = { artistColumns = it; prefs.artistColumns = it },
                    albumColumns = albumColumns,
                    onAlbumColumns = { albumColumns = it; prefs.gridColumns = it },
                ) { saved ->
                    prefs.save(saved)
                    config = saved
                    stack.clear(); stack.add(Screen.Artists())
                }
            } else when (screen) {
                is Screen.Artists -> AlbumArtistsScreen(
                    repo = repo, gridState = screen.grid, lastIndex = screen.last, columns = artistColumns,
                    onArtistClick = { stack.add(Screen.Artist(it)) },
                    onSettings = { stack.add(Screen.Config) },
                    onNowPlaying = if (manager.hasQueue) ({ nowPlayingStack(true) }) else null,
                )
                is Screen.Artist -> ArtistDetailScreen(
                    repo = repo, artist = screen.artist, gridState = screen.grid, lastIndex = screen.last, columns = albumColumns,
                    onAlbumClick = { albums, i -> stack.add(Screen.Tracks(albums, i)) },
                )
                is Screen.Tracks -> AlbumTracklistScreen(
                    repo = repo, albums = screen.albums, albumIndex = screen.index,
                    listState = screen.list, lastIndex = screen.last,
                    onPlay = { songs, start ->
                        manager.artist = stack.filterIsInstance<Screen.Artist>().lastOrNull()?.artist
                        manager.play(repo, screen.albums, screen.index, songs, start)
                        stack.add(Screen.Player)
                    },
                )
                is Screen.Player -> PlayerScreen(repo, manager)
                is Screen.Config -> Unit
            }
        }
    }
}

// ───────────────────────── Config screen ─────────────────────────

@Composable
fun ConfigScreen(
    initial: ServerConfig?,
    artistColumns: Int,
    onArtistColumns: (Int) -> Unit,
    albumColumns: Int,
    onAlbumColumns: (Int) -> Unit,
    onSaved: (ServerConfig) -> Unit,
) {
    var url by remember { mutableStateOf(initial?.url ?: "http://") }
    var user by remember { mutableStateOf(initial?.username.orEmpty()) }
    var pass by remember { mutableStateOf(initial?.password.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { delay(50); first.requestSafely() }

    // Two columns so everything fits comfortably on a 16:9 screen: connection on the left, display options on the right.
    Box(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvTextField("Server URL", url, { url = it }, KeyboardType.Uri, modifier = Modifier.focusRequester(first))
                    TvTextField("Username", user, { user = it }, KeyboardType.Text)
                    TvTextField("Password", pass, { pass = it }, KeyboardType.Password, password = true)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    ChoiceRow("Artists per row", listOf(0 to "Auto", 4 to "4", 5 to "5", 6 to "6", 7 to "7", 8 to "8"), artistColumns, onArtistColumns)
                    ChoiceRow("Albums per row", listOf(0 to "Auto", 3 to "3", 4 to "4", 5 to "5", 6 to "6"), albumColumns, onAlbumColumns)
                    error?.let { Text(it, color = Color(0xFFFF6B6B), fontSize = 15.sp) }
                    FocusSurface(onClick = {
                        if (busy || url.isBlank() || user.isBlank()) return@FocusSurface
                        busy = true; error = null
                        val cfg = ServerConfig(url.trim(), user.trim(), pass)
                        scope.launch {
                            try {
                                NavidromeRepository(cfg).ping()
                                onSaved(cfg)
                            } catch (e: CancellationException) { throw e
                            } catch (e: Exception) { error = "Could not connect: ${e.message ?: "unknown error"}"
                            } finally { busy = false }
                        }
                    }) {
                        Text(if (busy) "Connecting…" else "Save & Connect", Modifier.padding(horizontal = 26.dp, vertical = 12.dp), fontSize = 19.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(label: String, options: List<Pair<Int, String>>, selected: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 14.sp, color = Color(0xFFAAAAAA))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (n, text) ->
                FocusSurface(
                    onClick = { onSelect(n) },
                    containerColor = if (selected == n) Color(0xFF505050) else Color(0xFF121212),
                ) {
                    Text(
                        text,
                        Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        fontSize = 17.sp,
                        fontWeight = if (selected == n) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun TvTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
    modifier: Modifier = Modifier,
    password: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    Column(modifier) {
        Text(label, fontSize = 14.sp, color = Color(0xFFAAAAAA))
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 20.sp),
            cursorBrush = SolidColor(Color.White),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown && (ev.key == Key.DirectionCenter || ev.key == Key.Enter)) {
                        keyboard?.show(); true
                    } else false
                }
                .background(Color(0xFF121212), RoundedCornerShape(10.dp))
                .border(if (focused) 3.dp else 1.dp, if (focused) Color.White else Color(0xFF333333), RoundedCornerShape(10.dp))
                .padding(12.dp),
        )
    }
}
