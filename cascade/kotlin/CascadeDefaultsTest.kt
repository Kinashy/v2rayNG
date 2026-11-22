package com.v2ray.ang.handler

import com.v2ray.ang.util.Utils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class CascadeDefaultsTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun distributionUsesThePinnedXrayCoreWithItsOwnApplicationId() {
        assertTrue(Utils.isXray())
    }

    @Test
    fun repairsMissingDefaultsWithoutResettingUserChoices() {
        val stored = mutableMapOf<String, Any>("pref_per_app_proxy" to false, "pref_remote_dns" to "")
        fun repair() = CascadeDefaults.applySettings(
            stored.toMap(),
            { key, value -> stored[key] = value; true }, { stored.remove(it) }
        )
        repair()
        val first = stored.toMap()
        repair()
        assertEquals(first, stored)
        assertEquals(false, stored["pref_per_app_proxy"])
        assertEquals("", stored["pref_remote_dns"])
        assertEquals("IPOnDemand", stored["pref_routing_domain_strategy"])
        assertTrue((stored["pref_per_app_proxy_set"] as Set<*>).contains("com.microsoft.teams"))
        assertTrue(stored.keys.none { it.contains("password") || it.contains("subscription") })
    }

    private fun legacySettings(): MutableMap<String, Any> = CascadeDefaults.settings.toMutableMap().apply {
        this["pref_local_dns_enabled"] = false
        this["pref_remote_dns"] = "https://cloudflare-dns.com/dns-query"
        this["pref_domestic_dns"] = "223.5.5.5"
        this["pref_routing_ruleset"] = CascadeDefaults.previousRouting
        remove("pref_fake_dns_enabled")
    }

    private fun upgrade(stored: MutableMap<String, Any>) = CascadeDefaults.applySettings(
        stored.toMap(), { key, value -> stored[key] = value; true }, { stored.remove(it) }
    )

    @Test
    fun upgradesThePreviousReleaseDnsOnceAndKeepsOtherData() {
        val stored = legacySettings().apply {
            this["pref_per_app_proxy"] = false
            this["selected_server_fixture"] = "unchanged"
        }
        val originalApps = stored["pref_per_app_proxy_set"]
        upgrade(stored)
        assertEquals(true, stored["pref_local_dns_enabled"])
        assertEquals(false, stored["pref_fake_dns_enabled"])
        assertEquals("172.29.172.254", stored["pref_remote_dns"])
        assertEquals("https://8.8.8.8/dns-query", stored["pref_domestic_dns"])
        assertEquals("1.1.1.1", stored["pref_vpn_dns"])
        assertEquals(CascadeDefaults.DNS_MIGRATION_VERSION, stored[CascadeDefaults.DNS_MIGRATION_KEY])
        assertEquals(false, stored["pref_per_app_proxy"])
        assertEquals("unchanged", stored["selected_server_fixture"])
        assertEquals(CascadeDefaults.settings["pref_routing_ruleset"], stored["pref_routing_ruleset"])
        assertEquals(originalApps, stored["pref_per_app_proxy_set"])
        stored["pref_local_dns_enabled"] = false
        stored["pref_remote_dns"] = "https://example.invalid/custom-dns"
        val customized = stored.toMap()
        upgrade(stored)
        assertEquals(customized, stored)
    }

    @Test
    fun leavesACustomDnsTupleAndFutureMigrationVersionsAlone() {
        for ((key, value) in listOf(
            "pref_remote_dns" to "https://example.invalid/custom-dns",
            "pref_remote_dns" to "",
            "pref_domestic_dns" to "9.9.9.9",
            "pref_local_dns_enabled" to true,
            "pref_fake_dns_enabled" to true,
            CascadeDefaults.DNS_MIGRATION_KEY to 99
        )) {
            val stored = legacySettings().apply { this[key] = value }
            val before = stored.toMap()
            upgrade(stored)
            before.forEach { (oldKey, oldValue) -> assertEquals(oldValue, stored[oldKey]) }
            val firstUpgrade = stored.toMap()
            upgrade(stored)
            assertEquals(firstUpgrade, stored)
        }
    }

    @Test
    fun rollsBackEveryDnsWriteIncludingTheMigrationMarkerAndCanRetry() {
        for (throws in listOf(false, true)) {
            for (failedKey in listOf("pref_fake_dns_enabled", "pref_local_dns_enabled",
                "pref_remote_dns", "pref_domestic_dns", "pref_routing_ruleset", CascadeDefaults.DNS_MIGRATION_KEY)) {
                val stored = legacySettings()
                val before = stored.toMap()
                var failed = false
                assertThrows(Exception::class.java) {
                    CascadeDefaults.applySettings(before, { key, value ->
                        stored[key] = value
                        if (key == failedKey && !failed) {
                            failed = true
                            if (throws) throw IOException("Simulated write interruption")
                            false
                        } else true
                    }, { stored.remove(it) })
                }
                assertEquals(before, stored)
                upgrade(stored)
                assertEquals(true, stored["pref_local_dns_enabled"])
                assertEquals(CascadeDefaults.DNS_MIGRATION_VERSION, stored[CascadeDefaults.DNS_MIGRATION_KEY])
            }
        }
    }

    @Test
    fun upgradesCloudflareDefaultsFromRevisionThreeButKeepsCustomRouting() {
        val stored = legacySettings().apply {
            this["pref_local_dns_enabled"] = true
            this["pref_remote_dns"] = "https://1.1.1.1/dns-query"
            this["pref_domestic_dns"] = "https://1.1.1.1/dns-query"
            this[CascadeDefaults.DNS_MIGRATION_KEY] = 1
        }
        upgrade(stored)
        assertEquals("172.29.172.254", stored["pref_remote_dns"])
        assertEquals(CascadeDefaults.settings["pref_routing_ruleset"], stored["pref_routing_ruleset"])
        val customized = legacySettings().apply { this["pref_routing_ruleset"] = "[]" }
        upgrade(customized)
        assertEquals("[]", customized["pref_routing_ruleset"])
        assertEquals("https://cloudflare-dns.com/dns-query", customized["pref_remote_dns"])
    }

    @Test
    fun failedTransactionRemovesOnlyItsNewKeys() {
        val stored = mutableMapOf("user" to "keep")
        assertThrows(IllegalStateException::class.java) {
            CascadeDefaults.addMissing(stored.keys.toSet(), linkedMapOf("a" to "1", "b" to "2"),
                { key, value -> stored[key] = value; key != "b" }, { stored.remove(it) })
        }
        assertEquals(mapOf("user" to "keep"), stored)
    }

    @Test
    fun failedWriterAlsoRollsBackEarlierKeys() {
        val stored = mutableMapOf("user" to "keep")
        assertThrows(IOException::class.java) {
            CascadeDefaults.addMissing(stored.keys.toSet(), linkedMapOf("a" to "1", "b" to "2"),
                { key, value -> if (key == "b") throw IOException("test"); stored[key] = value; true },
                { stored.remove(it) })
        }
        assertEquals(mapOf("user" to "keep"), stored)
    }

    @Test
    fun addsOnlyMissingAssetSourcesAndKeepsCustomNames() {
        assertEquals(setOf("geosite.dat"), CascadeDefaults.missingAssets(listOf("geoip.dat", "custom.dat")).keys)
        assertTrue(CascadeDefaults.missingAssets(listOf("geoip.dat", "geosite.dat", "custom.dat")).isEmpty())
    }

    @Test
    fun restoresMissingFileAndKeepsExistingContents() {
        val target = File(directory, "geoip.dat")
        assertTrue(CascadeDefaults.copyMissingAsset(target) { "bundled".byteInputStream() })
        target.writeText("user-version")
        assertFalse(CascadeDefaults.copyMissingAsset(target) { error("Must not open bundled asset") })
        assertEquals("user-version", target.readText())
        target.delete()
        assertTrue(CascadeDefaults.copyMissingAsset(target) { "bundled".byteInputStream() })
        assertEquals("bundled", target.readText())
    }

    @Test
    fun interruptedCopyLeavesNoFileAndCanBeRetried() {
        val target = File(directory, "geosite.dat")
        assertThrows(IOException::class.java) {
            CascadeDefaults.copyMissingAsset(target) {
                object : InputStream() { override fun read(): Int = throw IOException("test") }
            }
        }
        assertFalse(target.exists())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        assertTrue(CascadeDefaults.copyMissingAsset(target) { "complete".byteInputStream() })
    }

    @Test
    fun failedManualReplacementKeepsTheExistingTarget() {
        val target = File(directory, "directory.dat").apply { mkdir() }
        val existing = File(target, "user").apply { writeText("preserved") }
        val staged = File(directory, "download.tmp").apply { writeText("new") }
        assertFalse(CascadeDefaults.replaceAssetFile(staged, target))
        assertEquals("preserved", existing.readText())
        assertFalse(staged.exists())
    }

    @Test
    fun restoreAfterAManualUpdateDoesNotReadOrReplaceTheBundledFile() {
        val target = File(directory, "geoip.dat")
        val staged = File(directory, "download.tmp").apply { writeText("user update") }
        assertTrue(CascadeDefaults.replaceAssetFile(staged, target))
        assertFalse(CascadeDefaults.copyMissingAsset(target) { error("Must not restore over an update") })
        assertEquals("user update", target.readText())
    }

    @Test
    fun manualCommitWaitsUntilAnInProgressRestoreFinishes() {
        val target = File(directory, "geosite.dat")
        val staged = File(directory, "download.tmp").apply { writeText("user update") }
        val copyEntered = CountDownLatch(1)
        val releaseCopy = CountDownLatch(1)
        val commitEntered = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val restore = pool.submit<Boolean> {
                CascadeDefaults.copyMissingAsset(target) {
                    copyEntered.countDown()
                    check(releaseCopy.await(5, TimeUnit.SECONDS))
                    "bundled".byteInputStream()
                }
            }
            assertTrue(copyEntered.await(5, TimeUnit.SECONDS))
            val update = pool.submit<Boolean> {
                commitEntered.countDown()
                CascadeDefaults.replaceAssetFile(staged, target)
            }
            assertTrue(commitEntered.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { update.get(100, TimeUnit.MILLISECONDS) }
            releaseCopy.countDown()
            assertTrue(restore.get(5, TimeUnit.SECONDS))
            val replaced = update.get(5, TimeUnit.SECONDS)
            // Android/Linux can replace an existing target; Windows may reject the rename.
            assertEquals(if (replaced) "user update" else "bundled", target.readText())
            assertFalse(staged.exists())
        } finally {
            releaseCopy.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
