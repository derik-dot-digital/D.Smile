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
import com.dsmile.emulator.emu.BackgroundMode
import com.dsmile.emulator.emu.BezelMode
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
    private var ffOn = false
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
            crtCurve = prefs.getFloat("crtCurveV", 1f)
            crtGlow = prefs.getFloat("crtGlowV", 1f)
            crtScan = prefs.getFloat("crtScanV", 1f)
            crtMask = prefs.getFloat("crtMaskV", 1f)
            crtVignette = prefs.getFloat("crtVigV", 1f)
            backgroundMode = BackgroundMode.valueOf(
                prefs.getString("background", BackgroundMode.BLACK.name)!!
            )
            bezelMode = BezelMode.valueOf(prefs.getString("bezel", BezelMode.NONE.name)!!)
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
            if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
            if (event.action == KeyEvent.ACTION_DOWN) {
                mapper.bind(event.keyCode, action)
                Toast.makeText(this, "${action.label} → ${KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_")}", Toast.LENGTH_SHORT).show()
                if (wizardActive) advanceWizard() else captureAction = null
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
        if (mapper.onMotion(event, this)) {
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
            if (ffOn) "Fast forward: ON" else "Fast forward: OFF",
            if (overlay.controlsVisible) "Hide touch controls" else "Show touch controls",
            "Controls opacity…",
            "Shader…",
            "CRT options…",
            "Aspect ratio…",
            "Background…",
            "Bezel…",
            "Map controller buttons…",
            "Trigger sensitivity…",
            "Reset game",
            "Quit"
        )
        AlertDialog.Builder(this)
            .setTitle(romName.substringBeforeLast('.'))
            .setItems(items) { _, which ->
                when (which) {
                    1 -> pickSlot("Save to slot") { saveState(it) }
                    2 -> pickSlot("Load from slot") { loadState(it) }
                    3 -> {
                        ffOn = !ffOn
                        NativeCore.nativeSetFastForward(ffOn)
                    }
                    4 -> {
                        overlay.controlsVisible = !overlay.controlsVisible
                        prefs.edit().putBoolean("touchControls", overlay.controlsVisible).apply()
                    }
                    5 -> showOpacityDialog()
                    6 -> pickChoice("Shader", ShaderMode.entries.map { it.name }, renderer.shaderMode.ordinal) {
                        renderer.shaderMode = ShaderMode.entries[it]
                        prefs.edit().putString("shader", renderer.shaderMode.name).apply()
                    }
                    7 -> showCrtOptions()
                    8 -> pickChoice("Aspect ratio", AspectMode.entries.map { it.name }, renderer.aspectMode.ordinal) {
                        renderer.aspectMode = AspectMode.entries[it]
                        prefs.edit().putString("aspect", renderer.aspectMode.name).apply()
                    }
                    9 -> pickChoice(
                        "Background",
                        listOf("Black", "V.Smile Blue", "V.Smile Purple"),
                        renderer.backgroundMode.ordinal
                    ) {
                        renderer.backgroundMode = BackgroundMode.entries[it]
                        prefs.edit().putString("background", renderer.backgroundMode.name).apply()
                    }
                    10 -> pickChoice(
                        "Bezel",
                        listOf("None", "Silver", "Black"),
                        renderer.bezelMode.ordinal
                    ) {
                        renderer.bezelMode = BezelMode.entries[it]
                        prefs.edit().putString("bezel", renderer.bezelMode.name).apply()
                    }
                    11 -> startBindingWizard()
                    12 -> showTriggerDialog()
                    13 -> NativeCore.nativeReset()
                    14 -> confirmQuit()
                }
            }
            .setOnDismissListener {
                menuOpen = false
                if (initialized) NativeCore.nativeSetPaused(false)
                hideSystemUi()
            }
            .show()
    }

    /** Radio-list picker that shows the current selection. */
    private fun pickChoice(title: String, items: List<String>, current: Int, f: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(items.toTypedArray(), current) { d, i ->
                f(i)
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { if (!menuOpen && !wizardActive && initialized) NativeCore.nativeSetPaused(false) }
            .show()
    }

    private fun pickSlot(title: String, f: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(arrayOf("Slot 1", "Slot 2", "Slot 3")) { _, s -> f(s) }
            .setOnDismissListener { if (!menuOpen && initialized) NativeCore.nativeSetPaused(false) }
            .show()
    }

    private fun showOpacityDialog() {
        val touchOverlay = overlay  // View.getOverlay() shadows the field inside apply{}
        val seek = SeekBar(this).apply {
            max = 100
            progress = (touchOverlay.controlOpacity * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                    touchOverlay.controlOpacity = p / 100f
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

    // Sequential binding wizard: walks every action; press a button to bind
    // (stealing it from any other action), Skip to keep, Unassign to clear.
    private var wizardActive = false
    private var wizardIndex = 0
    private var wizardDialog: AlertDialog? = null

    private fun startBindingWizard() {
        wizardActive = true
        wizardIndex = 0
        showWizardStep()
    }

    // Per-effect intensity sliders; changes apply live so the paused frame previews them.
    private fun showCrtOptions() {
        data class Fx(val name: String, val key: String, val get: () -> Float, val set: (Float) -> Unit)
        val fx = listOf(
            Fx("Curvature", "crtCurveV", { renderer.crtCurve }, { renderer.crtCurve = it }),
            Fx("Glow", "crtGlowV", { renderer.crtGlow }, { renderer.crtGlow = it }),
            Fx("Scanlines", "crtScanV", { renderer.crtScan }, { renderer.crtScan = it }),
            Fx("Aperture mask", "crtMaskV", { renderer.crtMask }, { renderer.crtMask = it }),
            Fx("Vignette", "crtVigV", { renderer.crtVignette }, { renderer.crtVignette = it }),
        )
        val pad = (resources.displayMetrics.density * 20).toInt()
        val col = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        for (e in fx) {
            val label = android.widget.TextView(this)
            fun labelText(v: Int) { label.text = "${e.name}: $v%" }
            labelText((e.get() * 100).toInt())
            val seek = SeekBar(this).apply {
                max = 100
                progress = (e.get() * 100).toInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                        e.set(p / 100f)
                        labelText(p)
                        prefs.edit().putFloat(e.key, p / 100f).apply()
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
            col.addView(label)
            col.addView(seek)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(col) }
        AlertDialog.Builder(this)
            .setTitle("CRT effects")
            .setView(scroll)
            .setPositiveButton("OK", null)
            .setOnDismissListener { if (!menuOpen && !wizardActive && initialized) NativeCore.nativeSetPaused(false) }
            .show()
    }

    private fun confirmQuit() {
        AlertDialog.Builder(this)
            .setMessage("Quit ${romName.substringBeforeLast('.')}?")
            .setPositiveButton("Quit") { _, _ -> finish() }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { if (!menuOpen && !wizardActive && initialized) NativeCore.nativeSetPaused(false) }
            .show()
    }

    private fun showTriggerDialog() {
        val seek = SeekBar(this).apply {
            max = 90
            progress = ((mapper.triggerThreshold * 100).toInt() - 5).coerceIn(0, 90)
        }
        AlertDialog.Builder(this)
            .setTitle("Trigger sensitivity (pull depth to register)")
            .setView(seek)
            .setPositiveButton("OK") { _, _ ->
                mapper.triggerThreshold = (seek.progress + 5) / 100f
                Toast.makeText(this, "Trigger threshold: ${seek.progress + 5}%", Toast.LENGTH_SHORT).show()
            }
            .setOnDismissListener { if (!menuOpen && !wizardActive && initialized) NativeCore.nativeSetPaused(false) }
            .show()
    }

    private fun showWizardStep() {
        wizardDialog?.setOnDismissListener(null)
        wizardDialog?.dismiss()
        val actions = Action.entries
        if (wizardIndex >= actions.size) {
            endWizard()
            return
        }
        val action = actions[wizardIndex]
        val key = mapper.bindingFor(action)
        val current = if (key != null) KeyEvent.keyCodeToString(key).removePrefix("KEYCODE_") else "unbound"
        wizardDialog = AlertDialog.Builder(this)
            .setTitle("${wizardIndex + 1}/${actions.size}: ${action.label}")
            .setMessage("Press a controller button to bind\n(currently: $current)")
            .setNeutralButton("Skip") { _, _ -> advanceWizard() }
            .setNegativeButton("Unassign") { _, _ ->
                mapper.unbind(action)
                advanceWizard()
            }
            .setPositiveButton("Done") { _, _ -> endWizard() }
            .setCancelable(false)
            .show()
        // The dialog window receives input before the activity does, so capture
        // happens here. Everything except Back is bindable (incl. d-pad keys);
        // use the touchscreen for Skip/Unassign/Done.
        wizardDialog?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK) return@setOnKeyListener false
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                wizardBind(keyCode, action)
            }
            true
        }
        // Triggers/d-pad on many pads (incl. Xbox) are analog axes, not keys.
        wizardDialog?.window?.decorView?.setOnGenericMotionListener { _, ev ->
            if (ev.source and android.view.InputDevice.SOURCE_JOYSTICK ==
                android.view.InputDevice.SOURCE_JOYSTICK) {
                val code = mapper.axisKeyOf(ev)
                if (code != 0 && wizardActive) wizardBind(code, action)
                true
            } else false
        }
    }

    private fun wizardBind(keyCode: Int, action: com.dsmile.emulator.input.Action) {
        mapper.bind(keyCode, action)
        Toast.makeText(
            this,
            "${action.label} → ${KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")}",
            Toast.LENGTH_SHORT
        ).show()
        advanceWizard()
    }

    private fun advanceWizard() {
        wizardIndex++
        showWizardStep()
    }

    private fun endWizard() {
        captureAction = null
        wizardActive = false
        wizardDialog?.setOnDismissListener(null)
        wizardDialog?.dismiss()
        wizardDialog = null
        Toast.makeText(this, "Bindings saved", Toast.LENGTH_SHORT).show()
        if (initialized) NativeCore.nativeSetPaused(false)
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
