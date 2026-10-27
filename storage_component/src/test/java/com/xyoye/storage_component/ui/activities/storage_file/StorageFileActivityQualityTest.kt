package com.xyoye.storage_component.ui.activities.storage_file

import android.app.Activity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageFileActivityQualityTest {
    private companion object {
        private const val REQUEST_CODE_OPEN115_REAUTH = 3302
        private const val REQUEST_CODE_CLOUD115_REAUTH = 3303
    }

    @Test
    fun shouldRefreshAfterReauthAcceptsBothCloud115AndOpen115SuccessOnly() {
        assertTrue(StorageFileActivity.shouldRefreshAfterReauth(REQUEST_CODE_OPEN115_REAUTH, Activity.RESULT_OK))
        assertTrue(StorageFileActivity.shouldRefreshAfterReauth(REQUEST_CODE_CLOUD115_REAUTH, Activity.RESULT_OK))

        assertFalse(StorageFileActivity.shouldRefreshAfterReauth(REQUEST_CODE_OPEN115_REAUTH, Activity.RESULT_CANCELED))
        assertFalse(StorageFileActivity.shouldRefreshAfterReauth(REQUEST_CODE_CLOUD115_REAUTH, Activity.RESULT_CANCELED))
        assertFalse(StorageFileActivity.shouldRefreshAfterReauth(9999, Activity.RESULT_OK))
    }
}
