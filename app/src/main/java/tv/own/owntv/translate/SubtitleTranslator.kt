package tv.own.owntv.translate

import android.content.Context
import android.content.SharedPreferences
import android.util.LruCache
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OwnTVnew: on-device live subtitle translation (Google ML Kit).
 *
 * Every subtitle line the player is about to show passes through [translate]. The source language is
 * detected from the text itself (ML Kit language identification), because live caption tracks rarely
 * carry a usable language tag; once the same language has been seen a few times it is locked for the
 * rest of the channel, so short lines ("Yes.", "OK") cannot flip it. [resetSource] unlocks it on a
 * channel change. Translation models (~30 MB each) download once, on first use, then work offline.
 */
object SubtitleTranslator {
    const val TARGET_ENGLISH = TranslateLanguage.ENGLISH
    const val TARGET_ARABIC = TranslateLanguage.ARABIC
    private val TARGETS = setOf(TARGET_ENGLISH, TARGET_ARABIC)

    private const val PREFS = "owntvnew_subtitle_translation"
    private const val KEY_TARGET = "target"
    private const val UNDETERMINED = "und"
    private const val LOCK_AFTER = 3

    private val _target = MutableStateFlow<String?>(null)
    /** Target language code, or null when translation is off. */
    val target: StateFlow<String?> = _target.asStateFlow()

    /** True while a translation model is being downloaded (first use of a language pair). */
    private val _downloading = MutableStateFlow(false)
    val downloading: StateFlow<Boolean> = _downloading.asStateFlow()

    @Volatile private var prefs: SharedPreferences? = null
    private val cache = LruCache<String, String>(400)
    private val translators = HashMap<String, Translator>()
    private val readyModels = HashSet<String>()
    private val modelLock = Mutex()

    private val identifier by lazy {
        LanguageIdentification.getClient(
            LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.5f).build(),
        )
    }

    // Source-language lock for the current channel.
    @Volatile private var lockedSource: String? = null
    private val sourceVotes = HashMap<String, Int>()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _target.value = p.getString(KEY_TARGET, null)?.takeIf { it in TARGETS }
    }

    fun setTarget(context: Context, lang: String?) {
        init(context)
        val value = lang?.takeIf { it in TARGETS }
        _target.value = value
        prefs?.edit()?.putString(KEY_TARGET, value)?.apply()
        resetSource()
    }

    /** Forget the detected source language — call when the channel changes. */
    @Synchronized
    fun resetSource() {
        lockedSource = null
        sourceVotes.clear()
    }

    /** A finished translation for [text] into the current target, if one is cached. */
    fun cached(text: String): String? {
        val tgt = _target.value ?: return null
        return cache.get(cacheKey(tgt, normalize(text)))
    }

    /**
     * Translate one subtitle block into the current target. Returns null when translation is off,
     * the language could not be identified yet, or translation failed — the caller then shows the
     * original text.
     */
    suspend fun translate(text: String): String? {
        val tgt = _target.value ?: return null
        val clean = normalize(text)
        if (clean.isEmpty()) return null
        cache.get(cacheKey(tgt, clean))?.let { return it }
        val src = sourceFor(clean) ?: return null
        val out = if (src == tgt) clean else {
            val translator = translatorFor(src, tgt)
            ensureModel(src, tgt, translator)
            translator.translate(clean).await()
        }
        cache.put(cacheKey(tgt, clean), out)
        return out
    }

    private fun cacheKey(tgt: String, text: String) = tgt + '\u0000' + text

    private suspend fun sourceFor(text: String): String? {
        lockedSource?.let { return it }
        val tag = runCatching { identifier.identifyLanguage(text).await() }.getOrNull()
        val lang = tag?.takeIf { it != UNDETERMINED }?.let { TranslateLanguage.fromLanguageTag(it) } ?: return null
        synchronized(this) {
            val votes = (sourceVotes[lang] ?: 0) + 1
            sourceVotes[lang] = votes
            if (votes >= LOCK_AFTER && lockedSource == null) lockedSource = lang
        }
        return lang
    }

    @Synchronized
    private fun translatorFor(src: String, tgt: String): Translator =
        translators.getOrPut(src + tgt) {
            Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build(),
            )
        }

    private suspend fun ensureModel(src: String, tgt: String, translator: Translator) {
        val key = src + tgt
        if (key in readyModels) return
        modelLock.withLock {
            if (key in readyModels) return
            _downloading.value = true
            try {
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                readyModels.add(key)
            } finally {
                _downloading.value = false
            }
        }
    }

    /**
     * Collapse whitespace, and turn ALL-CAPS broadcast captions (typical of US CEA-608) into sentence
     * case: translation models handle shouting text noticeably worse.
     */
    internal fun normalize(text: String): String {
        val collapsed = text.split('\n', '\r', '\t', ' ').filter { it.isNotEmpty() }.joinToString(" ")
        val letters = collapsed.filter { it.isLetter() }
        if (letters.length < 4 || letters.any { it.isLowerCase() }) return collapsed
        val sb = StringBuilder(collapsed.length)
        var capitalizeNext = true
        for (c in collapsed.lowercase()) {
            if (capitalizeNext && c.isLetter()) {
                sb.append(c.uppercaseChar()); capitalizeNext = false
            } else {
                sb.append(c)
            }
            if (c == '.' || c == '!' || c == '?') capitalizeNext = true
        }
        // English "i" as a word stays capital.
        return sb.toString().split(' ').joinToString(" ") { w ->
            if (w == "i" || w.startsWith("i'")) w.replaceFirstChar { it.uppercaseChar() } else w
        }
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { if (cont.isActive) cont.resume(it) }
    addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
