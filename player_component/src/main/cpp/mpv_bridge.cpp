#include <jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <dlfcn.h>
#include <mutex>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#ifndef MPV_PREBUILT_AVAILABLE
#define MPV_PREBUILT_AVAILABLE 0
#endif

#if MPV_PREBUILT_AVAILABLE
extern "C" {
#include <mpv/client.h>
#include <mpv/render.h>
#include <mpv/render_gl.h>
}
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#endif

namespace {
constexpr const char* kLogTag = "mpv_bridge";
constexpr jint kEventPrepared = 1;
constexpr jint kEventVideoSize = 2;
constexpr jint kEventRenderingStart = 3;
constexpr jint kEventCompleted = 4;
constexpr jint kEventError = 5;
constexpr jint kEventBufferingStart = 6;
constexpr jint kEventBufferingEnd = 7;
constexpr jint kEventLogMessage = 8;
constexpr jint kTrackVideo = 0;
constexpr jint kTrackAudio = 1;
constexpr jint kTrackSubtitle = 2;

JavaVM* g_java_vm = nullptr;
std::mutex g_app_ctx_mutex;
jobject g_android_app_ctx = nullptr;
std::mutex g_error_mutex;
std::string g_last_error;

void set_last_error(const std::string& message) {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    g_last_error = message;
}

std::string get_last_error() {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    return g_last_error;
}

#if MPV_PREBUILT_AVAILABLE
int mpvLogLevelToInt(const char* level) {
    if (level == nullptr) return 0;
    // Keep ordering aligned with MpvNativeBridge.Event.LogMessage parsing.
    if (strcmp(level, "fatal") == 0) return 5;
    if (strcmp(level, "error") == 0) return 4;
    if (strcmp(level, "warn") == 0) return 3;
    if (strcmp(level, "info") == 0) return 2;
    if (strcmp(level, "v") == 0) return 1;
    if (strcmp(level, "debug") == 0) return 0;
    if (strcmp(level, "trace") == 0) return 0;
    return 0;
}
#endif

using av_jni_set_java_vm_fn = void (*)(JavaVM*, void*);
using av_jni_set_android_app_ctx_fn = void (*)(void*, void*);

void initFfmpegJni(JavaVM* vm) {
    if (vm == nullptr) return;
    void* symbol = dlsym(RTLD_DEFAULT, "av_jni_set_java_vm");
    if (symbol == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "av_jni_set_java_vm not found");
        return;
    }
    auto fn = reinterpret_cast<av_jni_set_java_vm_fn>(symbol);
    fn(vm, nullptr);
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "av_jni_set_java_vm registered");
}

void initFfmpegAppContext(void* app_ctx) {
    if (app_ctx == nullptr) return;
    void* symbol = dlsym(RTLD_DEFAULT, "av_jni_set_android_app_ctx");
    if (symbol == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "av_jni_set_android_app_ctx not found");
        return;
    }
    auto fn = reinterpret_cast<av_jni_set_android_app_ctx_fn>(symbol);
    fn(app_ctx, nullptr);
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "av_jni_set_android_app_ctx registered");
}

struct EventCallbackRef {
    jobject callback = nullptr;
    jclass callback_class = nullptr;
    jmethodID method = nullptr;

    void reset(JNIEnv* env) {
        if (callback != nullptr) {
            env->DeleteGlobalRef(callback);
            callback = nullptr;
        }
        if (callback_class != nullptr) {
            env->DeleteGlobalRef(callback_class);
            callback_class = nullptr;
        }
        method = nullptr;
    }
};

std::vector<std::string> collectHeaders(JNIEnv* env, jobjectArray headers) {
    std::vector<std::string> result;
    if (headers == nullptr) {
        return result;
    }
    const auto headerCount = env->GetArrayLength(headers);
    result.reserve(static_cast<size_t>(headerCount));
    for (jsize i = 0; i < headerCount; i++) {
        auto element = static_cast<jstring>(env->GetObjectArrayElement(headers, i));
        const char* headerChars = env->GetStringUTFChars(element, nullptr);
        if (headerChars != nullptr) {
            result.emplace_back(headerChars);
        }
        env->ReleaseStringUTFChars(element, headerChars);
        env->DeleteLocalRef(element);
    }
    return result;
}

#if MPV_PREBUILT_AVAILABLE
struct MpvSession {
    mpv_handle* handle = nullptr;
    std::vector<std::string> headers;
    bool paused = false;
    bool looping = false;
    float speed = 1.0f;
    float volume = 1.0f;
    int64_t position = 0;
    int64_t duration = 0;
    int video_width = 0;
    int video_height = 0;
    std::atomic<bool> running = false;
    std::thread event_thread;
    EventCallbackRef event_callback;
    std::mutex mutex;
    jobject surface_ref = nullptr;
    ANativeWindow* native_window = nullptr;
    std::atomic<bool> surface_changed = false;
    std::atomic<bool> render_requested = false;
    int surface_width = 0;
    int surface_height = 0;
    // Rendering state (lives on event thread)
    EGLDisplay egl_display = EGL_NO_DISPLAY;
    EGLContext egl_context = EGL_NO_CONTEXT;
    EGLSurface egl_surface = EGL_NO_SURFACE;
    mpv_render_context* render_context = nullptr;
};

bool runtimeLinked() {
    static bool checked = false;
    static bool available = false;
    if (checked) {
        return available;
    }
    checked = true;
    void* handle = dlopen("libmpv.so", RTLD_LAZY | RTLD_LOCAL);
    if (handle == nullptr) {
        const char* dl_error = dlerror();
        const std::string message = dl_error == nullptr
                                        ? "dlopen libmpv.so failed with unknown error"
                                        : std::string("dlopen libmpv.so failed: ") + dl_error;
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "%s", message.c_str());
        set_last_error(message);
        return false;
    }
    dlclose(handle);
    set_last_error("");
    available = true;
    return true;
}

	mpv_handle* createHandle() {
	    if (!runtimeLinked()) {
	        set_last_error("libmpv.so is not packaged or cannot be loaded");
	        return nullptr;
	    }
	    mpv_handle* handle = mpv_create();
    if (handle == nullptr) {
        const std::string message = "mpv_create returned null handle";
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", message.c_str());
        set_last_error(message);
        return nullptr;
    }
	    mpv_set_option_string(handle, "config", "no");
	    mpv_set_option_string(handle, "terminal", "no");
	    mpv_set_option_string(handle, "pause", "yes");
	    mpv_set_option_string(handle, "idle", "once");
	    mpv_set_option_string(handle, "profile", "fast");
	    mpv_set_option_string(handle, "gpu-context", "android");
	    mpv_set_option_string(handle, "opengl-es", "yes");
	    mpv_set_option_string(handle, "hwdec", "mediacodec,mediacodec-copy");
	    mpv_set_option_string(handle, "ao", "audiotrack,opensles");
	    // Disable video output until we have an Android surface (wid) to attach to.
	    mpv_set_option_string(handle, "vo", "null");
	    mpv_set_option_string(handle, "force-window", "no");
	    const int initResult = mpv_initialize(handle);
    if (initResult < 0) {
        char buffer[128] = {0};
        snprintf(buffer, sizeof(buffer), "mpv_initialize failed: %d", initResult);
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", buffer);
        set_last_error(buffer);
        mpv_terminate_destroy(handle);
        return nullptr;
    }
    set_last_error("");
    return handle;
}

bool applyHttpHeaders(mpv_handle* handle, const std::vector<std::string>& headers) {
    if (handle == nullptr) {
        return false;
    }
    mpv_node root{};
    mpv_node_list list{};
    std::vector<mpv_node> nodes(headers.size());
    std::vector<const char*> cStrings;
    cStrings.reserve(headers.size());

    for (size_t i = 0; i < headers.size(); i++) {
        cStrings.push_back(headers[i].c_str());
        nodes[i].format = MPV_FORMAT_STRING;
        nodes[i].u.string = const_cast<char*>(cStrings[i]);
    }

    list.num = static_cast<int>(headers.size());
    list.values = nodes.empty() ? nullptr : nodes.data();
    list.keys = nullptr;

    root.format = MPV_FORMAT_NODE_ARRAY;
    root.u.list = &list;

    const int setResult = mpv_set_property(handle, "http-header-fields", MPV_FORMAT_NODE, &root);
    if (setResult < 0) {
        char buffer[128] = {0};
        snprintf(buffer, sizeof(buffer), "mpv_set_property http-header-fields failed: %d", setResult);
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "%s", buffer);
        set_last_error(buffer);
        return false;
    }
    set_last_error("");
    return true;
}

bool loadFile(mpv_handle* handle, const char* path) {
    if (handle == nullptr || path == nullptr) {
        set_last_error("loadFile invoked with null handle or path");
        return false;
    }
    const char* args[] = {"loadfile", path, nullptr};
    const int cmdResult = mpv_command(handle, args);
    if (cmdResult < 0) {
        char buffer[256] = {0};
        snprintf(buffer, sizeof(buffer), "mpv_command loadfile failed: %d", cmdResult);
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", buffer);
        set_last_error(buffer);
        return false;
    }
    set_last_error("");
    return true;
}

bool setFlagProperty(mpv_handle* handle, const char* property, bool value) {
    if (handle == nullptr) {
        set_last_error("mpv_set_property flag called with null handle");
        return false;
    }
    int flag = value ? 1 : 0;
    const int result = mpv_set_property(handle, property, MPV_FORMAT_FLAG, &flag);
    if (result < 0) {
        char buffer[128] = {0};
        snprintf(buffer, sizeof(buffer), "mpv_set_property %s failed: %d", property, result);
        set_last_error(buffer);
        return false;
    }
    set_last_error("");
    return true;
}

bool setDoubleProperty(mpv_handle* handle, const char* property, double value) {
    if (handle == nullptr) {
        set_last_error("mpv_set_property double called with null handle");
        return false;
    }
    const int result = mpv_set_property(handle, property, MPV_FORMAT_DOUBLE, &value);
    if (result < 0) {
        char buffer[128] = {0};
        snprintf(buffer, sizeof(buffer), "mpv_set_property %s failed: %d", property, result);
        set_last_error(buffer);
        return false;
    }
    set_last_error("");
    return true;
}

double getDoubleProperty(mpv_handle* handle, const char* property) {
    if (handle == nullptr) return 0.0;
    double value = 0.0;
    if (mpv_get_property(handle, property, MPV_FORMAT_DOUBLE, &value) < 0) {
        char buffer[128] = {0};
        snprintf(buffer, sizeof(buffer), "mpv_get_property %s failed", property);
        // Avoid overwriting the actual playback/root-cause error with auxiliary query failures.
        if (get_last_error().empty()) {
            set_last_error(buffer);
        }
        return 0.0;
    }
    set_last_error("");
    return value;
}

#else
struct MpvSession {
    std::string path;
    std::vector<std::string> headers;
    bool paused = false;
    bool looping = false;
    float speed = 1.0f;
    float volume = 1.0f;
    int64_t position = 0;
    int64_t duration = 0;
    int video_width = 0;
    int video_height = 0;
    std::atomic<bool> running = false;
    std::thread event_thread;
    EventCallbackRef event_callback;
    std::mutex mutex;
};

bool runtimeLinked() {
    set_last_error("libmpv.so not packaged; mpv bridge running in stub mode");
    return false;
}
#endif

inline MpvSession* fromHandle(jlong handle) {
    return reinterpret_cast<MpvSession*>(handle);
}

void dispatchEvent(JNIEnv* env, const EventCallbackRef& callback, jint type, jlong arg1, jlong arg2, const char* message) {
    if (callback.method == nullptr || callback.callback == nullptr || callback.callback_class == nullptr) {
        return;
    }
    jstring javaMessage = message == nullptr ? nullptr : env->NewStringUTF(message);
    env->CallVoidMethod(callback.callback, callback.method, type, arg1, arg2, javaMessage);
    if (javaMessage != nullptr) {
        env->DeleteLocalRef(javaMessage);
    }
}

void dispatchError(JNIEnv* env, MpvSession* session, const std::string& message, int64_t code = 0, int64_t reason = 0) {
    set_last_error(message);
    if (env == nullptr || session == nullptr) {
        return;
    }
    dispatchEvent(env, session->event_callback, kEventError, code, reason, message.c_str());
}

JNIEnv* ensureEnv(bool* did_attach) {
    if (g_java_vm == nullptr) {
        return nullptr;
    }
    JNIEnv* env = nullptr;
    const jint getEnvResult = g_java_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (getEnvResult == JNI_OK) {
        return env;
    }
    if (g_java_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        return nullptr;
    }
    *did_attach = true;
    return env;
}

void detachIfNeeded(bool did_attach) {
    if (did_attach && g_java_vm != nullptr) {
        g_java_vm->DetachCurrentThread();
    }
}

#if MPV_PREBUILT_AVAILABLE
void observeProperties(mpv_handle* handle) {
    if (handle == nullptr) {
        return;
    }
    mpv_observe_property(handle, 0, "paused-for-cache", MPV_FORMAT_FLAG);
    mpv_observe_property(handle, 0, "width", MPV_FORMAT_INT64);
    mpv_observe_property(handle, 0, "height", MPV_FORMAT_INT64);
}

void destroyRenderContext(MpvSession* session) {
    if (session == nullptr) return;
    if (session->render_context != nullptr) {
        mpv_render_context_set_update_callback(session->render_context, nullptr, nullptr);
        mpv_render_context_free(session->render_context);
        session->render_context = nullptr;
    }
}

void destroyEgl(MpvSession* session) {
    if (session == nullptr) return;
    if (session->egl_display != EGL_NO_DISPLAY) {
        eglMakeCurrent(session->egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }
    if (session->egl_surface != EGL_NO_SURFACE) {
        eglDestroySurface(session->egl_display, session->egl_surface);
        session->egl_surface = EGL_NO_SURFACE;
    }
    if (session->egl_context != EGL_NO_CONTEXT) {
        eglDestroyContext(session->egl_display, session->egl_context);
        session->egl_context = EGL_NO_CONTEXT;
    }
    if (session->egl_display != EGL_NO_DISPLAY) {
        eglTerminate(session->egl_display);
        session->egl_display = EGL_NO_DISPLAY;
    }
}

void on_mpv_render_update(void* data) {
    auto* session = static_cast<MpvSession*>(data);
    if (session == nullptr) return;
    session->render_requested = true;
    if (session->handle != nullptr) {
        mpv_wakeup(session->handle);
    }
}

EGLContext createContext(EGLDisplay display, EGLConfig config, int version) {
    const EGLint contextAttribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, version,
        EGL_NONE
    };
    return eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttribs);
}

bool initEgl(MpvSession* session) {
    if (session == nullptr || session->native_window == nullptr) {
        return false;
    }
    destroyEgl(session);

    session->egl_display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (session->egl_display == EGL_NO_DISPLAY) {
        const std::string message = "eglGetDisplay failed";
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", message.c_str());
        set_last_error(message);
        return false;
    }
    if (!eglInitialize(session->egl_display, nullptr, nullptr)) {
        const std::string message = "eglInitialize failed";
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", message.c_str());
        set_last_error(message);
        destroyEgl(session);
        return false;
    }
    const EGLint cfgAttribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_NONE
    };
    EGLConfig config = nullptr;
    EGLint numConfigs = 0;
    if (!eglChooseConfig(session->egl_display, cfgAttribs, &config, 1, &numConfigs) || numConfigs < 1) {
        const std::string message = "eglChooseConfig failed";
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", message.c_str());
        set_last_error(message);
        destroyEgl(session);
        return false;
    }

    session->egl_surface = eglCreateWindowSurface(
        session->egl_display,
        config,
        session->native_window,
        nullptr
    );
    if (session->egl_surface == EGL_NO_SURFACE) {
        const std::string message = "eglCreateWindowSurface failed";
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", message.c_str());
        set_last_error(message);
        destroyEgl(session);
        return false;
    }

    session->egl_context = createContext(session->egl_display, config, 3);
    if (session->egl_context == EGL_NO_CONTEXT) {
        session->egl_context = createContext(session->egl_display, config, 2);
    }
    if (session->egl_context == EGL_NO_CONTEXT) {
        const std::string message = "eglCreateContext failed";
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", message.c_str());
        set_last_error(message);
        destroyEgl(session);
        return false;
    }

    if (!eglMakeCurrent(session->egl_display, session->egl_surface, session->egl_surface, session->egl_context)) {
        const std::string message = "eglMakeCurrent failed";
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", message.c_str());
        set_last_error(message);
        destroyEgl(session);
        return false;
    }

    return true;
}

bool ensureRenderContext(MpvSession* session) {
    if (session == nullptr || session->native_window == nullptr) {
        return false;
    }

    destroyRenderContext(session);

    if (!initEgl(session)) {
        return false;
    }

    auto get_proc_address = [](void*, const char* name) -> void* {
        return reinterpret_cast<void*>(eglGetProcAddress(name));
    };

    mpv_opengl_init_params gl_init_params{};
    gl_init_params.get_proc_address = get_proc_address;
    gl_init_params.get_proc_address_ctx = nullptr;

    const char* api_type = MPV_RENDER_API_TYPE_OPENGL;
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_API_TYPE, const_cast<char*>(api_type)},
        {MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &gl_init_params},
        {MPV_RENDER_PARAM_INVALID, nullptr},
    };

    if (mpv_render_context_create(&session->render_context, session->handle, params) < 0) {
        const std::string message = "mpv_render_context_create failed";
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", message.c_str());
        set_last_error(message);
        destroyRenderContext(session);
        destroyEgl(session);
        return false;
    }

    mpv_render_context_set_update_callback(session->render_context, on_mpv_render_update, session);
    session->render_requested = true;
    return true;
}

bool makeCurrent(MpvSession* session) {
    if (session == nullptr || session->egl_display == EGL_NO_DISPLAY) {
        return false;
    }
    if (eglGetCurrentContext() == session->egl_context &&
        eglGetCurrentDisplay() == session->egl_display &&
        eglGetCurrentSurface(EGL_DRAW) == session->egl_surface) {
        return true;
    }
    return eglMakeCurrent(session->egl_display, session->egl_surface, session->egl_surface, session->egl_context);
}

void renderFrame(MpvSession* session) {
    if (session == nullptr || session->render_context == nullptr) {
        return;
    }
    if (!makeCurrent(session)) {
        set_last_error("eglMakeCurrent failed before rendering frame");
        return;
    }

    if (session->surface_width == 0 || session->surface_height == 0) {
        session->surface_width = ANativeWindow_getWidth(session->native_window);
        session->surface_height = ANativeWindow_getHeight(session->native_window);
    }

    mpv_opengl_fbo fbo{
        .fbo = 0,
        .w = session->surface_width,
        .h = session->surface_height,
        .internal_format = 0
    };
    int flip = 1;
    mpv_render_param render_params[] = {
        {MPV_RENDER_PARAM_OPENGL_FBO, &fbo},
        {MPV_RENDER_PARAM_FLIP_Y, &flip},
        {MPV_RENDER_PARAM_INVALID, nullptr},
    };

    glViewport(0, 0, fbo.w, fbo.h);
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);
    mpv_render_context_render(session->render_context, render_params);
    eglSwapBuffers(session->egl_display, session->egl_surface);
    session->render_requested = false;
}

void processRenderUpdates(JNIEnv* env, MpvSession* session) {
    if (session == nullptr) {
        return;
    }
    if (!session->render_requested.exchange(false)) {
        return;
    }
    if (session->render_context == nullptr) {
        return;
    }

    const int update_flags = mpv_render_context_update(session->render_context);
    if (update_flags < 0) {
        __android_log_print(
            ANDROID_LOG_ERROR,
            kLogTag,
            "mpv_render_context_update failed: %d",
            update_flags
        );
        char buffer[128] = {0};
        snprintf(buffer, sizeof(buffer), "mpv_render_context_update failed: %d", update_flags);
        dispatchError(env, session, buffer, update_flags, 0);
        return;
    }

    if (update_flags & MPV_RENDER_UPDATE_FRAME) {
        renderFrame(session);
    }
}

void markSurfaceChanged(MpvSession* session) {
    if (session == nullptr) return;
    session->surface_changed = true;
    if (session->handle != nullptr) {
        mpv_wakeup(session->handle);
    }
}
#endif

void stopEventThread(JNIEnv* env, MpvSession* session) {
    if (session == nullptr) {
        return;
    }
    if (session->running.exchange(false)) {
#if MPV_PREBUILT_AVAILABLE
        if (session->handle != nullptr) {
            mpv_wakeup(session->handle);
        }
#endif
        if (session->event_thread.joinable()) {
            session->event_thread.join();
        }
    }
    session->event_callback.reset(env);
}

#if MPV_PREBUILT_AVAILABLE
void eventLoop(MpvSession* session) {
    bool did_attach = false;
    JNIEnv* env = ensureEnv(&did_attach);
    if (env == nullptr || session == nullptr || session->handle == nullptr) {
        set_last_error("mpv eventLoop failed to attach JNI environment or session");
        detachIfNeeded(did_attach);
        return;
    }

    while (session->running.load()) {
        mpv_event* event = mpv_wait_event(session->handle, 0.25);
        if (event == nullptr || event->event_id == MPV_EVENT_NONE) {
            continue;
        }

        switch (event->event_id) {
            case MPV_EVENT_LOG_MESSAGE: {
                auto* log = static_cast<mpv_event_log_message*>(event->data);
                if (log != nullptr) {
                    const char* prefix = log->prefix == nullptr ? "" : log->prefix;
                    const char* level = log->level == nullptr ? "" : log->level;
                    const char* text = log->text == nullptr ? "" : log->text;
                    __android_log_print(ANDROID_LOG_INFO, kLogTag, "mpv[%s][%s] %s", prefix, level, text);
                    // Forward to Kotlin logger so it can be written into debug.log.
                    // Note: do not include newlines; mpv log text usually ends with '\n'.
                    std::string forwarded = std::string(prefix) + "[" + level + "] " + text;
                    dispatchEvent(
                        env,
                        session->event_callback,
                        kEventLogMessage,
                        static_cast<jlong>(mpvLogLevelToInt(level)),
                        0,
                        forwarded.c_str()
                    );
                }
                break;
            }
            case MPV_EVENT_FILE_LOADED: {
                dispatchEvent(env, session->event_callback, kEventPrepared, 0, 0, nullptr);
                break;
            }
            case MPV_EVENT_VIDEO_RECONFIG: {
                int64_t width = 0;
                int64_t height = 0;
                mpv_get_property(session->handle, "width", MPV_FORMAT_INT64, &width);
                mpv_get_property(session->handle, "height", MPV_FORMAT_INT64, &height);
                session->video_width = static_cast<int>(width);
                session->video_height = static_cast<int>(height);
                dispatchEvent(env, session->event_callback, kEventVideoSize, width, height, nullptr);
                break;
            }
            case MPV_EVENT_END_FILE: {
                auto* endFile = static_cast<mpv_event_end_file*>(event->data);
                if (endFile != nullptr) {
                    if (endFile->reason == MPV_END_FILE_REASON_EOF) {
                        dispatchEvent(env, session->event_callback, kEventCompleted, 0, 0, nullptr);
                    } else {
                        const char* errorMsg = mpv_error_string(endFile->error);
                        dispatchEvent(env, session->event_callback, kEventError, endFile->error, endFile->reason, errorMsg);
                    }
                }
                break;
            }
            case MPV_EVENT_PLAYBACK_RESTART: {
                dispatchEvent(env, session->event_callback, kEventRenderingStart, 0, 0, nullptr);
                break;
            }
            case MPV_EVENT_PROPERTY_CHANGE: {
                auto* prop = static_cast<mpv_event_property*>(event->data);
                if (prop != nullptr && prop->name != nullptr && prop->format == MPV_FORMAT_FLAG &&
                    strcmp(prop->name, "paused-for-cache") == 0) {
                    const bool isCaching = *static_cast<int*>(prop->data) != 0;
                    dispatchEvent(
                        env,
                        session->event_callback,
                        isCaching ? kEventBufferingStart : kEventBufferingEnd,
                        0,
                        0,
                        nullptr
                    );
                }
                break;
            }
            default:
                break;
        }
    }

    detachIfNeeded(did_attach);
}
#endif

bool registerEventCallback(JNIEnv* env, MpvSession* session, jobject callback) {
    if (env == nullptr || session == nullptr || callback == nullptr) {
        set_last_error("registerEventCallback invoked with null argument");
        return false;
    }
    stopEventThread(env, session);
    jobject globalCallback = env->NewGlobalRef(callback);
    if (globalCallback == nullptr) {
        set_last_error("Failed to allocate global reference for mpv callback");
        return false;
    }
    jclass callbackClass = env->GetObjectClass(callback);
    if (callbackClass == nullptr) {
        set_last_error("Failed to resolve callback class for mpv event loop");
        env->DeleteGlobalRef(globalCallback);
        return false;
    }
    jclass globalClass = static_cast<jclass>(env->NewGlobalRef(callbackClass));
    env->DeleteLocalRef(callbackClass);
    if (globalClass == nullptr) {
        set_last_error("Failed to create global class reference for mpv callback");
        env->DeleteGlobalRef(globalCallback);
        return false;
    }
    jmethodID method = env->GetMethodID(globalClass, "onNativeEvent", "(IJJLjava/lang/String;)V");
    if (method == nullptr) {
        set_last_error("onNativeEvent signature not found on mpv callback");
        env->DeleteGlobalRef(globalCallback);
        env->DeleteGlobalRef(globalClass);
        return false;
    }

    session->event_callback.callback = globalCallback;
    session->event_callback.callback_class = globalClass;
    session->event_callback.method = method;
    set_last_error("");
    return true;
}

#if MPV_PREBUILT_AVAILABLE
std::vector<std::string> fetchTrackList(MpvSession* session) {
    std::vector<std::string> result;
    if (session == nullptr || session->handle == nullptr) {
        return result;
    }
    mpv_node root{};
    if (mpv_get_property(session->handle, "track-list", MPV_FORMAT_NODE, &root) < 0) {
        return result;
    }
    if (root.format != MPV_FORMAT_NODE_ARRAY || root.u.list == nullptr) {
        mpv_free_node_contents(&root);
        return result;
    }
    auto* list = root.u.list;
    for (int i = 0; i < list->num; i++) {
        mpv_node* track = &list->values[i];
        if (track->format != MPV_FORMAT_NODE_MAP || track->u.list == nullptr) {
            continue;
        }
        int id = -1;
        std::string type;
        std::string title;
        bool selected = false;
        auto* mapList = track->u.list;
        for (int j = 0; j < mapList->num; j++) {
            const char* key = mapList->keys[j];
            mpv_node* value = &mapList->values[j];
            if (key == nullptr || value == nullptr) {
                continue;
            }
            if (strcmp(key, "id") == 0 && value->format == MPV_FORMAT_INT64) {
                id = static_cast<int>(value->u.int64);
            } else if (strcmp(key, "type") == 0 && value->format == MPV_FORMAT_STRING) {
                type = value->u.string == nullptr ? "" : value->u.string;
            } else if (strcmp(key, "selected") == 0 && value->format == MPV_FORMAT_FLAG) {
                selected = value->u.flag != 0;
            } else if ((strcmp(key, "title") == 0 || strcmp(key, "lang") == 0) &&
                       value->format == MPV_FORMAT_STRING && value->u.string != nullptr) {
                if (title.empty()) {
                    title = value->u.string;
                }
            }
        }
        int trackType = -1;
        if (type == "video") {
            trackType = kTrackVideo;
        } else if (type == "audio") {
            trackType = kTrackAudio;
        } else if (type == "sub" || type == "subtitle") {
            trackType = kTrackSubtitle;
        }
        if (trackType < 0 || id < 0) {
            continue;
        }

        char buffer[256] = {0};
        snprintf(
            buffer,
            sizeof(buffer),
            "%d|%d|%d|%s",
            trackType,
            id,
            selected ? 1 : 0,
            title.c_str()
        );
        result.emplace_back(buffer);
    }

    mpv_free_node_contents(&root);
    return result;
}

bool selectTrack(MpvSession* session, jint trackType, jint trackId) {
    if (session == nullptr || session->handle == nullptr) return false;
    const char* property = nullptr;
    switch (trackType) {
        case kTrackVideo:
            property = "vid";
            break;
        case kTrackAudio:
            property = "aid";
            break;
        case kTrackSubtitle:
            property = "sid";
            break;
        default:
            return false;
    }
    int64_t value = static_cast<int64_t>(trackId);
    return mpv_set_property(session->handle, property, MPV_FORMAT_INT64, &value) >= 0;
}

bool deselectTrack(MpvSession* session, jint trackType) {
    if (session == nullptr || session->handle == nullptr) return false;
    const char* property = nullptr;
    switch (trackType) {
        case kTrackVideo:
            property = "vid";
            break;
        case kTrackAudio:
            property = "aid";
            break;
        case kTrackSubtitle:
            property = "sid";
            break;
        default:
            return false;
    }
    return mpv_set_property_string(session->handle, property, "no") >= 0;
}

bool addExternalTrack(MpvSession* session, jint trackType, const std::string& path) {
    if (session == nullptr || session->handle == nullptr || path.empty()) {
        return false;
    }
    if (trackType == kTrackAudio) {
        const char* cmd[] = {"audio-add", path.c_str(), "select", nullptr};
        return mpv_command(session->handle, cmd) >= 0;
    }
    if (trackType == kTrackSubtitle) {
        const char* cmd[] = {"sub-add", path.c_str(), "select", nullptr};
        return mpv_command(session->handle, cmd) >= 0;
    }
    return false;
}

bool addShader(MpvSession* session, const std::string& path) {
    if (session == nullptr || session->handle == nullptr || path.empty()) {
        return false;
    }
    const char* cmd[] = {"change-list", "glsl-shaders", "append", path.c_str(), nullptr};
    return mpv_command(session->handle, cmd) >= 0;
}
#else
std::vector<std::string> fetchTrackList(MpvSession*) {
    return {};
}

bool selectTrack(MpvSession*, jint, jint) {
    return true;
}

bool deselectTrack(MpvSession*, jint) {
    return true;
}

bool addExternalTrack(MpvSession*, jint, const std::string&) {
    return true;
}

bool addShader(MpvSession*, const std::string&) {
    return true;
}

void markSurfaceChanged(MpvSession*) {}
#endif
}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
#if MPV_PREBUILT_AVAILABLE
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "mpv bridge compiled with mpv linkage");
#else
    __android_log_print(
        ANDROID_LOG_WARN,
        kLogTag,
        "libmpv.so not packaged; mpv bridge operating in stub mode"
    );
#endif
    g_java_vm = vm;
    initFfmpegJni(vm);
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_com_xyoye_player_kernel_impl_mpv_MpvNativeBridge_nativeSetAndroidAppContext(
    JNIEnv* env, jclass, jobject context) {
    if (env == nullptr || context == nullptr) return;
    std::lock_guard<std::mutex> lock(g_app_ctx_mutex);
    if (g_android_app_ctx != nullptr) {
        env->DeleteGlobalRef(g_android_app_ctx);
        g_android_app_ctx = nullptr;
    }
    g_android_app_ctx = env->NewGlobalRef(context);
    initFfmpegJni(g_java_vm);
    initFfmpegAppContext(g_android_app_ctx);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xyoye_player_kernel_impl_mpv_MpvNativeBridge_nativeIsLinked(JNIEnv*, jclass) {
    return runtimeLinked() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_xyoye_player_kernel_impl_mpv_MpvNativeBridge_nativeLastError(JNIEnv* env, jclass) {
    const std::string message = get_last_error();
    if (message.empty()) {
        return nullptr;
    }
    return env->NewStringUTF(message.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_xyoye_player_kernel_impl_mpv_MpvNativeBridge_nativeCreate(JNIEnv*, jclass) {
#if MPV_PREBUILT_AVAILABLE
    mpv_handle* handle = createHandle();
    if (handle == nullptr) {
        return 0;
    }
    auto* session = new MpvSession();
    session->handle = handle;
    observeProperties(session->handle);
    return reinterpret_cast<jlong>(session);
#else
    return reinterpret_cast<jlong>(new MpvSession());
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_xyoye_player_kernel_impl_mpv_MpvNativeBridge_nativeDestroy(
    JNIEnv* env, jclass, jlong handle) {
    auto* session = fromHandle(handle);
    if (session == nullptr) return;
    stopEventThread(env, session);
#if MPV_PREBUILT_AVAILABLE
    {
        std::lock_guard<std::mutex> guard(session->mutex);
        if (session->surface_ref != nullptr) {
            env->DeleteGlobalRef(session->surface_ref);
            session->surface_ref = nullptr;
        }
        if (session->native_window != nullptr) {
            ANativeWindow_release(session->native_window);
            session->native_window = nullptr;
        }
    }
    if (session->handle != nullptr) {
        mpv_terminate_destroy(session->handle);
        session->handle = nullptr;
    }
#endif
    delete session;
}

extern "C" JNIEXPORT void JNICALL
Java_com_xyoye_player_kernel_impl_mpv_MpvNativeBridge_nativeSetSurface(
    JNIEnv* env, jclass, jlong handle, jobject surface) {
    auto* session = fromHandle(handle);
    if (session == nullptr) return;
#if MPV_PREBUILT_AVAILABLE
    std::lock_guard<std::mutex> guard(session->mutex);
    if (session->handle == nullptr) return;

    if (session->surface_ref != nullptr) {
        int64_t wid = 0;
        const int result = mpv_set_option(session->handle, "wid", MPV_FORMAT_INT64, &wid);
        if (result < 0) {
            __android_log_print(ANDROID_LOG_WARN, kLogTag, "mpv_set_option(wid=0) failed: %d", result);
        }
        mpv_set_property_string(session->handle, "vo", "null");
        mpv_set_property_string(session->handle, "force-window", "no");
        env->DeleteGlobalRef(session->surface_ref);
        session->surface_ref = nullptr;
    }

    if (surface != nullptr) {
        session->surface_ref = env->NewGlobalRef(surface);
        if (session->surface_ref == nullptr) {
            set_last_error("Failed to allocate global surface reference");
            return;
        }

        int64_t wid = reinterpret_cast<intptr_t>(session->surface_ref);
        const int result = mpv_set_option(session->handle, "wid", MPV_FORMAT_INT64, &wid);
        if (result < 0) {
            char buffer[128] = {0};
            snprintf(buffer, sizeof(buffer), "mpv_set_option(wid) failed: %d", result);
            __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", buffer);
            set_last_error(buffer);
        } else {
            set_last_error("");
        }

        // Keep mpv rendering enabled while the surface is alive.
        std::string target_vo = "gpu";
        char* current_vo = mpv_get_property_string(session->handle, "vo");
        if (current_vo != nullptr) {
            std::string configured = current_vo;
            if (!configured.empty() && configured != "null") {
                target_vo = configured;
            }
            mpv_free(current_vo);
        }
        mpv_set_property_string(session->handle, "vo", target_vo.c_str());
        mpv_set_property_string(session->handle, "force-window", "yes");

        // Help mpv pick the correct output size.
        ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
        if (window != nullptr) {
            const int width = ANativeWindow_getWidth(window);
            const int height = ANativeWindow_getHeight(window);
            char size[64] = {0};
            snprintf(size, sizeof(size), "%dx%d", width, height);
            mpv_set_property_string(session->handle, "android-surface-size", size);
            ANativeWindow_release(window);
        }
    }
    __android_log_print(
        ANDROID_LOG_DEBUG,
        kLogTag,
        "nativeSetSurface updated Surface(wid ref): %p",
        session->surface_ref
    );
    mpv_wakeup(session->handle);
#else
    (void)env;
    (void)surface;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_xyoye_player_kernel_impl_mpv_MpvNativeBridge_nativeStartEventLoop(
    JNIEnv* env, jclass, jlong handle, jobject callback) {
    auto* session = fromHandle(handle);
    if (session == nullptr || callback == nullptr) return;
    if (!registerEventCallback(env, session, callback)) {
        return;
    }
#if MPV_PREBUILT_AVAILABLE
    if (session->running.exchange(true)) {
        return;
    }
    session->event_thread = std::thread([session]() { eventLoop(session); });
#else
    session->running = true;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xyoye_player_kernel_impl_mpv_MpvNativeBridge_nativeSetOptionString(
    JNIEnv* env, jclass, jlong handle, jstring name, jstring value) {
    auto* session = fromHandle(handle);
    if (session == nullptr || name == nullptr || value == nullptr) {
        set_last_error("nativeSetOptionString called with null argument");
        return JNI_FALSE;
    }
#if MPV_PREBUILT_AVAILABLE
    if (session->handle == nullptr) {
        set_last_error("mpv handle is null while setting option");
        return JNI_FALSE;
    }
    const char* nameChars = env->GetStringUTFChars(name, nullptr);
    const char* valueChars = env->GetStringUTFChars(value, nullptr);
    if (nameChars == nullptr || valueChars == nullptr) {
        set_last_error("Failed to decode option name or value for mpv");
        if (nameChars != nullptr) env->ReleaseStringUTFChars(name, nameChars);
        if (valueChars != nullptr) env->ReleaseStringUTFChars(value, valueChars);
        return JNI_FALSE;
    }
    const std::string optionName = nameChars;
    const std::string optionValue = valueChars;
    env->ReleaseStringUTFChars(name, nameChars);
    env->ReleaseStringUTFChars(value, valueChars);

    const int result = mpv_set_property_string(session->handle, optionName.c_str(), optionValue.c_str());
    if (result < 0) {
        char buffer[192] = {0};
        snprintf(buffer, sizeof(buffer), "mpv_set_property %s=%s failed: %d", optionName.c_str(), optionValue.c_str(), result);
        set_last_error(buffer);
        return JNI_FALSE;
    }
    set_last_error("");
    return JNI_TRUE;
#else
    (void)name;
    (void)value;
    set_last_error("libmpv.so not linked; setOptionString is unavailable");
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xyoye_player_kernel_impl_mpv_MpvNativeBridge_nativeSetLogLevel(
    JNIEnv* env, jclass, jlong handle, jstring level) {
    auto* session = fromHandle(handle);
    if (session == nullptr || level == nullptr) {
        set_last_error("nativeSetLogLevel called with null argument");
        return JNI_FALSE;
    }
#if MPV_PREBUILT_AVAILABLE
    if (session->handle == nullptr) {
        set_last_error("mpv handle is null while setting log level");
        return JNI_FALSE;
    }
    const char* levelChars = env->GetStringUTFChars(level, nullptr);
    if (levelChars == nullptr) {
        set_last_error("Failed to decode log level for mpv");
        return JNI_FALSE;
    }
    const std::string levelString = levelChars;
    env->ReleaseStringUTFChars(level, levelChars);

    const int result = mpv_request_log_messages(session->handle, levelString.c_str());
    if (result < 0) {
        char buffer[160] = {0};
        snprintf(buffer, sizeof(buffer), "mpv_request_log_messages(%s) failed: %d", levelString.c_str(), result);
        set_last_error(buffer);
        return JNI_FALSE;
    }
    set_last_error("");
    return JNI_TRUE;
#else
    (void)level;
    set_last_error("libmpv.so not linked; mpv logging unavailable");
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_xyoye_player_kernel_impl_mpv_MpvNativeBridge_nativeStopEventLoop(
    JNIEnv* env, jclass, jlong handle) {
    auto* session = fromHandle(handle);
    if (session == nullptr) return;
    stopEventThread(env, session);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_xyoye_player_kernel_impl_mpv_MpvNativeBridge_nativeListTracks(
    JNIEnv* env, jclass, jlong handle) {
    auto* session = fromHandle(handle);
    if (session == nullptr) return nullptr;
    std::vector<std::string> tracks = fetchTrackList(session);
    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) {
        return nullptr;
    }
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(tracks.size()), stringClass, nullptr);
    env->DeleteLocalRef(stringClass);
    if (result == nullptr) {
        return nullptr;
    }
    for (jsize i = 0; i < static_cast<jsize>(tracks.size()); i++) {
        jstring trackString = env->NewStringUTF(tracks[static_cast<size_t>(i)].c_str());
        env->SetObjectArrayElement(result, i, trackString);
        env->DeleteLocalRef(trackString);
    }
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xyoye_player_kernel_impl_mpv_MpvNativeBridge_nativeSelectTrack(
    JNIEnv* env, jclass, jlong handle, jint trackType, jint trackId) {
    (void)env;
    auto* session = fromHandle(handle);
    if (session == nullptr) return JNI_FALSE;
    return selectTrack(session, trackType, trackId) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xyoye_player_kernel_impl_mpv_MpvNativeBridge_nativeDeselectTrack(
    JNIEnv* env, jclass, jlong handle, jint trackType) {
    (void)env;
    auto* session = fromHandle(handle);
    if (session == nullptr) return JNI_FALSE;
    return deselectTrack(session, trackType) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xyoye_player_kernel_impl_mpv_MpvNativeBridge_nativeAddExternalTrack(
    JNIEnv* env, jclass, jlong handle, jint trackType, jstring path) {
    auto* session = fromHandle(handle);
    if (session == nullptr || path == nullptr) return JNI_FALSE;
    const char* pathChars = env->GetStringUTFChars(path, nullptr);
    const std::string pathString = pathChars == nullptr ? "" : pathChars;
    env->ReleaseStringUTFChars(path, pathChars);
    if (pathString.empty()) {
        return JNI_FALSE;
    }
    return addExternalTrack(session, trackType, pathString) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xyoye_player_kernel_impl_mpv_MpvNativeBridge_nativeAddShader(
    JNIEnv* env, jclass, jlong handle, jstring path) {
    auto* session = fromHandle(handle);
    if (session == nullptr || path == nullptr) return JNI_FALSE;
#if MPV_PREBUILT_AVAILABLE
    const char* pathChars = env->GetStringUTFChars(path, nullptr);
    if (pathChars == nullptr) {
        set_last_error("Failed to decode shader path for mpv");
        return JNI_FALSE;
    }
    const std::string pathString = pathChars;
    env->ReleaseStringUTFChars(path, pathChars);
    if (pathString.empty()) {
        return JNI_FALSE;
    }
    return addShader(session, pathString) ? JNI_TRUE : JNI_FALSE;
#else
    (void)env;
    (void)handle;
    set_last_error("libmpv.so not linked; addShader is unavailable");
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_xyoye_player_kernel_impl_mpv_MpvNativeBridge_nativeGetVideoSize(
    JNIEnv*, jclass, jlong handle) {
    auto* session = fromHandle(handle);
    if (session == nullptr) return 0;
#if MPV_PREBUILT_AVAILABLE
    int64_t width = 0;
    int64_t height = 0;
    if (session->handle != nullptr) {
        mpv_get_property(session->handle, "width", MPV_FORMAT_INT64, &width);
        mpv_get_property(session->handle, "height", MPV_FORMAT_INT64, &height);
        session->video_width = static_cast<int>(width);
        session->video_height = static_cast<int>(height);
    }
#else
    int64_t width = session->video_width;
    int64_t height = session->video_height;
#endif
    return (width << 32) | (height & 0xffffffff);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_xyoye_player_kernel_impl_mpv_MpvNativeBridge_nativeGetHwdecCurrent(
    JNIEnv* env, jclass, jlong handle) {
    auto* session = fromHandle(handle);
    if (session == nullptr) return nullptr;
#if MPV_PREBUILT_AVAILABLE
    if (session->handle == nullptr) return nullptr;
    char* value = mpv_get_property_string(session->handle, "hwdec-current");
    if (value == nullptr) {
        return nullptr;
    }
    jstring result = env->NewStringUTF(value);
    mpv_free(value);
    return result;
#else
    (void)env;
    (void)handle;
    set_last_error("libmpv.so not linked; hwdec-current unavailable");
    return nullptr;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xyoye_player_kernel_impl_mpv_MpvNativeBridge_nativeSetDataSource(
    JNIEnv* env, jclass, jlong handle, jstring path, jobjectArray headers) {
    auto* session = fromHandle(handle);
    if (session == nullptr || path == nullptr) {
        return JNI_FALSE;
    }
    const char* pathChars = env->GetStringUTFChars(path, nullptr);
    const std::string pathString = pathChars ? pathChars : "";
    env->ReleaseStringUTFChars(path, pathChars);
    session->headers = collectHeaders(env, headers);
#if MPV_PREBUILT_AVAILABLE
    if (session->handle == nullptr) {
        return JNI_FALSE;
    }
    applyHttpHeaders(session->handle, session->headers);
    if (!loadFile(session->handle, pathString.c_str())) {
        return JNI_FALSE;
    }
    session->paused = false;
    return JNI_TRUE;
#else
    session->path = pathString;
    session->position = 0;
    session->duration = 0;
    session->paused = false;
    return JNI_TRUE;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_xyoye_player_kernel_impl_mpv_MpvNativeBridge_nativePlay(JNIEnv*, jclass, jlong handle) {
    auto* session = fromHandle(handle);
    if (session == nullptr) return;
#if MPV_PREBUILT_AVAILABLE
    setFlagProperty(session->handle, "pause", false);
#endif
    session->paused = false;
}

extern "C" JNIEXPORT void JNICALL
Java_com_xyoye_player_kernel_impl_mpv_MpvNativeBridge_nativePause(JNIEnv*, jclass, jlong handle) {
    auto* session = fromHandle(handle);
    if (session == nullptr) return;
#if MPV_PREBUILT_AVAILABLE
    setFlagProperty(session->handle, "pause", true);
#endif
    session->paused = true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_xyoye_player_kernel_impl_mpv_MpvNativeBridge_nativeStop(JNIEnv*, jclass, jlong handle) {
    auto* session = fromHandle(handle);
    if (session == nullptr) return;
#if MPV_PREBUILT_AVAILABLE
    const char* cmd[] = {"stop", nullptr};
    mpv_command(session->handle, cmd);
#endif
    session->paused = true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_xyoye_player_kernel_impl_mpv_MpvNativeBridge_nativeSeek(
    JNIEnv*, jclass, jlong handle, jlong positionMs) {
    auto* session = fromHandle(handle);
    if (session == nullptr) return;
#if MPV_PREBUILT_AVAILABLE
    const double positionSeconds = static_cast<double>(positionMs) / 1000.0;
    char buffer[64] = {0};
    snprintf(buffer, sizeof(buffer), "%.3f", positionSeconds);
    const char* cmd[] = {"seek", buffer, "absolute+exact", nullptr};
    mpv_command(session->handle, cmd);
#else
    session->position = positionMs;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_xyoye_player_kernel_impl_mpv_MpvNativeBridge_nativeSetSpeed(
    JNIEnv*, jclass, jlong handle, jfloat speed) {
    auto* session = fromHandle(handle);
    if (session == nullptr) return;
#if MPV_PREBUILT_AVAILABLE
    setDoubleProperty(session->handle, "speed", static_cast<double>(speed));
#endif
    session->speed = speed;
}

extern "C" JNIEXPORT void JNICALL
Java_com_xyoye_player_kernel_impl_mpv_MpvNativeBridge_nativeSetVolume(
    JNIEnv*, jclass, jlong handle, jfloat volume) {
    auto* session = fromHandle(handle);
    if (session == nullptr) return;
#if MPV_PREBUILT_AVAILABLE
    const double scaledVolume = static_cast<double>(volume) * 100.0;
    setDoubleProperty(session->handle, "volume", scaledVolume);
#endif
    session->volume = volume;
}

extern "C" JNIEXPORT void JNICALL
Java_com_xyoye_player_kernel_impl_mpv_MpvNativeBridge_nativeSetLooping(
    JNIEnv*, jclass, jlong handle, jboolean looping) {
    auto* session = fromHandle(handle);
    if (session == nullptr) return;
#if MPV_PREBUILT_AVAILABLE
    const char* value = looping == JNI_TRUE ? "inf" : "no";
    mpv_set_property_string(session->handle, "loop-file", value);
#endif
    session->looping = looping == JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_xyoye_player_kernel_impl_mpv_MpvNativeBridge_nativeSetSubtitleDelay(
    JNIEnv*, jclass, jlong handle, jlong offsetMs) {
    auto* session = fromHandle(handle);
    if (session == nullptr) return;
#if MPV_PREBUILT_AVAILABLE
    const double offsetSeconds = static_cast<double>(offsetMs) / 1000.0;
    setDoubleProperty(session->handle, "sub-delay", offsetSeconds);
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_xyoye_player_kernel_impl_mpv_MpvNativeBridge_nativeGetPosition(
    JNIEnv*, jclass, jlong handle) {
    auto* session = fromHandle(handle);
    if (session == nullptr) return 0;
#if MPV_PREBUILT_AVAILABLE
    return static_cast<jlong>(getDoubleProperty(session->handle, "time-pos") * 1000.0);
#else
    return static_cast<jlong>(session->position);
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_xyoye_player_kernel_impl_mpv_MpvNativeBridge_nativeGetDuration(
    JNIEnv*, jclass, jlong handle) {
    auto* session = fromHandle(handle);
    if (session == nullptr) return 0;
#if MPV_PREBUILT_AVAILABLE
    return static_cast<jlong>(getDoubleProperty(session->handle, "duration") * 1000.0);
#else
    return static_cast<jlong>(session->duration);
#endif
}
