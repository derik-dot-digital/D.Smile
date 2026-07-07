# D.Smile

A from-scratch VTech V.Smile emulator for Android.

- **Core**: C++20 interpreter for the SunPlus SPG200 SoC (unSP CPU, PPU, 16-channel
  SPU, GPIO/UART, timers, DMA) with the V.Smile controller serial protocol.
- **Boot**: cartridges boot directly (no BIOS needed). If a BIOS/sysrom dump named
  `*bios*` or `*sysrom*` sits in your ROM folder, the real V.Smile boot intro plays.
- **Features**: save states (3 slots), rewind (~30 s), fast forward, on-screen
  controls (toggleable, opacity slider, controller-LED feedback), full gamepad
  remapping including hotkeys, CRT/sharp/pixel shaders, 4:3/stretch/integer aspect.
- **Frontends**: exported activity for iiSU / Daijisho / ES-DE — see
  [docs/iisu-integration.md](docs/iisu-integration.md).

## Building

Everything (JDK, Android SDK/NDK, Gradle) lives in `toolchain/` (not checked in).

```
$env:JAVA_HOME = "<repo>\toolchain\jdk-17.0.19+10"
$env:ANDROID_HOME = "<repo>\toolchain\sdk"
toolchain\gradle-8.11.1\bin\gradle.bat assembleRelease
```

APK lands in `app/build/outputs/apk/release/`.

### Core smoke test (desktop)

```
toolchain\w64devkit\bin\g++ -std=c++20 -O2 -o test\out\host_test.exe test\host_test.cpp app\src\main\cpp\core\*.cpp
test\out\host_test.exe "<rom>.bin" 1800
```

Boots the ROM headless, dumps framebuffer BMPs, and verifies save-state determinism.

## Accuracy references

Behavior was researched from [veesem](https://github.com/sp1187/veesem) (ISC),
[VFrown](https://github.com/Schnert0/VFrown), MAME's unSP/SPG2xx cores, and
[vdream](https://github.com/fodsoft/vdream) — see `docs/research/`. All D.Smile
code is original; the SPG200 register-level behavior follows those references,
with veesem serving as the primary behavioral model.
