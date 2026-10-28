package com.xyoye.storage_component.ui.activities.storage_plus

import com.alibaba.android.arouter.facade.annotation.Autowired
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.common_component.base.BaseActivity
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.storage.credential.MediaLibraryCredentialResolver
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.storage_component.BR
import com.xyoye.storage_component.R
import com.xyoye.storage_component.databinding.ActivityStoragePlusBinding
import com.xyoye.storage_component.ui.dialog.AlistStorageEditDialog
import com.xyoye.storage_component.ui.dialog.BaiduPanStorageEditDialog
import com.xyoye.storage_component.ui.dialog.Cloud115StorageEditDialog
import com.xyoye.storage_component.ui.dialog.ExternalStorageEditDialog
import com.xyoye.storage_component.ui.dialog.FTPStorageEditDialog
import com.xyoye.storage_component.ui.dialog.Open115StorageEditDialog
import com.xyoye.storage_component.ui.dialog.RemoteStorageEditDialog
import com.xyoye.storage_component.ui.dialog.SmbStorageEditDialog
import com.xyoye.storage_component.ui.dialog.StorageEditDialog
import com.xyoye.storage_component.ui.dialog.WebDavStorageEditDialog
import kotlinx.coroutines.Job

@Route(path = RouteTable.Stream.StoragePlus)
class StoragePlusActivity : BaseActivity<StoragePlusViewModel, ActivityStoragePlusBinding>() {
    @Autowired
    @JvmField
    var mediaType: MediaType? = null

    @Autowired
    @JvmField
    var editData: MediaLibraryEntity? = null

    private var storageEditDialog: StorageEditDialog<*>? = null

    override fun initViewModel() =
        ViewModelInit(
            BR.viewModel,
            StoragePlusViewModel::class.java,
        )

    override fun getLayoutId() = R.layout.activity_storage_plus

    override fun initView() {
        ARouter.getInstance().inject(this)

        if (checkBundle().not()) {
            finish()
            return
        }

        initObserver()

        showDialog()
    }

    override fun onDestroy() {
        storageEditDialog?.dismiss()
        super.onDestroy()
    }

    private fun checkBundle(): Boolean {
        mediaType ?: return false
        return true
    }

    private fun initObserver() {
        viewModel.storageSavedLiveData.observe(this) {
            editData = it
            setResult(RESULT_OK)
        }

        viewModel.testLiveData.observe(this) {
            if (storageEditDialog?.isShowing == true) {
                storageEditDialog?.onTestResult(it)
            }
        }
    }

    private fun showDialog() {
        val resolvedEditData = editData?.let { MediaLibraryCredentialResolver.resolve(it) }
        val dialog =
            when (mediaType) {
                MediaType.EXTERNAL_STORAGE -> ExternalStorageEditDialog(this, resolvedEditData)
                MediaType.REMOTE_STORAGE -> RemoteStorageEditDialog(this, resolvedEditData)
                MediaType.FTP_SERVER -> FTPStorageEditDialog(this, resolvedEditData)
                MediaType.WEBDAV_SERVER -> WebDavStorageEditDialog(this, resolvedEditData)
                MediaType.SMB_SERVER -> SmbStorageEditDialog(this, resolvedEditData)
                MediaType.SCREEN_CAST -> {
                    ToastCenter.showWarning("电视端不支持发起投屏发送")
                    finish()
                    null
                }
                MediaType.ALSIT_STORAGE -> AlistStorageEditDialog(this, resolvedEditData)
                MediaType.BAIDU_PAN_STORAGE -> BaiduPanStorageEditDialog(this, resolvedEditData)
                MediaType.OPEN_115_STORAGE -> Open115StorageEditDialog(this, resolvedEditData)
                MediaType.CLOUD_115_STORAGE -> Cloud115StorageEditDialog(this, resolvedEditData)
                else -> {
                    finish()
                    null
                }
            } ?: return

        dialog.show()
        storageEditDialog = dialog
    }

    fun addStorage(library: MediaLibraryEntity): Job = viewModel.addStorage(editData, library)

    fun addStorage(
        library: MediaLibraryEntity,
        showToast: Boolean
    ): Job = viewModel.addStorage(editData, library, showToast)

    fun testStorage(library: MediaLibraryEntity) {
        viewModel.testStorage(library)
    }
}
