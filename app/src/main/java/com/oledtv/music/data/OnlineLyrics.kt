package com.oledtv.music.data

import android.content.Context
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Outcome of an online synced-lyrics lookup. */
sealed interface OnlineResult {
    data class Found(val lines: List<LyricLine>) : OnlineResult
    data object NotFound : OnlineResult
    /** Network trouble: nothing is remembered, so the next play retries. */
    data object Failed : OnlineResult
}

/**
 * Looks up time-synced lyrics on LRCLIB (lrclib.net: free, no key) for songs that have none on the server.
 * Every answer is remembered on this device (found lyrics, and "nothing found" for 30 days), so a song is
 * never searched twice. Runs entirely off the main thread and is cancelled when the song is skipped.
 */
class OnlineLyrics private constructor(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "lyrics").apply { mkdirs() }
    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS)
        .build()

    private fun key(artist: String, title: String, album: String, durSec: Int): String {
        val raw = "${artist.trim().lowercase()}|${title.trim().lowercase()}|${album.trim().lowercase()}|$durSec"
        return MessageDigest.getInstance("SHA-1").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    suspend fun find(artist: String, title: String, album: String, durationMs: Long): OnlineResult {
        if (title.isBlank()) return OnlineResult.NotFound
        val durSec = (durationMs / 1000).toInt()
        val k = key(artist, title, album, durSec)
        val lrc = File(dir, "$k.lrc")
        val none = File(dir, "$k.none")
        val cached = withContext(Dispatchers.IO) {
            when {
                lrc.exists() -> LrcParser.parse(lrc.readText()).takeIf { it.isNotEmpty() }?.let { OnlineResult.Found(it) }
                none.exists() && System.currentTimeMillis() - none.lastModified() < NOT_FOUND_TTL_MS -> OnlineResult.NotFound
                else -> null
            }
        }
        if (cached != null) return cached

        return try {
            val text = fetch(artist, title, album, durSec)
            withContext(Dispatchers.IO) {
                if (text != null) { lrc.writeText(text); none.delete() } else none.writeText("0")
            }
            val lines = text?.let { LrcParser.parse(it) }.orEmpty()
            if (lines.isNotEmpty()) OnlineResult.Found(lines) else OnlineResult.NotFound
        } catch (e: CancellationException) { throw e } catch (e: Exception) { OnlineResult.Failed }
    }

    /** Synced LRC text, or null when LRCLIB has no usable match. Throws on network errors. */
    private suspend fun fetch(artist: String, title: String, album: String, durSec: Int): String? {
        // 1) exact match
        val get = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("artist_name", artist).addQueryParameter("track_name", title)
            .apply { if (album.isNotBlank()) addQueryParameter("album_name", album) }
            .apply { if (durSec > 0) addQueryParameter("duration", durSec.toString()) }.build()
        call(get.toString()).use { r ->
            if (r.isSuccessful) {
                val o = JsonParser.parseString(r.body?.string().orEmpty()).asJsonObject
                val s = o.get("syncedLyrics")?.takeIf { !it.isJsonNull }?.asString
                val d = o.get("duration")?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0
                if (!s.isNullOrBlank() && (durSec <= 0 || d <= 0 || kotlin.math.abs(d - durSec) <= DUR_TOLERANCE)) return s
            } else if (r.code != 404) throw IOException("HTTP ${r.code}")
        }
        // 2) looser search, still insisting on a matching duration so we never show another version's timings
        val search = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title).addQueryParameter("artist_name", artist).build()
        call(search.toString()).use { r ->
            if (!r.isSuccessful) { if (r.code == 404) return null else throw IOException("HTTP ${r.code}") }
            val arr = JsonParser.parseString(r.body?.string().orEmpty()).asJsonArray
            var best: String? = null
            var bestDiff = Double.MAX_VALUE
            for (e in arr) {
                val o = e.asJsonObject
                val s = o.get("syncedLyrics")?.takeIf { !it.isJsonNull }?.asString ?: continue
                if (s.isBlank()) continue
                val d = o.get("duration")?.takeIf { !it.isJsonNull }?.asDouble ?: continue
                val diff = if (durSec > 0) kotlin.math.abs(d - durSec) else 0.0
                if (durSec > 0 && diff > DUR_TOLERANCE) continue
                if (diff < bestDiff) { best = s; bestDiff = diff }
            }
            return best
        }
    }

    private suspend fun call(url: String): Response = suspendCancellableCoroutine { cont ->
        val c = http.newCall(Request.Builder().url(url).header("User-Agent", UA).build())
        cont.invokeOnCancellation { c.cancel() }
        c.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) { if (cont.isActive) cont.resume(response) else response.close() }
        })
    }

    companion object {
        private const val UA = "OLEDTV-Music/1.0 (https://github.com/TH-MS/OLEDTV-MUSIC)"
        private const val DUR_TOLERANCE = 2.0
        private const val NOT_FOUND_TTL_MS = 30L * 24 * 3600 * 1000
        @Volatile private var inst: OnlineLyrics? = null
        fun get(ctx: Context): OnlineLyrics = inst ?: synchronized(this) { inst ?: OnlineLyrics(ctx).also { inst = it } }
    }
}
