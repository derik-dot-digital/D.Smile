// Desktop smoke test for the D-Smile core. Not part of the Android build.
#include <cstdio>
#include <cstdlib>
#include <vector>

#include "../app/src/main/cpp/core/vsmile.h"

using namespace dsmile;

static void WriteBmp(const char* path, const u16* fb565) {
  const int w = 320, h = 240;
  const int row = w * 3;
  const int size = 54 + row * h;
  std::vector<u8> bmp(size, 0);
  u8* p = bmp.data();
  p[0] = 'B'; p[1] = 'M';
  *(u32*)(p + 2) = size;
  *(u32*)(p + 10) = 54;
  *(u32*)(p + 14) = 40;
  *(s32*)(p + 18) = w;
  *(s32*)(p + 22) = -h;  // top-down
  *(u16*)(p + 26) = 1;
  *(u16*)(p + 28) = 24;
  u8* px = p + 54;
  for (int i = 0; i < w * h; i++) {
    const u16 v = fb565[i];
    px[i * 3 + 0] = (u8)((v & 31) << 3);          // B
    px[i * 3 + 1] = (u8)(((v >> 5) & 63) << 2);   // G
    px[i * 3 + 2] = (u8)(((v >> 11) & 31) << 3);  // R
  }
  FILE* f = fopen(path, "wb");
  fwrite(bmp.data(), 1, bmp.size(), f);
  fclose(f);
}

static u32 FbHash(const u16* fb) {
  u32 h = 2166136261u;
  for (int i = 0; i < 320 * 240; i++) { h ^= fb[i]; h *= 16777619u; }
  return h;
}

int main(int argc, char** argv) {
  if (argc < 2) { std::printf("usage: host_test <rom.bin> [frames]\n"); return 1; }
  const int total_frames = argc > 2 ? atoi(argv[2]) : 300;

  FILE* f = fopen(argv[1], "rb");
  if (!f) { std::printf("cannot open rom\n"); return 1; }
  fseek(f, 0, SEEK_END);
  const long size = ftell(f);
  fseek(f, 0, SEEK_SET);
  std::vector<u8> rom(size);
  fread(rom.data(), 1, size, f);
  fclose(f);
  std::printf("rom: %ld bytes\n", size);

  static VSmile vs;
  if (!vs.LoadCart(rom.data(), rom.size())) { std::printf("LoadCart failed\n"); return 1; }
  vs.Reset(false /*NTSC*/);

  std::vector<s16> audio(600000);
  long total_audio = 0;
  long nonzero_audio = 0;

  for (int frame = 0; frame < total_frames; frame++) {
    // Hold Enter for a while mid-run to try to advance past title screens.
    if (frame == 150 || frame == 220) vs.SetInput(0, 0, 1);
    if (frame == 160 || frame == 230) vs.SetInput(0, 0, 0);

    vs.RunFrame();
    const int n = vs.DrainAudio(audio.data(), (int)audio.size());
    total_audio += n;
    for (int i = 0; i < n; i++) if (audio[i] != 0) nonzero_audio++;

    if (frame % 300 == 0 || frame == total_frames - 1) {
      char name[64];
      std::snprintf(name, sizeof(name), "frame_%03d.bmp", frame);
      WriteBmp(name, vs.Framebuffer());
      std::printf("frame %3d: fb hash %08x\n", frame, FbHash(vs.Framebuffer()));
    }
  }
  std::printf("audio: %ld samples total (%.0f/frame), %ld nonzero\n", total_audio,
              (double)total_audio / total_frames, nonzero_audio);

  // Save-state determinism: save, run 60 frames, hash; load, run 60, compare.
  std::vector<u8> state;
  vs.SaveState(state);
  std::printf("state size: %zu bytes\n", state.size());
  for (int i = 0; i < 60; i++) vs.RunFrame();
  vs.DrainAudio(audio.data(), (int)audio.size());
  const u32 hash_a = FbHash(vs.Framebuffer());
  if (!vs.LoadState(state.data(), state.size())) { std::printf("LoadState failed\n"); return 1; }
  for (int i = 0; i < 60; i++) vs.RunFrame();
  vs.DrainAudio(audio.data(), (int)audio.size());
  const u32 hash_b = FbHash(vs.Framebuffer());
  std::printf("savestate determinism: %08x vs %08x -> %s\n", hash_a, hash_b,
              hash_a == hash_b ? "PASS" : "FAIL");
  return hash_a == hash_b ? 0 : 2;
}
