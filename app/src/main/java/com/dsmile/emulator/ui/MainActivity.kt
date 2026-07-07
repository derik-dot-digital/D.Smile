package com.dsmile.emulator.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile

class MainActivity : Activity() {

    private data class RomEntry(val name: String, val uri: Uri)

    private val prefs by lazy { getSharedPreferences("dsmile", Context.MODE_PRIVATE) }
    private val roms = ArrayList<RomEntry>()
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var statusText: TextView

    private companion object {
        const val REQ_PICK_FOLDER = 1
        val ROM_EXTENSIONS = setOf("bin", "rom", "vsmile")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (resources.displayMetrics.density * 16).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val title = TextView(this).apply {
            text = "D.Smile"
            textSize = 28f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, pad / 2)
        }
        statusText = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, pad / 2)
        }
        val buttonRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val pickBtn = Button(this).apply {
            text = "ROM folder…"
            setOnClickListener { pickFolder() }
        }
        val settingsBtn = Button(this).apply {
            text = "Settings"
            setOnClickListener { showSettings() }
        }
        buttonRow.addView(pickBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttonRow.addView(settingsBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        val list = ListView(this).apply {
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> launchRom(roms[pos]) }
        }

        root.addView(title)
        root.addView(statusText)
        root.addView(buttonRow)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        scanRoms()
    }

    private fun pickFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        startActivityForResult(intent, REQ_PICK_FOLDER)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_FOLDER && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            prefs.edit().putString("romFolder", uri.toString()).apply()
            scanRoms()
        }
    }

    private fun scanRoms() {
        roms.clear()
        adapter.clear()
        val folderStr = prefs.getString("romFolder", null)
        if (folderStr == null) {
            statusText.text = "Pick your ROM folder to get started"
            return
        }
        val dir = DocumentFile.fromTreeUri(this, Uri.parse(folderStr))
        if (dir == null || !dir.isDirectory) {
            statusText.text = "ROM folder unavailable — pick it again"
            return
        }
        var sysromUri: String? = null
        for (f in dir.listFiles()) {
            val name = f.name ?: continue
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in ROM_EXTENSIONS) continue
            val base = name.lowercase()
            if ("bios" in base || "sysrom" in base || "system rom" in base) {
                sysromUri = f.uri.toString()
                continue
            }
            roms.add(RomEntry(name.removeSuffix(".$ext").removeSuffix(".${ext.uppercase()}"), f.uri))
        }
        prefs.edit().putString("sysromUri", sysromUri).apply()
        roms.sortBy { it.name.lowercase() }
        for (r in roms) adapter.add(r.name)
        val bios = if (sysromUri != null) " • BIOS found (real intro available)" else ""
        statusText.text = "${roms.size} game(s)$bios"
        if (roms.isEmpty()) {
            Toast.makeText(this, "No .bin ROMs found in that folder", Toast.LENGTH_LONG).show()
        }
    }

    private fun launchRom(rom: RomEntry) {
        startActivity(
            Intent(this, EmuActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(rom.uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    private fun showSettings() {
        val pal = prefs.getBoolean("pal", false)
        val intro = prefs.getBoolean("playIntro", true)
        val items = arrayOf(
            "Video system: ${if (pal) "PAL (50 Hz)" else "NTSC (60 Hz)"}",
            "V.Smile boot intro: ${if (intro) "on (needs BIOS in ROM folder)" else "off"}",
            "All files access (for frontends like iiSU)…"
        )
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> { prefs.edit().putBoolean("pal", !pal).apply(); showSettings() }
                    1 -> { prefs.edit().putBoolean("playIntro", !intro).apply(); showSettings() }
                    2 -> requestAllFilesAccess()
                }
            }
            .show()
    }

    private fun requestAllFilesAccess() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            if (android.os.Environment.isExternalStorageManager()) {
                Toast.makeText(this, "Already granted", Toast.LENGTH_SHORT).show()
                return
            }
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (prefs.getString("romFolder", null) != null) scanRoms()
    }
}
