package com.nevergrowold.holdhightheshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the physical invariants of our Material 3 Expressive spring tokens
 * and timing constants so regressions toward sluggish or overly wobbly values
 * are caught immediately.
 */
class MotionSpecsTest {

    @Test
    fun effectsSpringsAreCriticallyDampedAndOrdered() {
        assertEquals(1.0f, Motion.Effects.FAST.dampingRatio, 0.0001f)
        assertEquals(1.0f, Motion.Effects.DEFAULT.dampingRatio, 0.0001f)
        assertEquals(1.0f, Motion.Effects.SLOW.dampingRatio, 0.0001f)

        assertTrue(Motion.Effects.FAST.stiffness > Motion.Effects.DEFAULT.stiffness)
        assertTrue(Motion.Effects.DEFAULT.stiffness > Motion.Effects.SLOW.stiffness)
    }

    @Test
    fun spatialSpringsAreTautAndOrderedByResponsiveness() {
        // Exits must be at least as brisk as standard enters.
        assertTrue(Motion.Spatial.EXIT.stiffness > Motion.Spatial.STANDARD.stiffness)
        assertTrue(Motion.Spatial.EXIT.dampingRatio >= Motion.Spatial.STANDARD.dampingRatio)

        // Micro-interactions are crisper than tactile button rebounds, tracked pills, and larger transitions.
        assertTrue(Motion.Spatial.SNAPPY.stiffness > Motion.Spatial.TACTILE.stiffness)
        assertTrue(Motion.Spatial.TACTILE.stiffness > Motion.Spatial.RESPONSIVE.stiffness)
        assertTrue(Motion.Spatial.RESPONSIVE.stiffness > Motion.Spatial.STANDARD.stiffness)

        // No spatial spring should be overly sluggish (< 350) or cartoonishly underdamped (< 0.58).
        val allSpatial = listOf(
            Motion.Spatial.SNAPPY,
            Motion.Spatial.TACTILE,
            Motion.Spatial.RESPONSIVE,
            Motion.Spatial.STANDARD,
            Motion.Spatial.EXIT,
            Motion.Spatial.EXPRESSIVE,
        )
        allSpatial.forEach { spec ->
            assertTrue("Stiffness too slow: ${spec.stiffness}", spec.stiffness >= 350f)
            assertTrue("Damping too wobbly: ${spec.dampingRatio}", spec.dampingRatio in 0.58f..0.96f)
        }
    }

    @Test
    fun uiTimingConstantsStayCrisp() {
        assertTrue(Motion.STATE_MS in 100L..180L)
        assertTrue(Motion.ENTER_MS in 160L..260L)
        assertTrue(Motion.STAGGER_MS in 15L..40L)
    }
}
