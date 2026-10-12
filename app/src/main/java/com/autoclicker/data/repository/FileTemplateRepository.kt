package com.autoclicker.data.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.autoclicker.core.bus.LogLevel
import com.autoclicker.core.bus.RuntimeBus
import com.autoclicker.domain.codec.DomainJson
import com.autoclicker.domain.model.Ids
import com.autoclicker.domain.model.ImageTemplate
import com.autoclicker.domain.model.PercentRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.FileOutputStream

/** 图像模板仓库的文件实现：PNG 素材 + index.json 索引。 */
class FileTemplateRepository(baseDir: File) : TemplateRepository {

    private val dir = File(baseDir, "templates")
    private val indexFile = File(dir, "index.json")
    private val listSerializer = ListSerializer(ImageTemplate.serializer())

    private fun readIndex(): List<ImageTemplate> {
        if (!indexFile.isFile) return emptyList()
        val text = indexFile.readText()
        return try {
            DomainJson.instance.decodeFromString(listSerializer, text)
        } catch (e: Exception) {
            // 不能静默返回空：后续任何一次保存都会以「空 + 新模板」写回，把全部模板元数据抹掉。
            // 这里先把损坏文件改名备份并明确报错，用户至少还能从备份里找回内容。
            val backup = File(dir, "index.corrupt-${System.currentTimeMillis()}.json")
            val backedUp = runCatching { indexFile.renameTo(backup) }.getOrDefault(false)
            RuntimeBus.log(
                LogLevel.ERROR,
                "模板索引解析失败：${e.message}；" +
                    if (backedUp) "已备份为 ${backup.name}" else "备份失败，请勿在此状态下继续保存模板",
            )
            emptyList()
        }
    }

    private fun writeIndex(list: List<ImageTemplate>) {
        if (!dir.exists()) dir.mkdirs()
        // 临时文件 + 原子替换：直接覆盖写在中途崩溃时会把索引写坏。
        val tmp = File(dir, "index.${System.nanoTime()}.tmp")
        tmp.writeText(DomainJson.instance.encodeToString(listSerializer, list))
        if (!tmp.renameTo(indexFile)) {
            indexFile.writeText(DomainJson.instance.encodeToString(listSerializer, list))
            tmp.delete()
        }
    }

    override suspend fun list(): List<ImageTemplate> = withContext(Dispatchers.IO) {
        readIndex().sortedBy { it.name }
    }

    override suspend fun get(id: String): ImageTemplate? = withContext(Dispatchers.IO) {
        readIndex().firstOrNull { it.id == id }
    }

    /**
     * 已解码模板位图的 LRU 缓存：模板文件不变，识别时没必要每次都「读索引 + 解码 PNG」。
     *
     * 按字节数限制总量；**淘汰时只丢引用、不 recycle**——返回的实例可能与界面共用，
     * 回收会让正在绘制/匹配的一方拿到已回收位图而崩溃。
     */
    private val bitmapCache = object : LruCache<String, Bitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    override suspend fun loadBitmap(id: String): Bitmap? = withContext(Dispatchers.IO) {
        bitmapCache.get(id)?.let { return@withContext it }
        val meta = readIndex().firstOrNull { it.id == id } ?: return@withContext null
        val file = File(dir, meta.fileName)
        if (!file.isFile) return@withContext null
        val decoded = BitmapFactory.decodeFile(file.absolutePath) ?: return@withContext null
        bitmapCache.put(id, decoded)
        decoded
    }

    override suspend fun save(
        name: String,
        bitmap: Bitmap,
        region: PercentRect?,
    ): ImageTemplate = withContext(Dispatchers.IO) {
        if (!dir.exists()) dir.mkdirs()
        val id = Ids.newId()
        val fileName = "$id.png"
        FileOutputStream(File(dir, fileName)).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        val meta = ImageTemplate(id, name, fileName, bitmap.width, bitmap.height, region = region)
        writeIndex(readIndex() + meta)
        meta
    }

    override suspend fun rename(id: String, name: String) = withContext(Dispatchers.IO) {
        writeIndex(readIndex().map { if (it.id == id) it.copy(name = name) else it })
    }

    // 显式声明 Unit：块尾的 bitmapCache.remove 会返回 Bitmap?，否则覆盖签名变成非 Unit。
    override suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        val list = readIndex()
        list.firstOrNull { it.id == id }?.let { File(dir, it.fileName).delete() }
        writeIndex(list.filterNot { it.id == id })
        // 模板已删除，缓存里的位图必须失效，否则同名/id 复用时拿到旧图。
        bitmapCache.remove(id)
    }

    private companion object {
        /** 模板位图缓存上限：模板通常是裁剪出的小图，8MB 足够放几十张。 */
        const val MAX_CACHE_BYTES = 8 * 1024 * 1024
    }
}