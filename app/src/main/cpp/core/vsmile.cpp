#include "vsmile.h"

#include <algorithm>

#include "state.h"

namespace dsmile {

namespace {
constexpr u32 kStateMagic = 0x44534D53;  // "DSMS"
constexpr u32 kStateVersion = 2;

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
  tx_busy_ = false;
  tx_starting_ = false;
  active_ = false;
  leds_ = 0;
  probe_history_[0] = probe_history_[1] = 0;
  cur_x_ = cur_y_ = 0;
  cur_buttons_ = 0;
  sent_x_ = sent_y_ = 0;
  sent_buttons_ = 0;
  input_dirty_ = false;
  dump_pending_ = false;
  idle_counter_ = kIdlePeriod;
  rts_timeout_ = kRtsTimeout;
  tx_start_counter_ = kTxStartDelay;
}

void VSmileJoy::SetRtsActive(bool active) {
  if (rts_idle_ == !active) return;  // no change
  rts_idle_ = !active;
  if (active) machine_.Soc().RaiseExtIrq(0);  // falling edge -> EXT1
}

void VSmileJoy::QueueTx(u8 b) {
  DTRACE("[joy] queue %02x (cts=%d busy=%d)\n", b, cts_, tx_busy_);
  if (fifo_len_ >= 16) return;
  const bool was_empty = fifo_len_ == 0;
  fifo_[(fifo_head_ + fifo_len_) & 15] = b;
  fifo_len_++;
  if (was_empty) {
    SetRtsActive(true);
    if (cts_) {
      if (!tx_busy_ && !tx_starting_) {
        tx_starting_ = true;
        tx_start_counter_ = kTxStartDelay;
      }
    } else {
      rts_timeout_ = kRtsTimeout;
    }
  }
  idle_counter_ = kIdlePeriod;
}

void VSmileJoy::StartTx() {
  if (tx_busy_ || fifo_len_ == 0) return;
  const u8 b = fifo_[fifo_head_];
  fifo_head_ = (fifo_head_ + 1) & 15;
  fifo_len_--;
  if (fifo_len_ == 0) SetRtsActive(false);
  DTRACE("[joy] tx %02x\n", b);
  machine_.Soc().UartRxStart(b);
  tx_busy_ = true;
}

// Console UART finished receiving our byte: flow-control feedback.
void VSmileJoy::TxDone() {
  DTRACE("[joy] txdone (busy=%d)\n", tx_busy_);
  if (!tx_busy_) return;
  const bool was_active = active_;
  active_ = true;
  tx_busy_ = false;
  if (!was_active) dump_pending_ = true;  // announce ourselves after first byte
  if (cts_ && fifo_len_ > 0) {
    StartTx();
  } else if (fifo_len_ == 0 && dump_pending_) {
    dump_pending_ = false;
    QueueFullDump();
  }
}

// Full state refresh (vertical, horizontal, colors, buttons) - the real pad
// sends this after probe bursts; it is how the console detects the pad.
void VSmileJoy::QueueFullDump() {
  u8 yb = 0x80, xb = 0xC0;
  if (cur_y_ > 0) yb = 0x83 + (cur_y_ - 1);
  else if (cur_y_ < 0) yb = 0x8B + (-cur_y_ - 1);
  if (cur_x_ > 0) xb = 0xC3 + (cur_x_ - 1);
  else if (cur_x_ < 0) xb = 0xCB + (-cur_x_ - 1);
  QueueTx(yb);
  QueueTx(xb);
  u8 cb = 0x90;
  if (cur_buttons_ & (1u << 7)) cb |= 1;
  if (cur_buttons_ & (1u << 6)) cb |= 2;
  if (cur_buttons_ & (1u << 5)) cb |= 4;
  if (cur_buttons_ & (1u << 4)) cb |= 8;
  QueueTx(cb);
  u8 bb = 0xA0;
  if (cur_buttons_ & 1) bb = 0xA1;
  else if (cur_buttons_ & 2) bb = 0xA2;
  else if (cur_buttons_ & 4) bb = 0xA3;
  else if (cur_buttons_ & 8) bb = 0xA4;
  QueueTx(bb);
  sent_x_ = cur_x_; sent_y_ = cur_y_; sent_buttons_ = cur_buttons_;
}

void VSmileJoy::SetCts(bool state) {
  cts_ = state;
  if (cts_ && fifo_len_ > 0 && !tx_busy_ && !tx_starting_) {
    tx_starting_ = true;
    tx_start_counter_ = kTxStartDelay;
  }
}

void VSmileJoy::Rx(u8 byte) {
  DTRACE("[joy] rx %02x\n", byte);
  const u8 hi = byte & 0xF0;
  if (hi == 0x60) {
    leds_ = byte & 0x0F;
  } else if (hi == 0x70 || hi == 0xB0) {
    probe_history_[0] = (hi == 0x70) ? 0 : probe_history_[1];
    probe_history_[1] = byte & 0x0F;
    QueueTx(0xB0 | (((0u - probe_history_[0] - probe_history_[1]) ^ 0xA) & 0xF));
    dump_pending_ = true;  // re-sync state after the probe burst
  }
}

void VSmileJoy::UpdateInput(int joy_x, int joy_y, u32 buttons) {
  cur_x_ = std::max(-5, std::min(5, joy_x));
  cur_y_ = std::max(-5, std::min(5, joy_y));
  cur_buttons_ = buttons;
  input_dirty_ = true;
}

// Converts pending input changes into protocol bytes. Only runs once the
// controller is active (game has completed at least one transfer with us).
void VSmileJoy::QueueJoyUpdates() {
  DTRACE("[joy] queue-input x=%d y=%d b=%u\n", cur_x_, cur_y_, cur_buttons_);
  const bool update_fn = (cur_buttons_ & 0xF) != (sent_buttons_ & 0xF);
  const bool update_colors = (cur_buttons_ >> 4) != (sent_buttons_ >> 4);
  const bool update_joy = cur_x_ != sent_x_ || cur_y_ != sent_y_;
  if (!update_fn && !update_colors && !update_joy) {
    input_dirty_ = false;
    return;
  }

  if (update_fn) {
    u8 b = 0xA0;
    if (cur_buttons_ & 1) b = 0xA1;        // enter/OK
    else if (cur_buttons_ & 2) b = 0xA2;   // back/exit
    else if (cur_buttons_ & 4) b = 0xA3;   // help
    else if (cur_buttons_ & 8) b = 0xA4;   // abc / learning zone
    QueueTx(b);
  }
  if (update_colors) {
    // Protocol color bit order: bit0 green, bit1 blue, bit2 yellow, bit3 red.
    u8 b = 0x90;
    if (cur_buttons_ & (1u << 7)) b |= 1;  // green
    if (cur_buttons_ & (1u << 6)) b |= 2;  // blue
    if (cur_buttons_ & (1u << 5)) b |= 4;  // yellow
    if (cur_buttons_ & (1u << 4)) b |= 8;  // red
    QueueTx(b);
  }
  if (update_joy) {
    u8 xb = 0xC0, yb = 0x80;
    if (cur_x_ > 0) xb = 0xC3 + (cur_x_ - 1);
    else if (cur_x_ < 0) xb = 0xCB + (-cur_x_ - 1);
    if (cur_y_ > 0) yb = 0x83 + (cur_y_ - 1);
    else if (cur_y_ < 0) yb = 0x8B + (-cur_y_ - 1);
    QueueTx(xb);
    QueueTx(yb);
  }

  sent_x_ = cur_x_;
  sent_y_ = cur_y_;
  sent_buttons_ = cur_buttons_;
  input_dirty_ = false;
}

void VSmileJoy::RunCycles(int cycles) {
  if (!tx_busy_) {
    idle_counter_ -= cycles;
    if (idle_counter_ <= 0) {
      idle_counter_ = kIdlePeriod;
      QueueTx(0x55);
    }
  }

  if (tx_starting_) {
    tx_start_counter_ -= cycles;
    if (tx_start_counter_ <= 0) {
      tx_starting_ = false;
      StartTx();
    }
  }

  // Waiting for a CTS grant that never comes.
  if (!rts_idle_ && !cts_ && !tx_starting_ && !tx_busy_) {
    rts_timeout_ -= cycles;
    if (rts_timeout_ <= 0) {
      rts_timeout_ = kRtsTimeout;
      SetRtsActive(false);
      if (active_) {
        cur_x_ = cur_y_ = 0;
        cur_buttons_ = 0;
        sent_x_ = sent_y_ = 0;
        sent_buttons_ = 0;
        input_dirty_ = false;
        probe_history_[0] = probe_history_[1] = 0;
        idle_counter_ = kIdlePeriod;
      }
      active_ = false;
      fifo_len_ = fifo_head_ = 0;
      QueueTx(0x55);
    }
  }

  if (active_ && input_dirty_) QueueJoyUpdates();
}

void VSmileJoy::SaveState(StateWriter& w) const {
  w.Arr8(fifo_, 16);
  w.U32((u32)fifo_len_); w.U32((u32)fifo_head_);
  w.B(rts_idle_); w.B(cts_); w.B(tx_busy_); w.B(tx_starting_); w.B(active_);
  w.U8(leds_);
  w.Arr8(probe_history_, 2);
  w.U32((u32)cur_x_); w.U32((u32)cur_y_); w.U32(cur_buttons_);
  w.U32((u32)sent_x_); w.U32((u32)sent_y_); w.U32(sent_buttons_);
  w.B(input_dirty_);
  w.S64(idle_counter_); w.S64(rts_timeout_); w.S64(tx_start_counter_);
}

void VSmileJoy::LoadState(StateReader& r) {
  r.Arr8(fifo_, 16);
  fifo_len_ = (int)r.U32(); fifo_head_ = (int)r.U32();
  rts_idle_ = r.B(); cts_ = r.B(); tx_busy_ = r.B(); tx_starting_ = r.B(); active_ = r.B();
  leds_ = r.U8();
  r.Arr8(probe_history_, 2);
  cur_x_ = (int)r.U32(); cur_y_ = (int)r.U32(); cur_buttons_ = r.U32();
  sent_x_ = (int)r.U32(); sent_y_ = (int)r.U32(); sent_buttons_ = r.U32();
  input_dirty_ = r.B();
  idle_counter_ = r.S64(); rts_timeout_ = r.S64(); tx_start_counter_ = r.S64();
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
  // Real hardware boots with both controller-request lines pending; the game's
  // controller driver arms itself off this (veesem does the same at reset).
  spg_.RaiseExtIrq(0);
  spg_.RaiseExtIrq(1);
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
    DTRACE("[gpio] portC out data=%04x mask=%04x\n", data, mask);
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
