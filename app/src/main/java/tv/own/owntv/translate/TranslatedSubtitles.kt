package tv.own.owntv.translate

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.text.Cue
import kotlinx.coroutines.delay

/**
 * OwnTVnew: the subtitle line to show for [text] — its translation when translation is on and ready,
 * otherwise the original. Roll-up captions change word by word, so a new line waits a moment to settle
 * before it is translated; a cached line is shown at once.
 */
@Composable
fun rememberTranslatedText(text: String?): String? {
    val context = LocalContext.current
    remember(context) { SubtitleTranslator.init(context); true }
    val target by SubtitleTranslator.target.collectAsStateWithLifecycle()
    var shown by remember { mutableStateOf(text) }
    LaunchedEffect(text, target) {
        if (target == null || text.isNullOrBlank()) {
            shown = text
            return@LaunchedEffect
        }
        SubtitleTranslator.cached(text)?.let { shown = it; return@LaunchedEffect }
        delay(SETTLE_MS)
        val out = runCatching { SubtitleTranslator.translate(text) }.getOrNull()
        shown = out ?: text
    }
    return if (target == null) text else shown
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
    if (target == null || textCues.isEmpty() || translated.isNullOrBlank()) return cues
    return remember(cues, translated) {
        cues.filter { it.bitmap != null } + Cue.Builder().setText(translated).build()
    }
}

private const val SETTLE_MS = 150L
