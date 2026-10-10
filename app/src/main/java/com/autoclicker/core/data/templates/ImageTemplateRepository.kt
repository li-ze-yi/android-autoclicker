package com.autoclicker.core.data.templates

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * 识图模板元数据（对应 FR-7）。
 *
 * 每个模板由两份文件组成：
 * - `<id>.png`：模板图片（无损 PNG）；
 * - `<id>.json`：本类序列化后的元数据（id / 名称 / 宽高）。
 *
 * @property width 模板图片宽度（像素）
 * @property height 模板图片高度（像素）
 */
@Serializable
data class ImageTemplateMetadata(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
)

/**
 * 识图模板仓库（FR-7 / Task 11）。
 *
 * 存储位置：应用内部存储 `filesDir/templates/<id>.png` 与 `<id>.json`。
 * 全部文件读写均在 [Dispatchers.IO] 执行；元数据 JSON 允许出现未知字段
 * （向前兼容），但 JSON 损坏 / PNG 缺失的条目一律跳过，不导致列表崩溃。
 *
 * @param context 任意 Context，构造时统一取 applicationContext，避免 Activity 泄漏
 */
class ImageTemplateRepository(context: Context) {

    private val appContext: Context = context.applicationContext

    /** 模板元数据 JSON 解析器（忽略未知键以提升兼容性，默认值照样写入） */
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 模板存放目录（懒加载并按需创建） */
    private val templateDir: File by lazy {
        File(appContext.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }
    }

    /**
     * 列出全部模板元数据，按名称升序排序。
     *
     * 跳过条件（仅记录警告日志，不抛异常）：
     * - JSON 文件损坏 / 无法解析；
     * - 元数据声明的 id 与文件名不一致；
     * - 同名 PNG 图片不存在（孤儿元数据）。
     */
    suspend fun list(): List<ImageTemplateMetadata> = withContext(Dispatchers.IO) {
        val files = templateDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            ?: return@withContext emptyList()
        files.mapNotNull { file ->
            val metadata = runCatching {
                json.decodeFromString(
                    ImageTemplateMetadata.serializer(),
                    file.readText(Charsets.UTF_8),
                )
            }.onFailure { e ->
                Log.w(TAG, "模板元数据损坏，已跳过：${file.name}（${e.message}）")
            }.getOrNull() ?: return@mapNotNull null

            // id 与文件名不一致或 PNG 缺失：视为损坏条目
            val expectedId = file.nameWithoutExtension
            if (metadata.id != expectedId || !pngFile(metadata.id).isFile) {
                Log.w(TAG, "模板文件不完整，已跳过：${file.name}")
                null
            } else {
                metadata
            }
        }.sortedBy { it.name }
    }

    /**
     * 按 ID 获取模板元数据。
     * @return 元数据；文件不存在 / 内容损坏 / PNG 缺失时返回 null
     */
    suspend fun get(id: String): ImageTemplateMetadata? = withContext(Dispatchers.IO) {
        val file = metaFile(id)
        if (!file.isFile || !pngFile(id).isFile) return@withContext null
        runCatching {
            json.decodeFromString(
                ImageTemplateMetadata.serializer(),
                file.readText(Charsets.UTF_8),
            )
        }.onFailure { Log.w(TAG, "模板元数据读取失败：$id（${it.message}）") }
            .getOrNull()
            ?.takeIf { it.id == id }
    }

    /**
     * 按 ID 解码模板原图 Bitmap（调用方负责回收）。
     * @return 模板位图；文件不存在或解码失败时返回 null
     */
    suspend fun loadBitmap(id: String): Bitmap? = withContext(Dispatchers.IO) {
        val file = pngFile(id)
        if (!file.isFile) return@withContext null
        runCatching { BitmapFactory.decodeFile(file.absolutePath) }
            .onFailure { Log.w(TAG, "模板图片解码失败：$id（${it.message}）") }
            .getOrNull()
    }

    /**
     * 按 ID 解码模板缩略图（调用方负责回收）。
     *
     * 先只解析边界尺寸，再按 2 的幂计算 [BitmapFactory.Options.inSampleSize]，
     * 使解码结果长边尽量接近 [targetEdgePx]，避免列表加载整张大图造成内存压力。
     *
     * @param targetEdgePx 期望的长边像素（如 2 倍 56dp≈112px）
     */
    suspend fun loadThumbnail(id: String, targetEdgePx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val file = pngFile(id)
        if (!file.isFile) return@withContext null
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            var longEdge = maxOf(bounds.outWidth, bounds.outHeight)
            while (longEdge / 2 >= targetEdgePx && longEdge >= 2) {
                longEdge /= 2
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(file.absolutePath, options)
        }.onFailure { Log.w(TAG, "模板缩略图解码失败：$id（${it.message}）") }
            .getOrNull()
    }

    /**
     * 新增或覆盖保存模板：以无损 PNG 写入图片，并写入与之一致的 JSON 元数据。
     *
     * 图片与元数据均采用「临时文件 + 重命名」方式落盘，尽量避免半写损坏；
     * 宽高以 [bitmap] 实际尺寸为准。
     *
     * @param id 模板 ID
     * @param name 模板显示名称
     * @param bitmap 模板位图（本方法不回收，由调用方管理生命周期）
     */
    suspend fun save(id: String, name: String, bitmap: Bitmap): Unit = withContext(Dispatchers.IO) {
        templateDir.mkdirs()
        val targetPng = pngFile(id)
        val tmpPng = File(templateDir, "$id.${UUID.randomUUID()}.png.tmp")
        try {
            tmpPng.outputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)) {
                    throw java.io.IOException("PNG 编码失败")
                }
            }
            if (!tmpPng.renameTo(targetPng)) {
                // 个别 ROM rename 失败时退化为直接覆盖
                targetPng.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
                }
            }
        } finally {
            tmpPng.delete()
        }

        // 图片落盘成功后再写元数据
        val metadata = ImageTemplateMetadata(
            id = id,
            name = name,
            width = bitmap.width,
            height = bitmap.height,
        )
        val targetMeta = metaFile(id)
        val tmpMeta = File(templateDir, "$id.${UUID.randomUUID()}.json.tmp")
        try {
            tmpMeta.writeText(
                json.encodeToString(ImageTemplateMetadata.serializer(), metadata),
                Charsets.UTF_8,
            )
            if (!tmpMeta.renameTo(targetMeta)) {
                targetMeta.writeText(tmpMeta.readText(Charsets.UTF_8), Charsets.UTF_8)
            }
        } finally {
            tmpMeta.delete()
        }
    }

    /**
     * 仅重命名模板（保留图片与 ID）。
     * @throws IllegalStateException 模板不存在时抛出（中文消息）
     */
    suspend fun rename(id: String, newName: String): Unit = withContext(Dispatchers.IO) {
        val current = get(id)
            ?: throw IllegalStateException("模板不存在或已损坏，无法重命名")
        val targetMeta = metaFile(id)
        val tmpMeta = File(templateDir, "$id.${UUID.randomUUID()}.json.tmp")
        try {
            tmpMeta.writeText(
                json.encodeToString(
                    ImageTemplateMetadata.serializer(),
                    current.copy(name = newName),
                ),
                Charsets.UTF_8,
            )
            if (!tmpMeta.renameTo(targetMeta)) {
                targetMeta.writeText(tmpMeta.readText(Charsets.UTF_8), Charsets.UTF_8)
            }
        } finally {
            tmpMeta.delete()
        }
    }

    /** 删除模板（图片 + 元数据）；文件不存在时视为已删除，不抛异常 */
    suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        pngFile(id).delete()
        metaFile(id).delete()
        Unit
    }

    /** 判断指定 ID 的模板（图片 + 元数据）是否齐备 */
    suspend fun exists(id: String): Boolean = withContext(Dispatchers.IO) {
        pngFile(id).isFile && metaFile(id).isFile
    }

    /** 生成新模板 ID："tpl-" + UUID */
    fun newId(): String = "tpl-${UUID.randomUUID()}"

    /** 取该 id 对应的 PNG 文件 */
    private fun pngFile(id: String): File = File(templateDir, "$id.png")

    /** 取该 id 对应的 JSON 元数据文件 */
    private fun metaFile(id: String): File = File(templateDir, "$id.json")

    private companion object {
        const val DIR_NAME = "templates"
        const val TAG = "ImageTemplateRepo"

        /** PNG 为无损格式，质量参数固定 100 */
        const val PNG_QUALITY = 100
    }
}
