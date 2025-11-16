#include "libass_bridge.h"

#include <android/bitmap.h>
#include <android/log.h>

#include <ass/ass.h>

#include <algorithm>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace {
constexpr const char *kLogTag = "LibassBridge";

struct LibassContext {
    ASS_Library *library = nullptr;
    ASS_Renderer *renderer = nullptr;
    ASS_Track *track = nullptr;
    int frame_width = 0;
    int frame_height = 0;
};

void LogError(const char *message) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", message);
}

void LogInfo(const char *message) {
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "%s", message);
}

std::string JStringToUtf8(JNIEnv *env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char *raw = env->GetStringUTFChars(value, nullptr);
    if (raw == nullptr) {
        return {};
    }
    std::string str(raw);
    env->ReleaseStringUTFChars(value, raw);
    return str;
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

void ReleaseTrack(LibassContext *context) {
    if (context != nullptr && context->track != nullptr) {
        ass_free_track(context->track);
        context->track = nullptr;
    }
}

void DestroyContext(LibassContext *context) {
    if (context == nullptr) {
        return;
    }
    ReleaseTrack(context);
    if (context->renderer != nullptr) {
        ass_renderer_done(context->renderer);
        context->renderer = nullptr;
    }
    if (context->library != nullptr) {
        ass_library_done(context->library);
        context->library = nullptr;
    }
    delete context;
}

LibassContext *FromHandle(jlong handle) {
    return reinterpret_cast<LibassContext *>(handle);
}

void ConfigureFonts(LibassContext *context, const std::string &default_font,
                    const std::vector<std::string> &font_dirs) {
    if (context == nullptr || context->library == nullptr || context->renderer == nullptr) {
        return;
    }
    for (const auto &dir : font_dirs) {
        if (!dir.empty()) {
            ass_set_fonts_dir(context->library, dir.c_str());
        }
    }

    const char *font_ptr = default_font.empty() ? nullptr : default_font.c_str();
    ass_set_fonts(context->renderer, font_ptr, nullptr, ASS_FONTPROVIDER_AUTODETECT, nullptr, 0);
}

struct BitmapGuard {
    JNIEnv *env = nullptr;
    jobject bitmap = nullptr;
    AndroidBitmapInfo info{};
    uint8_t *pixels = nullptr;

    bool Lock(JNIEnv *env_ptr, jobject bmp) {
        env = env_ptr;
        bitmap = bmp;
        if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
            LogError("Failed to get bitmap info for libass render target");
            return false;
        }
        if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
            LogError("Bitmap must use ARGB_8888 format");
            return false;
        }
        if (AndroidBitmap_lockPixels(env, bitmap, reinterpret_cast<void **>(&pixels)) !=
            ANDROID_BITMAP_RESULT_SUCCESS) {
            pixels = nullptr;
            LogError("Failed to lock bitmap pixels");
            return false;
        }
        return true;
    }

    ~BitmapGuard() {
        if (pixels != nullptr) {
            AndroidBitmap_unlockPixels(env, bitmap);
        }
    }
};

inline uint8_t MulDiv255(uint32_t value, uint32_t scale) {
    return static_cast<uint8_t>((value * scale + 128) / 255);
}

inline uint8_t AssAlphaToAndroid(uint32_t color) {
    const uint8_t ass_alpha = static_cast<uint8_t>((color >> 24) & 0xFF);
    return 255 - ass_alpha;
}

void BlendPixel(uint8_t *dst, uint8_t coverage, uint32_t color) {
    const uint8_t base_alpha = AssAlphaToAndroid(color);
    if (base_alpha == 0 || coverage == 0) {
        return;
    }
    const uint8_t src_alpha = MulDiv255(coverage, base_alpha);
    if (src_alpha == 0) {
        return;
    }

    const uint8_t red = static_cast<uint8_t>(color & 0xFF);
    const uint8_t green = static_cast<uint8_t>((color >> 8) & 0xFF);
    const uint8_t blue = static_cast<uint8_t>((color >> 16) & 0xFF);

    const uint8_t src_r = MulDiv255(red, src_alpha);
    const uint8_t src_g = MulDiv255(green, src_alpha);
    const uint8_t src_b = MulDiv255(blue, src_alpha);

    // Android NDK reports RGBA_8888 for Bitmap.Config.ARGB_8888.
    // The in-memory byte order for each pixel is RGBA (R at [0], A at [3]).
    // Read existing destination in RGBA byte order to avoid channel swaps.
    uint8_t dst_r = dst[0];
    uint8_t dst_g = dst[1];
    uint8_t dst_b = dst[2];
    uint8_t dst_a = dst[3];

    const uint8_t inv_alpha = static_cast<uint8_t>(255 - src_alpha);
    dst_r = static_cast<uint8_t>(src_r + MulDiv255(dst_r, inv_alpha));
    dst_g = static_cast<uint8_t>(src_g + MulDiv255(dst_g, inv_alpha));
    dst_b = static_cast<uint8_t>(src_b + MulDiv255(dst_b, inv_alpha));
    dst_a = static_cast<uint8_t>(src_alpha + MulDiv255(dst_a, inv_alpha));

    // Write back in RGBA byte order.
    dst[0] = dst_r;
    dst[1] = dst_g;
    dst[2] = dst_b;
    dst[3] = dst_a;
}

void CompositeImage(uint8_t *buffer, const AndroidBitmapInfo &info, const ASS_Image *image) {
    if (info.width <= 0 || info.height <= 0 || image == nullptr || image->w <= 0 || image->h <= 0) {
        return;
    }
    const int start_x = std::max(image->dst_x, 0);
    const int start_y = std::max(image->dst_y, 0);
    const int end_x = std::min(image->dst_x + image->w, static_cast<int>(info.width));
    const int end_y = std::min(image->dst_y + image->h, static_cast<int>(info.height));
    if (start_x >= end_x || start_y >= end_y) {
        return;
    }

    const int offset_x = start_x - image->dst_x;
    for (int y = start_y; y < end_y; ++y) {
        const int src_row = y - image->dst_y;
        const uint8_t *src =
            image->bitmap + src_row * image->stride + offset_x;
        uint8_t *dst = buffer + static_cast<size_t>(y) * info.stride + start_x * 4;
        for (int x = start_x; x < end_x; ++x) {
            const uint8_t coverage = *src++;
            if (coverage != 0) {
                BlendPixel(dst, coverage, image->color);
            }
            dst += 4;
        }
    }
}

void CompositeAssImages(BitmapGuard &guard, const ASS_Image *image) {
    for (const ASS_Image *node = image; node != nullptr; node = node->next) {
        CompositeImage(guard.pixels, guard.info, node);
    }
}

}  // namespace

JNIEXPORT jlong JNICALL
Java_com_xyoye_player_subtitle_libass_LibassBridge_nativeCreate(JNIEnv *env, jobject thiz) {
    auto *context = new LibassContext();
    context->library = ass_library_init();
    if (context->library == nullptr) {
        delete context;
        LogError("Failed to initialize libass library");
        return 0;
    }

    ass_set_extract_fonts(context->library, 1);
    context->renderer = ass_renderer_init(context->library);
    if (context->renderer == nullptr) {
        ass_library_done(context->library);
        context->library = nullptr;
        delete context;
        LogError("Failed to initialize libass renderer");
        return 0;
    }

    LogInfo("libass context created");
    return reinterpret_cast<jlong>(context);
}

JNIEXPORT void JNICALL
Java_com_xyoye_player_subtitle_libass_LibassBridge_nativeDestroy(JNIEnv *env, jobject thiz,
                                                                jlong handle) {
    (void)env;
    (void)thiz;
    DestroyContext(FromHandle(handle));
}

JNIEXPORT void JNICALL
Java_com_xyoye_player_subtitle_libass_LibassBridge_nativeSetFrameSize(JNIEnv *env, jobject thiz,
                                                                     jlong handle, jint width,
                                                                     jint height) {
    (void)env;
    (void)thiz;
    auto *context = FromHandle(handle);
    if (context == nullptr || context->renderer == nullptr) {
        return;
    }
    context->frame_width = width;
    context->frame_height = height;
    ass_set_frame_size(context->renderer, width, height);
}

JNIEXPORT void JNICALL
Java_com_xyoye_player_subtitle_libass_LibassBridge_nativeSetFonts(JNIEnv *env, jobject thiz,
                                                                 jlong handle, jstring default_font,
                                                                 jobjectArray font_directories) {
    (void)thiz;
    auto *context = FromHandle(handle);
    if (context == nullptr) {
        return;
    }
    std::string default_font_value = JStringToUtf8(env, default_font);
    std::vector<std::string> directories = JObjectArrayToStrings(env, font_directories);
    ConfigureFonts(context, default_font_value, directories);
}

JNIEXPORT jboolean JNICALL
Java_com_xyoye_player_subtitle_libass_LibassBridge_nativeLoadTrack(JNIEnv *env, jobject thiz,
                                                                  jlong handle,
                                                                  jstring file_path) {
    (void)thiz;
    auto *context = FromHandle(handle);
    if (context == nullptr || context->library == nullptr) {
        return JNI_FALSE;
    }
    const std::string path = JStringToUtf8(env, file_path);
    if (path.empty()) {
        LogError("Empty subtitle path passed to libass");
        return JNI_FALSE;
    }

    ReleaseTrack(context);
    context->track = ass_read_file(context->library, path.c_str(), nullptr);
    if (context->track == nullptr) {
        LogError("Failed to read ASS/SSA track");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_xyoye_player_subtitle_libass_LibassBridge_nativeRenderFrame(JNIEnv *env, jobject thiz,
                                                                    jlong handle, jlong time_ms,
                                                                    jobject bitmap) {
    (void)thiz;
    auto *context = FromHandle(handle);
    if (context == nullptr || context->renderer == nullptr || context->track == nullptr) {
        return JNI_FALSE;
    }

    int changed = 0;
    ASS_Image *image = ass_render_frame(context->renderer, context->track, time_ms, &changed);
    if (image == nullptr || changed == 0) {
        return JNI_FALSE;
    }

    BitmapGuard guard;
    if (!guard.Lock(env, bitmap)) {
        return JNI_FALSE;
    }
    const size_t total_size = static_cast<size_t>(guard.info.stride) * guard.info.height;
    std::memset(guard.pixels, 0, total_size);
    CompositeAssImages(guard, image);

    return JNI_TRUE;
}
