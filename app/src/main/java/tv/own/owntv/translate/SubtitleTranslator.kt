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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
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
    const val TARGET_SPANISH = TranslateLanguage.SPANISH
    private val TARGETS = setOf(TARGET_ENGLISH, TARGET_ARABIC, TARGET_SPANISH)

    private const val PREFS = "owntvnew_subtitle_translation"
    private const val KEY_TARGET = "target"
    private const val UNDETERMINED = "und"
    private const val LOCK_AFTER = 3

    private val _target = MutableStateFlow<String?>(null)
    /** Target language code, or null when translation is off. */
    val target: StateFlow<String?> = _target.asStateFlow()

    /** What the translator is doing, for the on-screen status line. */
    enum class Status { OK, DOWNLOADING, DETECTING, FAILED }
    private val _status = MutableStateFlow(Status.OK)
    val status: StateFlow<Status> = _status.asStateFlow()
    /** Reason of the last failure, shown with [Status.FAILED]. */
    @Volatile var lastError: String? = null
        private set

    /** The newest finished translation and the subtitle text it was made from. */
    data class Translated(val source: String, val text: String)
    private val _latest = MutableStateFlow<Translated?>(null)
    val latest: StateFlow<Translated?> = _latest.asStateFlow()

    // Lines waiting to be translated. One worker takes them in order, always jumping to the newest
    // (conflate) and never being interrupted: live captions change many times a second, and a
    // translation or a model download that restarted on every change would never finish.
    private val pending = MutableStateFlow<String?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
        synchronized(this) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            _target.value = p.getString(KEY_TARGET, null)?.takeIf { it in TARGETS }
            prefs = p
            scope.launch { pending.filterNotNull().conflate().collect { work(it) } }
        }
    }

    /** Queue [text] for translation; the result arrives in [latest]. */
    fun request(text: String) {
        if (_target.value != null && text.isNotBlank()) pending.value = text
    }

    private suspend fun work(text: String) {
        if (_target.value == null) return
        try {
            val out = translate(text)
            if (out == null) {
                _status.value = Status.DETECTING
            } else {
                _latest.value = Translated(text, out)
                _status.value = Status.OK
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            _status.value = Status.FAILED
        }
    }

    fun setTarget(context: Context, lang: String?) {
        init(context)
        val value = lang?.takeIf { it in TARGETS }
        _target.value = value
        prefs?.edit()?.putString(KEY_TARGET, value)?.apply()
        _latest.value = null
        _status.value = Status.OK
        pending.value = null
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

    /**
     * Whether subtitle [cur] continues [prev] — the same caption growing word by word, or rolling up
     * a row — so the translation of [prev] may stand in until [cur]'s own is ready.
     */
    fun continues(prev: String, cur: String): Boolean {
        val p = normalize(prev).lowercase()
        val c = normalize(cur).lowercase()
        if (p.isEmpty() || c.isEmpty()) return false
        if (c.startsWith(p) || p.startsWith(c)) return true
        val tail = p.takeLast(CONTINUE_CHARS)
        val head = c.take(CONTINUE_CHARS)
        return (tail.length == CONTINUE_CHARS && c.contains(tail)) ||
            (head.length == CONTINUE_CHARS && p.contains(head))
    }

    private const val CONTINUE_CHARS = 12

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
            _status.value = Status.DOWNLOADING
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            readyModels.add(key)
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
