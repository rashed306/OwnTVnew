package tv.own.owntv.translate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.LruCache
import androidx.media3.common.text.Cue
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OwnTVnew: picture subtitles (DVB, the coloured ones on Sky/BBC) read with on-device text
 * recognition, translated, and drawn back as text in each line's own colour and in the picture's
 * place on screen. Broadcasters colour lines by speaker, so the colour is kept per line.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object ImageSubtitleOcr {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** One recognised line: its text and colour. */
    private data class Line(val text: String, val color: Int)

    private val cache = LruCache<Int, List<Line>>(CACHE_SIZE)

    /**
     * [images] (bitmap cues) as translated text cues, or null when nothing could be read or
     * translated — the caller then shows the pictures as they are.
     */
    suspend fun translateCues(images: List<Cue>): List<Cue>? {
        val out = ArrayList<Cue>()
        for (cue in images) {
            val bmp = cue.bitmap ?: continue
            val lines = read(bmp)
            if (lines.isEmpty()) continue
            // Consecutive lines of one colour are one speaker's sentence: translate them together.
            val groups = ArrayList<Pair<Int, String>>()
            for (l in lines) {
                val last = groups.lastOrNull()
                if (last != null && last.first == l.color) groups[groups.size - 1] = last.first to (last.second + " " + l.text)
                else groups += l.color to l.text
            }
            val text = SpannableStringBuilder()
            for ((color, source) in groups) {
                val translated = SubtitleTranslator.translate(source) ?: return null
                if (text.isNotEmpty()) text.append('\n')
                val start = text.length
                text.append(translated)
                text.setSpan(ForegroundColorSpan(color), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            out += placed(cue, text)
        }
        return out.ifEmpty { null }
    }

    /** A text cue whose bottom sits where the picture's bottom was. */
    private fun placed(image: Cue, text: CharSequence): Cue {
        val b = Cue.Builder().setText(text)
            .setTextAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
            .setPosition(HALF).setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
        if (image.line != Cue.DIMEN_UNSET && image.lineType == Cue.LINE_TYPE_FRACTION && image.bitmapHeight != Cue.DIMEN_UNSET) {
            val bottom = (image.line + image.bitmapHeight).coerceIn(0f, 1f)
            b.setLine(bottom, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_END)
        }
        return b.build()
    }

    private suspend fun read(src: Bitmap): List<Line> {
        val key = System.identityHashCode(src)
        cache.get(key)?.let { return it }
        // Subtitle pictures are coloured text on a transparent background: recognition wants it
        // opaque, so the picture is laid on black first.
        val flat = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply { drawColor(Color.BLACK); drawBitmap(src, 0f, 0f, null) }
        val result = recognizer.process(InputImage.fromBitmap(flat, 0)).await()
        val lines = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val text = line.text.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Line(text, line.boundingBox?.let { dominantColor(flat, it) } ?: Color.WHITE)
        }
        flat.recycle()
        cache.put(key, lines)
        return lines
    }

    /** The commonest bright colour inside [box] — the letters, not their dark outline. */
    private fun dominantColor(bmp: Bitmap, box: Rect): Int {
        val r = Rect(box).apply { intersect(0, 0, bmp.width, bmp.height) }
        if (r.isEmpty) return Color.WHITE
        val px = IntArray(r.width() * r.height())
        bmp.getPixels(px, 0, r.width(), r.left, r.top, r.width(), r.height())
        val counts = HashMap<Int, Int>()
        for (p in px) {
            val red = Color.red(p); val green = Color.green(p); val blue = Color.blue(p)
            if ((red * 299 + green * 587 + blue * 114) / 1000 < MIN_LUMA) continue
            val k = (red shr 4 shl 8) or (green shr 4 shl 4) or (blue shr 4)
            counts[k] = (counts[k] ?: 0) + 1
        }
        val k = counts.maxByOrNull { it.value }?.key ?: return Color.WHITE
        return Color.rgb(((k shr 8) and 0xF) * 17, ((k shr 4) and 0xF) * 17, (k and 0xF) * 17)
    }

    private const val CACHE_SIZE = 32
    private const val MIN_LUMA = 90
    private const val HALF = 0.5f
}

private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
