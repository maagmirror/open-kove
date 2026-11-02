package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KoveProfileTest {
    @Test
    fun marginsFillThePanelWithoutDistortion() {
        val (w, h) = KoveProfile.panelSize
        val m = AaMargins.forAspect(KoveProfile.aaVideo, w, h)
        assertEquals(0, m.marginW)
        assertEquals(128, m.marginH)
        assertEquals(h, KoveProfile.aaVideo.height - m.marginH)
    }

    @Test
    fun matchesOnlyItsOwnModelId() {
        assertTrue(KoveProfile.matchesModelId("21318"))
        assertFalse(KoveProfile.matchesModelId("37426"))
    }
}
