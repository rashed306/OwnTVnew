package tv.own.owntv.translate

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * OwnTVnew: small facts about each live channel, learned while it plays and kept on the device —
 * nothing is probed in the background. They feed the channel list's tags (SUB, HD/FHD/4K, 50/60 fps)
 * and the Subtitles menu's dots (which tracks have actually shown text).
 *
 * Keyed by the channel's name, as the player reports it ([tv.own.owntv.player.MediaMeta.title]),
 * which is the same `ChannelEntity.name` the channel list shows.
 */
object ChannelNotes {
    private const val PREFS = "owntvnew_channel_notes"
    private const val SUB = "s:"
    private const val RES = "r:"
    private const val FPS = "f:"
    private const val CC_EMPTY = "c:"
    private const val SEEN = "t:"

    /** How long a "CC was empty here" note stands before CC gets a fresh software-decode trial. */
    private const val CC_EMPTY_TTL_MS = 3L * 24 * 60 * 60 * 1000

    private val _version = MutableStateFlow(0)

    /** Bumped on every change, so lists and menus re-read their notes. */
    val version: StateFlow<Int> = _version.asStateFlow()

    @Volatile private var cache: MutableMap<String, Any?>? = null

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun all(context: Context): MutableMap<String, Any?> =
        cache ?: synchronized(this) {
            cache ?: HashMap<String, Any?>(prefs(context).all).also { cache = it }
        }

    private fun put(context: Context, key: String, value: Any?) {
        val map = all(context)
        if (map[key] == value) return
        val edit = prefs(context).edit()
        when (value) {
            null -> { map.remove(key); edit.remove(key) }
            is Boolean -> { map[key] = value; edit.putBoolean(key, value) }
            is Int -> { map[key] = value; edit.putInt(key, value) }
            is Long -> { map[key] = value; edit.putLong(key, value) }
            is String -> { map[key] = value; edit.putString(key, value) }
            is Set<*> -> {
                val set = value.map { it.toString() }.toSet()
                map[key] = set; edit.putStringSet(key, set)
            }
        }
        edit.apply()
        _version.value++
    }

    data class Tags(val sub: Boolean, val res: String?, val fps: Int?)

    fun tags(context: Context, channel: String): Tags {
        val map = all(context)
        return Tags(
            sub = map[SUB + channel] == true,
            res = map[RES + channel] as? String,
            fps = map[FPS + channel] as? Int,
        )
    }

    /** The channel showed a subtitle (text or picture), or lists one mpv cannot draw live. */
    fun markSub(context: Context, channel: String) = put(context, SUB + channel, true)

    /** Resolution class ("HD", "FHD", "4K"; null below HD) and frame rate, as last seen playing. */
    fun setVideo(context: Context, channel: String, resLabel: String?, fps: Float?) {
        resClass(resLabel)?.let { put(context, RES + channel, it) }
        fps?.takeIf { it > 1f }?.let { put(context, FPS + channel, kotlin.math.round(it).toInt()) }
    }

    fun markTrackSeen(context: Context, channel: String, trackKey: String) {
        val key = SEEN + channel
        val now = (all(context)[key] as? Set<*>).orEmpty().map { it.toString() }.toSet()
        if (trackKey !in now) put(context, key, now + trackKey)
    }

    fun trackSeen(context: Context, channel: String, trackKey: String): Boolean {
        val seen = all(context)[SEEN + channel] as? Set<*>
        return seen?.contains(trackKey) == true
    }

    /** CC was picked here and no caption arrived: decoding in software for it only slowed the video. */
    fun setCcEmpty(context: Context, channel: String, empty: Boolean) =
        put(context, CC_EMPTY + channel, if (empty) System.currentTimeMillis() else null)

    fun ccEmpty(context: Context, channel: String): Boolean {
        val at = all(context)[CC_EMPTY + channel] as? Long ?: return false
        return System.currentTimeMillis() - at < CC_EMPTY_TTL_MS
    }

    /** "1080p" / "4K" / "720p" (either engine) → the tag class, or null below 720p. */
    internal fun resClass(label: String?): String? {
        val l = label?.trim()?.uppercase() ?: return null
        if (l == RES_4K) return RES_4K
        val h = l.takeWhile { it.isDigit() }.toIntOrNull() ?: return null
        return when {
            h >= 2160 -> RES_4K
            h >= 1080 -> RES_FHD
            h >= 720 -> RES_HD
            else -> null
        }
    }

    const val RES_4K = "4K"
    const val RES_FHD = "FHD"
    const val RES_HD = "HD"

    /** Frame rates at or above this get a tag; below it the picture is ordinary 25/30 fps. */
    const val FPS_TAG_MIN = 49
}
