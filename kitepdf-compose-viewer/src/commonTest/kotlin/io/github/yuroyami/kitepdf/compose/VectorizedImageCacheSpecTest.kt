package io.github.yuroyami.kitepdf.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VectorizedImageCacheSpecTest {
    private val decorator: KiteCanvasDecorator = { it }

    @Test
    fun legacy_construction_keeps_defaults_and_the_first_two_components() {
        val defaults = KiteRenderSpec.Vectorized()
        assertEquals(1f, defaults.hairlineWidthPx)
        assertNull(defaults.canvasDecorator)
        assertEquals(16L * 1024 * 1024, defaults.imageCacheBudgetBytes)
        assertEquals(defaults, KiteRenderSpec.Vectorized(1f, null))
        assertEquals(2f, KiteRenderSpec.Vectorized(hairlineWidthPx = 2f).hairlineWidthPx)
        assertSame(decorator, KiteRenderSpec.Vectorized(canvasDecorator = decorator).canvasDecorator)

        val configured = KiteRenderSpec.Vectorized(2f, decorator)
        val (hairline, wrapper) = configured
        assertEquals(2f, hairline)
        assertSame(decorator, wrapper)
        assertEquals(16L * 1024 * 1024, configured.imageCacheBudgetBytes)
    }

    @Test
    fun existing_copy_arguments_preserve_a_custom_budget() {
        val original = KiteRenderSpec.Vectorized(2f, decorator, 4_096L)
        assertEquals(original, original.copy())
        assertEquals(original, original.copy(2f, decorator))
        val moved = original.copy(hairlineWidthPx = 3f)
        assertEquals(3f, moved.hairlineWidthPx)
        assertSame(decorator, moved.canvasDecorator)
        assertEquals(4_096L, moved.imageCacheBudgetBytes)
        val unwrapped = original.copy(canvasDecorator = null)
        assertNull(unwrapped.canvasDecorator)
        assertEquals(2f, unwrapped.hairlineWidthPx)
        assertEquals(4_096L, unwrapped.imageCacheBudgetBytes)
    }

    @Test
    fun the_budget_can_be_copied_and_is_part_of_the_value() {
        val original = KiteRenderSpec.Vectorized(2f, decorator, 4_096L)
        val disabled = original.copy(imageCacheBudgetBytes = 0L)
        assertNotEquals(original, disabled)
        assertEquals(2f, disabled.hairlineWidthPx)
        assertSame(decorator, disabled.canvasDecorator)
        val (_, _, budget) = disabled
        assertEquals(0L, budget)
        assertTrue("imageCacheBudgetBytes=0" in disabled.toString())
        assertEquals(original, disabled.copy(imageCacheBudgetBytes = 4_096L))
    }

    @Test
    fun a_negative_budget_is_rejected_by_construction_and_copy() {
        assertEquals(0L, KiteRenderSpec.Vectorized(imageCacheBudgetBytes = 0L).imageCacheBudgetBytes)
        assertEquals(Long.MAX_VALUE, KiteRenderSpec.Vectorized(imageCacheBudgetBytes = Long.MAX_VALUE).imageCacheBudgetBytes)
        assertFailsWith<IllegalArgumentException> { KiteRenderSpec.Vectorized(imageCacheBudgetBytes = -1L) }
        assertFailsWith<IllegalArgumentException> { KiteRenderSpec.Vectorized().copy(imageCacheBudgetBytes = -1L) }
    }
}
