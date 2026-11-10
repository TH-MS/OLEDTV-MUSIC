@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.oledtv.music

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import android.view.WindowManager
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

    private var swallowUp = false

    /** Stamps every key press for the idle timer; while the bouncing title is up, any key just dismisses it. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        Idle.lastInputMs = SystemClock.elapsedRealtime()
        if (Idle.screensaver && event.action == android.view.KeyEvent.ACTION_DOWN) {
            Idle.screensaver = false
            swallowUp = true
            return true
        }
        if (swallowUp) {
            if (event.action == android.view.KeyEvent.ACTION_UP) swallowUp = false
            return true
        }
        // Remote's Play/Pause button (not Select): toggles playback from any screen; ignored if nothing is queued.
        if (event.keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) PlaybackManager.get(this).togglePlayPause()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

// ───────────────────────── Navigation ─────────────────────────

sealed interface Screen {
    data object Config : Screen
    class Artists(val grid: LazyGridState = LazyGridState(), val last: MutableIntState = mutableIntStateOf(0)) : Screen {
        /** Id of the artist last focused/picked (or the playing artist via Now Playing): where we land on return. */
        var focusId: String? = null
        /** True only when arriving via Now Playing: centre the playing artist. A normal return keeps the scroll as-is. */
        var centre: Boolean = false
    }
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

    // Keep the screen on (no screensaver) while music plays, and for 30 s after it stops/pauses.
    val activity = context as? Activity
    androidx.compose.runtime.LaunchedEffect(manager) {
        var lastPlaying = System.currentTimeMillis()
        while (true) {
            if (manager.player.isPlaying) lastPlaying = System.currentTimeMillis()
            val keepOn = System.currentTimeMillis() - lastPlaying < 30_000 || Idle.screensaver
            activity?.window?.let { w ->
                if (keepOn) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            delay(1_000)
        }
    }

    // Rebuild the back stack so Back from Now Playing lands on the tracklist of the album that is playing.
    fun nowPlayingStack(includePlayer: Boolean) {
        val artist = manager.artist
        val albums = manager.queueAlbums
        val idx = manager.currentAlbumIndex()
        if (artist != null && idx in albums.indices) {
            val base = (stack.firstOrNull { it is Screen.Artists } as? Screen.Artists ?: Screen.Artists()).also { it.focusId = artist.id; it.centre = true }
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

    // 60 s without any key press on any screen except Now Playing:
    //  - something is playing/paused  -> go back to Now Playing
    //  - nothing playing              -> show the bouncing "OLED MUSIC PLAYER" title
    androidx.compose.runtime.LaunchedEffect(manager) {
        while (true) {
            delay(1_000)
            val idleMs = SystemClock.elapsedRealtime() - Idle.lastInputMs
            if (idleMs >= 60_000 && !Idle.screensaver && stack.last() !is Screen.Player) {
                if (manager.nowSongId != null) nowPlayingStack(true) else Idle.screensaver = true
            }
        }
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
                    repo = repo, gridState = screen.grid, lastIndex = screen.last, columns = artistColumns, playingArtistId = manager.nowArtistId,
                    focusArtistId = screen.focusId, onFocusArtist = { screen.focusId = it },
                    onArtistClick = { stack.add(Screen.Artist(it)) },
                    onSettings = { stack.add(Screen.Config) },
                    onNowPlaying = if (manager.hasQueue) ({ nowPlayingStack(true) }) else null,
                    centre = screen.centre, onCentred = { screen.centre = false },
                )
                is Screen.Artist -> ArtistDetailScreen(
                    repo = repo, artist = screen.artist, gridState = screen.grid, lastIndex = screen.last, columns = albumColumns, playingAlbumId = manager.nowAlbumId,
                    onAlbumClick = { albums, i -> stack.add(Screen.Tracks(albums, i)) },
                )
                is Screen.Tracks -> AlbumTracklistScreen(
                    repo = repo, albums = screen.albums, albumIndex = screen.index,
                    listState = screen.list, lastIndex = screen.last, playingSongId = manager.nowSongId,
                    onPlay = { songs, start ->
                        // Selecting the song that is already playing just opens Now Playing: no restart.
                        val alreadyPlaying = manager.nowSongId != null && manager.nowSongId == songs.getOrNull(start)?.id
                        if (!alreadyPlaying) {
                            manager.artist = stack.filterIsInstance<Screen.Artist>().lastOrNull()?.artist
                            manager.play(repo, screen.albums, screen.index, songs, start)
                        }
                        stack.add(Screen.Player)
                    },
                )
                is Screen.Player -> PlayerScreen(repo, manager)
                is Screen.Config -> Unit
            }
            if (Idle.screensaver) BouncingTitle()
        }
    }
}

/** App-wide idle bookkeeping. [lastInputMs] is stamped on every remote key press. */
object Idle {
    @Volatile var lastInputMs: Long = SystemClock.elapsedRealtime()
    var screensaver by mutableStateOf(false)
}

/**
 * Shown after 60 s idle when nothing is playing: the title bounces around the whole screen like an old DVD
 * menu, at the same speed and the same smooth per-frame motion as the "Fast" cover drift (4 dp/s x, 3 dp/s y).
 */
@Composable
fun BouncingTitle() {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val density = LocalDensity.current.density
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()
        var w by remember { mutableFloatStateOf(0f) }
        var h by remember { mutableFloatStateOf(0f) }
        val px = remember { mutableFloatStateOf(maxW * 0.3f) }
        val py = remember { mutableFloatStateOf(maxH * 0.3f) }

        androidx.compose.runtime.LaunchedEffect(maxW, maxH, w, h) {
            val slackX = (maxW - w).coerceAtLeast(0f)
            val slackY = (maxH - h).coerceAtLeast(0f)
            var dirX = 1f
            var dirY = 1f
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val dt = ((now - last) / 1_000_000_000f).coerceAtMost(0.1f)
                last = now
                var x = px.floatValue + dirX * 4f * density * dt
                var y = py.floatValue + dirY * 3f * density * dt
                if (x > slackX) { x = slackX - (x - slackX); dirX = -1f } else if (x < 0f) { x = -x; dirX = 1f }
                if (y > slackY) { y = slackY - (y - slackY); dirY = -1f } else if (y < 0f) { y = -y; dirY = 1f }
                px.floatValue = x.coerceIn(0f, slackX)
                py.floatValue = y.coerceIn(0f, slackY)
            }
        }

        Text(
            "OLED MUSIC PLAYER",
            Modifier
                .onSizeChanged { w = it.width.toFloat(); h = it.height.toFloat() }
                .graphicsLayer { translationX = px.floatValue; translationY = py.floatValue },
            color = Color(0xFFA0A0A0),
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
        )
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
                    TvTextField("Server URL", url, { url = it }, KeyboardType.Uri)
                    TvTextField("Username", user, { user = it }, KeyboardType.Text)
                    TvTextField("Password", pass, { pass = it }, KeyboardType.Password, password = true)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    ChoiceRow("Artists per row", listOf(0 to "Auto", 4 to "4", 5 to "5", 6 to "6", 7 to "7", 8 to "8"), artistColumns, onArtistColumns, first)
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
private fun ChoiceRow(
    label: String, options: List<Pair<Int, String>>, selected: Int, onSelect: (Int) -> Unit,
    startFocus: FocusRequester? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 14.sp, color = Color(0xFFAAAAAA))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (n, text) ->
                FocusSurface(
                    onClick = { onSelect(n) },
                    modifier = if (startFocus != null && selected == n) Modifier.focusRequester(startFocus) else Modifier,
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
