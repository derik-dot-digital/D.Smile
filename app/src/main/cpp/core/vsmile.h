#pragma once
#include "common.h"
#include "spg200.h"

namespace dsmile {

class VSmile;

// The V.Smile joystick: its own MCU speaking a serial protocol over the
// console UART, with RTS/CTS flow control on GPIO port C. Byte pacing is
// driven by the console UART's receive completion (TxDone), like real
// hardware flow control — never by a free-running timer.
class VSmileJoy {
 public:
  explicit VSmileJoy(VSmile& machine) : machine_(machine) {}

  void Reset();
  void RunCycles(int cycles);
  void SetCts(bool state);            // console -> controller select
  void Rx(u8 byte);                   // console -> controller byte (via UART TX)
  void TxDone();                      // console UART finished receiving our byte
  // buttons bitmask: bit0 enter, bit1 back/exit, bit2 help, bit3 abc,
  //                  bit4 red, bit5 yellow, bit6 blue, bit7 green
  void UpdateInput(int joy_x, int joy_y, u32 buttons);  // x/y in -5..5
  bool RtsIdle() const { return rts_idle_; }
  u8 LedState() const { return leds_; }

  void SaveState(struct StateWriter& w) const;
  void LoadState(struct StateReader& r);

 private:
  void QueueTx(u8 b);
  void StartTx();
  void QueueJoyUpdates();
  void QueueFullDump();
  void SetRtsActive(bool active);

  VSmile& machine_;
  u8 fifo_[16]{};
  int fifo_len_ = 0, fifo_head_ = 0;
  bool rts_idle_ = true;     // true = no transfer request
  bool cts_ = false;         // console grant
  bool tx_busy_ = false;     // byte in flight to console UART
  bool tx_starting_ = false; // 3.6 ms CTS-grant delay running
  bool active_ = false;      // controller considered alive by game
  u8 leds_ = 0;
  u8 probe_history_[2]{};
  // current vs last-transmitted input state
  int cur_x_ = 0, cur_y_ = 0;
  u32 cur_buttons_ = 0;
  int sent_x_ = 0, sent_y_ = 0;
  u32 sent_buttons_ = 0;
  bool input_dirty_ = false;
  bool dump_pending_ = false;
  // timers (cycle countdowns)
  s64 idle_counter_ = kIdlePeriod;
  s64 rts_timeout_ = kRtsTimeout;
  s64 tx_start_counter_ = kTxStartDelay;

  static constexpr s64 kIdlePeriod = 27000000;      // 1 s keepalive
  static constexpr s64 kRtsTimeout = 13500000;      // 0.5 s grant timeout
  static constexpr s64 kTxStartDelay = 97200;       // 3.6 ms after CTS
};

// The V.Smile console: SPG200 + cartridge + sysrom + controller wiring.
class VSmile : public MachineIo {
 public:
  VSmile();

  bool LoadCart(const u8* data, size_t size_bytes);     // raw LE dump
  void LoadSysrom(const u8* data, size_t size_bytes);   // optional real BIOS
  void SetRegion(int code) { region_ = code & 0xF; }
  void SetVtechLogo(bool on) { vtech_logo_ = on; }

  void Reset(bool pal);
  void RunFrame();

  const u16* Framebuffer() { return spg_.GetPpu().Framebuffer(); }
  int DrainAudio(s16* out, int max_samples) { return spg_.GetSpu().DrainAudio(out, max_samples); }

  void SetInput(int joy_x, int joy_y, u32 buttons) { joy_.UpdateInput(joy_x, joy_y, buttons); }
  void SetConsoleButtons(bool on, bool off, bool restart) {
    on_ = on; off_ = off; restart_ = restart;
  }

  // Save states: versioned blob, cart-checksum guarded.
  void SaveState(std::vector<u8>& out) const;
  bool LoadState(const u8* data, size_t size);

  u8 Leds() const { return joy_.LedState(); }

  // MachineIo
  u16 GpioIn(int port) override;
  void GpioOut(int port, u16 data, u16 mask) override;
  void UartTx(u8 byte) override;
  void UartRxDone() override { joy_.TxDone(); }
  u16 AdcIn(int ch) override;
  void RunCycles(int cycles) override;

  // Used by VSmileJoy
  Spg200& Soc() { return spg_; }

 private:
  void MakeDummySysrom();

  Spg200 spg_;
  VSmileJoy joy_;
  std::vector<u16> cart_;
  std::vector<u16> sysrom_;
  u32 cart_checksum_ = 0;
  int region_ = 0xF;        // US English
  bool vtech_logo_ = true;
  bool cts0_ = false, cts1_ = false;
  bool on_ = false, off_ = false, restart_ = false;
  int auto_on_frames_ = 0;  // simulated power-button pulse after reset
};

}  // namespace dsmile
