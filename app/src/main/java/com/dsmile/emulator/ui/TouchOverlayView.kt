package com.dsmile.emulator.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import com.dsmile.emulator.emu.NativeCore
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * On-screen V.Smile controller: joystick, Enter, 4 color buttons, Help/Exit/ABC.
 * Toggleable, adjustable opacity, multi-touch.
 */
class TouchOverlayView(context: Context) : View(context) {

    interface Listener {
        fun onTouchInput(joyX: Int, joyY: Int, buttons: Int)
        fun onMenuRequested()
    }

    var listener: Listener? = null
    var controlOpacity = 0.55f
        set(v) { field = v; invalidate() }
    var controlsVisible = true
        set(v) { field = v; joyPointer = -1; buttonPointers.clear(); pushState(); invalidate() }
    var leds = 0
        set(v) { if (field != v) { field = v; invalidate() } }

    private data class Btn(
        val id: String, val label: String, val bit: Int, val color: Int,
        val labelColor: Int = Color.WHITE,
        var cx: Float = 0f, var cy: Float = 0f, var r: Float = 0f
    )

    // Colors follow the real V.Smile controller: purple body, orange enter
    // dial with yellow lettering, orange joystick dish with a blue-violet ball.
    private val buttons = listOf(
        Btn("red", "", NativeCore.BTN_RED, Color.rgb(224, 58, 47)),
        Btn("yellow", "", NativeCore.BTN_YELLOW, Color.rgb(245, 197, 24)),
        Btn("blue", "", NativeCore.BTN_BLUE, Color.rgb(46, 95, 208)),
        Btn("green", "", NativeCore.BTN_GREEN, Color.rgb(63, 165, 60)),
        Btn("enter", "ENTER", NativeCore.BTN_ENTER, Color.rgb(247, 148, 29), Color.rgb(255, 224, 102)),
        Btn("help", "?", NativeCore.BTN_HELP, Color.rgb(106, 79, 163)),
        Btn("exit", "EXIT", NativeCore.BTN_BACK, Color.rgb(124, 108, 176)),
        Btn("abc", "ABC", NativeCore.BTN_ABC, Color.rgb(91, 111, 199)),
        Btn("menu", "☰", 0, Color.rgb(60, 60, 60)),
    )

    private var joyCx = 0f
    private var joyCy = 0f
    private var joyR = 0f
    private var joyPointer = -1
    private var joyDx = 0f
    private var joyDy = 0f
    private val buttonPointers = HashMap<Int, Btn>()  // pointerId -> button
    private var heldBits = 0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val u = min(w, h) / 100f  // layout unit
        joyR = 16f * u
        joyCx = joyR + 6f * u
        joyCy = h - joyR - 8f * u

        val br = 7f * u
        val bx = w - 26f * u
        val by = h - 52f * u  // color cluster raised
        fun place(id: String, cx: Float, cy: Float, r: Float) {
            buttons.first { it.id == id }.apply { this.cx = cx; this.cy = cy; this.r = r }
        }
        // color diamond on the right, upper
        place("red", bx, by - 11f * u, br)
        place("yellow", bx + 11f * u, by, br)
        place("green", bx, by + 11f * u, br)
        place("blue", bx - 11f * u, by, br)
        // enter directly beneath the diamond
        place("enter", bx, by + 30f * u, 9f * u)
        // small buttons stacked on the left, above the joystick
        place("help", 12f * u, h - 80f * u, 5f * u)
        place("abc", 12f * u, h - 66f * u, 5f * u)
        place("exit", 12f * u, h - 52f * u, 5f * u)
        place("menu", w - 8f * u, 6f * u, 5f * u)
    }

    override fun onDraw(canvas: Canvas) {
        val menuBtn = buttons.first { it.id == "menu" }
        // Menu button is always visible (faint); the rest obey the toggle.
        paint.alpha = 255
        if (controlsVisible) {
            val a = (controlOpacity * 255).toInt()
            // joystick: orange dish with blue-violet ball (like the real unit)
            paint.color = Color.argb(a, 247, 148, 29)
            canvas.drawCircle(joyCx, joyCy, joyR, paint)
            paint.color = Color.argb((a * 0.6f).toInt(), 200, 110, 20)
            canvas.drawCircle(joyCx, joyCy, joyR * 0.62f, paint)
            paint.color = Color.argb(a, 123, 111, 208)
            canvas.drawCircle(joyCx + joyDx * joyR * 0.55f, joyCy + joyDy * joyR * 0.55f, joyR * 0.42f, paint)

            for (b in buttons) {
                if (b.id == "menu") continue
                val held = buttonPointers.values.any { it === b }
                paint.color = b.color
                paint.alpha = if (held) 255 else a
                canvas.drawCircle(b.cx, b.cy, b.r, paint)
                // LED ring on color buttons when the game lights them
                val ledBit = when (b.id) {
                    "green" -> 1; "blue" -> 2; "yellow" -> 4; "red" -> 8; else -> 0
                }
                if (ledBit != 0 && (leds and ledBit) != 0) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = b.r * 0.18f
                    paint.color = Color.WHITE
                    paint.alpha = 235
                    canvas.drawCircle(b.cx, b.cy, b.r * 1.12f, paint)
                    paint.style = Paint.Style.FILL
                }
                if (b.label.isNotEmpty()) {
                    textPaint.textSize = if (b.label.length > 3) b.r * 0.42f else b.r * 0.7f
                    textPaint.color = b.labelColor
                    textPaint.alpha = 255
                    canvas.drawText(b.label, b.cx, b.cy + b.r * 0.18f, textPaint)
                }
            }
        }
        paint.color = menuBtn.color
        paint.alpha = 120
        canvas.drawCircle(menuBtn.cx, menuBtn.cy, menuBtn.r, paint)
        textPaint.textSize = menuBtn.r
        canvas.drawText(menuBtn.label, menuBtn.cx, menuBtn.cy + menuBtn.r * 0.35f, textPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                val x = event.getX(idx)
                val y = event.getY(idx)
                val menuBtn = buttons.first { it.id == "menu" }
                if (hypot(x - menuBtn.cx, y - menuBtn.cy) <= menuBtn.r * 1.6f) {
                    listener?.onMenuRequested()
                    return true
                }
                if (!controlsVisible) return false
                if (hypot(x - joyCx, y - joyCy) <= joyR * 1.4f && joyPointer < 0) {
                    joyPointer = id
                    updateJoy(x, y)
                    return true
                }
                val hit = buttons.firstOrNull {
                    it.id != "menu" && hypot(x - it.cx, y - it.cy) <= it.r * 1.35f
                }
                if (hit != null) {
                    buttonPointers[id] = hit
                    heldBits = heldBits or hit.bit
                    pushState()
                    invalidate()
                    return true
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!controlsVisible) return false
                var handled = false
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    if (id == joyPointer) {
                        updateJoy(event.getX(i), event.getY(i))
                        handled = true
                    }
                }
                return handled || buttonPointers.isNotEmpty()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                var handled = false
                if (id == joyPointer) {
                    joyPointer = -1
                    joyDx = 0f
                    joyDy = 0f
                    pushState()
                    invalidate()
                    handled = true
                }
                buttonPointers.remove(id)?.let {
                    heldBits = buttonPointers.values.fold(0) { acc, b -> acc or b.bit }
                    pushState()
                    invalidate()
                    handled = true
                }
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    joyPointer = -1; joyDx = 0f; joyDy = 0f
                    buttonPointers.clear(); heldBits = 0
                    pushState(); invalidate()
                }
                return handled
            }
        }
        return false
    }

    private fun updateJoy(x: Float, y: Float) {
        var dx = (x - joyCx) / joyR
        var dy = (y - joyCy) / joyR
        val len = hypot(dx, dy)
        if (len > 1f) { dx /= len; dy /= len }
        joyDx = dx
        joyDy = dy
        pushState()
        invalidate()
    }

    private fun pushState() {
        val jx = (joyDx * 5f).roundToInt().coerceIn(-5, 5)
        val jy = (-joyDy * 5f).roundToInt().coerceIn(-5, 5)
        listener?.onTouchInput(jx, jy, heldBits)
    }
}
