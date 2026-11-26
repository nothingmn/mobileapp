package com.anopticlabs.gravel.notebook.capture

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.R
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The capture chime on Android: one short sample of the app's own, played with media usage and no audio-focus
 * request, so it mixes with whatever is playing — nothing is paused, stopped or ducked beyond what the platform
 * does by itself. Where the sound leaves the phone is not decided here: the stream follows the active media route,
 * and whether the app may make the sound at all is [ChimeRouting]'s answer, whose Bluetooth-only rule is what keeps
 * the phone's speaker from ever standing in for a headset. Nothing here posts a notification, wakes the screen or
 * vibrates.
 *
 * [load] reads the sample on [scope] and must be called when the graph is built, so the first capture does not pay
 * for it; a chime asked for before the sample is ready is played as soon as it is. [silence] reads the phone's state
 * at each chime, so silent mode and Do Not Disturb are obeyed as they are now rather than as they were at startup.
 */
class AndroidCaptureChime(context: Context, private val scope: CoroutineScope) : ChimePlayer {
    private val context = context.applicationContext

    private val pool = SoundPool.Builder()
        .setMaxStreams(1)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val sample = AtomicInteger(NOT_LOADED)
    private val wanted = AtomicBoolean(false)

    /**
     * Whether the phone's own state holds a chime back right now, and which state does: silent or vibrate mode, or
     * Do Not Disturb. Unreadable services count as silence rather than as permission.
     */
    fun silence(): ChimeHeldReason? {
        val silenced = context.getSystemService(AudioManager::class.java)?.ringerMode != AudioManager.RINGER_MODE_NORMAL
        val filter = context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter
        return chimeSilence(silenced = silenced, doNotDisturb = filter != NotificationManager.INTERRUPTION_FILTER_ALL)
    }

    /** Loads the sample; call once from wherever the object graph is built, never from a capture's own path. */
    fun load() {
        pool.setOnLoadCompleteListener { _, id, status ->
            if (status != 0) return@setOnLoadCompleteListener
            sample.set(id)
            if (wanted.getAndSet(false)) pool.play(id, 1f, 1f, 0, 0, 1f)
        }
        scope.launch(Dispatchers.IO) {
            try {
                context.resources.openRawResourceFd(R.raw.capture_chime).use { pool.load(it, 1) }
            } catch (e: Exception) {
                logger.w(e) { "the capture chime did not load" }
            }
        }
    }

    override fun play() {
        val id = sample.get()
        if (id == NOT_LOADED) {
            wanted.set(true)
            return
        }
        pool.play(id, 1f, 1f, 0, 0, 1f)
    }

    private companion object {
        const val NOT_LOADED = 0
        val logger = Logger.withTag("CaptureChime")
    }
}
