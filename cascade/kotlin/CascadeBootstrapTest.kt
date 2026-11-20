package com.v2ray.ang.handler

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v2ray.ang.AppConfig
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
        for (name in listOf("geoip.dat", "geosite.dat")) {
            assertTrue(File(Utils.userAssetPath(context), name).length() > 0)
        }
        verifyConcurrentAssetMutation()
        val link = "vless://00000000-0000-4000-8000-000000000001@example.invalid:443?encryption=none&security=tls&type=ws&host=example.invalid&path=%2F&sni=example.invalid#Cascade-test"
        assertEquals(1, AngConfigManager.importBatchConfig(link, AppConfig.DEFAULT_SUBSCRIPTION_ID, true).first)
        val guid = MmkvManager.decodeAllServerList().single()
        MmkvManager.setSelectServer(guid)
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
