package eu.kanade.tachiyomi.util.qnn

import android.content.Context
import android.util.Log

// KMK --> Spatial depth was never ported here (docs/upscale-port-review.md §2-5);
// upstream 1.3.18 (299d82a5) dropped it from this comment too.
/** Runtime HTP capability detection for image enhancement. */
// KMK <--
object QualcommHtp {
    private val supportedArchitectures = setOf(69, 73, 75, 79, 81)

    @Volatile
    private var cachedArchitecture = UNQUERIED

    init {
        runCatching { System.loadLibrary("waifu2x-jni") }
    }

    fun architecture(context: Context): Int? = architecture(context.applicationInfo.nativeLibraryDir)

    fun architecture(): Int? = architecture("")

    private fun architecture(nativeLibraryDir: String): Int? {
        var architecture = cachedArchitecture
        if (architecture == UNQUERIED) {
            synchronized(this) {
                architecture = cachedArchitecture
                if (architecture == UNQUERIED) {
                    architecture = runCatching {
                        nativeArchitecture(nativeLibraryDir)
                    }.getOrDefault(UNKNOWN)
                    if (architecture != UNKNOWN) cachedArchitecture = architecture
                    Log.d(TAG, "Detected Qualcomm HTP architecture: v$architecture")
                }
            }
        }
        return architecture.takeIf(supportedArchitectures::contains)
    }

    private external fun nativeArchitecture(nativeLibraryDir: String): Int

    private const val TAG = "QualcommHtp"
    private const val UNQUERIED = -1
    private const val UNKNOWN = 0
}
