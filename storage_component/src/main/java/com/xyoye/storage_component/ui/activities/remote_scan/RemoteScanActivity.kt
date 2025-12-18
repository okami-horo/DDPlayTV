package com.xyoye.storage_component.ui.activities.remote_scan

import com.alibaba.android.arouter.facade.annotation.Route
import com.gyf.immersionbar.ImmersionBar
import com.huawei.hms.hmsscankit.RemoteView
import com.xyoye.common_component.base.BaseActivity
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.storage_component.BR
import com.xyoye.storage_component.R
import com.xyoye.storage_component.databinding.ActivityRemoteScanBinding

/**
 * TV端说明：
 * - 该页面依赖设备摄像头及华为ScanKit，TV环境普遍无法提供。
 * - TV构建建议移除扫码入口，仅保留展示二维码让手机扫码的流程。
 */
@Route(path = RouteTable.Stream.RemoteScan)
class RemoteScanActivity : BaseActivity<RemoteScanViewModel, ActivityRemoteScanBinding>() {
    private lateinit var remoteView: RemoteView

    override fun initViewModel() =
        ViewModelInit(
            BR.viewModel,
            RemoteScanViewModel::class.java,
        )

    override fun getLayoutId() = R.layout.activity_remote_scan

    override fun initStatusBar() {
        // do nothing
    }

    override fun initView() {
        ImmersionBar
            .with(this)
            .titleBar(dataBinding.toolbar, false)
            .transparentBar()
            .statusBarDarkFont(false)
            .init()

        title = ""

        ToastCenter.showWarning("电视端不支持扫码功能")
        finish()
    }

    /*
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        remoteView = RemoteView.Builder()
            .setContext(this)
            .setBoundingBox(getScanRect())
            .setFormat(HmsScan.QRCODE_SCAN_TYPE)
            .build()
            .apply {
                onCreate(savedInstanceState)
                setOnResultCallback { result ->
                    if (result?.isNotEmpty() == true) {
                        if (result[0]?.originalValue?.isNotEmpty() == true) {
                            onScanResult(result[0].originalValue)
                        }
                    }
                }
            }

        val layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        dataBinding.scanContainer.addView(remoteView, layoutParams)
    }

    override fun onStart() {
        remoteView.onStart()
        super.onStart()
    }

    override fun onResume() {
        remoteView.onResume()
        super.onResume()
    }

    override fun onPause() {
        remoteView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        remoteView.onDestroy()
        super.onDestroy()
    }

    override fun onStop() {
        remoteView.onStop()
        super.onStop()
    }

    private fun onScanResult(scanResult: String) {
        var result = scanResult

        remoteView.pauseContinuouslyScan()

        //去除不可见字符
        if (!result.startsWith("{")) {
            result = result.substring(1)
        }

        //解析Json
        val remoteScanBean = JsonHelper.parseJson<RemoteScanData>(result)
        if (remoteScanBean == null) {
            lifecycleScope.launch {
                ToastCenter.showOriginalToast("无法识别的二维码")
                delay(1000L)
                remoteView.resumeContinuouslyScan()
            }
            return
        }

        //仅存在一个IP
        if (remoteScanBean.ip.size == 1) {
            remoteScanBean.selectedIP = remoteScanBean.ip[0]
            returnScanResult(remoteScanBean)
            return
        }

        //多个IP，选择其中一个
        AlertDialog.Builder(this)
            .setTitle("请选择合适的IP地址")
            .setItems(remoteScanBean.ip.toTypedArray()) { dialog, which ->
                dialog.dismiss()
                remoteScanBean.selectedIP = remoteScanBean.ip[which]
                returnScanResult(remoteScanBean)
            }.setNegativeButton("取消") { dialog, _ ->
                dialog.dismiss()
                remoteView.resumeContinuouslyScan()
            }.setCancelable(false)
            .create()
            .show()
    }

    private fun returnScanResult(remoteScanBean: RemoteScanData) {
        val intent = Intent()
        intent.putExtra("scan_data", remoteScanBean)
        setResult(RESULT_OK, intent)
        finish()
    }

    private fun getScanRect(): Rect {
        val width = getScreenWidth()
        val height = getScreenHeight()

        val frameSize = (min(width, height)) / 5f * 4f

        val bottom = (max(width, height) - frameSize) / 2f
        val right = (width - frameSize) / 2f
        val left = right + frameSize
        val top = bottom + frameSize

        return Rect(right.toInt(), bottom.toInt(), left.toInt(), top.toInt())
    }
     */
}
