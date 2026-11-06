// Frontend libretro mínimo para Android: carrega um núcleo (.so), roda o jogo,
// desenha em um Surface (ANativeWindow), toca áudio via AAudio e recebe botões por JNI.
#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <aaudio/AAudio.h>
#include <dlfcn.h>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include "libretro_min.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "EclipseNative", __VA_ARGS__)

namespace {

struct CoreApi {
  void* handle = nullptr;
  void (*set_environment)(retro_environment_t) = nullptr;
  void (*set_video_refresh)(retro_video_refresh_t) = nullptr;
  void (*set_audio_sample)(retro_audio_sample_t) = nullptr;
  void (*set_audio_sample_batch)(retro_audio_sample_batch_t) = nullptr;
  void (*set_input_poll)(retro_input_poll_t) = nullptr;
  void (*set_input_state)(retro_input_state_t) = nullptr;
  void (*init)() = nullptr;
  void (*deinit)() = nullptr;
  unsigned (*api_version)() = nullptr;
  void (*get_system_info)(retro_system_info*) = nullptr;
  void (*get_system_av_info)(retro_system_av_info*) = nullptr;
  bool (*load_game)(const retro_game_info*) = nullptr;
  void (*unload_game)() = nullptr;
  void (*run)() = nullptr;
  void (*reset)() = nullptr;
  size_t (*serialize_size)() = nullptr;
  bool (*serialize)(void*, size_t) = nullptr;
  bool (*unserialize)(const void*, size_t) = nullptr;
  void* (*get_memory_data)(unsigned) = nullptr;
  size_t (*get_memory_size)(unsigned) = nullptr;
};

CoreApi core;
std::mutex ctlMutex;   // start/stop
std::mutex runMutex;   // retro_run / estados / sram
std::mutex winMutex;   // janela
ANativeWindow* window = nullptr;
unsigned lastW = 0, lastH = 0;

std::atomic<uint32_t> buttons{0};
std::atomic<bool> running{false}, paused{false}, audioOn{true};
std::atomic<float> speed{1.0f};
std::thread loopThread;
bool gameLoaded = false;

std::string sysDir, saveDir, srmPath;
retro_pixel_format pixfmt = RETRO_PIXEL_FORMAT_0RGB1555;
std::vector<uint32_t> frame;
unsigned fw = 0, fh = 0;
double fps = 60.0, sampleRate = 44100.0;
float aspect = 0.0f;
AAudioStream* stream = nullptr;
std::vector<uint8_t> romData;

bool readFile(const std::string& p, std::vector<uint8_t>& out) {
  FILE* f = fopen(p.c_str(), "rb");
  if (!f) return false;
  fseek(f, 0, SEEK_END);
  long n = ftell(f);
  fseek(f, 0, SEEK_SET);
  if (n < 0) { fclose(f); return false; }
  out.resize((size_t)n);
  size_t got = n ? fread(out.data(), 1, (size_t)n, f) : 0;
  fclose(f);
  return got == (size_t)n;
}
bool writeFile(const std::string& p, const void* d, size_t n) {
  FILE* f = fopen(p.c_str(), "wb");
  if (!f) return false;
  size_t w = n ? fwrite(d, 1, n, f) : 0;
  fclose(f);
  return w == n;
}

void updateAspect(const retro_game_geometry& g) {
  if (g.aspect_ratio > 0.01f) aspect = g.aspect_ratio;
  else if (g.base_height) aspect = (float)g.base_width / (float)g.base_height;
}

bool envCb(unsigned cmd, void* data) {
  switch (cmd) {
    case RETRO_ENVIRONMENT_GET_CAN_DUPE: *(bool*)data = true; return true;
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
      int f = *(int*)data;
      if (f == 0 || f == 1 || f == 2) { pixfmt = (retro_pixel_format)f; return true; }
      return false;
    }
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY: *(const char**)data = sysDir.c_str(); return true;
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: *(const char**)data = saveDir.c_str(); return true;
    case RETRO_ENVIRONMENT_GET_VARIABLE: ((retro_variable*)data)->value = nullptr; return false;
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: *(bool*)data = false; return true;
    case RETRO_ENVIRONMENT_SET_GEOMETRY: updateAspect(*(retro_game_geometry*)data); return true;
    case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO: {
      auto* i = (retro_system_av_info*)data;
      updateAspect(i->geometry);
      if (i->timing.fps > 1) fps = i->timing.fps;
      return true;
    }
    case RETRO_ENVIRONMENT_SET_VARIABLES:
    case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
    case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
    case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
    case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL: return true;
    default: return false;
  }
}

void present() {
  std::lock_guard<std::mutex> lk(winMutex);
  if (!window || !fw || !fh) return;
  if (lastW != fw || lastH != fh) {
    ANativeWindow_setBuffersGeometry(window, (int)fw, (int)fh, WINDOW_FORMAT_RGBA_8888);
    lastW = fw; lastH = fh;
  }
  ANativeWindow_Buffer b;
  if (ANativeWindow_lock(window, &b, nullptr) != 0) return;
  unsigned rows = fh < (unsigned)b.height ? fh : (unsigned)b.height;
  unsigned cols = fw < (unsigned)b.width ? fw : (unsigned)b.width;
  for (unsigned y = 0; y < rows; y++)
    memcpy((uint32_t*)b.bits + (size_t)y * b.stride, &frame[(size_t)y * fw], (size_t)cols * 4);
  ANativeWindow_unlockAndPost(window);
}

void videoCb(const void* data, unsigned w, unsigned h, size_t pitch) {
  if (!data || !w || !h) return;
  if (frame.size() < (size_t)w * h) frame.resize((size_t)w * h);
  fw = w; fh = h;
  for (unsigned y = 0; y < h; y++) {
    const uint8_t* src = (const uint8_t*)data + (size_t)y * pitch;
    uint32_t* dst = &frame[(size_t)y * w];
    switch (pixfmt) {
      case RETRO_PIXEL_FORMAT_RGB565:
        for (unsigned x = 0; x < w; x++) {
          uint16_t p = ((const uint16_t*)src)[x];
          uint32_t r5 = (p >> 11) & 31, g6 = (p >> 5) & 63, b5 = p & 31;
          uint32_t r = (r5 << 3) | (r5 >> 2), g = (g6 << 2) | (g6 >> 4), b = (b5 << 3) | (b5 >> 2);
          dst[x] = 0xFF000000u | (b << 16) | (g << 8) | r;
        }
        break;
      case RETRO_PIXEL_FORMAT_XRGB8888:
        for (unsigned x = 0; x < w; x++) {
          uint32_t p = ((const uint32_t*)src)[x];
          uint32_t r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
          dst[x] = 0xFF000000u | (b << 16) | (g << 8) | r;
        }
        break;
      default:
        for (unsigned x = 0; x < w; x++) {
          uint16_t p = ((const uint16_t*)src)[x];
          uint32_t r5 = (p >> 10) & 31, g5 = (p >> 5) & 31, b5 = p & 31;
          uint32_t r = (r5 << 3) | (r5 >> 2), g = (g5 << 3) | (g5 >> 2), b = (b5 << 3) | (b5 >> 2);
          dst[x] = 0xFF000000u | (b << 16) | (g << 8) | r;
        }
    }
  }
  present();
}

size_t audioBatchCb(const int16_t* data, size_t frames) {
  if (stream && audioOn.load() && speed.load() < 1.01f && !paused.load())
    AAudioStream_write(stream, data, (int32_t)frames, 0);
  return frames;
}
void audioSampleCb(int16_t l, int16_t r) {
  int16_t b[2] = {l, r};
  audioBatchCb(b, 1);
}
void inputPollCb() {}
int16_t inputStateCb(unsigned port, unsigned device, unsigned, unsigned id) {
  if (port != 0 || device != RETRO_DEVICE_JOYPAD) return 0;
  uint32_t m = buttons.load();
  if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return (int16_t)(m & 0xFFFF);
  return id < 16 ? (int16_t)((m >> id) & 1) : 0;
}

void openAudio() {
  AAudioStreamBuilder* b = nullptr;
  if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) return;
  AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
  AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
  AAudioStreamBuilder_setChannelCount(b, 2);
  AAudioStreamBuilder_setSampleRate(b, (int32_t)sampleRate);
  AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
  if (AAudioStreamBuilder_openStream(b, &stream) == AAUDIO_OK) {
    AAudioStream_setBufferSizeInFrames(stream, AAudioStream_getBufferCapacityInFrames(stream));
    AAudioStream_requestStart(stream);
  } else {
    stream = nullptr;
    LOG("AAudio indisponível");
  }
  AAudioStreamBuilder_delete(b);
}
void closeAudio() {
  if (!stream) return;
  AAudioStream_requestStop(stream);
  AAudioStream_close(stream);
  stream = nullptr;
}

void saveSramLocked() {
  if (!gameLoaded || srmPath.empty() || !core.get_memory_data) return;
  size_t n = core.get_memory_size(RETRO_MEMORY_SAVE_RAM);
  void* d = core.get_memory_data(RETRO_MEMORY_SAVE_RAM);
  if (d && n) writeFile(srmPath, d, n);
}
void loadSramLocked() {
  size_t n = core.get_memory_size(RETRO_MEMORY_SAVE_RAM);
  void* d = core.get_memory_data(RETRO_MEMORY_SAVE_RAM);
  std::vector<uint8_t> buf;
  if (d && n && readFile(srmPath, buf)) memcpy(d, buf.data(), buf.size() < n ? buf.size() : n);
}

void loopFn() {
  using clk = std::chrono::steady_clock;
  auto next = clk::now();
  auto lastSave = clk::now();
  while (running.load()) {
    if (paused.load()) {
      std::this_thread::sleep_for(std::chrono::milliseconds(20));
      next = clk::now();
      continue;
    }
    { std::lock_guard<std::mutex> lk(runMutex); core.run(); }
    double interval = 1.0 / (fps > 1.0 ? fps : 60.0) / (double)speed.load();
    next += std::chrono::duration_cast<clk::duration>(std::chrono::duration<double>(interval));
    auto now = clk::now();
    if (next > now) std::this_thread::sleep_until(next);
    else if (now - next > std::chrono::milliseconds(100)) next = now;
    if (now - lastSave > std::chrono::seconds(30)) {
      std::lock_guard<std::mutex> lk(runMutex);
      saveSramLocked();
      lastSave = now;
    }
  }
}

template <typename T> bool sym(T& out, const char* name) {
  out = (T)dlsym(core.handle, name);
  if (!out) LOG("símbolo ausente: %s", name);
  return out != nullptr;
}

void stopLocked() {
  running = false;
  if (loopThread.joinable()) loopThread.join();
  if (core.handle) {
    if (gameLoaded) {
      { std::lock_guard<std::mutex> lk(runMutex); saveSramLocked(); core.unload_game(); }
      gameLoaded = false;
    }
    if (core.deinit) core.deinit();
  }
  closeAudio();
  if (core.handle) dlclose(core.handle);
  core = CoreApi();
  romData.clear();
  romData.shrink_to_fit();
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL Java_me_eclipse_ptbr_NativeBridge_nativeStart(
    JNIEnv* env, jclass, jstring jcore, jstring jrom, jstring jsys, jstring jsave, jstring jsrm) {
  std::lock_guard<std::mutex> ctl(ctlMutex);
  stopLocked();
  auto str = [&](jstring s) { const char* c = env->GetStringUTFChars(s, nullptr); std::string r(c); env->ReleaseStringUTFChars(s, c); return r; };
  std::string corePath = str(jcore), romPath = str(jrom);
  sysDir = str(jsys); saveDir = str(jsave); srmPath = str(jsrm);
  pixfmt = RETRO_PIXEL_FORMAT_0RGB1555;
  fw = fh = 0; lastW = lastH = 0; aspect = 0; fps = 60.0; sampleRate = 44100.0;
  paused = false;

  core.handle = dlopen(corePath.c_str(), RTLD_NOW | RTLD_LOCAL);
  if (!core.handle) { std::string e = std::string("Não consegui carregar o núcleo: ") + dlerror(); core = CoreApi(); return env->NewStringUTF(e.c_str()); }
  bool ok = sym(core.set_environment, "retro_set_environment") && sym(core.set_video_refresh, "retro_set_video_refresh") &&
            sym(core.set_audio_sample, "retro_set_audio_sample") && sym(core.set_audio_sample_batch, "retro_set_audio_sample_batch") &&
            sym(core.set_input_poll, "retro_set_input_poll") && sym(core.set_input_state, "retro_set_input_state") &&
            sym(core.init, "retro_init") && sym(core.deinit, "retro_deinit") && sym(core.api_version, "retro_api_version") &&
            sym(core.get_system_info, "retro_get_system_info") && sym(core.get_system_av_info, "retro_get_system_av_info") &&
            sym(core.load_game, "retro_load_game") && sym(core.unload_game, "retro_unload_game") && sym(core.run, "retro_run") &&
            sym(core.reset, "retro_reset") && sym(core.serialize_size, "retro_serialize_size") && sym(core.serialize, "retro_serialize") &&
            sym(core.unserialize, "retro_unserialize") && sym(core.get_memory_data, "retro_get_memory_data") &&
            sym(core.get_memory_size, "retro_get_memory_size");
  if (!ok || core.api_version() != RETRO_API_VERSION) {
    dlclose(core.handle); core = CoreApi();
    return env->NewStringUTF("Núcleo inválido (não é um núcleo libretro compatível).");
  }

  core.set_environment(envCb);
  core.init();
  core.set_video_refresh(videoCb);
  core.set_audio_sample(audioSampleCb);
  core.set_audio_sample_batch(audioBatchCb);
  core.set_input_poll(inputPollCb);
  core.set_input_state(inputStateCb);

  retro_system_info si{};
  core.get_system_info(&si);
  retro_game_info gi{};
  gi.path = romPath.c_str();
  if (!si.need_fullpath) {
    if (!readFile(romPath, romData)) { core.deinit(); dlclose(core.handle); core = CoreApi(); return env->NewStringUTF("Não consegui ler o arquivo do jogo."); }
    gi.data = romData.data(); gi.size = romData.size();
  }
  if (!core.load_game(&gi)) {
    core.deinit(); dlclose(core.handle); core = CoreApi(); romData.clear();
    return env->NewStringUTF("O núcleo não conseguiu abrir este jogo.");
  }
  gameLoaded = true;

  retro_system_av_info av{};
  core.get_system_av_info(&av);
  if (av.timing.fps > 1) fps = av.timing.fps;
  if (av.timing.sample_rate > 1000) sampleRate = av.timing.sample_rate;
  updateAspect(av.geometry);
  { std::lock_guard<std::mutex> lk(runMutex); loadSramLocked(); }
  openAudio();
  running = true;
  loopThread = std::thread(loopFn);
  LOG("jogo iniciado: %s (%.2f fps, %.0f Hz)", si.library_name ? si.library_name : "?", fps, sampleRate);
  return nullptr;
}

JNIEXPORT void JNICALL Java_me_eclipse_ptbr_NativeBridge_nativeStop(JNIEnv*, jclass) {
  std::lock_guard<std::mutex> ctl(ctlMutex);
  stopLocked();
}

JNIEXPORT void JNICALL Java_me_eclipse_ptbr_NativeBridge_nativeSetSurface(JNIEnv* env, jclass, jobject surface) {
  std::lock_guard<std::mutex> lk(winMutex);
  if (window) { ANativeWindow_release(window); window = nullptr; }
  if (surface) window = ANativeWindow_fromSurface(env, surface);
  lastW = lastH = 0;
}

JNIEXPORT void JNICALL Java_me_eclipse_ptbr_NativeBridge_nativeSetButtons(JNIEnv*, jclass, jint mask) { buttons = (uint32_t)mask; }
JNIEXPORT void JNICALL Java_me_eclipse_ptbr_NativeBridge_nativeSetSpeed(JNIEnv*, jclass, jfloat s) { speed = s < 0.25f ? 0.25f : s; }
JNIEXPORT void JNICALL Java_me_eclipse_ptbr_NativeBridge_nativeSetAudio(JNIEnv*, jclass, jboolean on) { audioOn = on; }
JNIEXPORT jfloat JNICALL Java_me_eclipse_ptbr_NativeBridge_nativeAspect(JNIEnv*, jclass) { return aspect > 0.01f ? aspect : 1.5f; }

JNIEXPORT void JNICALL Java_me_eclipse_ptbr_NativeBridge_nativePause(JNIEnv*, jclass, jboolean p) {
  bool was = paused.exchange(p);
  if (p && !was) {
    std::lock_guard<std::mutex> lk(runMutex);
    saveSramLocked();
  }
  if (stream) { if (p) AAudioStream_requestPause(stream); else AAudioStream_requestStart(stream); }
}

JNIEXPORT void JNICALL Java_me_eclipse_ptbr_NativeBridge_nativeReset(JNIEnv*, jclass) {
  std::lock_guard<std::mutex> lk(runMutex);
  if (gameLoaded) core.reset();
}

JNIEXPORT jboolean JNICALL Java_me_eclipse_ptbr_NativeBridge_nativeSaveState(JNIEnv* env, jclass, jstring jpath) {
  const char* c = env->GetStringUTFChars(jpath, nullptr); std::string path(c); env->ReleaseStringUTFChars(jpath, c);
  std::lock_guard<std::mutex> lk(runMutex);
  if (!gameLoaded) return JNI_FALSE;
  size_t n = core.serialize_size();
  if (!n) return JNI_FALSE;
  std::vector<uint8_t> buf(n);
  if (!core.serialize(buf.data(), n)) return JNI_FALSE;
  return writeFile(path, buf.data(), n) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_me_eclipse_ptbr_NativeBridge_nativeLoadState(JNIEnv* env, jclass, jstring jpath) {
  const char* c = env->GetStringUTFChars(jpath, nullptr); std::string path(c); env->ReleaseStringUTFChars(jpath, c);
  std::vector<uint8_t> buf;
  if (!readFile(path, buf)) return JNI_FALSE;
  std::lock_guard<std::mutex> lk(runMutex);
  if (!gameLoaded) return JNI_FALSE;
  return core.unserialize(buf.data(), buf.size()) ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
