<div align="center">

<img src=".github/assets/icon.png" width="132" alt="D.Smile icon" />

# D.Smile

**VTech V.Smile emulator for Android.**

<img src="https://img.shields.io/badge/platform-Android%208.0%2B-3ddc84?style=flat-square" alt="Android 8.0+" />
<img src="https://img.shields.io/badge/renderer-OpenGL%20ES-blue?style=flat-square" alt="OpenGL ES" />
<img src="https://img.shields.io/badge/controllers-touch%20%2B%20gamepad-orange?style=flat-square" alt="touch and gamepad" />

<br/><br/>

<img src=".github/assets/hero.png" width="900" alt="D.Smile running the V.Smile boot screen with the CRT shader, silver bezel, purple background and on-screen controller" />

</div>

## Features

**Play**
- Runs V.Smile cartridge dumps straight away, no BIOS required.
- Optional system bios import for maximum compatibility. Not necessary for most titles.
- Save states with thumbnails and timestamps, and per game slot memory.
- Rewind and fast forward with adjustable speed. 

**Look**
- CRT shader with barrel curvature, glow, scanlines, aperture grille and vignette, each with effect its own intensity slider.
- Pixel, sharp, and CRT display modes. For aspect ratio you have 4:3, stretch, and integer scaling.
- Themed letterbox backgrounds (black, wavy blue, or V.Smile purple) and silver or black TV bezels that wrap the picture.
- Two renderer options. One that should be fast and perfect for most games, but the second is intended to be a more accurate option for edge cases.

**Control**
- An on screen controller modelled on the real thing, with classic orange and pink themes.
- A full layout editor: drag to move, box select to move groups, resize with a multiplier, save and name your own layouts.
- Gamepad support with remappable buttons and bindable hotkeys for save, load, fast forward and rewind.

**Fit in**
- Launches directly into a game from front ends like iiSU, Daijisho and ES-DE.
- Lots of QOL features and bug fixes baked-in that a lot of niche emulators fall short on. 

## Screenshots

<div align="center">

| CRT shader, classic controller | Crisp pixel mode, pink controller |
| :---: | :---: |
| <img src=".github/assets/screen-crt.jpg" width="420" /> | <img src=".github/assets/screen-pixel.jpg" width="420" /> |

</div>

## Getting started

1. Grab the latest `D.Smile-x.y.z.apk` from [Releases](../../releases) and install it (allow installs from your browser if prompted).
2. Open D.Smile, tap **ROM folder**, and pick the folder with your `.bin` dumps.
3. Tap a game. The menu button in the top corner opens save states, video options, the layout editor and more.

A BIOS is **not required**. Games boot and play fine without one. Importing a V.Smile system ROM (tap **BIOS**) is purely optional and only adds a bit of extra compatibility that can help a handful of games. Skip it unless you run into a title that misbehaves.

## Launching from a front end

D.Smile exposes an activity that front ends can launch straight into a game. See [docs/iisu-integration.md](docs/iisu-integration.md) for the iiSU config block and the general intent contract that also covers Daijisho and ES-DE.

## Acknowledgements

This project wouldn't exist without the V.Smile reverse engineering work that came before it:

- **[veesem](https://github.com/sp1187/veesem)** by sp1187 (ISC license) — the
  primary behavioral reference for this emulator. D.Smile's core was verified
  against veesem instruction-by-instruction during development, and its
  hardware model (unSP CPU, PPU, SPU, controller handshake) taught this
  project most of what it knows.
- **[MAME](https://github.com/mamedev/mame)** — the SPG2xx and V.Smile drivers
  (Ryan Holtz, Vas Crabb, and contributors) documented the hardware registers,
  timings and controller protocol that both veesem and D.Smile build on. The
  accurate-render fade/saturation formulas follow MAME's implementation.
- **[V.Frown](https://github.com/Schnert0/VFrown)** by Schnert0 — a further
  reference for controller timing and hardware behavior.
- **[Oboe](https://github.com/google/oboe)** by Google (Apache-2.0) — the
  low-latency Android audio library D.Smile ships with.
- The V.Smile research and homebrew community, whose compatibility testing and
  hardware documentation made a project like this possible.

Where D.Smile's behavior diverges from these projects (controller reporting
modes, held-input handling, sprite DMA edge cases), the changes are original
findings — documented in the commit history so they can flow back upstream.

## License

Personal project. Code written for D.Smile, informed by the references above.
Not affiliated with or endorsed by VTech.
