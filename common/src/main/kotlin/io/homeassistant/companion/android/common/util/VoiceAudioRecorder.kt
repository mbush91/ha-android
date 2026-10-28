package io.homeassistant.companion.android.common.util

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder.AudioSource
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Sample rate in Hz used by all voice pipelines and wake word detection.
 * Anything above would get downsampled to 16kHz in core, so we don't need
 * to capture at a higher frequency.
 */
const val VOICE_SAMPLE_RATE = 16000

/**
 * Fallback sample rate in Hz. Per the Android documentation, 44100Hz is
 * the only rate guaranteed to work on all devices. Used when the device
 * does not support [VOICE_SAMPLE_RATE].
 */
private const val FALLBACK_SAMPLE_RATE = 44100

/** Bluetooth SCO microphone input is 8 kHz; output must still be 16 kHz for Assist. */
private const val BLUETOOTH_SAMPLE_RATE = 8000

// Only format "[g]uaranteed to be supported by devices"
private const val VOICE_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

private const val VOICE_AUDIO_SOURCE = AudioSource.MIC
private const val VOICE_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO

/** Duration of each read chunk in milliseconds. */
private const val READ_CHUNK_DURATION_MS = 10

/** Number of samples per read chunk at [VOICE_SAMPLE_RATE] (10ms at 16kHz). */
private const val READ_CHUNK_SIZE = VOICE_SAMPLE_RATE * READ_CHUNK_DURATION_MS / 1000

/** Number of samples per read chunk at [FALLBACK_SAMPLE_RATE] (10ms at 44.1kHz). */
private const val FALLBACK_READ_CHUNK_SIZE = FALLBACK_SAMPLE_RATE * READ_CHUNK_DURATION_MS / 1000

/**
 * Converts a [ShortArray] of PCM 16-bit audio samples to a little-endian [ByteArray].
 *
 * Each 16-bit sample is split into two bytes (low byte first) matching the format
 * expected by the Home Assistant voice pipeline.
 */
fun ShortArray.toAudioBytes(): ByteArray {
    val byteArray = ByteArray(size * 2)
    for (i in indices) {
        val sample = this[i].toInt()
        byteArray[i * 2] = (sample and 0xFF).toByte()
        byteArray[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
    }
    return byteArray
}

/**
 * Provides audio recording as a shared [Flow] of [ShortArray] chunks.
 *
 * Multiple collectors share the same underlying [AudioRecord] instance. The first
 * collector triggers [AudioRecord] creation and starts recording; additional collectors
 * receive the same audio stream. When the last collector cancels, recording stops
 * and the [AudioRecord] is released immediately.
 *
 * This sharing is critical on pre-Android 10 devices where only one [AudioRecord]
 * can be active at a time. Both the Assist pipeline and wake word detection can
 * safely collect from the same [VoiceAudioRecorder] concurrently.
 */
class VoiceAudioRecorder(
    private val audioRecordFactory: () -> AudioRecord = ::createVoiceAudioRecord,
    private val recorderDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val sharingScope: CoroutineScope = CoroutineScope(Job() + recorderDispatcher),
    private val bluetoothAudioRouting: BluetoothAudioRouting? = null,
    private val bluetoothAudioRecordFactory: () -> AudioRecord = ::createBluetoothAudioRecord,
) {
    private val mutex = Mutex()
    private var sharedFlow: Flow<ShortArray>? = null

    /**
     * Returns a shared flow of audio samples as [ShortArray] chunks.
     *
     * The underlying [AudioRecord] read loop runs on [recorderDispatcher] (defaults to
     * [Dispatchers.IO]) because [AudioRecord.read] is a blocking call. Each emission
     * contains one chunk of PCM 16-bit mono samples.
     *
     * The flow is shared via [shareIn] with [SharingStarted.WhileSubscribed]:
     * - First collector starts the [AudioRecord] and begins emitting
     * - Additional collectors receive the same audio stream
     * - When the last collector cancels, [AudioRecord] stops immediately
     */
    suspend fun audioData(): Flow<ShortArray> = mutex.withLock {
        sharedFlow ?: createSharedFlow().also { sharedFlow = it }
    }

    private fun createSharedFlow(): Flow<ShortArray> {
        val upstream = channelFlow {
            var bluetoothConnected = bluetoothAudioRouting?.start() == true
            try {
                val recorder = if (bluetoothConnected) {
                    val bluetoothRecorder = runCatching { bluetoothAudioRecordFactory() }
                        .onFailure { Timber.w(it, "Unable to create Bluetooth voice recorder") }
                        .getOrNull()
                    if (bluetoothRecorder?.state == AudioRecord.STATE_INITIALIZED) {
                        bluetoothRecorder
                    } else {
                        bluetoothRecorder?.release()
                        bluetoothAudioRouting?.stop()
                        bluetoothConnected = false
                        audioRecordFactory()
                    }
                } else {
                    audioRecordFactory()
                }

                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    recorder.release()
                    Timber.e("AudioRecord failed to initialize")
                    return@channelFlow
                }

                val actualSampleRate = recorder.sampleRate
                val chunkSize = actualSampleRate * READ_CHUNK_DURATION_MS / 1000

                if (actualSampleRate != VOICE_SAMPLE_RATE) {
                    Timber.d("Recording at ${actualSampleRate}Hz, resampling to ${VOICE_SAMPLE_RATE}Hz")
                }

                try {
                    recorder.startRecording()
                    val buffer = ShortArray(chunkSize)

                    while (isActive) {
                        val readResult = recorder.read(buffer, 0, chunkSize)
                        when {
                            readResult > 0 -> {
                                val chunk = buffer.copyOf(readResult)
                                val output = when {
                                    actualSampleRate > VOICE_SAMPLE_RATE ->
                                        downsample(chunk, fromRate = actualSampleRate, toRate = VOICE_SAMPLE_RATE)
                                    actualSampleRate < VOICE_SAMPLE_RATE ->
                                        upsample(chunk, fromRate = actualSampleRate, toRate = VOICE_SAMPLE_RATE)
                                    else -> chunk
                                }
                                send(output)
                            }

                            readResult < 0 -> {
                                Timber.e("AudioRecord read error: $readResult")
                                break
                            }
                        }
                    }
                } finally {
                    try {
                        recorder.stop()
                    } catch (e: IllegalStateException) {
                        Timber.e(e, "Error stopping AudioRecord")
                    }
                    recorder.release()
                }
            } finally {
                if (bluetoothConnected) bluetoothAudioRouting?.stop()
            }
        }.flowOn(recorderDispatcher)

        return upstream.shareIn(
            scope = sharingScope,
            started = SharingStarted.WhileSubscribed(),
            replay = 0,
        )
    }
}

/**
 * Creates a pre-configured [AudioRecord] for voice input.
 *
 * Attempts to record at [VOICE_SAMPLE_RATE] (16kHz) first since that matches what
 * downstream consumers expect. If the device does not support 16kHz, falls back to
 * [FALLBACK_SAMPLE_RATE] (44.1kHz) which is the only rate guaranteed by the Android
 * documentation to work on all devices. The caller is responsible for downsampling
 * the 44.1kHz audio to 16kHz before passing it to consumers.
 *
 * @see [AudioRecord](https://cs.android.com/android/platform/superproject/+/android-16.0.0_r4:frameworks/base/media/java/android/media/AudioRecord.java;l=309)
 */
@SuppressLint("MissingPermission")
private fun createVoiceAudioRecord(): AudioRecord {
    try {
        val recorder = createAudioRecord(sampleRate = VOICE_SAMPLE_RATE, chunkSize = READ_CHUNK_SIZE)
        if (recorder.state == AudioRecord.STATE_INITIALIZED) return recorder
        Timber.w(
            "AudioRecord at ${VOICE_SAMPLE_RATE}Hz failed to initialize current state ${recorder.state}, falling back to ${FALLBACK_SAMPLE_RATE}Hz",
        )
        recorder.release()
    } catch (e: IllegalArgumentException) {
        Timber.w(e, "AudioRecord does not support sample rate ${VOICE_SAMPLE_RATE}Hz")
    }

    return createAudioRecord(sampleRate = FALLBACK_SAMPLE_RATE, chunkSize = FALLBACK_READ_CHUNK_SIZE)
}

@SuppressLint("MissingPermission")
private fun createBluetoothAudioRecord(): AudioRecord = createAudioRecord(
    sampleRate = BLUETOOTH_SAMPLE_RATE,
    chunkSize = BLUETOOTH_SAMPLE_RATE * READ_CHUNK_DURATION_MS / 1000,
    audioSource = AudioSource.VOICE_COMMUNICATION,
)

@SuppressLint("MissingPermission")
private fun createAudioRecord(
    sampleRate: Int,
    chunkSize: Int,
    audioSource: Int = VOICE_AUDIO_SOURCE,
): AudioRecord {
    val minBuffer = AudioRecord.getMinBufferSize(sampleRate, VOICE_CHANNEL_CONFIG, VOICE_AUDIO_FORMAT)
    val adjustedBufferSize = maxOf(minBuffer, chunkSize * 4)
    return AudioRecord(
        audioSource,
        sampleRate,
        VOICE_CHANNEL_CONFIG,
        VOICE_AUDIO_FORMAT,
        adjustedBufferSize,
    )
}

/**
 * Downsamples a [ShortArray] of PCM audio from [fromRate] to [toRate] using
 * linear interpolation.
 *
 * For each output sample, computes the corresponding fractional position in the
 * input and linearly interpolates between the two nearest input samples. This
 * produces acceptable quality for voice audio where the source is 44.1kHz and the
 * target is 16kHz.
 */
@VisibleForTesting
internal fun downsample(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
    check(toRate > 0 && fromRate > 0) { "Sample rates must be positive" }
    check(fromRate >= toRate) { "Downsampling requires fromRate >= toRate" }

    val ratio = fromRate.toDouble() / toRate
    val outputSize = (input.size / ratio).toInt()
    val output = ShortArray(outputSize)

    for (i in 0 until outputSize) {
        val srcPos = i * ratio
        val srcIndex = srcPos.toInt()
        val fraction = srcPos - srcIndex

        output[i] = if (srcIndex + 1 < input.size) {
            val sample = input[srcIndex] * (1.0 - fraction) + input[srcIndex + 1] * fraction
            sample.toInt().toShort()
        } else {
            input[srcIndex]
        }
    }

    return output
}

/** Upsamples Bluetooth SCO PCM audio to the 16 kHz sample rate required by Assist. */
@VisibleForTesting
internal fun upsample(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
    require(fromRate > 0 && toRate > fromRate)
    if (input.isEmpty()) return shortArrayOf()

    val outputSize = (input.size.toLong() * toRate / fromRate).toInt()
    val ratio = fromRate.toDouble() / toRate
    return ShortArray(outputSize) { i ->
        val sourcePosition = i * ratio
        val index = sourcePosition.toInt().coerceAtMost(input.lastIndex)
        val nextIndex = (index + 1).coerceAtMost(input.lastIndex)
        val fraction = sourcePosition - index
        (input[index] * (1.0 - fraction) + input[nextIndex] * fraction).toInt().toShort()
    }
}
