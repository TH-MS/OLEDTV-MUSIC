package com.oledtv.music.data

import java.text.Normalizer
import java.util.Locale

/*
 * Tolerance for messy tags (wrong / missing / conflicting disc, track and artist values).
 * Clean metadata passes through unchanged; these rules only act when the tags are unusable.
 */

private val COMBINING = Regex("\\p{M}+")
private val LETTER_MAP = mapOf('ø' to "o", 'æ' to "ae", 'œ' to "oe", 'ß' to "ss", 'đ' to "d", 'ł' to "l", 'þ' to "th", 'ð' to "d")

/** Lower-case, accent-free text, so "Édith", "Øyvind" and "Ólafur" sort next to E, O and O. */
private fun fold(s: String): String {
    val sb = StringBuilder()
    for (c in s.lowercase(Locale.ROOT)) sb.append(LETTER_MAP[c] ?: c.toString())
    return COMBINING.replace(Normalizer.normalize(sb, Normalizer.Form.NFKD), "")
}

/** Sort key that ignores a leading "The " and accents (The Police sits under P, Édith Piaf under E). */
fun artistSortKey(name: String): String {
    val n = fold(name.trim())
    return if (n.startsWith("the ") && n.length > 4) n.removePrefix("the ").trim() else n
}

fun albumSortKey(name: String): String = fold(name.trim())

private fun artistKey(a: String?): String = fold(a.orEmpty()).filter { it.isLetterOrDigit() }

/** Same artist ignoring case, spacing, punctuation, accents and Unicode form ("Mk.gee" == "mk gee "). */
fun sameArtist(a: String?, b: String?): Boolean {
    val ka = artistKey(a)
    val kb = artistKey(b)
    if (ka.isEmpty() || kb.isEmpty()) return !a.isNullOrBlank() && a.trim().lowercase(Locale.ROOT) == b?.trim()?.lowercase(Locale.ROOT)
    return ka == kb
}

/** The artist shared by every track when it differs from the album artist; otherwise null. Blank track artists count as the album artist. */
fun uniformTrackArtist(songs: List<SongDto>, albumArtist: String?): String? {
    val filled = songs.map { it.artist?.takeIf { a -> a.isNotBlank() } ?: albumArtist }
    val first = filled.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
    if (sameArtist(first, albumArtist)) return null
    return if (filled.all { sameArtist(it, first) }) first else null
}

class TrackRow(val song: SongDto, val disc: Int, val header: String?, val num: String)

private fun naturalCompare(a: String, b: String): Int {
    val re = Regex("\\d+|\\D+")
    val ra = re.findAll(a.lowercase(Locale.ROOT)).map { it.value }.toList()
    val rb = re.findAll(b.lowercase(Locale.ROOT)).map { it.value }.toList()
    for (i in 0 until minOf(ra.size, rb.size)) {
        val x = ra[i]
        val y = rb[i]
        val c = if (x[0].isDigit() && y[0].isDigit()) {
            val xt = x.trimStart('0'); val yt = y.trimStart('0')
            if (xt.length != yt.length) xt.length.compareTo(yt.length) else xt.compareTo(yt)
        } else x.compareTo(y)
        if (c != 0) return c
    }
    return ra.size.compareTo(rb.size)
}

private fun folderOf(path: String): String = path.replace('\\', '/').substringBeforeLast('/', "")

private fun validTrack(t: Int?): Boolean = t != null && t in 1..999

/** Effective disc per song: 0/negative = untagged, 0-based numbering shifted up, folders used if tags can't separate discs. */
private fun effectiveDiscs(songs: List<SongDto>): IntArray {
    val tags = songs.map { it.discNumber }
    val valid = tags.filterNotNull().filter { it >= 0 }
    val shift = if (valid.any { it == 0 } && valid.any { it > 0 }) 1 else 0
    val eff = IntArray(songs.size) { i ->
        val d = tags[i]
        if (d == null || d < 0 || (d == 0 && shift == 0)) 1 else d + shift
    }
    if (eff.toSet().size == 1 && songs.all { !it.path.isNullOrBlank() }) {
        val nums = songs.mapNotNull { it.track?.takeIf { t -> t > 0 } }
        if (nums.size != nums.toSet().size) {
            val folders = songs.indices.groupBy { folderOf(songs[it].path!!) }
            val clean = folders.values.all { g ->
                val t = g.mapNotNull { songs[it].track?.takeIf { x -> x > 0 } }
                t.size == t.toSet().size
            }
            if (folders.size >= 2 && clean) {
                folders.keys.sortedWith { a, b -> naturalCompare(a, b) }.forEachIndexed { rank, key ->
                    folders.getValue(key).forEach { eff[it] = rank + 1 }
                }
            }
        }
    }
    return eff
}

/**
 * Orders tracks and decides disc headers and displayed numbers.
 *  - Tag numbers are shown only if every track on that disc has a unique number 1..999; otherwise that disc is numbered 1,2,3.
 *  - Untagged tracks go last. "of N" appears only when discs run 1..N without gaps.
 * Idempotent: feeding the result back in gives the same result.
 */
fun layoutTracks(songs: List<SongDto>): List<TrackRow> {
    if (songs.isEmpty()) return emptyList()
    val eff = effectiveDiscs(songs)
    val order = songs.indices.sortedWith(
        compareBy<Int>({ eff[it] }, { if (validTrack(songs[it].track)) songs[it].track!! else Int.MAX_VALUE }),
    )
    val groups = order.groupBy { eff[it] }
    val keys = groups.keys.toList()
    val contiguous = keys == (1..keys.size).toList()
    val rows = ArrayList<TrackRow>(songs.size)
    for ((d, idxs) in groups) {
        val tracks = idxs.map { songs[it].track }
        val ok = tracks.all { validTrack(it) } && tracks.toSet().size == tracks.size
        idxs.forEachIndexed { j, si ->
            val header = if (keys.size > 1 && j == 0) (if (contiguous) "Disc $d of ${keys.size}" else "Disc $d") else null
            rows += TrackRow(songs[si], d, header, (if (ok) tracks[j]!! else j + 1).toString().padStart(2, '0'))
        }
    }
    return rows
}
