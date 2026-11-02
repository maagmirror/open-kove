package dev.zanderp.opencfmoto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GhostTouchTest {

    @Test
    fun near_samePoint() {
        assertTrue(EasyConnProber.near(100, 200, 100, 200))
    }

    @Test
    fun near_withinDefaultTolerance() {
        assertTrue(EasyConnProber.near(100, 200, 130, 230))
        assertTrue(EasyConnProber.near(100, 200, 52, 200))
    }

    @Test
    fun near_outsideDefaultTolerance() {
        assertFalse(EasyConnProber.near(100, 200, 149, 200))
        assertFalse(EasyConnProber.near(100, 200, 100, 249))
    }

    @Test
    fun near_customTolerance() {
        assertTrue(EasyConnProber.near(0, 0, 150, 150, tol = 150))
        assertFalse(EasyConnProber.near(0, 0, 151, 0, tol = 150))
    }
}
