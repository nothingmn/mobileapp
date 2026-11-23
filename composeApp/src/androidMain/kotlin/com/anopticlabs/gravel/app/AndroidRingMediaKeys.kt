package com.anopticlabs.gravel.app

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.ring.button.RingButtonAction
import com.anopticlabs.gravel.ring.button.RingMediaKeys

/**
 * Sends the ring's button to the phone's media session as a media key, the way upstream's
 * `:experimental` `RingSync.android.kt` does it: `AudioManager.dispatchMediaKeyEvent` with the key's ACTION_DOWN and
 * ACTION_UP pair at the same event time.
 *
 * The event time is `SystemClock.uptimeMillis`, the base `KeyEvent` event times are measured from, rather than the
 * wall clock. Nothing here holds a media session of its own: whichever app is playing receives the key, and with
 * nothing playing the phone swallows it, which is why a gesture can feel like it did nothing.
 */
class AndroidRingMediaKeys(private val context: Context) : RingMediaKeys {
    override fun send(action: RingButtonAction) {
        val keyCode = when (action) {
            RingButtonAction.PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            RingButtonAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            RingButtonAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            RingButtonAction.NOTHING -> return
        }
        val audioManager = context.getSystemService(AudioManager::class.java)
        if (audioManager == null) {
            logger.w { "no audio manager: $action was not sent" }
            return
        }
        val at = SystemClock.uptimeMillis()
        audioManager.dispatchMediaKeyEvent(KeyEvent(at, at, KeyEvent.ACTION_DOWN, keyCode, 0))
        audioManager.dispatchMediaKeyEvent(KeyEvent(at, at, KeyEvent.ACTION_UP, keyCode, 0))
        logger.d { "ring button: media key $action sent" }
    }

    private companion object {
        val logger = Logger.withTag("AndroidRingMediaKeys")
    }
}
