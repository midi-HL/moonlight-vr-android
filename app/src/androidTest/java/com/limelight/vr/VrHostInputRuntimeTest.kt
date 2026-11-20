package com.limelight.vr

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.Game
import com.limelight.HelpActivity
import com.limelight.TouchInputHandler
import com.limelight.KeyboardInputHandler
import com.limelight.binding.input.KeyboardTranslator
import com.limelight.nvstream.NvConnection
import com.limelight.nvstream.StreamConfiguration
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.LimelightCryptoProvider
import com.limelight.nvstream.input.KeyboardPacket
import com.limelight.nvstream.input.MouseButtonPacket
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.ui.StreamView
import java.security.PrivateKey
import java.security.cert.X509Certificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises production mouse and keyboard handlers through their host packet seams. */
@RunWith(AndroidJUnit4::class)
class VrHostInputRuntimeTest {
    @Test fun nativeMouseDownAndUpProduceBalancedHostPackets() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val scenario = ActivityScenario.launch<HelpActivity>(
            Intent(context, HelpActivity::class.java).setData(Uri.parse("about:blank")),
        )
        try {
            scenario.onActivity { activity ->
                val game = Game()
                ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                    .apply { isAccessible = true }
                    .invoke(game, activity)
                game.prefConfig = PreferenceConfiguration().apply { enableNativeMousePointer = true }
                game.grabbedInput = true
                val stream = StreamView(game).apply { layout(0, 0, 1000, 600) }
                game.streamView = stream
                val displays = requireNotNull(context.getSystemService(DisplayManager::class.java))
                val display = requireNotNull(displays.getDisplay(Display.DEFAULT_DISPLAY))
                val connection = RecordingConnection(activity.applicationContext, display)
                Game::class.java.declaredFields.single { NvConnection::class.java.isAssignableFrom(it.type) }
                    .apply { isAccessible = true }
                    .set(game, connection)

                val handler = TouchInputHandler(game)
                val downTime = SystemClock.uptimeMillis()
                val down = mouseEvent(downTime, MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_PRIMARY)
                val up = mouseEvent(downTime, MotionEvent.ACTION_UP, 0)
                try {
                    assertEquals(true, handler.handleMotionEvent(stream, down))
                    assertEquals(true, handler.handleMotionEvent(stream, up))
                } finally {
                    down.recycle()
                    up.recycle()
                }

                assertEquals(
                    listOf(
                        "position:240,180,1000,600",
                        "down:${MouseButtonPacket.BUTTON_LEFT}",
                        "position:240,180,1000,600",
                        "up:${MouseButtonPacket.BUTTON_LEFT}",
                    ),
                    connection.packets,
                )
            }
        } finally {
            scenario.close()
        }
    }

    @Test fun physicalKeyboardDownAndUpProduceBalancedHostPackets() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val keyboard = InputDevice.getDeviceIds().asSequence()
            .mapNotNull { deviceId -> InputDevice.getDevice(deviceId) }
            .firstOrNull { !it.isVirtual && it.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC }
        assumeTrue("A physical alphabetic keyboard must be attached", keyboard != null)
        val device = requireNotNull(keyboard)
        val scenario = ActivityScenario.launch<HelpActivity>(
            Intent(context, HelpActivity::class.java).setData(Uri.parse("about:blank")),
        )
        try {
            scenario.onActivity { activity ->
                val game = Game()
                ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                    .apply { isAccessible = true }
                    .invoke(game, activity)
                game.prefConfig = PreferenceConfiguration()
                game.grabbedInput = true
                val connection = RecordingConnection(
                    activity.applicationContext,
                    requireNotNull(context.getSystemService(DisplayManager::class.java))
                        .getDisplay(Display.DEFAULT_DISPLAY)!!,
                )
                Game::class.java.declaredFields.single { NvConnection::class.java.isAssignableFrom(it.type) }
                    .apply { isAccessible = true }
                    .set(game, connection)
                val handler = KeyboardInputHandler(game).apply { keyboardTranslator = KeyboardTranslator() }
                val downTime = SystemClock.uptimeMillis()
                val down = keyEvent(downTime, KeyEvent.ACTION_DOWN, device.id)
                val up = keyEvent(downTime, KeyEvent.ACTION_UP, device.id)
                try {
                    assertEquals(true, handler.handleKeyDown(down))
                    assertEquals(true, handler.handleKeyUp(up))
                } finally {
                    down.recycle()
                    up.recycle()
                }

                assertEquals(2, connection.keyboardPackets.size)
                assertEquals(KeyboardPacket.KEY_DOWN, connection.keyboardPackets[0].direction)
                assertEquals(KeyboardPacket.KEY_UP, connection.keyboardPackets[1].direction)
                assertEquals(connection.keyboardPackets[0].keyMap, connection.keyboardPackets[1].keyMap)
                assertFalse("The physical A key must map to a host key",
                    connection.keyboardPackets[0].keyMap.toInt() == 0)
            }
        } finally {
            scenario.close()
        }
    }

    private fun mouseEvent(downTime: Long, action: Int, buttons: Int): MotionEvent {
        val properties = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coordinates = arrayOf(MotionEvent.PointerCoords().apply {
            x = 240f
            y = 180f
            pressure = 1f
            size = 0f
        })
        return MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, 1, properties, coordinates,
            0, buttons, 1f, 1f, 7, 0, InputDevice.SOURCE_MOUSE, 0,
        )
    }

    private fun keyEvent(downTime: Long, action: Int, deviceId: Int) = KeyEvent(
        downTime, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_A, 0, 0,
        deviceId, 0, 0, InputDevice.SOURCE_KEYBOARD,
    )

    private class RecordingConnection(context: Context, display: Display) : NvConnection(
        context,
        ComputerDetails.AddressTuple("127.0.0.1", 47989),
        47984,
        "vr-host-input-test",
        "",
        StreamConfiguration.Builder().build(),
        object : LimelightCryptoProvider {
            override fun getClientCertificate(): X509Certificate = error("unused in input test")
            override fun getClientPrivateKey(): PrivateKey = error("unused in input test")
            override fun getPemEncodedClientCertificate(): ByteArray = error("unused in input test")
            override fun encodeBase64String(data: ByteArray): String = error("unused in input test")
        },
        null,
        display,
        null,
        false,
    ) {
        val packets = mutableListOf<String>()
        val keyboardPackets = mutableListOf<KeyboardPacketRecord>()

        override fun sendMousePosition(x: Short, y: Short, referenceWidth: Short, referenceHeight: Short) {
            packets += "position:$x,$y,$referenceWidth,$referenceHeight"
        }

        override fun sendMouseButtonDown(mouseButton: Byte) { packets += "down:$mouseButton" }
        override fun sendMouseButtonUp(mouseButton: Byte) { packets += "up:$mouseButton" }
        override fun sendKeyboardInput(keyMap: Short, keyDirection: Byte, modifier: Byte, flags: Byte) {
            keyboardPackets += KeyboardPacketRecord(keyMap, keyDirection)
        }
    }

    private data class KeyboardPacketRecord(val keyMap: Short, val direction: Byte)
}
