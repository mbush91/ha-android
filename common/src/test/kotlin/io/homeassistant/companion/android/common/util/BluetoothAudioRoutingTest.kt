package io.homeassistant.companion.android.common.util

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BluetoothAudioRoutingTest {

    @Test
    fun `Given connected SCO device When starting Then select and release route`() = runTest {
        val context = mockk<Context>()
        val headset = mockk<AudioDeviceInfo> {
            every { type } returns AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            every { id } returns 7
        }
        val manager = mockk<AudioManager>(relaxed = true) {
            every { availableCommunicationDevices } returns listOf(headset)
            every { communicationDevice } returnsMany listOf(null, headset)
            every { setCommunicationDevice(headset) } returns true
        }
        val routing = AndroidBluetoothAudioRouting(context, manager, useModernRouting = true)

        assertTrue(routing.start())
        routing.stop()

        verify(exactly = 1) { manager.setCommunicationDevice(headset) }
        verify(exactly = 1) { manager.clearCommunicationDevice() }
    }

    @Test
    fun `Given no Bluetooth SCO device When starting Then leave phone audio alone`() = runTest {
        val context = mockk<Context>()
        val builtIn = mockk<AudioDeviceInfo> {
            every { type } returns AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }
        val manager = mockk<AudioManager>(relaxed = true) {
            every { availableCommunicationDevices } returns listOf(builtIn)
        }
        val routing = AndroidBluetoothAudioRouting(context, manager, useModernRouting = true)

        assertFalse(routing.start())
        routing.stop()

        verify(exactly = 0) { manager.setCommunicationDevice(any()) }
        verify(exactly = 0) { manager.clearCommunicationDevice() }
    }

    @Test
    fun `Given rejected SCO device When starting Then do not clear unrelated route`() = runTest {
        val headset = mockk<AudioDeviceInfo> {
            every { type } returns AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            every { id } returns 8
        }
        val manager = mockk<AudioManager>(relaxed = true) {
            every { availableCommunicationDevices } returns listOf(headset)
            every { communicationDevice } returns null
            every { setCommunicationDevice(headset) } returns false
        }
        val routing = AndroidBluetoothAudioRouting(mockk(), manager, useModernRouting = true)

        assertFalse(routing.start())
        routing.stop()

        verify(exactly = 0) { manager.clearCommunicationDevice() }
    }

    @Test
    fun `Given permission denied When finding Bluetooth devices Then fall back safely`() = runTest {
        val manager = mockk<AudioManager>(relaxed = true) {
            every { availableCommunicationDevices } throws SecurityException("permission denied")
        }
        val routing = AndroidBluetoothAudioRouting(mockk(), manager, useModernRouting = true)

        assertFalse(routing.start())
        verify(exactly = 0) { manager.clearCommunicationDevice() }
    }
}
