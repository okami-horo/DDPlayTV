package com.xyoye.storage_component.ui.activities.screencast.receiver

import androidx.databinding.ObservableField
import androidx.lifecycle.viewModelScope
import com.xyoye.common_component.base.BaseViewModel
import com.xyoye.common_component.utils.ErrorReportHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException
import java.util.*

class ScreencastViewModel : BaseViewModel() {
    val displayIp = ObservableField<String>()

    val ipList = mutableListOf<String>()

    fun initIpPort() {
        viewModelScope.launch(Dispatchers.IO) {
            val ipAddresses = mutableListOf<String>()
            showLoading()
            try {
                val element = NetworkInterface.getNetworkInterfaces()
                while (element.hasMoreElements()) {
                    val networkInterface = element.nextElement()
                    val inetAddresses = networkInterface.inetAddresses
                    while (inetAddresses.hasMoreElements()) {
                        val inetAddress = inetAddresses.nextElement()
                        if (inetAddress.isLoopbackAddress || inetAddress.isLinkLocalAddress) {
                            continue
                        }

                        val ipAddress = inetAddress.hostAddress?.toString()
                        if (ipAddress == null || ipAddress.isEmpty()) {
                            continue
                        }
                        if (inetAddress is Inet4Address) {
                            ipAddresses.add(0, ipAddress)
                        } else {
                            ipAddresses.add(ipAddress)
                        }
                    }
                }
            } catch (e: SocketException) {
                // 上报网络接口获取异常
                ErrorReportHelper.postCatchedExceptionWithContext(
                    e,
                    "ScreencastViewModel",
                    "initIpPort",
                    "获取网络接口IP地址时发生Socket异常",
                )
                e.printStackTrace()
            }
            hideLoading()

            ipList.clear()
            ipList.addAll(ipAddresses)
            displayIp.set(ipAddresses.joinToString(separator = "\n"))
        }
    }

    fun createRandomPwd(): String {
        val uuid = UUID.randomUUID().toString()
        return uuid.substring(0, 8)
    }
}
