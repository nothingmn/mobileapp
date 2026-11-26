package com.anopticlabs.gravel.app

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.anopticlabs.gravel.notebook.capture.ChimeHeldReason
import com.anopticlabs.gravel.notebook.capture.ChimeRouting

/**
 * The capture chime's routing with `VOICE_MQTT_ENABLED` off: there is no switch and no pinned device to read, so
 * the chime takes the conservative half of the voice output policy — a Bluetooth audio output must be connected at
 * this moment, and the phone's speaker never stands in for one. Unconfigured must not mean loud.
 *
 * The read is the same one `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` gives the voice loop, filtered to its
 * headset types; the module that owns that gate is not on this build's classpath, so the filter is restated here.
 */
internal class HeadsetOnlyChimeRouting(context: Context) : ChimeRouting {

    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)

    override fun held(): ChimeHeldReason? {
        val bluetooth = try {
            audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty().any { it.type in HEADSET_TYPES }
        } catch (e: SecurityException) {
            false
        }
        return if (bluetooth) null else ChimeHeldReason.NO_BLUETOOTH_OUTPUT
    }

    private companion object {
        val HEADSET_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
    }
}
