@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.oledtv.music.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.State
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ───────────────────────── Shared UI building blocks ─────────────────────────

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Err(val message: String) : Load<Nothing>
}

/** Every interactive element in the app uses this: visible white border + scale on focus (no costly glow). */
/** Light-grey floor for the "breathing" UI colours on Now Playing (white pulses down to ~#A0A0A0). */
private const val BREATH_FLOOR = 0.627f      // 0xA0 / 0xFF
private const val READABLE_MIN = 0.4f        // 0x66: dim elements never go below this

/** The single shared clock: 0 = brightest, 1 = dimmest. Every UI element reads this same value. */
val LocalBreath = compositionLocalOf<State<Float>?> { null }
/** 1 = focus rings visible, 0 = faded away after 30 s without input (Now Playing only). */
val LocalRingFade = compositionLocalOf<State<Float>?> { null }

internal val UiWhite = Color(0xFFE6E6E6)

/**
 * One slow sine-like ease in/out loop (9 s each way, ~18 s per full cycle). The curve flattens at both ends, so the
 * colour lingers at the brightest and dimmest points and never changes in a mechanical, noticeable way.
 */
@Composable
fun rememberBreath(): State<Float> {
    val t = rememberInfiniteTransition(label = "breath")
    return t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(9_000, easing = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)), RepeatMode.Reverse),
        label = "breath",
    )
}

/**
 * Lowest alpha an element of colour [base] may reach. Pure/near white goes down by the full 37% (#FFFFFF -> #A0A0A0,
 * #E6E6E6 -> #909090). Dimmer elements move less so they stay readable (nothing is ever pushed below #666666,
 * and anything already darker than that does not move at all). No element is ever made brighter than it is today.
 * Because the UI sits on pure black, alpha x colour is exactly the same as scaling the colour itself, and since
 * every element uses the same clock, relationships never invert (e.g. the played part of the progress bar is
 * always at least as bright as the remaining part).
 */
private fun minAlphaFor(base: Color): Float {
    val v = base.red
    if (v <= 0f) return 1f
    val floor = maxOf(v * BREATH_FLOOR, minOf(v, READABLE_MIN))
    return floor / v
}

/** Makes this element breathe with the shared clock (draw phase only - no recomposition per frame). */
@Composable
fun Modifier.breathe(base: Color): Modifier {
    val breath = LocalBreath.current ?: return this
    val minA = remember(base) { minAlphaFor(base) }
    if (minA >= 0.999f) return this
    return this.graphicsLayer {
        alpha = 1f - breath.value * (1f - minA)
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }
}

/** Focus ring drawn in the draw phase: breathes with the shared clock and fades out when the ring-fade is active. */
@Composable
fun Modifier.focusRing(visible: Boolean, shape: Shape, width: Dp = 3.dp, base: Color = UiWhite): Modifier {
    if (!visible) return this
    val breath = LocalBreath.current
    val fade = LocalRingFade.current
    val minA = remember(base) { minAlphaFor(base) }
    return this.drawWithContent {
        drawContent()
        val a = (1f - (breath?.value ?: 0f) * (1f - minA)) * (fade?.value ?: 1f)
        if (a > 0.01f) {
            drawOutline(shape.createOutline(size, layoutDirection, this), base, alpha = a, style = Stroke(width.toPx()))
        }
    }
}

/** Every interactive element in the app uses this: visible ring + scale on focus (no costly glow). */
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
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.hasFocus },
        shape = ClickableSurfaceDefaults.shape(shape = shape, focusedShape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = containerColor,
            contentColor = UiWhite,
            focusedContainerColor = containerColor, // the focus fill is drawn below so it can fade with the ring
            focusedContentColor = UiWhite,
            pressedContainerColor = containerColor,
            pressedContentColor = UiWhite,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (showFocus) 1.08f else 1f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border.None),
    ) {
        // Focus fill (the lighter pill): drawn in the draw phase so it fades out together with the ring.
        if (focused && focusedContainerColor != containerColor) {
            val fade = LocalRingFade.current
            Box(Modifier.matchParentSize().drawWithContent {
                val a = fade?.value ?: 1f
                if (a > 0.01f) drawOutline(shape.createOutline(size, layoutDirection, this), focusedContainerColor, alpha = a)
                drawContent()
            })
        }
        // Explicit content colour so text never inherits an unexpected (invisible) colour.
        CompositionLocalProvider(LocalContentColor provides UiWhite) { content() }
        // Currently playing artist / album / track: a pulsating colour travels around the edge.
        if (playing) Box(Modifier.matchParentSize().nowPlayingBorder(shape))
        else if (focused && showFocus) Box(Modifier.matchParentSize().focusRing(true, shape, 3.dp))
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

/** Waits for layout (one frame), then focuses - retrying briefly if the target is not attached yet. */
suspend fun FocusRequester.requestWhenReady() {
    withFrameNanos { }
    repeat(8) {
        try { requestFocus(); return } catch (e: IllegalStateException) { /* not attached yet */ }
        delay(30)
    }
}

/** "Loading…" is only shown if it takes noticeably long, so quick transitions never flash it. */
@Composable
fun LoadingPane(message: String) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(400); show = true }
    if (show) StatusPane(message)
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
    focusArtistId: String?,
    onFocusArtist: (String) -> Unit,
    onArtistClick: (ArtistDto) -> Unit,
    onSettings: () -> Unit,
    onNowPlaying: (() -> Unit)?,
) {
    var state by remember { mutableStateOf<Load<List<ArtistDto>>>(repo.cachedArtists()?.let { Load.Ok(it) } ?: Load.Loading) }
    var reload by remember { mutableIntStateOf(0) }
    // Target of "hold Up": the Now Playing button (or Settings when nothing has been played yet).
    val topFocus = remember { FocusRequester() }

    LaunchedEffect(repo, reload) {
        if (state is Load.Err) state = Load.Loading
        state = try { Load.Ok(repo.artists()) } catch (e: CancellationException) { throw e } catch (e: Exception) { Load.Err(e.message ?: "Network error") }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("OLED MUSIC PLAYER", Modifier.weight(1f), fontSize = 32.sp, fontWeight = FontWeight.Bold)
            if (onNowPlaying != null) {
                FocusSurface(onNowPlaying, Modifier.focusRequester(topFocus)) { Text("Now Playing", Modifier.padding(horizontal = 20.dp, vertical = 10.dp), fontSize = 18.sp) }
                Spacer(Modifier.width(16.dp))
            }
            FocusSurface(onSettings, if (onNowPlaying == null) Modifier.focusRequester(topFocus) else Modifier) { Text("Settings", Modifier.padding(horizontal = 20.dp, vertical = 10.dp), fontSize = 18.sp) }
        }
        Spacer(Modifier.padding(8.dp))

        when (val s = state) {
            is Load.Loading -> LoadingPane("Loading artists…")
            is Load.Err -> StatusPane(s.message) { reload++ }
            is Load.Ok -> ArtistGrid(repo, s.value, gridState, lastIndex, onArtistClick, columns, playingArtistId, topFocus, focusArtistId, onFocusArtist)
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
    topFocus: FocusRequester,
    focusArtistId: String?,
    onFocusArtist: (String) -> Unit,
) {
    if (artists.isEmpty()) { StatusPane("No album artists found on this server."); return }
    // Returning to this screen: land on the artist you last picked/focused (or the playing artist when you
    // arrive via Now Playing), with its row centred where possible. First visit: the top.
    val focusIdx = artists.indexOfFirst { it.id == focusArtistId }
    val startIndex = remember { if (focusIdx >= 0) focusIdx else lastIndex.intValue.coerceIn(0, artists.lastIndex) }
    // One requester per tile so we can focus any tile (the first shown, or the very last one).
    val requesters = remember(artists) { List(artists.size) { FocusRequester() } }
    val startFocus = requesters[startIndex]
    val scope = rememberCoroutineScope()

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
                    .onFocusChanged { if (it.hasFocus) { lastIndex.intValue = i; onFocusArtist(artist.id) } }
                    .focusRequester(requesters[i]),
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
        gridState.scrollToItem(startIndex)   // remeasures synchronously, so layout info is valid right after
        if (focusIdx >= 0) {
            // Centre that artist's row vertically. Near the top/bottom of the list the scroll simply stops at
            // the edge, so it is centred only where that is actually possible.
            fun find() = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == startIndex }
            var info = find()
            if (info == null) { withFrameNanos { }; info = find() }
            if (info != null) {
                val viewport = gridState.layoutInfo.viewportSize.height
                val desiredTop = (viewport - info.size.height) / 2
                gridState.scrollBy((info.offset.y - desiredTop).toFloat())
            }
        }
        startFocus.requestWhenReady()
    }

    // Quietly pre-load the albums of the artist you are resting on, so opening it is instant.
    LaunchedEffect(artists) {
        snapshotFlow { lastIndex.intValue }.collectLatest { idx ->
            delay(250)
            val a = artists.getOrNull(idx) ?: return@collectLatest
            try { repo.albumsOf(a.id) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }
}
