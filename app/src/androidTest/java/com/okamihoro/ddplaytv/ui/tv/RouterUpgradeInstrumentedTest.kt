package com.okamihoro.ddplaytv.ui.tv

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alibaba.android.arouter.launcher.ARouter
import com.alibaba.android.arouter.utils.Consts
import com.xyoye.common_component.services.PlaybackMetadataService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Run seedLegacyCache, then restart the process before checking the installed update. */
@RunWith(AndroidJUnit4::class)
class RouterUpgradeInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun requireUpgradeProbe() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("routerUpgradeProbe") == "true")
    }

    @Test fun seedLegacyCache() {
        assertFalse("This regression requires the Release router", ARouter.debuggable())
        val cache = context.getSharedPreferences(Consts.AROUTER_SP_CACHE_KEY, Context.MODE_PRIVATE)
        val routes = requireNotNull(cache.getStringSet(Consts.AROUTER_SP_KEY_MAP, null))
        assertTrue(routes.any { it.endsWith("core_metadata_component") })
        val version = context.packageManager.getPackageInfo(context.packageName, 0)
        assertTrue(
            cache
                .edit()
                .putStringSet(Consts.AROUTER_SP_KEY_MAP, routes.filterNot { it.endsWith("core_metadata_component") }.toSet())
                .putString(Consts.LAST_VERSION_NAME, version.versionName)
                .putInt(Consts.LAST_VERSION_CODE, version.versionCode)
                .remove("ddplaytv_last_update_time")
                .commit()
        )
        context
            .getSharedPreferences("router_upgrade_fixture", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("preserved", true)
            .commit()
    }

    @Test fun metadataServiceIsAvailableAfterSameVersionUpgrade() {
        assertFalse("Release must keep normal router mode", ARouter.debuggable())
        assertTrue(context.getSharedPreferences("router_upgrade_fixture", Context.MODE_PRIVATE).getBoolean("preserved", false))
        assertNotNull(
            "Playback metadata provider must survive a same-version upgrade",
            ARouter.getInstance().navigation(PlaybackMetadataService::class.java)
        )
    }
}
