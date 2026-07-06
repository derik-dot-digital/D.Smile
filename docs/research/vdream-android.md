# Deep-read report: fodsoft/vdream and TimmyTurner51/VSmileEmu-Android

## 1. vdream (https://github.com/fodsoft/vdream)

### What it is
- **V.Dream** is a **closed-source Windows-only** V.Smile family emulator + debugger by FODSOFT (Néo Foderé de Frutos, GitHub user `neofodere`). Repo description: "V.Smile family emulator and debugger supporting V.Smile, V.Flash (V.Smile Pro) and V.Smile Baby".
- **The GitHub repo contains NO source code.** `GET /repos/fodsoft/vdream/git/trees/HEAD?recursive=1` returns exactly one blob: `README.md` (546 bytes, just logos + trademark disclaimer). Repo size = 0, `language: null`, single `main` branch, no releases via API, no wiki content. The repo exists purely as a project page / issue tracker. Created 2025-12-01, last push 2026-06-25. Latest README commits are titled "README V.Dream v2.0" (2026-06-14), so v2.0 exists or is imminent; itch.io still lists v1.4.1 (updated ~2026-02).
- Distribution: itch.io (`fodsoft.itch.io/vdream` — `vdream-v1.4.1-win-installer.exe` 18 MB, `vdream-v1.4.1-win-x64-portable.7z` 5.6 MB), SourceForge (`v-dream`), fodsoft.com/vdream (403s to bots), Softpedia, Uptodown. Windows x64/x86/ARM64. Written in **C (core) + C#/XAML (WPF UI)**. Main core binary is `vdream_core.exe`, configured by a text file `vdream_core.config` next to the exe (e.g. `EMUSPEED=100`).

### Provenance — the core is V.Frown
- **Issue #1** ("Is the emulator core copied from VFrown?", opened by `sp1187` — the author of veesem): symbol names in `vdream_core.exe` closely match **V.Frown (https://github.com/Schnert0/VFrown)**. Author's reply (verbatim): *"Yes, the core of my emulator was originally based on V.Frown. Since then I have improved it, added features, removed others, and adapted it to my own interface."* Attribution was then added; itch.io now states "The V.Dream core is based on V.Frown, an emulator originally developed by Ian (Schnert0) and other contributors."
- **Practical takeaway for D-Smile: vdream documents nothing about the hardware.** No registers, no controller protocol, no timing information is published anywhere in the repo. If you want what vdream knows, read **Schnert0/VFrown** — that is its actual upstream.

### The one genuinely useful engineering lesson from vdream
- **Issue #4 "too fast"** (open): on a 144 Hz+ monitor (i5-14600KF / RTX 4070 Ti Super / Win 11) the emulator runs much faster than real time even with `EMUSPEED=100`. Author's diagnosis (verbatim): *"this seems to be an issue inherited from the V.Frown emulator... the emulation speed is currently tied to the display refresh rate instead of real timing, so on high-refresh-rate monitors it runs much faster than intended."*
  - **Lesson for D-Smile on Android**: never pace emulation purely off vsync/Choreographer callbacks. Android devices commonly run 90/120/144 Hz; pace against a real-time clock (or count vsyncs against refresh rate) and decouple emulated 50/60 Hz from display Hz.
- Other issues: #5 offer of RU/UA translations (open); #2/#3 compatibility-list markdown updates (closed) — a compatibility list is maintained but not in this repo.

## 2. VSmileEmu-Android (https://github.com/TimmyTurner51/VSmileEmu-Android)

### Overview
- Self-described: "An experimental VSmile emulator **written by AI, vibe coded**, built for Android!" Credits sp1187. Created 2025-11-09, last push 2025-11-09 (single-day project, dormant), 2 stars, 0 issues/PRs. License: ISC (copied from veesem).
- **It is a port of `veesem` by sp1187 (Simon Eriksson)** — the complete veesem source tree is vendored verbatim at `app/src/main/cpp/veesem/` (ISC license, "Copyright © 2024-2025 Simon Eriksson"), including its desktop-only SDL2/ImGui UI (`src/ui/`, `src/contrib/imgui/`, `src/main.cc`) which is simply not compiled on Android. Only `veesem/src/core/**` is built.
- Author's performance claim (README): "near-constant 60-61 FPS while testing on Redmagic 10 Pro" (Snapdragon 8 Elite phone) — despite an objectively inefficient rendering path (below), i.e. the veesem core itself is cheap enough that even a naive Android front-end hits full speed on a flagship.

### NDK/JNI layout
```
app/src/main/cpp/
  CmakeLists.txt              (top-level, note lowercase 'm' in filename)
  android_bridge/
    jni_bridge.cpp            <- the ONLY bridge file actually compiled
    android_emulator.{h,cpp}  <- DEAD CODE (wrapper class, not in CMake)
    android_audio.cpp         <- stub (comment only)
    android_graphics.cpp      <- stub (comment only)
    android_input.cpp         <- stub (comment only)
  veesem/                     <- vendored upstream core
app/src/main/java/com/vsmileemu/android/  <- Kotlin app (Jetpack Compose)
```
- CMake (`app/src/main/cpp/CmakeLists.txt`): builds `veesem_core` as a STATIC lib from the ~30 `core/spg200/*.cc` + `core/vsmile/*.cc` files, then `vsmile_android` as a SHARED lib from **only `android_bridge/jni_bridge.cpp`**, linked against `veesem_core`, `android`, `log`. C++20 required. `add_compile_options(-O3 -ffast-math)`.
- **NDK pitfall found here**: `add_compile_options(-UREG_R0 -UREG_R1 ... -UREG_R7)` plus `#ifdef REG_R0 #undef REG_R0 ...` in jni_bridge.cpp — **Android's signal/ucontext headers define `REG_R0..REG_R7` macros that collide with unSP CPU register enum names**. Any Android port of an unSP core with `REG_*` identifiers must undefine these.
- **Second NDK pitfall**: `veesem/src/core/common.h` was patched for Android: `std::source_location` is only available on API 30+, so it has a `#if defined(__ANDROID__) && __ANDROID_API__ < 30` fallback using `__builtin_FILE()/__builtin_LINE()` for its `die()` helper. This is the only Android modification to the core I found.
- JNI surface (`jni_bridge.cpp`, class `com.vsmileemu.android.core.EmulatorCore`), one global `static std::unique_ptr<VSmile> g_vsmile`:
  - `nativeInit(jbyteArray sysrom /*nullable*/, jbyteArray cartrom, jint cartSize, jboolean usePAL) -> jboolean`. Sysrom must be exactly `sizeof(VSmile::SysRomType)` = 1M `word_t` = **2 MiB**; cart max = 4M words = **8 MiB** (`VSmile::CartRomType`). Copies via `GetByteArrayElements`/`memcpy`/`JNI_ABORT`. Comment: "ROM files are little-endian, Android ARM is little-endian, so no swap needed". If sysrom == null it builds a **dummy BIOS**: `sysrom_data->fill(0)` then `for (int i = 0xfffc0; i < 0xfffdc; i += 2) (*sysrom_data)[i+1] = 0x31;` (stuffs the reset/IRQ vector region so games boot without a real BIOS — same trick as desktop veesem). Constructs `VSmile(sysrom, cartrom, CartType::STANDARD, nullptr /*no Art NVRAM*/, 0xe /*UK English region*/, true /*VTech logo*/, PAL|NTSC)` then calls `g_vsmile->Reset()` (commented "CRITICAL ... initialize CPU state and program counter").
  - `nativeRunFrame()` -> `g_vsmile->RunFrame()`.
  - `nativeGetFrameBuffer() -> jbyteArray`: returns `g_vsmile->GetPicture()` raw (320×240×2 bytes, **RGB555 with bit15 = transparency**, see below). **Allocates a new 153,600-byte Java array every frame AND calls `LOGI` twice per frame** (`getFrameBuffer: picture.size()...` / `Returning %zu bytes`) — per-frame logging is a real perf bug.
  - `nativeGetAudioSamples() -> jshortArray`: comment block: "**IMPORTANT: VSmile SPU outputs UNSIGNED 16-bit audio (0-65535) but Android AudioTrack expects SIGNED 16-bit** ... convert by XOR with 0x8000". Does exactly that into a temp `std::vector<int16_t>`, new `jshortArray` per frame.
  - `nativeSendInput(8 jbooleans: enter,help,back,abc,red,yellow,blue,green + jint joyX, jint joyY)` — fills `VSmileJoy::JoyInput` (x/y range **-5..+5**), calls `UpdateJoystick`.
  - `nativePressOnButton(jboolean)` -> `UpdateOnButton` (console power button).
  - `nativeDestroy()` -> `g_vsmile.reset()`.
- Dead code: `android_emulator.{h,cpp}` is a complete alternate wrapper (`AndroidEmulator` class with pause/resume/FPS/RGB555→RGB565 conversion in C++ and stubbed saveState/loadState returning empty) that is never compiled — evidence the "AI vibe coding" produced two designs and shipped the simpler one.

### Rendering approach
- **Neither GLSurfaceView, SurfaceView, TextureView, nor Vulkan.** Pure **Jetpack Compose**: the emu loop converts each frame to an `android.graphics.Bitmap` (`Bitmap.Config.RGB_565`, 320×240, one **reused** bitmap + one reused direct `ByteBuffer` little-endian) and pushes it through a `MutableStateFlow<FrameData>`; the UI is a Compose `Image(bitmap.asImageBitmap(), contentScale = FillBounds, filterQuality = FilterQuality.None)` inside a `BoxWithConstraints` that letterboxes to **4:3** and applies a user scale (50/75/100%/fill).
  - `FrameData(bitmap, frameNumber)` data-class wrapper exists specifically to force StateFlow emission since the Bitmap object identity never changes (documented in comment). Mutating a bitmap currently being composited is a tearing risk they accepted.
  - Pixel conversion (`EmulationActivity.convertFrameToBitmap`, comment: "This is the performance bottleneck - optimize heavily!"): per-pixel Kotlin loop; input word `[15:T][14-10:R][9-5:G][4-0:B]`; **if bit15 (transparent) set -> output 0 (black)**; else RGB565 = `(r5 << 11) | ((g5 << 1) << 5) | b5` (green 5→6 bit by shift, no MSB replication in the Kotlin path; the dead C++ path did `(g<<1)|(g>>4)`).
  - Manifest requires OpenGL ES 3.0 (`<uses-feature android:glEsVersion="0x00030000" required="true"/>`) but **no GL code exists** — vestigial.

### Audio
- **java `android.media.AudioTrack` in MODE_STREAM** (not Oboe, not AAudio, not OpenSL — despite `minSdk = 26  // Android 8.0 for AAudio support` in gradle and "AAudio/OpenSL ES" comments in the stub .cpp files).
- `audio/AudioManager.kt`: 48,000 Hz, `CHANNEL_OUT_STEREO`, `ENCODING_PCM_16BIT`, `USAGE_GAME`/`CONTENT_TYPE_MUSIC`, buffer = `getMinBufferSize(...) * 4`. Writer runs in a coroutine on `Dispatchers.IO` consuming a `Channel<ShortArray>(capacity = 4)`; `writeSamples` uses `trySend` — **if the queue is full the batch is silently dropped** (counts `droppedSamples`, warns every 100 drops). Blocking `track.write()` inside the consumer.
- **Resampling is done in Kotlin** (`AudioResampler` at bottom of `EmulationActivity.kt`): input rate hardcoded **281,250 Hz** (the SPG200 SPU native rate = 27 MHz / 96, confirmed in core: `SimpleClock<96> sample_clock_` in `spu.h`), output 48,000 Hz, linear interpolation per stereo frame, then re-chunked to exactly `targetFramesPerVideoFrame` (**800 frames/video frame NTSC = 48000/60, 960 PAL = 48000/50**) with leftover carry between video frames.
- In-app settings text: *"Note: Audio may crackle during heavy emulation"* — the author's own documented latency/underrun problem.

### Emulation loop / frame pacing
- `EmulationActivity.startEmulationLoop()`: a coroutine on **`Dispatchers.Default`** (not a dedicated thread, not audio-clock- or vsync-driven): `runFrame()` → `getAudioSamples()` → resample → queue → `getFrameBuffer()` → convert to bitmap → StateFlow → then `delay(sleepTimeMs)` where `sleepTimeMs = (targetFrameTimeNanos - frameTime)/1_000_000`. Constants: `NTSC_FRAME_TIME_NS = 16_666_667`, `PAL_FRAME_TIME_NS = 20_000_000`. Millisecond-granularity `delay()` on a coroutine dispatcher = jittery pacing; no Choreographer/vsync integration, no audio-clock sync, no spin-wait for sub-ms remainder.
- **PAL is hardcoded** (`val usePal = true` in `loadAndStartEmulation`) even though the whole NTSC path exists.
- Boot sequence after init: `pressOnButton(true); delay(100); pressOnButton(false)` — the V.Smile needs its power button pressed to boot.
- Lifecycle: `onPause` stops loop+audio; `onResume` restarts only if a ROM was already loaded; `onDestroy` releases audio, destroys core. `FLAG_KEEP_SCREEN_ON` set. FPS counted app-side once/second.
- Frame skip: settings toggle exists but marked `// TODO: implement`. Save states: TODO (only in the dead C++ wrapper). Saves folder/`.sav` plumbing exists (`RomRepository.saveSaveData` etc.) but nothing calls it — V.Smile carts are ROM-only except Art Studio NVRAM, which this port passes `nullptr` for.

### Input handling
- **On-screen controls**: pure Compose (`ui/emulation/VirtualController.kt`), no images — `Box`+`clip(CircleShape)`+`background(color)` buttons with `pointerInput { detectTapGestures(onPress = { pressed=true; tryAwaitRelease(); pressed=false }) }`; overall controller overlay is `alpha(0.7f)`, bottom-aligned, 200.dp tall, over the game image. Left cluster: HELP/ENTER row, analog joystick, BACK/ABC row. Right cluster: 4 color buttons (Red/Yellow/Blue/Green) with 3 user-selectable layouts (GRID/HORIZONTAL/DIAMOND) and anchor (LEFT/CENTER/RIGHT) persisted in DataStore. Joystick: `detectDragGestures`, thumb clamped to circle radius, mapped to **integer -5..+5** per axis (Y inverted), reset to 0 on dragEnd/cancel. Note: single `pointerInput` per button — plausible multi-touch across separate composables, but no explicit multi-touch/slide-off handling.
- **Physical gamepads**: `dispatchKeyEvent`/`dispatchGenericMotionEvent` overridden in the Activity; source checked for GAMEPAD/JOYSTICK/DPAD; `InputManager.InputDeviceListener` tracks connect/disconnect; option to auto-hide virtual controller when external pad present. Analog: `AXIS_X/AXIS_Y` with `MotionRange.flat` deadzone + 0.2 threshold, scaled ×5 → -5..5; falls back to `AXIS_HAT_X/Y` (>±0.5 → ±5). DPAD keys map to ±5. Full button remapping UI (`ControllerAction` enum → keyCode map persisted in preferences).
- Virtual and hardware inputs are kept as two `ControllerInput` states and **OR-combined** (`combineInputs`) before each `sendInput`; input is sent on change, not per frame.

### ROM loading / storage
- **SAF end-to-end**: first-run Setup Wizard fires `Intent.ACTION_OPEN_DOCUMENT_TREE` (with `FLAG_GRANT_PERSISTABLE_URI_PERMISSION`), takes persistable read+write permission, and creates `bios/`, `roms/`, `saves/`, `screenshots/` subfolders via `DocumentFile` (`util/StorageHelper.kt`).
- ROM scan: `DocumentFile.listFiles()` on `roms/`, extensions `.bin .rom .v.smile .dat` (case-insensitive). BIOS: searched in `bios/` by name (`vsmile_bios.bin`, `bios.bin`, `system.bin`, `vsmile.bin`, `vsmile_bios.rom`, `bios.rom`), else first file with a ROM extension. BIOS optional (dummy BIOS fallback).
- Files are read **whole into a `ByteArray`** via `contentResolver.openInputStream(uri).readBytes()` on `Dispatchers.IO`, then copied again across JNI (so an 8 MiB cart exists ~3×: Kotlin array, JNI copy, core array). No mmap, no fd passing to native.
- ROM handoff between activities: `Intent.putExtra("ROM_URI", uri.toString())` + `"ROM_NAME"`.
- Legacy `READ/WRITE_EXTERNAL_STORAGE` declared with `maxSdkVersion="32"`; FileProvider declared for screenshot sharing (screenshots not actually implemented).

### Build system summary
- Gradle Kotlin DSL. AGP **8.3.2**, Kotlin **1.9.23**, Gradle wrapper **8.13**, `compileSdk 34`, `targetSdk 34`, **`minSdk 26`**, Java 17, Compose BOM 2024.02.00 (Material3, nav-compose 2.7.7, DataStore-preferences 1.0.0, documentfile 1.0.1, coil 2.5.0, coroutines 1.7.3).
- `externalNativeBuild.cmake`: `cppFlags = -std=c++20 -frtti -fexceptions -O3 -ffast-math`; arguments `-DANDROID_STL=c++_shared -DANDROID_PLATFORM=android-26`.
- **ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`**.
- Release build: minify/shrink **disabled** ("to avoid JDK compatibility issues"), signed with debug key. `gradle.properties` hardcodes `org.gradle.java.home=F:\Android Studio\jbr` (fragile, machine-specific).
- Manifest: `MainActivity` (launcher + `ACTION_VIEW` intent filter for `file:`/`content:` with `pathPattern=".*\\.bin"`, though MainActivity never actually reads that intent's data — ROMs launched via VIEW won't load); `EmulationActivity` `screenOrientation="landscape"`, `configChanges="orientation|screenSize|keyboardHidden"`, fullscreen theme, `exported=false`.
- Extras: `CrashHandler` persists uncaught-exception logs to `filesDir/last_crash.txt` and shows a copyable dialog on next launch; `FileLogger` writes a per-session debug log — heavy tracing everywhere (another AI-codegen tell, and a perf drag).

### Performance tricks/problems (no issues filed; from code + README)
- Works: -O3/-ffast-math on the core (veesem README explicitly warns unoptimized builds "lag frequently"); reused Bitmap + direct ByteBuffer; FilterQuality.None; audio drop-on-full instead of blocking the emu thread; input sent only on change.
- Problems an emulator author should avoid: per-frame `NewByteArray`/`NewShortArray` JNI allocations (GC churn); per-frame `__android_log_print` in `nativeGetFrameBuffer`; per-pixel Kotlin color conversion on the emu thread ("the performance bottleneck" per its own comment); `delay()` ms pacing without vsync; Compose Image recomposition as the render path; resampling in Kotlin with boxed `List<ShortArray>` chunks; PAL hardcoded; `min*4` AudioTrack buffer → ~80-100+ ms audio latency typical.

## 3. Hardware facts extractable from the vendored veesem core (bonus reference for D-Smile)
All from `app/src/main/cpp/veesem/src/core/` (identical to upstream sp1187/veesem):
- **Master clock 27 MHz** (all `SimpleClock` constants are in 27 MHz CPU cycles). `Spg200::RunFrame()` loop: `cpu_.Step()` returns cycles, then `io/adc/uart/timer/spu` all `RunCycles(cycles)`, and `ppu_.RunCycles(cycles)` returning true ends the frame.
- **Video** (`ppu.cc`): scanline clock = `(429 NTSC / 432 PAL) × 4` cycles/scanline (1716/1728); **262 scanlines NTSC, 312 PAL**; 240 visible lines drawn, 320 px wide; framebuffer = `std::array<std::array<Color,320>,240>`, Color is 16-bit **RGB555 + bit15 transparency flag**. PPU regs at word addresses 0x2810-0x2872 (per-BG: xscroll/yscroll/attribute/control/tilemap-ptr/attrmap-ptr at 0x2810-0x281B stride 6; vertical compress 0x281C/D; segment ptrs 0x2820/21; sprite segment 0x2822; blend 0x282A; fade 0x2830; IRQ vpos/hpos 0x2836/37; sprite control 0x2842; STN LCD 0x2854; IRQ ctrl/status 0x2862/63; sprite DMA src/tgt/len 0x2870-72), line scroll 0x2900-0x29FF, line compress 0x2A00-0x2AFF, **palette 0x2B00-0x2BFF (256 colors)**, sprite RAM 0x2C00-0x2FFF.
- **RAM**: 0x0000-0x27FF (10240 words).
- **SPU**: 16 channels, regs 0x3000-0x30FF (per-channel ×16: wave addr lo, mode, loop addr lo, pan, envelope0, envelope data, envelope1, env addr hi/lo, wave data0, env loop ctrl, wave data), phase regs 0x3200-0x32FF, control 0x3400+ (channel enable, main volume, FIQ enable/status, beat base/count, env clocks...). **Sample generated every 96 cycles → 281,250 Hz stereo**; output samples are **unsigned 16-bit**; internal ring buffer 6144×2. Mixing: `sample = ((prev_part + cur_part) - 0x8000)`, envelope `>>7`, pan/volume `>>14`.
- **CPU (unSP)**: IRQ vector fetch `regs_[REG_PC] = bus_.ReadWord(0xfff8 + irq_number)`; FIQ/IRQ nesting flags; `std::countr_zero` priority.
- **UART baud**: bit-time counter = `16 × (65536 - ((baud_hi<<8)|baud_lo)) × (mode ? 11 : 10)` cycles per byte.
- **Controller protocol** (`vsmile_joy.cc` — complete byte-level protocol):
  - Timers: idle keep-alive **0x55 sent every 1 s** (27,000,000 cycles); RTS timeout **0.5 s** (13,500,000) after which controller resets its TX queue and re-sends 0x55; TX start delay **3.6 ms** (97,200 cycles) after CTS asserted.
  - Controller→console bytes: **buttons 0xA0** (none) / **0xA1** enter / **0xA2** back / **0xA3** help / **0xA4** abc (priority order, one at a time); **colors 0x90 | green(1)|blue(2)|yellow(4)|red(8)**; **joystick X: 0xC0 neutral, positive(right) 0xC3+|x|-1 → 0xC3-0xC7, negative 0xCB+|x|-1 → 0xCB-0xCF; Y: 0x80 neutral, positive(up) 0x83-0x87, negative 0x8B-0x8F** (magnitude 1-5). Updates sent only on change.
  - Console→controller bytes (`Rx`): **0x6x = LED command** (same green/blue/yellow/red bit order); **0x70/0xB0 nibble probe handshake**: controller keeps 2-entry probe history; on `0x7n` history[0]=0, on `0xBn` history[0]=old history[1]; history[1]=n; replies **`0xB0 | (((-h0 + -h1) ^ 0xA) & 0xF)`**.
  - Flow control: CTS (console→controller) / RTS (controller→console) over GPIO; a **falling RTS edge raises EXT1 IRQ** (controller 1) / EXT2 (controller 2). Controller has a 16-byte TX ring buffer. Console UART TX reaches the controller only while CTS0 is asserted.
- **GPIO wiring** (`vsmile.cc` Io): **Port B** (active-low buttons): bit7=OFF, bit6=ON, bit3=RESTART. **Port C read**: bits0-3 = region code jumpers, bit4 = VTech-logo jumper, bit5 = always 1, bit8/9 = CTS0/CTS1 (readback), bit10/12 = RTS0/RTS1, bits13-14 set (|0x6000). **Port C write**: bit8 sets CTS0 (drives `joy_.SetCts`), bit9 CTS1.
- **Region jumper codes** (full table in vsmile.cc comment): 0x2 Italian/UK, 0x3 US English (no subtitle), 0x4 US "TV Learning System", 0x5/0x6 US/UK no-speech variants, 0x7 Chinese, 0x8 Portuguese, 0x9 "Dutch"(=US data), 0xA UK(1.03)/Italian, 0xB German, 0xC Spanish, 0xD French, **0xE UK English standard**, 0xF US English standard.
- **Cartridge/memory chip selects**: `ROMCSB` = cart[addr]; `CSB1` = cart[addr + 0x100000]; `CSB2` = cart[addr + 0x200000] (comment: some carts are dual-ROM — 4 MiB on ROMCSB+CSB1, 2 MiB on CSB2, combined dumps append CSB2 ROM) **or Art Studio 128K-word NVRAM** (CSB2 read/write `addr & 0x1ffff`); `CSB3` = 2 MiB system ROM. ADC channel 1 returns 0x3FF = "full battery"; ADC0/2/3 = 0.
- Sizes: SysRom = 1M words (2 MiB), CartRom = 4M words (8 MiB), ArtNvram = 128K words (256 KiB).

## 4. Android-specific pitfalls documented across both projects
1. **Refresh-rate-coupled emulation speed** (vdream issue #4, inherited from V.Frown): tie speed to a real clock, not display vsync — critical on 90-144 Hz Android panels.
2. **NDK macro collision**: Android headers define `REG_R0`-`REG_R7`; must `-U`/`#undef` them before including an unSP core using those names (VSmileEmu-Android CmakeLists.txt + jni_bridge.cpp).
3. **`std::source_location` needs API 30+**; use `__builtin_FILE()/__builtin_LINE()` below that (patched veesem common.h).
4. **SPU output is unsigned 16-bit**; AudioTrack/AAudio need signed — XOR 0x8000 (jni_bridge.cpp comment).
5. **SPU native rate is 281,250 Hz** — you must resample (→48 kHz); doing it in Kotlin with per-frame array allocations works but wastes CPU; do it natively.
6. Audio underrun ("crackle") acknowledged in-app; their mitigation was a 4-deep drop-on-overflow queue + minBuf×4 — trades latency for stability.
7. JNI per-frame array marshalling + per-frame logcat writes are the port's biggest self-inflicted costs; prefer a persistent direct ByteBuffer / ANativeWindow / GL texture upload.
8. SAF: whole-file `readBytes()` of ≤8 MiB ROMs via `DocumentFile`/`contentResolver` at startup is fine in practice (one-time cost); no streaming needed since carts are ROM-only. `DocumentFile.findFile/listFiles` are slow per-call but only used at scan time.
9. Unoptimized (non `-O3`) builds of an interpretive unSP core lag noticeably (veesem README warning; port compiles core -O3 -ffast-math even for debug).
10. Compose-Bitmap rendering is viable at 320×240@50-60fps on flagships but is the acknowledged bottleneck; the mutated-shared-Bitmap + StateFlow-wrapper hack shows why a SurfaceView/GL path is preferable.
11. PAL/NTSC: V.Smile is 50 Hz PAL / 60 Hz NTSC (262/312 scanlines, 429/432×4 cycles per line); the port hardcodes PAL with 20 ms frames — plan proper region selection.
12. Power button emulation: the console must receive an ON press (~100 ms) after reset before it boots.

