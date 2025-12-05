package com.xyoye.common_component.log

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.io.File

internal class TestLogContext(private val dir: File) :
    ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
    override fun getFilesDir(): File = dir
}
