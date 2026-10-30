#include "retrobox.h"

#include <android/log.h>
#include <dlfcn.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <mutex>

#define LOG_TAG "RetroBoxNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifndef EGL_OPENGL_ES3_BIT_KHR
#define EGL_OPENGL_ES3_BIT_KHR 0x0040
#endif

namespace retrobox {

namespace {

constexpr size_t kAudioRingFrames = 16384; // must stay in sync with AudioOutput
constexpr uint32_t kAudioRingMask = static_cast<uint32_t>(kAudioRingFrames - 1);

const char *kVertexShader = R"(attribute vec2 aPosition;
attribute vec2 aTexCoord;
varying vec2 vTexCoord;
void main() {
    vTexCoord = aTexCoord;
    gl_Position = vec4(aPosition, 0.0, 1.0);
})";

const char *kFragmentShader = R"(precision mediump float;
varying vec2 vTexCoord;
uniform sampler2D uTexture;
void main() {
    gl_FragColor = texture2D(uTexture, vTexCoord);
})";

GLuint compileShader(GLenum type, const char *source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint ok = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512];
        glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
        LOGE("shader compile failed: %s", log);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

} // namespace

// ===========================================================================
// AudioOutput
// ===========================================================================
bool AudioOutput::start(int sampleRate) {
    stop();
    sampleRate_ = sampleRate > 0 ? sampleRate : 48000;
    writeFrame_.store(0, std::memory_order_relaxed);
    readFrame_.store(0, std::memory_order_relaxed);

    AAudioStreamBuilder *builder = nullptr;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK || builder == nullptr) {
        LOGE("AAudio_createStreamBuilder failed");
        return false;
    }
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSampleRate(builder, sampleRate_);
    AAudioStreamBuilder_setChannelCount(builder, 2);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setDataCallback(builder, &AudioOutput::callback, this);

    aaudio_result_t result = AAudioStreamBuilder_openStream(builder, &stream_);
    AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK || stream_ == nullptr) {
        LOGE("AAudio openStream failed: %s", AAudio_convertResultToText(result));
        stream_ = nullptr;
        return false;
    }
    result = AAudioStream_requestStart(stream_);
    if (result != AAUDIO_OK) {
        LOGE("AAudio start failed: %s", AAudio_convertResultToText(result));
        stop();
        return false;
    }
    return true;
}

void AudioOutput::stop() {
    if (stream_ != nullptr) {
        AAudioStream_requestStop(stream_);
        AAudioStream_close(stream_);
        stream_ = nullptr;
    }
}

void AudioOutput::push(const int16_t *data, size_t frames) {
    if (stream_ == nullptr || data == nullptr || frames == 0) return;

    uint32_t write = writeFrame_.load(std::memory_order_relaxed);
    uint32_t read = readFrame_.load(std::memory_order_acquire);
    uint32_t used = write - read;
    const size_t capacity = kAudioRingFrames;

    if (used + frames > capacity) {
        // Drop the oldest frames instead of blocking the emulation thread.
        uint32_t drop = static_cast<uint32_t>(used + frames - capacity);
        readFrame_.store(read + drop, std::memory_order_release);
    }

    for (size_t i = 0; i < frames; ++i) {
        uint32_t slot = (write + i) & kAudioRingMask;
        ring_[slot * 2] = data[i * 2];
        ring_[slot * 2 + 1] = data[i * 2 + 1];
    }
    writeFrame_.store(write + static_cast<uint32_t>(frames), std::memory_order_release);
}

void AudioOutput::render(int16_t *out, int32_t frames) {
    uint32_t read = readFrame_.load(std::memory_order_relaxed);
    uint32_t write = writeFrame_.load(std::memory_order_acquire);
    uint32_t available = write - read;

    if (paused_ || available == 0) {
        std::memset(out, 0, static_cast<size_t>(frames) * 2 * sizeof(int16_t));
        if (!paused_) readFrame_.store(write, std::memory_order_release);
        return;
    }

    if (available > static_cast<uint32_t>(frames)) available = static_cast<uint32_t>(frames);
    for (uint32_t i = 0; i < available; ++i) {
        uint32_t slot = (read + i) & kAudioRingMask;
        out[i * 2] = ring_[slot * 2];
        out[i * 2 + 1] = ring_[slot * 2 + 1];
    }
    if (available < static_cast<uint32_t>(frames)) {
        std::memset(out + available * 2, 0,
                    static_cast<size_t>(frames - available) * 2 * sizeof(int16_t));
    }
    readFrame_.store(read + available, std::memory_order_release);
}

aaudio_data_callback_result_t AudioOutput::callback(AAudioStream *stream, void *userData,
                                                    void *audioData, int32_t numFrames) {
    auto *self = static_cast<AudioOutput *>(userData);
    self->render(static_cast<int16_t *>(audioData), numFrames);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

// ===========================================================================
// Renderer
// ===========================================================================
bool Renderer::init(ANativeWindow *window, int pixelFormat) {
    window_ = window;
    pixelFormat_ = pixelFormat;

    display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display_ == EGL_NO_DISPLAY || !eglInitialize(display_, nullptr, nullptr)) {
        LOGE("eglInitialize failed");
        return false;
    }

    EGLint configAttribs[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_RED_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_ALPHA_SIZE, 8,
            EGL_DEPTH_SIZE, 0,
            EGL_STENCIL_SIZE, 0,
            EGL_NONE};

    EGLint numConfigs = 0;
    if (!eglChooseConfig(display_, configAttribs, &config_, 1, &numConfigs) || numConfigs == 0) {
        configAttribs[1] = EGL_OPENGL_ES2_BIT;
        if (!eglChooseConfig(display_, configAttribs, &config_, 1, &numConfigs) || numConfigs == 0) {
            LOGE("no EGL config found");
            return false;
        }
    }

    EGLint contextAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    context_ = eglCreateContext(display_, config_, EGL_NO_CONTEXT, contextAttribs);
    if (context_ == EGL_NO_CONTEXT) {
        contextAttribs[1] = 2;
        context_ = eglCreateContext(display_, config_, EGL_NO_CONTEXT, contextAttribs);
        glMajor_ = 2;
    }
    if (context_ == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext failed");
        return false;
    }

    surface_ = eglCreateWindowSurface(display_, config_, window_, nullptr);
    if (surface_ == EGL_NO_SURFACE) {
        LOGE("eglCreateWindowSurface failed");
        return false;
    }
    if (!eglMakeCurrent(display_, surface_, surface_, context_)) {
        LOGE("eglMakeCurrent failed");
        return false;
    }
    eglQuerySurface(display_, surface_, EGL_WIDTH, &surfaceW_);
    eglQuerySurface(display_, surface_, EGL_HEIGHT, &surfaceH_);

    glGenTextures(1, &texture_);
    glBindTexture(GL_TEXTURE_2D, texture_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    if (!createProgram()) {
        LOGE("failed to create GL program");
        return false;
    }
    LOGI("GL context ready: %dx%d (ES%d)", surfaceW_, surfaceH_, glMajor_);
    return true;
}

void Renderer::destroy() {
    if (display_ != EGL_NO_DISPLAY) {
        eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (surface_ != EGL_NO_SURFACE) eglDestroySurface(display_, surface_);
        if (context_ != EGL_NO_CONTEXT) eglDestroyContext(display_, context_);
        eglTerminate(display_);
    }
    display_ = EGL_NO_DISPLAY;
    surface_ = EGL_NO_SURFACE;
    context_ = EGL_NO_CONTEXT;
    window_ = nullptr;
    program_ = vbo_ = texture_ = fbo_ = fboTexture_ = fboDepth_ = 0;
    texWidth_ = texHeight_ = 0;
    texFormat_ = texType_ = 0;
    fboWidth_ = fboHeight_ = 0;
}

bool Renderer::createProgram() {
    GLuint vs = compileShader(GL_VERTEX_SHADER, kVertexShader);
    GLuint fs = compileShader(GL_FRAGMENT_SHADER, kFragmentShader);
    if (!vs || !fs) return false;

    program_ = glCreateProgram();
    glAttachShader(program_, vs);
    glAttachShader(program_, fs);
    glLinkProgram(program_);
    glDeleteShader(vs);
    glDeleteShader(fs);

    GLint linked = GL_FALSE;
    glGetProgramiv(program_, GL_LINK_STATUS, &linked);
    if (!linked) {
        char log[512];
        glGetProgramInfoLog(program_, sizeof(log), nullptr, log);
        LOGE("program link failed: %s", log);
        return false;
    }
    aPosition_ = glGetAttribLocation(program_, "aPosition");
    aTexCoord_ = glGetAttribLocation(program_, "aTexCoord");
    uTexture_ = glGetUniformLocation(program_, "uTexture");

    static const GLfloat verts[] = {
            // x, y, u, v
            -1.f, -1.f, 0.f, 1.f,
            1.f, -1.f, 1.f, 1.f,
            -1.f, 1.f, 0.f, 0.f,
            1.f, 1.f, 1.f, 0.f,
    };
    glGenBuffers(1, &vbo_);
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    glBufferData(GL_ARRAY_BUFFER, sizeof(verts), verts, GL_STATIC_DRAW);
    return true;
}

void Renderer::setPixelFormat(int format) { pixelFormat_ = format; }

void Renderer::setLinearFilter(bool linear) {
    linear_ = linear;
    if (texture_ != 0) {
        glBindTexture(GL_TEXTURE_2D, texture_);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, linear ? GL_LINEAR : GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, linear ? GL_LINEAR : GL_NEAREST);
    }
}

void Renderer::enableHardware(bool enabled, unsigned maxWidth, unsigned maxHeight, bool depth,
                              bool stencil, bool bottomLeft, int versionMajor, int versionMinor) {
    hwActive_ = enabled;
    hwMaxWidth_ = maxWidth;
    hwMaxHeight_ = maxHeight;
    hwDepth_ = depth;
    hwStencil_ = stencil;
    bottomLeft_ = bottomLeft;
    glMajor_ = versionMajor > 0 ? versionMajor : glMajor_;
    glMinor_ = versionMinor;
}

bool Renderer::createFbo(unsigned width, unsigned height, bool depth, bool stencil) {
    if (width == 0) width = 1024;
    if (height == 0) height = 1024;

    if (fbo_ != 0 && fboWidth_ == width && fboHeight_ == height) return true;

    if (fbo_ != 0) {
        glDeleteFramebuffers(1, &fbo_);
        fbo_ = 0;
    }
    if (fboTexture_ != 0) {
        glDeleteTextures(1, &fboTexture_);
        fboTexture_ = 0;
    }
    if (fboDepth_ != 0) {
        glDeleteRenderbuffers(1, &fboDepth_);
        fboDepth_ = 0;
    }

    glGenTextures(1, &fboTexture_);
    glBindTexture(GL_TEXTURE_2D, fboTexture_);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    glGenFramebuffers(1, &fbo_);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo_);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, fboTexture_, 0);

    if (depth || stencil) {
        glGenRenderbuffers(1, &fboDepth_);
        glBindRenderbuffer(GL_RENDERBUFFER, fboDepth_);
        GLenum format = (depth && stencil) ? GL_DEPTH24_STENCIL8 : (depth ? GL_DEPTH_COMPONENT16 : GL_STENCIL_INDEX8);
        glRenderbufferStorage(GL_RENDERBUFFER, format, width, height);
        if (depth) glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, fboDepth_);
        if (stencil) glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_STENCIL_ATTACHMENT, GL_RENDERBUFFER, fboDepth_);
    }

    GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        LOGE("FBO incomplete: 0x%x", status);
        return false;
    }
    fboWidth_ = width;
    fboHeight_ = height;
    LOGI("FBO created %ux%u (depth=%d stencil=%d)", width, height, depth, stencil);
    return true;
}

void Renderer::contextReset() {
    if (!hwActive_) return;
    createFbo(hwMaxWidth_, hwMaxHeight_, hwDepth_, hwStencil_);
}

void Renderer::refreshSurfaceSize() {
    if (display_ == EGL_NO_DISPLAY || surface_ == EGL_NO_SURFACE) return;
    EGLint w = 0;
    EGLint h = 0;
    if (!eglQuerySurface(display_, surface_, EGL_WIDTH, &w)) return;
    if (!eglQuerySurface(display_, surface_, EGL_HEIGHT, &h)) return;
    if (w != surfaceW_ || h != surfaceH_) {
        LOGI("surface resized: %dx%d -> %dx%d", surfaceW_, surfaceH_, w, h);
        surfaceW_ = w;
        surfaceH_ = h;
    }
}

std::string Renderer::geometryInfo() const {
    char buffer[128];
    snprintf(buffer, sizeof(buffer), "%dx%d view %dx%d @ %d,%d", surfaceW_, surfaceH_,
             lastVw_, lastVh_, lastVx_, lastVy_);
    return std::string(buffer);
}

void Renderer::computeViewport(unsigned frameW, unsigned frameH, int *outX, int *outY, int *outW,
                               int *outH) {
    if (surfaceW_ <= 0 || surfaceH_ <= 0) {
        *outX = *outY = 0;
        *outW = *outH = 1;
        return;
    }
    double dstAspect = static_cast<double>(surfaceW_) / static_cast<double>(surfaceH_);
    double srcAspect = frameH > 0 ? static_cast<double>(frameW) / static_cast<double>(frameH) : dstAspect;

    if (aspectMode_ == 1) {
        *outX = 0;
        *outY = 0;
        *outW = surfaceW_;
        *outH = surfaceH_;
        return;
    }

    if (aspectMode_ == 2 && frameW > 0 && frameH > 0) {
        // Integer scaling: keep the pixel grid sharp.
        int scale = surfaceW_ / static_cast<int>(frameW);
        int scaleY = surfaceH_ / static_cast<int>(frameH);
        if (scaleY < scale) scale = scaleY;
        if (scale < 1) scale = 1;
        int w = static_cast<int>(frameW) * scale;
        int h = static_cast<int>(frameH) * scale;
        *outX = (surfaceW_ - w) / 2;
        *outY = (surfaceH_ - h) / 2;
        *outW = w;
        *outH = h;
        return;
    }

    int w;
    int h;
    if (srcAspect > dstAspect) {
        w = surfaceW_;
        h = static_cast<int>(surfaceW_ / srcAspect);
    } else {
        h = surfaceH_;
        w = static_cast<int>(surfaceH_ * srcAspect);
    }
    *outX = (surfaceW_ - w) / 2;
    *outY = (surfaceH_ - h) / 2;
    *outW = w;
    *outH = h;
}

void Renderer::applyViewport(unsigned frameW, unsigned frameH) {
    refreshSurfaceSize();

    int vx, vy, vw, vh;
    computeViewport(frameW, frameH, &vx, &vy, &vw, &vh);

    if (vx != lastVx_ || vy != lastVy_ || vw != lastVw_ || vh != lastVh_) {
        LOGI("viewport %dx%d at %d,%d (surface %dx%d, frame %ux%u)", vw, vh, vx, vy, surfaceW_,
             surfaceH_, frameW, frameH);
        lastVx_ = vx;
        lastVy_ = vy;
        lastVw_ = vw;
        lastVh_ = vh;
    }

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(vx, vy, vw, vh);
    glDisable(GL_SCISSOR_TEST);
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);
}

void Renderer::drawQuad(bool flipVertical) {
    if (program_ == 0) return;

    float topV = flipVertical ? 1.f : 0.f;
    float bottomV = flipVertical ? 0.f : 1.f;
    const GLfloat verts[] = {
            -1.f, -1.f, 0.f, bottomV,
            1.f, -1.f, 1.f, bottomV,
            -1.f, 1.f, 0.f, topV,
            1.f, 1.f, 1.f, topV,
    };

    glUseProgram(program_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, texture_);
    glUniform1i(uTexture_, 0);
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    glBufferSubData(GL_ARRAY_BUFFER, 0, sizeof(verts), verts);

    glEnableVertexAttribArray(static_cast<GLuint>(aPosition_));
    glVertexAttribPointer(static_cast<GLuint>(aPosition_), 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat),
                          nullptr);
    if (aTexCoord_ >= 0) {
        glEnableVertexAttribArray(static_cast<GLuint>(aTexCoord_));
        glVertexAttribPointer(static_cast<GLuint>(aTexCoord_), 2, GL_FLOAT, GL_FALSE,
                              4 * sizeof(GLfloat), reinterpret_cast<const void *>(2 * sizeof(GLfloat)));
    }
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray(static_cast<GLuint>(aPosition_));
    if (aTexCoord_ >= 0) glDisableVertexAttribArray(static_cast<GLuint>(aTexCoord_));
}

void Renderer::presentSoftware(const void *data, unsigned width, unsigned height, size_t pitch) {
    if (data == nullptr || display_ == EGL_NO_DISPLAY) return;

    // OpenGL ES is strict about the internal format: RGB565 data needs the
    // GL_RGB565 internal format (GL_RGBA + GL_UNSIGNED_SHORT_5_6_5 is rejected
    // with GL_INVALID_OPERATION).
    GLenum internalFormat = GL_RGBA;
    GLenum format = GL_RGBA;
    GLenum type = GL_UNSIGNED_BYTE;
    size_t bytesPerPixel = 4;
    switch (pixelFormat_) {
        case RETRO_PIXEL_FORMAT_0RGB1555:
            internalFormat = GL_RGBA;
            format = GL_RGBA;
            type = GL_UNSIGNED_SHORT_5_5_5_1;
            bytesPerPixel = 2;
            break;
        case RETRO_PIXEL_FORMAT_RGB565:
            internalFormat = GL_RGB565;
            format = GL_RGB;
            type = GL_UNSIGNED_SHORT_5_6_5;
            bytesPerPixel = 2;
            break;
        case RETRO_PIXEL_FORMAT_XRGB8888:
        default:
            internalFormat = GL_RGBA;
            format = GL_RGBA;
            type = GL_UNSIGNED_BYTE;
            bytesPerPixel = 4;
            break;
    }

    if (width == 0 || height == 0) return;

    const size_t rowBytes = static_cast<size_t>(width) * bytesPerPixel;

    // Some cores report a pitch larger than the visible width (they keep a
    // fixed stride). GL_UNPACK_ROW_LENGTH is not handled consistently by every
    // driver, so the rows are copied into a tightly packed buffer instead.
    const void *pixels = data;
    if (pitch != rowBytes) {
        uploadBuffer_.resize(rowBytes * height);
        const uint8_t *src = static_cast<const uint8_t *>(data);
        uint8_t *dst = uploadBuffer_.data();
        for (unsigned row = 0; row < height; ++row) {
            std::memcpy(dst + static_cast<size_t>(row) * rowBytes,
                        src + static_cast<size_t>(row) * pitch, rowBytes);
        }
        pixels = uploadBuffer_.data();
    }

    glBindTexture(GL_TEXTURE_2D, texture_);
    bool recreate = texture_ == 0 || width != texWidth_ || height != texHeight_ ||
                    internalFormat != texFormat_ || type != texType_;
    if (recreate) {
        // Sized internal formats (GL_RGB565) make the texture immutable in ES3,
        // so a size change needs a brand new texture object.
        if (texture_ != 0) {
            glDeleteTextures(1, &texture_);
            texture_ = 0;
        }
        glGenTextures(1, &texture_);
        glBindTexture(GL_TEXTURE_2D, texture_);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, linear_ ? GL_LINEAR : GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, linear_ ? GL_LINEAR : GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, static_cast<GLint>(width),
                     static_cast<GLint>(height), 0, format, type, pixels);
        texWidth_ = width;
        texHeight_ = height;
        texFormat_ = internalFormat;
        texType_ = type;
    } else {
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, static_cast<GLint>(width),
                        static_cast<GLint>(height), format, type, pixels);
    }

    GLenum error = glGetError();
    if (error != GL_NO_ERROR) {
        LOGE("texture upload failed (0x%x) %ux%u pitch=%zu type=0x%x - recreating", error,
             width, height, pitch, type);
        if (texture_ != 0) {
            glDeleteTextures(1, &texture_);
            texture_ = 0;
        }
        glGenTextures(1, &texture_);
        glBindTexture(GL_TEXTURE_2D, texture_);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, linear_ ? GL_LINEAR : GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, linear_ ? GL_LINEAR : GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, static_cast<GLint>(width),
                     static_cast<GLint>(height), 0, format, type, pixels);
        texWidth_ = width;
        texHeight_ = height;
        texFormat_ = internalFormat;
        texType_ = type;
        glGetError();
    }

    applyViewport(width, height);
    drawQuad(false);
    eglSwapBuffers(display_, surface_);
}

void Renderer::presentHardware(unsigned width, unsigned height) {
    if (!hwActive_ || display_ == EGL_NO_DISPLAY) return;
    if (fbo_ == 0) return;
    if (width == 0) width = fboWidth_;
    if (height == 0) height = fboHeight_;

    applyViewport(width, height);

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, fboTexture_);
    glUseProgram(program_);
    glUniform1i(uTexture_, 0);

    static const GLfloat vertsFlipped[] = {
            -1.f, -1.f, 0.f, 0.f,
            1.f, -1.f, 1.f, 0.f,
            -1.f, 1.f, 0.f, 1.f,
            1.f, 1.f, 1.f, 1.f,
    };
    static const GLfloat vertsNormal[] = {
            -1.f, -1.f, 0.f, 1.f,
            1.f, -1.f, 1.f, 1.f,
            -1.f, 1.f, 0.f, 0.f,
            1.f, 1.f, 1.f, 0.f,
    };
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    if (bottomLeft_) {
        glBufferSubData(GL_ARRAY_BUFFER, 0, sizeof(vertsNormal), vertsNormal);
    } else {
        glBufferSubData(GL_ARRAY_BUFFER, 0, sizeof(vertsFlipped), vertsFlipped);
    }
    glEnableVertexAttribArray(static_cast<GLuint>(aPosition_));
    glVertexAttribPointer(static_cast<GLuint>(aPosition_), 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat),
                          nullptr);
    if (aTexCoord_ >= 0) {
        glEnableVertexAttribArray(static_cast<GLuint>(aTexCoord_));
        glVertexAttribPointer(static_cast<GLuint>(aTexCoord_), 2, GL_FLOAT, GL_FALSE,
                              4 * sizeof(GLfloat), reinterpret_cast<const void *>(2 * sizeof(GLfloat)));
    }
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray(static_cast<GLuint>(aPosition_));
    if (aTexCoord_ >= 0) glDisableVertexAttribArray(static_cast<GLuint>(aTexCoord_));

    eglSwapBuffers(display_, surface_);

    // The core needs the FBO bound again for the next frame.
    glBindFramebuffer(GL_FRAMEBUFFER, fbo_);
}

// ===========================================================================
// Emulator
// ===========================================================================
namespace {
Emulator *g_emu = nullptr;

void videoRefreshThunk(const void *data, unsigned width, unsigned height, size_t pitch) {
    if (g_emu) g_emu->videoRefresh(data, width, height, pitch);
}
size_t audioBatchThunk(const int16_t *data, size_t frames) {
    return g_emu ? g_emu->audioBatch(data, frames) : frames;
}
void audioSampleThunk(int16_t left, int16_t right) {
    if (g_emu) g_emu->audioSample(left, right);
}
void inputPollThunk() {
    if (g_emu) g_emu->inputPoll();
}
int16_t inputStateThunk(unsigned port, unsigned device, unsigned index, unsigned id) {
    return g_emu ? g_emu->inputState(port, device, index, id) : 0;
}
bool environmentThunk(unsigned cmd, void *data) {
    return g_emu ? g_emu->environment(cmd, data) : false;
}
void coreLog(enum retro_log_level level, const char *fmt, ...) {
    va_list args;
    va_start(args, fmt);
    int prio = ANDROID_LOG_INFO;
    switch (level) {
        case RETRO_LOG_DEBUG: prio = ANDROID_LOG_DEBUG; break;
        case RETRO_LOG_INFO: prio = ANDROID_LOG_INFO; break;
        case RETRO_LOG_WARN: prio = ANDROID_LOG_WARN; break;
        case RETRO_LOG_ERROR: prio = ANDROID_LOG_ERROR; break;
    }
    __android_log_vprint(prio, "RetroBoxCore", fmt, args);
    va_end(args);
}
} // namespace

Emulator &Emulator::instance() {
    static Emulator emulator;
    return emulator;
}

Emulator::Emulator() {
    g_emu = this;
    for (auto &port : buttons_) {
        for (auto &button : port) button.store(false);
    }
    for (auto &port : axis_) {
        for (auto &a : port) a.store(0);
    }
}

void Emulator::setDirectories(const std::string &systemDir, const std::string &saveDir) {
    systemDir_ = systemDir;
    saveDir_ = saveDir;
}

bool Emulator::resolveSymbols(std::string *error) {
    auto load = [&](const char *name) -> void * {
        void *sym = dlsym(lib_, name);
        if (sym == nullptr && error != nullptr) {
            *error = std::string("missing symbol: ") + name;
        }
        return sym;
    };

    init_ = reinterpret_cast<fn_init>(load("retro_init"));
    deinit_ = reinterpret_cast<fn_deinit>(load("retro_deinit"));
    apiVersion_ = reinterpret_cast<fn_api_version>(load("retro_api_version"));
    getSystemInfo_ = reinterpret_cast<fn_get_system_info>(load("retro_get_system_info"));
    getSystemAvInfo_ = reinterpret_cast<fn_get_system_av_info>(load("retro_get_system_av_info"));
    setEnvironment_ = reinterpret_cast<fn_set_environment>(load("retro_set_environment"));
    setVideoRefresh_ = reinterpret_cast<fn_set_video_refresh>(load("retro_set_video_refresh"));
    setAudioSample_ = reinterpret_cast<fn_set_audio_sample>(load("retro_set_audio_sample"));
    setAudioSampleBatch_ = reinterpret_cast<fn_set_audio_sample_batch>(load("retro_set_audio_sample_batch"));
    setInputPoll_ = reinterpret_cast<fn_set_input_poll>(load("retro_set_input_poll"));
    setInputState_ = reinterpret_cast<fn_set_input_state>(load("retro_set_input_state"));
    loadGameFn_ = reinterpret_cast<fn_load_game>(load("retro_load_game"));
    unloadGameFn_ = reinterpret_cast<fn_unload_game>(load("retro_unload_game"));
    runFn_ = reinterpret_cast<fn_run>(load("retro_run"));
    resetFn_ = reinterpret_cast<fn_reset>(load("retro_reset"));
    serializeSize_ = reinterpret_cast<fn_serialize_size>(load("retro_serialize_size"));
    serializeFn_ = reinterpret_cast<fn_serialize>(load("retro_serialize"));
    unserializeFn_ = reinterpret_cast<fn_unserialize>(load("retro_unserialize"));
    getMemoryData_ = reinterpret_cast<fn_get_memory_data>(load("retro_get_memory_data"));
    getMemorySize_ = reinterpret_cast<fn_get_memory_size>(load("retro_get_memory_size"));
    setControllerPortDevice_ = reinterpret_cast<fn_set_controller_port_device>(
            dlsym(lib_, "retro_set_controller_port_device"));

    return init_ != nullptr && runFn_ != nullptr && loadGameFn_ != nullptr &&
           setEnvironment_ != nullptr;
}

bool Emulator::loadCore(const std::string &corePath, std::string *error) {
    unloadCore();
    lib_ = dlopen(corePath.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (lib_ == nullptr) {
        const char *err = dlerror();
        if (error != nullptr) *error = std::string("dlopen failed: ") + (err ? err : "unknown");
        return false;
    }
    std::string symbolError;
    if (!resolveSymbols(&symbolError)) {
        if (error != nullptr) *error = symbolError;
        dlclose(lib_);
        lib_ = nullptr;
        return false;
    }

    setEnvironment_(environmentThunk);
    setVideoRefresh_(videoRefreshThunk);
    setAudioSample_(audioSampleThunk);
    setAudioSampleBatch_(audioBatchThunk);
    setInputPoll_(inputPollThunk);
    setInputState_(inputStateThunk);

    retro_system_info info{};
    getSystemInfo_(&info);
    needFullPath_ = info.need_fullpath;
    LOGI("core loaded: %s %s (need_fullpath=%d)", info.library_name ? info.library_name : "?",
         info.library_version ? info.library_version : "?", info.need_fullpath);
    return true;
}

void Emulator::closeCore() {
    if (lib_ != nullptr) {
        dlclose(lib_);
        lib_ = nullptr;
    }
    init_ = nullptr;
    runFn_ = nullptr;
    loadGameFn_ = nullptr;
    serializeSize_ = nullptr;
    serializeFn_ = nullptr;
    unserializeFn_ = nullptr;
}

void Emulator::unloadCore() {
    if (gameLoaded_) unloadGame();
    closeCore();
}

bool Emulator::setSurface(ANativeWindow *window, int pixelFormat) {
    pixelFormat_ = pixelFormat;
    if (window_ != nullptr && window_ != window) {
        ANativeWindow_release(window_);
    }
    if (window != nullptr) ANativeWindow_acquire(window);
    window_ = window;
    return window != nullptr;
}

void Emulator::destroySurface() {
    if (window_ != nullptr) {
        ANativeWindow_release(window_);
        window_ = nullptr;
    }
}

std::string Emulator::startGame(const std::string &romPath, const std::string &sramPath) {
    if (lib_ == nullptr) return "no core loaded";
    if (running_) return "already running";
    if (window_ == nullptr) return "no surface";

    romPath_ = romPath;
    sramPath_ = sramPath;
    {
        std::lock_guard<std::mutex> lock(startMutex_);
        startFinished_ = false;
        startError_.clear();
    }
    stopRequested_ = false;
    running_ = true;
    thread_ = std::thread([this] { runLoop(); });

    std::unique_lock<std::mutex> lock(startMutex_);
    if (!startCv_.wait_for(lock, std::chrono::seconds(20), [this] { return startFinished_; })) {
        startError_ = "timed out starting the core";
    }
    return startError_;
}

void Emulator::runLoop() {
    using clock = std::chrono::steady_clock;

    auto finishStart = [this](const std::string &error) {
        std::lock_guard<std::mutex> lock(startMutex_);
        startError_ = error;
        startFinished_ = true;
        startCv_.notify_all();
    };

    if (!renderer_.init(window_, pixelFormat_)) {
        finishStart("failed to create the GL surface");
        running_ = false;
        return;
    }

    init_();

    retro_game_info game{};
    game.path = romPath_.c_str();
    game.data = nullptr;
    game.size = 0;
    game.meta = nullptr;

    // Cores com need_fullpath == 0 esperam os dados da ROM em memoria.
    // Sem isso o core fica com tamanho 0 e divide por zero (SIGFPE).
    std::vector<uint8_t> romData;
    if (!needFullPath_) {
        FILE *file = std::fopen(romPath_.c_str(), "rb");
        if (file != nullptr) {
            std::fseek(file, 0, SEEK_END);
            long size = std::ftell(file);
            std::fseek(file, 0, SEEK_SET);
            if (size > 0) {
                romData.resize(static_cast<size_t>(size));
                size_t read = std::fread(romData.data(), 1, romData.size(), file);
                romData.resize(read);
            }
            std::fclose(file);
            game.data = romData.data();
            game.size = romData.size();
            LOGI("rom em memoria: %zu bytes", romData.size());
        } else {
            LOGE("nao foi possivel abrir a rom: %s", romPath_.c_str());
        }
    }

    if (!loadGameFn_(&game)) {
        if (deinit_ != nullptr) deinit_();
        renderer_.destroy();
        running_ = false;
        finishStart("the core refused this file");
        return;
    }

    if (setControllerPortDevice_ != nullptr) {
        setControllerPortDevice_(0, RETRO_DEVICE_JOYPAD);
    }

    getSystemAvInfo_(&avInfo_);
    fps_ = avInfo_.timing.fps > 1.0 ? avInfo_.timing.fps : 60.0;
    sampleRate_ = avInfo_.timing.sample_rate > 0 ? avInfo_.timing.sample_rate : 48000.0;
    LOGI("game loaded: %ux%u @ %.2f fps, %.0f Hz", avInfo_.geometry.base_width,
         avInfo_.geometry.base_height, fps_, sampleRate_);

    loadSram();
    gameLoaded_ = true;
    audio_.start(static_cast<int>(sampleRate_));
    finishStart("");

    auto nextFrame = clock::now();
    auto fpsWindow = clock::now();
    int frames = 0;

    while (!stopRequested_) {
        {
            // save/load/reset rodam na thread da UI: o núcleo não pode mudar
            // de estado enquanto retro_run executa (era a causa do SIGSEGV).
            std::lock_guard<std::mutex> lock(stateMutex_);
            runFn_();
        }
        frames++;

        // avisa o core que o buffer de audio tem espaco (evita travamento)
        if (audioBufferCallback_ != nullptr) {
            if (++audioBufferCounter_ >= audioBufferInterval_) {
                audioBufferCounter_ = 0;
                audioBufferCallback_(true, 0, false);
            }
        }

        if (fastForward_) continue;

        auto now = clock::now();
        nextFrame += std::chrono::microseconds(static_cast<long long>(1000000.0 / fps_));
        if (nextFrame < now - std::chrono::milliseconds(100)) nextFrame = now;
        if (nextFrame > now) std::this_thread::sleep_until(nextFrame);

        auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(now - fpsWindow).count();
        if (elapsed >= 1000) {
            measuredFps_.store(frames * 1000.0 / static_cast<double>(elapsed));
            static int report = 0;
            if (report < 3) {
                LOGI("loop: %d quadros em %lld ms (fps %.1f)", frames,
                     static_cast<long long>(elapsed), measuredFps_.load());
                report++;
            }
            frames = 0;
            fpsWindow = now;
        }
    }

    audio_.stop();
    saveSram();
    unloadGame();
    renderer_.destroy();
    running_ = false;
}

void Emulator::stopLoop() {
    stopRequested_ = true;
    if (thread_.joinable()) thread_.join();
    running_ = false;
}

void Emulator::unloadGame() {
    if (!gameLoaded_) return;
    if (unloadGameFn_ != nullptr) unloadGameFn_();
    if (deinit_ != nullptr) deinit_();
    gameLoaded_ = false;
}

void Emulator::setButton(int port, int id, bool pressed) {
    if (port < 0 || port >= 4 || id < 0 || id >= 16) return;
    buttons_[port][id].store(pressed);
}

/**
 * Eixo analógico (libretro pede direcao + magnitude).
 *
 * `index` = 0 (stick esquerdo) ou 1 (stick direito);
 * `axis`  = 0 (X) ou 1 (Y);
 * `value` = -32767..32767 (negativo = direcao negativa).
 *
 * O libretro lê X-/X+/Y-/Y+ em ids 0..3, entao guardamos o par e zeramos o
 * sentido oposto.
 */
void Emulator::setAxis(int port, int index, int axis, int value) {
    if (port < 0 || port >= 4 || index < 0 || index > 1 || axis < 0 || axis > 1) return;
    if (value < -32767) value = -32767;
    if (value > 32767) value = 32767;
    const int base = index * 2;                    // 0 = stick esq., 2 = stick dir.
    const int neg = base + (axis == 0 ? 0 : 2);    // X- / Y-
    const int pos = base + (axis == 0 ? 1 : 3);    // X+ / Y+
    if (value < 0) {
        axis_[port][neg].store(static_cast<int16_t>(-value));
        axis_[port][pos].store(0);
    } else {
        axis_[port][pos].store(static_cast<int16_t>(value));
        axis_[port][neg].store(0);
    }
}

void Emulator::inputPoll() {
    // Polled by the core; the JVM already pushed the current state through setButton().
}

int16_t Emulator::inputState(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port >= 4) return 0;
    if (device == RETRO_DEVICE_JOYPAD) {
        if (id > 15) return 0;
        return buttons_[port][id].load() ? 1 : 0;
    }
    if (device == RETRO_DEVICE_ANALOG) {
        // index 0 = stick esquerdo, 1 = stick direito
        int base = static_cast<int>(index) * 2;
        if (base + 1 >= 4) return 0;
        if (id == 0) return axis_[port][base].load();      // X-
        if (id == 1) return axis_[port][base + 1].load();  // X+
        if (id == 2) return axis_[port][base + 2].load();  // Y-
        if (id == 3) return axis_[port][base + 3].load();  // Y+
        return 0;
    }
    return 0;
}

void Emulator::videoRefresh(const void *data, unsigned width, unsigned height, size_t pitch) {
    static int frames = 0;
    if (frames < 3) {
        LOGI("video refresh: %ux%u pitch=%zu data=%p", width, height, pitch, data);
        frames++;
    }
    if (renderer_.hwActive() && data == RETRO_HW_FRAME_BUFFER_VALID) {
        renderer_.presentHardware(width, height);
        return;
    }
    if (data == nullptr) return; // duped frame: nothing new to draw
    renderer_.presentSoftware(data, width, height, pitch);
}

size_t Emulator::audioBatch(const int16_t *data, size_t frames) {
    audio_.push(data, frames);
    return frames;
}

void Emulator::audioSample(int16_t left, int16_t right) {
    int16_t frame[2] = {left, right};
    audio_.push(frame, 1);
}

namespace {

// O layout de retro_core_option_v2_definition mudou entre versoes do
// libretro.h (values como ponteiro -> array fixo). Detectamos em runtime
// validando os ponteiros antes de usa-los.
inline bool plausiblePtr(const void *p) {
    if (p == nullptr) return true;
    uintptr_t v = reinterpret_cast<uintptr_t>(p);
    return v >= 0x1000 && v < 0x7fffffffffffULL && (v % 2 == 0);
}

inline bool plausibleStr(const char *s) {
    if (s == nullptr) return true;
    if (!plausiblePtr(s)) return false;
    for (int i = 0; i < 24; ++i) {
        unsigned char c = static_cast<unsigned char>(s[i]);
        if (c == 0) return true;
        if (c < 0x20 || c > 0x7e) return false;
    }
    return false;
}

} // namespace

bool Emulator::environment(unsigned cmd, void *data) {
    // Sem log por chamada: alguns cores consultam o ambiente a cada quadro e
    // isso estoura o buffer do logcat.
    const unsigned base = cmd & ~static_cast<unsigned>(RETRO_ENVIRONMENT_EXPERIMENTAL);

    // Comandos que nao usam (ou podem nao ter) o ponteiro de dados.
    switch (base) {
        case RETRO_ENVIRONMENT_SHUTDOWN:
            stopRequested_ = true;
            return true;
        case RETRO_ENVIRONMENT_SET_VARIABLES: {
            auto *vars = static_cast<const retro_variable *>(data);
            if (vars == nullptr) return true;
            for (const retro_variable *var = vars; var->key != nullptr && var->value != nullptr; ++var) {
                variables_[var->key] = var->value;
            }
            return true;
        }
        case RETRO_ENVIRONMENT_SET_AUDIO_BUFFER_STATUS_CALLBACK: {
            // O core usa este callback para saber quando o buffer de audio tem
            // espaco; sem ele o Snes9x fica esperando e a emulacao trava.
            // data == NULL significa "desregistrar".
            auto *cb = static_cast<retro_audio_buffer_status_callback *>(data);
            audioBufferCallback_ = (cb != nullptr) ? cb->callback : nullptr;
            audioBufferInterval_ = 1;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS:
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL: {
            // formato v1: key, desc, info, values*, default_value
            struct OptionV1 {
                const char *key;
                const char *desc;
                const char *info;
                retro_core_option_value *values;
                const char *default_value;
            };
            auto *definitions = static_cast<const OptionV1 *>(data);
            if (definitions == nullptr) return true;
            for (int i = 0; i < 128; ++i) {
                const OptionV1 *def = &definitions[i];
                if (def->key == nullptr) break;
                if (!plausibleStr(def->key) || !plausibleStr(def->default_value)) break;
                std::string value = def->default_value != nullptr ? def->default_value : "";
                if (value.empty() && def->values != nullptr && plausibleStr(def->values[0].value)) {
                    value = def->values[0].value;
                }
                coreOptions_[def->key] = value;
                variables_[def->key] = value;
                LOGI("opcao v1: %s = %s", def->key, value.c_str());
            }
            return true;
        }
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2:
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL: {
            // Tenta primeiro o layout antigo (values como ponteiro) e depois o
            // novo (values como array fixo). A validacao dos ponteiros
            // descarta o layout errado.
            struct OptionV2Old {
                const char *key;
                const char *desc;
                const char *info;
                const char *category_key;
                retro_core_option_value *values;
                const char *default_value;
            };
            struct OptionV2New {
                const char *key;
                const char *desc;
                const char *desc_categorized;
                const char *info;
                const char *info_categorized;
                const char *category_key;
                retro_core_option_value values[RETRO_NUM_CORE_OPTION_VALUES_MAX];
                const char *default_value;
            };

            const char *base = static_cast<const char *>(data);
            if (base == nullptr) return true;

            auto readOld = [&](int index) -> const OptionV2Old * {
                return reinterpret_cast<const OptionV2Old *>(base) + index;
            };

            bool oldLayout = false;
            const OptionV2Old *first = readOld(0);
            if (first->key != nullptr && plausibleStr(first->key) &&
                plausibleStr(first->default_value) &&
                (first->values == nullptr || plausibleStr(first->values[0].value))) {
                oldLayout = true;
            }

            if (oldLayout) {
                for (int i = 0; i < 128; ++i) {
                    const OptionV2Old *def = readOld(i);
                    if (def->key == nullptr) break;
                    if (!plausibleStr(def->key) || !plausibleStr(def->default_value)) break;
                    std::string value = def->default_value != nullptr ? def->default_value : "";
                    if (value.empty() && def->values != nullptr && plausibleStr(def->values[0].value)) {
                        value = def->values[0].value;
                    }
                    coreOptions_[def->key] = value;
                    variables_[def->key] = value;
                    LOGI("opcao v2: %s = %s", def->key, value.c_str());
                }
            } else {
                auto *definitions = reinterpret_cast<const OptionV2New *>(base);
                for (int i = 0; i < 128; ++i) {
                    const OptionV2New *def = &definitions[i];
                    if (def->key == nullptr) break;
                    if (!plausibleStr(def->key) || !plausibleStr(def->default_value)) break;
                    std::string value = def->default_value != nullptr ? def->default_value : "";
                    coreOptions_[def->key] = value;
                    variables_[def->key] = value;
                    LOGI("opcao v2 (novo layout): %s = %s", def->key, value.c_str());
                }
            }
            return true;
        }
        default:
            break;
    }

    // Qualquer outro comando pode vir sem dados (o core do Snes9x chama
    // GET_INPUT_BITMASKS com data == NULL) - nunca desreferencie.
    if (data == nullptr) {
        if (loggedCommands_.find(base | 0x80000000u) == loggedCommands_.end()) {
            loggedCommands_.insert(base | 0x80000000u);
            LOGI("env cmd=%u ignorado (sem dados)", base);
        }
        return false;
    }

    switch (base) {
        case RETRO_ENVIRONMENT_GET_CAN_DUPE:
            *static_cast<bool *>(data) = true;
            return true;
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            auto format = *static_cast<const retro_pixel_format *>(data);
            renderer_.setPixelFormat(format);
            LOGI("pixel format %d", static_cast<int>(format));
            return true;
        }
        case RETRO_ENVIRONMENT_SET_HW_RENDER: {
            auto *cb = static_cast<retro_hw_render_callback *>(data);
            if (cb->context_type != RETRO_HW_CONTEXT_OPENGLES2 &&
                cb->context_type != RETRO_HW_CONTEXT_OPENGLES3 &&
                cb->context_type != RETRO_HW_CONTEXT_OPENGL_CORE) {
                LOGW("unsupported hw context %d", cb->context_type);
                return false;
            }
            bool depth = cb->depth;
            bool stencil = cb->stencil;
            bool bottomLeft = cb->bottom_left_origin != 0;
            int major = static_cast<int>(cb->version_major);
            int minor = static_cast<int>(cb->version_minor);

            cb->get_current_framebuffer = []() -> uintptr_t {
                return instance().renderer_.currentFramebuffer();
            };
            cb->get_proc_address = [](const char *sym) -> retro_proc_address_t {
                void *proc = reinterpret_cast<void *>(eglGetProcAddress(sym));
                if (proc == nullptr) proc = dlsym(RTLD_DEFAULT, sym);
                return reinterpret_cast<retro_proc_address_t>(proc);
            };
            cb->context_reset = []() { instance().renderer_.contextReset(); };
            hwRender_ = *cb;

            renderer_.enableHardware(true, avInfo_.geometry.max_width, avInfo_.geometry.max_height,
                                     depth, stencil, bottomLeft, major, minor);
            ownsHwRender_ = true;
            LOGI("hw render enabled: GL %d.%d depth=%d stencil=%d", major, minor, depth, stencil);
            return true;
        }
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
            *static_cast<const char **>(data) = systemDir_.c_str();
            return true;
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            *static_cast<const char **>(data) = saveDir_.c_str();
            return true;
        case RETRO_ENVIRONMENT_GET_CONTENT_DIRECTORY:
            *static_cast<const char **>(data) = saveDir_.c_str();
            return true;
        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO: {
            auto *info = static_cast<const retro_system_av_info *>(data);
            avInfo_ = *info;
            fps_ = info->timing.fps > 1.0 ? info->timing.fps : fps_;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:
            return true;
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: {
            auto *cb = static_cast<retro_log_callback *>(data);
            cb->log = coreLog;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
            return true;
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            auto *var = static_cast<retro_variable *>(data);
            if (var->key == nullptr) return false;
            auto it = variables_.find(var->key);
            if (it == variables_.end()) {
                if (loggedCommands_.find(0x1000) == loggedCommands_.end()) {
                    loggedCommands_.insert(0x1000);
                    LOGI("variavel desconhecida: %s", var->key);
                }
                var->value = nullptr;
                return false;
            }
            var->value = it->second.c_str();
            return true;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
            *static_cast<bool *>(data) = false;
            return true;
        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
            // Usamos apenas a API legada de variaveis (retro_variable), cujo
            // layout e estavel. A v2 de core options muda de layout entre
            // versoes do libretro.h e quebraria o parsing.
            *static_cast<unsigned *>(data) = 0;
            return true;
        case RETRO_ENVIRONMENT_GET_LANGUAGE: {
            // o core usa o idioma para escolher a regiao (ex.: Snes9x divide por zero
            // quando o idioma nao e informado)
            *static_cast<const char **>(data) = language_.c_str();
            return true;
        }
        case RETRO_ENVIRONMENT_GET_USERNAME: {
            *static_cast<const char **>(data) = "RetroBox";
            return true;
        }
        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
            *static_cast<bool *>(data) = false;
            return true;
        case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE:
            *static_cast<int *>(data) = 3;
            return true;
        case RETRO_ENVIRONMENT_GET_FASTFORWARDING:
            *static_cast<bool *>(data) = fastForward_;
            return true;
        case RETRO_ENVIRONMENT_GET_TARGET_REFRESH_RATE:
            *static_cast<float *>(data) = 60.0f;
            return true;
        case RETRO_ENVIRONMENT_SET_MINIMUM_AUDIO_LATENCY:
            return true;
        case RETRO_ENVIRONMENT_SHUTDOWN:
            stopRequested_ = true;
            return true;
        default:
            // comandos desconhecidos sao ignorados (log unico por comando)
            if (loggedCommands_.find(base) == loggedCommands_.end()) {
                loggedCommands_.insert(base);
                LOGI("env: comando nao suportado %u", base);
            }
            return false;
    }
}

std::vector<uint8_t> Emulator::serializeState() const {
    std::lock_guard<std::mutex> lock(stateMutex_);
    std::vector<uint8_t> out;
    if (!gameLoaded_ || serializeSize_ == nullptr || serializeFn_ == nullptr) return out;
    size_t size = serializeSize_();
    if (size == 0) return out;
    out.resize(size);
    if (!serializeFn_(out.data(), size)) out.clear();
    return out;
}

bool Emulator::unserializeState(const uint8_t *data, size_t size) {
    std::lock_guard<std::mutex> lock(stateMutex_);
    if (!gameLoaded_ || unserializeFn_ == nullptr) return false;
    return unserializeFn_(data, size);
}

void Emulator::reset() {
    std::lock_guard<std::mutex> lock(stateMutex_);
    if (gameLoaded_ && resetFn_ != nullptr) resetFn_();
}

void Emulator::setLinearFilter(bool linear) { renderer_.setLinearFilter(linear); }

void Emulator::setAspectMode(int mode) { renderer_.setAspectMode(mode); }

std::string Emulator::geometryInfo() const { return renderer_.geometryInfo(); }

std::string Emulator::systemInfo() const {
    if (lib_ == nullptr || getSystemInfo_ == nullptr) return "";
    retro_system_info info{};
    getSystemInfo_(&info);
    return std::string(info.library_name ? info.library_name : "") + " " +
           (info.library_version ? info.library_version : "");
}

void Emulator::loadSram() {
    if (sramPath_.empty() || getMemoryData_ == nullptr || getMemorySize_ == nullptr) return;
    size_t size = getMemorySize_(RETRO_MEMORY_SAVE_RAM);
    void *dest = getMemoryData_(RETRO_MEMORY_SAVE_RAM);
    if (size == 0 || dest == nullptr) return;

    FILE *file = std::fopen(sramPath_.c_str(), "rb");
    if (file == nullptr) return;
    size_t read = std::fread(dest, 1, size, file);
    std::fclose(file);
    LOGI("loaded SRAM %zu/%zu bytes", read, size);
}

void Emulator::saveSram() {
    if (sramPath_.empty() || getMemoryData_ == nullptr || getMemorySize_ == nullptr) return;
    size_t size = getMemorySize_(RETRO_MEMORY_SAVE_RAM);
    void *data = getMemoryData_(RETRO_MEMORY_SAVE_RAM);
    if (size == 0 || data == nullptr) return;

    FILE *file = std::fopen(sramPath_.c_str(), "wb");
    if (file == nullptr) return;
    std::fwrite(data, 1, size, file);
    std::fclose(file);
    LOGI("saved SRAM %zu bytes", size);
}

} // namespace retrobox
