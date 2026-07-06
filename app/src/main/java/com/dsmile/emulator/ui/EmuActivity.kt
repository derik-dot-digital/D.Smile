package com.dsmile.emulator.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dsmile.emulator.emu.AspectMode
import com.dsmile.emulator.emu.GameRenderer
import com.dsmile.emulator.emu.NativeCore
import com.dsmile.emulator.emu.ShaderMode
import com.dsmile.emulator.input.Action
import com.dsmile.emulator.input.HotkeyListener
import com.dsmile.emulator.input.InputMapper
import java.io.File

class EmuActivity : Activity(), TouchOverlayView.Listener, HotkeyListener {

    private lateinit var glView: GLSurfaceView
    private lateinit var overlay: TouchOverlayView
    private lateinit var renderer: GameRenderer
    private lateinit var mapper: InputMapper
    private val prefs by lazy { getSharedPreferences("dsmile", Context.MODE_PRIVATE) }

    private var romName = "game"
    private var initialized = false
    private var menuOpen = false
    private var touchJoyX = 0
    private var touchJoyY = 0
    private var touchButtons = 0
    private var captureAction: Action? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        mapper = InputMapper(prefs)
        renderer = GameRenderer().apply {
            shaderMode = ShaderMode.valueOf(prefs.getString("shader", ShaderMode.SHARP.name)!!)
            aspectMode = AspectMode.valueOf(prefs.getString("aspect", AspectMode.FOUR_THREE.name)!!)
        }
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        overlay = TouchOverlayView(this).apply {
            listener = this@EmuActivity
            controlOpacity = prefs.getFloat("opacity", 0.55f)
            controlsVisible = prefs.getBoolean("touchControls", true)
        }
        val root = FrameLayout(this)
        root.addView(glView)
        root.addView(overlay)
        setContentView(root)

        val rom = loadRomBytes()
        if (rom == null) {
            Toast.makeText(this, "Could not read ROM", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val pal = intent.getBooleanExtra("pal", prefs.getBoolean("pal", false))
        val sysrom = if (prefs.getBoolean("playIntro", true)) loadSysromBytes() else null
        if (!NativeCore.nativeInit(rom, sysrom, pal)) {
            Toast.makeText(this, "Failed to initialize emulator", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        initialized = true
        NativeCore.nativeStart()

        // Poll controller LEDs into the overlay a few times a second.
        overlay.postDelayed(object : Runnable {
            override fun run() {
                if (!initialized) return
                overlay.leds = NativeCore.nativeGetLeds()
                overlay.postDelayed(this, 250)
            }
        }, 250)
    }

    /** Accepts: data Uri (content/file), or string extras rom/ROM/path (file path or uri). */
    private fun loadRomBytes(): ByteArray? {
        val uri: Uri? = intent.data
        val extraPath = intent.getStringExtra("rom")
            ?: intent.getStringExtra("ROM")
            ?: intent.getStringExtra("path")
            ?: intent.getStringExtra("uri")
        return try {
            when {
                uri != null -> readUri(uri)
                extraPath != null -> {
                    romName = extraPath.substringAfterLast('/').substringAfterLast('\\')
                    if (extraPath.startsWith("content:") || extraPath.startsWith("file:")) {
                        readUri(Uri.parse(extraPath))
                    } else {
                        File(extraPath).readBytes()
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun readUri(uri: Uri): ByteArray? {
        if (uri.scheme == "file") return File(uri.path!!).readBytes()
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) romName = c.getString(idx) ?: romName
        }
        return contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }

    private fun loadSysromBytes(): ByteArray? {
        val uriStr = prefs.getString("sysromUri", null) ?: return null
        return try {
            readUri(Uri.parse(uriStr))
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- input ----------------

    private fun pushInput() {
        val jx = if (touchJoyX != 0 || touchJoyY != 0) touchJoyX else mapper.joyX
        val jy = if (touchJoyX != 0 || touchJoyY != 0) touchJoyY else mapper.joyY
        NativeCore.nativeSetInput(jx, jy, touchButtons or mapper.buttons())
    }

    override fun onTouchInput(joyX: Int, joyY: Int, buttons: Int) {
        touchJoyX = joyX
        touchJoyY = joyY
        touchButtons = buttons
        pushInput()
    }

    override fun onMenuRequested() = showMenu()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        captureAction?.let { action ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                mapper.bind(event.keyCode, action)
                captureAction = null
                Toast.makeText(this, "${action.label} bound", Toast.LENGTH_SHORT).show()
            }
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) showMenu()
            return true
        }
        if (mapper.onKey(event, this)) {
            pushInput()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (mapper.onMotion(event)) {
            pushInput()
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onHotkey(action: Action, pressed: Boolean) {
        when (action) {
            Action.FAST_FORWARD -> NativeCore.nativeSetFastForward(pressed)
            Action.REWIND -> NativeCore.nativeSetRewind(pressed)
            Action.SAVE_STATE -> if (pressed) saveState(0)
            Action.LOAD_STATE -> if (pressed) loadState(0)
            Action.MENU -> if (pressed) showMenu()
            else -> {}
        }
    }

    // ---------------- save states ----------------

    private fun stateFile(slot: Int): File {
        val dir = File(getExternalFilesDir(null), "states").apply { mkdirs() }
        return File(dir, "$romName.slot$slot.dss")
    }

    private fun saveState(slot: Int) {
        val data = NativeCore.nativeSaveState()
        if (data != null) {
            stateFile(slot).writeBytes(data)
            Toast.makeText(this, "State saved (slot ${slot + 1})", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadState(slot: Int) {
        val f = stateFile(slot)
        if (!f.exists()) {
            Toast.makeText(this, "No state in slot ${slot + 1}", Toast.LENGTH_SHORT).show()
            return
        }
        if (NativeCore.nativeLoadState(f.readBytes())) {
            Toast.makeText(this, "State loaded (slot ${slot + 1})", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "State is for a different game/version", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------- menu ----------------

    @SuppressLint("SetTextI18n")
    private fun showMenu() {
        if (menuOpen || !initialized) return
        menuOpen = true
        NativeCore.nativeSetPaused(true)
        val items = arrayOf(
            "Resume",
            "Save state…",
            "Load state…",
            if (overlay.controlsVisible) "Hide touch controls" else "Show touch controls",
            "Controls opacity…",
            "Video: shader (${renderer.shaderMode})",
            "Video: aspect (${renderer.aspectMode})",
            "Map controller buttons…",
            "Reset game",
            "Quit"
        )
        AlertDialog.Builder(this)
            .setTitle("D-Smile")
            .setItems(items) { _, which ->
                when (which) {
                    1 -> pickSlot("Save to slot") { saveState(it) }
                    2 -> pickSlot("Load from slot") { loadState(it) }
                    3 -> {
                        overlay.controlsVisible = !overlay.controlsVisible
                        prefs.edit().putBoolean("touchControls", overlay.controlsVisible).apply()
                    }
                    4 -> showOpacityDialog()
                    5 -> cycle("shader") {
                        renderer.shaderMode = ShaderMode.entries[
                            (renderer.shaderMode.ordinal + 1) % ShaderMode.entries.size]
                        prefs.edit().putString("shader", renderer.shaderMode.name).apply()
                    }
                    6 -> cycle("aspect") {
                        renderer.aspectMode = AspectMode.entries[
                            (renderer.aspectMode.ordinal + 1) % AspectMode.entries.size]
                        prefs.edit().putString("aspect", renderer.aspectMode.name).apply()
                    }
                    7 -> showMappingDialog()
                    8 -> NativeCore.nativeReset()
                    9 -> finish()
                }
            }
            .setOnDismissListener {
                menuOpen = false
                if (initialized) NativeCore.nativeSetPaused(false)
                hideSystemUi()
            }
            .show()
    }

    private fun cycle(tag: String, f: () -> Unit) {
        f()
        showMenu()  // re-open to show the new value
    }

    private fun pickSlot(title: String, f: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(arrayOf("Slot 1", "Slot 2", "Slot 3")) { _, s -> f(s) }
            .setOnDismissListener { if (!menuOpen && initialized) NativeCore.nativeSetPaused(false) }
            .show()
    }

    private fun showOpacityDialog() {
        val seek = SeekBar(this).apply {
            max = 100
            progress = (overlay.controlOpacity * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                    overlay.controlOpacity = p / 100f
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        AlertDialog.Builder(this)
            .setTitle("Controls opacity")
            .setView(seek)
            .setPositiveButton("OK") { _, _ ->
                prefs.edit().putFloat("opacity", overlay.controlOpacity).apply()
            }
            .setOnDismissListener { if (!menuOpen && initialized) NativeCore.nativeSetPaused(false) }
            .show()
    }

    private fun showMappingDialog() {
        val bindable = Action.entries.toTypedArray()
        val labels = bindable.map { a ->
            val key = mapper.bindingFor(a)
            "${a.label}  —  ${if (key != null) KeyEvent.keyCodeToString(key).removePrefix("KEYCODE_") else "unbound"}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Press an entry, then press a button on your controller")
            .setItems(labels) { _, which ->
                captureAction = bindable[which]
                Toast.makeText(this, "Press a button for: ${bindable[which].label}", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Reset to defaults") { _, _ -> mapper.resetBindings() }
            .setOnDismissListener { if (!menuOpen && initialized) NativeCore.nativeSetPaused(false) }
            .show()
    }

    // ---------------- lifecycle ----------------

    private fun hideSystemUi() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onPause() {
        super.onPause()
        if (initialized) NativeCore.nativeSetPaused(true)
        glView.onPause()
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        if (initialized && !menuOpen) NativeCore.nativeSetPaused(false)
        hideSystemUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (initialized) {
            initialized = false
            NativeCore.nativeStop()
            NativeCore.nativeDestroy()
        }
    }
}
