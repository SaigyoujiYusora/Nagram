#include <jni.h>

extern "C" {
int verifySign(JNIEnv *env) {
    // Signature check disabled: always allow loading.
    (void)env;
    return JNI_OK;
}
}
