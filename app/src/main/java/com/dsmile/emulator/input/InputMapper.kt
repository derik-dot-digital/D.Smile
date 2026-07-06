package com.dsmile.emulator.input

import android.content.SharedPreferences
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.dsmile.emulator.emu.NativeCore
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Action(val label: String, val buttonBit: Int = 0) {
    ENTER("Enter / OK", NativeCore.BTN_ENTER),
    BACK("Exit", NativeCore.BTN_BACK),
    HELP("Help", NativeCore.BTN_HELP),
    ABC("Learning Zone (ABC)", NativeCore.BTN_ABC),
    RED("Red", NativeCore.BTN_RED),
    YELLOW("Yellow", NativeCore.BTN_YELLOW),
    BLUE("Blue", NativeCore.BTN_BLUE),
    GREEN("Green", NativeCore.BTN_GREEN),
    SAVE_STATE("Save State"),
    LOAD_STATE("Load State"),
    FAST_FORWARD("Fast Forward (hold)"),
    REWIND("Rewind (hold)"),
    MENU("Menu"),
}

interface HotkeyListener {
    fun onHotkey(action: Action, pressed: Boolean)
}

// Maps physical gamepad/keyboard input to emulator actions. Remappable, persisted.
class InputMapper(private val prefs: SharedPreferences) {
    private var keyMap = HashMap<Int, Action>()
    private var pressedButtons = 0
    var joyX = 0
        private set
    var joyY = 0
        private set

    init { load() }

    fun buttons(): Int = pressedButtons

    private fun defaults(): HashMap<Int, Action> = hashMapOf(
        KeyEvent.KEYCODE_BUTTON_A to Action.ENTER,
        KeyEvent.KEYCODE_BUTTON_B to Action.BACK,
        KeyEvent.KEYCODE_BUTTON_Y to Action.HELP,
        KeyEvent.KEYCODE_BUTTON_X to Action.ABC,
        KeyEvent.KEYCODE_BUTTON_L1 to Action.GREEN,
        KeyEvent.KEYCODE_BUTTON_R1 to Action.RED,
        KeyEvent.KEYCODE_BUTTON_L2 to Action.YELLOW,
        KeyEvent.KEYCODE_BUTTON_R2 to Action.BLUE,
        KeyEvent.KEYCODE_BUTTON_START to Action.MENU,
        KeyEvent.KEYCODE_BUTTON_THUMBR to Action.FAST_FORWARD,
        KeyEvent.KEYCODE_BUTTON_THUMBL to Action.REWIND,
        KeyEvent.KEYCODE_ENTER to Action.ENTER,
        KeyEvent.KEYCODE_ESCAPE to Action.BACK,
    )

    fun load() {
        keyMap = defaults()
        prefs.getStringSet("keymap", null)?.let { set ->
            keyMap = HashMap()
            for (entry in set) {
                val parts = entry.split(":")
                if (parts.size == 2) {
                    val code = parts[0].toIntOrNull() ?: continue
                    val action = runCatching { Action.valueOf(parts[1]) }.getOrNull() ?: continue
                    keyMap[code] = action
                }
            }
        }
    }

    fun save() {
        prefs.edit().putStringSet("keymap", keyMap.map { "${it.key}:${it.value.name}" }.toSet()).apply()
    }

    fun bind(keyCode: Int, action: Action) {
        keyMap.entries.removeAll { it.value == action }
        keyMap[keyCode] = action
        save()
    }

    fun bindingFor(action: Action): Int? = keyMap.entries.firstOrNull { it.value == action }?.key

    fun resetBindings() {
        keyMap = defaults()
        save()
    }

    /** Returns true if the event was consumed. */
    fun onKey(event: KeyEvent, hotkeys: HotkeyListener): Boolean {
        val pressed = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { joyY = if (pressed) 5 else 0; return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { joyY = if (pressed) -5 else 0; return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { joyX = if (pressed) -5 else 0; return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { joyX = if (pressed) 5 else 0; return true }
        }
        val action = keyMap[event.keyCode] ?: return false
        if (event.repeatCount > 0) return true
        if (action.buttonBit != 0) {
            pressedButtons = if (pressed) pressedButtons or action.buttonBit
            else pressedButtons and action.buttonBit.inv()
        } else {
            hotkeys.onHotkey(action, pressed)
        }
        return true
    }

    /** Handles joystick axis motion. Returns true if consumed. */
    fun onMotion(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false
        var x = event.getAxisValue(MotionEvent.AXIS_X)
        var y = event.getAxisValue(MotionEvent.AXIS_Y)
        if (abs(x) < 0.15f) x = 0f
        if (abs(y) < 0.15f) y = 0f
        if (x == 0f && y == 0f) {
            x = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            y = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        }
        joyX = (x * 5f).roundToInt().coerceIn(-5, 5)
        joyY = (-y * 5f).roundToInt().coerceIn(-5, 5)
        return true
    }
}
