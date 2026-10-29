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
}
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
constexpr jint kEventSubtitleAssFull = 9;
constexpr jint kEventSubtitleAssExtradata = 10;
constexpr jint kEventSubtitleSid = 11;
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
    std::mutex mutex;
    jobject surface_ref = nullptr;
    bool video_started = false;
    double paused_surface_position = -1.0;
    std::thread event_thread;
    EventCallbackRef event_callback;
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
        set_last_error("mpv_create returned null handle");
        return nullptr;
    }
    const std::pair<const char*, const char*> options[] = {
        {"config", "no"},
        {"terminal", "no"},
        {"pause", "yes"},
        {"idle", "once"},
        {"profile", "fast"},
        {"hwdec", "mediacodec"},
        {"vd-lavc-software-fallback", "no"},
        {"stop-playback-on-init-failure", "yes"},
        {"ao", "audiotrack,opensles"},
        {"vo", "null"},
        {"vid", "no"},
        {"force-window", "no"}
    };
    for (const auto& option : options) {
        const int result = mpv_set_option_string(handle, option.first, option.second);
        if (result < 0) {
            set_last_error(std::string("mpv option ") + option.first + ": " + mpv_error_string(result));
            mpv_terminate_destroy(handle);
            return nullptr;
        }
    }
    const int initResult = mpv_initialize(handle);
    if (initResult < 0) {
        set_last_error(std::string("mpv_initialize failed: ") + mpv_error_string(initResult));
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

bool tryGetDoubleProperty(mpv_handle* handle, const char* property, double* out_value) {
    if (handle == nullptr || property == nullptr || out_value == nullptr) {
        return false;
    }
    double value = 0.0;
    if (mpv_get_property(handle, property, MPV_FORMAT_DOUBLE, &value) < 0) {
        return false;
    }
    *out_value = value;
    return true;
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
    mpv_observe_property(handle, 0, "sub-text/ass-full", MPV_FORMAT_STRING);
    mpv_observe_property(handle, 0, "sub-ass-extradata", MPV_FORMAT_STRING);
    mpv_observe_property(handle, 0, "sid", MPV_FORMAT_STRING);
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
bool hasSourceVideo(mpv_handle* handle) {
    mpv_node tracks{};
    if (mpv_get_property(handle, "track-list", MPV_FORMAT_NODE, &tracks) < 0) {
        return false;
    }
    bool found = false;
    if (tracks.format == MPV_FORMAT_NODE_ARRAY && tracks.u.list != nullptr) {
        for (int index = 0; index < tracks.u.list->num && !found; index++) {
            const mpv_node& track = tracks.u.list->values[index];
            if (track.format != MPV_FORMAT_NODE_MAP || track.u.list == nullptr) continue;
            bool video = false;
            bool image = false;
            for (int field = 0; field < track.u.list->num; field++) {
                const char* key = track.u.list->keys[field];
                const mpv_node& value = track.u.list->values[field];
                if (strcmp(key, "type") == 0 && value.format == MPV_FORMAT_STRING) {
                    video = strcmp(value.u.string, "video") == 0;
                } else if ((strcmp(key, "image") == 0 || strcmp(key, "albumart") == 0) && value.format == MPV_FORMAT_FLAG) {
                    image = image || value.u.flag != 0;
                }
            }
            found = video && !image;
        }
    }
    mpv_free_node_contents(&tracks);
    return found;
}

bool validateVideoOutput(JNIEnv* env, MpvSession* session) {
    int aborted = 0;
    if (session->surface_ref == nullptr || !hasSourceVideo(session->handle) ||
        (mpv_get_property(session->handle, "playback-abort", MPV_FORMAT_FLAG, &aborted) >= 0 && aborted)) {
        return true;
    }
    char* hwdec = mpv_get_property_string(session->handle, "hwdec-current");
    const bool mediaCodecActive = hwdec != nullptr && strcmp(hwdec, "mediacodec") == 0;
    mpv_free(hwdec);
    session->video_started = mediaCodecActive;
    if (mediaCodecActive) return true;

    // mpv may otherwise continue audio after its video decoder reaches forced EOF.
    dispatchError(
        env, session,
        "mpv MediaCodec video decoding failed. Switch to Media3 for this file.",
        MPV_ERROR_VO_INIT_FAILED, MPV_END_FILE_REASON_ERROR
    );
    const char* stop[] = {"stop", nullptr};
    mpv_command_async(session->handle, 0, stop);
    return false;
}

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
                    // Forward to Kotlin logger so it can be emitted to logcat / TCP log server.
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
                std::lock_guard<std::mutex> guard(session->mutex);
                int64_t width = 0;
                int64_t height = 0;
                mpv_get_property(session->handle, "width", MPV_FORMAT_INT64, &width);
                mpv_get_property(session->handle, "height", MPV_FORMAT_INT64, &height);
                if (session->video_started && !validateVideoOutput(env, session)) break;
                if (session->surface_ref != nullptr && width > 0 && height > 0) {
                    char* hwdec = mpv_get_property_string(session->handle, "hwdec-current");
                    session->video_started = hwdec != nullptr && strcmp(hwdec, "mediacodec") == 0;
                    mpv_free(hwdec);
                }
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
                    } else if (endFile->reason == MPV_END_FILE_REASON_ERROR) {
                        const char* errorMsg = mpv_error_string(endFile->error);
                        dispatchEvent(env, session->event_callback, kEventError, endFile->error, endFile->reason, errorMsg);
                    }
                }
                break;
            }
            case MPV_EVENT_PLAYBACK_RESTART: {
                std::lock_guard<std::mutex> guard(session->mutex);
                int seeking = 0;
                int aborted = 0;
                if (mpv_get_property(session->handle, "seeking", MPV_FORMAT_FLAG, &seeking) < 0 || seeking ||
                    (mpv_get_property(session->handle, "playback-abort", MPV_FORMAT_FLAG, &aborted) >= 0 && aborted)) {
                    // A queued restart can belong to the timeline preceding a Surface rebind or stop.
                    break;
                }
                if (!validateVideoOutput(env, session)) break;
                dispatchEvent(env, session->event_callback, kEventRenderingStart, 0, 0, nullptr);
                break;
            }
            case MPV_EVENT_PROPERTY_CHANGE: {
                auto* prop = static_cast<mpv_event_property*>(event->data);
                if (prop == nullptr || prop->name == nullptr) {
                    break;
                }
                if (prop->format == MPV_FORMAT_FLAG && strcmp(prop->name, "paused-for-cache") == 0) {
                    const bool isCaching = *static_cast<int*>(prop->data) != 0;
                    dispatchEvent(
                        env,
                        session->event_callback,
                        isCaching ? kEventBufferingStart : kEventBufferingEnd,
                        0,
                        0,
                        nullptr
                    );
                    break;
                }
                if (prop->format == MPV_FORMAT_STRING && strcmp(prop->name, "sub-text/ass-full") == 0) {
                    const char* value = nullptr;
                    if (prop->data != nullptr) {
                        value = *static_cast<const char**>(prop->data);
                    }
                    if (value != nullptr) {
                        std::string copy = value;
                        dispatchEvent(env, session->event_callback, kEventSubtitleAssFull, 0, 0, copy.c_str());
                    } else {
                        dispatchEvent(env, session->event_callback, kEventSubtitleAssFull, 0, 0, nullptr);
                    }
                    break;
                }
                if (prop->format == MPV_FORMAT_STRING && strcmp(prop->name, "sub-ass-extradata") == 0) {
                    const char* value = nullptr;
                    if (prop->data != nullptr) {
                        value = *static_cast<const char**>(prop->data);
                    }
                    if (value != nullptr) {
                        std::string copy = value;
                        dispatchEvent(env, session->event_callback, kEventSubtitleAssExtradata, 0, 0, copy.c_str());
                    } else {
                        dispatchEvent(env, session->event_callback, kEventSubtitleAssExtradata, 0, 0, nullptr);
                    }
                    break;
                }
                if (prop->format == MPV_FORMAT_STRING && strcmp(prop->name, "sid") == 0) {
                    const char* value = nullptr;
                    if (prop->data != nullptr) {
                        value = *static_cast<const char**>(prop->data);
                    }
                    if (value != nullptr) {
                        std::string copy = value;
                        dispatchEvent(env, session->event_callback, kEventSubtitleSid, 0, 0, copy.c_str());
                    } else {
                        dispatchEvent(env, session->event_callback, kEventSubtitleSid, 0, 0, nullptr);
                    }
                    break;
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
        std::string codec;
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
            } else if (strcmp(key, "codec") == 0 && value->format == MPV_FORMAT_STRING) {
                codec = value->u.string == nullptr ? "" : value->u.string;
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

        std::string entry;
        entry.reserve(64 + title.size() + codec.size());
        entry.append(std::to_string(trackType))
            .append("|")
            .append(std::to_string(id))
            .append("|")
            .append(selected ? "1" : "0")
            .append("|")
            .append(title)
            .append("|")
            .append(codec);
        result.emplace_back(std::move(entry));
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
	        const int result = mpv_command(session->handle, cmd);
	        if (result < 0) {
	            const std::string message =
	                "mpv audio-add failed: " + std::to_string(result) + " (" + mpv_error_string(result) +
	                ") path=" + path;
	            set_last_error(message);
	            return false;
	        }
	        set_last_error("");
	        return true;
	    }
	    if (trackType == kTrackSubtitle) {
	        const char* cmd[] = {"sub-add", path.c_str(), "select", nullptr};
	        const int result = mpv_command(session->handle, cmd);
	        if (result < 0) {
	            const std::string message =
	                "mpv sub-add failed: " + std::to_string(result) + " (" + mpv_error_string(result) +
	                ") path=" + path;
	            set_last_error(message);
	            return false;
	        }
	        set_last_error("");
	        return true;
	    }
    return false;
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
    if (session->handle != nullptr) {
        mpv_terminate_destroy(session->handle);
        session->handle = nullptr;
    }
    if (session->surface_ref != nullptr) {
        env->DeleteGlobalRef(session->surface_ref);
        session->surface_ref = nullptr;
    }
#endif
    delete session;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xyoye_player_kernel_impl_mpv_MpvNativeBridge_nativeSetSurface(
    JNIEnv* env, jclass, jlong handle, jobject surface) {
    auto* session = fromHandle(handle);
    if (session == nullptr) return JNI_FALSE;
#if MPV_PREBUILT_AVAILABLE
    std::lock_guard<std::mutex> guard(session->mutex);
    if (session->handle == nullptr) return JNI_FALSE;

    auto setProperty = [session](const char* name, const char* value) {
        const int result = mpv_set_property_string(session->handle, name, value);
        if (result < 0) {
            set_last_error(std::string("mpv surface ") + name + ": " + mpv_error_string(result));
            return false;
        }
        return true;
    };
    auto setWindow = [session](int64_t wid) {
        const int result = mpv_set_option(session->handle, "wid", MPV_FORMAT_INT64, &wid);
        if (result < 0) {
            set_last_error(std::string("mpv surface wid: ") + mpv_error_string(result));
            return false;
        }
        return true;
    };

    session->video_started = false;
    if (session->surface_ref != nullptr) {
        int paused = 0;
        int seekable = 0;
        double position = 0.0;
        session->paused_surface_position = -1.0;
        if (mpv_get_property(session->handle, "pause", MPV_FORMAT_FLAG, &paused) >= 0 && paused &&
            mpv_get_property(session->handle, "seekable", MPV_FORMAT_FLAG, &seekable) >= 0 && seekable &&
            tryGetDoubleProperty(session->handle, "time-pos", &position)) {
            session->paused_surface_position = position;
        }
        // Stop the decoder/VO before the producer's Surface reference is released.
        if (!setProperty("vid", "no") || !setProperty("vo", "null") || !setWindow(0)) {
            return JNI_FALSE;
        }
        env->DeleteGlobalRef(session->surface_ref);
        session->surface_ref = nullptr;
    }

    if (surface != nullptr) {
        session->surface_ref = env->NewGlobalRef(surface);
        if (session->surface_ref == nullptr) {
            set_last_error("Failed to allocate global surface reference");
            return JNI_FALSE;
        }
        const auto wid = static_cast<int64_t>(reinterpret_cast<intptr_t>(session->surface_ref));
        // TV video is rendered by MediaCodec; subtitles use the independent app overlay.
        if (!setWindow(wid) || !setProperty("vo", "mediacodec_embed") || !setProperty("vid", "auto")) {
            return JNI_FALSE;
        }
        ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
        if (window != nullptr) {
            char size[64] = {0};
            snprintf(size, sizeof(size), "%dx%d", ANativeWindow_getWidth(window), ANativeWindow_getHeight(window));
            mpv_set_property_string(session->handle, "android-surface-size", size);
            ANativeWindow_release(window);
        }
        int paused = 0;
        if (session->paused_surface_position >= 0.0 &&
            mpv_get_property(session->handle, "pause", MPV_FORMAT_FLAG, &paused) >= 0 && paused) {
            // Reselecting vid resumes demuxing at a keyframe; redraw the actual paused timestamp.
            char position[64] = {0};
            snprintf(position, sizeof(position), "%.9f", session->paused_surface_position);
            const char* seek[] = {"seek", position, "absolute+exact", nullptr};
            const int result = mpv_command(session->handle, seek);
            if (result < 0) {
                set_last_error(std::string("mpv Surface paused seek: ") + mpv_error_string(result));
                return JNI_FALSE;
            }
        }
        session->paused_surface_position = -1.0;
    }
    set_last_error("");
    mpv_wakeup(session->handle);
    return JNI_TRUE;
#else
    (void)env;
    (void)surface;
    return JNI_FALSE;
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
    std::lock_guard<std::mutex> guard(session->mutex);
    session->video_started = false;
    session->paused_surface_position = -1.0;
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
    std::lock_guard<std::mutex> guard(session->mutex);
    session->paused_surface_position = -1.0;
    setFlagProperty(session->handle, "pause", false);
#endif
    session->paused = false;
}

extern "C" JNIEXPORT void JNICALL
Java_com_xyoye_player_kernel_impl_mpv_MpvNativeBridge_nativePause(JNIEnv*, jclass, jlong handle) {
    auto* session = fromHandle(handle);
    if (session == nullptr) return;
#if MPV_PREBUILT_AVAILABLE
    std::lock_guard<std::mutex> guard(session->mutex);
    setFlagProperty(session->handle, "pause", true);
    if (session->surface_ref == nullptr) {
        int seekable = 0;
        double position = 0.0;
        if (mpv_get_property(session->handle, "seekable", MPV_FORMAT_FLAG, &seekable) >= 0 && seekable &&
            tryGetDoubleProperty(session->handle, "time-pos", &position)) {
            session->paused_surface_position = position;
        }
    }
#endif
    session->paused = true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_xyoye_player_kernel_impl_mpv_MpvNativeBridge_nativeStop(JNIEnv*, jclass, jlong handle) {
    auto* session = fromHandle(handle);
    if (session == nullptr) return;
#if MPV_PREBUILT_AVAILABLE
    std::lock_guard<std::mutex> guard(session->mutex);
    session->video_started = false;
    session->paused_surface_position = -1.0;
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
    std::lock_guard<std::mutex> guard(session->mutex);
    const double positionSeconds = static_cast<double>(positionMs) / 1000.0;
    if (session->paused_surface_position >= 0.0) {
        session->paused_surface_position = positionSeconds;
    }
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
