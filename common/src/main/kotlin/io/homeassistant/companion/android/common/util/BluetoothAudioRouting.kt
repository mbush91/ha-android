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

/** Provides an opt-in Bluetooth microphone route for a headset-initiated Assist session. */
interface BluetoothAudioRouting {
    suspend fun start(): Boolean
    fun stop()
}

/**
 * Only headset-initiated Assist sessions use this router. Normal Assist and background wake-word
 * sessions retain their existing microphone behavior. Waits for the route before recording starts.
 */
class AndroidBluetoothAudioRouting(
    private val context: Context,
    private val audioManager: AudioManager,
    private val useModernRouting: Boolean = SdkVersion.isAtLeast(Build.VERSION_CODES.S),
) : BluetoothAudioRouting {

    private companion object {
        const val CONNECTION_TIMEOUT_MS = 3_000L
        const val ROUTE_POLL_INTERVAL_MS = 50L
    }

    private var ownsRoute = false

    @SuppressLint("MissingPermission", "NewApi")
    override suspend fun start(): Boolean = try {
        if (useModernRouting) {
            startCommunicationDevice()
        } else {
            startLegacySco()
        }
    } catch (e: SecurityException) {
        Timber.w(e, "Bluetooth audio permission denied; using phone microphone")
        stop()
        false
    }

    /** Android 12+: select an actual connected SCO communication device, not device capability. */
    @SuppressLint("MissingPermission", "NewApi")
    private suspend fun startCommunicationDevice(): Boolean {
        val headset = audioManager.availableCommunicationDevices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }

        // Only release a communication route that this instance successfully selected.
        return if (headset == null) {
            false
        } else if (audioManager.communicationDevice?.id == headset.id) {
            true
        } else if (!audioManager.setCommunicationDevice(headset)) {
            false
        } else {
            ownsRoute = true
            var ready = false
            try {
                ready = withTimeoutOrNull(CONNECTION_TIMEOUT_MS) {
                    while (audioManager.communicationDevice?.id != headset.id) {
                        delay(ROUTE_POLL_INTERVAL_MS)
                    }
                    true
                } == true
                if (!ready) Timber.w("Bluetooth communication device did not become ready")
                ready
            } finally {
                // Also releases the route if the coroutine was cancelled during setup.
                if (!ready) stop()
            }
        }
    }

    /** Android 11 and earlier: start SCO and wait for the actual connected broadcast. */
    @SuppressLint("MissingPermission")
    private suspend fun startLegacySco(): Boolean {
        if (!audioManager.isBluetoothScoAvailableOffCall ||
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).none {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        ) {
            return false
        }

        var connected = false
        try {
            connected = withTimeoutOrNull(CONNECTION_TIMEOUT_MS) {
                callbackFlow {
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            when (
                                intent.getIntExtra(
                                    AudioManager.EXTRA_SCO_AUDIO_STATE,
                                    AudioManager.SCO_AUDIO_STATE_ERROR,
                                )
                            ) {
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

            if (!connected) Timber.w("Bluetooth SCO was not established, using phone microphone")
            return connected
        } finally {
            if (!connected) stop()
        }
    }

    @SuppressLint("MissingPermission", "NewApi")
    override fun stop() {
        if (!ownsRoute) return
        ownsRoute = false
        try {
            if (useModernRouting) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.stopBluetoothSco()
            }
        } catch (e: RuntimeException) {
            Timber.w(e, "Unable to release Bluetooth audio route")
        }
    }
}
