#include "libass_bridge.h"

#include <android/log.h>

#include <ass/ass.h>

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
    (void)env;
    (void)thiz;
    (void)bitmap;
    auto *context = FromHandle(handle);
    if (context == nullptr || context->renderer == nullptr || context->track == nullptr) {
        return JNI_FALSE;
    }

    int changed = 0;
    ASS_Image *image = ass_render_frame(context->renderer, context->track, time_ms, &changed);
    if (image == nullptr) {
        return JNI_FALSE;
    }

    return changed > 0 ? JNI_TRUE : JNI_FALSE;
}
