@file:Suppress("DEPRECATION")
package com.limelight

import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.limelight.utils.VrToast as Toast
import com.limelight.binding.input.ControllerHandler
import com.limelight.binding.input.KeyboardTranslator
import com.limelight.nvstream.input.KeyboardPacket
import com.limelight.nvstream.input.MouseButtonPacket
import com.limelight.nvstream.jni.MoonBridge
import com.limelight.services.KeyboardAccessibilityService

/**
 * 键盘/按键事件处理器。
 * 从 Game.java 提取，处理 handleKeyDown/handleKeyUp/handleKeyMultiple、
 * 特殊组合键、ESC 自定义映射和修饰键状态追踪。
 */
class KeyboardInputHandler(private val game: Game) {

    lateinit var keyboardTranslator: KeyboardTranslator

    private var modifierFlags = 0
    private var waitingForAllModifiersUp = false
    private var specialKeyCode = KeyEvent.KEYCODE_UNKNOWN
    private var lastEscPressTime = 0L
    private var hasShownEscHint = false

    private val pressedKeys = HashSet<Int>()
    private data class ForwardedKey(val translated: Short, val flags: Byte)
    private val forwardedKeys = LinkedHashMap<Int, ForwardedKey>()
    // Several physical/custom presses can hold the same host key. Preserve its first DOWN.
    private val forwardedHostKeys = LinkedHashMap<Short, ForwardedKey>()
    private val handler = Handler(Looper.getMainLooper())
    private data class CustomButton(val deviceId: Int, val keyCode: Int)
    private enum class CustomKind { PENDING_ESCAPE, ESCAPE, ESCAPE_TAP, COMBO_ESCAPE, FUNCTION, MENU }
    private data class CustomPress(
        val downTime: Long, var kind: CustomKind, var forwarded: ForwardedKey? = null,
        var pending: Runnable? = null,
    )
    private val customPresses = LinkedHashMap<CustomButton, CustomPress>()
    private val customPressTails = HashMap<CustomButton, Long>()

    private val toggleGrab = Runnable { game.setInputGrabState(!game.grabbedInput) }

    companion object {
        private const val ESC_DOUBLE_PRESS_INTERVAL = 500L // 500毫秒内按第二次ESC才有效
    }

    // Returns true if the key stroke was consumed
    private fun handleSpecialKeys(androidKeyCode: Int, down: Boolean): Boolean {
        var modifierMask = 0
        var nonModifierKeyCode = KeyEvent.KEYCODE_UNKNOWN

        when (androidKeyCode) {
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT ->
                modifierMask = KeyboardPacket.MODIFIER_CTRL.toInt()
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT ->
                modifierMask = KeyboardPacket.MODIFIER_SHIFT.toInt()
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT ->
                modifierMask = KeyboardPacket.MODIFIER_ALT.toInt()
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT ->
                modifierMask = KeyboardPacket.MODIFIER_META.toInt()
            else -> nonModifierKeyCode = androidKeyCode
        }

        modifierFlags = if (down) {
            modifierFlags or modifierMask
        } else {
            modifierFlags and modifierMask.inv()
        }

        // Handle the special combos on the key up
        if (waitingForAllModifiersUp || specialKeyCode != KeyEvent.KEYCODE_UNKNOWN) {
            if (specialKeyCode == androidKeyCode) {
                return true
            } else if (modifierFlags != 0) {
                return down
            } else {
                when (specialKeyCode) {
                    // Toggle input grab
                    KeyEvent.KEYCODE_Z -> {
                        val h = game.window.decorView.handler
                        h?.postDelayed(toggleGrab, 250)
                    }
                    // Quit
                    KeyEvent.KEYCODE_Q -> game.finish()
                    // Toggle cursor visibility
                    KeyEvent.KEYCODE_C -> {
                        if (!game.grabbedInput) {
                            game.inputCaptureProvider.enableCapture()
                            game.grabbedInput = true
                        }
                        game.cursorVisible = !game.cursorVisible
                        if (game.cursorVisible) {
                            game.inputCaptureProvider.showCursor()
                        } else {
                            game.inputCaptureProvider.hideCursor()
                        }
                    }
                }

                specialKeyCode = KeyEvent.KEYCODE_UNKNOWN
                waitingForAllModifiersUp = false
            }
        } else if ((modifierFlags and (KeyboardPacket.MODIFIER_CTRL.toInt() or KeyboardPacket.MODIFIER_ALT.toInt() or KeyboardPacket.MODIFIER_SHIFT.toInt())) ==
            (KeyboardPacket.MODIFIER_CTRL.toInt() or KeyboardPacket.MODIFIER_ALT.toInt() or KeyboardPacket.MODIFIER_SHIFT.toInt()) &&
            (down && nonModifierKeyCode != KeyEvent.KEYCODE_UNKNOWN)
        ) {
            when (androidKeyCode) {
                KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_C -> {
                    specialKeyCode = androidKeyCode
                    waitingForAllModifiersUp = true
                    return true
                }
                else -> return false
            }
        }

        return false
    }

    private fun getModifierState(event: KeyEvent): Byte {
        var modifier = getModifierState().toInt()
        if (event.isShiftPressed) modifier = modifier or KeyboardPacket.MODIFIER_SHIFT.toInt()
        if (event.isCtrlPressed) modifier = modifier or KeyboardPacket.MODIFIER_CTRL.toInt()
        if (event.isAltPressed) modifier = modifier or KeyboardPacket.MODIFIER_ALT.toInt()
        if (event.isMetaPressed) modifier = modifier or KeyboardPacket.MODIFIER_META.toInt()
        return modifier.toByte()
    }

    private fun getModifierState(): Byte = modifierFlags.toByte()

    fun clearModifierState() {
        modifierFlags = 0
    }

    fun releaseForwardedKeys() {
        customPresses.toList().forEach { (button, press) ->
            press.forwarded = null
            cancelCustomPress(button, press)
        }
        specialKeyCode = KeyEvent.KEYCODE_UNKNOWN
        waitingForAllModifiersUp = false
        val keys = forwardedHostKeys.values.toList()
        forwardedKeys.clear()
        forwardedHostKeys.clear()
        for (key in keys) {
            game.conn?.sendKeyboardInput(
                key.translated, KeyboardPacket.KEY_UP, getModifierState(), key.flags
            )
        }
        modifierFlags = 0
    }

    private fun sendKeyDown(trackingKey: Int, translated: Short, modifier: Byte, flags: Byte) {
        val key = ForwardedKey(translated, flags)
        forwardedKeys[trackingKey] = key
        forwardHostDown(key, modifier)
    }

    private fun sendTrackedKeyUp(trackingKey: Int, translated: Short, modifier: Byte, flags: Byte) {
        val key = forwardedKeys.remove(trackingKey) ?: ForwardedKey(translated, flags)
        releaseHostIfUnheld(key.translated, modifier)
    }

    private fun forwardHostDown(key: ForwardedKey, modifier: Byte) {
        if (forwardedHostKeys.containsKey(key.translated)) return
        forwardedHostKeys[key.translated] = key
        game.conn?.sendKeyboardInput(key.translated, KeyboardPacket.KEY_DOWN, modifier, key.flags)
    }

    private fun releaseHostIfUnheld(translated: Short, modifier: Byte) {
        if (forwardedKeys.values.any { it.translated == translated } ||
            customPresses.values.any { it.forwarded?.translated == translated }) return
        val key = forwardedHostKeys.remove(translated) ?: return
        game.conn?.sendKeyboardInput(key.translated, KeyboardPacket.KEY_UP, modifier, key.flags)
    }

    private fun forwardCustomDown(press: CustomPress, keyCode: Int, deviceId: Int) {
        val key = ForwardedKey(keyboardTranslator.translate(keyCode, deviceId), MoonBridge.SS_KBE_FLAG_NON_NORMALIZED)
        press.forwarded = key
        forwardHostDown(key, 0.toByte())
    }

    private fun releaseCustomForwarded(press: CustomPress) {
        val key = press.forwarded ?: return
        press.forwarded = null
        releaseHostIfUnheld(key.translated, 0.toByte())
    }

    private fun cancelCustomPress(button: CustomButton, press: CustomPress) {
        press.pending?.let(handler::removeCallbacks)
        releaseCustomForwarded(press)
        customPresses.remove(button)
        rememberCustomTail(button, press.downTime)
    }

    private fun rememberCustomTail(button: CustomButton, downTime: Long) {
        customPressTails[button] = maxOf(customPressTails[button] ?: Long.MIN_VALUE, downTime)
    }

    private fun isCustomTail(button: CustomButton, downTime: Long): Boolean {
        return customPressTails[button]?.let { downTime <= it } == true
    }

    /** The physical DOWN owns its mapping even after ESC, focus, or settings change. */
    private fun handleCustomKeyDown(event: KeyEvent): Boolean {
        val button = CustomButton(event.deviceId, event.keyCode)
        if (isCustomTail(button, event.downTime)) return true
        customPresses[button]?.let { previous ->
            if (previous.downTime == event.downTime || event.repeatCount > 0) return true
            cancelCustomPress(button, previous)
        }
        if (!game.prefConfig.enableCustomKeyMap) return false
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.isCanceled || event.repeatCount > 0) return true
            val press = CustomPress(event.downTime, CustomKind.PENDING_ESCAPE)
            customPresses[button] = press
            val pending = Runnable {
                if (customPresses[button] !== press || press.kind != CustomKind.PENDING_ESCAPE) return@Runnable
                press.pending = null
                press.kind = CustomKind.ESCAPE
                forwardCustomDown(press, KeyEvent.KEYCODE_ESCAPE, button.deviceId)
            }
            press.pending = pending
            handler.postDelayed(pending, 200)
            return true
        }
        val escape = customPresses[CustomButton(event.deviceId, KeyEvent.KEYCODE_ESCAPE)]
            ?.takeIf { it.kind == CustomKind.PENDING_ESCAPE } ?: return false
        if (event.isCanceled || event.repeatCount > 0) return true
        escape.pending?.let(handler::removeCallbacks)
        escape.pending = null
        val mapped = when (event.keyCode) {
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> KeyEvent.KEYCODE_F1 + event.keyCode - KeyEvent.KEYCODE_1
            KeyEvent.KEYCODE_0 -> KeyEvent.KEYCODE_F10
            KeyEvent.KEYCODE_MINUS -> KeyEvent.KEYCODE_F11
            KeyEvent.KEYCODE_EQUALS -> KeyEvent.KEYCODE_F12
            else -> null
        }
        if (mapped != null || event.keyCode == KeyEvent.KEYCODE_Q) {
            escape.kind = CustomKind.COMBO_ESCAPE
            val press = CustomPress(event.downTime, if (mapped == null) CustomKind.MENU else CustomKind.FUNCTION)
            customPresses[button] = press
            if (mapped != null) forwardCustomDown(press, mapped, event.deviceId)
            return true
        }
        escape.kind = CustomKind.ESCAPE
        forwardCustomDown(escape, KeyEvent.KEYCODE_ESCAPE, event.deviceId)
        return false
    }

    private fun handleCustomKeyUp(event: KeyEvent, cancel: Boolean = event.isCanceled): Boolean {
        val button = CustomButton(event.deviceId, event.keyCode)
        if (isCustomTail(button, event.downTime)) return true
        val press = customPresses[button]
            ?: return game.prefConfig.enableCustomKeyMap && event.keyCode == KeyEvent.KEYCODE_ESCAPE &&
                !forwardedKeys.containsKey(KeyEvent.KEYCODE_ESCAPE)
        // A late UP must not release a newer press of the same physical key.
        if (press.downTime != event.downTime) return true
        press.pending?.let(handler::removeCallbacks)
        press.pending = null
        if (!cancel && press.kind == CustomKind.PENDING_ESCAPE) {
            press.kind = CustomKind.ESCAPE_TAP
            forwardCustomDown(press, KeyEvent.KEYCODE_ESCAPE, event.deviceId)
            val release = Runnable {
                if (customPresses[button] !== press) return@Runnable
                releaseCustomForwarded(press)
                customPresses.remove(button)
                rememberCustomTail(button, press.downTime)
            }
            press.pending = release
            handler.postDelayed(release, 50)
        } else {
            customPresses.remove(button)
            rememberCustomTail(button, press.downTime)
            releaseCustomForwarded(press)
            if (!cancel && press.kind == CustomKind.MENU) game.onBackPressed()
        }
        return true
    }

    private fun accessibilityOwnsWindowCopy(event: KeyEvent): Boolean {
        val service = KeyboardAccessibilityService.instance
        val systemKey = when (event.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_POWER -> true
            else -> false
        }
        // A connected service can be idle while focus changes or a native menu is open.
        // Only the service actively forwarding to this Game owns its physical window copies.
        return KeyboardAccessibilityInputPolicy.shouldSuppressWindowEvent(
            fromAccessibility = game.isEventFromAccessibilityService,
            physicalDevice = event.device?.isVirtual == false,
            systemKey = systemKey,
            serviceConnected = service != null,
            intercepting = KeyboardAccessibilityService.interceptingEnabled,
            callbackIsOwner = service?.keyEventCallback === game,
        )
    }

    fun handleKeyDown(event: KeyEvent): Boolean {
        if (accessibilityOwnsWindowCopy(event)) return true

        // Custom ESC shortcuts keep the physical press's selected host mapping through UP.
        val keyCode = event.keyCode
        pressedKeys.add(keyCode)
        if (handleCustomKeyDown(event)) return true

        // Pass-through virtual navigation keys
        if ((event.flags and KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) {
            return false
        }

        // Handle a synthetic back button event that some Android OS versions
        // create as a result of a right-click
        val eventSource = event.source
        if ((eventSource == InputDevice.SOURCE_MOUSE || eventSource == InputDevice.SOURCE_MOUSE_RELATIVE) &&
            event.keyCode == KeyEvent.KEYCODE_BACK
        ) {
            if (!game.prefConfig.mouseNavButtons) {
                game.conn?.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT)
            }
            return true
        }

        // 鼠标中键（同时影响触摸返回）
        if (game.touchInputHandler.detectMouseMiddle && eventSource == InputDevice.SOURCE_KEYBOARD &&
            event.keyCode == KeyEvent.KEYCODE_BACK
        ) {
            if (android.os.SystemClock.uptimeMillis() - game.touchInputHandler.lastMouseHoverTime < 250) {
                game.touchInputHandler.detectMouseMiddleDown = true
                game.touchInputHandler.detectMouseMiddle = false
                game.conn?.sendMouseButtonDown(MouseButtonPacket.BUTTON_MIDDLE)
                return true
            }
        }

        var handled = false

        if (ControllerHandler.isGameControllerDevice(event.device)) {
            handled = game.controllerHandler.handleButtonDown(event) == true
        }

        if (!handled) {
            if (handleSpecialKeys(event.keyCode, true)) {
                return true
            }

            if (!game.grabbedInput) {
                return false
            }

            val translated = keyboardTranslator.translate(event.keyCode, event.deviceId)
            if (translated.toInt() == 0) {
                val unicodeChar = event.unicodeChar
                if ((unicodeChar and KeyCharacterMap.COMBINING_ACCENT) == 0 &&
                    (unicodeChar and KeyCharacterMap.COMBINING_ACCENT_MASK) != 0
                ) {
                    game.conn?.sendUtf8Text("" + unicodeChar.toChar())
                    return true
                }
                return false
            }

            if (event.repeatCount > 0) {
                return true
            }

            sendKeyDown(
                event.keyCode, translated, getModifierState(event),
                if (keyboardTranslator.hasNormalizedMapping(event.keyCode, event.deviceId)) 0 else MoonBridge.SS_KBE_FLAG_NON_NORMALIZED
            )
        }

        return true
    }

    fun handleKeyUp(event: KeyEvent): Boolean {
        if (accessibilityOwnsWindowCopy(event)) return true

        if (isPhysicalKeyboardConnected()) {
            // ESC键双击逻辑
            if (event.keyCode == game.prefConfig.escMenuKey && game.prefConfig.enableEscMenu) {
                val currentTime = System.currentTimeMillis()

                if (currentTime - lastEscPressTime <= ESC_DOUBLE_PRESS_INTERVAL && hasShownEscHint) {
                    handleCustomKeyUp(event, cancel = true)
                    game.onBackPressed()
                    lastEscPressTime = 0
                    hasShownEscHint = false
                    return true
                } else {
                    var keyName = KeyEvent.keyCodeToString(game.prefConfig.escMenuKey)
                    if (keyName.startsWith("KEYCODE_")) {
                        keyName = keyName.substring("KEYCODE_".length)
                    }
                    Toast.makeText(game, game.getString(R.string.toast_press_again_to_open_menu, keyName), Toast.LENGTH_SHORT).show()
                    lastEscPressTime = currentTime
                    hasShownEscHint = true
                }
            }
        }

        val keyCode = event.keyCode
        pressedKeys.remove(keyCode)

        if (handleCustomKeyUp(event)) return true

        // Pass-through virtual navigation keys
        if ((event.flags and KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) {
            return false
        }

        // Handle a synthetic back button event
        val eventSource = event.source
        if ((eventSource == InputDevice.SOURCE_MOUSE || eventSource == InputDevice.SOURCE_MOUSE_RELATIVE) &&
            event.keyCode == KeyEvent.KEYCODE_BACK
        ) {
            if (!game.prefConfig.mouseNavButtons) {
                game.conn?.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT)
            }
            return true
        }

        // 鼠标中键
        if (game.touchInputHandler.detectMouseMiddleDown && eventSource == InputDevice.SOURCE_KEYBOARD &&
            event.keyCode == KeyEvent.KEYCODE_BACK
        ) {
            game.touchInputHandler.detectMouseMiddleDown = false
            game.conn?.sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE)
            return true
        }

        var handled = false
        if (ControllerHandler.isGameControllerDevice(event.device)) {
            handled = game.controllerHandler.handleButtonUp(event) == true
        }

        if (!handled) {
            if (handleSpecialKeys(event.keyCode, false)) {
                return true
            }

            if (!game.grabbedInput) {
                return false
            }

            val translated = keyboardTranslator.translate(event.keyCode, event.deviceId)
            if (translated.toInt() == 0) {
                val unicodeChar = event.unicodeChar
                return (unicodeChar and KeyCharacterMap.COMBINING_ACCENT) == 0 &&
                        (unicodeChar and KeyCharacterMap.COMBINING_ACCENT_MASK) != 0
            }

            sendTrackedKeyUp(
                event.keyCode, translated, getModifierState(event),
                if (keyboardTranslator.hasNormalizedMapping(event.keyCode, event.deviceId)) 0 else MoonBridge.SS_KBE_FLAG_NON_NORMALIZED
            )
        }

        return true
    }

    fun handleKeyMultiple(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_UNKNOWN || event.characters == null) {
            return false
        }
        game.conn?.sendUtf8Text(event.characters)
        return true
    }

    private fun isPhysicalKeyboardConnected(): Boolean {
        return game.resources.configuration.keyboard == Configuration.KEYBOARD_QWERTY
    }

    /**
     * EvdevListener 键盘事件处理，从 Game.keyboardEvent() 委托。
     */
    fun keyboardEvent(buttonDown: Boolean, keyCode: Short) {
        val keyMap = keyboardTranslator.translate(keyCode.toInt(), -1)
        if (keyMap.toInt() != 0) {
            if (handleSpecialKeys(keyCode.toInt(), buttonDown)) {
                return
            }
            if (buttonDown) {
                game.conn?.sendKeyboardInput(keyMap, KeyboardPacket.KEY_DOWN, getModifierState(), 0.toByte())
            } else {
                game.conn?.sendKeyboardInput(keyMap, KeyboardPacket.KEY_UP, getModifierState(), 0.toByte())
            }
        }
    }
}
