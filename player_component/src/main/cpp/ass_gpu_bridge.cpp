#include <jni.h>

#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <ass/ass.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <cstdarg>
#include <mutex>
#include <new>
#include <string>
#include <vector>

#ifndef EGL_RECORDABLE_ANDROID
#define EGL_RECORDABLE_ANDROID 0x3142
#endif

namespace {
constexpr const char *kGpuLogTag = "AssGpuBridge";
constexpr const char *kVertexShaderSrc = R"(#version 300 es
layout (location = 0) in vec2 aPosition;
layout (location = 1) in vec2 aTexCoord;
out vec2 vTexCoord;
void main() {
    vTexCoord = aTexCoord;
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
)";
constexpr const char *kFragmentShaderSrc = R"(#version 300 es
precision mediump float;
in vec2 vTexCoord;
uniform sampler2D uBitmap;
uniform vec4 uColor;
out vec4 outColor;
void main() {
    float coverage = texture(uBitmap, vTexCoord).r;
    float alpha = uColor.a * coverage;
    vec3 rgb = uColor.rgb * alpha;
    outColor = vec4(rgb, alpha);
}
)";
constexpr long long kDefaultEmbeddedChunkDurationMs = 5000;

struct GpuContext {
    std::mutex mutex;
    ANativeWindow *window = nullptr;
    int width = 0;
    int height = 0;
    int last_frame_width = 0;
    int last_frame_height = 0;
    float scale = 1.0F;
    int rotation = 0;
    std::string color_format;
    bool supports_hardware_buffer = false;
    bool telemetry_enabled = true;
    int64_t last_vsync_id = 0;
    int gles_version = 3;
    EGLDisplay egl_display = EGL_NO_DISPLAY;
    EGLContext egl_context = EGL_NO_CONTEXT;
    EGLSurface egl_surface = EGL_NO_SURFACE;
    EGLConfig egl_config = nullptr;
    GLuint program = 0;
    GLuint vertex_buffer = 0;
    GLint uniform_color = -1;
    GLint uniform_sampler = -1;
    ASS_Library *library = nullptr;
    ASS_Renderer *renderer = nullptr;
    ASS_Track *track = nullptr;
    float user_alpha = 1.0F;
    bool pending_invalidate = false;
    struct TextureEntry {
        GLuint id = 0;
        int width = 0;
        int height = 0;
        bool params_set = false;
    };
    std::vector<TextureEntry> texture_pool;
    size_t texture_pool_pos = 0;
    std::vector<uint8_t> upload_buffer;
};

struct ScoredConfig {
    EGLConfig config = nullptr;
    int gles_version = 2;
    int score = 0;
    EGLint alpha = 0;
    EGLint native_visual = 0;
    EGLint recordable = 0;
    EGLint red = 0;
    EGLint green = 0;
    EGLint blue = 0;
};

void LogInfo(const char *message) {
    __android_log_print(ANDROID_LOG_INFO, kGpuLogTag, "%s", message);
}

void LogError(const char *message) {
    __android_log_print(ANDROID_LOG_ERROR, kGpuLogTag, "%s", message);
}

std::string FormatEglError(const char *label) {
    const EGLint error = eglGetError();
    char buffer[80];
    std::snprintf(buffer, sizeof(buffer), "%s (eglError=0x%04x)", label, error);
    buffer[sizeof(buffer) - 1] = '\0';
    return std::string(buffer);
}

void DumpEglConfigs(EGLDisplay display) {
    static bool dumped = false;
    if (dumped || display == EGL_NO_DISPLAY) {
        return;
    }
    dumped = true;
    EGLint config_count = 0;
    if (eglGetConfigs(display, nullptr, 0, &config_count) != EGL_TRUE || config_count <= 0) {
        LogError(FormatEglError("eglGetConfigs count failed").c_str());
        return;
    }
    std::vector<EGLConfig> configs(static_cast<size_t>(config_count));
    if (eglGetConfigs(display, configs.data(), config_count, &config_count) != EGL_TRUE) {
        LogError(FormatEglError("eglGetConfigs list failed").c_str());
        return;
    }
    const int max_log = 32;
    for (int i = 0; i < config_count && i < max_log; ++i) {
        const EGLConfig cfg = configs[static_cast<size_t>(i)];
        EGLint renderable = 0;
        EGLint alpha = 0;
        EGLint native_visual = 0;
        EGLint surface_type = 0;
        EGLint recordable = 0;
        eglGetConfigAttrib(display, cfg, EGL_RENDERABLE_TYPE, &renderable);
        eglGetConfigAttrib(display, cfg, EGL_ALPHA_SIZE, &alpha);
        eglGetConfigAttrib(display, cfg, EGL_NATIVE_VISUAL_ID, &native_visual);
        eglGetConfigAttrib(display, cfg, EGL_SURFACE_TYPE, &surface_type);
        eglGetConfigAttrib(display, cfg, EGL_RECORDABLE_ANDROID, &recordable);
        __android_log_print(ANDROID_LOG_INFO, kGpuLogTag,
                            "EGL config #%d: renderable=0x%x alpha=%d native_visual=0x%x surface=0x%x recordable=%d",
                            i, renderable, alpha, native_visual, surface_type, recordable);
    }
    if (config_count > max_log) {
        __android_log_print(ANDROID_LOG_INFO, kGpuLogTag,
                            "EGL config list truncated: %d total, logged %d", config_count,
                            max_log);
    }
}

EGLint GetConfigAttr(EGLDisplay display, EGLConfig config, EGLint attribute) {
    EGLint value = 0;
    eglGetConfigAttrib(display, config, attribute, &value);
    return value;
}

std::vector<ScoredConfig> EnumerateAndScoreConfigs(EGLDisplay display, int window_format) {
    EGLint config_count = 0;
    if (display == EGL_NO_DISPLAY ||
        eglGetConfigs(display, nullptr, 0, &config_count) != EGL_TRUE || config_count <= 0) {
        return {};
    }
    std::vector<EGLConfig> configs(static_cast<size_t>(config_count));
    if (eglGetConfigs(display, configs.data(), config_count, &config_count) != EGL_TRUE) {
        return {};
    }

    std::vector<ScoredConfig> scored;
    scored.reserve(static_cast<size_t>(config_count));
    for (int i = 0; i < config_count; ++i) {
        const EGLConfig cfg = configs[static_cast<size_t>(i)];
        const EGLint surface_type = GetConfigAttr(display, cfg, EGL_SURFACE_TYPE);
        if ((surface_type & EGL_WINDOW_BIT) == 0) {
            continue;
        }

        const EGLint renderable = GetConfigAttr(display, cfg, EGL_RENDERABLE_TYPE);
        const bool es3 = (renderable & EGL_OPENGL_ES3_BIT) != 0;
        const bool es2 = (renderable & EGL_OPENGL_ES2_BIT) != 0;
        if (!es3 && !es2) {
            continue;
        }

        ScoredConfig candidate;
        candidate.config = cfg;
        candidate.gles_version = es3 ? 3 : 2;
        candidate.alpha = GetConfigAttr(display, cfg, EGL_ALPHA_SIZE);
        candidate.native_visual = GetConfigAttr(display, cfg, EGL_NATIVE_VISUAL_ID);
        candidate.recordable = GetConfigAttr(display, cfg, EGL_RECORDABLE_ANDROID);
        candidate.red = GetConfigAttr(display, cfg, EGL_RED_SIZE);
        candidate.green = GetConfigAttr(display, cfg, EGL_GREEN_SIZE);
        candidate.blue = GetConfigAttr(display, cfg, EGL_BLUE_SIZE);

        int score = 0;
        score += es3 ? 8 : 4;
        if (candidate.recordable == EGL_TRUE) score += 4;
        if (candidate.alpha >= 8) {
            score += 2;
        } else if (candidate.alpha > 0) {
            score += 1;
        }
        if (candidate.red == 8 && candidate.green == 8 && candidate.blue == 8) {
            score += 2;
        } else if (candidate.red == 5 && candidate.green == 6 && candidate.blue == 5) {
            score += 1;
        }
        if (window_format > 0 && candidate.native_visual == window_format) {
            score += 2;
        }
        candidate.score = score;
        scored.push_back(candidate);
    }

std::sort(scored.begin(), scored.end(), [](const ScoredConfig &a, const ScoredConfig &b) {
        if (a.score != b.score) return a.score > b.score;
        if (a.gles_version != b.gles_version) return a.gles_version > b.gles_version;
        if (a.alpha != b.alpha) return a.alpha > b.alpha;
        if (a.recordable != b.recordable) return a.recordable > b.recordable;
        const int bits_a = a.red + a.green + a.blue;
        const int bits_b = b.red + b.green + b.blue;
        return bits_a > bits_b;
    });
    return scored;
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

void ReleaseWindow(GpuContext *context) {
    if (context == nullptr || context->window == nullptr) return;
    ANativeWindow_release(context->window);
    context->window = nullptr;
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
        ass_set_message_cb(context->library, [](int level, const char *fmt, va_list args, void *) {
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
        }, nullptr);
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

inline uint8_t AssAlphaToAndroid(uint32_t color) {
    const uint8_t ass_alpha = static_cast<uint8_t>(color & 0xFF);
    return static_cast<uint8_t>(255 - ass_alpha);
}

GLuint CompileShader(GLenum type, const char *source) {
    GLuint shader = glCreateShader(type);
    if (shader == 0) {
        LogError("Failed to create shader");
        return 0;
    }
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint compiled = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (compiled != GL_TRUE) {
        GLint length = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &length);
        std::string log(std::max(length, 1), '\0');
        glGetShaderInfoLog(shader, length, nullptr, log.data());
        LogError(("Shader compile failed: " + log).c_str());
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

bool EnsureProgram(GpuContext *context) {
    if (context->program != 0) return true;
    GLuint vertex = CompileShader(GL_VERTEX_SHADER, kVertexShaderSrc);
    GLuint fragment = CompileShader(GL_FRAGMENT_SHADER, kFragmentShaderSrc);
    if (vertex == 0 || fragment == 0) {
        if (vertex != 0) glDeleteShader(vertex);
        if (fragment != 0) glDeleteShader(fragment);
        return false;
    }
    GLuint program = glCreateProgram();
    glAttachShader(program, vertex);
    glAttachShader(program, fragment);
    glLinkProgram(program);
    glDeleteShader(vertex);
    glDeleteShader(fragment);
    GLint linked = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
        GLint length = 0;
        glGetProgramiv(program, GL_INFO_LOG_LENGTH, &length);
        std::string log(std::max(length, 1), '\0');
        glGetProgramInfoLog(program, length, nullptr, log.data());
        LogError(("Program link failed: " + log).c_str());
        glDeleteProgram(program);
        return false;
    }
    context->program = program;
    context->uniform_color = glGetUniformLocation(program, "uColor");
    context->uniform_sampler = glGetUniformLocation(program, "uBitmap");
    glUseProgram(context->program);
    glUniform1i(context->uniform_sampler, 0);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    glDisable(GL_DEPTH_TEST);
    if (context->vertex_buffer == 0) {
        glGenBuffers(1, &context->vertex_buffer);
    }
    return true;
}

void DestroyEgl(GpuContext *context, bool destroy_context = true) {
    if (context == nullptr) return;
    if (context->egl_display != EGL_NO_DISPLAY && context->egl_surface != EGL_NO_SURFACE) {
        eglDestroySurface(context->egl_display, context->egl_surface);
        context->egl_surface = EGL_NO_SURFACE;
    }
    if (destroy_context && context->egl_display != EGL_NO_DISPLAY &&
        context->egl_context != EGL_NO_CONTEXT) {
        if (context->program != 0 || context->vertex_buffer != 0 ||
            !context->texture_pool.empty()) {
            eglMakeCurrent(context->egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE,
                           context->egl_context);
            if (context->program != 0) {
                glDeleteProgram(context->program);
            }
            if (context->vertex_buffer != 0) {
                glDeleteBuffers(1, &context->vertex_buffer);
            }
            if (!context->texture_pool.empty()) {
                std::vector<GLuint> ids;
                ids.reserve(context->texture_pool.size());
                for (const auto &entry : context->texture_pool) {
                    if (entry.id != 0) ids.push_back(entry.id);
                }
                if (!ids.empty()) {
                    glDeleteTextures(static_cast<GLsizei>(ids.size()), ids.data());
                }
                context->texture_pool.clear();
                context->texture_pool_pos = 0;
            }
        }
        eglDestroyContext(context->egl_display, context->egl_context);
        context->egl_context = EGL_NO_CONTEXT;
        context->program = 0;
        context->vertex_buffer = 0;
    }
    if (destroy_context && context->egl_display != EGL_NO_DISPLAY) {
        eglTerminate(context->egl_display);
        context->egl_display = EGL_NO_DISPLAY;
    }
    context->uniform_color = -1;
    context->uniform_sampler = -1;
    context->egl_config = nullptr;
}

bool InitEglAndSurface(GpuContext *context) {
    if (context == nullptr || context->window == nullptr) {
        return false;
    }
    DestroyEgl(context);
    context->egl_display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (context->egl_display == EGL_NO_DISPLAY) {
        LogError("Failed to get EGL display");
        return false;
    }
    if (!eglBindAPI(EGL_OPENGL_ES_API)) {
        LogError(FormatEglError("Failed to bind GLES API").c_str());
        DestroyEgl(context);
        return false;
    }
    if (!eglInitialize(context->egl_display, nullptr, nullptr)) {
        LogError(FormatEglError("Failed to initialize EGL").c_str());
        DestroyEgl(context);
        return false;
    }

    const int window_format = ANativeWindow_getFormat(context->window);
    const auto scored_configs = EnumerateAndScoreConfigs(context->egl_display, window_format);
    if (scored_configs.empty()) {
        DumpEglConfigs(context->egl_display);
        DestroyEgl(context);
        LogError("No compatible EGL configs found");
        return false;
    }

    for (const auto &candidate : scored_configs) {
        const EGLint ctx_attribs[] = {EGL_CONTEXT_CLIENT_VERSION, candidate.gles_version, EGL_NONE};
        EGLContext egl_context = eglCreateContext(context->egl_display, candidate.config,
                                                  EGL_NO_CONTEXT, ctx_attribs);
        if (egl_context == EGL_NO_CONTEXT) {
            LogError(FormatEglError("Failed to create EGL context").c_str());
            continue;
        }

        if (candidate.native_visual != 0) {
            const int result =
                ANativeWindow_setBuffersGeometry(context->window, 0, 0, candidate.native_visual);
            if (result != 0) {
                __android_log_print(ANDROID_LOG_WARN, kGpuLogTag,
                                    "setBuffersGeometry failed: %d (format=0x%x)",
                                    result, candidate.native_visual);
            }
        }

        EGLSurface egl_surface = eglCreateWindowSurface(context->egl_display, candidate.config,
                                                        context->window, nullptr);
        if (egl_surface == EGL_NO_SURFACE) {
            LogError(FormatEglError("Failed to create EGL surface").c_str());
            eglDestroyContext(context->egl_display, egl_context);
            continue;
        }

        context->egl_config = candidate.config;
        context->gles_version = candidate.gles_version;
        context->egl_context = egl_context;
        context->egl_surface = egl_surface;
        return true;
    }

    DumpEglConfigs(context->egl_display);
    DestroyEgl(context);
    LogError("Failed to initialize EGL with any candidate config");
    return false;
}

bool MakeCurrent(GpuContext *context) {
    if (context->egl_display == EGL_NO_DISPLAY || context->egl_surface == EGL_NO_SURFACE ||
        context->egl_context == EGL_NO_CONTEXT) {
        return false;
    }
    if (eglGetCurrentContext() != context->egl_context ||
        eglGetCurrentSurface(EGL_DRAW) != context->egl_surface) {
        if (!eglMakeCurrent(context->egl_display, context->egl_surface, context->egl_surface,
                            context->egl_context)) {
            LogError(FormatEglError("eglMakeCurrent failed").c_str());
            return false;
        }
    }
    return true;
}

bool EnsureSurface(GpuContext *context) {
    if (context->window == nullptr) {
        return false;
    }
    if (context->egl_display == EGL_NO_DISPLAY || context->egl_context == EGL_NO_CONTEXT ||
        context->egl_config == nullptr || context->egl_surface == EGL_NO_SURFACE) {
        if (!InitEglAndSurface(context)) {
            return false;
        }
    }
    if (!MakeCurrent(context)) {
        DestroyEgl(context);
        if (!InitEglAndSurface(context) || !MakeCurrent(context)) {
            return false;
        }
    }
    glViewport(0, 0, context->width, context->height);
    return EnsureProgram(context);
}

void WriteRenderMetrics(JNIEnv *env, jlongArray metrics_out, jlong render_ms, jlong upload_ms,
                        jlong composite_ms) {
    if (metrics_out == nullptr) {
        return;
    }
    jlong values[3] = {render_ms, upload_ms, composite_ms};
    env->SetLongArrayRegion(metrics_out, 0, 3, values);
}

void UpdateFrameSizeIfNeeded(GpuContext *context) {
    if (context == nullptr || context->renderer == nullptr) return;
    if (context->width <= 0 || context->height <= 0) return;
    if (context->width == context->last_frame_width &&
        context->height == context->last_frame_height) {
        return;
    }
    ass_set_frame_size(context->renderer, context->width, context->height);
    context->last_frame_width = context->width;
    context->last_frame_height = context->height;
}

GpuContext::TextureEntry &AcquireTexture(GpuContext *context, int width, int height) {
    if (context->texture_pool_pos >= context->texture_pool.size()) {
        GLuint id = 0;
        glGenTextures(1, &id);
        context->texture_pool.push_back({id, 0, 0, false});
    }
    auto &entry = context->texture_pool[context->texture_pool_pos++];
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, entry.id);
    if (!entry.params_set) {
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        entry.params_set = true;
    }
    if (entry.width != width || entry.height != height) {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, width, height, 0, GL_RED, GL_UNSIGNED_BYTE,
                     nullptr);
        entry.width = width;
        entry.height = height;
    }
    return entry;
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_xyoye_player_subtitle_gpu_AssGpuNativeBridge_nativeCreate(
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
Java_com_xyoye_player_subtitle_gpu_AssGpuNativeBridge_nativeDestroy(
    JNIEnv *env, jobject /*thiz*/, jlong handle) {
    (void)env;
    auto *context = reinterpret_cast<GpuContext *>(handle);
    if (context == nullptr) {
        return;
    }
    {
        std::lock_guard<std::mutex> guard(context->mutex);
        DestroyAss(context);
        DestroyEgl(context);
        ReleaseWindow(context);
    }
    delete context;
    LogInfo("GPU context destroyed");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xyoye_player_subtitle_gpu_AssGpuNativeBridge_nativeAttachSurface(
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
    DestroyEgl(context);
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
    if (context->window == nullptr) {
        LogError("Failed to attach GPU surface (window null)");
        return JNI_FALSE;
    }
    if (!EnsureSurface(context)) {
        LogError("Failed to set up EGL surface for GPU pipeline");
        return JNI_FALSE;
    }
    LogInfo("GPU surface attached");
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_xyoye_player_subtitle_gpu_AssGpuNativeBridge_nativeDetachSurface(
    JNIEnv *env, jobject /*thiz*/, jlong handle) {
    (void)env;
    auto *context = reinterpret_cast<GpuContext *>(handle);
    if (context == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> guard(context->mutex);
    DestroyEgl(context);
    ReleaseWindow(context);
    context->width = 0;
    context->height = 0;
    context->last_frame_width = 0;
    context->last_frame_height = 0;
    context->last_vsync_id = 0;
    LogInfo("GPU surface detached");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xyoye_player_subtitle_gpu_AssGpuNativeBridge_nativeLoadTrack(
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
    UpdateFrameSizeIfNeeded(context);
    LogInfo("GPU subtitle track loaded");
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_xyoye_player_subtitle_gpu_AssGpuNativeBridge_nativeInitEmbeddedTrack(
    JNIEnv *env,
    jobject /*thiz*/,
    jlong handle,
    jbyteArray codec_private,
    jobjectArray font_dirs,
    jstring default_font) {
    auto *context = reinterpret_cast<GpuContext *>(handle);
    if (context == nullptr) return;
    std::lock_guard<std::mutex> guard(context->mutex);
    EnsureAss(context);
    const auto fontDirectories = JObjectArrayToStrings(env, font_dirs);
    const std::string defaultFontPath = JStringToUtf8(env, default_font);
    ConfigureFonts(context, defaultFontPath, fontDirectories);
    if (context->track != nullptr) {
        ass_free_track(context->track);
        context->track = nullptr;
    }
    context->track = ass_new_track(context->library);
    if (context->track == nullptr) {
        LogError("Failed to create embedded SSA/ASS track");
        return;
    }
    if (codec_private != nullptr) {
        const jsize length = env->GetArrayLength(codec_private);
        if (length > 0) {
            jbyte *bytes = env->GetByteArrayElements(codec_private, nullptr);
            ass_process_codec_private(context->track,
                                      reinterpret_cast<char *>(bytes),
                                      length);
            env->ReleaseByteArrayElements(codec_private, bytes, JNI_ABORT);
        }
    }
    UpdateFrameSizeIfNeeded(context);
    context->pending_invalidate = true;
    LogInfo("Embedded SSA/ASS track initialized");
}

extern "C" JNIEXPORT void JNICALL
Java_com_xyoye_player_subtitle_gpu_AssGpuNativeBridge_nativeAppendEmbeddedChunk(
    JNIEnv *env,
    jobject /*thiz*/,
    jlong handle,
    jbyteArray data,
    jlong time_ms,
    jlong duration_ms) {
    auto *context = reinterpret_cast<GpuContext *>(handle);
    if (context == nullptr) return;
    std::lock_guard<std::mutex> guard(context->mutex);
    if (context->track == nullptr) {
        LogError("Embedded chunk ignored: track not initialized");
        return;
    }
    const jsize length = env->GetArrayLength(data);
    if (length <= 0) {
        return;
    }
    jbyte *bytes = env->GetByteArrayElements(data, nullptr);
    const long long safe_duration = duration_ms > 0 ? duration_ms : kDefaultEmbeddedChunkDurationMs;
    ass_process_chunk(context->track,
                      reinterpret_cast<char *>(bytes),
                      length,
                      static_cast<long long>(time_ms),
                      safe_duration);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    context->pending_invalidate = true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_xyoye_player_subtitle_gpu_AssGpuNativeBridge_nativeFlushEmbeddedEvents(
    JNIEnv *env, jobject /*thiz*/, jlong handle) {
    (void)env;
    auto *context = reinterpret_cast<GpuContext *>(handle);
    if (context == nullptr) return;
    std::lock_guard<std::mutex> guard(context->mutex);
    if (context->track != nullptr) {
        ass_flush_events(context->track);
        context->pending_invalidate = true;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_xyoye_player_subtitle_gpu_AssGpuNativeBridge_nativeClearEmbeddedTrack(
    JNIEnv *env, jobject /*thiz*/, jlong handle) {
    (void)env;
    auto *context = reinterpret_cast<GpuContext *>(handle);
    if (context == nullptr) return;
    std::lock_guard<std::mutex> guard(context->mutex);
    if (context->track != nullptr) {
        ass_free_track(context->track);
        context->track = nullptr;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xyoye_player_subtitle_gpu_AssGpuNativeBridge_nativeRender(
    JNIEnv *env,
    jobject /*thiz*/,
    jlong handle,
    jlong subtitle_pts_ms,
    jlong vsync_id,
    jlongArray metrics_out) {
    auto *context = reinterpret_cast<GpuContext *>(handle);
    if (context == nullptr) {
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> guard(context->mutex);
    context->last_vsync_id = vsync_id;
    const bool collect_metrics = metrics_out != nullptr && context->telemetry_enabled;
    if (context->window == nullptr || context->renderer == nullptr || context->track == nullptr ||
        context->width <= 0 || context->height <= 0) {
        if (collect_metrics) {
            WriteRenderMetrics(env, metrics_out, 0, 0, 0);
        }
        return JNI_FALSE;
    }
    UpdateFrameSizeIfNeeded(context);
    if (!EnsureSurface(context) || !MakeCurrent(context)) {
        if (collect_metrics) {
            WriteRenderMetrics(env, metrics_out, 0, 0, 0);
        }
        return JNI_FALSE;
    }

    int change = 0;
    std::chrono::steady_clock::time_point render_start;
    if (collect_metrics) {
        render_start = std::chrono::steady_clock::now();
    }
    ASS_Image *img = ass_render_frame(context->renderer, context->track,
                                      static_cast<int>(subtitle_pts_ms), &change);
    std::chrono::steady_clock::time_point render_end;
    if (collect_metrics) {
        render_end = std::chrono::steady_clock::now();
    }
    if (change == 0 && !context->pending_invalidate) {
        // libass 表示当前时间戳无需重绘，直接复用上一帧，避免重复上传/绘制开销。
        if (collect_metrics) {
            WriteRenderMetrics(env, metrics_out, 0, 0, 0);
        }
        return JNI_TRUE;
    }
    context->pending_invalidate = false;

    context->texture_pool_pos = 0;
    glViewport(0, 0, context->width, context->height);
    glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
    glClear(GL_COLOR_BUFFER_BIT);

    double upload_ms = 0.0;
    double composite_ms = 0.0;

    glUseProgram(context->program);
    glBindBuffer(GL_ARRAY_BUFFER, context->vertex_buffer);
    glBufferData(GL_ARRAY_BUFFER, sizeof(float) * 16, nullptr, GL_DYNAMIC_DRAW);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, sizeof(float) * 4,
                          reinterpret_cast<void *>(0));
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, sizeof(float) * 4,
                          reinterpret_cast<void *>(sizeof(float) * 2));
    glEnableVertexAttribArray(1);

    for (ASS_Image *cur = img; cur != nullptr; cur = cur->next) {
        if (cur->w <= 0 || cur->h <= 0) continue;
        const float base_alpha = static_cast<float>(AssAlphaToAndroid(cur->color)) / 255.0F;
        const float final_alpha = base_alpha * context->user_alpha;
        if (final_alpha <= 0.0F) continue;

        const std::chrono::steady_clock::time_point upload_start =
            collect_metrics ? std::chrono::steady_clock::now() : std::chrono::steady_clock::time_point{};
        AcquireTexture(context, cur->w, cur->h);
        if (context->gles_version >= 3 && cur->bitmap != nullptr && cur->stride > 0 &&
            cur->stride >= cur->w) {
            if (cur->stride != cur->w) {
                glPixelStorei(GL_UNPACK_ROW_LENGTH, cur->stride);
            }
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, cur->w, cur->h, GL_RED, GL_UNSIGNED_BYTE,
                            cur->bitmap);
            if (cur->stride != cur->w) {
                glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
            }
        } else {
            const size_t required = static_cast<size_t>(cur->w * cur->h);
            if (context->upload_buffer.size() < required) {
                context->upload_buffer.resize(required);
            }
            uint8_t *coverage = context->upload_buffer.data();
            for (int y = 0; y < cur->h; ++y) {
                const uint8_t *src_row = cur->bitmap + y * cur->stride;
                std::memcpy(coverage + static_cast<size_t>(y * cur->w), src_row,
                            static_cast<size_t>(cur->w));
            }
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, cur->w, cur->h, GL_RED, GL_UNSIGNED_BYTE,
                            coverage);
        }
        if (collect_metrics) {
            const auto upload_end = std::chrono::steady_clock::now();
            upload_ms += std::chrono::duration_cast<std::chrono::microseconds>(upload_end - upload_start)
                             .count() /
                         1000.0;
        }

        const float left = (static_cast<float>(cur->dst_x) / static_cast<float>(context->width)) *
                               2.0F -
                           1.0F;
        const float right = (static_cast<float>(cur->dst_x + cur->w) /
                             static_cast<float>(context->width)) *
                                2.0F -
                            1.0F;
        const float top = 1.0F -
                          (static_cast<float>(cur->dst_y) / static_cast<float>(context->height)) *
                              2.0F;
        const float bottom =
            1.0F - (static_cast<float>(cur->dst_y + cur->h) / static_cast<float>(context->height)) *
                       2.0F;

        const std::chrono::steady_clock::time_point draw_start =
            collect_metrics ? std::chrono::steady_clock::now() : std::chrono::steady_clock::time_point{};
        const float red = static_cast<float>((cur->color >> 24) & 0xFF) / 255.0F;
        const float green = static_cast<float>((cur->color >> 16) & 0xFF) / 255.0F;
        const float blue = static_cast<float>((cur->color >> 8) & 0xFF) / 255.0F;
        glUniform4f(context->uniform_color, red, green, blue, final_alpha);

        const float vertices[] = {
            left,  top,    0.0F, 0.0F,  // left-top
            right, top,    1.0F, 0.0F,  // right-top
            left,  bottom, 0.0F, 1.0F,  // left-bottom
            right, bottom, 1.0F, 1.0F   // right-bottom
        };

        glBufferSubData(GL_ARRAY_BUFFER, 0, sizeof(vertices), vertices);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        if (collect_metrics) {
            const auto draw_end = std::chrono::steady_clock::now();
            composite_ms += std::chrono::duration_cast<std::chrono::microseconds>(draw_end - draw_start)
                                .count() /
                            1000.0;
        }
    }

    std::chrono::steady_clock::time_point swap_start;
    if (collect_metrics) {
        swap_start = std::chrono::steady_clock::now();
    }
    eglSwapBuffers(context->egl_display, context->egl_surface);
    if (collect_metrics) {
        const auto swap_end = std::chrono::steady_clock::now();
        composite_ms +=
            std::chrono::duration_cast<std::chrono::microseconds>(swap_end - swap_start).count() /
            1000.0;

        const auto render_latency =
            std::chrono::duration_cast<std::chrono::microseconds>(render_end - render_start).count() /
            1000;
        WriteRenderMetrics(env, metrics_out, render_latency, static_cast<jlong>(upload_ms),
                           static_cast<jlong>(composite_ms));
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_xyoye_player_subtitle_gpu_AssGpuNativeBridge_nativeFlush(
    JNIEnv *env, jobject /*thiz*/, jlong handle) {
    (void)env;
    auto *context = reinterpret_cast<GpuContext *>(handle);
    if (context == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> guard(context->mutex);
    if (context->egl_display != EGL_NO_DISPLAY && context->egl_surface != EGL_NO_SURFACE &&
        context->egl_context != EGL_NO_CONTEXT) {
        MakeCurrent(context);
        glFinish();
        LogInfo("GPU pipeline flush requested");
        return;
    }
    LogInfo("GPU pipeline flush requested");
}

extern "C" JNIEXPORT void JNICALL
Java_com_xyoye_player_subtitle_gpu_AssGpuNativeBridge_nativeSetTelemetryEnabled(
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

extern "C" JNIEXPORT void JNICALL
Java_com_xyoye_player_subtitle_gpu_AssGpuNativeBridge_nativeSetGlobalOpacity(
    JNIEnv *env, jobject /*thiz*/, jlong handle, jint percent) {
    (void)env;
    auto *context = reinterpret_cast<GpuContext *>(handle);
    if (context == nullptr) {
        return;
    }
    const int clamped = std::max(0, std::min(100, static_cast<int>(percent)));
    const float scaled = static_cast<float>(clamped) / 100.0F;
    std::lock_guard<std::mutex> guard(context->mutex);
    if (context->user_alpha != scaled) {
        context->user_alpha = scaled;
        context->pending_invalidate = true;
    }
}
