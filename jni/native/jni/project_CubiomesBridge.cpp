#include "project_CubiomesBridge.h"
#include "../../SwampHutPhase1.h"

JNIEXPORT jboolean JNICALL Java_project_CubiomesBridge_nativeIsSwampBiome
  (JNIEnv *, jclass, jlong seed, jint hutX, jint hutZ,
   jint gameVersion, jint worldPreset)
{
    int ok = swampHutIsSwampBiome((uint64_t)seed, (int)hutX, (int)hutZ,
                                  (int)gameVersion, (int)worldPreset);
    return ok ? JNI_TRUE : JNI_FALSE;
}
