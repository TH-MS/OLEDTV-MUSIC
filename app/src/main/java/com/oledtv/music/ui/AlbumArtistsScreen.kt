@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.oledtv.music.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import kotlin.math.PI
import kotlin.math.sin
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.oledtv.music.data.ArtistDto
import com.oledtv.music.data.NavidromeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

// ───────────────────────── Shared UI building blocks ─────────────────────────

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Err(val message: String) : Load<Nothing>
}

/** Every interactive element in the app uses this: visible white border + scale on focus (no costly glow). */
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    containerColor: Color = Color(0xFF121212),
    focusedContainerColor: Color = Color(0xFF2A2A2A),
    showFocus: Boolean = true,
    playing: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = shape, focusedShape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = containerColor,
            contentColor = Color(0xFFE6E6E6),
            focusedContainerColor = focusedContainerColor,
            focusedContentColor = Color(0xFFE6E6E6),
            pressedContainerColor = focusedContainerColor,
            pressedContentColor = Color(0xFFE6E6E6),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (showFocus) 1.08f else 1f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = if (showFocus && !playing) Border(BorderStroke(3.dp, Color(0xFFE6E6E6)), shape = shape) else Border.None,
        ),
    ) {
        // Explicit content colour so text never inherits an unexpected (invisible) colour.
        CompositionLocalProvider(LocalContentColor provides Color(0xFFE6E6E6)) { content() }
        // Currently playing artist / album / track: a pulsating colour travels around the edge.
        if (playing) Box(Modifier.matchParentSize().nowPlayingBorder(shape))
    }
}

private val NpA = Color(0xFF00E5FF)
private val NpB = Color(0xFFFF4DFF)

/** Pulsating, colour-cycling border. Animation state is only read in the draw phase (no recomposition per frame). */
@Composable
fun Modifier.nowPlayingBorder(shape: Shape, width: Dp = 4.dp): Modifier {
    val t = rememberInfiniteTransition(label = "np")
    val phase = t.animateFloat(0f, 1f, infiniteRepeatable(tween(3600, easing = LinearEasing)), label = "phase")
    val pulse = t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    return this.drawWithContent {
        drawContent()
        val p = phase.value
        val n = 24
        val stops = Array(n + 1) { i ->
            val f = i / n.toFloat()
            val u = ((f - p) % 1f + 1f) % 1f
            f to lerp(NpA, NpB, (sin(2.0 * PI * u).toFloat() + 1f) / 2f)
        }
        drawOutline(
            outline = shape.createOutline(size, layoutDirection, this),
            brush = Brush.sweepGradient(*stops),
            alpha = pulse.value,
            style = Stroke(width.toPx()),
        )
    }
}

/** Downscaled, cache-keyed cover image. [sizePx] keeps bitmaps small for 1.5 GB devices. */
@Composable
fun CoverImage(
    url: String?,
    cacheKey: String,
    sizePx: Int,
    fallbackLetter: String,
    modifier: Modifier = Modifier,
) {
    if (url == null) {
        Box(modifier.background(Color(0xFF1A1A1A)), contentAlignment = Alignment.Center) {
            Text(fallbackLetter.take(1).uppercase(), fontSize = 40.sp, color = Color(0xFF666666), fontWeight = FontWeight.Bold)
        }
        return
    }
    val ctx = LocalContext.current
    val request = remember(url, cacheKey, sizePx) {
        ImageRequest.Builder(ctx)
            .data(url)
            .size(sizePx)
            .precision(Precision.INEXACT)
            .memoryCacheKey("$cacheKey@$sizePx")
            .diskCacheKey("$cacheKey@$sizePx")
            .crossfade(false)
            .build()
    }
    AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
}

@Composable
fun StatusPane(message: String, onRetry: (() -> Unit)? = null) {
    val fr = remember { FocusRequester() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, fontSize = 22.sp, textAlign = TextAlign.Center)
        if (onRetry != null) {
            Spacer(Modifier.padding(12.dp))
            FocusSurface(onRetry, Modifier.focusRequester(fr)) { Text("Retry", Modifier.padding(horizontal = 28.dp, vertical = 12.dp), fontSize = 20.sp) }
            LaunchedEffect(Unit) { fr.requestSafely() }
        }
    }
}

suspend fun FocusRequester.requestSafely() {
    try { requestFocus() } catch (e: IllegalStateException) { /* not attached yet */ } catch (e: CancellationException) { throw e }
}

fun formatMs(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

// ───────────────────────── Album Artists (root menu) ─────────────────────────

@Composable
fun AlbumArtistsScreen(
    repo: NavidromeRepository,
    gridState: LazyGridState,
    lastIndex: MutableIntState,
    columns: Int,
    playingArtistId: String?,
    onArtistClick: (ArtistDto) -> Unit,
    onSettings: () -> Unit,
    onNowPlaying: (() -> Unit)?,
) {
    var state by remember { mutableStateOf<Load<List<ArtistDto>>>(Load.Loading) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(repo, reload) {
        state = Load.Loading
        state = try { Load.Ok(repo.artists()) } catch (e: CancellationException) { throw e } catch (e: Exception) { Load.Err(e.message ?: "Network error") }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("OLED MUSIC PLAYER", Modifier.weight(1f), fontSize = 32.sp, fontWeight = FontWeight.Bold)
            if (onNowPlaying != null) {
                FocusSurface(onNowPlaying) { Text("Now Playing", Modifier.padding(horizontal = 20.dp, vertical = 10.dp), fontSize = 18.sp) }
                Spacer(Modifier.width(16.dp))
            }
            FocusSurface(onSettings) { Text("Settings", Modifier.padding(horizontal = 20.dp, vertical = 10.dp), fontSize = 18.sp) }
        }
        Spacer(Modifier.padding(8.dp))

        when (val s = state) {
            is Load.Loading -> StatusPane("Loading artists…")
            is Load.Err -> StatusPane(s.message) { reload++ }
            is Load.Ok -> ArtistGrid(repo, s.value, gridState, lastIndex, onArtistClick, columns, playingArtistId)
        }
    }
}

@Composable
private fun ArtistGrid(
    repo: NavidromeRepository,
    artists: List<ArtistDto>,
    gridState: LazyGridState,
    lastIndex: MutableIntState,
    onClick: (ArtistDto) -> Unit,
    columns: Int,
    playingArtistId: String?,
) {
    if (artists.isEmpty()) { StatusPane("No album artists found on this server."); return }
    val startIndex = remember { lastIndex.intValue.coerceIn(0, artists.lastIndex) }
    val startFocus = remember { FocusRequester() }

    LazyVerticalGrid(
        columns = if (columns > 0) GridCells.Fixed(columns) else GridCells.Adaptive(170.dp),
        state = gridState,
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(artists, key = { _, a -> a.id }) { i, artist ->
            FocusSurface(
                onClick = { onClick(artist) },
                playing = artist.id == playingArtistId,
                modifier = Modifier
                    .onFocusChanged { if (it.hasFocus) lastIndex.intValue = i }
                    .then(if (i == startIndex) Modifier.focusRequester(startFocus) else Modifier),
            ) {
                Column {
                    CoverImage(
                        url = repo.coverArtUrl(artist.coverArt, 400),
                        cacheKey = "ar-${artist.id}",
                        sizePx = 400,
                        fallbackLetter = artist.name,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    )
                    Text(
                        artist.name,
                        Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }

    LaunchedEffect(artists) {
        gridState.scrollToItem(startIndex)
        delay(80)
        startFocus.requestSafely()
    }
}
