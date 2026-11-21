package com.anopticlabs.gravel.voicemqtt.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.voicemqtt.copy.AndroidVoiceText
import com.anopticlabs.gravel.voicemqtt.copy.VoiceCopy
import com.anopticlabs.gravel.voicemqtt.copy.VoiceKeys
import com.anopticlabs.gravel.voicemqtt.copy.VoiceText
import com.anopticlabs.gravel.voicemqtt.settings.VoiceSettings
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One installed text-to-speech engine, as the engine picker shows it. */
data class TtsEngineOption(val name: String, val label: String, val isSystemDefault: Boolean)

/**
 * What the speaker knows about the engine it is using right now: the engines to choose between, the one in
 * use, the voices that engine offers, and — when something is wrong — one plain-language line.
 *
 * The settings screen reads this, so a phone with no usable voice is a sentence on screen rather than a
 * silence. [problem] is the line; null means a voice will speak the next reply.
 */
data class SpeechEngineState(
    val read: Boolean = false,
    val engines: List<TtsEngineOption> = emptyList(),
    val chosenEngine: String? = null,
    /** Blank until an engine has been read: the screen then says "the phone's default engine" itself. */
    val engineLabel: String = "",
    val languageLabel: String = "",
    /** Every voice the chosen engine offers, offline ones first, so the picker can show both kinds. */
    val voices: List<OfferedVoice> = emptyList(),
    val chosenVoice: OfferedVoice? = null,
    val refusal: VoiceRefusal? = null,
    /**
     * The refusal's own sentence, already in the phone's language. Held here because the state is read by a
     * Compose screen that has no resolver of its own, and the reason must never arrive without its words.
     */
    val refusalSentence: String? = null,
    val failure: String? = null,
) {
    /** The one sentence the screen shows when replies cannot be spoken, or null when they can. */
    val problem: String?
        get() = failure ?: refusalSentence
}

/**
 * Speaks the loop's sentences through the platform's own text-to-speech engine when the two switches allow it
 * (see [PlaybackPolicy] and [BluetoothAudioGate]): nothing at all with spoken replies off, a live Bluetooth
 * output with headset only on, whatever output is active with it off.
 *
 * **The privacy rule: only an on-device voice ever speaks.** The requirement is that a reply is never sent to a
 * third party to be spoken, so the engine's own voice list is filtered to voices whose
 * `Voice.isNetworkConnectionRequired()` is false, by [VoiceSelection] — a pure rule proved by the host tests.
 * There is no fallback to a network voice and none to the engine's default voice: when no offline voice exists
 * for the language, the reply is recorded (one log line, one [VoiceSpeechHistory] entry) and the settings
 * screen states the reason in words. Each utterance additionally asks for embedded synthesis
 * ([TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS]) and declines network synthesis, which is a hint to
 * the engine; the guarantee is the voice filter, not the hint.
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
 *
 * The engine and the voice inside it are the user's choice ([VoiceSettings.ttsEngine],
 * [VoiceSettings.preferredVoice]) and are applied without an app restart: a changed engine or voice is noticed
 * before the next sentence and the engine is rebuilt then, and [reload] rebuilds it on the spot when the picker
 * changes.
 */
class AndroidSpeechOutput(
    context: Context,
    private val settings: () -> VoiceSettings,
    private val gate: BluetoothAudioGate,
    private val micBusy: () -> Boolean,
    private val history: VoiceSpeechHistory,
    private val scope: CoroutineScope,
    /** The feature's own copy, so the sentences below follow the phone's language. */
    private val text: VoiceText = AndroidVoiceText(context),
) : SpeechOutput {

    private val appContext = context.applicationContext
    private val audioManager: AudioManager? = appContext.getSystemService(AudioManager::class.java)
    private val lines = Channel<String>(Channel.CONFLATED)
    private val counter = AtomicInteger(0)
    private val lock = Any()
    private val _speechState = MutableStateFlow(SpeechEngineState())

    private var engine: TextToSpeech? = null
    /** The settings the live engine was built for, so a change is noticed without an app restart. */
    private var startedFor: VoiceSettings? = null
    /** Bumped on every restart, so an init callback from a replaced engine is ignored. */
    private var generation = 0
    @Volatile private var engineReady = false
    @Volatile private var choice: VoiceChoice = VoiceChoice.Refused(VoiceRefusal.NO_VOICES_OFFERED)
    /** The real platform voices, by name, so the chosen [OfferedVoice] can be handed back to `setVoice`. */
    private var realVoices: Map<String, Voice> = emptyMap()
    private var focusRequest: AudioFocusRequest? = null

    /** The engines the user may choose between, and what the chosen one offers. */
    val speechState: StateFlow<SpeechEngineState> = _speechState.asStateFlow()

    init {
        // The stored engine is used at construction, before any reply arrives.
        applyCurrentSettings()
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

    /**
     * Rebuilds the engine from the stored engine and voice. Called by the settings screen when the picker
     * changes, so the new engine's offline voices appear and speak without an app restart.
     */
    fun reload() {
        synchronized(lock) { restart(settings()) }
    }

    /**
     * Applies the stored engine/voice pair if it is not the one the live engine was built for. Runs before
     * every sentence, so a change made on the settings screen takes effect even if the screen did not call
     * [reload].
     */
    private fun applyCurrentSettings() {
        synchronized(lock) {
            val current = settings()
            if (startedFor.isTheSameChoiceAs(current)) return@synchronized
            restart(current)
        }
    }

    /** Replaces the live engine with one built for [current]. Must be called holding [lock]. */
    private fun restart(current: VoiceSettings) {
        generation += 1
        val thisGeneration = generation
        startedFor = current
        engineReady = false
        choice = VoiceChoice.Refused(VoiceRefusal.NO_VOICES_OFFERED)
        realVoices = emptyMap()
        runCatching { engine?.shutdown() }
        engine = null

        val engines = _speechState.value.engines
        _speechState.value = SpeechEngineState(
            // Not read until the new engine reports its voices: a half-built engine must not look like a phone
            // with nothing installed.
            read = false,
            engines = engines,
            chosenEngine = current.ttsEngine,
            engineLabel = engineLabelFor(current.ttsEngine, engines),
            languageLabel = languageLabel(),
        )
        engine = runCatching {
            // A null engine name is the platform's own default engine, which is the default choice.
            TextToSpeech(appContext, { status -> onInit(thisGeneration, status) }, current.ttsEngine)
        }.getOrElse { e ->
            logger.w { "speech engine not started: ${e::class.java.simpleName}" }
            _speechState.value = _speechState.value.copy(
                failure = text.of(VoiceCopy(VoiceKeys.ENGINE_START_FAILED)),
            )
            null
        }
    }

    private fun onInit(callGeneration: Int, status: Int) {
        synchronized(lock) {
            if (callGeneration != generation) return@synchronized // a replaced engine's late callback
            val tts = engine
            if (status != TextToSpeech.SUCCESS || tts == null) {
                engineReady = false
                _speechState.value = _speechState.value.copy(
                    failure = text.of(VoiceCopy(VoiceKeys.ENGINE_INIT_FAILED)),
                )
                logger.w { "speech engine unavailable (status $status)" }
                return@synchronized
            }
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) = abandonFocus()

                @Deprecated("The platform's abstract signature")
                override fun onError(utteranceId: String?) = abandonFocus()

                override fun onError(utteranceId: String?, errorCode: Int) = abandonFocus()
            })

            val voices = runCatching { tts.voices.orEmpty() }.getOrElse { e ->
                logger.w { "engine voices not readable: ${e::class.java.simpleName}" }
                emptyList()
            }
            realVoices = voices.associateBy { it.name }
            val offered = voices.map { voice ->
                OfferedVoice(
                    name = voice.name,
                    locale = voice.locale?.toLanguageTag().orEmpty(),
                    networkRequired = voice.isNetworkConnectionRequired,
                    quality = voice.quality,
                )
            }
            // The settings this engine was built for, so a change mid-start cannot mix two choices.
            val active = startedFor ?: settings()
            val picked = VoiceSelection.choose(
                voices = offered,
                language = Locale.getDefault().language,
                pinnedName = active.preferredVoice,
                offlineOnly = active.offlineOnly,
            )
            val usable = when (picked) {
                is VoiceChoice.Selected -> applyVoice(tts, picked.voice, active)
                is VoiceChoice.Refused -> picked
            }
            choice = usable
            engineReady = true
            publish(installedEngines(tts), offered, usable)
        }
    }

    /** Hands the chosen voice to the engine. A refusal here is still a refusal to speak with another. */
    private fun applyVoice(tts: TextToSpeech, voice: OfferedVoice, active: VoiceSettings): VoiceChoice {
        val real = realVoices[voice.name] ?: return VoiceChoice.Refused(VoiceRefusal.VOICE_NOT_ACCEPTED)
        val applied = runCatching { tts.setVoice(real) }.getOrElse { e ->
            logger.w { "engine refused the voice: ${e::class.java.simpleName}" }
            TextToSpeech.ERROR
        }
        return if (applied == TextToSpeech.SUCCESS) {
            VoiceChoice.Selected(voice, pinnedHonoured = voice.name == active.preferredVoice)
        } else {
            VoiceChoice.Refused(VoiceRefusal.VOICE_NOT_ACCEPTED)
        }
    }

    private fun publish(engines: List<TtsEngineOption>, offered: List<OfferedVoice>, picked: VoiceChoice) {
        val language = languageLabel()
        _speechState.value = _speechState.value.copy(
            read = true,
            engines = engines.ifEmpty { _speechState.value.engines },
            engineLabel = engineLabelFor(_speechState.value.chosenEngine, engines),
            languageLabel = language,
            voices = offered.sortedWith(
                compareByDescending<OfferedVoice> { !it.networkRequired }.thenBy { it.label },
            ),
            chosenVoice = (picked as? VoiceChoice.Selected)?.voice,
            refusal = (picked as? VoiceChoice.Refused)?.reason,
            refusalSentence = (picked as? VoiceChoice.Refused)
                ?.let { text.of(VoiceSelection.explanation(it.reason, language)) },
            failure = null,
        )
        when (picked) {
            is VoiceChoice.Selected -> logger.i { "speech voice: ${picked.voice.label}, ${picked.voice.spokenWhere}" }
            is VoiceChoice.Refused -> logger.i { VoiceSelection.logLine(picked.reason, language) }
        }
    }

    private fun installedEngines(tts: TextToSpeech): List<TtsEngineOption> = runCatching {
        // Both are instance calls: the engine list is asked of a live engine, not of the class.
        val systemDefault = runCatching { tts.defaultEngine }.getOrNull()
        tts.engines.orEmpty()
            .map { info ->
                TtsEngineOption(
                    name = info.name,
                    label = info.label?.takeIf { it.isNotBlank() }?.toString() ?: info.name,
                    isSystemDefault = info.name == systemDefault,
                )
            }
            .sortedBy { it.label.lowercase() }
    }.getOrElse { e ->
        logger.w { "installed speech engines not readable: ${e::class.java.simpleName}" }
        emptyList()
    }

    private fun engineLabelFor(name: String?, engines: List<TtsEngineOption>): String = when {
        name == null -> engines.firstOrNull { it.isSystemDefault }
            ?.let { text.of(VoiceCopy(VoiceKeys.ENGINE_DEFAULT_NAMED, it.label)) }
            ?: text.of(VoiceCopy(VoiceKeys.ENGINE_DEFAULT))
        else -> engines.firstOrNull { it.name == name }?.label ?: name
    }

    private fun languageLabel(): String {
        val locale = Locale.getDefault()
        return locale.getDisplayLanguage(locale).ifBlank { locale.language }
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
        // The engine picker's change reaches the speaker here even if the screen did not reload it.
        applyCurrentSettings()
        val usable = withTimeoutOrNull(INIT_TIMEOUT_MILLIS) {
            while (!engineReady) delay(READY_POLL_MILLIS)
            true
        } ?: false
        if (!usable) {
            logger.w { "reply not spoken: the speech engine did not start" }
            return
        }
        val picked = choice
        if (picked !is VoiceChoice.Selected) {
            // No usable voice: recorded, never spoken through a voice that would leave the phone.
            val reason = (picked as VoiceChoice.Refused).reason
            logger.i { VoiceSelection.logLine(reason, languageLabel()) }
            history.suppressed(suppressionFor(reason))
            return
        }
        requestFocus()
        val utteranceId = "gravel-voice-${counter.incrementAndGet()}"
        val params = Bundle().apply {
            if (settings().offlineOnly) {
                // Asked of the engine as well as enforced by the voice: synthesise here, do not synthesise over
                // the network. An engine that ignores the hint still cannot reach the network for this voice.
                @Suppress("DEPRECATION") // Still the only way to ask an engine for embedded synthesis.
                putString(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS, "true")
                @Suppress("DEPRECATION")
                putString(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS, "false")
            }
        }
        val accepted = runCatching { engine?.speak(line, TextToSpeech.QUEUE_FLUSH, params, utteranceId) }
            .getOrNull() ?: TextToSpeech.ERROR
        if (accepted != TextToSpeech.SUCCESS) {
            abandonFocus()
            logger.i { "reply not spoken: the engine would not use its voice" }
            history.suppressed(SpeechSuppression.NO_USABLE_VOICE)
            return
        }
        history.spoken()
    }

    /** A held-back sentence: one log line and one run-history entry, never an error to say aloud. */
    private fun suppress(reason: SpeechSuppression) {
        logger.i { PlaybackPolicy.logLine(reason) }
        history.suppressed(reason)
    }

    /** The history entry a refusal earns: the privacy one where offline speech caused it, a plain one otherwise. */
    private fun suppressionFor(reason: VoiceRefusal): SpeechSuppression = when (reason) {
        VoiceRefusal.NETWORK_VOICES_ONLY,
        VoiceRefusal.NO_OFFLINE_VOICE_FOR_LANGUAGE,
        VoiceRefusal.PINNED_VOICE_IS_NETWORK_ONLY,
        -> SpeechSuppression.NO_OFFLINE_VOICE
        VoiceRefusal.NO_VOICES_OFFERED,
        VoiceRefusal.NO_VOICE_FOR_LANGUAGE,
        VoiceRefusal.VOICE_NOT_ACCEPTED,
        -> SpeechSuppression.NO_USABLE_VOICE
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
        const val READY_POLL_MILLIS = 25L
    }
}

/** Whether these two settings ask the live engine for the same thing, so a restart would change nothing. */
private fun VoiceSettings?.isTheSameChoiceAs(other: VoiceSettings): Boolean =
    this != null &&
        ttsEngine == other.ttsEngine &&
        preferredVoice == other.preferredVoice &&
        offlineOnly == other.offlineOnly
