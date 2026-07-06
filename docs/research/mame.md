# D-Smile Research Report: unSP CPU + SPG2xx SoC + V.Smile Driver (from MAME master, commit f3e71448d66ab30323b4894ad779a5f0126d5c59)

All information below extracted directly from MAME source files (paths given per section). Local copies read from downloaded raw files; original repo paths cited.

**V.Smile hardware summary**: SunPlus SPG200-family SoC ("SPG24X" in MAME, 256 sprites; V.Smile Baby uses SPG28X, 64 sprites), unSP 1.x CPU core (MAME instantiates the base `unsp_device` ISA 1.0 via spg2xx_device : unsp_device), XTAL 27 MHz, NTSC 320x240 visible of 320x262 total @60Hz, 2 MB (1M x 16) system ROM, cartridge on chip selects with GPIO-driven banking, 2 wireless-ish serial controllers over a UART with RTS/CTS handshake.

---

## 1. unSP instruction set (src/devices/cpu/unsp/unsp.cpp, unsp_other.cpp, unsp_jumps.cpp, unsp_fxxx.cpp, unsp_exxx.cpp, unsp_extended.cpp, unspdefs.h)

### 1.1 Field breakdown of the 16-bit opcode

```
op0  = (op >> 12) & 0xF   // major opcode / ALU operation
opA  = (op >>  9) & 0x7   // destination register index (0=SP,1..4=R1..R4,5=BP,6=SR,7=PC)
op1  = (op >>  6) & 0x7   // addressing-mode group
opN  = (op >>  3) & 0x7   // sub-mode / shift amount / register count
opB  =  op        & 0x7   // source/base register index
opimm = op & 0x3F         // 6-bit immediate for branches, [BP+imm6], imm6
```

Top-level decode (`unsp_device::execute_one`):
1. `op0 == 0xF` → fxxx group.
2. `op0 < 0xF && opA == 7 && op1 < 2` → conditional branches (op1==0 forward, op1==1 backward).
3. `op0 == 0xE` → exxx group (bitops/MUL/shifts; ISA 1.2+, base 1.0 treats as invalid).
4. everything else → `execute_remaining` (ALU/push/pop).

### 1.2 ALU operations (op0, in `do_basic_alu_ops`)

| op0 | mnemonic | operation | flags |
|---|---|---|---|
| 0x0 | ADD | lres = r0 + r1 | NZSC |
| 0x1 | ADC | lres = r0 + r1 + C | NZSC |
| 0x2 | SUB | lres = r0 + (uint16)~r1 + 1 | NZSC (with r1 := ~r1) |
| 0x3 | SBC | lres = r0 + (uint16)~r1 + C | NZSC (with ~r1) |
| 0x4 | CMP | as SUB, no writeback | NZSC |
| 0x5 | (invalid) | logs illegal, no effect | — |
| 0x6 | NEG | lres = -r1 | NZ |
| 0x7 | — | (branch space when opA==7/op1<2) | — |
| 0x8 | XOR | r0 ^ r1 | NZ |
| 0x9 | LOAD | lres = r1 | NZ |
| 0xA | OR | r0 \| r1 | NZ |
| 0xB | AND | r0 & r1 | NZ |
| 0xC | TEST | r0 & r1, no writeback | NZ |
| 0xD | STORE | write16(ea, r0), no flags | — |
| 0xE | — | exxx group | — |
| 0xF | — | fxxx group | — |

Flags are NOT updated when the destination register opA == 7 (PC). STORE with op1 addressing computes ea but skips the load (`if (op0 != 0x0d) r1 = read16(r2)` throughout).

Flag semantics (`update_nzsc`, unsp.cpp:427):
- N = bit15 of 16-bit result
- Z = (result & 0xFFFF) == 0
- C = bit16 of the 17-bit sum
- S = bit16(result) != bit15(r0 ^ r1)  — note for SUB/CMP/SBC, r1 is already complemented when passed in. S is the "signed less-than" flag (used by JGE/JL), N^S ≈ overflow (used by JVC/JVS).

`update_nz` (logic ops): N=bit15, Z=zero; S and C unchanged.

### 1.3 Addressing modes (op1 field, `execute_remaining` in unsp_other.cpp)

- **op1=0**: `Rd, [BP+imm6]` — ea = (uint16)(BP + (op & 0x3F)). 16-bit ea (no DS). 6 cycles.
- **op1=1**: `Rd, imm6` — r1 = op & 0x3F. 2 cycles.
- **op1=2**: unused in base decode (falls through switch default = no address setup; effectively invalid except push/pop patterns below).
- **op1=3**: indirect `Rd, [Rs]` forms, opN selects:
  - opN bit2 = 0: plain 16-bit address in Rs; opN&3: 0=`[Rs]`, 1=`[Rs--]` (post-dec), 2=`[Rs++]` (post-inc), 3=`[++Rs]` (pre-inc).
  - opN bit2 = 1: **D: prefix (data-segment) form** `[DS:Rs]` etc.: ea = `UNSP_LREG_I(Rs)` = `((SR << 6) & 0x3F0000) | Rs` (DS field of SR supplies bits 16–21). Post-dec wrapping 0x0000→0xFFFF does `SR -= 0x0400` (decrements DS); post/pre-inc wrapping 0xFFFF→0x0000 does `SR += 0x0400` (increments DS). Plain (non-D:) forms just wrap in 16 bits.
  - 6 cycles (7 if opA==PC).
- **op1=4**, opN selects "16-bit ops":
  - opN=0: `Rd, Rs` register-register. 3 cyc (5 if opA==PC).
  - opN=1: `Rd = Rs op IMM16` (next word is imm16; note r0 becomes Rs=opB, so `Rd = Rs op imm16` 3-operand form). 4 cyc (5 if PC).
  - opN=2: `Rd = Rs op [A16]` (direct 16-bit address in next word). 7 cyc (8 if PC).
  - opN=3: `[A16] = Rd op Rs` — store-to-direct-address form; result written to memory. 7 cyc (8).
  - opN=4..7: `Rd = Rd op (Rs ASR n)` arithmetic shift right by n = opN-3 (1..4), through 4-bit shift buffer SB: `shift = signext20((Rs<<4)|SB) >> n; SB = shift & 0xF; operand = shift >> 4`. 3 cyc (5).
- **op1=5**: logical shifts through SB. opN 0..3 = LSL by opN+1: `shift = ((SB<<16)|Rs) << (opN+1); SB=(shift>>16)&0xF; operand=(uint16)shift`. opN 4..7 = LSR by opN-3: `shift = ((Rs<<4)|SB) >> (opN-3); SB=shift&0xF; operand=shift>>4`. 3 cyc (5 if PC).
- **op1=6**: rotates through SB. `shift = (((SB<<16)|Rs)<<4)|SB` (20-bit ring); ROR by opN-3 (opN≥4): `shift >>= n; SB = shift & 0xF`; ROL by opN+1 (opN<4): `shift <<= n; SB=(shift>>20)&0xF`; operand = (uint16)(shift>>4). 3 cyc (5).
- **op1=7**: `Rd, [imm6]` direct zero-page: ea = op & 0x3F. 5 cyc (6 if PC).

The 6-op formats: register names `{"sp","r1","r2","r3","r4","bp","sr","pc"}`, indirect forms `{"[%s]","[%s--]","[%s++]","[++%s]"}`.

### 1.4 PUSH / POP / RETF / RETI

Detected via `lower_op = (op1 << 4) | op0`:
- **PUSH** (`lower_op == 0x2D`, i.e. op0=0xD, op1=2): pushes opN registers ending at register opA downward onto stack pointed by r[opB]: `while(n--) push(r[rH--], &r[opB])`. `push` = write16(*sp, val); *sp = (uint16)(*sp - 1) (full-descending, 16-bit wrap, stack always in page 0 address space). Cycles: 4 + 2n.
- **POP** (`lower_op == 0x29`, i.e. op0=9, op1=2): pops opN registers starting at r[opA+1] upward: `while(n--) r[++rL] = pop(&r[opB])`. pop = *sp=(uint16)(*sp+1); read16(*sp). Cycles: 4 + 2n.
- **RETF** = POP encoding 0x9A90 (opA=5, opN=2, opB=0/SP): pops SR then PC.
- **RETI** = special-cased exact opcode **0x9A98**: 8 cycles; if INE (nest enable) pops FR first (via set_fr), then pops SR, then PC; clears the in-FIQ flag if set else the in-IRQ flag. After executing RETI (op == 0x9A98), MAME skips the post-instruction `check_irqs()` for that one instruction (one-instruction IRQ shadow).

### 1.5 Conditional branches (opA==7, op1∈{0,1}, unsp_jumps.cpp)

Offset = op & 0x3F; op1==0 → PC += offset, op1==1 → PC -= offset (22-bit add via add_lpc, carries into CS). Taken: 4 cycles; not taken: 2 cycles. JMP always 4.

| op0 | mnemonic | condition |
|---|---|---|
| 0 | JB/JNC/JCC | !C |
| 1 | JAE/JC/JCS | C |
| 2 | JGE | !S |
| 3 | JL | S |
| 4 | JNE/JNZ | !Z |
| 5 | JE/JZ | Z |
| 6 | JPL | !N |
| 7 | JMI | N |
| 8 | JBE | !(C && !Z) i.e. (SR & (Z\|C)) != C |
| 9 | JA | C && !Z |
| 10 | JLE | Z \|\| S |
| 11 | JG | !Z && !S |
| 12 | JVC | N == S |
| 13 | JVS | N != S |
| 14 | JMP | always |
| 15 | — | fxxx group |

### 1.6 fxxx group (op0 == 0xF), sub-decoded on bits 8:6 (`(op & 0x01C0) >> 6`)

**Group 000** (unsp_fxxx.cpp:19):
- `DS = imm6` : encoding 1111 1110 00ii iiii (`(op&0xFFC0)==0xFE00`), ISA1.2+
- `Rx = DS` : 1111 ---0 0010 0rrr (`(op&0xF1F8)==0xF020`), ISA1.2+
- `DS = Rx` : 1111 ---0 0010 1rrr (0xF028), ISA1.2+
- `Rx = FR` : 0xF030; `FR = Rx` : 0xF038 (ISA1.2+; FR = "inner flag register", layout below)
- **MUL us** (signed Rs × unsigned Rd): everything else in this group: `1111 rrr0 0000 1rrr`; `lres = r[opA]*r[opB]; if (r[opB] & 0x8000) lres -= r[opA] << 16; R4 = lres>>16; R3 = lres&0xFFFF`. 12 cycles.

**Group 001**:
- **CALL a22**: `1111 --00 01aa aaaa` + imm16 (`(op&0xF3C0)==0xF040`): read imm16 (PC low), push PC then SR, PC=imm16, SR CS bits = op&0x3F. 9 cycles.
- Memory BITOP `[A16]/ds:[A16], bit-offset` (ISA 2.0 only): `1111 -D10 01bb oooo` + imm16 (`(op&0xF3C0)==0xF240`): bb = tstb/setb/clrb/invb; Z always set from original bit value (Z = !bit); D=1 uses DS<<16|imm16.

**Group 010**:
- **JMPF / goto a22**: `1111 1110 10aa aaaa` + imm16 (`(op&0xFFC0)==0xFE80`): PC=imm16, CS=op&0x3F. 5 cycles. (Nominally 1.2+ but used by 1.0/1.1-era software; implement unconditionally.)
- Else MULS us sizes 16,1..7 (`1111 rrr0 10ss srrr`) — **unimplemented in MAME** (fatalerror).

**Group 011**:
- **JMPR (goto MR)**: `1111 1110 11-- ----` (0xFEC0), ISA1.2+: PC = R3, CS = R4 & 0x3F. 5 cycles.
- Else MULS us sizes 8..15 — unimplemented.

**Group 100**:
- **MUL ss** (signed×signed): `1111 rrr1 0000 1rrr` (`(op&0xF1F8)==0xF108`): lres = r[opA]*r[opB]; subtract r[opA]<<16 if r[opB] bit15; subtract r[opB]<<16 if r[opA] bit15; R4:R3 = lres. 12 cycles.

**Group 101** (interrupt / misc; opcode listed for the opA=0 variant, opA bits are don't-care so each op has 8 aliases f1xx,f3xx,...,ffxx). Base device (ISA 1.0) implements, 2 cycles each:
- 0xF140 `INT OFF` (irq=0,fiq=0); 0xF141 `INT IRQ` (irq=1,fiq=0); 0xF142 `INT FIQ` (irq=0,fiq=1); 0xF143 `INT FIQ,IRQ` (both on)
- 0xF144 `FIR_MOV ON` (fir_move=1); 0xF145 `FIR_MOV OFF` (fir_move=0)
- 0xF148 `IRQ OFF`; 0xF149 `IRQ ON`; 0xF14C `FIQ OFF`; 0xF14E `FIQ ON` (spec says 1.2 only but appears in 1.0-era code; implement)
- 0xF160 `BREAK` — unimplemented in MAME ("call vector fff5?")
- 0xF165 `NOP`

unsp_12 (ISA1.2) adds (1 cycle "unknown" in MAME):
- 0xF146 `FRACTION OFF` (FRA=0); 0xF147 `FRACTION ON` (FRA=1)
- 0xF14A `SECBANK OFF` (BNK=0); 0xF14B `SECBANK ON` (BNK=1) — swaps R1–R4 with shadow bank m_secbank[0..3] on change
- 0xF14D `IRQNEST OFF` (INE=0); 0xF14F `IRQNEST ON` (INE=1)
- 0xF161 (mask 0xF9E7? actual cases f161/f169/f171/f179 per opA aliases) `CALLR` (call MR): addr = R3 | ((R4&0x3F)<<16); push PC,SR; jump
- 0xF162.. `DIVS MR,R2` — unimplemented in MAME
- 0xF163.. `DIVQ MR,R2` (`op_is_divq: (op & 0xF163)==0xF163`): iterative non-restoring divide, one bit per instruction execution: on first exec loads m_divq_dividend = (R4<<16)|R3, divisor = R2, a = 0, bit=15; each step: AQ = bit31(a); a += a + bit15(dividend) ± divisor (+ if AQ set, − if clear); dividend = (dividend<<1)+1, bit0 ^= bit31(a); R3 = low16(dividend); bit--.
- 0xF164.. `EXP R4` → R2 = countl_one(R4)-1 if negative else countl_zero(R4)-1 (exponent detect)

Encoding comments in the source:
```
INT SET   1111 ---1 0100 00FI
FIR_MOV   1111 ---1 0100 010f
Fraction  1111 ---1 0100 011f
IRQ       1111 ---1 0100 100I
SECBANK   1111 ---1 0100 101S
FIRQ      1111 ---1 0100 11F0
NESTMODE  1111 ---1 0100 11N1
BREAK     1111 ---1 011- -000
CALLR     1111 ---1 011- -001
DIVS      1111 ---1 011- -010
DIVQ      1111 ---1 011- -011
EXP       1111 ---1 011- -100
NOP       1111 ---1 011- -101
```

**Group 110**:
- 0xFF80 (exactly) = EXTOP prefix for ISA 2.0 extended instructions (+ 16-bit second word) — not needed for V.Smile.
- Else **MULS ss** sizes {16,1..7}: `1111 rrr1 10ss srrr`. `execute_muls_ss(rd, rs, size)`: sum over i<size of signed16(mem[rd+i]) * signed16(mem[rs+i]) accumulated as int64; SB=0; if FIR_MOV enabled, shifts the rd sample window (write mem[rd+i]=mem[rd+i-1] for i=size-1..1); rd += size; rs += size; R4:R3 = low 32 bits of sum.

**Group 111**: MULS ss sizes 8..15 (`size = ((op>>3)&7)+8`).

### 1.7 exxx group (op0 == 0xE, ISA 1.2 device; base 1.0 faults)

```
Register BITOP  BITOP Rd,Rs           1110 rrr0 00bb 0rrr  (mask F1C8 == E000)
Register BITOP  BITOP Rd,offset       1110 rrr0 01bb oooo  (F1C0 == E040)
Memory BITOP    BITOP [Rd],Rs         1110 rrr1 00bb 0rrr  (F1C8 == E100)
Memory BITOP    BITOP ds:[Rd],Rs      1110 rrr1 01bb 0rrr  (F1C8 == E140)
Memory BITOP    BITOP [Rd],offset     1110 rrr1 10bb oooo  (F1C0 == E180)
Memory BITOP    BITOP ds:[Rd],offset  1110 rrr1 11bb oooo  (F1C0 == E1C0)
bb: 0=tstb 1=setb 2=clrb 3=invb; Z := !original-bit in all cases
MUL uu          1110 rrr0 0000 1rrr   (F1F8 == E008) unsigned*unsigned → R4:R3, 12 cyc
MULS uu/su      1110 rrrS 1sss srrr   (F180 == E080) — unimplemented in MAME
16-bit shift    1110 rrr1 0lll 1rrr   (F188 == E108): Rd = Rd op Rs, shift amount = Rs & 0x1F
   lll: 0=asr (int16 Rd >> n)
        1=asror (int32(Rd<<16) >> n; R3 |= low16, R4 = high16)
        2=lsl (uint16 result of Rd << n)
        3=lslor (res = Rd << n; R3 = low16, R4 |= high16)
        4=lsr (uint16(Rd) >> n)
        5=lsror (res = (Rd<<16) >> n; R3 |= low16, R4 = high16)
        6=rol, 7=ror — unimplemented in MAME
```

(V.Smile is nominally ISA 1.0/1.1; MAME instantiates spg2xx_device on the base unsp_device (ISA 1.0), so games shouldn't need exxx/1.2 ops, but jak_* comments show some "1.0-era" titles use FE80 JMPF and F148/F149.)

### 1.8 ISA 2.0 extended group (unsp_extended.cpp)

Not needed for V.Smile (GCM394-era: R8–R15, extended push/pop, Rb-op-imm16, indirect via R8–R15 with ds:, BP+imm6, A6). Documented in file if ever needed.

---

## 2. Register file, SR layout, CS:PC addressing (unsp.h/unspdefs.h/unsp.cpp)

Registers (3-bit index): 0=SP, 1=R1, 2=R2, 3=R3, 4=R4, 5=BP, 6=SR, 7=PC. R3:R4 pair = MR (R4 = high word). ISA1.2 adds a secondary bank of R1–R4 (BNK swap). ISA2.0 adds R8–R15.

**SR word layout**:
- bits 0–5: **CS** (code segment, upper 6 bits of 22-bit program counter)
- bit 6: C, bit 7: S, bit 8: Z, bit 9: N (`UNSP_C=0x0040, UNSP_S=0x0080, UNSP_Z=0x0100, UNSP_N=0x0200`)
- bits 10–15: **DS** (data segment, upper 6 bits for D:-prefixed/indirect-DS accesses); `get_ds() = (SR>>10)&0x3F`

**FR register** (ISA1.2 view of internal flags, get_fr/set_fr, unsp.cpp:629):
bit14 AQ, bit13 BNK, bit12 FRA, bit11 FIR_MOV, bits10–7 SB (4-bit shift buffer), bit6 FIQ enable, bit5 IRQ enable, bit4 INE (IRQ nest enable), bits3–0 PRI (current IRQ priority, reset=8).

**22-bit PC**: `UNSP_LPC = ((SR & 0x3F) << 16) | PC`. Every instruction fetch and PC-relative operation uses `add_lpc(offset)`: computes new 22-bit LPC, stores low 16 into PC and bits 16–21 into SR bits 0–5 (`SR = (SR & 0xFFC0) | ((new_lpc >> 16) & 0x3F)`). So sequential execution and branches carry across 64KW page boundaries automatically. Data accesses via plain 16-bit registers do NOT get CS; only [DS:...] forms use DS; direct [A16]/[imm6]/[BP+imm6]/stack accesses are within the low 64KW (page 0 = internal RAM + peripherals + start of ROM window).

Address space: 16-bit data, 23-bit address config with -1 shift (word-addressed; effectively 22-bit/4M-word space, big-endian words). Reads/writes are always 16-bit words.

`get_csb()` (used by GPIO special function) = `1 << ((LPC >> 20) & 3)` — i.e. which 1M-word quadrant the CPU is currently executing from, one-hot.

**Reset** (device_reset): all regs 0, `PC = read16(0xFFF7)` (bootvector base 0xFFF0 + 7), enable_irq=0, enable_fiq=0, fir_move=1, SB=0, AQ=0, FRA=0, BNK=0, INE=0, PRI=8, fiq/irq flags 0.

---

## 3. Cycle counts (as MAME implements)

- Branch taken 4 / not taken 2; JMP 4
- ALU [BP+imm6] 6; imm6 2; indirect 6 (7 if opA==PC); Rd,Rs 3 (5 if PC); Rd,imm16 4 (5); Rd,[A16] 7 (8); [A16]=Rd op Rs 7 (8); shift ops 3 (5); [imm6] 5 (6)
- PUSH/POP: 4 + 2·n; RETI 8
- CALL a22: 9; JMPF (goto a22): 5; JMPR (goto MR): 5
- MUL (all variants): 12
- INT SET / IRQ ON/OFF / FIR_MOV / NOP / BREAK group: 2 (base device); ISA1.2 additions (FRA/SECBANK/INE/CALLR/DIVQ/EXP): 1 (marked "unknown count" in MAME)
- MULS: no cycle count charged in MAME (bug/unknown)
- execute_min_cycles / execute_max_cycles both report 5.

CPU clock: 27 MHz on V.Smile (MAME passes XTAL(27'000'000) straight to SPG24X).

---

## 4. Interrupt model

Vector table (m_vectorbase default 0xFFF0):
- 0xFFF5: BREAK vector (per MAME comment; BREAK unimplemented)
- 0xFFF6: FIQ vector (base + 0x06)
- 0xFFF7: RESET vector (base + 0x07)
- 0xFFF8–0xFFFF: IRQ0–IRQ7 vectors (base + 0x08 + line)

Lines: FIQ, IRQ0..IRQ7, BRK (input line enum FIQ=0, IRQ0=1 ... IRQ7=8, BRK=9). `m_sirq` bitmask: bit0=FIQ, bits1–8=IRQ0–7. Level-triggered: `set_state_unsynced` clears the bit then sets it if state!=0.

`check_irqs()` runs after every instruction (except immediately after RETI opcode 0x9A98): finds the **lowest-numbered set bit** (FIQ highest priority, then IRQ0 > IRQ1 > ... > IRQ7).

`trigger_fiq`: requires enable_fiq && !already-in-fiq. Pushes PC then SR to [SP], PC = read16(vector 0xFFF6), SR = 0. Sets in-FIQ flag. (FIQ can interrupt an IRQ.)

`trigger_irq(line)`: blocked if (INE==0 && already-in-irq) || PRI <= line || !enable_irq. Pushes PC then SR; if INE additionally pushes FR (get_fr()) and sets PRI = line (so only strictly-higher-priority IRQs nest). PC = read16(0xFFF8 + line), SR = 0.

RETI: (if INE: FR = pop), SR = pop, PC = pop; clears in-FIQ if set else in-IRQ.

**SPG2xx IRQ line assignment** (spg2xx.cpp:105-150):
- IRQ0 ← PPU/video (vblank, position, sprite-DMA-done) — unless FIQ select == 0 (PPU) in which case video drives FIQ instead
- IRQ1 ← (not connected in MAME)
- IRQ2 ← Timer A / Timer B
- IRQ3 ← UART / SPI / SIO / I2C / ADC ("data" IRQ)
- IRQ4 ← audio beat IRQ (SPU)
- IRQ5 ← external interrupts EXT1/EXT2 (V.Smile: controller RTS lines)
- IRQ6 ← 1024/2048/4096 Hz periodic
- IRQ7 ← TMB1/TMB2/4 Hz/key-change
- FIQ ← SPU channel IRQ (audio channel FIQ), and/or video when FIQ_SEL==0. FIQ source select register (0x3D2E) values: 0=PPU, 1=SPU channel, 2=Timer A, 3=Timer B, 4=UART/SPI, 5=External, 6=Reserved, 7=None.

---

## 5. Internal memory map and SPG2xx IO registers (spg2xx.cpp internal_map; spg2xx_io.cpp/.h)

```
0x000000-0x0027FF : internal work RAM (10K words)
0x002800-0x0028FF : PPU video registers
0x002900-0x0029FF : scroll RAM (row-scroll, 256 words)
0x002A00-0x002AFF : hcomp RAM (horizontal compress; "not all models")
0x002B00-0x002BFF : palette RAM (256 x RGB555, bit15 = transparent)
0x002C00-0x002FFF : sprite RAM (256 sprites x 4 words)
0x003000-0x0031FF : audio channel regs (16ch x 16 regs)
0x003200-0x0033FF : audio phase regs (16ch x 16 regs)
0x003400-0x0037FF : audio control regs
0x003D00-0x003D2F : IO block (io_r/io_w)
0x003D30-0x003DFF : extended IO (UART/SPI/SIO/I2C)
0x003E00-0x003E03 : system DMA
0x004000-0x3FFFFF : external bus (ROMCSB/CSB1-3 per EXT_MEMORY_CTRL decode)
```

### GPIO (offsets from 0x3D00)
- 0x00 REG_IO_MODE: bit0 IOA special-function select, bit1 IOB special select, bit3 IOA wake, bit4 IOB wake
- Port A: 0x01 DATA, 0x02 BUFFER, 0x03 DIR, 0x04 ATTRIB, 0x05 MASK(special-enable)
- Port B: 0x06–0x0A (same 5-reg layout); Port C: 0x0B–0x0F
- Writes to DATA are redirected to BUFFER. Output resolution (`do_gpio`): `push=DIR; pull=~DIR; what = BUFFER & (push|pull); what ^= DIR & ~ATTRIB` (ATTRIB=0 inverts driven outputs); `what &= ~SPECIAL`; write → porta_out(what, mask = push & ~special); read → what = (what & ~pull) | (port_in() & pull); then special-function bits merged: port A bits 15:13 = CSB pins = `(get_csb() & 0x0E) << 12` (reflects current executing 1M-word bank).
- Port A special functions table (per direction/mode) includes CSB1/2/3 on bits 14/13/15..., VSYNC/HSYNC, SPI, UART TxD/RxD (bit9/10), TAPWM/TBPWM etc. (see s_pa_special, spg2xx_io.cpp:613).

### Timebase / timers
- 0x10 TIMEBASE_SETUP: bit4 selects source (0=32768 Hz, 1=27 MHz); TMB1 rate bits1:0 → {8,16,32,64 Hz} or {12k,24k,40k,40k}; TMB2 bits3:2 → {128,256,512,1024 Hz} or {105k,210k,420k,840k}. TMB1 sets IRQ-status bit0, TMB2 bit1.
- 0x11 TIMEBASE_CLEAR (resets 2k/1k/4Hz dividers)
- 0x12 TIMERA_DATA (16-bit up-counter; write sets preload; overflow → reload preload, IRQ status bit 11)
- 0x13 TIMERA_CTRL: bits2:0 Source A = {0,0,32768Hz,8192Hz,4096Hz,1,0,ExtClk1}; bits5:3 Source B divides A = {/2048,/1024,/256,TMB1,/4,/2,x1,ExtClk2}
- 0x14 TIMERA_ON, 0x15 TIMERA_IRQCLR (clears status bit 11)
- 0x16 TIMERB_DATA (preload), 0x17 TIMERB_CTRL bits2:0 Source C {0,0,32768,8192,4096,1,0,ExtClk1}, 0x18 TIMERB_ON (bit0), 0x19 TIMERB_IRQCLR (clears status bit 10). Timer B overflow sets IRQ status bit 10.
- 0x1C REG_VERT_LINE: current video scanline (read = screen vpos)

### System / IRQ
- 0x20 SYSTEM_CTRL: bit15 watchdog enable (750 ms in MAME), bit14 sleep, bits13:12 SysClk {13.5M,27M,27M-NoICE,54M}, bit11 clk invert, LVR/LVD bits 9..4, bit3 strong/weak, bits2:0 DAC disables
- 0x21 INT_CTRL (IRQ enable mask), 0x22 INT_CLEAR (status; write-1-to-clear). **Status/enable bit map**: bit0 TMB1, bit1 TMB2, bit3 4 Hz, bit4 1024 Hz, bit5 2048 Hz, bit6 4096 Hz, bit7 key change, bit8 UART, bit9 EXT1, bit10 TimerB, bit11 TimerA, bit12 EXT2, bit13 ADC, bit14 SPI. Group→CPU line: 0x0C00→IRQ2 (timers); 0x6100→IRQ3 (UART|ADC|SPI); 0x1200→IRQ5 (ext); 0x0070→IRQ6 (1k/2k/4k); 0x008B→IRQ7 (TMB1/TMB2/4Hz/key). The 4096 Hz base timer is a free-running 4096 Hz tick that sets bit6 always, bit5 at 2048 Hz, bit4 at 1024 Hz, bit3 at 4 Hz (dividers 2/2/256). UART Rx/Tx keep re-asserting status bit8 until their per-flag acknowledges in UART_STATUS are written.
- 0x23 EXT_MEMORY_CTRL (reset value 0x0028): bits2:1 wait states, bits5:3 bus arbitration, **bits7:6 ROM address decode** (0: ROMCSB=0x4000–0x3FFFFF; 1: ROMCSB=0x4000–0x1FFFFF + CSB1=0x200000–0x3FFFFF; 2/3: ROMCSB=0x4000–0xFFFFF, CSB1=0x100000–0x1FFFFF, CSB2=0x200000–0x2FFFFF, CSB3=0x300000–0x3FFFFF), bits11:8 RAM decode (0x8..0xF = 4KW@0x3FF000 ... 512KW@0x380000). Writing calls chip_select callback with (data>>6)&3 → this is the V.Smile bank-switch trigger.
- 0x24 WATCHDOG_CLEAR (write 0x55AA to kick, only if SYSTEM_CTRL bit15)
- 0x25 ADC_CTRL: bit0 ADE enable, bits3:2 conversion clock (16<<n cycles @27MHz), bits5:4 channel select, bit9 IRQ enable, bit10 Req_Auto_8K (8 kHz auto-conversion), bit12 conversion request (edge), bit13 conversion-ready/IRQ flag (write-1-to-clear; also clears IRQ status bit13). 0x26 ADC_PAD (per-channel pad enable bits0–3), 0x27 ADC_DATA: bit15 = data-ready, bits11:0 = 12-bit result.
- 0x28 SLEEP_MODE (write 0xAA55), 0x29 WAKEUP_SOURCE (bits: TMB1,TMB2,2Hz,4Hz,1024Hz,2048Hz,4096Hz,Key), 0x2A WAKEUP_TIME
- 0x2B NTSC_PAL (read: 0=NTSC 1=PAL)
- 0x2C/0x2D PRNG1/PRNG2: 15-bit LFSR, reset seeds 0x1418/0x1658; each read clocks: `new = ((v<<1) | (bit14(v)^bit13(v))) & 0x7FFF`, returns pre-clock value
- 0x2E FIQ_SEL (see §4), 0x2F DATA_SEGMENT (read/write CPU DS field directly)

### UART (0x3D30–0x3D37)
- 0x30 UART_CTRL: bit7 TxEn, bit6 RxEn, bit5 9-bit mode, bit4 multiproc, bits3:2 9th-bit mode {0,1,Odd,Even}, bit1 TxIntEn, bit0 RxIntEn
- 0x31 UART_STATUS: bit0 RxRDY (w1c ack), bit1 TxRDY, bit6 TxBusy, bit7 RBF; ack of both Rx and Tx flags clears IRQ status bit8
- 0x32 UART_RESET; 0x33/0x34 BAUD1(lo)/BAUD2(hi): baud = 27,000,000 / (16 × (65536 − ((BAUD2<<8)|BAUD1))). **SPG28X difference (V.Smile Baby)**: baud = 27,000,000 / (65536 − BAUD1) (no ×16, only BAUD1).
- 0x35 TXBUF (write starts tx if TxEn, one frame = 10 bits, 11 in 9-bit mode; on completion sets TxRDY, clears TxBusy, raises IRQ bit8 if TxIntEn)
- 0x36 RXBUF (read pops 8-deep FIFO; sets underrun 0x2000 in RXFIFO if empty)
- 0x37 RXFIFO ctrl: bit15 reset, bit14 overrun, bit13 underrun, bits6:4 count, bits2:0 threshold.
Rx path: incoming byte enqueued only if RxEn; a byte "arrives" (RBF/RxRDY set, IRQ if RxIntEn) after one frame time at current baud.

### SPI 0x3D40–0x3D45 (ctrl/txstatus/txdata/rxstatus/rxdata/misc; 16-deep FIFOs, IRQ status bit14), SIO 0x3D50–0x3D55, I2C 0x3D58–0x3D5F (CMD bit6=read; addr = ((I2C_ADDR&6)<<7)|subaddr-low-byte), 0x3D60 regulator, 0x3D61 clock ctrl, 0x3D62 IO drive. None used meaningfully by V.Smile.

### System DMA (0x3E00–0x3E03, spg2xx_sysdma.cpp)
0: source low; 1: source high (6 bits → 22-bit source); 2: length (write triggers DMA; reads back 0; writes with bits 0xC000 set are ignored); 3: destination (& 0x3FFF, i.e. into internal RAM only). Copies len words from mem[src+j] to mem[(dst+j)&0x3FFF]. Afterwards: source regs advance by len, dest = (dst+len)&0x3FFF, length reg = 0. No IRQ.

---

## 6. SPG2xx video (spg2xx_video.cpp + spg_renderer.cpp)

Register offsets relative to 0x2800 (all 16-bit):

- 0x10/0x11 Page1 X scroll (9 bits) / Y scroll (8 bits); 0x16/0x17 Page2 scroll
- 0x12 Page1 attr, 0x13 Page1 ctrl; 0x18/0x19 Page2 attr/ctrl
  - **attr**: bits1:0 bpp code (bpp = 2*(n+1) → 2/4/6/8bpp), bit2 FlipX, bit3 FlipY, bits5:4 tile width (8<<n px), bits7:6 tile height (8<<n px), bits11:8 palette bank (palette_offset = (attr&0x0F00)>>4, then rounded down to a multiple of 2^bpp: `off >>= nc_bpp; off <<= nc_bpp`), bits13:12 depth/priority (0–3)
  - **ctrl**: bit0 bitmap/linemap mode, bit1 RegSet (1 = use attr register for flip/pal/blend; 0 = read per-tile extended attribute bytes), bit2 wallpaper (use word at tilemap base for every tile), bit3 page enable, bit4 row scroll enable, bit5 Hcmp (horizontal compress; not implemented in MAME renderer), bit6 Ycmp (vertical compression), bit7 HiColor (linemap direct-RGB mode), bit8 blend enable
- 0x14/0x15 Page1 tile-map RAM base / attribute-map RAM base (word address in CPU space); 0x1A/0x1B for Page2
- 0x1C Ycmp_Value (0x20 = no scale), 0x1D Ycmp_Y_Offset, 0x1E Ycmp_Step (signed 8-bit) — build a per-scanline source-line table: lines < offset are blank; line advances when accumulated (value<<4) counter passes 0x200, step added to increment each advance
- 0x20 Page1 segment (tile gfx base = reg × 0x40 words), 0x21 Page2 segment, 0x22 Sprite segment (× 0x40)
- 0x2A blend level (2 bits): alpha = {8,16,24,32}/32 = 25/50/75/100%
- 0x30 fade effect (8 bits, subtracts from each RGB888 channel post-conversion)
- 0x36 IRQ pos V (9 bits), 0x37 IRQ pos H (9 bits): scanline IRQ fires at (V, H×2) screen position when enabled (only if H<160 && V<240; re-arms each frame)
- 0x38 current line (read-only = vpos)
- 0x39 latch 1st line pen pulse (bit0)
- 0x3C TV control 1: hue(hi byte)/saturation(lo byte), default 0x0020 (=neutral); saturation adjust factor = (0xFF − sat)/(0xFF − 0x20) applied around luma (0.299/0.587/0.114)
- 0x3D TV control 2: bits3:2 LPF mode {LPF1,LPF2,All,Edge}, bit1 enable, bit0 interlace
- 0x3E/0x3F light-pen / lightgun Y/X (read from callbacks)
- 0x42 sprite control: bit0 sprite enable, bit1 coordinate mode (1 = top-left origin; 0 = centered: x = 160 + x − w/2, y = (128 − y) − h/2, i.e. Y-up center origin), default 0x0001
- 0x62 Video IRQ enable / 0x63 Video IRQ status (w1c): bit0 vblank, bit1 position (timing), bit2 sprite-DMA complete. IRQ0 asserted while (status & enable) != 0.
- 0x70 sprite DMA source (&0x3FFF), 0x71 sprite DMA dest (&0x03FF into sprite RAM), 0x72 sprite DMA length: write triggers immediate copy (len = data&0x3FF, 0 → 0x400); writes beyond sprite RAM bounds discarded; sets status bit2 (if enabled) and clears reg 0x72 to 0.
- Reset values: regs zeroed; 0x36 = 0x36? no — 0x36 = 0xFFFF and 0x37 = 0xFFFF (IRQ pos disabled), renderer defaults 0x3C = 0x0020, 0x42 = 0x0001.

**Rendering model** (spg_renderer.cpp): For each scanline, for priority 0..3: draw page1, page2, then sprites of that priority (later draws overwrite → sprites above tiles at same depth, page2 above page1). Line buffer is RGB555 with 0x8000 = transparent. Palette entries with bit15 set are transparent. Blending is 5-bit-per-channel lerp: `((32−a)*bottom + a*top) >> 5` (only blends onto opaque pixels).

**Tilemap**: virtual size 512×256 px (tile_count_x = 512/tile_w; y mask 0xFF). tile index word read from `tilemap_rambase + realx0 + tile_count_x*y0`; tile 0 = skip (transparent). Tile gfx data address = `segment*0x40 + words_per_tile*tile + bits_per_row*row` where bits_per_row = bpp*tile_w/16 words, words_per_tile = bits_per_row*tile_h. Pixels are packed MSB-first **after byte-swapping each word** (`b = (b<<8)|(b>>8)` before shifting) — i.e. pixel bitstream is little-endian-byte order within the word. Row scroll: if ctrl bit4, `xscroll += (int16)scrollram[(scanline + yscroll) & 0xFF]`.

**Per-tile extended attributes** (when ctrl bit1==0): packed bytes at `attribute_map_base + tile_address/2`, low byte = even column: `- b f f p p p p` → bits3:0 palette (attr bits 11:8), bits5:4 flip (attr bits 3:2), bit6 blend (ctrl bit8).

**Linemap/bitmap mode** (ctrl bit0): per scanline: `tile = word[tilemap_base + line]`, palette byte from `palette_map_base + line/2` (odd line = high byte); source = tile | (palbyte<<16). If HiColor (ctrl bit7): 320 words read directly as RGB555 (bit15 transparency); else bpp pixels decoded as in tiles, 320 pixels.

**Sprites**: 256 entries × 4 words at 0x2C00: word0 tile number (0 = disabled), word1 X, word2 Y, word3 attr: bits1:0 bpp code, bit2 flipX, bit3 flipY, bits5:4 width (8<<n), bits7:6 height, bits11:8 palette bank, bits13:12 priority, bit14 blend. Sprite gfx base = reg0x22 × 0x40; per-sprite data address = base + words_per_tile × tile. Coordinates masked to 9 bits (0x1FF); screen 320×240 (renderer uses screenheight 256 for wrap handling).

**Post-processing**: per-pixel RGB555→RGB888 lookup with saturation (reg 0x3C low byte) and fade (reg 0x30) baked into a 32K-entry LUT.

Screen timing (vsmile.cpp): 60 Hz, 320×262 total, visible (0,0)-(319,239). VBlank callback drives the vblank IRQ bit.

---

## 7. SPG2xx audio (spg2xx_audio.cpp/.h)

16 channels. Output sample rate 281250/4 = **70312.5 Hz**. Beat timer ticks at the same 70312.5 Hz.

### Per-channel registers 0x3000 + ch*16 + n:
- 0x0 WAVE_ADDR (low 16 of wave address, word address in CPU space)
- 0x1 MODE: bits5:0 wave addr high (bits21:16), bits11:6 loop addr high, bits13:12 tone mode (0=SW channel (data written via WAVE_DATA), 1=one-shot, 2=loop), bit14 16-bit PCM (else 8-bit), bit15 ADPCM (IMA)
- 0x2 LOOP_ADDR (low 16)
- 0x3 PAN_VOL: bits6:0 volume (0–127), bits14:8 pan (0–127; pan<0x40: left=0x7F*vol, right=pan*2*vol; else left=(0x7F−pan)*2*vol, right=0x7F*vol; contribution = (sample*pan_x)>>14)
- 0x4 ENVELOPE0: bits6:0 increment, bit7 sign (1=decrement), bits14:8 target, bit15 repeat-period
- 0x5 ENVELOPE_DATA: bits6:0 EDD (current envelope level, multiplies sample: `(sample*edd)>>7`), bits15:8 count
- 0x6 ENVELOPE1: bits7:0 load value (count reload), bit8 repeat enable, bits15:9 repeat count
- 0x7 ENVELOPE_ADDR_HIGH: bits5:0 envelope addr high, bit6 IRQ enable, bits15:7 IRQ addr (channel envelope IRQ — not hooked up in MAME beyond storage)
- 0x8 ENVELOPE_ADDR (low), 0x9 WAVE_DATA_PREV (previous sample, used for interpolation), 0xA ENVELOPE_LOOP_CTRL: bits8:0 EA offset, bits15:9 rampdown offset; 0xB WAVE_DATA (current decoded sample, biased unsigned: stored ^0x8000), 0xD ADPCM_SEL: bit15 ADPCM36 mode, bits14:9 point number.

### Per-channel phase registers 0x3200 + ch*16 + n:
- 0x0 PHASE_HIGH (3 bits), 0x1 PHASE_ACCUM_HIGH, 0x2 TARGET_PHASE_HIGH, 0x3 RAMP_DOWN_CLOCK (3 bits), 0x4 PHASE (low 16), 0x5 PHASE_ACCUM, 0x6 TARGET_PHASE, 0x7 PHASE_CTRL: bits11:0 offset, bit12 sign, bits15:13 time step.
- **Sample rate formula**: `rate_hz = phase19bit * 140625.0 * 2.0 / 2^19` (phase = (PHASE_HIGH<<16)|PHASE). Channel advances when accumulated rate ≥ 70312.5 per output sample. Automatic pitch-bend (target phase / phase ctrl) not implemented in MAME.

### Sample fetch/decoding:
- 16-bit: word at wave addr; addr++ per sample. Value 0xFFFF = end marker → one-shot: stop channel (sets CHANNEL_STOP bit); loop: wave addr = loop addr.
- 8-bit: two samples per word, low byte first (shift 0 then 8); sample = byte replicated to 16 bits (`raw |= raw >> 8` after masking); 0xFFFF (both bytes 0xFF) = end marker.
- ADPCM (bit15 of MODE): 4-bit IMA-ADPCM nybbles, low nybble first, 4 per word; decoded via standard IMA state (MAME ima_adpcm_state), output ^0x8000; end marker = word 0xFFFF; **on loop the ADPCM bit is cleared** (continues as PCM — hardware quirk).
- ADPCM36 (bit15 of ADPCM_SEL): SunPlus block format: 1 header word then 8 data words (16 nybbles); header: bits3:0 = right-shift, bits9:4 = filter (6-bit signed f0, f1 unused/0 in MAME): `s = (nybble<<12 >> shift) + ((prev0*f0 + prev1*f1 + 32) >> 12)`; commented filter-pair table in source {0,0},{60,0},{115,-52},{98,-55},{122,-60}....
- Interpolation: unless AUDIO_CONTROL bit9 (NOINT), output lerps WAVE_DATA_PREV→WAVE_DATA by phase fraction.
- Samples are stored/processed as (signed value ^ 0x8000) biased words in WAVE_DATA.

### Control registers 0x3400 + n:
- 0x0 CHANNEL_ENABLE (start channel on 0→1 if not stopped), 0x1 MAIN_VOLUME (7 bits; final = (total * mainvol) >> 7)
- 0x2 CHANNEL_FIQ_ENABLE, 0x3 CHANNEL_FIQ_STATUS (w1c): per-channel timer at channel rate raises FIQ (audio channel IRQ → CPU FIQ line)
- 0x4 BEAT_BASE_COUNT (11 bits), 0x5 BEAT_COUNT: bits13:0 count, bit14 BIS (beat IRQ status, w1c), bit15 BIE (enable). Beat tick at 70312.5 Hz decrements base counter; on expiry reloads BASE_COUNT and decrements BEAT_COUNT; when it hits 0 and BIE → BIS set, IRQ4 asserted while BIS&&BIE.
- 0x6/0x7 ENVCLK0 lo/hi, 0x8/0x9 ENVCLK1 lo/hi: 4 bits per channel envelope clock select → frame counts {4,8,16,32,64,128,256,512,1024,2048,4096,8192,...} output frames per envelope tick
- 0xA ENV_RAMP_DOWN (per-channel fast ramp-down trigger; masked by CHANNEL_STATUS); rampdown frame counts from RAMP_DOWN_CLOCK: 13×{4,16,64,256,1024,4096,8192,8192}; each tick subtracts rampdown_offset from EDD; stops channel at 0
- 0xB CHANNEL_STOP (w1c; writing 1 clears stop and restarts if enabled), 0xC CHANNEL_ZERO_CROSS, 0xD AUDIO_CONTROL: bit15 saturate, bit12 softch, bit11 compen, bit10 nohigh, bit9 noint(erpolation), bit8 EQ enable, bits7:6 volume select (0: >>4, 1: >>2, 2: x1, 3: x2 — MAME hacks 1-3 to >>2), bit5 FOF, bit3 init
- 0xE COMPRESS_CTRL (compressor: peak/threshold/attack/release/ratio — unimplemented), 0xF CHANNEL_STATUS (read-only playing mask)
- 0x10/0x11 WAVE_IN_L / WAVE_IN_R (software channel FIFO; MAME just adds (val − 0x8000) into the mix), 0x12/0x13 WAVE_OUT_L/R
- 0x14 CHANNEL_REPEAT (reset 0x3F), 0x15 CHANNEL_ENV_MODE (reset 0x3F; bit=1 manual envelope i.e. auto-envelope disabled), 0x16 CHANNEL_TONE_RELEASE, 0x17 CHANNEL_ENV_IRQ (w1c), 0x18 CHANNEL_PITCH_BEND enable, 0x19 SOFT_PHASE, 0x1A ATTACK_RELEASE, 0x1B–0x1E EQ cutoffs/gains.

### Envelope engine (auto mode, per envclk frames): count-down count; at 0: EDD steps by inc toward target (sign bit selects direction; clamps; reaching 0 while decrementing stops the channel); at target: if repeat bit, dec repeat count and on 0 reloads ENVELOPE0/1/LOOP_CTRL from wave memory at envelope_addr and adds eaoffset; else loads next ENVELOPE0/1 pair from envelope_addr and advances by 2. Channel start latches envelope_addr from regs and count=load.

---

## 8. V.Smile driver specifics (src/mame/vtech/vsmile.cpp/.h, vsmileb.cpp, src/devices/bus/vsmile/*)

### Machine config
- vsmile / vsmilem: SPG24X @ 27 MHz (256 sprites), NTSC 60 Hz 320×262 (visible 320×240); vsmilep/vsmilebp = PAL flag set. vsmileb (Baby): SPG28X (64 sprites).
- CPU program space 0x000000–0x3FFFFF routed through a 4M-word banking device (`address_map_bank`, stride 0x400000 words, big-endian, 16-bit).

### Cartridge / sysrom banking
- `chip_sel_w` receives (EXT_MEMORY_CTRL >> 6) & 3 from the SoC (write to 0x3D23): value 0 → bank 0, 1 → bank 1, 2 or 3 → bank 2; **+4 offset if a cartridge is present** (cart pulls a line). Cache invalidated on switch.
- Banked map (each bank = 4M words = 0x400000):
  - Banks 0–2 (no cart): sysrom (1M words) mirrored ×4 within each bank.
  - Bank 4 (cart, decode 0): 0x1000000–0x13FFFFF = cart bank0 (offsets 0–0xFFFFF words ×4? actually full 4M mapped to cart bank0_r, offset masked by handler).
  - Bank 5 (decode 1): 0x1400000–0x15FFFFF cart bank0; 0x1600000–0x17FFFFF cart **bank2** (this is where NVRAM carts map their 2M-word NVRAM).
  - Bank 6 (decode 2/3): 0x1800000–0x18FFFFF cart bank0; 0x1900000–0x19FFFFF cart bank1; 0x1A00000–0x1AFFFFF cart bank2; 0x1B00000–0x1BFFFFF **sysrom** (so the BIOS is still reachable at CPU 0x300000–0x3FFFFF in full-decode mode).
- Cart ROM device (rom.h): bank0_r = rom[bank_offset + 0x000000 + off]; bank1_r = +0x100000; bank2_r = +0x200000; bank3_r = +0x300000 (word offsets). **CS2 bank switching**: `set_cs2(bool)` sets bank_offset = 0x400000 words (8 MB) — driven by **GPIO port B bit1** (`portb_w`: cs2 = !bit1), used by carts >8MB. NVRAM carts: bank2 reads/writes go to a 0x20'0000-byte (1M-word) battery NVRAM instead of ROM. Max cart size 16MB.
- sysrom images are 0x200000 bytes (1M words) loaded ROM_GROUPWORD | ROM_REVERSE (byte-swapped 16-bit words); vsmileb BIOS is 0x800000 bytes.

### GPIO usage
- **Port B** (in): bit0 extra cart address line/CS1, bit1 cart-ROM enable (out→CS2), bit2 internal-ROM enable, bit3 restart jumper, bit4 ADC?, bit5 voltage detect, bit6 ON button (active low), bit7 OFF button (active low). (Driving RESET/ON/OFF low triggers the BIOS test screen.)
- **Port C**: read: bits3:0 = region code, bit4 = VTech intro on/off, bit5 = test point (always 1), bit10 = ctrl1 RTS (0 when asserted), bit12 = ctrl2 RTS (0 when asserted), bit13 = 0 only when both RTS asserted; write: bit8 = controller-1 select/CTS, bit9 = controller-2 select/CTS.
- **Region codes** (port C low nibble, vsmile): 0x02 Italian, 0x07 Chinese, 0x08 Portuguese, 0x09 Dutch, 0x0B German, 0x0C Spanish, 0x0D French, 0x0E UK English, 0x0F US English. vsmilem uses a different set (0x05/0x06/0x0F English variants, 0x08 Mexico, 0x09 NL, 0x0B DE, 0x0C ES, 0x0D FR, 0x07 CN, 0x02 IT).
- **vsmilem** additionally: port A read returns 0xC000 (bits 15:14 set) — motion-related, protocol undumped/unknown.

### Controller serial protocol (vsmile_ctrl.cpp + pad.cpp)
Physical: each controller has a data line into the console UART (console RX shared by both controllers), a data line out (console TX broadcast to both), an RTS line per controller (→ EXTINT1/2 + port C status bits), and a CTS/select per controller (← port C bits 8/9). Effective byte rate modeled as 9600 baud / 10 bits = 960 bytes/s.

Handshake: controller queues bytes in an 8-deep FIFO; when FIFO becomes non-empty it raises RTS; it only shifts bytes while CTS(select) is high; if not selected within **500 ms** the FIFO is flushed and a 0x55 keep-alive is queued (tx_timeout). Console asserts select to let it transmit. When FIFO drains, RTS drops.

**Controller → console bytes (joystick pad)**:
- 0x55: keep-alive / idle (sent after 1 s of inactivity, and on timeout)
- Vertical: 0x87 = up, 0x8F = down, 0x80 = released
- Horizontal: 0xCF = left, 0xC7 = right, 0xC0 = released
- Color buttons: 0x90 | bits (bit0 Green, bit1 Blue, bit2 Yellow, bit3 Red)
- Function buttons: 0xA1 OK, 0xA2 Quit, 0xA3 Help, 0xA4 ABC, 0xA0 = all released
- Probe response: 0xB0 | nibble (see below)

**Console → controller bytes**:
- Probe/handshake: bytes 0x70–0x7F and 0xB0–0xBF. Controller keeps a 2-byte history: on 0x7x, history[0]=0, history[1]=byte; on 0xBx, history[0]=previous history[1], history[1]=byte. Response = `0xB0 | (((history[0] + history[1] + 0x0F) & 0x0F) ^ 0x05)`. (Console sends 0x7x then a 0xBx sequence; controller must answer each with the rolling checksum nibble.)
- LED control: 0x60 | bits — bit3 red, bit2 yellow, bit1 blue, bit0 green (applied by controller when selected).
After a completed transmission burst the pad refreshes all "stale" inputs (full state dump: vertical, horizontal, colors, buttons) — that is how the console re-syncs after probing.

**Gym mat** (mat.cpp): same protocol, different meanings: 0xCB yellow, 0xCD right, 0xC0 none; 0x8B red, 0x8D left, 0x80 none; 0x90|bits for center/up/down/green; 0xA1 OK, 0xA2 Quit, 0xA3 Help, 0xA4 Blue.

**Smartbook keyboard** (keyboard.cpp): power-up: raises RTS for 12.2 ms every 300 ms until selected; then sends 0x52 0x52 0x52; expects console reply sequence 0x02, 0x02, then 0xE6, 0xD6, 0x60; then sends layout ID byte (0x40 US, 0x42 FR, 0x44 GE) and enters running state (2400 Hz scan). Key make = translated code (5×16 matrix table at keyboard.cpp:104), key break = code | 0xC0 (shift make=0xA9, break=0xAA); joystick arrows: up 0x87/down 0x8F/0x80, left 0x7F/right 0x77/neutral 0x70; buttons 0xA1 OK, 0xA2 Quit, 0xA3 Help, 0xA0 release; probe protocol same as pad.

### V.Smile Baby (vsmileb.cpp)
No serial controllers; buttons feed the UART directly with 2 bytes (high, low) of `mode | code`; release sends `mode | 0x0080`. Mode (3-position switch): 0x0400 Play Time, 0x0800 Watch & Learn, 0x0C00 Learn & Explore. Codes: Yellow 0x01FE, Blue 0x03EE, Orange 0x03DE, Green 0x03BE, Red 0x02FE, Cloud 0x03F6, Ball 0x03FA, Exit 0x03FC. porta_r returns 0x0302 | (logo jumper ? 0x0080 : 0); portb_r returns 0x0080. Banked map: sysrom is 4M words mapped at banks 0–2 (0x0000000/0x0400000/0x0800000), cart layout same as vsmile.

### Differences vsmile / vsmilem / vsmileb
- vsmile: SPG24X, 3 BIOS versions (v100/v102/v103, 2MB each), pad/mat/keyboard controllers.
- vsmilem (Motion, 2008): same platform + port A returns 0xC000; separate BIOS (vsmilemotion.bin / vmotionbios.bin); motion controller not emulated (uses vsmilem_cart list; vsmile also lists vsmilem_cart as compatible).
- vsmileb (Baby, 2005): SPG28X (64 sprites, different UART baud formula), 8MB BIOS, direct-UART button pad, marked NOT_WORKING in MAME due to audio "fast rampdown" cutting off voice clips (disabling rampdown fixes audio — known emulation issue to watch for).

---

## Key implementation notes for D-Smile
1. MAME forces the interpreter (no DRC) for V.Smile (`set_force_no_drc(true)`), and SPG2xx video timing depends on `screen->vpos()` — implement a scanline-based loop (262 lines, 60 Hz; ~1718 CPU cycles/line at 27 MHz).
2. Word addressing everywhere: addresses are 16-bit word offsets; ROM files are byte streams needing 16-bit byte-swap on load (ROM_GROUPWORD|ROM_REVERSE).
3. IRQ checking happens after every instruction; RETI shadows one instruction.
4. Palette bit15 transparency, tile 0 skip, and per-word byte-swapped pixel bitstream are essential for correct graphics.
5. The BIOS probes controllers with the 0x7x/0xBx checksum handshake before accepting input; implement it exactly or the pad will be ignored.
