package tv.own.owntv.translate

import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import tv.own.owntv.player.LivePreviewEngine
import tv.own.owntv.player.PlaybackEngine
import tv.own.owntv.player.TrackOption

/**
 * OwnTVnew: what kind of subtitle a track is, so the Subtitles menu can say so on every row without
 * selecting it — the stream's own track table declares each track's format.
 *
 * mpv reports the format on the track ([TrackOption.codec]). ExoPlayer's live engine knows it too but
 * does not pass it on, so it is read from the engine's player (same track order as its menu) — and
 * when that is not possible the row simply gets no label.
 */
enum class SubtitleKind { TELETEXT, CC, IMAGE, TEXT }

object SubtitleKinds {

    /** Kinds for [player]'s subtitle tracks, keyed by [TrackOption.mpvId]. Read once per menu. */
    fun of(player: PlaybackEngine): Map<Int, SubtitleKind> {
        if (player is LivePreviewEngine) return runCatching { fromExo(player) }.getOrDefault(emptyMap())
        return player.textTracks().mapNotNull { t -> fromCodec(t.codec)?.let { t.mpvId to it } }.toMap()
    }

    internal fun fromCodec(codec: String?): SubtitleKind? {
        val c = codec?.lowercase() ?: return null
        return when {
            TELETEXT_MARK in c -> SubtitleKind.TELETEXT
            CC_MARKS.any { it in c } -> SubtitleKind.CC
            IMAGE_MARKS.any { it in c } -> SubtitleKind.IMAGE
            else -> SubtitleKind.TEXT
        }
    }

    /** Media3 1.4+ parses most subtitles while extracting: the format then says "cues" and keeps the
     *  original type in `codecs`, so both fields are read. */
    private fun fromExo(engine: LivePreviewEngine): Map<Int, SubtitleKind> {
        val field = LivePreviewEngine::class.java.getDeclaredField(PLAYER_FIELD).apply { isAccessible = true }
        val exo = field.get(engine) as? ExoPlayer ?: return emptyMap()
        val out = HashMap<Int, SubtitleKind>()
        var id = 0
        for (group in exo.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                val f = group.getTrackFormat(i)
                val kind = fromCodec(f.codecs) ?: fromCodec(f.sampleMimeType)
                if (kind != null) out[id] = kind
                id++
            }
        }
        return out
    }

    private const val PLAYER_FIELD = "player"
    private const val TELETEXT_MARK = "teletext"
    private val CC_MARKS = listOf("608", "708", "eia")
    private val IMAGE_MARKS = listOf("dvb_subtitle", "dvbsub", "pgs", "vobsub", "dvd_subtitle", "dvdsub", "xsub")
}
