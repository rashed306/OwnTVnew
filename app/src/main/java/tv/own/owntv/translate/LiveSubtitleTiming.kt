package tv.own.owntv.translate

import android.content.Context
import kotlinx.coroutines.launch
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
    fun available(player: PlaybackEngine): Boolean = when (player) {
        is MpvPlaybackEngine -> player.isLiveContent && player.textTracks().any { it.selected && !it.image }
        // ExoPlayer: every subtitle (CC, teletext, pictures) is re-stamped as it is read.
        is tv.own.owntv.player.LivePreviewEngine -> player.textTracks().any { it.selected }
        else -> false
    }

    /** The current live offset shown in the menu: mpv's own, or ExoPlayer's re-stamping. */
    fun currentMs(player: PlaybackEngine): Int =
        if (player is tv.own.owntv.player.LivePreviewEngine) tv.own.owntv.player.SubtitleTimeShift.offsetMs.value
        else player.subDelayMs.value

    /** Nudge the live offset by [deltaMs] and remember it for [channel]. */
    fun adjust(context: Context, player: PlaybackEngine, channel: String?, deltaMs: Int) {
        if (player is tv.own.owntv.player.LivePreviewEngine) {
            val ms = (tv.own.owntv.player.SubtitleTimeShift.offsetMs.value + deltaMs).coerceIn(-MAX_SHIFT_MS, MAX_SHIFT_MS)
            applyExo(player, ms)
            save(context, channel, ms)
        } else {
            player.adjustSubtitleDelay(deltaMs)
            save(context, channel, player.subDelayMs.value)
        }
    }

    fun reset(context: Context, player: PlaybackEngine, channel: String?) {
        if (player is tv.own.owntv.player.LivePreviewEngine) applyExo(player, 0) else player.resetSubtitleDelay()
        save(context, channel, 0)
    }

    private var pauseJob: kotlinx.coroutines.Job? = null
    private val scope = kotlinx.coroutines.MainScope()

    /**
     * ExoPlayer: re-stamp subtitles by [ms]. Earlier than the stream has been read ahead can't be
     * shown in time, so the picture pauses once for the difference while the stream keeps loading;
     * from then on the channel plays that much behind live and the subtitles land early enough.
     */
    private fun applyExo(engine: tv.own.owntv.player.LivePreviewEngine, ms: Int) {
        tv.own.owntv.player.SubtitleTimeShift.set(ms)
        if (ms >= 0) return
        pauseJob?.cancel()
        pauseJob = scope.launch {
            val need = -ms - engine.bufferAheadMs()
            if (need < MIN_PAUSE_MS || !engine.isPlaying.value) return@launch
            engine.togglePlayPause()
            kotlinx.coroutines.delay(need + PAUSE_MARGIN_MS)
            if (!engine.isPlaying.value) engine.togglePlayPause()
        }
    }

    fun save(context: Context, channel: String?, ms: Int) {
        val key = channel?.takeIf { it.isNotBlank() } ?: return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (ms == 0) p.remove(key) else p.putInt(key, ms)
        p.apply()
    }

    /** Put [channel]'s remembered offset (or zero) back on a live [player]. */
    fun restore(context: Context, player: PlaybackEngine, channel: String?) {
        val key = channel?.takeIf { it.isNotBlank() } ?: return
        val ms = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(key, 0)
        if (player is tv.own.owntv.player.LivePreviewEngine) {
            if (tv.own.owntv.player.SubtitleTimeShift.offsetMs.value != ms) applyExo(player, ms)
            return
        }
        if (player !is MpvPlaybackEngine || !player.isLiveContent) return
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
    private const val MAX_SHIFT_MS = 60_000
    private const val MIN_PAUSE_MS = 300L
    private const val PAUSE_MARGIN_MS = 500L
    private const val MIN_MS = 2_500L
    private const val MAX_MS = 7_000L
    private const val PER_CHAR_MS = 60L
}
