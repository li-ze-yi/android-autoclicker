package com.autoclicker.core.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 识图模板仓库：负责模板 PNG 与索引 index.json 的读写。
 *
 * 说明：模板名称允许重复，重复时不自动去重（便于用户保留多个同名模板）。
 * 所有文件操作均以 try/catch 包裹，失败时返回安全值，不抛出异常。
 */
class ImageTemplateRepository private constructor(context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: ImageTemplateRepository? = null

        fun get(context: Context): ImageTemplateRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ImageTemplateRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val indexSerializer = ListSerializer(ImageTemplate.serializer())

    /** 模板目录：filesDir/templates（确保 mkdirs）。 */
    val templatesDir: File = File(context.applicationContext.filesDir, "templates").apply { mkdirs() }

    private val indexFile: File = File(templatesDir, "index.json")

    /** 按 createdAt 倒序列出所有模板。 */
    fun list(): List<ImageTemplate> {
        return readIndex().sortedByDescending { it.createdAt }
    }

    fun load(id: String): ImageTemplate? {
        return try {
            readIndex().firstOrNull { it.id == id }
        } catch (e: Exception) {
            null
        }
    }

    /** 从 PNG 读回模板位图；失败返回 null。 */
    fun loadBitmap(template: ImageTemplate): Bitmap? {
        return try {
            val file = File(templatesDir, template.fileName)
            if (!file.exists()) return null
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            null
        }
    }

    /** 存 PNG 并更新 index.json，成功返回模板元数据，失败返回 null。 */
    fun save(name: String, bitmap: Bitmap): ImageTemplate? {
        return try {
            val id = UUID.randomUUID().toString()
            val fileName = "$id.png"
            val file = File(templatesDir, fileName)

            FileOutputStream(file).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    return null
                }
                out.flush()
            }

            val template = ImageTemplate(
                id = id,
                name = name,
                fileName = fileName,
                width = bitmap.width,
                height = bitmap.height
            )

            val updated = readIndex().toMutableList().apply { add(template) }
            if (!writeIndex(updated)) {
                // 索引写入失败则回滚图片文件，避免产生孤儿文件
                try {
                    file.delete()
                } catch (e: Exception) {
                    // 忽略
                }
                return null
            }
            template
        } catch (e: Exception) {
            null
        }
    }

    fun rename(id: String, newName: String): ImageTemplate? {
        return try {
            val index = readIndex().toMutableList()
            val position = index.indexOfFirst { it.id == id }
            if (position < 0) return null
            val renamed = index[position].copy(name = newName)
            index[position] = renamed
            if (!writeIndex(index)) return null
            renamed
        } catch (e: Exception) {
            null
        }
    }

    fun delete(id: String): Boolean {
        return try {
            val index = readIndex().toMutableList()
            val existing = index.firstOrNull { it.id == id } ?: return false
            if (!writeIndex(index.filterNot { it.id == id })) return false
            try {
                File(templatesDir, existing.fileName).delete()
            } catch (e: Exception) {
                // 图片删除失败不影响索引结果
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---- 索引读写 ----

    private fun readIndex(): List<ImageTemplate> {
        return try {
            if (!indexFile.exists()) return emptyList()
            val text = indexFile.readText()
            if (text.isBlank()) return emptyList()
            json.decodeFromString(indexSerializer, text)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeIndex(items: List<ImageTemplate>): Boolean {
        return try {
            indexFile.writeText(json.encodeToString(indexSerializer, items))
            true
        } catch (e: Exception) {
            false
        }
    }
}