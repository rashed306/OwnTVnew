package tv.own.owntv.translate

import android.content.Context
import tv.own.owntv.player.MpvPlaybackEngine
import tv.own.owntv.player.PlaybackEngine
import tv.own.owntv.player.TrackOption

/**
 * OwnTVnew: subtitle timing for live channels on mpv, remembered per channel.
 *
 * Upstream offers Subtitle timing for films only. On a live channel mpv can shift subtitles just as
 * well (`sub-delay`), and earlier as well as later, because it holds a few seconds of the stream
 * ahead of the picture. Broadcasters tend to be consistently early or late, so the offset is kept
 * per channel and put back whenever that channel is tuned again.
 */
object LiveSubtitleTiming {
    private const val PREFS = "owntvnew_live_sub_delay"

    /** Whether the live timing control applies to [player] right now. */
    fun available(player: PlaybackEngine): Boolean =
        player is MpvPlaybackEngine && player.isLiveContent &&
            player.textTracks().any { it.selected && !it.image }

    fun save(context: Context, channel: String?, ms: Int) {
        val key = channel?.takeIf { it.isNotBlank() } ?: return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (ms == 0) p.remove(key) else p.putInt(key, ms)
        p.apply()
    }

    /** Put [channel]'s remembered offset (or zero) back on a live mpv [player]. */
    fun restore(context: Context, player: PlaybackEngine, channel: String?) {
        if (player !is MpvPlaybackEngine || !player.isLiveContent) return
        val key = channel?.takeIf { it.isNotBlank() } ?: return
        val ms = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(key, 0)
        if (player.subDelayMs.value == ms) return
        player.resetSubtitleDelay()
        if (ms != 0) player.adjustSubtitleDelay(ms)
    }

    /** DVB teletext: lines often come without a "clear" signal, so they may need to time out. */
    fun isTeletext(track: TrackOption): Boolean = track.codec?.lowercase()?.contains(TELETEXT) == true

    /**
     * How long a teletext line may stay up without being replaced: a reading time from its length,
     * 2.5 s for a short line up to 7 s for a long one.
     */
    fun readingTimeMs(text: String): Long = (MIN_MS + PER_CHAR_MS * text.length).coerceIn(MIN_MS, MAX_MS)

    private const val TELETEXT = "teletext"
    private const val MIN_MS = 2_500L
    private const val MAX_MS = 7_000L
    private const val PER_CHAR_MS = 60L
}
