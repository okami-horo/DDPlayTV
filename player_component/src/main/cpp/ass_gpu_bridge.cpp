#include <jni.h>

#include <android/log.h>
#include <android/native_window_jni.h>
#include <ass/ass.h>

#include <chrono>
#include <cstring>
#include <cstdarg>
#include <mutex>
#include <new>
#include <string>
#include <vector>

#include "libass_bridge.h"

namespace {
constexpr const char *kGpuLogTag = "AssGpuBridge";

struct GpuContext {
    std::mutex mutex;
    ANativeWindow *window = nullptr;
    int width = 0;
    int height = 0;
    float scale = 1.0F;
    int rotation = 0;
    std::string color_format;
    bool supports_hardware_buffer = false;
    bool telemetry_enabled = true;
    int64_t last_vsync_id = 0;
    ASS_Library *library = nullptr;
    ASS_Renderer *renderer = nullptr;
    ASS_Track *track = nullptr;
};

void LogInfo(const char *message) {
    __android_log_print(ANDROID_LOG_INFO, kGpuLogTag, "%s", message);
}

void LogError(const char *message) {
    __android_log_print(ANDROID_LOG_ERROR, kGpuLogTag, "%s", message);
}

void LibassMessageCallback(int level, const char *fmt, va_list args, void *data) {
    (void)data;
    char buffer[1024];
    if (fmt != nullptr) {
        vsnprintf(buffer, sizeof(buffer), fmt, args);
        buffer[sizeof(buffer) - 1] = '\0';
    } else {
        std::strncpy(buffer, "(null)", sizeof(buffer));
        buffer[sizeof(buffer) - 1] = '\0';
    }
    int android_level = ANDROID_LOG_DEBUG;
    if (level <= 1) {
        android_level = ANDROID_LOG_ERROR;
    } else if (level <= 3) {
        android_level = ANDROID_LOG_WARN;
    } else if (level <= 5) {
        android_level = ANDROID_LOG_INFO;
    }
    __android_log_print(android_level, kGpuLogTag, "libass[%d]: %s", level, buffer);
}

void ReleaseWindow(GpuContext *context) {
    if (context == nullptr) {
        return;
    }
    if (context->window != nullptr) {
        ANativeWindow_release(context->window);
        context->window = nullptr;
    }
}

std::string JStringToUtf8(JNIEnv *env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char *raw = env->GetStringUTFChars(value, nullptr);
    if (raw == nullptr) {
        return {};
    }
    std::string result(raw);
    env->ReleaseStringUTFChars(value, raw);
    return result;
}

std::vector<std::string> JObjectArrayToStrings(JNIEnv *env, jobjectArray array) {
    std::vector<std::string> paths;
    if (array == nullptr) {
        return paths;
    }
    const jsize length = env->GetArrayLength(array);
    paths.reserve(length);
    for (jsize i = 0; i < length; ++i) {
        auto string_obj = static_cast<jstring>(env->GetObjectArrayElement(array, i));
        if (string_obj != nullptr) {
            paths.push_back(JStringToUtf8(env, string_obj));
            env->DeleteLocalRef(string_obj);
        }
    }
    return paths;
}

jlongArray BuildRenderMetrics(JNIEnv *env, bool rendered, jlong render_ms = 0,
                              jlong upload_ms = 0, jlong composite_ms = 0) {
    jlongArray array = env->NewLongArray(4);
    if (array == nullptr) {
        return nullptr;
    }
    jlong values[4] = {rendered ? 1 : 0, render_ms, upload_ms, composite_ms};
    env->SetLongArrayRegion(array, 0, 4, values);
    return array;
}

void DestroyAss(GpuContext *context) {
    if (context == nullptr) return;
    if (context->track != nullptr) {
        ass_free_track(context->track);
        context->track = nullptr;
    }
    if (context->renderer != nullptr) {
        ass_renderer_done(context->renderer);
        context->renderer = nullptr;
    }
    if (context->library != nullptr) {
        ass_library_done(context->library);
        context->library = nullptr;
    }
}

void EnsureAss(GpuContext *context) {
    if (context->library == nullptr) {
        context->library = ass_library_init();
        ass_set_message_cb(context->library, LibassMessageCallback, nullptr);
    }
    if (context->renderer == nullptr) {
        context->renderer = ass_renderer_init(context->library);
    }
}

void ConfigureFonts(GpuContext *context, const std::string &default_font,
                    const std::vector<std::string> &font_dirs) {
    if (context == nullptr || context->library == nullptr || context->renderer == nullptr) {
        return;
    }
    const std::string *selected_dir = nullptr;
    for (const auto &dir : font_dirs) {
        if (!dir.empty()) {
            selected_dir = &dir;
            break;
        }
    }
    if (selected_dir != nullptr) {
        ass_set_fonts_dir(context->library, selected_dir->c_str());
    }
    const char *font_ptr = default_font.empty() ? nullptr : default_font.c_str();
    ass_set_fonts(context->renderer, font_ptr, nullptr, ASS_FONTPROVIDER_NONE, nullptr, 0);
}

inline uint8_t MulDiv255(uint32_t value, uint32_t scale) {
    return static_cast<uint8_t>((value * scale + 128) / 255);
}

inline uint8_t AssAlphaToAndroid(uint32_t color) {
    const uint8_t ass_alpha = static_cast<uint8_t>(color & 0xFF);
    return static_cast<uint8_t>(255 - ass_alpha);
}

void BlendPixel(uint8_t *dst, uint8_t coverage, uint32_t color, uint8_t effective_alpha) {
    if (effective_alpha == 0 || coverage == 0) {
        return;
    }
    const uint8_t src_alpha = MulDiv255(coverage, effective_alpha);
    if (src_alpha == 0) {
        return;
    }

    const uint8_t red = static_cast<uint8_t>((color >> 24) & 0xFF);
    const uint8_t green = static_cast<uint8_t>((color >> 16) & 0xFF);
    const uint8_t blue = static_cast<uint8_t>((color >> 8) & 0xFF);

    const uint8_t src_r = MulDiv255(red, src_alpha);
    const uint8_t src_g = MulDiv255(green, src_alpha);
    const uint8_t src_b = MulDiv255(blue, src_alpha);

    uint8_t dst_r = dst[0];
    uint8_t dst_g = dst[1];
    uint8_t dst_b = dst[2];
    uint8_t dst_a = dst[3];

    const uint8_t inv_alpha = static_cast<uint8_t>(255 - src_alpha);
    dst_r = static_cast<uint8_t>(src_r + MulDiv255(dst_r, inv_alpha));
    dst_g = static_cast<uint8_t>(src_g + MulDiv255(dst_g, inv_alpha));
    dst_b = static_cast<uint8_t>(src_b + MulDiv255(dst_b, inv_alpha));
    dst_a = static_cast<uint8_t>(src_alpha + MulDiv255(dst_a, inv_alpha));

    dst[0] = dst_r;
    dst[1] = dst_g;
    dst[2] = dst_b;
    dst[3] = dst_a;
}

void ClearWindow(ANativeWindow_Buffer &buffer) {
    uint8_t *dst = static_cast<uint8_t *>(buffer.bits);
    if (dst == nullptr) return;
    const int stride_bytes = buffer.stride * 4;
    for (int y = 0; y < buffer.height; ++y) {
        std::memset(dst + y * stride_bytes, 0, stride_bytes);
    }
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_xyoye_player_1component_subtitle_gpu_AssGpuNativeBridge_nativeCreate(
    JNIEnv *env, jobject /*thiz*/) {
    (void)env;
    auto *context = new (std::nothrow) GpuContext();
    if (context == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kGpuLogTag, "Failed to allocate GPU context");
        return 0;
    }
    LogInfo("GPU context created");
    return reinterpret_cast<jlong>(context);
}

extern "C" JNIEXPORT void JNICALL
Java_com_xyoye_player_1component_subtitle_gpu_AssGpuNativeBridge_nativeDestroy(
    JNIEnv *env, jobject /*thiz*/, jlong handle) {
    (void)env;
    auto *context = reinterpret_cast<GpuContext *>(handle);
    if (context == nullptr) {
        return;
    }
    {
        std::lock_guard<std::mutex> guard(context->mutex);
        DestroyAss(context);
        ReleaseWindow(context);
    }
    delete context;
    LogInfo("GPU context destroyed");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xyoye_player_1component_subtitle_gpu_AssGpuNativeBridge_nativeAttachSurface(
    JNIEnv *env,
    jobject /*thiz*/,
    jlong handle,
    jobject surface,
    jint width,
    jint height,
    jfloat scale,
    jint rotation,
    jstring color_format,
    jboolean supports_hardware_buffer,
    jlong vsync_id) {
    auto *context = reinterpret_cast<GpuContext *>(handle);
    if (context == nullptr) {
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> guard(context->mutex);
    ReleaseWindow(context);
    if (surface != nullptr) {
        context->window = ANativeWindow_fromSurface(env, surface);
    }
    context->width = width;
    context->height = height;
    context->scale = scale;
    context->rotation = rotation;
    context->color_format = JStringToUtf8(env, color_format);
    context->supports_hardware_buffer = supports_hardware_buffer == JNI_TRUE;
    context->last_vsync_id = vsync_id;
    LogInfo("GPU surface attached");
    return context->window != nullptr ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_xyoye_player_1component_subtitle_gpu_AssGpuNativeBridge_nativeDetachSurface(
    JNIEnv *env, jobject /*thiz*/, jlong handle) {
    (void)env;
    auto *context = reinterpret_cast<GpuContext *>(handle);
    if (context == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> guard(context->mutex);
    ReleaseWindow(context);
    context->width = 0;
    context->height = 0;
    context->last_vsync_id = 0;
    LogInfo("GPU surface detached");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xyoye_player_1component_subtitle_gpu_AssGpuNativeBridge_nativeLoadTrack(
    JNIEnv *env,
    jobject /*thiz*/,
    jlong handle,
    jstring path,
    jobjectArray font_dirs,
    jstring default_font) {
    auto *context = reinterpret_cast<GpuContext *>(handle);
    if (context == nullptr) return JNI_FALSE;
    std::lock_guard<std::mutex> guard(context->mutex);
    EnsureAss(context);
    const std::string track_path = JStringToUtf8(env, path);
    const auto fontDirectories = JObjectArrayToStrings(env, font_dirs);
    const std::string defaultFontPath = JStringToUtf8(env, default_font);
    ConfigureFonts(context, defaultFontPath, fontDirectories);
    if (context->track != nullptr) {
        ass_free_track(context->track);
        context->track = nullptr;
    }
    context->track = ass_read_file(context->library, track_path.c_str(), nullptr);
    if (context->track == nullptr) {
        LogError("Failed to load subtitle track for GPU pipeline");
        return JNI_FALSE;
    }
    if (context->width > 0 && context->height > 0) {
        ass_set_frame_size(context->renderer, context->width, context->height);
    }
    LogInfo("GPU subtitle track loaded");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_xyoye_player_1component_subtitle_gpu_AssGpuNativeBridge_nativeRender(
    JNIEnv *env,
    jobject /*thiz*/,
    jlong handle,
    jlong subtitle_pts_ms,
    jlong vsync_id,
    jboolean telemetry_enabled) {
    auto *context = reinterpret_cast<GpuContext *>(handle);
    if (context == nullptr) {
        return BuildRenderMetrics(env, false);
    }
    std::lock_guard<std::mutex> guard(context->mutex);
    context->last_vsync_id = vsync_id;
    context->telemetry_enabled = telemetry_enabled == JNI_TRUE;
    if (context->window == nullptr || context->renderer == nullptr || context->track == nullptr) {
        return BuildRenderMetrics(env, false);
    }
    if (context->width > 0 && context->height > 0) {
        ass_set_frame_size(context->renderer, context->width, context->height);
    }

    ANativeWindow_setBuffersGeometry(context->window, context->width, context->height,
                                     WINDOW_FORMAT_RGBA_8888);
    ANativeWindow_Buffer buffer{};
    if (ANativeWindow_lock(context->window, &buffer, nullptr) != 0) {
        LogError("GPU subtitle: failed to lock window");
        return BuildRenderMetrics(env, false);
    }
    ClearWindow(buffer);

    int change = 0;
    auto render_start = std::chrono::steady_clock::now();
    ASS_Image *img = ass_render_frame(context->renderer, context->track,
                                      static_cast<int>(subtitle_pts_ms), &change);
    auto render_end = std::chrono::steady_clock::now();

    if (img != nullptr) {
        const int stride_bytes = buffer.stride * 4;
        for (ASS_Image *cur = img; cur != nullptr; cur = cur->next) {
            if (cur->w <= 0 || cur->h <= 0) continue;
            const uint8_t alpha = AssAlphaToAndroid(cur->color);
            if (alpha == 0) continue;
            for (int y = 0; y < cur->h; ++y) {
                uint8_t *dst = static_cast<uint8_t *>(buffer.bits) +
                               (cur->dst_y + y) * stride_bytes + cur->dst_x * 4;
                uint8_t *src = cur->bitmap + y * cur->stride;
                for (int x = 0; x < cur->w; ++x) {
                    BlendPixel(dst + x * 4, src[x], cur->color, alpha);
                }
            }
        }
    }
    auto upload_end = std::chrono::steady_clock::now();
    ANativeWindow_unlockAndPost(context->window);

    const auto render_latency =
        std::chrono::duration_cast<std::chrono::microseconds>(render_end - render_start).count() /
        1000;
    const auto upload_latency =
        std::chrono::duration_cast<std::chrono::microseconds>(upload_end - render_end).count() /
        1000;
    return BuildRenderMetrics(env, true, render_latency, upload_latency, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_xyoye_player_1component_subtitle_gpu_AssGpuNativeBridge_nativeFlush(
    JNIEnv *env, jobject /*thiz*/, jlong handle) {
    (void)env;
    auto *context = reinterpret_cast<GpuContext *>(handle);
    if (context == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> guard(context->mutex);
    LogInfo("GPU pipeline flush requested");
}

extern "C" JNIEXPORT void JNICALL
Java_com_xyoye_player_1component_subtitle_gpu_AssGpuNativeBridge_nativeSetTelemetryEnabled(
    JNIEnv *env, jobject /*thiz*/, jlong handle, jboolean enabled) {
    (void)env;
    auto *context = reinterpret_cast<GpuContext *>(handle);
    if (context == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> guard(context->mutex);
    context->telemetry_enabled = enabled == JNI_TRUE;
    LogInfo("GPU telemetry toggle updated");
}
