package com.anopticlabs.gravel.voicemqtt.speech

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.voicemqtt.R
import com.anopticlabs.gravel.voicemqtt.copy.AndroidVoiceText
import com.anopticlabs.gravel.voicemqtt.copy.VoiceCopy
import com.anopticlabs.gravel.voicemqtt.copy.VoiceKeys
import com.anopticlabs.gravel.voicemqtt.copy.VoiceText

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
 * Starts and stops [SpeakingNoticeService], and decides — through the pure [SpeakingNoticeTracker] — whether
 * there is anything to start at all.
 *
 * The speaker calls [begin] with the decision that allowed the utterance and [end] for every ending, so the
 * notification can never appear for a reply that was held back, and can never outlive the sound it describes.
 * Starting the service is best-effort: a platform that refuses a foreground service started from the
 * background must not cost the user the reply, so a refusal is logged and the speech goes ahead unannounced.
 */
class AndroidSpeakingNotice(
    context: Context,
    private val text: VoiceText = AndroidVoiceText(context),
) {

    private val appContext = context.applicationContext
    private val tracker = SpeakingNoticeTracker()

    /** Serialises the tracker with the service calls, which arrive from the engine's callback thread too. */
    private val lock = Any()

    fun begin(decision: PlaybackDecision, reply: String) {
        val command = synchronized(lock) { tracker.begin(decision) }
        when (command) {
            SpeakingNoticeCommand.SHOW, SpeakingNoticeCommand.UPDATE -> show(reply)
            SpeakingNoticeCommand.HIDE, SpeakingNoticeCommand.NONE -> Unit
        }
    }

    fun end() {
        if (synchronized(lock) { tracker.end() } != SpeakingNoticeCommand.HIDE) return
        hide()
    }

    private fun show(reply: String) {
        val intent = Intent(appContext, SpeakingNoticeService::class.java)
            .putExtra(SpeakingNoticeService.EXTRA_REPLY, reply)
        try {
            ContextCompat.startForegroundService(appContext, intent)
        } catch (e: Exception) {
            // Expected from the background without an exemption, the same way the notebook's own service is
            // started. The reply is still spoken; only the notice is missing.
            logger.w { "speaking notice not started: ${e::class.java.simpleName}" }
        }
    }

    private fun hide() {
        runCatching { appContext.stopService(Intent(appContext, SpeakingNoticeService::class.java)) }
            .onFailure { e -> logger.w { "speaking notice not stopped: ${e::class.java.simpleName}" } }
    }

    private companion object {
        val logger = Logger.withTag("AndroidSpeakingNotice")
    }
}

/**
 * Holds the notification that says a reply is being spoken, for exactly as long as it is being spoken.
 *
 * A foreground service rather than a bare ongoing notification: the system takes the notification down with
 * `STOP_FOREGROUND_REMOVE` when the process dies or the speech path fails, so a "speaking" notice cannot be
 * stranded on screen by a crash, a kill or an engine that stops answering. The service owns no work of its own
 * — the engine does the speaking — it owns the notice and the Stop action.
 *
 * The Stop action is an explicit intent straight back here, not a broadcast: the module would have to register
 * a receiver dynamically for a broadcast, and a `PendingIntent` to this service survives the process being
 * alive but idle, which is the case that matters (the user reaches for Stop after the reply has finished
 * arriving). On Stop the speaker is told to abort the current utterance and clear anything queued behind it,
 * then the notice is removed and the service leaves the foreground.
 *
 * `mediaPlayback` is the honest type: this is audio playing to the user, and Android 17 requires a running
 * foreground service — not of type `shortService` — for an app that plays audio while it is not visible, which
 * is exactly the reply arriving with the phone in a pocket. It needs no runtime permission and no media
 * session; the app has no transport controls to publish, only a Start and a Stop.
 */
class SpeakingNoticeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Abort first, then take the notice down: the sound and the notification end together.
            SpeakingStop.abortNow()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val reply = intent?.getStringExtra(EXTRA_REPLY).orEmpty()
        try {
            // A second start is the newer reply of the same turn: the same id re-posts the one notification
            // rather than stacking a second beside it.
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(reply), TYPE)
        } catch (e: Exception) {
            // Without a foreground notification the service must not stay up (and must not be left started).
            logger.w { "speaking service could not enter the foreground: ${e::class.java.simpleName}" }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun notification(reply: String): Notification {
        val copy = AndroidVoiceText(this)
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                copy.of(VoiceCopy(VoiceKeys.SPEAKING_CHANNEL)),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setSound(null, null) },
        )
        val body = copy.of(VoiceCopy(VoiceKeys.SPEAKING_BODY, SpeakingPreview.of(reply)))
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, SpeakingNoticeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)
            ?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE) }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_speaking_notice)
            .setContentTitle(copy.of(VoiceCopy(VoiceKeys.SPEAKING_TITLE)))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(0, copy.of(VoiceCopy(VoiceKeys.SPEAKING_STOP)), stop)
            .build()
    }

    companion object {
        /** The action the Stop button carries; explicit, so nothing else can route it. */
        const val ACTION_STOP: String = "com.anopticlabs.gravel.voicemqtt.action.STOP_SPEECH"

        /** The reply being spoken; the service shows its beginning. */
        const val EXTRA_REPLY: String = "com.anopticlabs.gravel.voicemqtt.extra.REPLY"

        private const val CHANNEL_ID = "voice_speaking"
        private const val NOTIFICATION_ID = 0x5643
        private val TYPE = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        private val logger = Logger.withTag("SpeakingNoticeService")
    }
}
