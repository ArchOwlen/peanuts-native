// Peanuts Native: the companion page's JNI (dev.peanuts.Bridge), built into libnxlaunch_<title ID>.so beside nxlaunch.c (android/app/src/main/cpp/CMakeLists.txt).
// The host's entry points are weak, as the launcher interface's are: a host without Peanuts gives an empty snapshot and no page.
#include <jni.h>
#include <stddef.h>

#pragma weak nxhost_peanuts_snapshot
size_t nxhost_peanuts_snapshot(char *out, size_t n);
#pragma weak nxhost_peanuts_frame_size
void nxhost_peanuts_frame_size(int *w, int *h);
#pragma weak nxhost_peanuts_frame
int nxhost_peanuts_frame(void *out_rgba, int w, int h);
#pragma weak nxhost_peanuts_touch
void nxhost_peanuts_touch(int id, float x, float y, int down);

JNIEXPORT jstring JNICALL Java_dev_peanuts_Bridge_snapshot(JNIEnv *env, jclass cls) {
    (void)cls;
    char buf[8192] = "";
    if (nxhost_peanuts_snapshot) nxhost_peanuts_snapshot(buf, sizeof buf);
    return (*env)->NewStringUTF(env, buf);
}

// The page's size as w << 16 | h (0 = no page)
JNIEXPORT jint JNICALL Java_dev_peanuts_Bridge_frameSize(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    int w = 0, h = 0;
    if (nxhost_peanuts_frame_size) nxhost_peanuts_frame_size(&w, &h);
    return w > 0 && h > 0 && w < 65536 && h < 65536 ? (w << 16) | h : 0;
}

// The newest frame into a direct ByteBuffer of at least w*h*4 bytes: true when a new frame was written
JNIEXPORT jboolean JNICALL Java_dev_peanuts_Bridge_frame(JNIEnv *env, jclass cls, jobject buffer, jint w, jint h) {
    (void)cls;
    void *p = (*env)->GetDirectBufferAddress(env, buffer);
    jlong cap = (*env)->GetDirectBufferCapacity(env, buffer);
    if (!p || cap < (jlong)w * h * 4 || !nxhost_peanuts_frame) return JNI_FALSE;
    return nxhost_peanuts_frame(p, w, h) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_dev_peanuts_Bridge_touch(JNIEnv *env, jclass cls, jint id, jfloat x, jfloat y, jboolean down) {
    (void)env; (void)cls;
    if (nxhost_peanuts_touch) nxhost_peanuts_touch(id, x, y, down ? 1 : 0);
}
