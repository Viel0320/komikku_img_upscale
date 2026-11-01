package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.text.TextUtils
import android.widget.TextView
import androidx.core.view.isVisible
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancer
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.kmk.KMR

/**
 * Resolves the status label for the actively processed page from its enhancement phase:
 * model preparation, native inference (with live percentage) or cache encoding.
 * Returns null when the page is not being processed so callers can fall back to
 * the queued/raw labels (upstream 1.3.17 status distinction).
 */
internal fun Context.activeEnhancementLabel(
    mangaId: Long,
    chapterId: Long,
    pageIndex: Int,
    pageVariant: String,
): String? {
    return when (ImageEnhancer.processingPhase(mangaId, chapterId, pageIndex, pageVariant)) {
        ImageEnhancer.ProcessingPhase.PREPARING -> stringResource(KMR.strings.reader_status_preparing)
        ImageEnhancer.ProcessingPhase.SAVING -> stringResource(KMR.strings.reader_status_finishing)
        ImageEnhancer.ProcessingPhase.ENHANCING -> {
            val progress = Waifu2x.getProgressPercent()
            if (progress in 0..100) {
                stringResource(KMR.strings.reader_status_enhancing_progress, progress)
            } else {
                stringResource(KMR.strings.reader_status_enhancing)
            }
        }

        null -> null
    }
}

/** Progress polling must not re-layout or reorder a label whose contents have not changed. */
internal fun TextView.updateEnhancementStatus(label: String?) {
    if (!label.isNullOrEmpty() && !TextUtils.equals(text, label)) {
        text = label
    }
    val show = !label.isNullOrEmpty()
    if (isVisible != show) isVisible = show
}
