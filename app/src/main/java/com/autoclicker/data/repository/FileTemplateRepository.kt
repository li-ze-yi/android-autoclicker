package com.autoclicker.data.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.autoclicker.domain.codec.DomainJson
import com.autoclicker.domain.model.Ids
import com.autoclicker.domain.model.ImageTemplate
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
        return runCatching {
            DomainJson.instance.decodeFromString(listSerializer, indexFile.readText())
        }.getOrDefault(emptyList())
    }

    private fun writeIndex(list: List<ImageTemplate>) {
        if (!dir.exists()) dir.mkdirs()
        indexFile.writeText(DomainJson.instance.encodeToString(listSerializer, list))
    }

    override suspend fun list(): List<ImageTemplate> = withContext(Dispatchers.IO) {
        readIndex().sortedBy { it.name }
    }

    override suspend fun get(id: String): ImageTemplate? = withContext(Dispatchers.IO) {
        readIndex().firstOrNull { it.id == id }
    }

    override suspend fun loadBitmap(id: String): Bitmap? = withContext(Dispatchers.IO) {
        val meta = readIndex().firstOrNull { it.id == id } ?: return@withContext null
        val file = File(dir, meta.fileName)
        if (!file.isFile) return@withContext null
        BitmapFactory.decodeFile(file.absolutePath)
    }

    override suspend fun save(name: String, bitmap: Bitmap): ImageTemplate = withContext(Dispatchers.IO) {
        if (!dir.exists()) dir.mkdirs()
        val id = Ids.newId()
        val fileName = "$id.png"
        FileOutputStream(File(dir, fileName)).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        val meta = ImageTemplate(id, name, fileName, bitmap.width, bitmap.height)
        writeIndex(readIndex() + meta)
        meta
    }

    override suspend fun rename(id: String, name: String) = withContext(Dispatchers.IO) {
        writeIndex(readIndex().map { if (it.id == id) it.copy(name = name) else it })
    }

    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val list = readIndex()
        list.firstOrNull { it.id == id }?.let { File(dir, it.fileName).delete() }
        writeIndex(list.filterNot { it.id == id })
    }
}