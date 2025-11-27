#include <jni.h>

#include <android/log.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>

#include <mutex>
#include <new>
#include <string>

namespace {
constexpr const char *kGpuLogTag = "AssGpuBridge";

struct GpuContext {
    ANativeWindow *window = nullptr;
    int width = 0;
    int height = 0;
    float scale = 1.0F;
    int rotation = 0;
    std::string color_format;
    bool supports_hardware_buffer = false;
    bool telemetry_enabled = true;
    int64_t last_vsync_id = 0;
    std::mutex mutex;
};

void LogInfo(const char *message) {
    __android_log_print(ANDROID_LOG_INFO, kGpuLogTag, "%s", message);
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

jlongArray BuildRenderMetrics(JNIEnv *env, bool rendered) {
    jlongArray array = env->NewLongArray(4);
    if (array == nullptr) {
        return nullptr;
    }
    jlong values[4] = {rendered ? 1 : 0, 0, 0, 0};
    env->SetLongArrayRegion(array, 0, 4, values);
    return array;
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

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_xyoye_player_1component_subtitle_gpu_AssGpuNativeBridge_nativeRender(
    JNIEnv *env,
    jobject /*thiz*/,
    jlong handle,
    jlong subtitle_pts_ms,
    jlong vsync_id,
    jboolean telemetry_enabled) {
    (void)subtitle_pts_ms;
    auto *context = reinterpret_cast<GpuContext *>(handle);
    const bool rendered = context != nullptr && context->window != nullptr;
    if (context != nullptr) {
        std::lock_guard<std::mutex> guard(context->mutex);
        context->last_vsync_id = vsync_id;
        context->telemetry_enabled = telemetry_enabled == JNI_TRUE;
    }
    // Placeholder metrics; real GPU pipeline will fill in latencies.
    return BuildRenderMetrics(env, rendered);
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
