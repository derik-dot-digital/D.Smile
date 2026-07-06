# VFrown (Schnert0/VFrown) Deep-Read Report — reference for D-Smile

Repo cloned at commit `d17874ace5350bc9ace8f902c99cad5cfd4b56ab` (2024-01-22, branch `main`). Language: C11, ~7k LOC core. Frontend: sokol_app/sokol_gfx/sokol_audio + nuklear UI. All core state lives in per-module `static <Module>_t this;` singletons.

File inventory (core): `src/core/vsmile.c/h` (top-level glue + main loop), `src/core/bus.c/h` (memory map), `src/core/cpu.c/h` (unSP), `src/core/ppu.c/h`, `src/core/spu.c/h`, `src/core/timer.c/h` (generic countdown timer object), `src/core/hw/{gpio,timers,misc,uart,dma,controller}.c/h`, `src/common.h` (constants), `src/main.c`, `src/backend/*`.

---

## 1. Architecture, main loop, timing

Constants (`src/common.h`):
- `CYCLES_PER_LINE 1716`
- `LINES_PER_FIELD 262`
- `SYSCLOCK 27000000` — with comment `// Is this correct?` and a commented-out alternative `(CYCLES_PER_LINE * LINES_PER_FIELD * 60)` = 26,975,520. So VFrown itself is unsure whether the master clock is exactly 27 MHz.
- `OUTPUT_FREQUENCY 48000` (audio sample rate)
- `RGB5A1_TO_RGBA8(color)` = `(((color & 0x1f) << 19) | ((color & 0x3e0) << 6) | ((color & 0x7c00) >> 7) | 0xff000000)` — 15-bit BGR->RGBA8 conversion.

Main loop (`VSmile_RunFrame`, vsmile.c): scanline-driven.
```
cyclesPerLine = CYCLES_PER_LINE * clockScale;
loop {
  cyclesLeft += cyclesPerLine;
  while (cyclesLeft > 0) { cycles = CPU_Tick(); SPU_Tick(cycles); cyclesLeft -= cycles; }
  Bus_Update(cyclesPerLine - cyclesLeft);      // Timers, Misc(PRNG/ADC/watchdog), UART
  Controller_Tick(cyclesPerLine - cyclesLeft); // controller timers
  if (PPU_RenderLine()) break;                 // returns true when line wraps past 261
}
```
Comment in source: peripherals are ticked **once per scanline** rather than per instruction: "Even though it's slightly less accurate, it's waaaay more efficient this way." SPU is the exception (ticked per instruction). The host frame callback (`main.c frameFunc`) accumulates `Backend_GetSpeed()` (default 1.0) and calls `VSmile_RunFrame()` accordingly (allows fast-forward/slowdown). CPU crash → `VSmile_Error` sets `isHalted` and stops the machine (shows a message box), no exit.

Reset order: `Bus_Reset(); CPU_Reset(); PPU_Reset();` (SPU_Reset exists but is NOT called from VSmile_Reset).

## 2. unSP CPU (`src/core/cpu.c`, 1152 lines)

### Registers/state
`r[8]` union = `sp, r1, r2, r3, r4, bp, sr, pc`. SR bitfield (`SR_t`, low→high): `cs:6, c:1, s:1, n:1, z:1, ds:6`. **NOTE: this puts N at bit 8 and Z at bit 9 — the opposite of MAME's unSP (MAME: Z=0x0100/bit8, N=0x0200/bit9). Only observable via PUSH SR / POP SR / RETI round-trips, but it is a real disagreement with other emulators.**
Extra state: `sb` (4-bit shift buffer) plus `sbBanked[3]` (banks for normal/IRQ/FIQ), `irqEnabled, fiqEnabled, irqActive, fiqActive, irqPending, fiq (pending FIQ flag), fiqSource, firMov`.

### Instruction decode
`Ins_t` union over the 16-bit opcode: `opB:3 (bits0-2), opN:3 (bits3-5), op1:3 (bits6-8), opA:3 (bits9-11), op0:4 (bits12-15)`; `imm:6` overlays bits 0-5.

Dispatch: `type = ((op1 == 2) << 1) | (opA == 7 && op1 < 2)` → 0/3 = ALU, 1 = branch, 2 = push/pop. Function tables:
- ALU table by op0: `ADD ADC SUB SBC CMP BAD NEG BAD XOR LD OR AND TST STR BAD SPC` (op0=0xF = special).
- Branch table by op0: `JCC JCS JSC JSS JNE JE JPL JMI JBE JA JLE JG JVC JVS JMP SPC`.
- Push/pop table: only op0=9 → POP, op0=0xD → PSH, op0=0xF → SPC, everything else BAD.
- Special (op0=0xF) by op1: `MULU CALL JMPF BAD MULS MISC BAD BAD`.

Addressing modes (`op1` index): `0=[BP+imm6], 1=imm6, 2=invalid, 3=[Rb] indirect family, 4=extended, 5=LSL/LSR, 6=ROL/ROR, 7=[imm6]`. Extended (op1=4, `opN` index): `0=Rb, 1=imm16, 2=[imm16], 3=[imm16]=Ra (reverse/store-direction), 4..7=Rb ASR n`.

Indirect (op1=3) `opN` variants: `0=[Rb], 1=[Rb--], 2=[Rb++], 3=[++Rb], 4=[DS:Rb], 5=[DS:Rb--], 6=[DS:Rb++], 7=[DS:++Rb]`; DS variants form 22-bit address `(sr.ds<<16)|Rb` and inc/dec carries into DS (via CPU_IncDS). For STR-class ops (op0==0xd), the memory load of opr2 is skipped.

Shift semantics: LSL/LSR use 4-bit `sb` as extension: LSR: `shift = ((Rb<<4)|sb) >> (opN-3); sb = shift & 0xf; opr2 = (shift>>4)&0xffff`. LSL: `shift = ((sb<<16)|Rb) << (opN+1); sb=(shift>>16)&0xf; opr2=shift&0xffff`. ROR/ROL: `shift = (((sb<<16)|Rb)<<4)|sb` then shifted, sb from bits. ASR sign-extends bit19 through bits 20-23 before shifting.

Write-back rule: every ALU op checks `if (op1 == 0x4 && opN == 0x3)` → store result to `[imm16]` (`aluAddr`) instead of `Ra`. CMP/TST store nothing; STR stores `opr1` (Ra) to `aluAddr`.

Flags: ADD/ADC/SUB/SBC/CMP update N,Z,S,C; NEG/XOR/LD/OR/AND/TST update N,Z only; **flags are skipped entirely when opA == 7 (PC as destination)**. Carry: `result > 0xffff`. Sign/overflow S: `((result>>16)&1) != (((opr1 ^ opr2)>>15)&1)` (for SUB family, `~opr2`). SUB implemented as `opr1 + (~opr2 & 0xffff) + 1`, SBC as `... + c`.

Branch conditions (all relative ±imm6; `CPU_Branch`: `op1` bit set = subtract imm, clear = add): JCC !c, JCS c, JSC !s, JSS s, JNE !z, JE z, JPL !n, JMI n, JBE `(!z && !c) || z` (i.e. !c||z), JA `!z && c`, JLE `z || s`, JG `!(z||s)`, JVC `n==s`, JVS `n!=s`, JMP always.

Specials:
- **MULU** (`opN==1 && opA==7` illegal): `result = a*b; if (b & 0x8000) result -= a<<16;` → unsigned×signed. **MULS**: additionally `if (a & 0x8000) result -= b<<16` → signed×signed. Results to r4:r3 (hi:lo).
- **CALL**: fetch imm16 low PC; push PC then SR onto SP stack; `CS:PC = (ins.imm<<16)|lowPC`. Illegal if opA odd.
- **JMPF (goto)**: `CS:PC = (imm<<16)|fetch16`; requires opA==7.
- **MISC (op1=5)** by imm6: `0x00 INT OFF, 0x01 INT IRQ, 0x03 INT FIQ,IRQ, 0x04 FIR_MOV ON, 0x05 FIR_MOV OFF, 0x08 IRQ OFF, 0x09 IRQ ON, 0x0c FIQ OFF, 0x0e FIQ ON, 0x25 NOP`. Anything else (BREAK, CALLR, DIVS, EXP, etc.) = fatal "unimplemented". Note `0x02 INT FIQ` (FIQ only) is NOT handled — hits the error path.
- **PUSH/POP**: `PSH`: push r[opA], r[opA-1], ... n=opN words onto stack r[opB]. `POP`: pop into r[opA+1...]. **RETI is detected by exact opcode `raw == 0x9a98`**: pops SR then PC, restores banked SB (fiqActive → sbBanked[2] swap; else irqActive → sbBanked[1]/[0] swap), clears active flag, sets `irqPending = true`.
- Stack push decrements after store (`Bus_Store(r[idx], data); r[idx]--`), pop increments before load.

### Cycle counts (VFrown's numbers)
No base cost for ALU ops; the addressing-mode routine supplies the entire cost:
- `[BP+imm6]` 6; `imm6` 2; indirect family 6 (7 if opA==7); LSL/LSR 3 (5 if opA==7); ROL/ROR 5 (6); `[imm6]` 5 (6); ext `Rb` 3 (5); ext `imm16` 4 (5); ext `[imm16]` and `[imm16]=` 7 (8); ASR 3 (5).
- Branches: 2 not taken, 4 taken (2 + 2 in CPU_Branch).
- MULU/MULS +12; CALL +9; JMPF +5; MISC +2; PSH/POP `2*opN + 4`; RETI 8.
- Safety net: if an instruction reports <1 cycles, forces 1 and warns.

### Interrupts
- Reset: `pc = Bus_Load(0xfff7)`, everything else zeroed, fiqSource = FIQSRC_NONE(7).
- Vectors: FIQ = `[0xfff6]`, IRQ n = `[0xfff8 + n]` (n = 0..7). BREAK vector unimplemented.
- `CPU_TestIRQ()` runs after each instruction when `irqPending`. It computes: `maskedIRQ = [0x3d21] & [0x3d22]`, `ppuMaskedIRQ = [0x2862] & [0x2863]`, `spuIRQ = SPU beat/env flag`, `spuChannelIRQ`. Early-out clears `irqPending` when all zero.
- Priority order implemented: pending FIQ first, then **IRQ0 = PPU (any of 2862&2863), IRQ1 = SPU channel, IRQ2 = maskedIRQ & 0x0c00 (Timer A 0x0800 / Timer B 0x0400), IRQ3 = maskedIRQ & 0x6100 (UART 0x0100, ADC 0x2000, SPI 0x4000), IRQ4 = SPU beat/envelope, IRQ5 = maskedIRQ & 0x1200 (controller-1 RTS 0x0200, controller-2 RTS 0x1000), IRQ6 = maskedIRQ & 0x0070 (1024/2048/4096 Hz), IRQ7 = maskedIRQ & 0x008b (TMB1 0x0001, TMB2 0x0002, 4 Hz 0x0008, key-change 0x0080)**.
- `CPU_DoIRQ(n)`: guards on `irqActive||fiqActive||!irqEnabled`; banks SB (`sbBanked[0]=sb`), pushes PC then SR to SP stack, `sb = sbBanked[1]`, `pc = [0xfff8+n]`, `sr.raw = 0` (clears CS/DS!).
- `CPU_DoFIQ()`: same pattern using sbBanked[2], vector 0xfff6.
- FIQ source: `CPU_TriggerFIQ(src)` only sets `fiq` if `src == fiqSource`. **Wiring gap: Misc write to 0x3d2e stores `fiqSelect` but never calls `CPU_SetFIQSource`, and CPU_Reset sets fiqSource=NONE, so the FIQ path can never actually fire in VFrown as shipped.** SPU channel IRQs instead reach the CPU via the normal IRQ1 path.
- Devices signal via `CPU_ActivatePendingIRQs()` (just sets `irqPending = true`) — level-triggered re-evaluation from status registers, not queued events.

## 3. Memory map (`src/core/bus.c/h`)

Word-addressed 22-bit bus, `BUS_SIZE 0x400000` words:
```
0x000000-0x0027ff  RAM   (RAM_SIZE 0x2800 words = 10 KW)
0x002800-0x002fff  PPU registers/RAM (PPU_SIZE 0x800)
0x003000-0x0037ff  SPU   (SPU_SIZE 0x800)
0x003800-0x003cff  "internal memory" — warn, reads 0x0000
0x003d00-0x003d0f  GPIO  (GPIO_START/SIZE)
0x003d10-0x003d1f  Timers
0x003d20-0x003d2f  Misc (SysCtrl/INT/ADC/PRNG/etc.)
0x003d30-0x003d3f  UART
0x003e00-0x003e0f  CPU DMA (DMA_START 0x3e00, handled size 4 regs)
0x003exx-0x003fff  "internal memory" — warn
0x004000-0x3fffff  Cartridge ROM (romBuffer[addr], full 4M-word array, ROM file loaded at offset 0)
0x300000-0x3fffff  System ROM overlay when (romDecodeMode & 2) and sysrom loaded
                   (SYSROM_START 0x300000, SYSROM_SIZE 0x100000)
```
- `Bus_Store` masks `(addr & 0xffff) < 0x4000` → `addr &= 0x3fff` (mirrors the low 16K of every 64K segment onto internal space for writes). Writes to ROM areas are silently ignored.
- `Bus_Load` of unmapped IO warns and returns 0.
- ROM loading: file read raw little-endian into `uint16_t romBuffer[0x400000]` (max 8 MB); sysrom likewise into its own buffer. No byte-swapping logic (assumes LE host and LE ROM dumps).
- `romDecodeMode` = bits 7:6 of 0x3d23 (ext memory ctrl); `ramDecodeMode` = bits 11:8; `chipSelectMode` = 3 bits driven by GPIO port B low bits (CS1/CS2 banking for >4MB carts — stored but only echoed back through IOB reads, not used to bank ROM).
- **No cartridge NVRAM/EEPROM emulation**: `uint16_t nvram[0x200000]` exists only as a commented-out field in Bus_t. Games that save (e.g. via I2C/CS lines) are unsupported.

## 4. PPU (`src/core/ppu.c`, 521 lines)

Register map (word addresses; write masks shown):
```
0x2810 layer0 xPos (&0x1ff)      0x2816 layer1 xPos (&0x1ff)
0x2811 layer0 yPos (&0xff)       0x2817 layer1 yPos (&0xff)
0x2812 layer0 attr               0x2818 layer1 attr
0x2813 layer0 ctrl               0x2819 layer1 ctrl
0x2814 layer0 tilemapAddr        0x281a layer1 tilemapAddr
0x2815 layer0 attribAddr         0x281b layer1 attribAddr
0x281c vertScale (&0xff)  [stored only, no effect]
0x281d vertMovement (&0x1ff) [stored only]
0x2820 segmentPtr[0] (layer0 tile-data base >> 6)
0x2821 segmentPtr[1]
0x2822 spriteSegment
0x282a blendLevel (&3)    [stored only — blending NOT rendered]
0x2830 fadeLevel (&0xff)  [stored only — fade NOT rendered]
0x2836 vCompare (&0x1ff)  0x2837 hCompare (&0x1ff) [hCompare unused]
0x2838 currLine (read = current scanline; write ignored via default warn)
0x283c hueSatAdjust (&0xff) [stored only]
0x283d LFPInterlace (&0x5) [stored only]
0x283e lightpenX (&0x1ff), 0x283f lightpenY (&0x1ff) [stored only]
0x2842 spriteEnable (&1)
0x2854 lcdCtrl (&0x3f) [stored only]
0x2862 irqCtrl (enable mask)   0x2863 irqStat (write-1-to-clear: irqStat &= ~data)
0x2870 dmaSrc (&0x3fff)  0x2871 dmaDst (&0x3ff)  0x2872 dmaSize (write triggers DMA; reads back 0 after)
0x2900-0x29ff scroll[256] (row-scroll table)
0x2a00-0x2aff hScale[256] [stored only, never used in rendering]
0x2b00-0x2bff palette[256] (RGB5A1; bit15 = transparent)
0x2c00-0x2fff sprite RAM: 256 sprites × 4 words {tileID, xPos, yPos, attr}
```
PPU_Reset sets irqCtrl = irqStat = 0xffff (all pending/enabled!).

Tile attribute word (`TileAttr_t`): `bpp:2 (0=2bpp,1=4bpp,2=6bpp,3=8bpp), hFlip:1, vFlip:1, width:2, height:2 (8<<n pixels), palBank:4, depth:2, unused:2`. Tile control word (`TileCtrl_t`): `bitMap:1, regSet:1, wallPaper:1, pageEnable:1, rowScroll:1, hCompression:1, vCompression:1, directColor:1, blendEnable:1` (hCompression/vCompression/blendEnable unimplemented in rendering).

Rendering model: per scanline, `PPU_RenderLine()`:
1. If `currLine < 240`: clear 320-px scanline to 0, then for depth = 0..3 render `layer0 strip, layer1 strip, sprite strips` (so within a depth, sprites are drawn on top of layers, layer1 over layer0). Later pixels overwrite earlier (painter's algorithm; transparency = palette entry bit15 or tile index 0).
2. `if (currLine == vCompare) irqStat |= 0x0002` (vcompare/hblank IRQ).
3. `if (currLine == 240) irqStat |= 0x0001` (VBlank).
4. `currLine++`, wraps at LINES_PER_FIELD=262 → frame complete.

IRQ flag bits used in 0x2862/0x2863: `0x0001 VBlank, 0x0002 VCompare position, 0x0004 PPU DMA complete`.

Layer strip details (`PPU_RenderLayerStrip`): `y = (line + yPos) & 0xff` (256-line wrap); `nc = (bpp+1)*2` bits/pixel; `palOffset = (palBank << 4)` aligned down to a multiple of `1<<nc` (`palOffset >>= nc; <<= nc`). Row scroll: `hOffset = scroll[(line+yOffset)&0xff] * ctrl.rowScroll`. Tilemap assumed 512 px wide: `numTilesW = 512/tileWidth`; row = `tilemapAddr + numTilesW*(y/tileHeight)`; attributes packed 2 per word at `attribAddr + numTilesW/2*(y/tileHeight)`. When `regSet==0`, per-tile attribute byte supplies: bits2-3→ hFlip/vFlip (`attr.raw` bits 2-3 from `(attrData>>2)&0x0c`), bits0-3→ palBank low nibble (`(attrData<<8)&0x0f00`), bit6→ ctrl blend bit8. When `regSet==1`, flips/palette come from the layer attr register. `wallPaper` mode: repeats tile at tilemapAddr[0] across the row (t never increments, row never advances). Tile data address: `(segmentPtr[layer] << 6) + tileW*tileH*nc/16*tileIndex + rowOffset`; tile index 0 = skip (transparent).

Pixel unpack (`PPU_RenderTileStrip`): streams words from `Bus_Load(m++ & 0x3fffff)`, **byte-swaps each word (`b = (b<<8)|(b>>8)`)** then consumes `nc`-bit fields MSB-first; color = `palette[palOffset + bits]`; draws when `!(color & 0x8000)`; output `RGB5A1_TO_RGBA8`. hFlip is implemented by iterating destination x backwards.

Sprites (`PPU_RenderSpriteStrips`): all 256 sprites scanned per depth per line, tileID 0 = disabled; **coordinate transform: `xPos = 160 + x - w/2; yPos = 120 - y - h/2 + 8`** (i.e. sprite x,y are center-origin, y up, with a +8 vertical fudge — this is VFrown-specific; MAME uses different edge-based math). Sprite data base = `spriteSegment << 6`. Same nc/palOffset scheme (palOffset not bank-aligned here, just `palBank<<4`).

Bitmap mode (`PPU_RenderBitmapStrip`, when ctrl.bitMap): `tilemapAddr` = table of per-line word pointers: `tile = Bus_Load(tileMap + line)`; `attribAddr` = per-line palette-bank byte table (`palette >>= 8` on odd lines); line pixel base = `(paletteByte << 16) | tile`. If `ctrl.directColor`: 320 16-bit RGB5A1 words per line. Else: 160 words, 2×8-bit palette indices per word (low byte = left pixel).

PPU DMA (0x2872 write): `len = data & 0x3ff; if 0 → 0x400`; copies from `Bus_Load(dmaSrc + i)` into `sprData[(dmaDst+i)&0x3ff]` (sprite RAM only!), instant (no cycle cost), clears size, sets IRQ flag 0x0004.

Debug features (not hardware): per-layer enable toggles, sprite outline boxes, flip-visualization overlay.

## 5. SPU (`src/core/spu.c`, 823 lines)

16 channels. Register layout:
- `0x3000 + ch*16 + reg` (regs0[16] per channel): `0 waveAddr, 1 mode, 2 loopAddr, 3 panVol, 4 env0, 5 envData, 6 env1, 7 envAddrHigh, 8 envAddr, 9 prevWaveData, A envLoopCtrl, B waveData, C adpcmSel, D-F unknown`.
- `0x3200 + ch*16 + reg` (regs2[8]): `0 phaseHigh, 1 phaseAccumHigh, 2 targetPhaseHigh, 3 rampDownClock, 4 phase, 5 phaseAccum, 6 targetPhase, 7 phaseCtrl`.
- `0x3400 + reg` (regs4[32]): `00 chanEnable, 01 volumeSelect, 02 fiqEnable, 03 fiqStat, 04 beatBaseCount, 05 beatCount, 06 envClock0, 07 envClock0Hi, 08 envClock1, 09 envClock1Hi, 0a envRampDown, 0b chanStop, 0c zeroCross, 0d ctrl, 0e compressionCtrl, 0f chanStat, 10 leftIn, 11 rightIn, 12 leftOut, 13 rightOut, 14 chanRepeat, 15 envMode, 16 toneRelease, 17 chanEnvIrq, 18 chanPitchBend, 19 chanSoftRelease, 1a attackRelease, 1b eqCutoff10, 1c eqCutoff32, 1d eqGain10, 1e eqGain32, 1f unknown`.

Bitfields: mode = `waveHi:6, loopHi:6, playMode:2, pcmMode:2`; panVol = `vol:7, _:1, pan:7`; env0 = `increment:8, target:7, loopEnable:1`; envData = `envelopeData:7, _:1, envelopeCount:7`; env1 = `loadVal:8, repeatEnable:1, repeatCount:7`; envAddrHigh = `envAddrHi:6, irqEnable:1, irqAddr:9`; envLoopCtrl = `envAddrOffset:9, rampDownOffset:7`; adpcmSel = `pointNum:15, codec:1` (codec=1 → ADPCM36); beatCount = `count:14, irq:1, irqEn:1`; ADPCM36 header = `shift:4, filter:6`.

Special write behavior: writing waveAddr/mode resets `accum`; writing phase/phaseHigh recomputes `rate = ((phase | (phaseHigh&7)<<16) * 281250.0f) / 524288.0f` Hz and resets accum (and re-arms channel FIQ timer if playing); 0x3403 (fiqStat) and 0x340b (chanStop) are write-1-to-clear; 0x3400 (chanEnable) diffs bits and calls Start/StopChannel; 0x3405 beatCount: bit14 write-1-to-clear IRQ flag, other bits stored; if `(raw & 0xc000)==0xc000` → beat IRQ asserted.

Sample generation (`SPU_Tick`, called with per-instruction cycles): `SPU_SAMPLE_TIMER = SYSCLOCK/OUTPUT_FREQUENCY` (562). Every 562 cycles produces one stereo output sample; channel FIQ timers ticked in the same cadence. Per channel per output sample (`SPU_TickSample`): `accum += rate * clockScale; sampleTicks = floor(accum / 48000)` → consume that many source samples.

Playback modes (mode.pcmMode): `0 = 8-bit PCM` (two samples per word, low byte first, 0xFF byte = end marker → stop (playMode==1 one-shot) or jump to `loopAddr|(loopHi<<16)`), `1 = 16-bit PCM` (word per sample, 0xFFFF = end marker), `2/3 or adpcmSel.codec = ADPCM` (nybble stream; word 0xFFFF = end; IMA-style with 49×16 `adpcmLookup` table (verbatim in source, lines 76-126) and step shifts `{-1,-1,-1,-1,2,4,6,8}`, clamp sample ±2047/2048, index 0..48; result XOR 0x8000). ADPCM36 (codec bit): every 8 words a header word `{shift:4, filter:6}`; decode `sample = (nybble<<12 >> shift) + ((prev0*f0 + prev1*1 + 32) >> 12)` with f0 sign-extended from 6 bits; output XOR 0x8000. Wave address advances write back into waveAddr/mode.waveHi (6-bit hi).

Mixing (`SPU_TickChannel`): `sample = (int16)(waveData ^ 0x8000)`; if `!(ctrl & 0x0200)` applies a linear-interpolation low-pass (`lerp = rate/48000*256`; **note: it lerps waveData against itself — prevSample also reads `waveData`, likely intended `prevWaveData`; effectively a volume scale bug**). `fsample = ((sample * envelopeData) >> 7) / 8192.0f`; **hack (line 393): `if (pcmMode & 2) fsample *= 8.0f; // "Hack to make VOX samples louder"`**. Pan: pan>1/64 → left = vol, right = pan*2*vol; else left = (1-pan)*2*vol, right = vol (7-bit pan/127). Channel sum ×(1/16) each, master ×(1/32), then volumeSelect bits 7:6 of 0x3401: `0→×1/16, 1→×1/8, 2→×1, 3→×2` (**bug: case 3 multiplies leftSample twice, right never**). Clamped ±1.0, pushed to backend ring buffer; leftOut/rightOut regs get int16 copies.

Envelope: when `envRampDown & (1<<ch)`: countdown `rampDownFrame` using `rampdownFrameCounts[] = {13*4,13*16,13*64,13*256,13*1024,13*4096,13*8192,13*8192}` indexed by rampDownClock&7; each expiry subtracts `envLoopCtrl.rampDownOffset` from envelopeData, stops channel at 0. Else if `!(envMode & (1<<ch))` (auto envelope): countdown `envelopeFrame` from `envelopeFrameCounts[] = {4,8,16,32,64,128,256,512,1024,2048,4096,8192,8192,8192,8192,8192}*4` indexed by the channel's 4-bit clock from envClock0/0Hi/1/1Hi (4 channels per register, 4 bits each). `SPU_TickEnvelope`: decrement envelopeCount; at 0, move envelopeData toward env0.target by env0.increment&0x7f (bit7 = decrement; clamps; 0 → stop channel); when target reached, load next envelope segment from `envAddr|(envAddrHi<<16)`: env0 = [addr], env1 = [addr+1]; repeat handling via env1.repeatEnable/repeatCount and envLoopCtrl.envAddrOffset.

Beat/IRQ: `beatTimer` fires every 384 cycles (= SYSCLOCK / (281250/4)); decrements `currBeatBase`; at 0 reloads from `beatBaseCount` (0x3404) and decrements `beatCount.count`; count==0 & irqEn → sets beatCount.irq and asserts SPU beat IRQ (CPU IRQ4). Channel FIQ: per-channel timer with period `SYSCLOCK/rate`; on expiry, `if (!(fiqEnable & (1<<ch)))` sets fiqStat bit + channelIrq (**note the seemingly inverted enable check in the code**); channelIrq is consumed in SPU_Tick calling `CPU_TriggerFIQ(FIQSRC_SPU)` (dead, see FIQ note) and otherwise surfaces as CPU IRQ1 via `SPU_GetChannelIRQ()`.

Start/Stop channel side effects: start sets chanEnable/chanStat bits, resets ADPCM state (unless chanStop bit set); stop clears chanEnable/envRampDown/chanStat/toneRelease, sets chanStop bit, zeroes pcm state and waveData, stops timer, `mode.pcmMode = 0`.

## 6. GPIO (`src/core/hw/gpio.c`, 0x3d00-0x3d0f)

Registers: 0x3d00 config; per port (A=0x3d01, B=0x3d06, C=0x3d0b): `+0 data, +1 buffer, +2 direction, +3 attributes, +4 special/mask`. Reset: config=0x001f; port A regs all 0xffff; port B all 0x00ff; port C all 0xffff.

Read of a data register recomputes:
```
push = dir; pull = ~dir & ~attr;
what = (buffer & (push|pull)) ^ (dir & ~attr);  // attr=0 + dir=1 inverts output
what &= ~special;
what = (what & ~pull) | (GetIOx(push & ~special) & pull);
```
Writes to data regs are redirected to buffer (+1), then the same computation drives `SetIOx(what, push & ~special)`.

Port wiring:
- **Port A**: stubbed (returns 0, writes ignored).
- **Port B**: read returns `0x00c8 | chipSelectMode`; write with mask&7 sets `Bus_SetChipSelectMode(data & 7)` (cart CS lines / bank select — not used to actually bank memory).
- **Port C**: read = `region | Controller_GetRequests()` where requests = `(!rts0 << 10) | (!rts1 << 12) | ((!rts0 && !rts1) << 13)` (RTS lines active-low). Write: bit8 = controller-1 select (CTS), bit9 = controller-2 select.
- Region byte: low nibble = region code, bits 4-5 = intro flags: `0x30` = play intro, `0x20` = skip intro (`GPIO_SetIntroEnable`). Region codes (common.h, from vtech.pulkomandy.tk/doku.php?id=io): `US=0b1111, UK=0b1110, FRENCH=0b1101, SPANISH=0b1100, GERMAN=0b1011, ITALIAN=0b1010, DUTCH=0b1001, POLISH/PORTUGUESE=0b1000 (unsure), CHINESE=0b0111`.

## 7. Controller protocol (`src/core/hw/controller.c`) — byte tables VERBATIM

Controller→console bytes (queued into a 16-deep Tx FIFO, delivered through the UART at 9600 baud):
```
Joystick vertical:    up      = 0x87
                      down    = 0x8f
                      neutral = 0x80
Joystick horizontal:  left    = 0xcf
                      right   = 0xc7
                      neutral = 0xc0
Color buttons: byte = 0x90 | (red?0x08) | (yellow?0x04) | (green?0x02) | (blue?0x01)
Action buttons:       enter/OK       = 0xa1
                      help           = 0xa2
                      exit           = 0xa3
                      abc (Learning) = 0xa4
                      all released   = 0xa0
Idle/keep-alive byte  = 0x55  (sent on idle-timer expiry ~1 s, and on Tx timeout)
```
Console→controller bytes (seen in `Controller_RxComplete`):
```
0x7x or 0xbx : probe/handshake. Response = ((past0 + past1 + 0x0f) & 0x0f) ^ 0xb5
               where past0 = 0x00 if byte was 0x7x, else the previous 0xbx byte;
               past1 = the just-received byte.  (This is the controller "checksum" reply.)
0x6x         : set LED state; low nibble = LED bitmask
               (bit0=GREEN, bit1=BLUE, bit2=YELLOW, bit3=RED per common.h LED enum).
```
Timing/flow control: four timers per controller — `txTimer` (SYSCLOCK/9600 initial; **after first byte, inter-byte period is SYSCLOCK/960 while selected**), `rxTimer` (SYSCLOCK/9600 after UART transmit), `rtsTimer` (SYSCLOCK/2 = 500 ms: if console never raises select/CTS after we assert request/RTS, the Tx FIFO is flushed and a 0x55 timeout byte is queued), `idleTimer` (SYSCLOCK = 1 s keep-alive 0x55). Request (RTS) is asserted when the FIFO transitions non-empty (`Controller_SetRequest` → sets IRQ status bit 0x0200 (ctrl 1) / 0x1000 (ctrl 2) in 0x3d22 on every request *change*); select (CTS, from GPIO port C bits 8/9) gates transmission. Byte path: `Controller_TxExpired` pops FIFO → `UART_PushRx(byte)` + `UART_RxTimerReset()`. On FIFO drain, `Controller_TxComplete` re-sends any *stale* buttons (buttons that changed while FIFO was busy) using the same byte tables, marks controller `active`, resets idle timer. Button-change entry point `Controller_UpdateButtons(ctrlNum, buttons)`: if controller not yet `active` it only pulses request; sends bytes only for changed groups (directions group, colors group, action group). Both-selected edge case: `Controller_SendByte` returns OR of both Tx buffers with comment "If both selects just happen to be enabled, I assume it would send garbage that is the bitwise OR of both Tx buffers, but this is just a guess".

Input bit indices (common.h): `UP=0 DOWN=1 LEFT=2 RIGHT=3 RED=4 YELLOW=5 BLUE=6 GREEN=7 ENTER=8 HELP=9 EXIT=10 ABC=11`.

## 8. UART (`src/core/hw/uart.c`, 0x3d30+)

```
0x3d30 ctrl   (bit0 RxIRQ-en, bit1 TxIRQ-en, bit6 Rx enable, bit7 Tx enable)
0x3d31 stat   (bit0 RxReady, bit1 TxReady, bit6 TxBusy, bit7 RxFull; writing 1 clears bits0/1;
               when both cleared, writes 0x0100 to 0x3d22-clear (acknowledges UART IRQ))
0x3d32 reset  (write bit0=1 → UART_Reset)
0x3d33 baudLo, 0x3d34 baudHi   baudRate = SYSCLOCK / (16 * (0x10000 - baud16))
0x3d35 txBuffer (write starts Tx if ctrl bit7: clears TxReady, sets TxBusy, arms txTimer=baudRate)
0x3d36 rxBuffer (read pops 16-deep Rx FIFO; clears stat bits 0x81; re-arms rxTimer if more data)
```
Reset values (comment: "Maybe wrong since init captured using uart"): ctrl=0x00ef, stat=0x0003, baudLo=baudHi=0xff, tx=0xff. Tx completion (`UART_TransmitTick`): byte delivered to `Controller_RecieveByte` (broadcast to any selected controller), sets TxReady, clears TxBusy, raises IRQ 0x0100 if ctrl bit1. Rx completion (`UART_RecieveTick`): sets stat 0x81 (RxFull+RxReady), raises IRQ 0x0100 if ctrl bit0. UART timers are raw cycle countdowns in `UART_Update` (TODO comment: use Timer objects).

## 9. Misc block (0x3d20-0x3d2f) — `src/core/hw/misc.c`

```
0x3d20 sysCtrl    (reset 0x4006; stored only)
0x3d21 irqCtrl    (IRQ enable mask; write triggers CPU_ActivatePendingIRQs; reset 0x3ffb)
0x3d22 irqStat    (write-1-to-CLEAR: irqStat &= ~data; reset 0x7fff;
                   set via Misc_SetIRQFlags → irqStat |= bits + notify CPU)
0x3d23 extMemCtrl (reset 0x003e) → romDecode=(d>>6)&3, ramDecode=(d>>8)&0xf;
                   bit15 = watchdog enable (toggling re-arms 750ms = (SYSCLOCK/4)*3 cycles)
0x3d24 watchdog clear: write 0x55aa (else warn)
0x3d25 adcCtrl    (reset 0x2002) bits: 0 ADE, 1 CSB, 3:2 clock sel (conversion = 16<<n ticks),
                   5:4 channel, 8 VRT, 9 IRQ enable, 10 8kHz auto request (SYSCLOCK/8000 period),
                   12 conversion request, 13 IRQ status (write-1-to-clear semantics emulated)
0x3d26 adcPad     (bit n = disable/stop ADC channel n timer)
0x3d27 adcData    (bit15 = ready; low 12 bits value; stub values 0x03ff on all 4 channels)
0x3d28 sleepMode (0xffff), 0x3d29 wakeupSrc (0x0080), 0x3d2a wakeupTime (0x00ff) — stored only
0x3d2b tvSystem   (reset 0x0001)
0x3d2c/0x3d2d prng[0]/prng[1] (reset 0x1418/0x1658; write masks &0x7fff;
                   LFSR ticked EVERY CPU CYCLE: prng = ((prng<<1) | (bit14 ^ bit13)) & 0x7fff)
0x3d2e fiqSelect  (&7; reset 7=none; NOT wired to CPU FIQ source — see CPU section)
0x3d2f dataSegment (read/write CPU SR.DS 6 bits; misc reset sets DS=0x3f)
```
ADC conversion completion sets adcData = value|0x8000, adcCtrl |= 0x2000, raises IRQ 0x2000 if bit9; auto mode re-arms.

## 10. System timers (0x3d10-0x3d1f) — `src/core/hw/timers.c`

```
0x3d10 timebaseSetup: bits1:0 → TMB1 freq = 8<<n Hz (8/16/32/64);
                      bits3:2 → TMB2 freq = 128<<n Hz (128/256/512/1024)
0x3d11 timebase clear (write): zeroes internal 2khz/1khz/4hz dividers
0x3d12 Timer A data (count-up; write also latches reload "timerASetup")
0x3d13 Timer A ctrl: bits2:0 source A: 2=32768Hz, 3=8192Hz, 4=4096Hz, else stopped;
                     bits5:3 source B divider of source A: {/2048, /1024, /256, off, /4, /2, ×1, off}
0x3d14 Timer A enable (stored; NOT actually checked when ticking A — VFrown quirk)
0x3d15 Timer A IRQ acknowledge (write → clears bit 0x0800 in 0x3d22 via Bus_Store)
0x3d16 Timer B data (write latches reload)
0x3d17 Timer B ctrl: clock select like source A (2/3/4); write==1 re-arms
0x3d18 Timer B enable (bit0; disable stops clock)
0x3d19 Timer B IRQ acknowledge (clears 0x0400)
0x3d1c read = current PPU scanline (line counter)
```
Timer A/B are 16-bit **up counters**; on wrap to 0 they reload the latched setup value and set IRQ status 0x0800 (A) / 0x0400 (B). "sysTimers" master ticks at 4096 Hz and cascades: every tick sets 0x0040 (4096 Hz), every 2nd 0x0020 (2048), every 4th 0x0010 (1024), every 1024th 0x0008 (4 Hz). TMB1 sets 0x0001, TMB2 sets 0x0002 in 0x3d22. Reset quirk: `Timers_Reset` arms tmb[0] at SYSCLOCK/128 and tmb[1] at SYSCLOCK/8 even though `timebaseSetup=0x000f` implies TMB1=64 Hz/TMB2=1024 Hz — initial values are internally inconsistent (only until a game writes 0x3d10).

Generic timer object (`src/core/timer.c`): countdown in CPU cycles; `Tick(cycles)` subtracts and fires callback once when ≤0 (callbacks must Reset/Adjust to re-arm); `Adjust` sets both reset value and remaining.

## 11. CPU DMA (0x3e00-0x3e03) — `src/core/hw/dma.c`

```
0x3e00 srcLo, 0x3e01 srcHi (&0x3f) → 22-bit source
0x3e03 dst (16-bit — targets low 64K, i.e. RAM/PPU/SPU space)
0x3e02 size: WRITE triggers immediate transfer of (data & 0x3fff) words
             via Bus_Load(src+i) → Bus_Store(dst+i); size register then reads 0
```
No cycles consumed, no completion IRQ (unlike PPU sprite DMA which raises 0x0004).

## 12. Boot / sysrom / cart loading

- Cart ROM is loaded to bus address 0 (the whole 4M-word space); sysrom is a separate 1M-word buffer overlaid at 0x300000-0x3fffff **only when `romDecodeMode & 2`** (romDecode comes from 0x3d23 bits 7:6). Since Misc_Reset writes extMemCtrl=0x003e (romDecode=0, sysrom hidden) but Bus_Load checks `this.romDecodeMode` which is set through `Misc_SetExtMemoryCtrl` — the sysrom becomes visible when the game/BIOS sets those bits.
- Reset vector fetched from word 0xfff7 (inside cart ROM image).
- Default sysrom path `sysrom/sysrom.bin`; drag-and-drop of 2 files = ROM + sysrom. README: sysrom optional but "not all games can run properly (or at all) without" it.
- Region and intro-skip are fed to games purely through GPIO port C input bits (see §6) — `VSmile_SetRegion(0xf)` (US) and intro enabled are defaults in main.c.

## 13. Save states / NVRAM

Save states only (F-key J/K): `savestates/<title>.vfss`, implemented as raw struct dumps (Bus, CPU, PPU, SPU, VSmile, Controller, DMA, GPIO, Misc, Timers, UART in that order, each 16-byte aligned) with pointers fixed up after load. Not portable across builds. **No cartridge EEPROM/NVRAM/battery save emulation at all.**

## 14. Known hacks, guesses, and disagreements (explicit in source)

1. `common.h:27` — `#define SYSCLOCK 27000000 // Is this correct?` (alt: 1716*262*60).
2. `spu.c:393` — `fsample *= 8.0f; // Hack to make VOX samples louder` for ADPCM (pcmMode&2) channels.
3. `controller.c:91-92` — both-controllers-selected behavior is "just a guess" (OR of Tx buffers).
4. `uart.c:28` — UART reset values "Maybe wrong since init captured using uart".
5. `common.h:78` — region code 0b1000 uncertain (Polish vs Portuguese).
6. SR flag layout N=bit8/Z=bit9 — swapped vs MAME (Z=bit8, N=bit9). Disagreement with other emulators.
7. FIQ select (0x3d2e) never wired to `CPU_SetFIQSource` → FIQs never fire; SPU channel interrupts delivered as IRQ1 instead.
8. Timer A "enable" register (0x3d14) stored but never gated.
9. SPU interpolation lerps `waveData` against itself (probable bug; `prevWaveData` unused for this).
10. volumeSelect case 3 doubles the left sample twice and never the right (typo bug).
11. `Controller_SetRequest` raises the RTS IRQ flag on *any* change (both assert and deassert edges).
12. PPU: fade (0x2830), blend level (0x282a)/blendEnable, hue/sat (0x283c), hScale table (0x2a00), vertScale/vertMovement (0x281c/1d), lcdCtrl (0x2854), hCompare, lightpen — all stored but have no rendering effect. Sprite blending, 6bpp edge cases beyond the generic path, interlace: unimplemented.
13. PPU reset leaves irqCtrl/irqStat = 0xffff (all IRQs enabled+pending) — questionable but what the code does.
14. `CPU_Reset` has `// this.sr.ds = 0x3f;` commented out; DS=0x3f is instead set by `Misc_Reset` via `CPU_SetDataSegment(0x003f)`.
15. RETI matched by literal opcode 0x9a98 rather than by field decode (a commented-out field-based check exists).
16. MISC 0x02 (`INT FIQ` only) missing from the switch → fatal error if executed.
17. No per-scanline mid-frame register capture: layers/sprites are rendered with whatever registers hold at the time the line is rendered, but line rendering happens after the CPU has run that line's cycles.
18. No game-specific per-title workarounds exist anywhere in the code (no title/checksum sniffing) — all hacks are global.

## 15. Cross-reference notes for D-Smile

- The controller byte tables and the `((p0+p1+0x0f)&0x0f)^0xb5` handshake response match the documentation on vtech.pulkomandy.tk (V.Smile io page), which VFrown cites for region codes.
- VFrown's PPU is a simplification of the MAME `spg2xx` video model (no blending, no hcompression, painter's-algorithm depth loop, centered sprite coordinates with +8 y fudge). Its per-line 4-depth loop with sprites-after-layers per depth is the ordering an implementer should replicate to match its output.
- VFrown's SPU envelope frame counts, rampdown counts, ADPCM table, and 281250/524288 phase→Hz conversion align with the SPG2xx datasheet-derived values used by MAME's spg2xx audio.

