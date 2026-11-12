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
    data class Found(val lines: List<LyricLine>, val english: Boolean) : OnlineResult
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
        val raw = "v2|${artist.trim().lowercase()}|${title.trim().lowercase()}|${album.trim().lowercase()}|$durSec"
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
                lrc.exists() -> LrcParser.parse(lrc.readText()).takeIf { it.isNotEmpty() }?.let { OnlineResult.Found(it, LyricsLang.looksEnglish(it.joinToString("\n") { l -> l.text })) }
                none.exists() && System.currentTimeMillis() - none.lastModified() < NOT_FOUND_TTL_MS -> OnlineResult.NotFound
                else -> null
            }
        }
        if (cached != null) return cached

        return try {
            val text = fetch(artist, title, album, durSec)
            withContext(Dispatchers.IO) {
                if (text != null) { lrc.writeText(text); none.delete() }
                else if (durSec > 0) { none.writeText("0") }
                Unit
            }
            val lines = text?.let { LrcParser.parse(it) }.orEmpty()
            if (lines.isNotEmpty()) OnlineResult.Found(lines, LyricsLang.looksEnglish(lines.joinToString("\n") { it.text })) else OnlineResult.NotFound
        } catch (e: CancellationException) { throw e } catch (e: Exception) { OnlineResult.Failed }
    }

    /**
     * Best synced LRC text, or null when LRCLIB has no usable match. Throws on network errors.
     * Only versions whose length matches the track are considered, and an English version is preferred
     * (the same song is sometimes listed in several languages).
     */
    private suspend fun fetch(artist: String, title: String, album: String, durSec: Int): String? {
        data class Cand(val text: String, val diff: Double, val english: Boolean)
        val cands = ArrayList<Cand>()
        fun consider(o: com.google.gson.JsonObject) {
            val s = o.get("syncedLyrics")?.takeIf { !it.isJsonNull }?.asString ?: return
            if (s.isBlank()) return
            val d = o.get("duration")?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0
            val diff = if (durSec > 0 && d > 0) kotlin.math.abs(d - durSec) else 0.0
            if (durSec > 0 && d > 0 && diff > DUR_TOLERANCE) return
            cands += Cand(s, diff, LyricsLang.looksEnglish(LrcParser.parse(s).joinToString("\n") { it.text }))
        }

        // 1) exact match
        val get = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("artist_name", artist).addQueryParameter("track_name", title)
            .apply { if (album.isNotBlank()) addQueryParameter("album_name", album) }
            .apply { if (durSec > 0) addQueryParameter("duration", durSec.toString()) }.build()
        call(get.toString()).use { r ->
            if (r.isSuccessful) consider(JsonParser.parseString(r.body?.string().orEmpty()).asJsonObject)
            else if (r.code != 404) throw IOException("HTTP ${r.code}")
        }
        if (cands.any { it.english }) return cands.filter { it.english }.minByOrNull { it.diff }!!.text

        // 2) wider search: may list other versions (including an English one) of the same song
        val search = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title).addQueryParameter("artist_name", artist).build()
        call(search.toString()).use { r ->
            if (r.isSuccessful) { for (e in JsonParser.parseString(r.body?.string().orEmpty()).asJsonArray) consider(e.asJsonObject) }
            else if (r.code != 404) throw IOException("HTTP ${r.code}")
        }
        return (cands.filter { it.english }.minByOrNull { it.diff } ?: cands.minByOrNull { it.diff })?.text
    }

    /** Stable fingerprint of a lyric set (used to avoid offering the same version twice). */
    fun signature(lines: List<LyricLine>): Int = lines.joinToString("\n") { "${it.timeMs}|${it.text}" }.hashCode()

    /**
     * "Refresh lyrics": looks for a DIFFERENT version than those already shown (`exclude` = their signatures).
     * Sources, in order of preference: every other length-matched LRCLIB version, then NetEase Cloud Music.
     * English versions win; the chosen one replaces the saved copy, so it is used from then on.
     */
    suspend fun refresh(artist: String, title: String, album: String, durationMs: Long, exclude: Set<Int>): OnlineResult {
        val durSec = (durationMs / 1000).toInt()
        data class C(val lines: List<LyricLine>, val raw: String, val diff: Double, val english: Boolean)
        val all = ArrayList<C>()
        fun add(raw: String, diff: Double) {
            val lines = LrcParser.parse(raw)
            if (lines.size < 4 || signature(lines) in exclude) return
            all += C(lines, raw, diff, LyricsLang.looksEnglish(lines.joinToString("\n") { it.text }))
        }
        var anySuccess = false
        try {
            fun lr(o: com.google.gson.JsonObject) {
                val sl = o.get("syncedLyrics")?.takeIf { !it.isJsonNull }?.asString ?: return
                val d = o.get("duration")?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0
                val diff = if (durSec > 0 && d > 0) kotlin.math.abs(d - durSec) else 0.0
                if (durSec > 0 && d > 0 && diff > DUR_TOLERANCE) return
                add(sl, diff)
            }
            val get = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
                .addQueryParameter("artist_name", artist).addQueryParameter("track_name", title)
                .apply { if (album.isNotBlank()) addQueryParameter("album_name", album) }
                .apply { if (durSec > 0) addQueryParameter("duration", durSec.toString()) }.build()
            call(get.toString()).use { r -> if (r.isSuccessful) { anySuccess = true; lr(JsonParser.parseString(r.body?.string().orEmpty()).asJsonObject) } }
            val search = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
                .addQueryParameter("track_name", title).addQueryParameter("artist_name", artist).build()
            call(search.toString()).use { r -> if (r.isSuccessful) { anySuccess = true; for (e in JsonParser.parseString(r.body?.string().orEmpty()).asJsonArray) lr(e.asJsonObject) } }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { }

        // Second source: NetEase Cloud Music (best effort; any failure is simply ignored).
        try {
            val q = "https://music.163.com/api/search/get".toHttpUrl().newBuilder()
                .addQueryParameter("s", "$artist $title").addQueryParameter("type", "1").addQueryParameter("limit", "6").build()
            val ids = ArrayList<Long>()
            call(q.toString(), referer = "https://music.163.com/").use { r ->
                if (r.isSuccessful) {
                    anySuccess = true
                    val songs = JsonParser.parseString(r.body?.string().orEmpty()).asJsonObject.getAsJsonObject("result")?.getAsJsonArray("songs")
                    if (songs != null) for (e in songs) {
                        val o = e.asJsonObject
                        val d = (o.get("duration")?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0) / 1000.0
                        if (durSec > 0 && d > 0 && kotlin.math.abs(d - durSec) > DUR_TOLERANCE) continue
                        ids += o.get("id").asLong
                    }
                }
            }
            for (id in ids.take(3)) {
                call("https://music.163.com/api/song/lyric?id=$id&lv=1", referer = "https://music.163.com/").use { r ->
                    if (r.isSuccessful) {
                        val raw = JsonParser.parseString(r.body?.string().orEmpty()).asJsonObject.getAsJsonObject("lrc")?.get("lyric")?.takeIf { !it.isJsonNull }?.asString
                        if (!raw.isNullOrBlank()) add(raw, 1.5)
                    }
                }
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { }

        val best = all.filter { it.english }.minByOrNull { it.diff } ?: all.minByOrNull { it.diff }
        if (best == null) return if (anySuccess) OnlineResult.NotFound else OnlineResult.Failed
        withContext(Dispatchers.IO) { File(dir, "${key(artist, title, album, durSec)}.lrc").writeText(best.raw) }
        return OnlineResult.Found(best.lines, best.english)
    }

    private suspend fun call(url: String, referer: String? = null): Response = suspendCancellableCoroutine { cont ->
        val c = http.newCall(Request.Builder().url(url).header("User-Agent", UA).apply { if (referer != null) header("Referer", referer) }.build())
        cont.invokeOnCancellation { c.cancel() }
        c.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) { if (cont.isActive) cont.resume(response) else response.close() }
        })
    }

    companion object {
        private const val UA = "OLEDTV-Music/1.0 (https://github.com/TH-MS/OLEDTV-MUSIC)"
        private const val DUR_TOLERANCE = 3.0
        private const val NOT_FOUND_TTL_MS = 30L * 24 * 3600 * 1000
        @Volatile private var inst: OnlineLyrics? = null
        fun get(ctx: Context): OnlineLyrics = inst ?: synchronized(this) { inst ?: OnlineLyrics(ctx).also { inst = it } }
    }
}


/** Very small language sniffing: is a block of lyrics mostly English? (Common English words vs. everything else.) */
object LyricsLang {
    private val common = setOf(
        "the", "and", "you", "your", "i", "i'm", "i'll", "i've", "my", "we", "it", "it's", "is", "of", "to", "that", "this",
        "with", "for", "are", "was", "be", "not", "but", "just", "can", "don't", "all", "have", "from", "they", "what",
        "when", "will", "there", "so", "if", "one", "like", "know", "got", "baby", "love", "me", "he", "she", "her", "him",
        "up", "out", "back", "now", "see", "way", "oh", "yeah", "come", "go", "need", "want", "never", "ever", "been",
    )
    private val split = Regex("[^\\p{L}']+")

    private fun ratio(text: String): Pair<Double, Int> {
        val w = text.lowercase().split(split).filter { it.isNotEmpty() }
        if (w.isEmpty()) return 0.0 to 0
        return w.count { it in common }.toDouble() / w.size to w.size
    }

    fun looksEnglish(text: String): Boolean { val (r, n) = ratio(text); return n < 20 || r >= 0.17 }
    fun clearlyNonEnglish(text: String): Boolean { val (r, n) = ratio(text); return n >= 40 && r < 0.07 }
}
