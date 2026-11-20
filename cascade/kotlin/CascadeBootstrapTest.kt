package com.v2ray.ang.handler

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.Utils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@RunWith(AndroidJUnit4::class)
class CascadeBootstrapTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun seedUserData() {
        SettingsManager.initApp(context)
        SettingsManager.initAssets(context, context.assets)
        assertTrue(MmkvManager.decodeAllServerList().isEmpty())
        assertEquals(24, MmkvManager.decodeRoutingRulesets()!!.size)
        assertEquals("IPOnDemand", MmkvManager.decodeSettingsString(AppConfig.PREF_ROUTING_DOMAIN_STRATEGY))
        assertTrue(Utils.isXray())
        assertEquals(SettingsManager.getSocksPort(), SettingsManager.getHttpPort())
        assertTrue(MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY))
        assertFalse(MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS))
        assertTrue(MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED))
        assertFalse(MmkvManager.decodeSettingsBool(AppConfig.PREF_FAKE_DNS_ENABLED))
        assertEquals("https://1.1.1.1/dns-query", MmkvManager.decodeSettingsString(AppConfig.PREF_REMOTE_DNS))
        assertEquals("https://1.1.1.1/dns-query", MmkvManager.decodeSettingsString(AppConfig.PREF_DOMESTIC_DNS))
        for (name in listOf("geoip.dat", "geosite.dat")) {
            assertTrue(File(Utils.userAssetPath(context), name).length() > 0)
        }
        verifyConcurrentAssetMutation()
        val link = "vless://00000000-0000-4000-8000-000000000001@127.0.0.1:443?encryption=none&security=tls&type=ws&host=example.invalid&path=%2F&sni=example.invalid#Cascade-test"
        assertEquals(1, AngConfigManager.importBatchConfig(link, AppConfig.DEFAULT_SUBSCRIPTION_ID, true).first)
        val guid = MmkvManager.decodeAllServerList().single()
        MmkvManager.setSelectServer(guid)
        verifyDnsConfig(guid)
        MmkvManager.encodeSettings("cascade_test_profile_guid", guid)
        MmkvManager.encodeSettings(AppConfig.PREF_REMOTE_DNS, "https://example.invalid/dns-query")
        val rules = MmkvManager.decodeRoutingRulesets()!!
        rules.first().remarks = "User routing retained"
        MmkvManager.encodeRoutingRulesets(rules)
        val selected = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)!!.toMutableSet()
        selected.add("com.android.settings")
        MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY_SET, selected)
        val geoip = MmkvManager.decodeAssetUrls().single { it.assetUrl.remarks == "geoip.dat" }
        MmkvManager.encodeAsset(geoip.guid, geoip.assetUrl.copy(url = "https://example.invalid/user-geoip.dat"))
        val geosite = MmkvManager.decodeAssetUrls().single { it.assetUrl.remarks == "geosite.dat" }
        MmkvManager.removeAssetUrl(geosite.guid)
        assertTrue(File(Utils.userAssetPath(context), "geosite.dat").delete())
        File(Utils.userAssetPath(context), "custom.dat").writeText("user asset")
    }

    @Test
    fun verifyAfterReinstall() {
        SettingsManager.initApp(context)
        SettingsManager.initAssets(context, context.assets)
        val guid = MmkvManager.decodeSettingsString("cascade_test_profile_guid")!!
        assertEquals(listOf(guid), MmkvManager.decodeAllServerList())
        assertEquals(guid, MmkvManager.getSelectServer())
        assertNotNull(MmkvManager.decodeServerConfig(guid))
        assertEquals("https://example.invalid/dns-query", MmkvManager.decodeSettingsString(AppConfig.PREF_REMOTE_DNS))
        assertEquals("User routing retained", MmkvManager.decodeRoutingRulesets()!!.first().remarks)
        assertTrue(MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)!!.contains("com.android.settings"))
        assertEquals("https://example.invalid/user-geoip.dat", MmkvManager.decodeAssetUrls().single { it.assetUrl.remarks == "geoip.dat" }.assetUrl.url)
        assertEquals(1, MmkvManager.decodeAssetUrls().count { it.assetUrl.remarks == "geosite.dat" })
        assertTrue(File(Utils.userAssetPath(context), "geosite.dat").length() > 0)
        assertEquals("user asset", File(Utils.userAssetPath(context), "custom.dat").readText())
    }

    @Test
    fun seedLegacyDnsForUpgrade() {
        // Persist the exact DNS values shipped by .1/.2, without a migration marker.
        MmkvManager.encodeSettings(AppConfig.PREF_LOCAL_DNS_ENABLED, false)
        MmkvManager.encodeSettings(AppConfig.PREF_REMOTE_DNS, "https://cloudflare-dns.com/dns-query")
        MmkvManager.encodeSettings(AppConfig.PREF_DOMESTIC_DNS, "223.5.5.5")
        MmkvManager.encodeSettings(AppConfig.PREF_FAKE_DNS_ENABLED, false)
        MmkvManager.encodeSettings(CascadeDefaults.DNS_MIGRATION_KEY, null as String?)
        assertEquals(-1, MmkvManager.decodeSettingsInt(CascadeDefaults.DNS_MIGRATION_KEY, -1))
    }

    @Test
    fun verifyAfterDnsUpgrade() {
        SettingsManager.initApp(context)
        assertTrue(MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED))
        assertFalse(MmkvManager.decodeSettingsBool(AppConfig.PREF_FAKE_DNS_ENABLED))
        assertEquals("https://1.1.1.1/dns-query", MmkvManager.decodeSettingsString(AppConfig.PREF_REMOTE_DNS))
        assertEquals("https://1.1.1.1/dns-query", MmkvManager.decodeSettingsString(AppConfig.PREF_DOMESTIC_DNS))
        val guid = MmkvManager.decodeSettingsString("cascade_test_profile_guid")!!
        assertEquals(listOf(guid), MmkvManager.decodeAllServerList())
        assertEquals(guid, MmkvManager.getSelectServer())
        assertNotNull(MmkvManager.decodeServerConfig(guid))
        assertEquals("User routing retained", MmkvManager.decodeRoutingRulesets()!!.first().remarks)
        assertTrue(MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)!!.contains("com.android.settings"))
        verifyDnsConfig(guid)
        // Once migrated, explicit later changes must survive another initialization.
        MmkvManager.encodeSettings(AppConfig.PREF_LOCAL_DNS_ENABLED, false)
        MmkvManager.encodeSettings(AppConfig.PREF_REMOTE_DNS, "https://example.invalid/after-upgrade")
        SettingsManager.initApp(context)
        assertFalse(MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED))
        assertEquals("https://example.invalid/after-upgrade", MmkvManager.decodeSettingsString(AppConfig.PREF_REMOTE_DNS))
    }

    private fun verifyDnsConfig(guid: String) {
        val result = CoreConfigManager.getV2rayConfig(context, guid)
        assertTrue(result.status)
        val config = JsonUtil.parseString(result.content)!!.asJsonObject
        val servers = config["dns"].asJsonObject["servers"].asJsonArray
        assertTrue(servers.size() > 0)
        assertTrue(servers.all {
            (if (it.isJsonPrimitive) it.asString else it.asJsonObject["address"].asString) ==
                "https://1.1.1.1/dns-query"
        })
        assertTrue(config["outbounds"].asJsonArray.any {
            it.asJsonObject["tag"].asString == "dns-out" && it.asJsonObject["protocol"].asString == "dns"
        })
        assertTrue(config["routing"].asJsonObject["rules"].asJsonArray.any {
            it.asJsonObject["outboundTag"]?.asString == "dns-out" &&
                it.asJsonObject["port"]?.asString == "53"
        })
    }

    private fun verifyConcurrentAssetMutation() {
        val directory = File(context.cacheDir, "cascade-asset-race").apply { mkdirs() }
        val target = File(directory, "test.dat")
        val staged = File(directory, "download.tmp").apply { writeText("manual update") }
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
            var committedTooSoon = false
            try {
                update.get(100, TimeUnit.MILLISECONDS)
                committedTooSoon = true
            } catch (expected: TimeoutException) {
                // The restore owns the common mutation lock until its file is complete.
            }
            assertFalse(committedTooSoon)
            releaseCopy.countDown()
            assertTrue(restore.get(5, TimeUnit.SECONDS))
            assertTrue(update.get(5, TimeUnit.SECONDS))
            assertEquals("manual update", target.readText())
        } finally {
            releaseCopy.countDown()
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
            staged.delete()
            target.delete()
            directory.delete()
        }
    }
}
