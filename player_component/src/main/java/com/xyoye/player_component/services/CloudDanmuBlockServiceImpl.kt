package com.xyoye.player_component.services

import android.content.Context
import com.alibaba.android.arouter.facade.annotation.Route
import com.xyoye.common_component.config.AppConfig
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.database.DatabaseManager
import com.xyoye.common_component.network.repository.OtherRepository
import com.xyoye.common_component.services.CloudDanmuBlockService
import com.xyoye.data_component.entity.DanmuBlockEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import java.io.InputStream
import java.util.Date
import javax.xml.parsers.DocumentBuilderFactory

@Route(path = RouteTable.Player.CloudDanmuBlockService, name = "云端屏蔽词同步 Service")
class CloudDanmuBlockServiceImpl : CloudDanmuBlockService {
    override fun init(context: Context?) {
    }

    override suspend fun syncIfNeed() {
        val lastUpdateTime = AppConfig.getCloudBlockUpdateTime()
        val currentTime = System.currentTimeMillis()

        if (currentTime - lastUpdateTime < UPDATE_INTERVAL_MS) {
            return
        }

        withContext(Dispatchers.IO) {
            val result = OtherRepository.getCloudFilters()
            val body = result.getOrNull() ?: return@withContext

            body.byteStream().use { stream ->
                val filterData = parseFilterData(stream)
                if (filterData.isEmpty()) {
                    return@use
                }
                saveFilterData(filterData)
                AppConfig.putCloudBlockUpdateTime(currentTime)
            }
        }
    }

    private fun parseFilterData(inputStream: InputStream): List<String> =
        runCatching {
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val document = builder.parse(inputStream)
            val nodeList = document.getElementsByTagName("FilterItem")

            val traditional = StringBuilder()
            val simplified = StringBuilder()

            for (index in 0 until nodeList.length) {
                val element = nodeList.item(index) as? Element ?: continue
                val tagName = element.getAttribute("Name")
                val content = element.textContent
                if (content.isNullOrBlank()) {
                    continue
                }

                if (tagName.endsWith("简体")) {
                    simplified.append(content)
                } else if (tagName.endsWith("繁体")) {
                    traditional.append(content)
                }
            }

            listOf(simplified.toString(), traditional.toString())
                .map { it.trim() }
                .filter { it.isNotBlank() }
        }.getOrElse {
            it.printStackTrace()
            emptyList()
        }

    private suspend fun saveFilterData(filterData: List<String>) {
        val blockEntities =
            filterData.map {
                DanmuBlockEntity(
                    0,
                    it,
                    true,
                    Date(),
                    true
                )
            }
        val dao = DatabaseManager.instance.getDanmuBlockDao()
        dao.deleteByType(true)
        dao.insert(*blockEntities.toTypedArray())
    }

    private companion object {
        private const val UPDATE_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
    }
}

