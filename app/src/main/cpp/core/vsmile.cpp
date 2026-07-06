#include "vsmile.h"

#include <algorithm>

#include "state.h"

namespace dsmile {

namespace {
constexpr u32 kStateMagic = 0x44534D53;  // "DSMS"
constexpr u32 kStateVersion = 1;

u32 Fnv1a(const u8* data, size_t n) {
  u32 h = 2166136261u;
  for (size_t i = 0; i < n; i++) { h ^= data[i]; h *= 16777619u; }
  return h;
}
}  // namespace

// ---------------- VSmileJoy ----------------

void VSmileJoy::Reset() {
  fifo_len_ = fifo_head_ = 0;
  rts_idle_ = true;
  cts_ = false;
  active_ = false;
  leds_ = 0;
  probe_history_[0] = probe_history_[1] = 0;
  joy_x_ = joy_y_ = 0;
  buttons_ = 0;
  idle_counter_ = kIdlePeriod;
  rts_timeout_ = -1;
  tx_counter_ = -1;
}

void VSmileJoy::SetRtsActive(bool active) {
  if (rts_idle_ != !active) return;
  rts_idle_ = !active;
  if (active) machine_.Soc().RaiseExtIrq(0);  // falling edge -> EXT1
}

void VSmileJoy::QueueByte(u8 b) {
  if (fifo_len_ >= 16) return;
  fifo_[(fifo_head_ + fifo_len_) & 15] = b;
  fifo_len_++;
  if (fifo_len_ == 1) {
    SetRtsActive(true);
    if (cts_) {
      if (tx_counter_ < 0) tx_counter_ = kTxStartDelay;
    } else {
      rts_timeout_ = kRtsTimeout;
    }
  }
}

void VSmileJoy::SendNext() {
  if (!cts_ || fifo_len_ == 0) {
    tx_counter_ = -1;
    return;
  }
  const u8 b = fifo_[fifo_head_];
  fifo_head_ = (fifo_head_ + 1) & 15;
  fifo_len_--;
  machine_.Soc().UartRxStart(b);
  active_ = true;
  idle_counter_ = kIdlePeriod;
  if (fifo_len_ > 0) {
    tx_counter_ = kInterByte;
  } else {
    tx_counter_ = -1;
    SetRtsActive(false);
    rts_timeout_ = -1;
  }
}

void VSmileJoy::SetCts(bool state) {
  if (cts_ == state) return;
  cts_ = state;
  if (cts_ && fifo_len_ > 0 && tx_counter_ < 0) {
    tx_counter_ = kTxStartDelay;
    rts_timeout_ = -1;
  }
}

void VSmileJoy::Rx(u8 byte) {
  const u8 hi = byte & 0xF0;
  if (hi == 0x60) {
    leds_ = byte & 0x0F;
  } else if (hi == 0x70) {
    probe_history_[0] = 0;
    probe_history_[1] = byte & 0x0F;
    QueueByte(0xB0 | (((0u - probe_history_[0] - probe_history_[1]) ^ 0xA) & 0xF));
  } else if (hi == 0xB0) {
    probe_history_[0] = probe_history_[1];
    probe_history_[1] = byte & 0x0F;
    QueueByte(0xB0 | (((0u - probe_history_[0] - probe_history_[1]) ^ 0xA) & 0xF));
  }
}

void VSmileJoy::UpdateInput(int joy_x, int joy_y, u32 buttons) {
  joy_x = std::max(-5, std::min(5, joy_x));
  joy_y = std::max(-5, std::min(5, joy_y));

  if (joy_x != joy_x_) {
    joy_x_ = joy_x;
    u8 b = 0xC0;
    if (joy_x > 0) b = 0xC3 + (joy_x - 1);
    else if (joy_x < 0) b = 0xCB + (-joy_x - 1);
    QueueByte(b);
  }
  if (joy_y != joy_y_) {
    joy_y_ = joy_y;
    u8 b = 0x80;
    if (joy_y > 0) b = 0x83 + (joy_y - 1);
    else if (joy_y < 0) b = 0x8B + (-joy_y - 1);
    QueueByte(b);
  }

  const u32 old_colors = (buttons_ >> 4) & 0xF;
  const u32 new_colors = (buttons >> 4) & 0xF;
  if (old_colors != new_colors) {
    // Protocol color bit order: bit0 green, bit1 blue, bit2 yellow, bit3 red.
    u8 b = 0x90;
    if (buttons & (1u << 7)) b |= 1;  // green
    if (buttons & (1u << 6)) b |= 2;  // blue
    if (buttons & (1u << 5)) b |= 4;  // yellow
    if (buttons & (1u << 4)) b |= 8;  // red
    QueueByte(b);
  }

  const u32 old_fn = buttons_ & 0xF;
  const u32 new_fn = buttons & 0xF;
  if (old_fn != new_fn) {
    u8 b = 0xA0;
    if (buttons & 1) b = 0xA1;        // enter/OK
    else if (buttons & 2) b = 0xA2;   // back/exit
    else if (buttons & 4) b = 0xA3;   // help
    else if (buttons & 8) b = 0xA4;   // abc / learning zone
    QueueByte(b);
  }

  buttons_ = buttons;
}

void VSmileJoy::RunCycles(int cycles) {
  if (tx_counter_ >= 0) {
    tx_counter_ -= cycles;
    if (tx_counter_ < 0) SendNext();
  }
  if (rts_timeout_ >= 0) {
    rts_timeout_ -= cycles;
    if (rts_timeout_ < 0) {
      // Console never granted: flush and go idle, then re-request with 0x55.
      fifo_len_ = fifo_head_ = 0;
      active_ = false;
      SetRtsActive(false);
      QueueByte(0x55);
    }
  }
  idle_counter_ -= cycles;
  if (idle_counter_ <= 0) {
    idle_counter_ = kIdlePeriod;
    QueueByte(0x55);
  }
}

void VSmileJoy::SaveState(StateWriter& w) const {
  w.Arr8(fifo_, 16);
  w.U32((u32)fifo_len_); w.U32((u32)fifo_head_);
  w.B(rts_idle_); w.B(cts_); w.B(active_);
  w.U8(leds_);
  w.Arr8(probe_history_, 2);
  w.U32((u32)joy_x_); w.U32((u32)joy_y_);
  w.U32(buttons_);
  w.S64(idle_counter_); w.S64(rts_timeout_); w.S64(tx_counter_);
}

void VSmileJoy::LoadState(StateReader& r) {
  r.Arr8(fifo_, 16);
  fifo_len_ = (int)r.U32(); fifo_head_ = (int)r.U32();
  rts_idle_ = r.B(); cts_ = r.B(); active_ = r.B();
  leds_ = r.U8();
  r.Arr8(probe_history_, 2);
  joy_x_ = (int)r.U32(); joy_y_ = (int)r.U32();
  buttons_ = r.U32();
  idle_counter_ = r.S64(); rts_timeout_ = r.S64(); tx_counter_ = r.S64();
}

// ---------------- VSmile ----------------

VSmile::VSmile() : spg_(*this), joy_(*this) {
  MakeDummySysrom();
}

void VSmile::MakeDummySysrom() {
  sysrom_.assign(0x100000, 0);
  // Stuff the sysrom entry-point vector area so games that call into the BIOS
  // land on harmless zero-filled memory (same trick as veesem).
  for (u32 i = 0xFFFC0; i < 0xFFFDC; i += 2) sysrom_[i + 1] = 0x0031;
  spg_.SetSysrom(sysrom_.data());
}

bool VSmile::LoadCart(const u8* data, size_t size_bytes) {
  const size_t words = size_bytes / 2;
  if (words == 0 || words > 0x800000) return false;
  size_t cap = 0x400000;  // at least 4M words (8 MiB)
  while (cap < words) cap <<= 1;
  cart_.assign(cap, 0);
  for (size_t i = 0; i < words; i++) {
    cart_[i] = (u16)(data[i * 2] | (data[i * 2 + 1] << 8));  // little-endian words
  }
  spg_.SetCart(cart_.data(), (u32)cap - 1, nullptr);
  cart_checksum_ = Fnv1a(data, std::min<size_t>(size_bytes, 0x10000));
  return true;
}

void VSmile::SaveState(std::vector<u8>& out) const {
  out.clear();
  StateWriter w(out);
  w.U32(kStateMagic);
  w.U32(kStateVersion);
  w.U32(cart_checksum_);
  spg_.SaveState(w);
  joy_.SaveState(w);
  w.B(cts0_); w.B(cts1_);
  w.B(on_); w.B(off_); w.B(restart_);
  w.U32((u32)auto_on_frames_);
}

bool VSmile::LoadState(const u8* data, size_t size) {
  StateReader r(data, size);
  if (r.U32() != kStateMagic) return false;
  if (r.U32() != kStateVersion) return false;
  if (r.U32() != cart_checksum_) return false;
  spg_.LoadState(r);
  joy_.LoadState(r);
  cts0_ = r.B(); cts1_ = r.B();
  on_ = r.B(); off_ = r.B(); restart_ = r.B();
  auto_on_frames_ = (int)r.U32();
  return r.ok;
}

void VSmile::LoadSysrom(const u8* data, size_t size_bytes) {
  const size_t words = std::min<size_t>(size_bytes / 2, 0x100000);
  sysrom_.assign(0x100000, 0);
  for (size_t i = 0; i < words; i++) {
    sysrom_[i] = (u16)(data[i * 2] | (data[i * 2 + 1] << 8));
  }
  spg_.SetSysrom(sysrom_.data());
}

void VSmile::Reset(bool pal) {
  cts0_ = cts1_ = false;
  on_ = off_ = restart_ = false;
  auto_on_frames_ = 20;  // hold the power button through early boot polling
  joy_.Reset();
  spg_.Reset(pal);
}

void VSmile::RunFrame() {
  spg_.RunFrame();
  if (auto_on_frames_ > 0) auto_on_frames_--;
}

u16 VSmile::GpioIn(int port) {
  switch (port) {
    case 0:
      return 0;
    case 1: {
      // Active-low console buttons: bit3 restart, bit6 ON, bit7 OFF.
      u16 v = 0x00C8;
      if (restart_) v &= (u16)~0x0008;
      if (on_ || auto_on_frames_ > 0) v &= (u16)~0x0040;
      if (off_) v &= (u16)~0x0080;
      return v;
    }
    default: {
      u16 v = (u16)(region_ & 0xF);
      if (vtech_logo_) v |= 0x0010;
      v |= 0x0020;
      if (cts0_) v |= 0x0100;
      if (cts1_) v |= 0x0200;
      if (joy_.RtsIdle()) v |= 0x0400;  // controller 1 RTS (1 = idle)
      v |= 0x1000;                      // controller 2 RTS always idle
      v |= 0x6000;
      return v;
    }
  }
}

void VSmile::GpioOut(int port, u16 data, u16 mask) {
  if (port == 1) {
    if (mask & 0x0002) spg_.SetCs2(!(data & 0x0002));
  } else if (port == 2) {
    if (mask & 0x0100) {
      cts0_ = data & 0x0100;
      joy_.SetCts(cts0_);
    }
    if (mask & 0x0200) cts1_ = data & 0x0200;
  }
}

void VSmile::UartTx(u8 byte) {
  if (cts0_) joy_.Rx(byte);
}

u16 VSmile::AdcIn(int ch) {
  return ch == 1 ? 0x03FF : 0;  // full battery
}

void VSmile::RunCycles(int cycles) {
  joy_.RunCycles(cycles);
}

}  // namespace dsmile
