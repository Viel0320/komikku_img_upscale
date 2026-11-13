package eu.kanade.tachiyomi.util.qnn

import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import io.mockk.every
import io.mockk.mockkObject
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QnnPrecisionPolicyTest {
    @Test
    fun `risky models retain FP16 without an INT8 asset or option`() {
        val models = buildList {
            add("realesrgan-general-x4v3-x2")
            add("span-nomosuni-x2")
            add("w2xex-photo-small-x2")
            for (noise in listOf("no-denoise", "denoise1x", "denoise2x", "denoise3x", "conservative")) {
                add("realcugan-se-x2-$noise")
            }
            for (scale in listOf(2, 3)) {
                for (noise in listOf("no-denoise", "denoise3x", "conservative")) {
                    add("realcugan-pro-x$scale-$noise")
                }
            }
        }
        assertEquals(14, models.size)
        for (model in models) {
            assertEquals(listOf(0), QnnModelContexts.supportedPrecisions(model))
            assertEquals(model, QnnModelContexts.assetName(model, 0))
            assertThrows(IllegalArgumentException::class.java) { QnnModelContexts.assetName(model, 2) }
        }
        assertEquals(listOf(0), QnnModelContexts.supportedPrecisions(null))
        assertEquals(listOf(0), QnnModelContexts.supportedPrecisions("unknown"))
    }

    @Test
    fun `Anime exposes a separate W8A8 asset and preparation state`() {
        val model = "realesrgan-animevideov3-x2"
        assertEquals(listOf(0, 2), QnnModelContexts.supportedPrecisions(model))
        assertEquals("$model-w8a8", QnnModelContexts.assetName(model, 2))
        assertNotEquals(QnnModelContexts.stateKey(model, 0), QnnModelContexts.stateKey(model, 2))
    }

    @Test
    fun `retired W8A16 precision migrates to INT8 for an INT8-capable model`() {
        // Upstream 1.3.17 beta 2: value 4 was the on-device W8A16 experiment; readers that
        // stored it keep their INT8 choice only where an INT8 asset exists.
        assertEquals(2, Waifu2x.resolvePrecision(4, 1, Waifu2x.MODEL_REAL_ESRGAN_ANIME, 2))
        // No INT8 asset (no 3x Anime model, Photo style, other models): the retired
        // value degrades to FP16.
        assertEquals(0, Waifu2x.resolvePrecision(4, 1, Waifu2x.MODEL_REAL_ESRGAN_ANIME, 3))
        assertEquals(0, Waifu2x.resolvePrecision(4, 1, Waifu2x.MODEL_REAL_ESRGAN_ANIME, 2, 1))
        assertEquals(0, Waifu2x.resolvePrecision(4, 1, 0, 2))
        // Non-NPU backends never accepted value 4.
        assertEquals(0, Waifu2x.resolvePrecision(4, 0, Waifu2x.MODEL_REAL_ESRGAN_ANIME, 2))
    }

    @Test
    fun `model style and backend gate INT8`() {
        assertEquals(2, Waifu2x.resolvePrecision(2, 1, 2, 2, 0))
        assertEquals(0, Waifu2x.resolvePrecision(2, 1, 2, 2, 1))
        assertEquals(0, Waifu2x.resolvePrecision(2, 1, 2, 3, 0))
        for (model in listOf(0, 1, 9, 19)) {
            assertEquals(0, Waifu2x.resolvePrecision(2, 1, model, 2))
        }
        assertEquals(0, Waifu2x.resolvePrecision(2, 0, 2, 2))
        assertEquals(0, Waifu2x.resolvePrecision(2, 2, 2, 2))
        assertEquals(1, Waifu2x.resolvePrecision(1, 0, 2, 2))
        assertEquals(3, Waifu2x.resolvePrecision(3, 0, 2, 2))
    }

    @Test
    fun `image cache distinguishes Anime INT8 but normalizes Photo to FP16`() {
        assertNotEquals(hash(0, 0), hash(0, 2))
        // Upstream 1.3.18: the retired W8A16 value and the INT8 request share the key.
        assertEquals(hash(0, 2), hash(0, 4))
        assertEquals(hash(1, 0), hash(1, 2))
        assertEquals(hash(1, 0), hash(1, 4))
    }

    @Test
    fun `QNN Anime drops legacy INT8 results and legacy FP16 skip markers`() {
        // Pre-1.3.18 page keys carried no _w8a8v revision: INT8 requests were demoted to
        // FP16 by the decoder, so both the legacy INT8 and legacy FP16 keys can hold
        // FP16 output or FP16 skip markers and must not match the regenerated keys.
        // This fork's hash has no upstream _ink suffix (ink filter not ported).
        val legacyKey = "0x2_m2_rs0_w0_h0_sw0_sh0_t128_p%s_fa0_b1_qv22_nf1_dv1_nv3"
        assertNotEquals(legacyKey.format(2), hash(0, 2))
        assertNotEquals(legacyKey.format(0), hash(0, 0))
        // Photo style has no W8A8 asset, so its key keeps the legacy shape.
        assertEquals("0x2_m2_rs1_w0_h0_sw0_sh0_t128_p0_fa0_b1_qv22_nf1_dv1_nv3", hash(1, 0))
    }

    @Test
    fun `reader still accepts the saved page after cold QNN fallback`() {
        // The page cache must not depend on the FP16 capability state: a page saved while
        // the device was known to be integer-only has to still match on a cold process
        // where the detection has not run yet.
        val fp16Supported = MutableStateFlow<Boolean?>(null)
        mockkObject(QnnModelContexts) {
            every { QnnModelContexts.fp16Supported } returns fp16Supported

            val saveKey = ImageEnhancementCache.CacheKey(1L, 2L, 0, hash(0, 0), "")
            val saved = ImageEnhancementCache.CacheEvent.Saved(saveKey)
            fp16Supported.value = false

            assertTrue(saved.affects(1L, 2L, 0, hash(0, 0), ""))
        }
    }

    private fun hash(style: Int, precision: Int) = ImageEnhancementCache.getConfigHash(
        noise = 0,
        scale = 2,
        model = Waifu2x.MODEL_REAL_ESRGAN_ANIME,
        realEsrganStyle = style,
        precision = precision,
        processingBackend = Waifu2x.PROCESSING_BACKEND_QUALCOMM_NPU,
    )
}
