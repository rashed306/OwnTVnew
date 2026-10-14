package tv.own.owntv.translate

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import tv.own.owntv.player.PlaybackEngine
import tv.own.owntv.player.TrackOption

/**
 * OwnTVnew: one subtitle choice that follows the viewer from channel to channel.
 *
 * Upstream OwnTV remembers a subtitle pick per channel, and its "preferred subtitle language"
 * setting matches by language tag only — which US closed captions (CEA-608/708) usually lack, so
 * captions switched off again on every zap. Here the last pick made in the player's Subtitles menu
 * is kept globally (its language, whether it was closed captions, its label) and re-applied to each
 * new channel that offers a matching track. Turning subtitles Off in the menu is remembered too.
 */
object SubtitleChoice {
    private const val PREFS = "owntvnew_subtitle_choice"
    private const val KEY_ON = "on"
    private const val KEY_LANG = "lang"
    private const val KEY_CC = "cc"
    private const val KEY_LABEL = "label"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun rememberTrack(context: Context, track: TrackOption) {
        prefs(context).edit()
            .putBoolean(KEY_ON, true)
            .putString(KEY_LANG, track.lang?.let { normalizeLang(it) })
            .putBoolean(KEY_CC, isClosedCaption(track))
            .putString(KEY_LABEL, track.label)
            .apply()
    }

    fun rememberOff(context: Context) {
        prefs(context).edit().putBoolean(KEY_ON, false).apply()
    }

    /** Select the remembered kind of subtitle on [player], if it is on and the channel offers one. */
    fun apply(context: Context, player: PlaybackEngine) {
        val p = prefs(context)
        if (!p.getBoolean(KEY_ON, false)) return
        val tracks = player.textTracks().filter { !it.image }
        if (tracks.isEmpty() || tracks.any { it.selected }) return
        val lang = p.getString(KEY_LANG, null)
        val cc = p.getBoolean(KEY_CC, false)
        val label = p.getString(KEY_LABEL, null)
        val pick = tracks.firstOrNull { cc && isClosedCaption(it) }
            ?: tracks.firstOrNull { lang != null && it.lang != null && normalizeLang(it.lang!!) == lang }
            ?: tracks.firstOrNull { label != null && it.label == label }
            ?: return
        player.selectSubtitle(pick.mpvId)
    }

    internal fun isClosedCaption(track: TrackOption): Boolean {
        val codec = track.codec?.lowercase().orEmpty()
        if (CC_CODEC_MARKERS.any { codec.contains(it) }) return true
        return CC_LABEL.containsMatchIn(track.label)
    }

    /** ISO 639-2/B and 639-1 codes folded onto one form, so "ger", "deu" and "de" all match. */
    internal fun normalizeLang(code: String): String {
        val c = code.trim().lowercase()
        BIBLIOGRAPHIC[c]?.let { return it }
        if (c.length == 2) return runCatching { java.util.Locale.forLanguageTag(c).isO3Language }.getOrNull() ?: c
        return c
    }

    private val CC_CODEC_MARKERS = listOf("608", "708", "eia")
    private val CC_LABEL = Regex("""\bCC\d?\b""")
    private val BIBLIOGRAPHIC = mapOf(
        "ger" to "deu", "fre" to "fra", "dut" to "nld", "chi" to "zho",
        "cze" to "ces", "gre" to "ell", "per" to "fas", "rum" to "ron",
    )
}

/**
 * Re-applies [SubtitleChoice] whenever the engine reports a new channel or a new subtitle track
 * list, and unlocks the translator's detected source language for the new channel.
 */
@Composable
fun StickySubtitles(player: PlaybackEngine) {
    val context = LocalContext.current
    androidx.compose.runtime.remember(context) { SubtitleTranslator.init(context); true }
    val count by player.subCount.collectAsStateWithLifecycle()
    val meta by player.currentMeta.collectAsStateWithLifecycle()
    LaunchedEffect(player, meta) {
        SubtitleTranslator.resetSource()
        // OwnTVnew: this channel's remembered live subtitle timing (mpv), or back to zero.
        runCatching { LiveSubtitleTiming.restore(context, player, meta.title) }
    }
    LaunchedEffect(player, meta, count) {
        if (count <= 0) return@LaunchedEffect
        // Give the engine's own per-channel recall its turn first; this only fills in when nothing
        // ended up selected.
        delay(APPLY_DELAY_MS)
        runCatching { SubtitleChoice.apply(context, player) }
        // After a track is (re)selected the engine may reset its delay; put this channel's back.
        delay(APPLY_DELAY_MS)
        runCatching { LiveSubtitleTiming.restore(context, player, meta.title) }
    }
}

private const val APPLY_DELAY_MS = 800L
