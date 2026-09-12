@file:Suppress("UNUSED", "KotlinConstantConditions")

package eu.kanade.tachiyomi.util.system

import eu.kanade.tachiyomi.BuildConfig

val telemetryIncluded: Boolean
    inline get() = BuildConfig.TELEMETRY_INCLUDED

val updaterEnabled: Boolean
    inline get() = BuildConfig.UPDATER_ENABLED

val isDebugBuildType: Boolean
    inline get() = BuildConfig.BUILD_TYPE == "debug"

val isPreviewBuildType: Boolean
    // KMK -->
    inline get() = BuildConfig.BUILD_TYPE == "preview" || BuildConfig.BUILD_TYPE == "viel"
// KMK <--

// KMK -->
/**
 * `viel` is a fork-only build type (same as `preview`, but with its own application id) that is
 * published from this fork. Its updates and changelogs point at [eu.kanade.tachiyomi.data.updater.VIEL_REPO].
 */
val isVielBuildType: Boolean
    inline get() = BuildConfig.BUILD_TYPE == "viel"
// KMK <--

val isReleaseBuildType: Boolean
    inline get() = BuildConfig.BUILD_TYPE == "release"

val isFossBuildType: Boolean
    inline get() = BuildConfig.BUILD_TYPE == "foss"
