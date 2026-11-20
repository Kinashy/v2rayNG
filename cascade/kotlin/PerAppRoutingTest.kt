package com.v2ray.ang.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PerAppRoutingTest {
    private val self = "net.kinashy.cascadevpn"

    @Test
    fun selectedOnlyAllowsExistingSelectedAppsWithoutMutatingTheSelection() {
        val selected = setOf("installed", "missing", self)
        val allowed = mutableListOf<String>()
        assertTrue(configureSelectedApplications(true, false, selected, self,
            { allowed.add(it); it == "installed" }, { error("Unexpected bypass") }))
        assertEquals(listOf("installed", "missing"), allowed)
        assertEquals(setOf("installed", "missing", self), selected)
    }

    @Test
    fun emptySelfOnlyOrUninstalledSelectionCannotBecomeAnAllAppsVpn() {
        for (selection in listOf(emptySet(), setOf(self), setOf("missing"))) {
            assertFalse(configureSelectedApplications(true, false, selection, self, { false }, { error("Unexpected bypass") }))
        }
    }

    @Test
    fun disabledModeExcludesOnlyTheClient() {
        val excluded = mutableListOf<String>()
        assertTrue(configureSelectedApplications(false, false, setOf("user"), self,
            { error("Unexpected allow") }, { excluded.add(it); true }))
        assertEquals(listOf(self), excluded)
    }

    @Test
    fun bypassModeKeepsSelectedAppsAndSelfExcluded() {
        val excluded = mutableSetOf<String>()
        assertTrue(configureSelectedApplications(true, true, setOf("user"), self,
            { error("Unexpected allow") }, { excluded.add(it); true }))
        assertEquals(setOf("user", self), excluded)
    }

    @Test
    fun bypassModeStillExcludesSelfWhenAnOldSelectionIsUninstalled() {
        val attempted = mutableSetOf<String>()
        assertTrue(configureSelectedApplications(true, true, setOf("missing"), self,
            { error("Unexpected allow") }, { attempted.add(it); it == self }))
        assertEquals(setOf("missing", self), attempted)
    }
}
