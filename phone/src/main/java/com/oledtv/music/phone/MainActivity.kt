package com.oledtv.music.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.oledtv.music.data.AlbumDto
import com.oledtv.music.data.ArtistDto
import com.oledtv.music.data.NavidromeRepository
import com.oledtv.music.data.ServerConfig

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("oled_phone", Context.MODE_PRIVATE)
    fun load(c: Context): ServerConfig? {
        val s = sp(c)
        val url = s.getString("url", null) ?: return null
        return ServerConfig(url, s.getString("user", "").orEmpty(), s.getString("pass", "").orEmpty())
    }
    fun save(c: Context, cfg: ServerConfig) {
        sp(c).edit().putString("url", cfg.url).putString("user", cfg.username).putString("pass", cfg.password).apply()
    }
    fun mobileOnly(c: Context) = sp(c).getBoolean("mobileOnly", false)
    fun setMobileOnly(c: Context, v: Boolean) { sp(c).edit().putBoolean("mobileOnly", v).apply() }
}

sealed interface Route {
    data object Artists : Route
    data class Artist(val artist: ArtistDto) : Route
    data class Album(val albums: List<AlbumDto>, val index: Int) : Route
    data object Now : Route
    data object Settings : Route
}

class MainActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NetPolicy.apply(this, Prefs.mobileOnly(this))
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color.Black, surface = Color.Black, primary = Accent,
                    onBackground = OffWhite, onSurface = OffWhite, surfaceVariant = Panel,
                ),
            ) { Box(Modifier.fillMaxSize().background(Color.Black)) { PhoneApp() } }
        }
    }
}

@Composable
fun PhoneApp() {
    val ctx = LocalContext.current
    var config by remember { mutableStateOf(Prefs.load(ctx)) }
    var mobileOnly by remember { mutableStateOf(Prefs.mobileOnly(ctx)) }
    val onMobileOnly: (Boolean) -> Unit = {
        mobileOnly = it
        Prefs.setMobileOnly(ctx, it)
        NetPolicy.apply(ctx, it)
    }
    val cfg = config
    if (cfg == null) {
        SettingsScreen(null, null, mobileOnly, onMobileOnly, onSaved = { Prefs.save(ctx, it); config = it }, onBack = null)
        return
    }
    val repo = remember(cfg) { NavidromeRepository(cfg) }
    val pp = remember { PhonePlayer.get(ctx) }
    val stack = remember { mutableStateListOf<Route>(Route.Artists) }
    fun push(r: Route) { stack.add(r) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.size > 1) { pop() }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val r = stack.last()) {
                Route.Artists -> ArtistsScreen(repo, onOpenSettings = { push(Route.Settings) }, onOpen = { push(Route.Artist(it)) })
                is Route.Artist -> ArtistScreen(repo, r.artist, onBack = { pop() }, onOpen = { albums, i -> push(Route.Album(albums, i)) })
                is Route.Album -> AlbumScreen(repo, pp, r.albums, r.index, onBack = { pop() })
                Route.Now -> NowPlayingScreen(repo, pp, onBack = { pop() })
                Route.Settings -> SettingsScreen(
                    cfg, repo, mobileOnly, onMobileOnly,
                    onSaved = { Prefs.save(ctx, it); config = it; stack.clear(); stack.add(Route.Artists) },
                    onBack = { pop() },
                )
            }
        }
        if (stack.last() != Route.Now && stack.last() != Route.Settings) MiniPlayer(repo, pp) { push(Route.Now) }
    }
}
