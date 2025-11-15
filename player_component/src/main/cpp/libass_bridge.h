#pragma once

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jlong JNICALL
Java_com_xyoye_player_subtitle_libass_LibassBridge_nativeCreate(JNIEnv *env, jobject thiz);

JNIEXPORT void JNICALL
Java_com_xyoye_player_subtitle_libass_LibassBridge_nativeDestroy(JNIEnv *env, jobject thiz, jlong handle);

JNIEXPORT void JNICALL
Java_com_xyoye_player_subtitle_libass_LibassBridge_nativeSetFrameSize(JNIEnv *env, jobject thiz, jlong handle,
                                                                     jint width, jint height);

JNIEXPORT void JNICALL
Java_com_xyoye_player_subtitle_libass_LibassBridge_nativeSetFonts(JNIEnv *env, jobject thiz, jlong handle,
                                                                 jstring default_font, jobjectArray font_directories);

JNIEXPORT jboolean JNICALL
Java_com_xyoye_player_subtitle_libass_LibassBridge_nativeLoadTrack(JNIEnv *env, jobject thiz, jlong handle,
                                                                  jstring file_path);

JNIEXPORT jboolean JNICALL
Java_com_xyoye_player_subtitle_libass_LibassBridge_nativeRenderFrame(JNIEnv *env, jobject thiz, jlong handle,
                                                                    jlong time_ms, jobject bitmap);

#ifdef __cplusplus
}
#endif
