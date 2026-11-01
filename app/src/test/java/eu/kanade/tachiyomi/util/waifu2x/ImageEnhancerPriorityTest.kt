package eu.kanade.tachiyomi.util.waifu2x

import android.content.Context
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * JVM port of the ordering assertions in upstream's androidTest
 * ImageEnhancerQueueTest. The device-instrumented cases that enqueue real
 * work stay upstream-only; this covers the pure priority semantics that the
 * queue, pruning and preemption all depend on.
 */
class ImageEnhancerPriorityTest {

    private val context = mockk<Context>(relaxed = true)

    @BeforeEach
    fun resetFocus() {
        ImageEnhancer.reprioritizeAround(0)
    }

    @Test
    fun queueOrderFollowsVisibleSpreadPromotionThenDistance() {
        ImageEnhancer.reprioritizeAround(12, secondaryPageIndex = 11)
        val requests = listOf(20, 8, 11, 12, 13).mapIndexed { index, page ->
            request(page = page, priority = if (page == 8) 1 else 0, seq = index)
        }

        assertEquals(listOf(12, 11, 8, 13, 20), requests.sorted().map(ImageEnhancer.EnhanceRequest::pageIndex))
    }

    @Test
    fun equalPriorityTiesBreakByDistanceThenFifo() {
        ImageEnhancer.reprioritizeAround(12)
        val requests = listOf(
            request(page = 14, priority = 0, seq = 0),
            request(page = 10, priority = 0, seq = 1),
            request(page = 16, priority = 0, seq = 2),
        )

        assertEquals(listOf(14, 10, 16), requests.sorted().map(ImageEnhancer.EnhanceRequest::pageIndex))
    }

    @Test
    fun visiblePageOutranksPromotedPreload() {
        ImageEnhancer.reprioritizeAround(5)
        // Different pages on purpose: two requests for the same page would sort to
        // the same index and the assertion would hold no matter which one won.
        val promoted = request(page = 3, priority = 1, seq = 0)
        val visible = request(page = 5, priority = 0, seq = 1)

        assertEquals(listOf(5, 3), listOf(visible, promoted).sorted().map(ImageEnhancer.EnhanceRequest::pageIndex))
    }

    @Test
    fun secondarySpreadOutranksPromotedPreload() {
        ImageEnhancer.reprioritizeAround(5, secondaryPageIndex = 4)
        val secondary = request(page = 4, priority = 0, seq = 0)
        val promoted = request(page = 2, priority = 1, seq = 1)

        assertEquals(listOf(4, 2), listOf(secondary, promoted).sorted().map(ImageEnhancer.EnhanceRequest::pageIndex))
    }

    @Test
    fun focusedTargetsIncludeSecondarySpread() {
        ImageEnhancer.reprioritizeAround(5, secondaryPageIndex = 4)

        assertTrue(ImageEnhancer.isFocusedTarget(5))
        assertTrue(ImageEnhancer.isFocusedTarget(4))
        assertFalse(ImageEnhancer.isFocusedTarget(6))
        assertFalse(ImageEnhancer.isFocusedTarget(5, pageVariant = "wide_right"))
    }

    @Test
    fun processingPhaseIsNullWithoutActiveRequest() {
        // JVM port of upstream's androidTest completion cases that need a real active
        // request: the status contract that reader polling relies on is testable here.
        // With nothing active, the reader must fall back to its generic labels.
        assertFalse(ImageEnhancer.isActivelyProcessing(1L, 2L, 0, ""))
        assertNull(ImageEnhancer.processingPhase(1L, 2L, 0, ""))
    }

    @Test
    fun phaseUpdatesAreIgnoredWithoutActiveRequest() {
        // A stale phase write (for example from a decode that outlived its request) must
        // be a no-op, never resurrect state for a page that is not being processed.
        ImageEnhancer.updateProcessingPhase(1L, 2L, 0, "", ImageEnhancer.ProcessingPhase.ENHANCING)

        assertNull(ImageEnhancer.processingPhase(1L, 2L, 0, ""))
    }

    private fun request(page: Int, priority: Int, seq: Int): ImageEnhancer.EnhanceRequest {
        return ImageEnhancer.EnhanceRequest(
            context = context,
            mangaId = 1L,
            chapterId = 2L,
            pageIndex = page,
            pageVariant = "",
            dataProvider = { null },
            priority = priority,
            generation = 0,
            seq = seq,
        )
    }
}
