/* Force-included on C++ compiles (see build-jni.bat).
 * cubiomes rng.h defines lerp(); C++20 <math.h> does using std::lerp.
 */
#ifdef __cplusplus
#define lerp cubiomes_lerp
#include "cubiomes/rng.h"
#undef lerp
#endif
