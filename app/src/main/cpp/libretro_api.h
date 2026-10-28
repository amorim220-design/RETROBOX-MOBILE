// Minimal libretro API surface used by the RetroBox frontend.
// Mirrors the definitions from libretro.h (MIT licensed upstream header).
#pragma once

#include <cstddef>
#include <cstdint>

#define RETRO_API_VERSION 1

#define RETRO_ENVIRONMENT_EXPERIMENTAL 0x10000
#define RETRO_ENVIRONMENT_PRIVATE 0x20000

enum retro_pixel_format {
    RETRO_PIXEL_FORMAT_0RGB1555 = 0,
    RETRO_PIXEL_FORMAT_XRGB8888 = 1,
    RETRO_PIXEL_FORMAT_RGB565 = 2,
};

enum retro_hw_context_type {
    RETRO_HW_CONTEXT_NONE = 0,
    RETRO_HW_CONTEXT_OPENGL = 1,
    RETRO_HW_CONTEXT_OPENGLES2 = 2,
    RETRO_HW_CONTEXT_OPENGL_CORE = 3,
    RETRO_HW_CONTEXT_OPENGLES3 = 4,
    RETRO_HW_CONTEXT_OPENGLES_VERSION = 5,
};

enum retro_commands {
    RETRO_ENVIRONMENT_GET_OVERSCAN = 2,
    RETRO_ENVIRONMENT_GET_CAN_DUPE = 3,
    RETRO_ENVIRONMENT_SET_MESSAGE = 6,
    RETRO_ENVIRONMENT_SHUTDOWN = 7,
    RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL = 8,
    RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY = 9,
    RETRO_ENVIRONMENT_SET_PIXEL_FORMAT = 10,
    RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS = 11,
    RETRO_ENVIRONMENT_SET_KEYBOARD_CALLBACK = 12,
    RETRO_ENVIRONMENT_SET_DISK_CONTROL_INTERFACE = 13,
    RETRO_ENVIRONMENT_SET_HW_RENDER = 14,
    RETRO_ENVIRONMENT_GET_VARIABLE = 15,
    RETRO_ENVIRONMENT_SET_VARIABLES = 16,
    RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE = 17,
    RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME = 18,
    RETRO_ENVIRONMENT_GET_LIBRETRO_PATH = 19,
    RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE = 23,
    RETRO_ENVIRONMENT_GET_INPUT_DEVICE_CAPABILITIES = 24,
    RETRO_ENVIRONMENT_GET_LOG_INTERFACE = 27,
    RETRO_ENVIRONMENT_GET_PERF_INTERFACE = 28,
    RETRO_ENVIRONMENT_GET_LOCATION_INTERFACE = 29,
    RETRO_ENVIRONMENT_GET_CONTENT_DIRECTORY = 30,
    RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY = 31,
    RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO = 32,
    RETRO_ENVIRONMENT_SET_SUPPORT_ACHIEVEMENTS = 42,
    RETRO_ENVIRONMENT_SET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE = 43,
    RETRO_ENVIRONMENT_SET_SERIALIZATION_QUIRKS = 44,
    RETRO_ENVIRONMENT_GET_VFS_INTERFACE = 45,
    RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE = 47,
    RETRO_ENVIRONMENT_GET_FASTFORWARDING = 49,
    RETRO_ENVIRONMENT_GET_TARGET_REFRESH_RATE = 50,
    RETRO_ENVIRONMENT_GET_INPUT_BITMASKS = 51,
    RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION = 52,
    RETRO_ENVIRONMENT_SET_CORE_OPTIONS = 53,
    RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL = 54,
    RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2 = 67,
    RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL = 68,
    RETRO_ENVIRONMENT_SET_CORE_OPTIONS_UPDATE_DISPLAY_CALLBACK = 69,
    RETRO_ENVIRONMENT_SET_VARIABLE = 70,
    RETRO_ENVIRONMENT_GET_CURRENT_SOFTWARE_FRAMEBUFFER = (40 | RETRO_ENVIRONMENT_EXPERIMENTAL),
};

enum retro_device {
    RETRO_DEVICE_NONE = 0,
    RETRO_DEVICE_JOYPAD = 1,
    RETRO_DEVICE_ANALOG = 5,
};

enum retro_device_id {
    RETRO_DEVICE_ID_JOYPAD_B = 0,
    RETRO_DEVICE_ID_JOYPAD_Y = 1,
    RETRO_DEVICE_ID_JOYPAD_SELECT = 2,
    RETRO_DEVICE_ID_JOYPAD_START = 3,
    RETRO_DEVICE_ID_JOYPAD_UP = 4,
    RETRO_DEVICE_ID_JOYPAD_DOWN = 5,
    RETRO_DEVICE_ID_JOYPAD_LEFT = 6,
    RETRO_DEVICE_ID_JOYPAD_RIGHT = 7,
    RETRO_DEVICE_ID_JOYPAD_A = 8,
    RETRO_DEVICE_ID_JOYPAD_X = 9,
    RETRO_DEVICE_ID_JOYPAD_L = 10,
    RETRO_DEVICE_ID_JOYPAD_R = 11,
    RETRO_DEVICE_ID_JOYPAD_L2 = 12,
    RETRO_DEVICE_ID_JOYPAD_R2 = 13,
    RETRO_DEVICE_ID_JOYPAD_L3 = 14,
    RETRO_DEVICE_ID_JOYPAD_R3 = 15,
};

enum retro_memory_type {
    RETRO_MEMORY_SAVE_RAM = 0,
    RETRO_MEMORY_RTC = 1,
    RETRO_MEMORY_SYSTEM_RAM = 2,
    RETRO_MEMORY_VIDEO_RAM = 3,
};

enum retro_log_level {
    RETRO_LOG_DEBUG = 0,
    RETRO_LOG_INFO = 1,
    RETRO_LOG_WARN = 2,
    RETRO_LOG_ERROR = 3,
};

#define RETRO_HW_FRAME_BUFFER_VALID ((void *) -1)

struct retro_game_info {
    const char *path;
    const void *data;
    size_t size;
    const char *meta;
};

struct retro_game_geometry {
    unsigned base_width;
    unsigned base_height;
    unsigned max_width;
    unsigned max_height;
    float aspect_ratio;
};

struct retro_system_timing {
    double fps;
    double sample_rate;
};

struct retro_system_av_info {
    retro_game_geometry geometry;
    retro_system_timing timing;
};

struct retro_system_info {
    const char *library_name;
    const char *library_version;
    const char *valid_extensions;
    bool need_fullpath;
    bool block_extract;
};

struct retro_variable {
    const char *key;
    const char *value;
};

struct retro_log_callback {
    void (*log)(enum retro_log_level level, const char *fmt, ...);
};

struct retro_hw_render_callback;

typedef void (*retro_hw_context_reset_t)(void);
typedef uintptr_t (*retro_hw_get_current_framebuffer_t)(void);
typedef void *(*retro_hw_get_proc_address_t)(const char *sym);

struct retro_hw_render_callback {
    enum retro_hw_context_type context_type;
    retro_hw_context_reset_t context_reset;
    retro_hw_get_current_framebuffer_t get_current_framebuffer;
    retro_hw_get_proc_address_t get_proc_address;
    bool depth;
    bool stencil;
    unsigned bottom_left_origin;
    unsigned version_major;
    unsigned version_minor;
    bool cache_context;
    retro_hw_context_reset_t context_destroy;
    bool debug_context;
};

typedef void (*retro_video_refresh_t)(const void *data, unsigned width, unsigned height, size_t pitch);
typedef void (*retro_audio_sample_t)(int16_t left, int16_t right);
typedef size_t (*retro_audio_sample_batch_t)(const int16_t *data, size_t frames);
typedef void (*retro_input_poll_t)(void);
typedef int16_t (*retro_input_state_t)(unsigned port, unsigned device, unsigned index, unsigned id);
typedef bool (*retro_environment_t)(unsigned cmd, void *data);

// --- function pointers exported by every libretro core (.so) ---
typedef void (*fn_init)(void);
typedef void (*fn_deinit)(void);
typedef unsigned (*fn_api_version)(void);
typedef void (*fn_get_system_info)(struct retro_system_info *);
typedef void (*fn_get_system_av_info)(struct retro_system_av_info *);
typedef void (*fn_set_environment)(retro_environment_t);
typedef void (*fn_set_video_refresh)(retro_video_refresh_t);
typedef void (*fn_set_audio_sample)(retro_audio_sample_t);
typedef void (*fn_set_audio_sample_batch)(retro_audio_sample_batch_t);
typedef void (*fn_set_input_poll)(retro_input_poll_t);
typedef void (*fn_set_input_state)(retro_input_state_t);
typedef bool (*fn_load_game)(const struct retro_game_info *);
typedef void (*fn_unload_game)(void);
typedef void (*fn_run)(void);
typedef void (*fn_reset)(void);
typedef size_t (*fn_serialize_size)(void);
typedef bool (*fn_serialize)(void *, size_t);
typedef bool (*fn_unserialize)(const void *, size_t);
typedef void *(*fn_get_memory_data)(unsigned);
typedef size_t (*fn_get_memory_size)(unsigned);
typedef void (*fn_set_controller_port_device)(unsigned, unsigned);
