package com.oledtv.music.data

import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

// ───────────────────────── Config & auth ─────────────────────────

data class ServerConfig(val url: String, val username: String, val password: String) {
    val baseUrl: String
        get() {
            var u = url.trim()
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
            if (!u.endsWith("/")) u += "/"
            return u
        }
}

/** Subsonic token auth: t = md5(password + salt). */
object SubsonicAuth {
    fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun randomSalt(): String {
        val b = ByteArray(8); SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    fun params(user: String, password: String, salt: String = randomSalt()): Map<String, String> = mapOf(
        "u" to user,
        "t" to md5(password + salt),
        "s" to salt,
        "v" to "1.16.1",
        "c" to "OLEDTVMusic",
        "f" to "json",
    )
}

// ───────────────────────── DTOs ─────────────────────────

interface SubsonicBody { val status: String?; val error: SubsonicError? }
data class SubsonicError(val code: Int?, val message: String?)
data class SubsonicResponse<T>(@SerializedName("subsonic-response") val response: T?)

data class PingBody(override val status: String?, override val error: SubsonicError?) : SubsonicBody

data class ArtistsBody(override val status: String?, override val error: SubsonicError?, val artists: ArtistsDto?) : SubsonicBody
data class ArtistsDto(val index: List<IndexDto>?)
data class IndexDto(val name: String?, val artist: List<ArtistDto>?)
data class ArtistDto(val id: String, val name: String, val albumCount: Int?, val coverArt: String?)

data class ArtistBody(override val status: String?, override val error: SubsonicError?, val artist: ArtistWithAlbums?) : SubsonicBody
data class ArtistWithAlbums(val id: String?, val name: String?, val album: List<AlbumDto>?)

data class AlbumList2Body(override val status: String?, override val error: SubsonicError?, val albumList2: AlbumList2Dto?) : SubsonicBody
data class AlbumList2Dto(val album: List<AlbumDto>?)

data class AlbumDto(
    val id: String, val name: String, val artist: String?, val artistId: String?,
    val year: Int?, val coverArt: String?, val songCount: Int?, val duration: Int?,
)

data class AlbumBody(override val status: String?, override val error: SubsonicError?, val album: AlbumWithSongs?) : SubsonicBody
data class AlbumWithSongs(val id: String?, val name: String?, val artist: String?, val year: Int?, val coverArt: String?, val song: List<SongDto>?)
data class SongDto(
    val id: String, val title: String, val album: String?, val artist: String?, val albumId: String?,
    val track: Int?, val discNumber: Int?, val duration: Int?, val coverArt: String?,
)

data class LyricsBody(override val status: String?, override val error: SubsonicError?, val lyrics: LegacyLyrics?) : SubsonicBody
data class LegacyLyrics(val artist: String?, val title: String?, val value: String?)

data class LyricsListBody(override val status: String?, override val error: SubsonicError?, val lyricsList: LyricsListDto?) : SubsonicBody
data class LyricsListDto(val structuredLyrics: List<StructuredLyrics>?)
data class StructuredLyrics(val lang: String?, val synced: Boolean?, val offset: Long?, val line: List<StructuredLine>?)
data class StructuredLine(val start: Long?, val value: String?)

// ───────────────────────── Retrofit interface ─────────────────────────

interface NavidromeApiService {
    @GET("rest/ping.view") suspend fun ping(): SubsonicResponse<PingBody>

    /** Navidrome returns Album Artists here. */
    @GET("rest/getArtists.view") suspend fun getArtists(): SubsonicResponse<ArtistsBody>

    @GET("rest/getArtist.view") suspend fun getArtist(@Query("id") id: String): SubsonicResponse<ArtistBody>

    @GET("rest/getAlbum.view") suspend fun getAlbum(@Query("id") id: String): SubsonicResponse<AlbumBody>

    @GET("rest/getAlbumList2.view")
    suspend fun getAlbumList2(
        @Query("type") type: String,
        @Query("size") size: Int,
        @Query("offset") offset: Int,
    ): SubsonicResponse<AlbumList2Body>

    /** OpenSubsonic: embedded / sidecar .lrc exposed as structured lyrics (synced when timestamps exist). */
    @GET("rest/getLyricsBySongId.view")
    suspend fun getLyricsBySongId(@Query("id") id: String): SubsonicResponse<LyricsListBody>

    /** Classic Subsonic fallback. */
    @GET("rest/getLyrics.view")
    suspend fun getLyrics(@Query("artist") artist: String, @Query("title") title: String): SubsonicResponse<LyricsBody>
}

// ───────────────────────── Lyrics models & LRC parser ─────────────────────────

data class LyricLine(val timeMs: Long, val text: String)

data class Lyrics(val synced: List<LyricLine> = emptyList(), val plain: String? = null) {
    val isEmpty get() = synced.isEmpty() && plain.isNullOrBlank()
    companion object { val EMPTY = Lyrics() }
}

object LrcParser {
    private val stamp = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

    fun parse(text: String): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        for (raw in text.lines()) {
            val matches = stamp.findAll(raw).toList()
            if (matches.isEmpty()) continue
            val body = raw.substring(matches.last().range.last + 1).trim()
            if (body.isEmpty()) continue
            for (m in matches) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val ms = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
                out += LyricLine(min * 60_000 + sec * 1_000 + ms, body)
            }
        }
        return out.sortedBy { it.timeMs }
    }
}

// ───────────────────────── Repository ─────────────────────────

class NavidromeRepository(val config: ServerConfig) {

    private val authInterceptor = Interceptor { chain ->
        val req = chain.request()
        val url = req.url.newBuilder().apply {
            SubsonicAuth.params(config.username, config.password).forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        chain.proceed(req.newBuilder().url(url).build())
    }

    private val http = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val api: NavidromeApiService = Retrofit.Builder()
        .baseUrl(config.baseUrl)
        .client(http)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(NavidromeApiService::class.java)

    // Small in-memory caches so back-navigation is instant on low-RAM hardware.
    private var artistsCache: List<ArtistDto>? = null
    private val albumsCache = HashMap<String, List<AlbumDto>>()
    private val songsCache = HashMap<String, List<SongDto>>()

    private fun <T : SubsonicBody> SubsonicResponse<T>.unwrap(): T {
        val body = response ?: throw IOException("Empty server response")
        if (body.status != "ok") throw IOException(body.error?.message ?: "Server error")
        return body
    }

    suspend fun ping() { api.ping().unwrap() }

    /** Album Artists only, alphabetical. */
    suspend fun artists(): List<ArtistDto> {
        artistsCache?.let { return it }
        val primary = api.getArtists().unwrap().artists?.index.orEmpty()
            .flatMap { it.artist.orEmpty() }
        val result = primary.ifEmpty { artistsFromAlbumList() }
            .sortedBy { it.name.lowercase() }
        artistsCache = result
        return result
    }

    /** Fallback built from getAlbumList2 grouped by album artist. */
    private suspend fun artistsFromAlbumList(): List<ArtistDto> {
        val albums = ArrayList<AlbumDto>()
        var offset = 0
        while (true) {
            val page = api.getAlbumList2("alphabeticalByArtist", 500, offset).unwrap().albumList2?.album.orEmpty()
            albums += page
            if (page.size < 500) break
            offset += 500
        }
        return albums.filter { it.artistId != null && it.artist != null }
            .groupBy { it.artistId!! }
            .map { (id, list) -> ArtistDto(id, list.first().artist!!, list.size, null) }
    }

    /** Albums of one album artist sorted by release year ascending (unknown year last). */
    suspend fun albumsOf(artistId: String): List<AlbumDto> {
        albumsCache[artistId]?.let { return it }
        val list = api.getArtist(artistId).unwrap().artist?.album.orEmpty()
            .sortedWith(compareBy<AlbumDto> { it.year ?: Int.MAX_VALUE }.thenBy { it.name.lowercase() })
        albumsCache[artistId] = list
        return list
    }

    suspend fun songsOf(albumId: String): List<SongDto> {
        songsCache[albumId]?.let { return it }
        val list = api.getAlbum(albumId).unwrap().album?.song.orEmpty()
            .sortedWith(compareBy<SongDto> { it.discNumber ?: 1 }.thenBy { it.track ?: 0 })
        songsCache[albumId] = list
        return list
    }

    suspend fun lyrics(songId: String, artist: String, title: String): Lyrics {
        try {
            val all = api.getLyricsBySongId(songId).unwrap().lyricsList?.structuredLyrics.orEmpty()
            val synced = all.firstOrNull { it.synced == true && !it.line.isNullOrEmpty() }
            if (synced != null) {
                val off = synced.offset ?: 0L
                return Lyrics(synced = synced.line.orEmpty().map { LyricLine((it.start ?: 0L) + off, it.value.orEmpty().trim()) }
                    .filter { it.text.isNotEmpty() }.sortedBy { it.timeMs })
            }
            val plain = all.firstOrNull { !it.line.isNullOrEmpty() }
            if (plain != null) return Lyrics(plain = plain.line.orEmpty().joinToString("\n") { it.value.orEmpty() }.trim())
        } catch (e: Exception) { if (e is CancellationException) throw e }

        try {
            val v = api.getLyrics(artist, title).unwrap().lyrics?.value
            if (!v.isNullOrBlank()) {
                val parsed = LrcParser.parse(v)
                return if (parsed.isNotEmpty()) Lyrics(synced = parsed) else Lyrics(plain = v.trim())
            }
        } catch (e: Exception) { if (e is CancellationException) throw e }
        return Lyrics.EMPTY
    }

    // URL builders. Cover art uses a deterministic salt so Coil's memory/disk cache keys stay stable.
    private fun urlFor(path: String, params: Map<String, String>, extra: Map<String, String>): String {
        val b = config.baseUrl.toHttpUrl().newBuilder().addPathSegments(path)
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        extra.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build().toString()
    }

    fun streamUrl(songId: String): String =
        urlFor("rest/stream.view", SubsonicAuth.params(config.username, config.password), mapOf("id" to songId))

    fun coverArtUrl(coverArtId: String?, sizePx: Int): String? {
        if (coverArtId.isNullOrBlank()) return null
        val salt = SubsonicAuth.md5(config.username).take(12)
        return urlFor("rest/getCoverArt.view", SubsonicAuth.params(config.username, config.password, salt),
            mapOf("id" to coverArtId, "size" to sizePx.toString()))
    }
}
