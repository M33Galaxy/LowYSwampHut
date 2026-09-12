#include "project_CubiomesBridge.h"
#include "../../SwampHutPhase1.h"

JNIEXPORT jintArray JNICALL Java_project_CubiomesBridge_nativeGetHutInRegion
  (JNIEnv *env, jclass, jlong seed, jint regX, jint regZ, jint gameVersion)
{
    int x = 0, z = 0;
    if (!swampHutPosInRegion((uint64_t)seed, (int)regX, (int)regZ, (int)gameVersion, &x, &z)) {
        return nullptr;
    }
    jintArray arr = env->NewIntArray(2);
    if (!arr) return nullptr;
    jint vals[2] = {x, z};
    env->SetIntArrayRegion(arr, 0, 2, vals);
    return arr;
}

JNIEXPORT jintArray JNICALL Java_project_CubiomesBridge_nativeClimateRegion
  (JNIEnv *env, jclass, jlong seed, jint regX, jint regZ,
   jint gameVersion, jint worldPreset)
{
    int x = 0, z = 0;
    if (!swampHutClimateRegion((uint64_t)seed, (int)regX, (int)regZ,
                               (int)gameVersion, (int)worldPreset, &x, &z)) {
        return nullptr;
    }
    jintArray arr = env->NewIntArray(2);
    if (!arr) return nullptr;
    jint vals[2] = {x, z};
    env->SetIntArrayRegion(arr, 0, 2, vals);
    return arr;
}

JNIEXPORT jboolean JNICALL Java_project_CubiomesBridge_nativeClimateFilter
  (JNIEnv *, jclass, jlong seed, jint hutX, jint hutZ,
   jint gameVersion, jint worldPreset)
{
    int ok = swampHutClimateFilter((uint64_t)seed, (int)hutX, (int)hutZ,
                                   (int)gameVersion, (int)worldPreset);
    return ok ? JNI_TRUE : JNI_FALSE;
}
