package com.xyoye.common_component.database.migration

import android.content.Context
import androidx.startup.Initializer
import com.xyoye.common_component.base.app.BaseInitializer
import com.xyoye.common_component.utils.SupervisorScope
import kotlinx.coroutines.launch

class ManualMigrationInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        SupervisorScope.IO.launch {
            runCatching { ManualMigration.migrate() }
                .onFailure { it.printStackTrace() }
        }
    }

    override fun dependencies(): MutableList<Class<out Initializer<*>>> =
        mutableListOf(BaseInitializer::class.java)
}

