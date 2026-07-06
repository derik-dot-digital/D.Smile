# veesem (sp1187/veesem) Deep-Read Report — Reference for D-Smile (V.Smile emulator)

All information below was extracted directly from the veesem source at commit `f31b3249ec99fae0abc740f3d9ad3bf43e5b9000` (HEAD as of 2026-07-06). File paths refer to the repo layout. veesem models the SPG200 SoC + a `VSmile` machine wrapper.

---

## 1. Overall architecture

### File layout (relevant code only)
```
src/main.cc                     - CLI arg parsing -> RunEmulation()
src/ui/ui.cc, ui.h              - SDL2+ImGui frontend, ROM loading, frame pacing, audio
src/ui/graphics_state.cc/.h     - GL texture upload of framebuffer
src/core/common.h               - Word/Addr types, Bitfield<pos,size>, clock helpers, sext/rotl/rotr
src/core/spg200/
  spg200.cc/.h                  - SoC top: bus decode (ReadWord/WriteWord), RunFrame loop, system ctrl reg
  spg200_io.h                   - Spg200Io abstract interface (ports, ADC, extmem chip selects, UART wires)
  bus_interface.h               - BusInterface {ReadWord, WriteWord}
  cpu.cc/.h                     - unSP CPU core
  ppu.cc/.h                     - PPU (2 BG layers + 256 sprites, scanline renderer)
  spu.cc/.h                     - 16-channel SPU
  adpcm.cc/.h                   - IMA-style ADPCM decoder
  irq.cc/.h                     - IRQ controller (PPU/SPU direct lines + "IO" IRQ ctrl/status regs, FIQ select)
  timer.cc/.h                   - Timebase + Timer A/B
  gpio.cc/.h                    - 3 GPIO ports (A/B/C) with buffer/dir/attrib/mask
  uart.cc/.h                    - UART (controller link)
  adc.cc/.h                     - 4-channel ADC
  extmem.cc/.h                  - External memory controller (ROMCSB/CSB1/CSB2/CSB3 decode)
  dma.cc/.h                     - CPU DMA (ext -> internal RAM)
  random.cc/.h                  - 2 PRNG registers
  watchdog.cc/.h                - Watchdog
  settings.h                    - PpuViewSettings (debug layer toggles)
  types.h                       - enum class VideoTiming { PAL, NTSC }
src/core/vsmile/
  vsmile.cc/.h                  - Machine: ROM wiring, GPIO port meanings, controller hookup, ON/OFF/RESTART
  vsmile_joy.cc/.h              - V.Smile controller state machine (serial protocol)
  vsmile_common.h               - VSmileJoySend interface {SetRts, Tx}
```

### Component wiring
`Spg200` **is** the `BusInterface`; the CPU, PPU, SPU and DMA all issue reads/writes through `Spg200::ReadWord/WriteWord` (so PPU tile fetches and SPU sample fetches see the same address decoding as the CPU, including cart ROM). `Spg200` owns Cpu, Ppu, Spu, Irq, Timer, Extmem, Gpio, Adc, Uart, Dma, 2×Random, Watchdog. Machine-specific behavior is injected via the `Spg200Io` interface implemented by `VSmile::Io` (GPIO port values, ADC values, chip-select memory, UART TX/RX plumbing to controller).

### Main loop / timing model (spg200.cc `RunFrame`, master clock = 27 MHz)
```cpp
void Spg200::RunFrame() {
  int cycles_in_frame = 0;
  for (;;) {
    int cycles = cpu_.Step();          // returns instruction cycle count
    cycles_in_frame += cycles;
    io_.RunCycles(cycles);             // machine IO (controller timers)
    adc_.RunCycles(cycles);
    uart_.RunCycles(cycles);
    timer_.RunCycles(cycles);
    spu_.RunCycles(cycles);
    if (ppu_.RunCycles(cycles)) break; // PPU signals end-of-frame (after drawing line 239)
  }
  watchdog_.RunCycles(cycles_in_frame); // checked once per frame
}
```
- **Instruction-level granularity**: every subsystem is stepped with the cycles of the just-executed instruction. No event scheduler.
- **Scanline clock** (ppu.cc ctor): `SimpleConfigurableClock((NTSC ? 429 : 432) * 4, 1)` →
  - NTSC: **1716 CPU cycles/scanline**, **262 scanlines/frame** → 449,592 cycles/frame ≈ 60.05 fps.
  - PAL: **1728 cycles/scanline**, **312 scanlines/frame** → 539,136 cycles/frame ≈ 50.08 fps.
- Clock helpers (common.h): `SimpleClock<A,B>` — counter decremented by `B*cycles`, fires and adds `A` when ≤0. `DivisibleClock` adds a tick counter with `GetDividedTick(div)` = true when `(div_counter & ((1<<div)-1))==0` (power-of-two subdivision).
- Frontend pacing: audio-driven. Runs one frame, pushes audio into an `SDL_AudioStream` (281250 Hz stereo U16 → 48 kHz S16) and sleeps while >0.1 s of audio is queued (ui.cc lines 640-673).

### Framebuffer handoff
PPU keeps `std::array<std::array<Color,320>,240>` where `Color` = raw RGB555 word (bit 15 = transparent flag internally, cleared to black at line end). UI uploads with `glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB5, 320, 240, 0, GL_BGRA, GL_UNSIGNED_SHORT_1_5_5_5_REV, fb)` — i.e. the framebuffer is literal x1r5g5b5 little-endian words.

---

## 2. unSP CPU core (cpu.cc / cpu.h)

### Decoding approach
Interpreter; each 16-bit opcode word is viewed through a bitfield union:
```
op0   = bits 12..15   (ALU op / 0xF = extended ops)
rd    = bits 9..11
op1   = bits 6..8
op1n  = bits 3..8     (op1 and opn combined, used as 0..63 switch)
muls_n= bits 3..6
opn   = bits 3..5
rs    = bits 0..2
imm6  = bits 0..5
```
Registers: `regs_[8]` = SP(0), R1, R2, R3, R4, BP(5), SR(6), PC(7). SR layout: `ds` bits 10-15 (6 bits), `n` bit 9, `z` bit 8, `s` bit 7, `c` bit 6, `cs` bits 0-5. Full 22-bit code address = `(SR.cs << 16) | PC`; data segment address = `(SR.ds << 16) | Rs` in ds-indirect modes.

Shift buffer `sb_` is a 4-bit register **banked 3 ways**: `sb_[0]` normal, `sb_[1]` IRQ mode, `sb_[2]` FIQ mode; selected by `sb_[fiq_ ? 2 : irq_]`.

`fir_mov_` flag (default **true** at reset): when enabled, `muls` (FIR filter mode) writes back the shifted previous operand: on iteration i>0 does `Write(rd_ptr, old_val1)` (see cpu.cc lines 170-184). Toggled by `FIR_MOV ON/OFF` (op0=0xF, op1=5, imm6=4/5 → `fir_mov_ = !(imm6&1)`).

### ALU ops (op0): 
0=ADD, 1=ADC, 2=SUB, 3=SBC, 4=CMP, 6=NEG, 8=XOR, 9=LOAD, 10=OR, 11=AND, 12=TEST, 13=STORE. Flags: ADD/ADC/SUB/SBC/CMP update NZSC (`c` = bit16 carry of unsigned sum; SUB implemented as `val1 + ~val2 + carry(1 for SUB/CMP)`; `s` = sign of signed 32-bit result; `n` = bit15; `z` = 16-bit zero). NEG/XOR/LOAD/OR/AND/TEST update NZ only. Flags NOT updated when rd==PC.

### Addressing modes (switch on op1n, when op0 != 0xF)
| op1n | mode | cycles |
|---|---|---|
| 0-7 | `[BP+imm6]` (or **conditional branch** ±imm6 if rd==PC) | 6 (branch: 4 taken / 2 not) |
| 8-15 | `imm6` (or branch backward if rd==PC) | 2 |
| 16-23 | push (STORE) / pop (LOAD) rd..rd±n via [rs]; **RETI pseudo-op** = pop with rd=BP,opn=3,rs=SP (clears fiq_ else irq_, then pops SR,PC i.e. n=2 starting at reg after BP) | 2n+4 |
| 24 | `[Rs]` | 6 (7 if rd==PC) |
| 25 | `[Rs--]` | 6/7 |
| 26 | `[Rs++]` | 6/7 |
| 27 | `[++Rs]` | 6/7 |
| 28 | `ds:[Rs]` (addr = ds<<16|Rs) | 6/7 |
| 29 | `ds:[Rs--]`, ds-- when Rs wraps 0xFFFF | 6/7 |
| 30 | `ds:[Rs++]`, ds++ when Rs wraps to 0 | 6/7 |
| 31 | `ds:[++Rs]`, ds++ on wrap to 0 | 6/7 |
| 32 | register Rs | 3 (5 if rd==PC) |
| 33 | `imm16` (2nd word); dest=rd, src1=rs | 4/5 |
| 34 | `[imm16]` read; src1=rs | 7/8 |
| 35 | `[imm16]` write (result of `rs OP rd` stored to [imm16]) | 7/8 |
| 36-39 | Rs ASR n (n=opn&3 +1, 1..4) through 4-bit sb | 3/5 |
| 40-43 | Rs LSL n | 3/5 |
| 44-47 | Rs LSR n | 3/5 |
| 48-51 | Rs ROL n (20-bit rotate incl. sb) | 3/5 |
| 52-55 | Rs ROR n | 3/5 |
| 56-63 | `[A6]` direct address = imm6 | 5 (6 if rd==PC) |

Shifts operate on a 20-bit quantity: ASR/LSR/ROR use `(Rs<<4)|sb`, result = bits 4-19, new sb = low 4 bits; LSL/ROL use `(sb<<16)|Rs`, result = low 16, new sb = bits 16-19.

STORE with imm6/register/imm16/shift modes = "die" (invalid). Store via `[BP+imm6]`, indirect, `[A6]`, `[imm16]-store` writes rd (or rs for op1n=35 STORE).

### Extended ops (op0 == 0xF, switch on op1)
- op1=0, opn=1: `MUL us` (rd unsigned × rs signed) → R4:R3, **12 cycles**.
- op1=1: `CALL a22`: target = `(imm6<<16)|next_word`; pushes PC then SR onto SP; **9 cycles**.
- op1=2 with rd==PC: `GOTO a22` — **5 cycles**; else falls into...
- op1=2/3: `MULS us` n terms (n = muls_n, 0→16): sum of `[rd++] * (int16)[rs++]`, result to R4:R3, FIR_MOV writeback as above; **10n+6 cycles**.
- op1=4, opn=1: `MUL ss` (signed×signed) → R4:R3, **12 cycles**.
- op1=5: interrupt control by imm6: 0-3 = `INT OFF/IRQ/FIQ/FIQ,IRQ` (irq_enable=bit0, fiq_enable=bit1); 4-5 = FIR_MOV ON/OFF; 8/9 = `IRQ OFF/ON`; 12/14 = `FIQ OFF/ON`; 32/40/48/56 = `BREAK` (push PC,SR; PC=[0xFFF5]; SR=0; 10 cycles); 37 = `NOP` (2 cycles). All others 2 cycles.
- op1=6/7: `MULS ss` (signed×signed FIR), same as above.

Branch conditions (op0 when rd==PC, op1n<16): 0 JB(!C), 1 JAE(C), 2 JGE(!S), 3 JL(S), 4 JNE(!Z), 5 JE(Z), 6 JPL(!N), 7 JMI(N), 8 JBE(!(C&&!Z)), 9 JA(C&&!Z), 10 JLE(!(!S&&!Z)), 11 JG(!S&&!Z), 12 JVC(N==S), 13 JVS(N!=S), 14 JMP(always). NOTE unSP carry sense: JB = carry **clear**, JAE = carry **set** (carry = NOT borrow).

Stack: `PushWord: Write(sp--, val)`; `PopWord: Read(++sp)` — full-descending on 16-bit SP (page 0 RAM).

### Interrupts (cpu.cc `CheckInterrupts`, called before each instruction)
- Vectors (fetched via bus, i.e. from cart ROM at boot): **BREAK = [0xFFF5]**, **FIQ = [0xFFF6]**, **RESET = [0xFFF7]**, **IRQ0..IRQ7 = [0xFFF8..0xFFFF]**.
- FIQ: taken if `fiq_signal_ && !fiq_ && fiq_enable_`. IRQ: lowest-numbered pending bit in the 8-bit `irq_signal_` bitset (via `countr_zero`), if `!irq_ && irq_enable_`. FIQ has priority over IRQ; FIQ can preempt IRQ mode (checks `!fiq_` only) but IRQ cannot preempt anything.
- Entry: push PC, push SR, PC = vector, **SR = 0** (clears cs/ds/flags!), sets mode flag; costs **10 cycles** and no instruction executes that step.
- Exit: RETI encoding (pop BP,3 from SP) clears fiq_ else irq_, restores SR then PC.
- Level-triggered: peripheral modules call `SetIrq(n, bool)`/`SetFiq(bool)`; the signal stays asserted until the peripheral's status register is acknowledged.
- Reset(): all regs 0, sb 0, both mode flags false, both enables false, fir_mov_=true, `PC = Read(0xFFF7)` (cs stays 0).

### unSP version quirks
veesem implements a **single ISA variant** (base unSP 1.0/1.1-style). There is **no** unSP 1.2+ extended instruction support (no extended push/pop of secondary banks, no 1.2 opcodes) and no version-dependent cycle counts. Unknown opcodes call `die()` (abort). This is sufficient for V.Smile (SPG200 uses unSP 1.0-class core).

---

## 3. Memory map (Spg200::ReadWord/WriteWord, spg200.cc; all addresses are 16-bit WORD addresses; bus masked to 22 bits: `addr & 0x3fffff`)

| Range | What |
|---|---|
| 0x000000–0x0027FF | Internal RAM, 10 K words (`std::array<uint16_t,0x2800>`) |
| 0x002810–0x00281B | PPU BG1/BG2 regs (6 regs each: Xscroll, Yscroll, Attribute, Control, TileMapPtr, AttributeMapPtr) |
| 0x00281C/0x00281D | PPU vertical compress amount / offset |
| 0x002820/21/22 | BG1 segment ptr, BG2 segment ptr, sprite segment ptr |
| 0x00282A | Blend level (2 bits) |
| 0x002830 | Fade level (8 bits; stored, not rendered) |
| 0x002836/0x002837 | IRQ Vpos / Hpos (9 bits each) |
| 0x002842 | Sprite control (bit0 = sprite enable) |
| 0x002854 | STN LCD control (6 bits, stored only) |
| 0x002862/0x002863 | PPU IRQ control / IRQ status (write-1-to-clear); bits: 0=VBlank,1=Pos,2=SpriteDMA |
| 0x002870/71/72 | Sprite DMA source (14b) / target (10b) / length (write triggers) |
| 0x002900–0x0029FF | Line scroll table (256 × 9 bits) |
| 0x002A00–0x002AFF | Line compress table (256, stored only) |
| 0x002B00–0x002BFF | Palette, 256 × RGB555 (bit15 = transparent) |
| 0x002C00–0x002FFF | Sprite attribute memory, 256 sprites × 4 words |
| 0x003000–0x0030FF | SPU channel regs bank 0 (16 ch × 16 regs, offsets 0-11 used) |
| 0x003200–0x0032FF | SPU channel regs bank 2 (phase etc., offsets 0-7 used) |
| 0x003400–0x003418 | SPU global regs |
| 0x003D00–0x003D0F | GPIO (mode + 3 ports × {data,buffer,dir,attrib,mask}) |
| 0x003D10–0x003D19 | Timebase/Timer A/Timer B |
| 0x003D1C | PPU current line counter (read) |
| 0x003D20 | System control (watchdog enable bit15, sleep bit14, ... WriteMask 0xC3F6) |
| 0x003D21/0x003D22 | IO IRQ control / IO IRQ status (write-1-to-clear) |
| 0x003D23 | ExtMem control |
| 0x003D24 | Watchdog clear (write 0x55AA) |
| 0x003D25 | ADC control, 0x003D27 ADC data |
| 0x003D28–0x003D2A | Sleep/wakeup (unimplemented) |
| 0x003D2B | NTSC/PAL flag (reads 1 = PAL) |
| 0x003D2C/0x003D2D | PRNG 1 / PRNG 2 |
| 0x003D2E | FIQ source select (3 bits) |
| 0x003D2F | DS (data segment) direct access |
| 0x003D30–0x003D36 | UART: control, status, reset(w), baud lo, baud hi, TX buf, RX buf |
| 0x003E00–0x003E03 | CPU DMA: source lo / source hi (6 bits) / length (write triggers, reads remaining) / target (14 bits, internal RAM only) |
| 0x004000–0x3FFFFF | External memory via Extmem/chip selects (**note: 0x2800-0x280F, 0x3300-0x33FF gaps read 0**) |

Unhandled addresses: reads return 0, writes ignored.

### External memory / chip selects (extmem.cc + vsmile.cc)
ExtMem control (0x3D23): `ram_decode` bits 8-11, `address_decode` bits 6-7, `bus_arbiter` bits 3-5 (reset value 5), `wait_state` bits 1-2, WriteMask 0x0FFE. `address_decode` selects the mapping of the 22-bit bus (extmem sees full address incl. low 16K, but Spg200 only forwards ≥0x4000):
- 0 (reset): entire space → ROMCSB, unmasked (addr up to 0x3FFFFF straight into cart array).
- 1: addr>>21: 0 → ROMCSB (addr & 0x1FFFFF), 1 → CSB1 (addr & 0x1FFFFF).
- 2/3: addr>>20: 0 → ROMCSB, 1 → CSB1, 2 → CSB2, 3 → CSB3 (each addr & 0x0FFFFF).

V.Smile wiring (vsmile.cc): ROMCSB → `cart_rom[addr]`; CSB1 → `cart_rom[addr + 0x100000]`; CSB2 → Art Studio NVRAM (128 K words, `addr & 0x1FFFF`, writable) if cart type ART_STUDIO, else `cart_rom[addr + 0x200000]` (dual-ROM carts: 2 MiB second ROM appended to dump); CSB3 → `sysrom[addr]`. So with address_decode=2/3: CPU 0x000000-0x0FFFFF=cart lower 1MW, 0x100000-0x1FFFFF=cart second 1MW, 0x200000-0x2FFFFF=CSB2, 0x300000-0x3FFFFF=sysrom (1MW = 2 MiB).

---

## 4. PPU (ppu.cc/.h)

Screen: **320×240**, output RGB555. Rendering is **per scanline** (DrawLine at each visible scanline tick). No mid-line raster effects (Hpos ignored).

### Register bit layouts
**BG Attribute** (0x2812/0x2818, WriteMask 0x3FFF): color_mode bits 0-1 (bpp = (mode+1)*2 → 2/4/6/8), hflip bit 2, vflip bit 3, hsize bits 4-5 (tile w = 8<<hsize), vsize bits 6-7, palette bits 8-11, depth bits 12-13.

**BG Control** (0x2813/0x2819, WriteMask 0x01FF): bit0 bitmap_mode, bit1 register_mode (1 = attributes from BG attr reg, 0 = from attribute map), bit2 wallpaper_mode (repeat tile 0), bit3 enabled, bit4 hmovement (per-line scroll from 0x2900 table), bit5 hcompress (NOT implemented), bit6 vcompress, bit7 hicolor_mode (16bpp, bitmap only), bit8 blend.

**Scrolls**: X 9 bits (512 virtual width), Y 8 bits (256 virtual height). Tile map pointer / attribute map pointer: 14 bits (point into low RAM). Segment pointers (0x2820/21/22): full 16 bits; **tile data address = (segment_ptr << 6) + (ch*tile_h + tile_y) * tile_w * bpp / 16** (word address anywhere in 22-bit space, usually cart ROM).

**Tilemap layout**: `tiles_per_row = 512 >> (hsize+3)`; entry index = `row*tiles_per_row + col`; tile number 0 = transparent/skip. Attribute map (register_mode=0): packed 2 entries/word (even tile = low byte, odd = high byte); TileAttribute byte: palette bits 0-3, hflip bit 4, vflip bit 5, blend bit 6.

**Bitmap mode**: per scanline y: `addr_lo = [tile_map_ptr + y]`, `addr_hi = byte from [attribute_map_ptr + y/2]` (low byte even lines, high byte odd) → 22-bit line address `lo | (hi<<16)`. Line is 512 px wide, wraps with scroll_x, hicolor_mode gives 16bpp direct RGB555; else bpp from color_mode.

**Pixel fetch byte order**: for bpp != 16 each word is byte-swapped before unpacking (`val = (val>>8)|(val<<8)`), pixels consumed MSB-first; hflip reads words backwards from end of line.

**Palette lookup**: 2/4bpp: `palette*16 + pix`; 6bpp: `(palette>>2)*64 + pix`; 8bpp: `pix` direct; 16bpp: raw pixel value. Palette entry bit15 = transparent.

**Vertical compress** (BG ctrl bit6): `virtual_y = screen_y * amount/0x20 + (sext13(offset) + 128 - 128*amount/0x20)`; amount default 0x20 (=1.0), reg 0x281C (9 bits), offset 0x281D (13 bits signed).

### Sprites
256 sprites × 4 words at 0x2C00: +0 `ch` (tile number, **0 = disabled**), +1 xpos (9 bits), +2 ypos (9 bits), +3 attr (WriteMask 0x7FFF): color_mode 0-1, hflip 2, vflip 3, hsize 4-5, vsize 6-7, palette 8-11, depth 12-13, blend 14. Screen position: `x = 160 + sext9(xpos) - w/2`, `y = 128 - sext9(ypos) - h/2` (y axis points up). Tile data from sprite segment ptr with the same segment formula. No per-line sprite limit emulated.

### Layering/priority (DrawLine)
For layer (depth) 0..3: draw BG1 then BG2 if `ctrl.enabled && attr.depth == layer`; then all 256 non-blend sprites with that depth in index order, then all blend sprites with that depth. Blend: `result = old*(4-(level+1))/4 + new*(level+1)/4` per RGB channel, level = 0x282A & 3. Transparent leftover pixels become black (raw 0). Fade level register exists but is not applied.

### Timing / IRQs
Per scanline tick: if `cur_scanline_ == irq_vpos && ctrl.pos` → pos IRQ status. Lines 0-239 drawn; after drawing line 239: vblank status set (if enabled in ctrl... note: status only set when ctrl.vblank enabled — quirk of veesem), frame_count++, frame finished. At line (total-1) vblank status is force-cleared and scanline resets to 0. PPU IRQ line = `(ctrl & status) & {dma|pos|vblank}` → IRQ0 (and FIQ if fiq_select==0). Status reg is write-1-to-clear at 0x2863. Reset: irq_vpos=irq_hpos=0x1FF, compress amount 0x20.

**Sprite DMA** (0x2872 write = length): synchronous copy from bus `sprite_dma_source_++` into sprite memory `sprite_dma_target_++ & 0x3FF`; then sets DMA IRQ status if enabled. Reads of 0x2872 return remaining (0).

---

## 5. SPU (spu.cc/.h) — 16 channels

### Clocks
- Sample generation: `SimpleClock<96>` → 27 MHz / 96 = **281250 Hz output sample rate** (stereo interleaved, offset-binary u16, XOR 0x8000). Frontend converts 281250→48000 Hz via SDL_AudioStream.
- Envelope clock: `DivisibleClock<384>` → 70312.5 Hz base for envelope/pitch-bend/beat processing.
- Rampdown clock: `DivisibleClock<13>` ticked once per envelope tick (÷13).

### Channel registers, bank 0x30n0-0x30nB (n = channel)
| off | reg |
|---|---|
| 0 | Wave address lo (write resets wave_shift) |
| 1 | Mode: bits 0-5 wave addr hi (6 bits), bits 6-11 loop addr hi, bit12-13 tone_mode (0=SW/unimpl, 1=one-shot, 2=loop), bit14 tone_color (0=8-bit PCM, 1=16-bit PCM), bit15 adpcm |
| 2 | Loop address lo |
| 3 | Pan: volume bits 0-6, pan bits 8-14 |
| 4 | Envelope0: inc bits 0-6, sign bit7 (1=decay), target bits 8-14 |
| 5 | Envelope data: edd (current level) bits 0-6, count bits 8-15 |
| 6 | Envelope1: load bits 0-7, repeat bit8, repeat_count bits 9-15 |
| 7 | Envelope address hi (bits 0-5) + envelope IRQ: irq_enable bit6, irq_fire_address bits 7-15 |
| 8 | Envelope address lo |
| 9 | Wave data 0 (previous sample, raw u16) |
| 10 | Envelope loop control: ea_offset bits 0-8, rampdown_offset bits 9-15 |
| 11 | Wave data (current sample, raw u16) |

### Channel registers, bank 0x32n0-0x32n7
0: phase hi (3 bits, phase is 19-bit), 1: phase accumulator hi, 2: target phase hi, 3: rampdown clock select (3 bits), 4: phase lo, 5: phase acc lo, 6: target phase lo, 7: pitch bend control (offset bits 0-11, sign bit12, time_step bits 13-15).

### Global registers 0x3400-0x3418
0x3400 channel enable (start/stop channels on edge; stopped-flag channels unaffected), 0x3401 main volume (7b), 0x3402 channel FIQ enable, 0x3403 channel FIQ status (W1C), 0x3404 beat base count (12b, reload counter), 0x3405 beat count: bits 0-13 count, bit14 IRQ status, bit15 IRQ enable, 0x3406-0x3409 env clk selects (4 bits/channel, 4 channels/reg), 0x340A env rampdown trigger (masked to running channels), 0x340B channel stop status (W1C; clearing restarts channel), 0x340C zero cross enable, 0x340D control (WriteMask 0x388: init bit3, overflow bit5 RO, high_volume bits 6-7, low_pass bit8, no_interpolation bit9 — last two unused in mixing), 0x340F channel status (enable & ~stop, RO), 0x3410/11 wave in L/R (write), 0x3412/13 wave out L/R (read, final DAC values), 0x3414 repeat enable, 0x3415 env mode (1 = manual envelope, disables auto), 0x3416 tone release, 0x3417 env IRQ status (W1C), 0x3418 pitch bend enable.

### Playback (TickChannel per output sample)
`phase_acc += phase; if (phase_acc >= 0x80000)` (19-bit overflow): raise channel FIQ (if enabled), shift `wave_data_0 = wave_data`, fetch next sample word at `wave_address`:
- **ADPCM** (mode.adpcm): word 0xFFFF = end marker; else nibble `(word >> wave_shift) & 0xF` through IMA-like decoder (tables in adpcm.cc: standard IMA StepSizeTable[89], StepAdjustTable {-1,-1,-1,-1,2,4,6,8}; e = ss/8 + code&1?ss/4 + code&2?ss/2 + code&4?ss, sign bit 8; clamp ±32767; step index clamp 0-88), result XOR 0x8000 (to offset binary); wave_shift += 4, address++ every 4 nibbles.
- **8-bit PCM** (tone_color=0): byte `(word>>shift)&0xFF`; **0xFF = end marker**; sample = byte duplicated into both halves `(b<<8)|b`; shift+=8.
- **16-bit PCM** (tone_color=1): word as-is; 0xFFFF = end marker; address++.
End marker: tone_mode 1 → stop channel (sets stop flag, clears tone_release/rampdown/adpcm); tone_mode 2 → `wave_address = loop_address; wave_shift = 0; mode.adpcm = false` (**ADPCM carts loop into PCM data — real hardware quirk**).

Output per channel: linear interpolation `sample = (wave_data_0*(2^19 - acc) + wave_data*acc) >> 19 - 0x8000`; apply envelope `sample = sample*edd >> 7`; pan: `left_pan = clamp((0x80-pan)*2,0,0x7F)`, `right_pan = clamp(pan*2,0,0x7F)`; accumulate `(sample*pan_side*volume) >> 14`. Master: `out >>= (4 - high_volume)`; `final = (out + wave_in - 0x8000) * main_volume >> 7`; stored/output as `final ^ 0x8000` (u16 offset binary).

### Envelope engine (per envelope tick, ÷ table by env_clk)
Frame-divide tables: envelope `{2,3,4,5,6,7,8,9,10,11,12,13,13,13,13,13}` (power-of-2 divides of the 70312.5 Hz clock), rampdown `{2,4,6,8,10,12,13,13}`, pitchbend `{3,4,5,6,7,8,9,10}`. Auto envelope (env_mode=0, no rampdown): decrement count; at 0: step edd by ±inc toward target (clamped); decay reaching 0 stops channel; on reaching target, load next envelope segment from `envelope_address + ea_offset`: reads envelope0 = [addr], envelope1 = [addr+1]; if repeat bit: on repeat_count exhaustion also reads loop control from [addr+2] (preserving rampdown_offset); else ea_offset += 2. Envelope IRQ fires when ea_offset == irq_fire_address (→ SPU beat IRQ line, IRQ4). Rampdown: edd -= rampdown_offset until 0 → stop. Pitch bend: phase steps by offset toward target_phase.

### Beat counter
`beat_base_count` (0x3404) decrements at envelope rate; on expiry reloads and decrements `beat_count`; at 0 with IRQ enable → IRQ status bit14 set → **IRQ4** (shared with envelope IRQs).

Channel FIQ status drives **IRQ1** (and FIQ if selected). Output frequency of a channel = 281250 × phase / 2^19 Hz.

---

## 6. IRQ controller, GPIO/IO block

### IRQ line mapping (irq.cc)
- IRQ0 = PPU (vblank/pos/dma)
- IRQ1 = SPU channel FIQs
- IRQ2 = Timer A | Timer B
- IRQ3 = UART | ADC
- IRQ4 = SPU beat | SPU envelope IRQs
- IRQ5 = EXT1 | EXT2 (external pins — controller RTS lines)
- IRQ6 = 4096 Hz | 2048 Hz | 1024 Hz ticks
- IRQ7 = TMB1 | TMB2 | 4 Hz | key change
- FIQ source select (0x3D2E, 3 bits): 0=PPU, 1=SPU channel, 2=TimerA, 3=TimerB, 4=UART, 5=EXT1/2, 6=ADC, 7=none (reset value 7).

IO IRQ control/status (0x3D21/0x3D22) bit layout: bit0 TMB1, bit1 TMB2, bit3 4Hz, bit4 1024Hz, bit5 2048Hz, bit6 4096Hz, bit7 key_change, bit8 UART, bit9 EXT1, bit10 TimerB, bit11 TimerA, bit12 EXT2, bit13 ADC, bit14 SPI. Status is W1C via 0x3D22. PPU (0x2862/63), SPU (0x3403/0x3405 bit14/0x3417) have their own enable/status regs.

### GPIO (0x3D00-0x3D0F)
0x3D00 mode: bit0 ioa_special, bit1 iob_special, bits 2-4 wake enables (WriteMask 0x1F). Per port n (A=0x3D01, B=0x3D06, C=0x3D0B): +0 data (read = resolved pin state; write = buffer), +1 buffer, +2 dir, +3 attrib, +4 mask. Read logic: `buf = buffer ^ (dir & ~attrib)` (attrib=0 + dir=1 inverts); `result = (buf & dir & ~mask) | (external & ~dir & ~mask)`. Write pushes `buf & ~mask` with valid-bits `dir & ~mask` to the machine.

### V.Smile port wiring (vsmile.cc)
- **Port A**: reads 0.
- **Port B** (inputs, active low): bit3 = !RESTART button, bit6 = !ON button, bit7 = !OFF button.
- **Port C**: read = `region_code(bits 0-3) | vtech_logo<<4 | 0x0020 | cts0<<8 | cts1<<9 | rts0<<10 | rts1<<12 | 0x6000`. Writes: bit8 = CTS to controller 1 (`joy_.SetCts`), bit9 = CTS to controller 2 (unemulated 2nd pad). Bits 10/12 = controller RTS inputs (rts=true means idle/no request). Bit5 and bits 13-14 always read 1.

### UART (0x3D30-0x3D36)
Control 0x3D30: bit0 rx_irq_enable, bit1 tx_irq_enable, bits2-3 bit9 mode ctl, bit4 mulpro, bit5 mode (9-bit), bit6 rx_enable, bit7 tx_enable. Status 0x3D31: bit0 rx_ready, bit1 tx_ready (W1C mask 0x0003), bit2 parity_err, bit3 frame_err, bit4 overrun, bit5 bit9 (reads 1 after reset), bit6 tx_busy, bit7 rx_full. 0x3D32 write = soft reset (no-op TODO). Baud: 0x3D33 lo, 0x3D34 hi; **byte time = 16 × (65536 − baud16) × (mode ? 11 : 10) CPU cycles**. TX (0x3D35 write): if tx_enable && !busy, starts; on completion delivers byte to machine (→ controller if CTS0 high), sets tx_ready, raises UART IRQ if enabled. RX: machine calls `RxStart(byte)` (ignored if rx busy or rx_enable=0); after byte time, rx_buf latched, rx_full/rx_ready set, IRQ. Reading 0x3D36 clears rx_full and returns rx_buf.

### ADC (0x3D25 control, 0x3D27 data)
Control: bit0 enable, bit1 csb, bits2-3 clock divide select, bits4-5 channel, bit8 vrt, bit9 int_enable, bit10 req_auto_8k (unsupported → die), bit12 request (self-clearing), bit13 = IRQ status (W1C via same reg). Write with enable+request starts conversion of `channel`; after `DivisibleClock<16>` + divided tick, data = 10-bit value, ready bit15 set, IRQ raised if enabled. V.Smile ADC values: ch0=0, **ch1=0x3FF (battery, full)**, ch2=0, ch3=0.

---

## 7. V.Smile controller protocol (vsmile_joy.cc) — serial via UART + RTS/CTS on Port C

Timers (27 MHz cycles): idle keepalive `SimpleClock<27000000>` = 1 s; RTS timeout `SimpleClock<13500000>` = 0.5 s; TX start delay `SimpleClock<97200>` = **3.6 ms** between CTS grant and first byte.

**Handshake**: Controller queues bytes in a 16-byte FIFO. When FIFO becomes non-empty it drives its RTS line active (veesem: `SetRts(false)`; the **falling edge raises EXT1 IRQ** (IRQ5) for pad 1, EXT2 for pad 2). Console grants by raising CTS (Port C bit8 write); controller waits 3.6 ms then sends bytes back-to-back while CTS remains high and FIFO non-empty; when FIFO empties RTS returns idle (true). If console doesn't grant CTS within 0.5 s: buffer is flushed, joystick state reset, controller re-queues 0x55. At machine Reset both EXT1/EXT2 IRQs are asserted true, rts=[true,true], cts=[false,false].

**Controller → console (TX) bytes**:
- `0x55` — idle/keepalive, sent after 1 s with no other TX.
- Buttons `0xA0`-`0xA4` (sent when Enter/Back/Help/ABC state changes): `0xA0` = all released, `0xA1` = Enter, `0xA2` = Back/Exit, `0xA3` = Help, `0xA4` = ABC (Learning Zone). Priority order enter>back>help>abc, one at a time.
- Color buttons `0x90 | bits` (on change): bit0 green, bit1 blue, bit2 yellow, bit3 red (e.g. 0x90 = none pressed).
- Joystick X (sent with Y as a pair, on change): `0xC0` = center; right = `0xC3 + (mag-1)`, mag 1-5 (0xC3..0xC7); left = `0xCB + (mag-1)` (0xCB..0xCF).
- Joystick Y: `0x80` = center; up (y>0) = `0x83 + (mag-1)` (0x83..0x87); down = `0x8B + (mag-1)` (0x8B..0x8F).
- Probe response `0xB0 | nibble` (see below).

**Console → controller (RX) bytes** (only reach controller while console's CTS for it is high):
- `0x6X` — set LEDs: bit0 green, bit1 blue, bit2 yellow, bit3 red.
- `0x7X` / `0xBX` — probe/challenge: controller keeps `probe_history[2]`; on `0x7X`: h0 = 0, h1 = X; on `0xBX`: h0 = previous h1, h1 = X. Response queued: `0xB0 | (((-h0 + -h1) ^ 0xA) & 0xF)`. (Games use this as a liveness/checksum handshake; without correct responses the game decides no controller is attached.)

Joystick becomes "active" (`joy_active_`) after the first successfully transmitted byte; state resets on RTS timeout.

---

## 8. Boot / ROM loading

- **No BIOS/sysrom required.** At reset ExtMem address_decode = 0, so the whole 0x4000-0x3FFFFF space maps to ROMCSB = **cartridge ROM**, and the CPU fetches the reset vector from `[0xFFF7]` **in cart ROM** (word offset 0xFFF7 of the cart file). Carts are self-booting. The sysrom (2 MiB, at CSB3 = 0x300000-0x3FFFFF once the game sets address_decode=2/3) provides the V.Smile boot animation and library routines.
- **Dummy sysrom** (ui.cc LoadVSmile): when no sysrom given, a zero-filled 1 M-word array with `sysrom[i+1] = 0x0031` for even `i` in 0xFFFC0..0xFFFDA — i.e. the 14 two-word vectors at CPU 0x3FFFC0-0x3FFFDB each become `lo=0x0000, hi=0x0031` (pointer 0x310000, which contains zeros). This makes games that call sysrom entry points at 0x3FFFC0+ not crash ("game-compatible dummy system ROM").
- **ROM file format**: raw binary, **little-endian 16-bit words** (`SDL_SwapLE16` on load), loaded into `CartRomType = std::array<Word, 4*1024*1024>` (**4 M words = 8 MiB**, zero-filled tail for smaller dumps; no mirroring performed — the fixed masks in extmem provide effective wrapping only per-decode-mode). SysRom = 1 M words (2 MiB). Art NVRAM = 128 K words (256 KiB), persisted LE to file.
- Region code jumpers (Port C bits 0-3) select sysrom language/branding; full table in vsmile.cc comments (0x3 = US English, 0xE = UK English w/ subtitle default, 0xD = French, 0xC = Spanish, 0xB = German, 0x7 = Chinese, 0x8 = Portuguese, etc.). Port C bit4 jumper = show VTech logo.
- ON/OFF/RESTART console buttons are GPIO Port B inputs (active low) that the sysrom/game polls.

---

## 9. Everything else an implementer needs

- **CPU DMA (0x3E00-0x3E03)**: source 22-bit (hi reg 6 bits), target 14 bits (**internal RAM only**, wraps &0x3FFF), write length to 0x3E02 executes the whole copy instantly (no cycle cost in veesem), length reads back remaining (0). No IRQ.
- **Sprite DMA (0x2870-72)**: source 14-bit (RAM), target 10-bit sprite mem, instant, raises PPU DMA IRQ (bit2 of 0x2862/63).
- **Random (0x3D2C/0x3D2D)**: two PRNG regs; hardware is a 15-bit LFSR (`seed = (seed<<1)|((bit15^bit14) of old)`, kept to 15 bits — code present but commented out); veesem actually returns `rand() & 0x7FFF`. Reset seeds 0x1418/0x1658. Games read these for randomness.
- **Timers**: base `DivisibleClock<27000000, 32768>` = 32768 Hz. Fixed IRQs: 4096 Hz (div 3), 2048 Hz (div 4), 1024 Hz (div 5), TMB2 = `2^(8-tmb2_sel)`-divided (128/256/512/1024 Hz), TMB1 = `2^(12-tmb1_sel)` (8/16/32/64 Hz), 4 Hz (div 13). Timebase setup 0x3D10 (tmb1 bits 0-1, tmb2 bits 2-3), 0x3D11 write clears the divider counter. **Timer A** (0x3D12 data/preload, 0x3D13 control, 0x3D14 enable, 0x3D15 clear IRQ): 16-bit up-counter; on overflow reloads preload and raises IRQ. Source A bits 0-2 / source B bits 3-5: A=5 selects B: 0=2048 Hz, 1=1024 Hz, 2=256 Hz, 3=TMB1, 4=4 Hz, 5=2 Hz; else B must be 6 and A: 2=32768 Hz, 3=8192 Hz, 4=4096 Hz. **Timer B** (0x3D16-0x3D19): source C: 2=32768, 3=8192, 4=4096 Hz. Unsupported combos abort.
- **Watchdog**: enabled by system-ctrl (0x3D20) bit15; period 0.75 s (20,250,000 cycles); write **0x55AA** to 0x3D24 to kick; expiry calls `cpu_.Reset()` only (peripherals keep state).
- **System control 0x3D20**: WriteMask 0xC3F6; bit15 watchdog enable, bit14 sleep enable, bits others = LVD/LVR/DAC disables (stored only).
- **0x3D2B**: PAL detect (1=PAL) — games use this to configure timing.
- **PeekWord**: reading 0x3D36 (UART RX) has a side effect (clears rx_full); veesem provides a side-effect-free Peek for its debugger — do the same for savestates/debuggers.
- **Known accuracy gaps in veesem** (deliberate simplifications you may need to exceed): Hpos IRQ ignored (Vpos fires at line start); fade level not applied; line_compress and BG hcompress not rendered; SPU "SW channel" tone_mode 0 unimplemented; SPU no_interpolation/low-pass bits ignored; UART soft reset a no-op; extmem wait states/bus arbiter have no timing effect; DMA instantaneous; GPIO pull attributes only partially modeled (RTS edge IRQ noted as "affected by GPIO pull configuration" TODO); key-change IRQ (IO bit7) never asserted; second controller (EXT2/CTS1) stubbed.
- **Game quirks encoded**: dual-ROM carts (4 MiB + 2 MiB on CSB2) supported via appended dumps; V.Smile Art Studio uses battery-backed 128 K-word NVRAM on CSB2; ADPCM channels that loop switch to PCM at loop point; sysrom region-code behavior differences by version documented in vsmile.cc header comment (v1.00 without cart + region <0x6 shows blinking blue VTech logo).
- **Audio ring**: SPU buffer 6144 stereo pairs/frame max; note veesem has an off-by-one wrap check (`pos == size()+1`) — harmless because it's drained every frame, but don't copy it.
- Screen aspect: 320×240 uploaded as GL_RGB5; PAL vs NTSC only changes line count (312/262) and line length (1728/1716 cycles), not resolution.

