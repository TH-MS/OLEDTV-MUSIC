package com.oledtv.music.phone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.oledtv.music.data.friendlyError
import kotlinx.coroutines.CancellationException

val OffWhite = Color(0xFFE6E6E6)
val Dim = Color(0xFF8A8A8A)
val Dimmer = Color(0xFF353535)
val Accent = Color(0xFF7FB2FF)
val Panel = Color(0xFF141414)

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Err(val message: String) : Load<Nothing>
}

/** Loads [fetch] once per [key]/[reload]; starts with [cached] when the repository already has it. */
@Composable
fun <T> rememberLoad(key: Any?, cached: T?, reload: Int, fetch: suspend () -> T): Load<T> {
    var state by remember(key, reload) { mutableStateOf<Load<T>>(if (cached != null) Load.Ok(cached) else Load.Loading) }
    LaunchedEffect(key, reload) {
        if (state is Load.Ok) return@LaunchedEffect
        state = try { Load.Ok(fetch()) } catch (e: CancellationException) { throw e } catch (e: Exception) { Load.Err(friendlyError(e)) }
    }
    return state
}

fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun Cover(url: String?, letter: String, modifier: Modifier, radius: Dp = 8.dp) {
    Box(modifier.clip(RoundedCornerShape(radius)).background(Color(0xFF1A1A1A)), contentAlignment = Alignment.Center) {
        Text(letter.take(1).uppercase(), color = Color(0xFF555555), fontSize = 28.sp)
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

enum class GlyphKind { Play, Pause, Next, Prev, Shuffle, Back, More }

@Composable
fun Glyph(kind: GlyphKind, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.09f
        fun tri(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) =
            drawPath(Path().apply { moveTo(x1, y1); lineTo(x2, y2); lineTo(x3, y3); close() }, color)
        when (kind) {
            GlyphKind.Play -> tri(w * .25f, h * .15f, w * .85f, h * .5f, w * .25f, h * .85f)
            GlyphKind.Pause -> {
                drawRect(color, Offset(w * .22f, h * .15f), Size(w * .18f, h * .7f))
                drawRect(color, Offset(w * .60f, h * .15f), Size(w * .18f, h * .7f))
            }
            GlyphKind.Next -> {
                tri(w * .15f, h * .2f, w * .65f, h * .5f, w * .15f, h * .8f)
                drawRect(color, Offset(w * .70f, h * .2f), Size(w * .13f, h * .6f))
            }
            GlyphKind.Prev -> {
                tri(w * .85f, h * .2f, w * .35f, h * .5f, w * .85f, h * .8f)
                drawRect(color, Offset(w * .17f, h * .2f), Size(w * .13f, h * .6f))
            }
            GlyphKind.Shuffle -> {
                drawLine(color, Offset(w * .1f, h * .75f), Offset(w * .8f, h * .25f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * .1f, h * .25f), Offset(w * .38f, h * .45f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * .55f, h * .58f), Offset(w * .8f, h * .75f), sw, StrokeCap.Round)
                tri(w * .93f, h * .25f, w * .73f, h * .1f, w * .73f, h * .4f)
                tri(w * .93f, h * .75f, w * .73f, h * .6f, w * .73f, h * .9f)
            }
            GlyphKind.Back -> {
                drawLine(color, Offset(w * .65f, h * .15f), Offset(w * .3f, h * .5f), sw * 1.3f, StrokeCap.Round)
                drawLine(color, Offset(w * .3f, h * .5f), Offset(w * .65f, h * .85f), sw * 1.3f, StrokeCap.Round)
            }
            GlyphKind.More -> {
                for (f in listOf(.2f, .5f, .8f)) drawCircle(color, w * .07f, Offset(w * .5f, h * f))
            }
        }
    }
}
