package tv.own.owntv.translate

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.text.Cue
import tv.own.owntv.R

/**
 * OwnTVnew: the subtitle line to show for [text] while translation is on.
 *
 * - Its translation, as soon as there is one.
 * - While a line is still being translated: the previous translation if this line continues it
 *   (captions that build up word by word), otherwise nothing — never a flash of the original.
 * - The original only when translation cannot happen (model still downloading, language not
 *   detected, an error), with a short status line saying why.
 *
 * Returns [text] unchanged when translation is off.
 */
@Composable
fun rememberTranslatedText(text: String?): String? {
    val context = LocalContext.current
    remember(context) { SubtitleTranslator.init(context); true }
    val target by SubtitleTranslator.target.collectAsStateWithLifecycle()
    val latest by SubtitleTranslator.latest.collectAsStateWithLifecycle()
    val status by SubtitleTranslator.status.collectAsStateWithLifecycle()
    LaunchedEffect(text, target) {
        if (target != null && !text.isNullOrBlank()) SubtitleTranslator.request(text)
    }
    if (target == null || text.isNullOrBlank()) return text

    SubtitleTranslator.cached(text)?.let { return it }
    val done = latest
    if (done != null && SubtitleTranslator.continues(done.source, text)) return done.text

    val note = when (status) {
        SubtitleTranslator.Status.DOWNLOADING -> stringResource(R.string.owntvnew_translate_downloading)
        SubtitleTranslator.Status.DETECTING -> stringResource(R.string.owntvnew_translate_detecting)
        SubtitleTranslator.Status.FAILED ->
            stringResource(R.string.owntvnew_translate_failed, SubtitleTranslator.lastError.orEmpty())
        SubtitleTranslator.Status.OK -> null
    }
    // Waiting on a quick translation: show nothing rather than the original for a split second.
    return if (note == null) null else text + '\n' + note
}

/**
 * OwnTVnew: Media3 cues with their text translated. All text cues on screen are joined into one block
 * (live captions split a sentence across rows, and translating row by row loses the meaning) and shown
 * as a single bottom cue; image-based cues (DVB/PGS bitmaps) pass through untouched.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun rememberTranslatedCues(cues: List<Cue>): List<Cue> {
    val target by SubtitleTranslator.target.collectAsStateWithLifecycle()
    val textCues = cues.filter { it.bitmap == null && !it.text.isNullOrBlank() }
    val joined = textCues.joinToString(" ") { it.text.toString() }.trim()
    val translated = rememberTranslatedText(joined.ifEmpty { null })
    if (target == null || textCues.isEmpty()) return cues
    return remember(cues, translated) {
        val images = cues.filter { it.bitmap != null }
        if (translated.isNullOrBlank()) images else images + Cue.Builder().setText(translated).build()
    }
}
