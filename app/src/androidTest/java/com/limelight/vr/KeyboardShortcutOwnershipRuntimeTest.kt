package com.limelight.vr

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.SystemClock
import android.os.Looper
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.Game
import com.limelight.HelpActivity
import com.limelight.KeyboardInputHandler
import com.limelight.TouchInputHandler
import com.limelight.binding.input.ControllerHandler
import com.limelight.binding.input.KeyboardTranslator
import com.limelight.nvstream.NvConnection
import com.limelight.nvstream.StreamConfiguration
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.LimelightCryptoProvider
import com.limelight.nvstream.input.KeyboardPacket
import com.limelight.preferences.PreferenceConfiguration
import java.security.PrivateKey
import java.security.cert.X509Certificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real production keyboard handling at the host packet API, without a network session. */
@RunWith(AndroidJUnit4::class)
class KeyboardShortcutOwnershipRuntimeTest {
    @Test fun escapeReleasedFirstKeepsF1AndF12ReleaseMappings() = withFixture { fixture ->
        for ((key, mapped) in FUNCTION_BOUNDARIES) {
            val (escape, function) = fixture.chord(key)
            fixture.up(escape)
            fixture.up(function)
            fixture.expectAndClear(mapped to DOWN, mapped to UP)
        }
    }

    @Test fun functionReleasedFirstKeepsF1AndF12ReleaseMappings() = withFixture { fixture ->
        for ((key, mapped) in FUNCTION_BOUNDARIES) {
            val (escape, function) = fixture.chord(key)
            fixture.up(function)
            fixture.up(escape)
            fixture.expectAndClear(mapped to DOWN, mapped to UP)
        }
    }

    @Test fun shortcutRepeatAndLateReleaseCannotReleaseANewerPress() = withFixture { fixture ->
        val (escape, oldFunction) = fixture.chord(KeyEvent.KEYCODE_1)
        fixture.repeat(oldFunction)
        fixture.up(escape)
        val number = fixture.down(KeyEvent.KEYCODE_1) // A fresh DOWN replaces the lost old UP.
        fixture.up(oldFunction)
        fixture.up(number)
        fixture.expectAndClear(
            KeyEvent.KEYCODE_F1 to DOWN, KeyEvent.KEYCODE_F1 to UP,
            KeyEvent.KEYCODE_1 to DOWN, KeyEvent.KEYCODE_1 to UP,
        )
        val (newEscape, newFunction) = fixture.chord(KeyEvent.KEYCODE_1)
        fixture.up(oldFunction)
        fixture.up(newFunction)
        fixture.up(newEscape)
        fixture.expectAndClear(KeyEvent.KEYCODE_F1 to DOWN, KeyEvent.KEYCODE_F1 to UP)
    }

    @Test fun anotherKeyboardCannotCompleteOrReleaseTheShortcut() = withFixture { fixture ->
        fixture.onMain {
            val (escape, ordinary) = fixture.chord(KeyEvent.KEYCODE_1, keyDevice = KEYBOARD_B)
            fixture.up(ordinary)
            fixture.up(escape)
        }
        fixture.awaitPacketCount(4)
        fixture.expectAndClear(
            KeyEvent.KEYCODE_1 to DOWN, KeyEvent.KEYCODE_1 to UP,
            KeyEvent.KEYCODE_ESCAPE to DOWN, KeyEvent.KEYCODE_ESCAPE to UP,
        )
        val (nextEscape, function) = fixture.chord(KeyEvent.KEYCODE_1)
        val otherNumber = fixture.down(KeyEvent.KEYCODE_1, KEYBOARD_B)
        fixture.up(otherNumber)
        fixture.up(nextEscape)
        fixture.up(function)
        fixture.expectAndClear(
            KeyEvent.KEYCODE_F1 to DOWN, KeyEvent.KEYCODE_1 to DOWN,
            KeyEvent.KEYCODE_1 to UP, KeyEvent.KEYCODE_F1 to UP,
        )
    }

    @Test fun canceledPendingAndMappedPressesBalanceAndRecover() = withFixture { fixture ->
        fixture.onMain {
            val pending = fixture.down(KeyEvent.KEYCODE_ESCAPE)
            fixture.up(pending, canceled = true)
        }
        SystemClock.sleep(250)
        fixture.expectAndClear()
        val (escape, function) = fixture.chord(KeyEvent.KEYCODE_EQUALS)
        fixture.up(function, canceled = true)
        fixture.up(escape, canceled = true)
        fixture.up(function)
        fixture.expectAndClear(KeyEvent.KEYCODE_F12 to DOWN, KeyEvent.KEYCODE_F12 to UP)
        val (nextEscape, nextFunction) = fixture.chord(KeyEvent.KEYCODE_1)
        fixture.up(nextEscape)
        fixture.up(nextFunction)
        fixture.expectAndClear(KeyEvent.KEYCODE_F1 to DOWN, KeyEvent.KEYCODE_F1 to UP)
    }

    @Test fun bulkReleaseCancelsMappedPressAndConsumesItsOldTails() = withFixture { fixture ->
        val (escape, function) = fixture.chord(KeyEvent.KEYCODE_EQUALS)
        fixture.onMain { fixture.handler.releaseForwardedKeys() }
        fixture.up(escape)
        fixture.up(function)
        fixture.up(function)
        SystemClock.sleep(250)
        fixture.expectAndClear(KeyEvent.KEYCODE_F12 to DOWN, KeyEvent.KEYCODE_F12 to UP)
        val (nextEscape, nextFunction) = fixture.chord(KeyEvent.KEYCODE_1)
        fixture.up(nextEscape)
        fixture.up(nextFunction)
        fixture.expectAndClear(KeyEvent.KEYCODE_F1 to DOWN, KeyEvent.KEYCODE_F1 to UP)
    }

    @Test fun escapeTimeoutForwardsOrdinaryEscapeAndFollowingKey() = withFixture { fixture ->
        val escape = fixture.down(KeyEvent.KEYCODE_ESCAPE)
        fixture.awaitPacketCount(1)
        val letter = fixture.down(KeyEvent.KEYCODE_A)
        fixture.up(letter)
        fixture.up(escape)
        fixture.expectAndClear(
            KeyEvent.KEYCODE_ESCAPE to DOWN, KeyEvent.KEYCODE_A to DOWN,
            KeyEvent.KEYCODE_A to UP, KeyEvent.KEYCODE_ESCAPE to UP,
        )
    }

    @Test fun quickEscapeTapAndBulkReleaseBalanceDelayedUp() = withFixture { fixture ->
        lateinit var escape: Press
        fixture.onMain {
            escape = fixture.down(KeyEvent.KEYCODE_ESCAPE)
            fixture.up(escape)
            fixture.handler.releaseForwardedKeys()
        }
        SystemClock.sleep(100)
        fixture.up(escape)
        fixture.expectAndClear(KeyEvent.KEYCODE_ESCAPE to DOWN, KeyEvent.KEYCODE_ESCAPE to UP)
        fixture.onMain {
            val nextEscape = fixture.down(KeyEvent.KEYCODE_ESCAPE)
            fixture.up(nextEscape)
        }
        fixture.awaitPacketCount(2)
        fixture.expectAndClear(KeyEvent.KEYCODE_ESCAPE to DOWN, KeyEvent.KEYCODE_ESCAPE to UP)
    }

    @Test fun shortcutMappingSurvivesPreferenceChangeBeforeRelease() = withFixture { fixture ->
        val (escape, function) = fixture.chord(KeyEvent.KEYCODE_EQUALS)
        fixture.onMain { fixture.game.prefConfig.enableCustomKeyMap = false }
        fixture.up(escape)
        fixture.up(function)
        fixture.expectAndClear(KeyEvent.KEYCODE_F12 to DOWN, KeyEvent.KEYCODE_F12 to UP)
        val ordinary = fixture.down(KeyEvent.KEYCODE_1)
        fixture.up(ordinary)
        fixture.expectAndClear(KeyEvent.KEYCODE_1 to DOWN, KeyEvent.KEYCODE_1 to UP)
    }

    @Test fun physicalAndShortcutFunctionShareTheHostKeyUntilBothRelease() = withFixture { fixture ->
        val physical = fixture.down(KeyEvent.KEYCODE_F1)
        val (escape, function) = fixture.chord(KeyEvent.KEYCODE_1)
        fixture.up(function)
        fixture.up(escape)
        fixture.expectAndClear(KeyEvent.KEYCODE_F1 to DOWN)
        fixture.up(physical)
        fixture.expectAndClear(KeyEvent.KEYCODE_F1 to UP)

        val (nextEscape, nextFunction) = fixture.chord(KeyEvent.KEYCODE_1)
        val nextPhysical = fixture.down(KeyEvent.KEYCODE_F1)
        fixture.up(nextPhysical)
        fixture.expectAndClear(KeyEvent.KEYCODE_F1 to DOWN)
        fixture.up(nextEscape)
        fixture.up(nextFunction)
        fixture.expectAndClear(KeyEvent.KEYCODE_F1 to UP)

        val heldPhysical = fixture.down(KeyEvent.KEYCODE_F1)
        val (heldEscape, heldFunction) = fixture.chord(KeyEvent.KEYCODE_1)
        fixture.onMain { fixture.handler.releaseForwardedKeys() }
        fixture.up(heldEscape)
        fixture.up(heldFunction)
        fixture.up(heldPhysical)
        fixture.expectAndClear(KeyEvent.KEYCODE_F1 to DOWN, KeyEvent.KEYCODE_F1 to UP)
    }

    @Test fun olderCompletedShortcutReleaseCannotReleaseALaterOrdinaryNumber() = withFixture { fixture ->
        val (firstEscape, firstFunction) = fixture.chord(KeyEvent.KEYCODE_1)
        fixture.up(firstEscape)
        fixture.up(firstFunction)
        val (secondEscape, secondFunction) = fixture.chord(KeyEvent.KEYCODE_1)
        fixture.up(secondEscape)
        fixture.up(secondFunction)
        val ordinary = fixture.down(KeyEvent.KEYCODE_1)
        fixture.up(firstFunction)
        fixture.expectAndClear(
            KeyEvent.KEYCODE_F1 to DOWN, KeyEvent.KEYCODE_F1 to UP,
            KeyEvent.KEYCODE_F1 to DOWN, KeyEvent.KEYCODE_F1 to UP,
            KeyEvent.KEYCODE_1 to DOWN,
        )
        fixture.up(ordinary)
        fixture.expectAndClear(KeyEvent.KEYCODE_1 to UP)
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val scenario = ActivityScenario.launch<HelpActivity>(
            Intent(context, HelpActivity::class.java).setData(Uri.parse("about:blank")),
        )
        var fixture: Fixture? = null
        try {
            scenario.onActivity { activity ->
                val game = Game()
                ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                    .apply { isAccessible = true }.invoke(game, activity)
                game.prefConfig = PreferenceConfiguration().apply {
                    enableCustomKeyMap = true
                    enableEscMenu = false
                }
                game.grabbedInput = true
                game.touchInputHandler = TouchInputHandler(game)
                val display = requireNotNull(context.getSystemService(DisplayManager::class.java))
                    .getDisplay(Display.DEFAULT_DISPLAY)!!
                val connection = RecordingConnection(context, display)
                Game::class.java.declaredFields.single { NvConnection::class.java.isAssignableFrom(it.type) }
                    .apply { isAccessible = true }.set(game, connection)
                val handler = KeyboardInputHandler(game).apply { keyboardTranslator = KeyboardTranslator() }
                val keyboards = InputDevice.getDeviceIds().asSequence().mapNotNull { InputDevice.getDevice(it) }
                    .filter { it.supportsSource(InputDevice.SOURCE_KEYBOARD) &&
                        !ControllerHandler.isGameControllerDevice(it) }.toList()
                val keyboardA = keyboards.firstOrNull { it.isVirtual }
                val keyboardB = keyboards.firstOrNull { !it.isVirtual &&
                    it.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC }
                assertNotNull("An existing non-controller virtual keyboard is required for role A", keyboardA)
                assertNotNull("An existing non-controller physical alphabetic keyboard is required for role B", keyboardB)
                val roles = mapOf(KEYBOARD_A to requireNotNull(keyboardA).id,
                    KEYBOARD_B to requireNotNull(keyboardB).id)
                assertTrue("The two keyboard roles must use distinct existing devices", roles.values.toSet().size == 2)
                fixture = Fixture(game, handler, connection, roles)
            }
            block(requireNotNull(fixture))
        } finally {
            try { fixture?.let { it.onMain { it.handler.releaseForwardedKeys() } } }
            finally { scenario.close() }
        }
    }

    private data class Press(val keyCode: Int, val deviceId: Int, val downTime: Long)
    private data class Packet(val keyMap: Short, val direction: Byte)

    private class Fixture(val game: Game, val handler: KeyboardInputHandler, val connection: RecordingConnection,
                          private val keyboardRoles: Map<Int, Int>) {
        private var lastDownTime = 0L
        fun onMain(action: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) action()
            else InstrumentationRegistry.getInstrumentation().runOnMainSync { action() }
        }

        fun chord(keyCode: Int, keyDevice: Int = KEYBOARD_A): Pair<Press, Press> {
            lateinit var result: Pair<Press, Press>
            onMain { result = down(KeyEvent.KEYCODE_ESCAPE) to down(keyCode, keyDevice) }
            return result
        }

        fun awaitPacketCount(expected: Int) {
            val deadline = SystemClock.uptimeMillis() + 2_000
            while (SystemClock.uptimeMillis() < deadline) {
                var count = 0
                onMain { count = connection.packets.size }
                if (count >= expected) return
                SystemClock.sleep(20)
            }
            onMain { assertEquals("Timed keyboard action never reached the host packet API", expected, connection.packets.size) }
        }

        fun down(keyCode: Int, keyboardRole: Int = KEYBOARD_A): Press {
            val time = SystemClock.uptimeMillis().coerceAtLeast(lastDownTime + 1)
            lastDownTime = time
            val deviceId = requireNotNull(keyboardRoles[keyboardRole]) { "Unknown keyboard role" }
            return Press(keyCode, deviceId, time).also { send(it, KeyEvent.ACTION_DOWN) }
        }

        fun repeat(press: Press) = send(press, KeyEvent.ACTION_DOWN, repeat = 1)
        fun up(press: Press, canceled: Boolean = false) = send(press, KeyEvent.ACTION_UP, canceled = canceled)

        private fun send(press: Press, action: Int, repeat: Int = 0, canceled: Boolean = false) = onMain {
            val event = KeyEvent(press.downTime, SystemClock.uptimeMillis().coerceAtLeast(press.downTime), action,
                press.keyCode, repeat, 0, press.deviceId, 0, if (canceled) KeyEvent.FLAG_CANCELED else 0,
                InputDevice.SOURCE_KEYBOARD)
            val keyboard = event.device
            assertNotNull("Every key event must resolve to an existing keyboard device", keyboard)
            assertTrue("The event device must advertise SOURCE_KEYBOARD",
                requireNotNull(keyboard).supportsSource(InputDevice.SOURCE_KEYBOARD))
            assertFalse("Shortcut fixture events must take the production keyboard path",
                ControllerHandler.isGameControllerDevice(keyboard))
            assertTrue(if (action == KeyEvent.ACTION_DOWN) handler.handleKeyDown(event) else handler.handleKeyUp(event))
        }

        fun expectAndClear(vararg expected: Pair<Int, Byte>) = onMain {
            val translator = KeyboardTranslator()
            val packets = expected.map { (key, direction) -> Packet(translator.translate(key, -1), direction) }
            assertEquals("Host key DOWN/UP must keep the mapping selected by that physical press", packets, connection.packets)
            connection.packets.clear()
        }
    }

    private class RecordingConnection(context: Context, display: Display) : NvConnection(
        context, ComputerDetails.AddressTuple("127.0.0.1", 47989), 47984, "vr-shortcut-input-test", "",
        StreamConfiguration.Builder().build(),
        object : LimelightCryptoProvider {
            override fun getClientCertificate(): X509Certificate = error("unused in input test")
            override fun getClientPrivateKey(): PrivateKey = error("unused in input test")
            override fun getPemEncodedClientCertificate(): ByteArray = error("unused in input test")
            override fun encodeBase64String(data: ByteArray): String = error("unused in input test")
        },
        null, display, null, false,
    ) {
        val packets = mutableListOf<Packet>()
        override fun sendKeyboardInput(keyMap: Short, keyDirection: Byte, modifier: Byte, flags: Byte) {
            packets += Packet(keyMap, keyDirection)
        }
    }

    companion object {
        // Logical roles only; Fixture resolves these to actual registered keyboard device IDs.
        private const val KEYBOARD_A = 0
        private const val KEYBOARD_B = 1
        private val DOWN = KeyboardPacket.KEY_DOWN
        private val UP = KeyboardPacket.KEY_UP
        private val FUNCTION_BOUNDARIES = listOf(KeyEvent.KEYCODE_1 to KeyEvent.KEYCODE_F1,
            KeyEvent.KEYCODE_EQUALS to KeyEvent.KEYCODE_F12)
    }
}
