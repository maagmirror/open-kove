// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupTest {

    @Test
    fun formatAndVersionAreStable() {
        assertEquals("opencfmoto.settings", SettingsBackup.FORMAT)
        assertEquals(1, SettingsBackup.VERSION)
    }

    @Test
    fun buttonActionIdsRoundTrip() {
        for (a in ButtonAction.entries) {
            assertEquals(a, ButtonAction.byId(a.id))
        }
        for (g in ButtonGesture.entries) {
            assertEquals(g.default, ButtonAction.byId(g.default.id))
        }
    }

    @Test
    fun profileOverrideIdsRoundTrip() {
        for (p in ProfileOverride.entries) {
            assertEquals(p, ProfileOverride.byId(p.id))
        }
        assertTrue(ProfileOverride.byId("cfdl26_land") == ProfileOverride.CFDL26_LAND)
    }
}
