package tv.own.owntv.translate

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import tv.own.owntv.player.LivePreviewEngine
import tv.own.owntv.player.MpvPlaybackEngine
import tv.own.owntv.player.OwnTVPlayer
import tv.own.owntv.player.PlaybackEngine
import tv.own.owntv.player.TrackOption
import java.util.concurrent.Executors

/**
 * OwnTVnew: reaches the two things the mpv player keeps to itself. Both are by name, guarded by
 * keep rules (proguard-rules.pro), and fail quietly — nothing here may ever break playback.
 */
internal object MpvAccess {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, WORKER).apply { isDaemon = true } }

    /** The [OwnTVPlayer] behind an mpv engine, or null for ExoPlayer. */
    fun player(engine: PlaybackEngine): OwnTVPlayer? = runCatching {
        if (engine !is MpvPlaybackEngine) return null
        val f = MpvPlaybackEngine::class.java.getDeclaredField(ENGINE_FIELD).apply { isAccessible = true }
        f.get(engine) as? OwnTVPlayer
    }.getOrNull()

    /**
     * CEA-608/708 captions only come out of software video decoding, so picking CC switches the whole
     * picture to software — too slow for a 1080p 50 fps channel on a TV box (slow motion). This hands
     * the picture back to the hardware decoder; the CC track stays selected.
     */
    fun setCcSoftware(player: OwnTVPlayer, on: Boolean) {
        runCatching {
            OwnTVPlayer::class.java.getDeclaredMethod(CC_METHOD, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(player, on)
        }.onFailure { android.util.Log.w(TAG, "CC decode switch unavailable", it) }
    }

    /** mpv's `teletext-page`: 0 = every subtitle page (the default), or one page such as 888. */
    fun setTeletextPage(player: OwnTVPlayer, page: Int) {
        worker.execute {
            runCatching {
                val f = OwnTVPlayer::class.java.getDeclaredField(MPV_FIELD).apply { isAccessible = true }
                val mpv = f.get(player) ?: return@runCatching
                mpv.javaClass.getMethod(SET_STRING, String::class.java, String::class.java)
                    .invoke(mpv, TELETEXT_PAGE, page.toString())
            }.onFailure { android.util.Log.w(TAG, "teletext page unavailable", it) }
        }
    }

    private const val TAG = "OwnTVnew"
    private const val WORKER = "owntvnew-mpv"
    private const val ENGINE_FIELD = "p"
    private const val MPV_FIELD = "mpv"
    private const val CC_METHOD = "setCcSoftwareOverride"
    private const val SET_STRING = "setPropertyString"
    private const val TELETEXT_PAGE = "teletext-page"
}

/** One language of a DVB teletext stream: the page that carries it. */
data class TeletextPage(val lang: String, val page: Int)

/**
 * OwnTVnew: a teletext stream that carries several languages ("deu,eng"), split into one menu entry
 * per language. The engine (rashed306/OwnTV_libmpv) names the pages in the track title, e.g.
 * "teletext deu=150/2 eng=888/2" (language=page/type); picking an entry sets mpv's `teletext-page`.
 */
object TeletextPages {
    private val ENTRY = Regex("""([A-Za-z]{3})=(\d{3})/(\d+)""")
    private const val TITLE_MARK = "teletext"

    /** Teletext types that are subtitles: 2 = subtitle page, 5 = subtitle page for the hard of hearing. */
    private val SUBTITLE_TYPES = setOf(2, 5)

    /** The page set on mpv now; 0 = all subtitle pages. Reset with every channel. */
    @Volatile var current: Int = 0
        private set

    fun pages(track: TrackOption): List<TeletextPage> {
        if (!track.label.startsWith(TITLE_MARK)) return emptyList()
        val all = ENTRY.findAll(track.label).map {
            Triple(it.groupValues[1].lowercase(), it.groupValues[2].toInt(), it.groupValues[3].toInt())
        }.toList()
        val subs = all.filter { it.third in SUBTITLE_TYPES }.ifEmpty { all }
        return subs.map { TeletextPage(it.first, it.second) }.distinct()
    }

    /** Show only [page] (0 = all again). */
    fun choose(player: OwnTVPlayer, page: Int) {
        if (current == page) return
        current = page
        MpvAccess.setTeletextPage(player, page)
    }

    fun choose(engine: PlaybackEngine, page: Int) {
        MpvAccess.player(engine)?.let { choose(it, page) }
    }

    /** A new channel starts from "all subtitle pages", which is what a one-language stream wants. */
    fun reset(player: OwnTVPlayer) {
        current = -1
        choose(player, 0)
    }
}

/**
 * OwnTVnew: what the player learns about a channel while it plays — see [ChannelNotes] — plus the
 * guard against empty CC slowing the picture down on mpv.
 */
object SubtitleWatch {
    /** No caption within this long after picking CC: the channel has none — back to hardware decoding. */
    private const val CC_TRIAL_MS = 6_000L
    private const val POLL_MS = 500L
    /** Video facts are saved once the channel has played this long (the previous one's are gone). */
    private const val SETTLE_MS = 3_000L
    private const val USER_PICK_WINDOW_MS = 3_000L

    @Volatile private var userPickAt = 0L

    /** The viewer picked a subtitle in the menu: give CC a fresh trial even where it was empty before. */
    fun userPicked() { userPickAt = System.currentTimeMillis() }

    /** The key a subtitle track is remembered under: its kind, language and teletext page. */
    fun key(kind: SubtitleKind?, lang: String?, page: Int?): String =
        listOf(kind?.name.orEmpty(), lang?.let { SubtitleChoice.normalizeLang(it) }.orEmpty(), page?.toString().orEmpty())
            .joinToString(SEP)

    /** The key for [track] as it plays now on mpv (the chosen page of a split teletext stream). */
    internal fun mpvKey(track: TrackOption): String {
        val kind = SubtitleKinds.fromCodec(track.codec)
        val pages = TeletextPages.pages(track)
        if (pages.size > 1) {
            val p = pages.firstOrNull { it.page == TeletextPages.current }
            if (p != null) return key(kind, p.lang, p.page)
        }
        return key(kind, pages.singleOrNull()?.lang ?: track.lang, null)
    }

    private class MpvState {
        var selectedId: Int? = null
        var ccSince: Long? = null
        var textSinceSelect = false
    }

    @Composable
    fun Mpv(player: OwnTVPlayer) {
        val context = LocalContext.current
        val meta by player.currentMeta.collectAsStateWithLifecycle()
        val text by player.subText.collectAsStateWithLifecycle()
        val res by player.videoRes.collectAsStateWithLifecycle()
        val fps by player.videoFps.collectAsStateWithLifecycle()
        val channel = meta.title?.takeIf { player.isLiveContent && it.isNotBlank() } ?: return
        val state = remember(channel) { MpvState() }

        LaunchedEffect(channel, res, fps) {
            if (res == null) return@LaunchedEffect
            delay(SETTLE_MS)
            ChannelNotes.setVideo(context, channel, res, fps)
        }
        LaunchedEffect(channel, text) {
            if (text.isNullOrBlank()) return@LaunchedEffect
            state.textSinceSelect = true
            ChannelNotes.markSub(context, channel)
            val sel = player.textTracks().firstOrNull { it.selected } ?: return@LaunchedEffect
            ChannelNotes.markTrackSeen(context, channel, mpvKey(sel))
            if (SubtitleChoice.isClosedCaption(sel)) ChannelNotes.setCcEmpty(context, channel, false)
        }
        LaunchedEffect(channel) {
            TeletextPages.reset(player)
            while (true) {
                runCatching { tick(context, player, channel, state) }
                delay(POLL_MS)
            }
        }
    }

    private fun tick(context: Context, player: OwnTVPlayer, channel: String, s: MpvState) {
        val tracks = player.textTracks()
        // mpv cannot draw a picture subtitle on live TV, but its being listed is enough for the SUB tag.
        if (tracks.any { it.image }) ChannelNotes.markSub(context, channel)
        val sel = tracks.firstOrNull { it.selected }
        val now = System.currentTimeMillis()
        if (sel?.mpvId != s.selectedId) {
            s.selectedId = sel?.mpvId
            s.textSinceSelect = false
            s.ccSince = null
            if (sel != null && SubtitleChoice.isClosedCaption(sel)) {
                val fresh = now - userPickAt < USER_PICK_WINDOW_MS
                if (!fresh && ChannelNotes.ccEmpty(context, channel)) {
                    MpvAccess.setCcSoftware(player, false) // known empty here: never slow the picture
                } else {
                    s.ccSince = now
                }
            }
        }
        s.ccSince?.let { since ->
            if (s.textSinceSelect) {
                s.ccSince = null
            } else if (now - since > CC_TRIAL_MS) {
                s.ccSince = null
                MpvAccess.setCcSoftware(player, false)
                ChannelNotes.setCcEmpty(context, channel, true)
            }
        }
        // A teletext stream with several languages: show the one the viewer last chose, else the first.
        if (sel != null) {
            val pages = TeletextPages.pages(sel)
            if (pages.size > 1 && pages.none { it.page == TeletextPages.current }) {
                val want = SubtitleChoice.rememberedLang(context)
                val pick = pages.firstOrNull { want != null && SubtitleChoice.normalizeLang(it.lang) == want } ?: pages.first()
                TeletextPages.choose(player, pick.page)
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Composable
    fun Exo(engine: LivePreviewEngine) {
        val context = LocalContext.current
        val meta by engine.currentMeta.collectAsStateWithLifecycle()
        val cues by engine.cues.collectAsStateWithLifecycle()
        val res by engine.videoRes.collectAsStateWithLifecycle()
        val fps by engine.videoFps.collectAsStateWithLifecycle()
        val channel = meta.title?.takeIf { it.isNotBlank() } ?: return

        LaunchedEffect(channel, res, fps) {
            if (res == null) return@LaunchedEffect
            delay(SETTLE_MS)
            ChannelNotes.setVideo(context, channel, res, fps)
        }
        LaunchedEffect(channel, cues) {
            if (cues.none { it.bitmap != null || !it.text.isNullOrBlank() }) return@LaunchedEffect
            ChannelNotes.markSub(context, channel)
            val sel = engine.textTracks().firstOrNull { it.selected } ?: return@LaunchedEffect
            val kind = SubtitleKinds.of(engine)[sel.mpvId]
            ChannelNotes.markTrackSeen(context, channel, key(kind, sel.lang, null))
        }
    }

    private const val SEP = "|"
}
