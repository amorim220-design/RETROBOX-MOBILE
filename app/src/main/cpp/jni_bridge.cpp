#include <jni.h>
#include <android/native_window_jni.h>

#include <string>
#include <vector>

#include "retrobox.h"

using retrobox::Emulator;

namespace {

std::string jstringToStd(JNIEnv *env, jstring value) {
    if (value == nullptr) return std::string();
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars != nullptr ? chars : "");
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return result;
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_retrobox_emulation_NativeEmulator_nativeSetDirectories(JNIEnv *env, jobject, jstring systemDir,
                                                                jstring saveDir) {
    Emulator::instance().setDirectories(jstringToStd(env, systemDir), jstringToStd(env, saveDir));
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_retrobox_emulation_NativeEmulator_nativeLoadCore(JNIEnv *env, jobject, jstring corePath) {
    std::string error;
    if (!Emulator::instance().loadCore(jstringToStd(env, corePath), &error)) {
        return env->NewStringUTF(error.c_str());
    }
    return nullptr;
}

JNIEXPORT jboolean JNICALL
Java_com_retrobox_emulation_NativeEmulator_nativeSetSurface(JNIEnv *env, jobject, jobject surface,
                                                            jint pixelFormat) {
    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) return JNI_FALSE;
    bool ok = Emulator::instance().setSurface(window, pixelFormat);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_retrobox_emulation_NativeEmulator_nativeDestroySurface(JNIEnv *, jobject) {
    Emulator::instance().destroySurface();
}

JNIEXPORT jstring JNICALL
Java_com_retrobox_emulation_NativeEmulator_nativeStartGame(JNIEnv *env, jobject, jstring romPath,
                                                           jstring sramPath) {
    std::string error = Emulator::instance().startGame(jstringToStd(env, romPath), jstringToStd(env, sramPath));
    if (error.empty()) return nullptr;
    return env->NewStringUTF(error.c_str());
}

JNIEXPORT void JNICALL
Java_com_retrobox_emulation_NativeEmulator_nativeStop(JNIEnv *, jobject) {
    Emulator::instance().stopLoop();
}

JNIEXPORT void JNICALL
Java_com_retrobox_emulation_NativeEmulator_nativeUnloadCore(JNIEnv *, jobject) {
    Emulator::instance().unloadCore();
}

JNIEXPORT void JNICALL
Java_com_retrobox_emulation_NativeEmulator_nativeSetButton(JNIEnv *, jobject, jint port, jint id,
                                                           jboolean pressed) {
    Emulator::instance().setButton(port, id, pressed == JNI_TRUE);
}

JNIEXPORT jbyteArray JNICALL
Java_com_retrobox_emulation_NativeEmulator_nativeSaveState(JNIEnv *env, jobject) {
    std::vector<uint8_t> state = Emulator::instance().serializeState();
    if (state.empty()) return nullptr;
    jbyteArray array = env->NewByteArray(static_cast<jsize>(state.size()));
    env->SetByteArrayRegion(array, 0, static_cast<jsize>(state.size()),
                            reinterpret_cast<const jbyte *>(state.data()));
    return array;
}

JNIEXPORT jboolean JNICALL
Java_com_retrobox_emulation_NativeEmulator_nativeLoadState(JNIEnv *env, jobject, jbyteArray data) {
    if (data == nullptr) return JNI_FALSE;
    jsize size = env->GetArrayLength(data);
    std::vector<uint8_t> buffer(static_cast<size_t>(size));
    env->GetByteArrayRegion(data, 0, size, reinterpret_cast<jbyte *>(buffer.data()));
    bool ok = Emulator::instance().unserializeState(buffer.data(), buffer.size());
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_retrobox_emulation_NativeEmulator_nativeCanSerialize(JNIEnv *, jobject) {
    return Emulator::instance().canSerialize() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_retrobox_emulation_NativeEmulator_nativeReset(JNIEnv *, jobject) {
    Emulator::instance().reset();
}

JNIEXPORT jdouble JNICALL
Java_com_retrobox_emulation_NativeEmulator_nativeGetFps(JNIEnv *, jobject) {
    return Emulator::instance().measuredFps();
}

JNIEXPORT void JNICALL
Java_com_retrobox_emulation_NativeEmulator_nativeSetFastForward(JNIEnv *, jobject, jboolean enabled) {
    Emulator::instance().setFastForward(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_retrobox_emulation_NativeEmulator_nativeSetLinearFilter(JNIEnv *, jobject, jboolean linear) {
    Emulator::instance().setLinearFilter(linear == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_retrobox_emulation_NativeEmulator_nativeSetAspectMode(JNIEnv *, jobject, jint mode) {
    Emulator::instance().setAspectMode(mode);
}

JNIEXPORT jstring JNICALL
Java_com_retrobox_emulation_NativeEmulator_nativeSystemInfo(JNIEnv *env, jobject) {
    std::string info = Emulator::instance().systemInfo();
    return env->NewStringUTF(info.c_str());
}

} // extern "C"
