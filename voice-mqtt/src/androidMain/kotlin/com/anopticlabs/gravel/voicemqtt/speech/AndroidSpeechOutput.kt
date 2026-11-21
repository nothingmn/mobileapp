package com.anopticlabs.gravel.voicemqtt.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.voicemqtt.settings.VoiceSettings
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Speaks the loop's sentences through the platform's own text-to-speech engine when the two switches allow it
 * (see [PlaybackPolicy] and [BluetoothAudioGate]): nothing at all with spoken replies off, a live Bluetooth
 * output with headset only on, whatever output is active with it off.
 *
 * A sentence that is held back is recorded rather than swallowed: one log line naming the reason and one entry
 * in [VoiceSpeechHistory], which the settings screen shows. It is never an error to speak aloud — the reason
 * there is no audio is usually that there is nowhere to put it.
 *
 * Two rules beyond the switches:
 * - **The microphone wins.** While the phone is recording, a reply waits; a newer line replaces an older one
 *   that is still waiting (they are stale answers to answers), and a line still waiting after
 *   [MIC_WAIT_MILLIS] is dropped rather than queued behind a long recording. So: queued, newest only, dropped
 *   at the cap.
 * - **Audio focus is transient-may-duck**, so a reply never fights music or a podcast for the output; focus is
 *   given back as soon as the utterance finishes.
 */
class AndroidSpeechOutput(
    context: Context,
    private val settings: () -> VoiceSettings,
    private val gate: BluetoothAudioGate,
    private val micBusy: () -> Boolean,
    private val history: VoiceSpeechHistory,
    scope: CoroutineScope,
) : SpeechOutput {

    private val appContext = context.applicationContext
    private val audioManager: AudioManager? = appContext.getSystemService(AudioManager::class.java)
    private val ready = CompletableDeferred<Boolean>()
    private val lines = Channel<String>(Channel.CONFLATED)
    private val counter = AtomicInteger(0)
    private var engine: TextToSpeech? = null
    private var focusRequest: AudioFocusRequest? = null

    init {
        engine = TextToSpeech(appContext) { status -> onInit(status) }
        scope.launch {
            for (line in lines) speakWhenPossible(line)
        }
    }

    /** Never blocks the turn: the sentence is handed to the speaker and played when the rules allow it. */
    override suspend fun speak(text: String) {
        lines.trySend(text)
    }

    /** What the loop did with each sentence, newest first; the settings screen reads the head of it. */
    val runs: StateFlow<List<VoiceSpeechRun>> get() = history.runs

    private fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            try {
                val chosen = engine?.setLanguage(Locale.getDefault())
                if (chosen == TextToSpeech.LANG_MISSING_DATA || chosen == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine?.setLanguage(Locale.US)
                }
                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) = abandonFocus()

                    @Deprecated("The platform's abstract signature")
                    override fun onError(utteranceId: String?) = abandonFocus()

                    override fun onError(utteranceId: String?, errorCode: Int) = abandonFocus()
                })
            } catch (e: Exception) {
                logger.w { "speech engine setup failed: ${e::class.java.simpleName}" }
            }
        } else {
            logger.w { "speech engine unavailable (status $status)" }
        }
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    private suspend fun speakWhenPossible(line: String) {
        // The decision is asked twice: now, so a hopeless line is dropped before any waiting, and again right
        // before playback, because a headset can disconnect while a line is queued.
        when (val decision = gate.decision(settings())) {
            PlaybackDecision.Speak -> Unit
            is PlaybackDecision.Silent -> {
                suppress(decision.reason)
                return
            }
        }
        var waited = 0L
        while (micBusy() && waited < MIC_WAIT_MILLIS) {
            delay(MIC_POLL_MILLIS)
            waited += MIC_POLL_MILLIS
        }
        if (micBusy()) {
            logger.i { "reply dropped: the microphone is still recording" }
            return
        }
        when (val decision = gate.decision(settings())) {
            PlaybackDecision.Speak -> Unit
            is PlaybackDecision.Silent -> {
                suppress(decision.reason)
                return
            }
        }
        val usable = withTimeoutOrNull(INIT_TIMEOUT_MILLIS) { ready.await() } ?: false
        if (!usable) {
            logger.w { "reply not spoken: the speech engine did not start" }
            return
        }
        requestFocus()
        val utteranceId = "gravel-voice-${counter.incrementAndGet()}"
        engine?.speak(line, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        history.spoken()
    }

    /** A held-back sentence: one log line and one run-history entry, never an error to say aloud. */
    private fun suppress(reason: SpeechSuppression) {
        logger.i { PlaybackPolicy.logLine(reason) }
        history.suppressed(reason)
    }

    private fun requestFocus() {
        val manager = audioManager ?: return
        val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { }
            .build()
            .also { focusRequest = it }
        runCatching { manager.requestAudioFocus(request) }
    }

    private fun abandonFocus() {
        val manager = audioManager ?: return
        val request = focusRequest ?: return
        runCatching { manager.abandonAudioFocusRequest(request) }
    }

    private companion object {
        val logger = Logger.withTag("AndroidSpeechOutput")
        const val MIC_POLL_MILLIS = 250L
        const val MIC_WAIT_MILLIS = 30_000L
        const val INIT_TIMEOUT_MILLIS = 5_000L
    }
}
