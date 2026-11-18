package com.hmx.pdf.reading

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Phase 4.5 — the on-device speech engine.
 *
 * Offline policy: only a voice whose `isNetworkConnectionRequired` is false is selected. If the
 * device has no such voice the panel reports unavailable; this app never requests a cloud voice
 * and never sends text anywhere. Audio focus is transient (speech is not background playback):
 * losing it pauses the session, and closing the editor releases both focus and the engine.
 */
class AndroidTtsEngine(
    context: Context,
    private val onFocusLost: () -> Unit,
    private val onReady: () -> Unit
) : TtsEngine {

    private val app = context.applicationContext
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    /** TTS init and utterance callbacks arrive on binder threads; every listener hop goes to main. */
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var listener: TtsEngine.Listener? = null
    /** The utterance currently holding audio focus, so a flushed utterance can't release the new one's. */
    private var speakingId: String? = null
    /** Storage is `Any` so the API-26 type is not referenced on older devices. */
    private var focusRequest: Any? = null

    override var available: Boolean = false
        private set
    override var language: String? = null
        private set
    override var rate: Float = 1f
        set(value) {
            field = value.coerceIn(0.5f, 2f)
            runCatching { tts?.setSpeechRate(field) }
        }

    private val legacyFocus = AudioManager.OnAudioFocusChangeListener { change ->
        if (change <= AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            abandonFocus()
            onMain { onFocusLost() }
        }
    }

    init {
        tts = runCatching {
            TextToSpeech(app) { status -> onInit(status) }
        }.getOrNull()
    }

    private fun onInit(status: Int) {
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            available = false
            onMain { onReady() }
            return
        }
        runCatching { engine.setOnUtteranceProgressListener(progress) }
        available = configureVoice(engine)
        if (available) runCatching { engine.setSpeechRate(rate) }
        onMain { onReady() }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /** Selects an installed voice that does not need the network; false when there is none. */
    private fun configureVoice(engine: TextToSpeech): Boolean {
        val wanted = Locale.getDefault()
        val offline = runCatching { engine.voices }.getOrNull().orEmpty()
            .filter { !it.isNetworkConnectionRequired }
        val best = offline.firstOrNull { it.locale.language == wanted.language }
            ?: offline.firstOrNull { it.locale.language == "en" }
            ?: offline.firstOrNull()
        if (best != null) {
            if (runCatching { engine.setVoice(best) }.getOrDefault(TextToSpeech.ERROR) != TextToSpeech.SUCCESS) {
                return false
            }
            language = best.locale.toLanguageTag()
            return true
        }
        // Some engines report no voice list; accept only if the resulting voice is known offline.
        val result = runCatching { engine.setLanguage(wanted) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) return false
        val current = runCatching { engine.voice }.getOrNull()
        if (current != null && current.isNetworkConnectionRequired) return false
        language = wanted.toLanguageTag()
        return true
    }

    override fun setListener(listener: TtsEngine.Listener?) {
        this.listener = listener
    }

    override fun speak(text: String, utteranceId: String) {
        val engine = tts
        if (!available || engine == null) return
        if (!requestFocus()) {
            listener?.onError(utteranceId)
            return
        }
        val result = runCatching { engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) }
            .getOrDefault(TextToSpeech.ERROR)
        if (result != TextToSpeech.SUCCESS) {
            abandonFocus()
            listener?.onError(utteranceId)
        } else {
            speakingId = utteranceId
        }
    }

    override fun stop() {
        speakingId = null
        runCatching { tts?.stop() }
        abandonFocus()
    }

    override fun close() {
        listener = null
        speakingId = null
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        available = false
        abandonFocus()
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}

        override fun onDone(utteranceId: String?) {
            finish(utteranceId) { listener?.onDone(it) }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            finish(utteranceId) { listener?.onError(it) }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            finish(utteranceId) { listener?.onError(it) }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            // Pause/stop already abandon focus in [stop]; a flushed utterance must not release the
            // focus the next chunk just acquired, so only the matching id does anything here.
            finish(utteranceId) { }
        }
    }

    /** Releases focus only for the utterance that owns it, then forwards the event on main. */
    private fun finish(utteranceId: String?, forward: (String) -> Unit) {
        onMain {
            if (utteranceId != null && utteranceId == speakingId) {
                speakingId = null
                abandonFocus()
            }
            utteranceId?.let(forward)
        }
    }

    private fun requestFocus(): Boolean {
        val am = audio ?: return true
        return if (Build.VERSION.SDK_INT >= 26) {
            val request = focusRequest as? AudioFocusRequest ?: run {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener { change ->
                        if (change <= AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                            abandonFocus()
                            onMain { onFocusLost() }
                        }
                    }
                    .build()
                    .also { focusRequest = it }
            }
            runCatching { am.requestAudioFocus(request) }
                .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                am.requestAudioFocus(legacyFocus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonFocus() {
        val am = audio ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            (focusRequest as? AudioFocusRequest)?.let { runCatching { am.abandonAudioFocusRequest(it) } }
        } else {
            @Suppress("DEPRECATION")
            runCatching { am.abandonAudioFocus(legacyFocus) }
        }
    }
}
