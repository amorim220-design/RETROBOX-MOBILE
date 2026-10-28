#pragma once

#include <android/native_window.h>
#include <aaudio/AAudio.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "libretro_api.h"

namespace retrobox {

// ---------------------------------------------------------------------------
// Audio: AAudio output fed by a lock-free ring buffer written by the core.
// ---------------------------------------------------------------------------
class AudioOutput {
public:
    bool start(int sampleRate);
    void stop();
    void push(const int16_t *interleavedStereo, size_t frames);
    void setPaused(bool paused) { paused_ = paused; }
    int sampleRate() const { return sampleRate_; }

private:
    static aaudio_data_callback_result_t callback(AAudioStream *stream, void *userData,
                                                  void *audioData, int32_t numFrames);
    void render(int16_t *out, int32_t frames);

    AAudioStream *stream_ = nullptr;
    int sampleRate_ = 48000;
    std::atomic<bool> paused_{false};

    static constexpr size_t kCapacityFrames = 16384; // power of two
    std::vector<int16_t> ring_ = std::vector<int16_t>(kCapacityFrames * 2, 0);
    std::atomic<uint32_t> writeFrame_{0};
    std::atomic<uint32_t> readFrame_{0};
};

// ---------------------------------------------------------------------------
// Renderer: EGL/GLES surface on top of the Android SurfaceView.
// ---------------------------------------------------------------------------
class Renderer {
public:
    bool init(ANativeWindow *window, int pixelFormat);
    void destroy();

    void setPixelFormat(int format);
    void setLinearFilter(bool linear);
    void setAspectMode(int mode) { aspectMode_ = mode; } // 0 fit, 1 stretch, 2 integer

    // Software rendered frame coming from retro_video_refresh.
    void presentSoftware(const void *data, unsigned width, unsigned height, size_t pitch);
    // Hardware rendered frame: the core drew into our FBO.
    void presentHardware(unsigned width, unsigned height);

    bool hwActive() const { return hwActive_; }
    void enableHardware(bool enabled, unsigned maxWidth, unsigned maxHeight, bool depth,
                        bool stencil, bool bottomLeft, int versionMajor, int versionMinor);
    uintptr_t currentFramebuffer() const { return fbo_; }
    void contextReset();

    int surfaceWidth() const { return surfaceW_; }
    int surfaceHeight() const { return surfaceH_; }

private:
    bool createContext(ANativeWindow *window);
    bool createProgram();
    bool createFbo(unsigned width, unsigned height, bool depth, bool stencil);
    void drawQuad(bool flipVertical);
    void computeViewport(unsigned frameW, unsigned frameH, int *outX, int *outY, int *outW, int *outH);

    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLSurface surface_ = EGL_NO_SURFACE;
    EGLContext context_ = EGL_NO_CONTEXT;
    EGLConfig config_ = nullptr;
    ANativeWindow *window_ = nullptr;

    int surfaceW_ = 0;
    int surfaceH_ = 0;
    int pixelFormat_ = RETRO_PIXEL_FORMAT_XRGB8888;
    int aspectMode_ = 0;
    bool linear_ = true;

    GLuint program_ = 0;
    GLuint vbo_ = 0;
    GLuint texture_ = 0;
    GLuint fbo_ = 0;
    GLuint fboTexture_ = 0;
    GLuint fboDepth_ = 0;

    unsigned texWidth_ = 0;
    unsigned texHeight_ = 0;
    unsigned fboWidth_ = 0;
    unsigned fboHeight_ = 0;

    bool hwActive_ = false;
    bool hwDepth_ = false;
    bool hwStencil_ = false;
    bool bottomLeft_ = false;
    unsigned hwMaxWidth_ = 0;
    unsigned hwMaxHeight_ = 0;
    int glMajor_ = 3;
    int glMinor_ = 0;

    GLint uTexture_ = -1;
    GLint aPosition_ = -1;
    GLint aTexCoord_ = -1;
};

// ---------------------------------------------------------------------------
// Emulator: owns the dlopen'ed libretro core and its run loop.
// ---------------------------------------------------------------------------
class Emulator {
public:
    static Emulator &instance();

    void setDirectories(const std::string &systemDir, const std::string &saveDir);
    bool loadCore(const std::string &corePath, std::string *error);
    void unloadCore();

    bool setSurface(ANativeWindow *window, int pixelFormat);
    void destroySurface();

    std::string startGame(const std::string &romPath, const std::string &sramPath);
    void stopLoop();
    void unloadGame();

    void setButton(int port, int id, bool pressed);
    void setRumbleEnabled(bool enabled) { rumbleEnabled_ = enabled; }

    std::vector<uint8_t> serializeState() const;
    bool unserializeState(const uint8_t *data, size_t size);
    bool canSerialize() const { return serializeSize_ != nullptr; }

    void reset();
    void setFastForward(bool enabled) { fastForward_ = enabled; }
    bool isLoaded() const { return gameLoaded_; }
    std::string systemInfo() const;
    std::string lastError() const { return lastError_; }
    double measuredFps() const { return measuredFps_.load(); }
    void setLinearFilter(bool linear);
    void setAspectMode(int mode);

    // libretro callbacks (public so the C trampolines can reach them)
    bool environment(unsigned cmd, void *data);
    void videoRefresh(const void *data, unsigned width, unsigned height, size_t pitch);
    size_t audioBatch(const int16_t *data, size_t frames);
    void audioSample(int16_t left, int16_t right);
    int16_t inputState(unsigned port, unsigned device, unsigned index, unsigned id);
    void inputPoll();

private:
    Emulator();
    void runLoop();
    bool resolveSymbols(std::string *error);
    void tunePixelFormat();
    void saveSram();
    void loadSram();
    void closeCore();

    void *lib_ = nullptr;
    fn_init init_ = nullptr;
    fn_deinit deinit_ = nullptr;
    fn_api_version apiVersion_ = nullptr;
    fn_get_system_info getSystemInfo_ = nullptr;
    fn_get_system_av_info getSystemAvInfo_ = nullptr;
    fn_set_environment setEnvironment_ = nullptr;
    fn_set_video_refresh setVideoRefresh_ = nullptr;
    fn_set_audio_sample setAudioSample_ = nullptr;
    fn_set_audio_sample_batch setAudioSampleBatch_ = nullptr;
    fn_set_input_poll setInputPoll_ = nullptr;
    fn_set_input_state setInputState_ = nullptr;
    fn_load_game loadGameFn_ = nullptr;
    fn_unload_game unloadGameFn_ = nullptr;
    fn_run runFn_ = nullptr;
    fn_reset resetFn_ = nullptr;
    fn_serialize_size serializeSize_ = nullptr;
    fn_serialize serializeFn_ = nullptr;
    fn_unserialize unserializeFn_ = nullptr;
    fn_get_memory_data getMemoryData_ = nullptr;
    fn_get_memory_size getMemorySize_ = nullptr;
    fn_set_controller_port_device setControllerPortDevice_ = nullptr;

    Renderer renderer_;
    AudioOutput audio_;

    std::string systemDir_ = "/data/local/tmp";
    std::string saveDir_ = "/data/local/tmp";
    std::string sramPath_;
    std::string romPath_;
    std::string error_;
    ANativeWindow *window_ = nullptr;
    int pixelFormat_ = RETRO_PIXEL_FORMAT_XRGB8888;

    std::thread thread_;
    std::atomic<bool> running_{false};
    std::atomic<bool> stopRequested_{false};
    std::atomic<bool> fastForward_{false};
    std::atomic<bool> gameLoaded_{false};
    std::atomic<bool> rumbleEnabled_{false};
    std::atomic<double> measuredFps_{0.0};
    std::atomic<bool> buttons_[4][16];
    std::atomic<int16_t> axis_[4][4];

    std::mutex startMutex_;
    std::condition_variable startCv_;
    bool startFinished_ = false;
    std::string startError_;

    mutable std::mutex stateMutex_;
    std::string lastError_;
    double fps_ = 60.0;
    double sampleRate_ = 48000.0;
    retro_system_av_info avInfo_{};
    retro_hw_render_callback hwRender_{};
    bool ownsHwRender_ = false;
};

} // namespace retrobox
