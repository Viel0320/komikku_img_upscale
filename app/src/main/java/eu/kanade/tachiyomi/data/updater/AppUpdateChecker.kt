package eu.kanade.tachiyomi.data.updater

import android.content.Context
import android.os.Build
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.system.isFossBuildType
import eu.kanade.tachiyomi.util.system.isPreviewBuildType
import eu.kanade.tachiyomi.util.system.isVielBuildType
import exh.source.ExhPreferences
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.service.AppUpdatePolicy
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class AppUpdateChecker(
    // VIEL -->
    private val peekIntoPreview: Boolean = false,
    // VIEL <--
) {

    private val getApplicationRelease: GetApplicationRelease by injectLazy()

    // VIEL -->
    private val exhPreferences by lazy { Injekt.get<ExhPreferences>() }
    // VIEL <--

    suspend fun checkForUpdate(
        context: Context,
        forceCheck: Boolean = false,
        // VIEL -->
        autoUpdate: Boolean = AppUpdatePolicy.DISABLE_AUTO_DOWNLOAD !in exhPreferences.appShouldAutoUpdate().get(),
        // VIEL <--
    ): GetApplicationRelease.Result {
        return withIOContext {
            val result = getApplicationRelease.await(
                GetApplicationRelease.Arguments(
                    isFoss = isFossBuildType,
                    isPreview = isPreviewBuildType || peekIntoPreview,
                    commitCount = BuildConfig.COMMIT_COUNT.toInt(),
                    versionName = BuildConfig.VERSION_NAME,
                    repository = getGithubRepo(peekIntoPreview),
                    forceCheck = forceCheck,
                ),
            )

            // VIEL -->
            if (!peekIntoPreview) {
                // VIEL <--
                when (result) {
                    is GetApplicationRelease.Result.NewUpdate -> {
                        // VIEL -->
                        AppUpdateNotifier.releasePageUrl = result.release.releaseLink
                        // VIEL <--
                        AppUpdateNotifier(context).promptUpdate(result.release)
                    }

                    else -> {}
                }

                // VIEL -->
                if (autoUpdate && result is GetApplicationRelease.Result.NewUpdate) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        AppUpdateDownloadJob.start(
                            context = context,
                            url = result.release.downloadLink,
                            title = result.release.version,
                            scheduled = true,
                        )
                    }
                }
                // VIEL <--
            }

            result
        }
    }

    // VIEL -->
    suspend fun getReleaseNotes(): GetApplicationRelease.Result {
        return withIOContext {
            getApplicationRelease.awaitReleaseNotes(
                GetApplicationRelease.Arguments(
                    isFoss = isFossBuildType,
                    isPreview = isPreviewBuildType || peekIntoPreview,
                    commitCount = BuildConfig.COMMIT_COUNT.toInt(),
                    versionName = BuildConfig.VERSION_NAME,
                    repository = getGithubRepo(peekIntoPreview),
                ),
            )
        }
    }
    // VIEL <--
}

val GITHUB_REPO: String by lazy { getGithubRepo() }

// VIEL -->
/**
 * Repository that hosts the releases of the `viel` build, which is published by the
 * `Viel Builder` workflow (`.github/workflows/build_viel.yml`) of this fork.
 * Keep in sync with the repository the workflow runs in.
 */
const val VIEL_REPO = "Viel0320/komikku_img_upscale"

fun getGithubRepo(peekIntoPreview: Boolean = false): String =
    when {
        // `viel` builds are updated from this fork's releases.
        isVielBuildType -> VIEL_REPO
        isPreviewBuildType || peekIntoPreview -> "komikku-app/komikku-preview"
        else -> "komikku-app/komikku"
    }
// VIEL <--

val RELEASE_TAG: String by lazy { getReleaseTag() }

fun getReleaseTag(peekIntoPreview: Boolean = false): String =
    if (isPreviewBuildType || peekIntoPreview) {
        "r${BuildConfig.COMMIT_COUNT}"
    } else {
        "v${BuildConfig.VERSION_NAME}"
    }

val RELEASE_URL = "https://github.com/$GITHUB_REPO/releases/tag/$RELEASE_TAG"
