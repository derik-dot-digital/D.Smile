// Ground-truth test: does veesem's core accept input for this ROM without a BIOS?
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <vector>

#include "core/vsmile/vsmile.h"
#ifndef LOGO_FLAG
#define LOGO_FLAG true
#endif

static uint32_t Hash(std::span<uint8_t> p) {
  uint32_t h = 2166136261u;
  for (auto b : p) { h ^= b; h *= 16777619u; }
  return h;
}

static std::vector<uint8_t> g_sys;
static uint32_t Run(const std::vector<uint8_t>& rom, bool with_input) {
  auto sysrom = std::make_unique<VSmile::SysRomType>();
  sysrom->fill(0);
  for (int i = 0xfffc0; i < 0xfffdc; i += 2) (*sysrom)[i + 1] = 0x31;
  if (!g_sys.empty()) {
    for (size_t i = 0; i < g_sys.size() / 2 && i < sysrom->size(); i++)
      (*sysrom)[i] = g_sys[i * 2] | (g_sys[i * 2 + 1] << 8);
  }
  auto cart = std::make_unique<VSmile::CartRomType>();
  cart->fill(0);
  for (size_t i = 0; i < rom.size() / 2 && i < cart->size(); i++)
    (*cart)[i] = rom[i * 2] | (rom[i * 2 + 1] << 8);

  VSmile vs(std::move(sysrom), std::move(cart), VSmile::CartType::STANDARD, nullptr, 0xF, LOGO_FLAG,
            VideoTiming::NTSC);
  vs.Reset();
  vs.UpdateOnButton(true);
  for (int f = 0; f < 2040; f++) {
    if (f == 20) vs.UpdateOnButton(false);
    if (with_input) {
      VSmile::JoyInput in;
      in.enter = (f >= 1810 && f < 1840);
      vs.UpdateJoystick(in);
    }
    vs.RunFrame();
    vs.GetAudio();
  }
  return Hash(vs.GetPicture());
}

int main(int argc, char** argv) {
  if (argc > 2) {
    FILE* sf = fopen(argv[2], "rb");
    if (sf) { fseek(sf, 0, SEEK_END); long ss = ftell(sf); fseek(sf, 0, SEEK_SET); g_sys.resize(ss); fread(g_sys.data(), 1, ss, sf); fclose(sf); std::printf("sysrom %ld bytes\n", ss); }
  }
  FILE* f = fopen(argv[1], "rb");
  fseek(f, 0, SEEK_END);
  long size = ftell(f);
  fseek(f, 0, SEEK_SET);
  std::vector<uint8_t> rom(size);
  fread(rom.data(), 1, size, f);
  fclose(f);

  srand(1234);
  const uint32_t with_input = Run(rom, true);
  srand(1234);
  const uint32_t without = Run(rom, false);
  std::printf("veesem: with=%08x without=%08x -> input %s\n", with_input, without,
              with_input != without ? "WORKS" : "IGNORED");
  return 0;
}
