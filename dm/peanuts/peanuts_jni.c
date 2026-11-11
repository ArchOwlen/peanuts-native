// Peanuts Native: the companion page's JNI (dev.peanuts.Bridge), built into libnxlaunch_<title ID>.so beside nxlaunch.c (android/app/src/main/cpp/CMakeLists.txt).
// The host's entry point is weak, as the launcher interface's are: a host without Peanuts gives an empty snapshot.
#include <jni.h>
#include <stddef.h>

#pragma weak nxhost_peanuts_snapshot
size_t nxhost_peanuts_snapshot(char *out, size_t n);

JNIEXPORT jstring JNICALL Java_dev_peanuts_Bridge_snapshot(JNIEnv *env, jclass cls) {
    (void)cls;
    char buf[4096] = "";
    if (nxhost_peanuts_snapshot) nxhost_peanuts_snapshot(buf, sizeof buf);
    return (*env)->NewStringUTF(env, buf);
}
