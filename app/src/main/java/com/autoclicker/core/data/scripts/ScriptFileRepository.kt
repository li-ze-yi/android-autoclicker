package com.autoclicker.core.data.scripts

import android.content.Context
import android.util.Log
import com.autoclicker.domain.codec.ScriptCodec
import com.autoclicker.domain.model.Script
import com.autoclicker.domain.validate.StructureValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 脚本文件仓库：脚本以 JSON 存于内部存储 filesDir/scripts/<id>.json。
 *
 * 读写一律走 [ScriptCodec]（schemaVersion 信封）；写入前经
 * [StructureValidator.validateScript] 校验。全部 IO 在 [Dispatchers.IO]。
 */
class ScriptFileRepository(context: Context) {

    private val appContext = context.applicationContext

    private val scriptDir: File by lazy {
        File(appContext.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }
    }

    /** 列出全部脚本（按更新时间/名称，坏文件跳过不崩溃） */
    suspend fun list(): List<Script> = withContext(Dispatchers.IO) {
        val files = scriptDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            ?: return@withContext emptyList()
        files.mapNotNull { file ->
            runCatching { ScriptCodec.decode(file.readText(Charsets.UTF_8)) }
                .onFailure { Log.w(TAG, "脚本文件损坏，已跳过：${file.name}") }
                .getOrNull()
        }.sortedBy { it.name }
    }

    /** 按 ID 获取脚本 */
    suspend fun get(id: String): Script? = withContext(Dispatchers.IO) {
        val file = fileOf(id)
        if (!file.isFile) return@withContext null
        runCatching { ScriptCodec.decode(file.readText(Charsets.UTF_8)) }
            .onFailure { Log.w(TAG, "脚本读取失败：$id") }
            .getOrNull()
    }

    /**
     * 新增或更新脚本。
     * @throws IllegalArgumentException 结构校验失败（消息含中文原因）
     */
    suspend fun upsert(script: Script): Unit = withContext(Dispatchers.IO) {
        when (val result = StructureValidator.validateScript(script)) {
            is StructureValidator.Result.Valid -> Unit
            is StructureValidator.Result.Invalid ->
                throw IllegalArgumentException(
                    "脚本保存失败：\n" + result.reasons.joinToString("\n") { "· $it" }
                )
        }
        scriptDir.mkdirs()
        val target = fileOf(script.id)
        val tmp = File(scriptDir, "${script.id}.${UUID.randomUUID()}.tmp")
        try {
            tmp.writeText(ScriptCodec.encode(script), Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                target.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
            }
        } finally {
            tmp.delete()
        }
    }

    /** 删除脚本 */
    suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        fileOf(id).delete(); Unit
    }

    /** 生成新脚本 ID */
    fun newId(): String = "script-${UUID.randomUUID()}"

    private fun fileOf(id: String): File = File(scriptDir, "$id.json")

    private companion object {
        const val DIR_NAME = "scripts"
        const val TAG = "ScriptFileRepo"
    }
}
