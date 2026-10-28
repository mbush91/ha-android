package io.homeassistant.companion.android.common.util

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Routes a headset-initiated Assist recording through the Bluetooth microphone.
 * The connection must be ready before an 8 kHz AudioRecord is created.
 */
interface BluetoothAudioRouting {
    suspend fun start(): Boolean
    fun stop()
}

/**
 * Opt-in audio routing for a Bluetooth voice command. Normal Assist sessions and background wake
 * word recording do not request a Bluetooth communication route.
 */
class AndroidBluetoothAudioRouting(
    private val context: Context,
    private val audioManager: AudioManager,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : BluetoothAudioRouting {

    private var ownsRoute = false

    @SuppressLint("MissingPermission")
    override suspend fun start(): Boolean {
        if (sdkInt >= Build.VERSION_CODES.S) {
            val headset = audioManager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            } ?: return false

            // Do not clear a route selected by another application on cleanup.
            if (audioManager.communicationDevice?.id == headset.id) return true

            val accepted = try {
                audioManager.setCommunicationDevice(headset)
            } catch (e: SecurityException) {
                Timber.w(e, "Bluetooth audio routing permission denied")
                false
            }
            if (!accepted) return false

            ownsRoute = true
            val connected = withTimeoutOrNull(3000) {
                while (audioManager.communicationDevice?.id != headset.id) {
                    delay(50)
                }
                true
            } == true
            if (!connected) {
                Timber.w("Bluetooth communication device did not become ready")
                stop()
            }
            return connected
        }

        // Legacy SCO is asynchronous. Observe the connected broadcast before starting it.
        if (!audioManager.isBluetoothScoAvailableOffCall ||
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).none {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        ) {
            return false
        }

        val connected = withTimeoutOrNull(3000) {
            callbackFlow {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        when (intent.getIntExtra(
                            AudioManager.EXTRA_SCO_AUDIO_STATE,
                            AudioManager.SCO_AUDIO_STATE_ERROR,
                        )) {
                            AudioManager.SCO_AUDIO_STATE_CONNECTED -> trySend(true)
                            AudioManager.SCO_AUDIO_STATE_ERROR -> trySend(false)
                        }
                    }
                }
                context.registerReceiver(receiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
                try {
                    @Suppress("DEPRECATION")
                    audioManager.startBluetoothSco()
                    ownsRoute = true
                } catch (e: SecurityException) {
                    Timber.w(e, "Unable to start Bluetooth SCO")
                    trySend(false)
                }
                awaitClose { context.unregisterReceiver(receiver) }
            }.first()
        } == true

        if (!connected) {
            Timber.w("Bluetooth SCO was not established, using phone microphone")
            stop()
        }
        return connected
    }

    @SuppressLint("MissingPermission")
    override fun stop() {
        if (!ownsRoute) return
        ownsRoute = false
        try {
            if (sdkInt >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.stopBluetoothSco()
            }
        } catch (e: SecurityException) {
            Timber.w(e, "Unable to release Bluetooth audio route")
        }
    }
}
