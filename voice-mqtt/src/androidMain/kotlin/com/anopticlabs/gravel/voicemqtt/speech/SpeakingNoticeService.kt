package com.anopticlabs.gravel.voicemqtt.speech

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.AppVisibility
import com.anopticlabs.gravel.voicemqtt.R
import com.anopticlabs.gravel.voicemqtt.copy.AndroidVoiceText
import com.anopticlabs.gravel.voicemqtt.copy.VoiceCopy
import com.anopticlabs.gravel.voicemqtt.copy.VoiceKeys
import com.anopticlabs.gravel.voicemqtt.copy.VoiceText
import com.anopticlabs.gravel.voicemqtt.settings.VoiceSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The one speaker in this process, reachable from [SpeakingNoticeService] when the user taps Stop.
 *
 * A plain registry rather than a container lookup: the module has no dependency injection of its own, and the
 * service is started by the notification rather than by whoever built the speaker, so there is no constructor
 * to hand the speaker through. There is exactly one `AndroidSpeechOutput` in the app for its whole lifetime
 * (`buildVoiceMqttEngine` builds it once), which is what makes a single slot honest.
 */
object SpeakingStop {

    @Volatile
    private var handler: (() -> Unit)? = null

    /** Called once by the speaker when it is built; the handler aborts the utterance and clears the queue. */
    fun bind(handler: () -> Unit) {
        this.handler = handler
    }

    /** Aborts whatever is being spoken. False when the speaker is not built, which is not an error. */
    fun abortNow(): Boolean {
        val handler = handler ?: return false
        handler()
        return true
    }
}

/**
 * The notice that owns the Stop button, reachable from [SpeakingNoticeService].
 *
 * The button reaches [SpeakingStop] normally: the speaker aborts the utterance and, on its way out, tells this
 * notice that speech ended, which is what decides whether the notification reverts to the ready face or goes.
 * The speaker can, in principle, be absent from the process the button outlived, so this second slot is the
 * route for "speech ended and there was no speaker to say so".
 */
object SpeakingNoticeLink {

    @Volatile
    private var endNotice: (() -> Unit)? = null

    /** Bound once by the notice when it is built. */
    fun bind(handler: () -> Unit) {
        this.endNotice = handler
    }

    /** Ends the speaking face without a speaker to consult. False when no notice is bound. */
    fun endNotice(): Boolean {
        val handler = endNotice ?: return false
        handler()
        return true
    }
}

/**
 * Holds the notice — and the foreground state that makes it dependable — for as long as it is honest.
 *
 * Two states, one notification:
 *
 * - **Ready.** The app is on screen, or a turn it published is still young enough for an answer: the service is
 *   warm, and the notification says the voice assistant is ready and that replies will appear here. It says
 *   nothing about speaking, because nothing is speaking.
 * - **Speaking.** A reply plays: the same notification id is re-posted with the reply's beginning and the Stop
 *   action.
 *
 * The warmth is decided by the pure [SpeakingWarmTracker] and the speaking face by [SpeakingNoticeTracker], both
 * combined by [noticeFace] — this class holds no rule of its own, only the calls that carry out the decision.
 * The one thing it adds is the deferral the platform needs: a warmth that lapses mid-utterance is not acted on
 * until the utterance ends, because tearing the foreground service down under playing audio is exactly what the
 * foreground service exists to prevent.
 *
 * Starting the service is best-effort and always has been: with the warmth lapsed and the app pocketed, a start
 * can still be refused, and then the reply is spoken unannounced instead of not at all. The warmth is what makes
 * that the exception rather than the rule.
 */
class AndroidSpeakingNotice(
    context: Context,
    /** The app's own visibility signal, registered with the Application before any activity runs. */
    visibility: AppVisibility,
    /** The stored settings, read for the three switches that make a warm notice mean anything. */
    settings: StateFlow<VoiceSettings>,
    scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val appContext = context.applicationContext
    private val speaking = SpeakingNoticeTracker()
    private val warmth = SpeakingWarmTracker(now = clock)

    /** Serialises the two trackers against the service calls, which arrive from the engine's callback thread too. */
    private val lock = Any()

    private val handler = Handler(Looper.getMainLooper())

    /** The window closing: re-decided against the clock, and deferred while a reply is still playing. */
    private val lapse = Runnable { apply(synchronized(lock) { warmth.onLapse() }) }

    init {
        // Called with the current state straight away and then on the main thread each time it changes; a resumed
        // activity is the only circumstance in which the platform is known to permit this start.
        visibility.addResumedListener { resumed -> apply(synchronized(lock) { warmth.onVisible(resumed) }) }
        scope.launch {
            settings.collect { current ->
                apply(synchronized(lock) { warmth.onFeatureEnabled(current.warmsSpeakingNotice) })
            }
        }
        SpeakingNoticeLink.bind(::end)
    }

    fun begin(decision: PlaybackDecision, reply: String) {
        val command = synchronized(lock) { speaking.begin(decision) }
        when (command) {
            SpeakingNoticeCommand.SHOW, SpeakingNoticeCommand.UPDATE -> speak(reply)
            SpeakingNoticeCommand.HIDE, SpeakingNoticeCommand.NONE -> Unit
        }
    }

    fun end() {
        if (synchronized(lock) { speaking.end() } != SpeakingNoticeCommand.HIDE) return
        // The utterance is over: what is left is the ready face if the service is still warm, and nothing at all
        // otherwise. A warmth that lapsed during the utterance ends here, not under the audio.
        when (face()) {
            NoticeFace.READY -> send(SpeakingNoticeService.ACTION_READY, null)
            NoticeFace.SPEAKING -> Unit
            NoticeFace.NONE -> stop()
        }
        schedule()
    }

    /** A published turn or a received reply: it refreshes the window, and never starts the warmth by itself. */
    fun activity() {
        apply(synchronized(lock) { warmth.onVoiceActivity() })
    }

    private fun face(): NoticeFace = synchronized(lock) { noticeFace(warmth.isWarm, speaking.isSpeaking) }

    private fun apply(command: WarmNoticeCommand) {
        when (command) {
            // A start while a reply is playing needs nothing: the speaking notification already holds the
            // foreground, and the warmth is recorded for when the utterance ends.
            WarmNoticeCommand.START -> if (face() == NoticeFace.READY) send(SpeakingNoticeService.ACTION_READY, null)
            // Likewise a lapse while playing is deferred: end() carries it out.
            WarmNoticeCommand.STOP -> if (face() == NoticeFace.NONE) stop()
            WarmNoticeCommand.NONE -> Unit
        }
        schedule()
    }

    /** Arms the timer for the window the tracker currently names, or clears it when nothing is on a clock. */
    private fun schedule() {
        handler.removeCallbacks(lapse)
        val at = synchronized(lock) { warmth.lapseAtMillis } ?: return
        handler.postDelayed(lapse, (at - clock()).coerceAtLeast(0L))
    }

    private fun speak(reply: String) {
        send(SpeakingNoticeService.ACTION_SPEAK, reply)
    }

    private fun send(action: String, reply: String?) {
        val intent = Intent(appContext, SpeakingNoticeService::class.java).setAction(action)
        if (reply != null) intent.putExtra(SpeakingNoticeService.EXTRA_REPLY, reply)
        try {
            ContextCompat.startForegroundService(appContext, intent)
        } catch (e: Exception) {
            // Expected only once the warmth has lapsed with the app in the background, which is the case the
            // warmth exists to make rare. The reply is still spoken; only the notice is missing.
            logger.w { "speaking notice not started: ${e::class.java.simpleName}" }
        }
    }

    private fun stop() {
        handler.removeCallbacks(lapse)
        runCatching { appContext.stopService(Intent(appContext, SpeakingNoticeService::class.java)) }
            .onFailure { e -> logger.w { "speaking notice not stopped: ${e::class.java.simpleName}" } }
    }

    private companion object {
        val logger = Logger.withTag("AndroidSpeakingNotice")
    }
}

/**
 * Holds the one notification — ready, or speaking — and the foreground state the platform needs for both.
 *
 * A foreground service rather than a bare ongoing notification: the system takes the notification down with
 * `STOP_FOREGROUND_REMOVE` when the process dies or the speech path fails, so a notice cannot be stranded on
 * screen by a crash, a kill or an engine that stops answering. The service owns no work of its own — the engine
 * does the speaking, the loop does the receiving — it owns the notice, the Stop action, and the foreground
 * state that lets a reply arriving in a pocket post its notice without a background start.
 *
 * Every start command carries the face it wants: [ACTION_READY] for the warm idle state, [ACTION_SPEAK] with the
 * reply for the speaking state, [ACTION_STOP] for the user's button. Starting the service again while it is up is
 * how the face changes, and it is re-entering the same notification id rather than posting a second one.
 *
 * The Stop action is an explicit intent straight back here, not a broadcast: the module would have to register
 * a receiver dynamically for a broadcast, and a `PendingIntent` to this service survives the process being
 * alive but idle, which is the case that matters (the user reaches for Stop after the reply has finished
 * arriving). On Stop the speaker is told to abort the current utterance and clear anything queued behind it, and
 * the notice itself is left to decide what it becomes next — the ready face while the service is still warm,
 * removal otherwise.
 *
 * `mediaPlayback` is the honest type: this is audio playing to the user, and Android 17 requires a running
 * foreground service — not of type `shortService` — for an app that plays audio while it is not visible, which
 * is exactly the reply arriving with the phone in a pocket. It needs no runtime permission and no media
 * session; the app has no transport controls to publish, only a Stop.
 */
class SpeakingNoticeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val reply = intent?.getStringExtra(EXTRA_REPLY).orEmpty()
        when (intent?.action) {
            ACTION_STOP -> {
                // Abort first: the sound and the notification end together, and what is left of the notice —
                // ready or nothing — is decided by the speaker's own ending, never by this branch.
                if (!SpeakingStop.abortNow()) SpeakingNoticeLink.endNotice()
            }
            ACTION_SPEAK -> foreground(speakingNotification(reply), startId)
            // ACTION_READY, and a null intent: the warm idle face. Nothing here is started sticky, so a null
            // intent is not a redelivery; ready is the honest default rather than a stale reply's text.
            else -> foreground(readyNotification(), startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun foreground(notification: Notification, startId: Int) {
        try {
            // A second start is a change of face in the same notification, not a second notification beside it.
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, TYPE)
        } catch (e: Exception) {
            // Without a foreground notification the service must not stay up (and must not be left started).
            logger.w { "speaking service could not enter the foreground: ${e::class.java.simpleName}" }
            stopSelf(startId)
        }
    }

    /** Warm and idle: what it says is that replies will appear here, never that one is being spoken. */
    private fun readyNotification(): Notification {
        val copy = AndroidVoiceText(this)
        return base(
            copy,
            copy.of(VoiceCopy(VoiceKeys.NOTICE_READY_TITLE)),
            copy.of(VoiceCopy(VoiceKeys.NOTICE_READY_BODY)),
        ).build()
    }

    private fun speakingNotification(reply: String): Notification {
        val copy = AndroidVoiceText(this)
        val body = copy.of(VoiceCopy(VoiceKeys.SPEAKING_BODY, SpeakingPreview.of(reply)))
        return base(copy, copy.of(VoiceCopy(VoiceKeys.SPEAKING_TITLE)), body)
            .addAction(0, copy.of(VoiceCopy(VoiceKeys.SPEAKING_STOP)), stopIntent())
            .build()
    }

    /**
     * Both faces share one builder, one id and one channel on purpose. Their behaviour is identical — low
     * importance, silent, ongoing, no timestamp — and only the words differ, so a second channel would relabel
     * the same silence and a second id would put two entries in the shade for one thing.
     */
    private fun base(copy: VoiceText, title: String, body: String): NotificationCompat.Builder {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                copy.of(VoiceCopy(VoiceKeys.NOTICE_CHANNEL)),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setSound(null, null) },
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)
            ?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE) }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_speaking_notice)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
    }

    private fun stopIntent(): PendingIntent = PendingIntent.getService(
        this,
        0,
        Intent(this, SpeakingNoticeService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        /** The action the Stop button carries; explicit, so nothing else can route it. */
        const val ACTION_STOP: String = "com.anopticlabs.gravel.voicemqtt.action.STOP_SPEECH"

        /** The warm idle face: the service holds the foreground, and nothing is being spoken. */
        const val ACTION_READY: String = "com.anopticlabs.gravel.voicemqtt.action.READY"

        /** The speaking face: the reply being played rides along in [EXTRA_REPLY]. */
        const val ACTION_SPEAK: String = "com.anopticlabs.gravel.voicemqtt.action.SPEAK"

        /** The reply being spoken; the service shows its beginning. */
        const val EXTRA_REPLY: String = "com.anopticlabs.gravel.voicemqtt.extra.REPLY"

        /**
         * Kept from the first version of this notice so an installed app keeps the channel, and any importance
         * the user set on it, instead of orphaning it beside a fresh one. The id is never shown to anyone; the
         * name below is what the notification settings screen lists.
         */
        private const val CHANNEL_ID = "voice_speaking"

        /** One id for both faces: the ready notice becomes the speaking notice in place. */
        private const val NOTIFICATION_ID = 0x5643
        private val TYPE = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        private val logger = Logger.withTag("SpeakingNoticeService")
    }
}
