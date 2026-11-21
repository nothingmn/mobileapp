package com.anopticlabs.gravel.voicemqtt.speech

import android.Manifest
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.voicemqtt.settings.VoiceSettings

/** One already-paired Bluetooth audio device, as the picker shows it. */
data class VoiceHeadsetDevice(val address: String, val name: String)

/**
 * The Bluetooth gate, in one place, because it is the operator's rule and the reason a reply never comes out
 * of the phone speaker.
 *
 * The question it answers is asked at the moment of speaking and answered from the audio outputs that are
 * **connected right now** (`AudioManager.getDevices(GET_DEVICES_OUTPUTS)` filtered to A2DP, SCO and BLE
 * headsets), never from the pairing list and never from the toggle alone: a paired headset that is switched
 * off is not an output, and the phone speaker must not take its place.
 */
class BluetoothAudioGate(private val context: Context) {

    private val audioManager: AudioManager? = context.getSystemService(AudioManager::class.java)

    /** The Bluetooth audio outputs connected at this instant, empty when none is. */
    fun connectedOutputs(): List<AudioDeviceInfo> {
        val manager = audioManager ?: return emptyList()
        return try {
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.type in HEADSET_TYPES }
        } catch (e: SecurityException) {
            logger.w { "connected audio outputs not readable: ${e::class.java.simpleName}" }
            emptyList()
        }
    }

    /**
     * Whether a reply may be spoken now. [VoiceSettings.headsetAddress] picks the one device the user chose;
     * null means any of them. The headset-only preference has no OFF position (there is no
     * always-speak-without-a-headset mode), so its value is not what decides this — the connected outputs are.
     */
    fun maySpeak(settings: VoiceSettings): Boolean {
        val connected = connectedOutputs()
        if (connected.isEmpty()) return false
        val wanted = settings.headsetAddress ?: return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return true
        return connected.any { it.address.equals(wanted, ignoreCase = true) }
    }

    /** The paired Bluetooth audio devices the user may pick between; empty without the permission. */
    fun pairedDevices(): List<VoiceHeadsetDevice> {
        if (!hasConnectPermission()) return emptyList()
        return try {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
            if (!adapter.isEnabled) return emptyList()
            adapter.bondedDevices.orEmpty()
                .filter { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO }
                .mapNotNull { device -> device.addressOrNull()?.let { VoiceHeadsetDevice(it, device.namedOrNull()) } }
                .sortedBy { it.name.lowercase() }
        } catch (e: SecurityException) {
            logger.w { "paired Bluetooth devices not readable: ${e::class.java.simpleName}" }
            emptyList()
        }
    }

    fun canListDevices(): Boolean = hasConnectPermission()

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    // Both of these throw SecurityException without BLUETOOTH_CONNECT, so each read is guarded.
    private fun BluetoothDevice.addressOrNull(): String? = try {
        address
    } catch (e: SecurityException) {
        null
    }

    private fun BluetoothDevice.namedOrNull(): String = try {
        name?.takeIf { it.isNotBlank() } ?: addressOrNull() ?: "Bluetooth device"
    } catch (e: SecurityException) {
        addressOrNull() ?: "Bluetooth device"
    }

    private companion object {
        val logger = Logger.withTag("BluetoothAudioGate")
        val HEADSET_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
    }
}
